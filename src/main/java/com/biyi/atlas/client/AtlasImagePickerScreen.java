package com.biyi.atlas.client;

import com.biyi.atlas.BiyiAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 游戏内**图片选择器**：列出预设文件夹里的图片让你挑，不需要弹系统资源管理器。
 *
 * <p>预设文件夹：{@code <游戏目录>/biyi_atlas/import}（不存在会自动创建）。
 * 把 PNG/JPG 丢进去，然后在游戏里打开这个界面选即可。</p>
 */
public class AtlasImagePickerScreen extends Screen {

    /** 用途：作为标记图标（自定义路标图标）或作为地图大图（贴到地图上的图层）。 */
    public enum Purpose {
        MARKER_ICON("选择标记图标"),
        MAP_IMAGE("选择地图大图"),
        REGION_IMAGE("选择区域映像图");

        final String title;

        Purpose(String title) {
            this.title = title;
        }
    }

    private final Screen parent;
    private final Purpose purpose;
    private final Consumer<Path> onPickOverride;
    private final List<Path> files = new ArrayList<>();
    private FileList list;
    private String hint = "";

    public AtlasImagePickerScreen(Screen parent, Purpose purpose) {
        this(parent, purpose, null);
    }

    /** 带回调的构造：由调用方决定拿到文件后做什么（例如挂到某个区域上）。 */
    public AtlasImagePickerScreen(Screen parent, Purpose purpose, Consumer<Path> onPickOverride) {
        super(Component.literal("避役图志 · " + purpose.title));
        this.parent = parent;
        this.purpose = purpose;
        this.onPickOverride = onPickOverride;
    }

    /** 预设导入文件夹（自动创建）。 */
    public static Path importFolder() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(BiyiAtlas.MODID).resolve("import");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 建不出来就只读列出，不影响使用
        }
        return dir;
    }

    /** 列出可导入的图片（按文件名排序）。 */
    public static List<Path> listImages() {
        List<Path> result = new ArrayList<>();
        Path dir = importFolder();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(result::add);
        } catch (IOException ignored) {
            // 读不到就当空列表
        }
        return result;
    }

    @Override
    protected void init() {
        files.clear();
        files.addAll(listImages());
        int top = 40;
        int bottom = this.height - 48;
        list = new FileList(this.minecraft, this.width, this.height, top, bottom, 22);
        list.setLeftPos(this.width / 2 - 160);
        list.fill(files, this::pick);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.literal("刷新列表"), button -> {
            files.clear();
            files.addAll(listImages());
            list.fill(files, this::pick);
        }).bounds(this.width / 2 - 150, this.height - 36, 90, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(this.width / 2 + 60, this.height - 36, 90, 20).build());
        hint = files.isEmpty()
                ? "文件夹里还没有图片：" + importFolder()
                : "共 " + files.size() + " 张 · 文件夹：" + importFolder();
    }

    private void pick(Path path) {
        if (onPickOverride != null) {
            onPickOverride.accept(path);
            onClose();
            return;
        }
        if (purpose == Purpose.MARKER_ICON) {
            ExternalImageImport.reportForScreen(CustomIcons.importIcon(path));
        } else {
            ExternalImageImport.importFile(path);
        }
        onClose();
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = this.width / 2 - 170;
        int right = this.width / 2 + 170;
        graphics.fill(left, 18, right, this.height - 42, 0xFFE8D5A9);
        graphics.fill(left, 18, right, 20, 0xFF3A2A1B);
        graphics.fill(left, this.height - 44, right, this.height - 42, 0xFF3A2A1B);
        graphics.fill(left, 18, left + 2, this.height - 42, 0xFF3A2A1B);
        graphics.fill(right - 2, 18, right, this.height - 42, 0xFF3A2A1B);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFF3A2A1B);
        graphics.drawString(this.font, hint, left + 8, 34, 0xFF5A4632, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /** 可滚动文件列表（滚轮/滚动条由原版列表组件提供）。 */
    private static class FileList extends ObjectSelectionList<FileList.FileRow> {

        FileList(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
        }

        void fill(List<Path> files, Consumer<Path> onPick) {
            clearEntries();
            for (Path path : files) {
                addEntry(new FileRow(path, onPick));
            }
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 158;
        }

        private static class FileRow extends ObjectSelectionList.Entry<FileRow> {
            private final Path path;
            private final Consumer<Path> onPick;

            FileRow(Path path, Consumer<Path> onPick) {
                this.path = path;
                this.onPick = onPick;
            }

            @Override
            public void render(@NotNull GuiGraphics graphics, int index, int top, int left, int width,
                               int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
                String name = path.getFileName().toString();
                long size = 0L;
                try {
                    size = Files.size(path);
                } catch (IOException ignored) {
                    // 大小读不到就不显示
                }
                String text = (hovered ? "▶ " : "  ") + name + "   (" + (size / 1024) + " KB)";
                graphics.drawString(Minecraft.getInstance().font, text, left + 4,
                        top + (height - 8) / 2, hovered ? 0xFF3A2A1B : 0xFF5A4632, false);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                onPick.accept(path);
                return true;
            }

            @Override
            public @NotNull Component getNarration() {
                return Component.literal(path.getFileName().toString());
            }
        }
    }
}
