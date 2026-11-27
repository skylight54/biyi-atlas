package com.biyi.atlas.client;

import com.biyi.atlas.map.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Consumer;

final class EditorObjectTree extends Screen {
    private record Row(String label, Object target) {}
    private final EditorAtlasScreen editor;
    private final ResourceLocation dimension;
    private final int layer;
    private final Consumer<Object> select;
    private final List<Row> rows = new ArrayList<>();
    private int first;

    EditorObjectTree(EditorAtlasScreen editor, ResourceLocation dimension, int layer, Consumer<Object> select) {
        super(Component.literal("区域与对象"));
        this.editor = editor;
        this.dimension = dimension;
        this.layer = layer;
        this.select = select;
    }

    private void populate() {
        rows.clear();
        if (!editor.canResume()) return;
        var content = AtlasContent.get();
        var regions = content.regionsAt(dimension, layer);
        Map<String,List<Row>> objects = new LinkedHashMap<>();
        for (var place : content.placesAt(dimension, layer)) {
            String kind = "author_waypoint".equals(place.type()) ? "作者路标" : "共享地标";
            objects.computeIfAbsent(place.region(), k -> new ArrayList<>()).add(new Row(kind + " · " + place.name(), place));
        }
        for (var road : content.roadsAt(dimension, layer)) {
            objects.computeIfAbsent(road.region(), k -> new ArrayList<>()).add(new Row("道路 · " + road.name(), road));
        }
        Map<String,List<AtlasRegion>> children = new HashMap<>();
        for (var r : regions) children.computeIfAbsent(r.parent(), k -> new ArrayList<>()).add(r);
        Set<String> seen = new HashSet<>();
        for (var r : regions) if (!r.isChild()) append(r, 0, children, objects, seen);
        for (var r : regions) append(r, 0, children, objects, seen);
        objects.values().forEach(rows::addAll);
        for (var point : AtlasStorage.get(dimension).waypoints()) {
            if (point.layer() == layer) rows.add(new Row("私人路标 · " + point.name(), point));
        }
    }

    private void append(AtlasRegion region, int depth, Map<String,List<AtlasRegion>> children,
                        Map<String,List<Row>> objects, Set<String> seen) {
        if (!seen.add(region.id())) return;
        String indent = "  ".repeat(Math.min(depth, 6));
        rows.add(new Row(indent + (region.isChild() ? "子区" : "大区") + " · " + region.name(), region));
        var members = objects.remove(region.id());
        if (members != null) for (var row : members) rows.add(new Row(indent + "  " + row.label(), row.target()));
        for (var child : children.getOrDefault(region.id(), List.of())) append(child, depth + 1, children, objects, seen);
    }

    @Override
    protected void init() {
        populate();
        int count = Math.max(1, (height - 80) / 22);
        first = Math.max(0, Math.min(first, rows.size() - count));
        int w = Math.min(360, width - 24), x = (width - w) / 2;
        for (int i = first; i < Math.min(rows.size(), first + count); i++) {
            var row = rows.get(i);
            addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(row.label(), w - 12)), b -> {
                if (!editor.canResume()) { AtlasScreen.open(); return; }
                minecraft.setScreen(editor);
                select.accept(row.target());
            }).bounds(x, 36 + (i - first) * 22, w, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> page(-count))
                .bounds(x, height - 32, 58, 20).build()).active = first > 0;
        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> page(count))
                .bounds(x + 62, height - 32, 58, 20).build()).active = first + count < rows.size();
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(x + w - 58, height - 32, 58, 20).build());
    }

    private void page(int delta) { first += delta; rebuildWidgets(); }

    @Override
    public void tick() { if (!editor.canResume()) AtlasScreen.open(); }

    @Override
    public void onClose() {
        if (editor.canResume()) minecraft.setScreen(editor);
        else AtlasScreen.open();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float tick) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 14, 0xffece5d4);
        if (rows.isEmpty()) g.drawCenteredString(font, "本层暂无对象", width / 2, 42, 0xffece5d4);
        super.render(g, x, y, tick);
    }
}
