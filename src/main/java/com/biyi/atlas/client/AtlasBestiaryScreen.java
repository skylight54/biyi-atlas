package com.biyi.atlas.client;

import com.biyi.atlas.map.AtlasContent;
import com.biyi.atlas.map.AtlasRegion;
import com.biyi.atlas.map.BestiaryEntry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 区域怪物图鉴查看界面（解锁该区域后玩家也能看）。
 * 父区域会汇总**已解锁子区域**的条目，并标注来源；未解锁子区域只给数量提示。
 */
public class AtlasBestiaryScreen extends Screen {

    private static final int PER_PAGE = 5;

    /** 一条显示项：条目 + 来源子区域名（空 = 本区域自己的条目）。 */
    private record Row(BestiaryEntry entry, String source, String sourceId) {
    }

    private final Screen parent;
    private final AtlasRegion region;
    private final List<Row> rows = new ArrayList<>();
    private int lockedChildren;
    private int page;

    public AtlasBestiaryScreen(Screen parent, AtlasRegion region) {
        super(Component.literal("怪物图鉴 · " + region.name()));
        this.parent = parent;
        this.region = region;
        collect();
    }

    private void collect() {
        AtlasContent content = AtlasContent.get();
        for (BestiaryEntry entry : region.bestiary()) {
            rows.add(new Row(entry, "", region.id()));
        }
        for (AtlasRegion child : content.descendantsOf(region.id())) {
            if (!AtlasUnlocks.isVisible(child.id())) {
                lockedChildren++;
                continue;
            }
            for (BestiaryEntry entry : child.bestiary()) {
                rows.add(new Row(entry, child.name(), child.id()));
            }
        }
        // 稳定排序：来源区域 id → 条目名（分页内容不会乱跳）
        rows.sort(Comparator.comparing(Row::sourceId).thenComparing(row -> row.entry().name()));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int bottom = this.height - 40;
        addRenderableWidget(Button.builder(Component.literal("上一页"), button -> page = Math.max(0, page - 1))
                .bounds(centerX - 150, bottom, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下一页"),
                        button -> page = Math.min(maxPage(), page + 1))
                .bounds(centerX + 60, bottom, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), button -> onClose())
                .bounds(centerX - 45, bottom + 24, 90, 20).build());
    }

    private int maxPage() {
        return rows.isEmpty() ? 0 : (rows.size() - 1) / PER_PAGE;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        page = Math.min(page, maxPage());
        int centerX = this.width / 2;
        int top = 44;
        graphics.drawCenteredString(this.font, this.title, centerX, 16, 0xFFF4E7C6);
        int childCount = AtlasContent.get().childrenOf(region.id()).size();
        graphics.drawCenteredString(this.font,
                Component.literal("许可证 " + region.unlockId() + " · 共 " + rows.size() + " 种"
                        + (childCount > 0 ? "（含 " + childCount + " 个子区域）" : "")
                        + " · 第 " + (page + 1) + "/" + (maxPage() + 1) + " 页"),
                centerX, 30, 0xFFC4A46B);

        int index = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && index + i < rows.size(); i++) {
            Row row = rows.get(index + i);
            BestiaryEntry entry = row.entry();
            int y = top + i * 36;
            String head = "· " + entry.name()
                    + (entry.title().isEmpty() ? "" : "  「" + entry.title() + "」")
                    + (row.source().isEmpty() ? "" : "  ← 来自子区域：" + row.source());
            graphics.drawString(this.font, head, centerX - 200, y,
                    row.source().isEmpty() ? 0xFFE8D5A9 : 0xFFD9BF8C, false);
            String hp = entry.health() >= 0 ? "生命 " + entry.health() : "生命 ?";
            graphics.drawString(this.font, "  " + hp + " · 类型 " + shorten(entry.entityType(), this.width - 240),
                    centerX - 200, y + 11, 0xFFC4A46B, false);
            String drops = entry.drops().isEmpty() ? "掉落：未记录（可用图鉴魔杖重新右键）"
                    : "掉落：" + String.join("、", entry.drops());
            graphics.drawString(this.font, "  " + shorten(drops, this.width - 240), centerX - 200, y + 22,
                    0xFFB08A4A, false);
        }
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                    Component.literal("这个区域还没有怪物记录 —— 作者用图鉴魔杖右键 NPC 即可加入"),
                    centerX, this.height / 2 - 8, 0xFFC4A46B);
        }
        if (lockedChildren > 0) {
            graphics.drawCenteredString(this.font,
                    Component.literal("还有 " + lockedChildren + " 个子区域的图鉴未解锁"),
                    centerX, this.height - 60, 0xFF8A7A66);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String shorten(String text, int max) {
        if (this.font.width(text) <= max) {
            return text;
        }
        String result = text;
        while (result.length() > 4 && this.font.width(result + "…") > max) {
            result = result.substring(0, result.length() - 1);
        }
        return result + "…";
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0xC0241A10);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
