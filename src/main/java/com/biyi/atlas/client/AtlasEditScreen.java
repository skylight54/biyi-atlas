package com.biyi.atlas.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 通用"元素编辑"界面：支持多个文本字段 + 多个可循环切换的属性按钮，
 * 所以元素的名字、描述、层级、图标/类型、映像图等都能在这里改。
 */
public class AtlasEditScreen extends Screen {

    /** 文本字段（名称/描述/许可证 id…）。 */
    public record TextInput(String label, String initial) {
    }

    /** 可循环属性：显示当前值，点一下切到下一个。 */
    public record Cycle(String label, Supplier<String> value, Runnable action) {
    }

    private final Screen parent;
    private final List<String> infoLines;
    private final List<TextInput> inputs;
    private final List<Cycle> cycles;
    private final Consumer<Map<String, String>> onSave;
    private final List<EditBox> boxes = new ArrayList<>();
    private final Map<String,String> drafts=new LinkedHashMap<>();
    private int scrollOffset;
    private int contentTop;
    private int formLeft,formWidth,maxScroll;

    public AtlasEditScreen(Screen parent, String title, List<String> infoLines, List<TextInput> inputs,
                           List<Cycle> cycles, Consumer<Map<String, String>> onSave) {
        super(Component.literal(title));
        this.parent = parent;
        this.infoLines = infoLines;
        this.inputs = inputs;
        this.cycles = cycles;
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        for(int i=0;i<boxes.size()&&i<inputs.size();i++)drafts.put(inputs.get(i).label(),boxes.get(i).getValue());
        boxes.clear();
        formWidth=Math.min(360,this.width-48);
        formLeft=(this.width-formWidth)/2;
        int left = formLeft;
        contentTop=44+Math.min(2,infoLines.size())*12;
        maxScroll=Math.max(0,inputs.size()*36+cycles.size()*24-(this.height-contentTop-64));
        scrollOffset=Math.max(0,Math.min(maxScroll,scrollOffset));
        int top=contentTop-scrollOffset;
        this.panelTop = 8;
        for (TextInput input : inputs) {
            EditBox box = new EditBox(this.font, left, top + 12, formWidth, 20, Component.literal(input.label()));
            box.setMaxLength(96);
            box.setValue(drafts.getOrDefault(input.label(),input.initial() == null ? "" : input.initial()));
            box.visible=top>=contentTop&&top+32<=this.height-58;
            box.active=box.visible;
            addRenderableWidget(box);
            boxes.add(box);
            top += 36;
        }
        for (int i = 0; i < cycles.size(); i++) {
            Cycle cycle = cycles.get(i);
            int y = top + i * 24;
            Button cycleButton=Button.builder(
                            Component.literal(cycle.label() + "：" + cycle.value().get()),
                            button -> {
                                if (!mayEdit()) return;
                                cycle.action().run();
                                rebuildWidgets();
                            })
                    .bounds(left, y, formWidth, 20).build();
            cycleButton.visible=y>=contentTop&&y+20<=this.height-58;
            cycleButton.active=cycleButton.visible && mayEdit();
            addRenderableWidget(cycleButton);
        }
        int buttonsY = this.height-34;
        if (parent instanceof EditorAtlasScreen editor) {
            int third = (formWidth - 8) / 3;
            addRenderableWidget(Button.builder(Component.literal("保存文本"), b -> save())
                    .bounds(left, buttonsY, third, 20).build()).active = mayEdit();
            addRenderableWidget(Button.builder(Component.literal("玩家预览"), b -> editor.openPreview(this))
                    .bounds(left + third + 4, buttonsY, third, 20).build()).active = mayEdit();
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(left + 2 * third + 8, buttonsY, third, 20).build());
            updatePanelBottom(buttonsY);
            editable = mayEdit();
            return;
        }
        updatePanelBottom(buttonsY);
        addRenderableWidget(Button.builder(Component.literal("保存文本"), button -> save())
                .bounds(left, buttonsY, (formWidth-8)/2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(left + (formWidth+8)/2, buttonsY, (formWidth-8)/2, 20).build());
    }

    public void refreshLabels(){rebuildWidgets();}

    private boolean editable = true;

    private boolean mayEdit() {
        return !(parent instanceof EditorAtlasScreen editor) || editor.canResume();
    }

    @Override
    public void tick() {
        if (editable != mayEdit()) rebuildWidgets();
    }

    private void save() {
        if (!mayEdit()) return;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size() && i < boxes.size(); i++) {
            values.put(inputs.get(i).label(), boxes.get(i).getValue());
        }
        onSave.accept(values);
        onClose();
    }

    /** 面板高度在 init 里算出来（字段 + 按钮都装得下）。 */
    private void updatePanelBottom(int buttonsY) {
        this.panelBottom = Math.min(this.height - 8, buttonsY + 30);
    }

    private int panelTop;
    private int panelBottom;
    private static final int PANEL_HALF_WIDTH = 152;

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int centerX = this.width / 2;
        // 先画一个羊皮纸面板，让字段/按钮"在框里"
        int left = formLeft-12;
        int right = formLeft+formWidth+12;
        AtlasUiTheme.panel(graphics, left, panelTop, right - left, panelBottom - panelTop);

        graphics.drawCenteredString(this.font, this.title, centerX, panelTop + 8, 0xFF343F3E);
        int y = 28;
        for (String line : infoLines.subList(0,Math.min(2,infoLines.size()))) {
            graphics.drawCenteredString(this.font, Component.literal(font.plainSubstrByWidth(line,formWidth)), centerX, y, 0xFF65716C);
            y += 12;
        }
        int top = contentTop-scrollOffset;
        for (TextInput input : inputs) {
            if(top>=contentTop&&top+32<=this.height-58)graphics.drawString(this.font, input.label(), formLeft, top, 0xFF343F3E, false);
            top += 36;
        }
        if(maxScroll>0){
            int track=this.height-58-contentTop;
            int thumb=Math.max(12,track*track/(track+maxScroll));
            int sy=contentTop+(track-thumb)*scrollOffset/Math.max(1,maxScroll);
            AtlasUiTheme.rect(graphics,right-5,sy,2,thumb,0xFF8C9A94);
        }
        if (!mayEdit()) graphics.drawString(font,"编辑权限已暂停，文本草稿已保留",formLeft,this.height-46,0xff774b35,false);
        else if(!cycles.isEmpty())graphics.drawString(font,"切换项即时生效",formLeft,this.height-46,0xFF65716C,false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseScrolled(double x,double y,double delta){
        scrollOffset-= (int)(delta*24);rebuildWidgets();return true;
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        AtlasUiTheme.rect(graphics, 0, 0, this.width, this.height, 0xC02D3735);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            if (mayEdit()) this.minecraft.setScreen(parent);
            else AtlasScreen.open();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
