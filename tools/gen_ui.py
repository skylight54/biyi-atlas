"""生成避役图志的羊皮纸主题 UI 素材（零许可风险，全部自绘）。

配色来自调研：纸面/墨线/火漆红/烫金/传送蓝。
用法: python tools/gen_ui.py
输出: src/main/resources/assets/biyi_atlas/textures/ui/**
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "ui")
MARKER = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "atlas", "marker")

# ---- 调色板 ---------------------------------------------------------------
PAPER_LIGHT = (242, 228, 196, 255)
PAPER = (232, 213, 169, 255)
PAPER_SHADE = (217, 191, 140, 255)
AGED = (196, 164, 107, 255)
BURNT = (168, 135, 79, 255)
LEATHER = (107, 74, 42, 255)
INK = (58, 42, 27, 255)
DEEP_INK = (36, 26, 16, 255)
SECONDARY = (90, 70, 50, 255)
ROAD = (140, 106, 63, 255)
WAX = (168, 50, 45, 255)
GOLD = (212, 166, 74, 255)
PORTAL = (62, 110, 158, 255)
CLEAR = (0, 0, 0, 0)


def ensure(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path


def canvas(w: int, h: int) -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGBA", (w, h), CLEAR)
    return img, ImageDraw.Draw(img)


# ---- 通用控件 -------------------------------------------------------------
def make_frame(size: int = 32, border: int = 4) -> Image.Image:
    """9-slice 外框：皮革 + 墨线 + 纸面内沿。"""
    img, d = canvas(size, size)
    d.rectangle([0, 0, size - 1, size - 1], fill=LEATHER, outline=DEEP_INK)
    d.rectangle([1, 1, size - 2, size - 2], outline=AGED)
    d.rectangle([border, border, size - 1 - border, size - 1 - border], fill=PAPER, outline=INK)
    d.rectangle([border + 1, border + 1, size - 2 - border, size - 2 - border], outline=PAPER_LIGHT)
    return img


def make_banner(size: int = 48, height: int = 16) -> Image.Image:
    """9-slice 标题横幅：纸面 + 烫金描线 + 两端卷轴。"""
    img, d = canvas(size, height)
    d.rectangle([0, 0, size - 1, height - 1], fill=PAPER, outline=INK)
    d.line([0, 1, size - 1, 1], fill=PAPER_LIGHT)
    d.line([0, height - 2, size - 1, height - 2], fill=AGED)
    d.rectangle([0, 0, 2, height - 1], fill=LEATHER, outline=DEEP_INK)
    d.rectangle([size - 3, 0, size - 1, height - 1], fill=LEATHER, outline=DEEP_INK)
    d.line([3, height // 2, size - 4, height // 2], fill=GOLD)
    return img


def make_button(state: str) -> Image.Image:
    """按钮三态：default / hover / disabled。**不画中央方框**（会压到文字）。"""
    size = 20
    img, d = canvas(size, size)
    if state == "disabled":
        base, edge = AGED, AGED
    elif state == "hover":
        base, edge = PAPER_LIGHT, GOLD
    else:
        base, edge = PAPER_SHADE, INK
    d.rectangle([0, 0, size - 1, size - 1], fill=base, outline=DEEP_INK)
    # 只保留一条顶部高光 + 一条底部阴影，做出"纸片被按下去"的层次
    d.line([1, 1, size - 2, 1], fill=PAPER_LIGHT if state != "disabled" else AGED)
    d.line([1, size - 2, size - 2, size - 2], fill=BURNT if state != "disabled" else AGED)
    if state == "hover":
        d.line([1, 0, size - 2, 0], fill=GOLD)
    return img


def make_tab(selected: bool) -> Image.Image:
    size_w, size_h = 48, 16
    img, d = canvas(size_w, size_h)
    base = PAPER_LIGHT if selected else PAPER_SHADE
    edge = GOLD if selected else INK
    d.rectangle([0, 0, size_w - 1, size_h - 1], fill=base, outline=DEEP_INK)
    d.line([1, 1, size_w - 2, 1], fill=edge)
    d.line([1, size_h - 2, size_w - 2, size_h - 2], fill=AGED)
    if selected:
        d.line([2, size_h - 1, size_w - 3, size_h - 1], fill=GOLD)
    return img


def make_toolbar(size_w: int = 64, size_h: int = 12) -> Image.Image:
    img, d = canvas(size_w, size_h)
    d.rectangle([0, 0, size_w - 1, size_h - 1], fill=LEATHER, outline=DEEP_INK)
    d.line([1, 1, size_w - 2, 1], fill=BURNT)
    d.line([1, size_h - 2, size_w - 2, size_h - 2], fill=DEEP_INK)
    return img


def make_divider(size_w: int = 16, size_h: int = 4) -> Image.Image:
    img, d = canvas(size_w, size_h)
    d.line([0, 1, size_w - 1, 1], fill=AGED)
    d.line([0, 2, size_w - 1, 2], fill=INK)
    return img


def make_scroll_track(size_w: int = 8, size_h: int = 16) -> Image.Image:
    img, d = canvas(size_w, size_h)
    d.rectangle([0, 0, size_w - 1, size_h - 1], fill=PAPER_SHADE, outline=AGED)
    return img


def make_scroll_thumb(size_w: int = 8, size_h: int = 12) -> Image.Image:
    img, d = canvas(size_w, size_h)
    d.rectangle([0, 0, size_w - 1, size_h - 1], fill=AGED, outline=INK)
    d.line([1, 1, size_w - 2, 1], fill=PAPER_LIGHT)
    return img


def make_plate(size_w: int = 32, size_h: int = 12) -> Image.Image:
    """HUD 距离文字底衬（半透明纸片）。"""
    img, d = canvas(size_w, size_h)
    d.rectangle([0, 0, size_w - 1, size_h - 1], fill=(232, 213, 169, 204), outline=INK)
    return img


def make_compass(size: int = 32) -> Image.Image:
    """罗盘玫瑰：墨线 + 烫金指北 + 火漆红指针。"""
    img, d = canvas(size, size)
    c = (size - 1) / 2.0
    d.ellipse([1, 1, size - 2, size - 2], outline=INK)
    d.ellipse([3, 3, size - 4, size - 4], outline=AGED)
    # 四向尖角
    d.polygon([(c, 2), (c + 3, c), (c, size - 3), (c - 3, c)], fill=PAPER, outline=INK)
    d.polygon([(2, c), (c, c - 3), (size - 3, c), (c, c + 3)], fill=GOLD, outline=INK)
    d.polygon([(c, 2), (c + 2, c - 1), (c - 2, c - 1)], fill=WAX)
    return img


def make_arrow(direction: int, size: int = 16) -> Image.Image:
    """八向指路箭头（0=上/前，顺时针 45° 一档）。"""
    img, d = canvas(size, size)
    c = size // 2

    def pts(dx: float, dy: float) -> list[tuple[float, float]]:
        # 以"向上"为基准的箭头形状，再按方向旋转
        base = [(0, -6), (4, 1), (1.6, 1), (1.6, 6), (-1.6, 6), (-1.6, 1), (-4, 1)]
        import math
        rad = math.radians(direction * 45)
        cos_a, sin_a = math.cos(rad), math.sin(rad)
        return [(c + x * cos_a - y * sin_a, c + x * sin_a + y * cos_a) for x, y in base]

    poly = pts(0, 0)
    d.polygon(poly, fill=WAX, outline=DEEP_INK)
    return img


# ---- 地图图标（墨线 + 局部上色） ------------------------------------------
def icon_tree(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.rectangle([14, 20, 17, 28], fill=LEATHER, outline=INK)
    d.ellipse([7, 6, 24, 21], fill=(108, 128, 86, 255), outline=INK)
    d.ellipse([10, 9, 18, 16], fill=(134, 152, 104, 255))
    d.line([3, 28, 29, 28], fill=INK)
    return img


def icon_forest(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    for x, y, r in ((7, 22, 7), (16, 18, 8), (25, 23, 6)):
        d.polygon([(x, y - r * 2), (x + r, y), (x - r, y)], fill=(108, 128, 86, 255), outline=INK)
        d.rectangle([x - 2, y, x + 2, y + 5], fill=LEATHER, outline=INK)
    d.line([2, 28, 30, 28], fill=INK)
    return img


def icon_mountain(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.polygon([(3, 27), (13, 8), (22, 27)], fill=(150, 138, 116, 255), outline=INK)
    d.polygon([(16, 27), (24, 13), (30, 27)], fill=(168, 156, 134, 255), outline=INK)
    d.polygon([(13, 8), (17, 14), (9, 14)], fill=(240, 238, 230, 255), outline=INK)
    return img


def icon_bridge(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.line([2, 20, 30, 20], fill=LEATHER, width=3)
    d.line([2, 20, 30, 20], fill=INK)
    for x in (6, 14, 22):
        d.line([x, 20, x, 27], fill=LEATHER)
    d.arc([2, 12, 30, 24], 180, 360, fill=INK)
    return img


def icon_gate(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.rectangle([5, 6, 27, 26], fill=PAPER_SHADE, outline=INK)
    d.rectangle([11, 13, 21, 26], fill=DEEP_INK)
    d.rectangle([3, 3, 29, 8], fill=LEATHER, outline=INK)
    return img


def icon_wayshard(size: int = 16) -> Image.Image:
    """路标魔杖物品图标：带光环的小旗。"""
    img, d = canvas(size, size)
    d.line([6, 2, 6, 14], fill=LEATHER, width=2)
    d.polygon([(7, 3), (14, 5), (7, 8)], fill=WAX, outline=INK)
    d.ellipse([2, 9, 8, 15], outline=PORTAL, width=1)
    return img


def icon_town(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    # 城墙 + 两座塔
    d.rectangle([6, 16, 25, 27], fill=PAPER_SHADE, outline=INK)
    for x in (6, 18):
        d.rectangle([x, 9, x + 7, 27], fill=PAPER, outline=INK)
        d.rectangle([x - 1, 7, x + 8, 10], fill=WAX, outline=INK)
    d.rectangle([14, 20, 18, 27], fill=LEATHER, outline=INK)
    return img


def icon_village(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    for x, y in ((5, 17), (17, 19)):
        d.polygon([(x + 6, y - 7), (x + 12, y), (x, y)], fill=WAX, outline=INK)
        d.rectangle([x + 1, y, x + 11, y + 8], fill=PAPER, outline=INK)
    return img


def icon_camp(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.polygon([(16, 7), (27, 25), (5, 25)], fill=PAPER_SHADE, outline=INK)
    d.polygon([(16, 7), (20, 25), (12, 25)], fill=PAPER, outline=INK)
    d.line([16, 7, 16, 25], fill=INK)
    d.ellipse([19, 20, 27, 27], fill=WAX, outline=INK)   # 篝火
    return img


def icon_tower(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.rectangle([10, 8, 21, 26], fill=PAPER_SHADE, outline=INK)
    d.rectangle([8, 4, 23, 9], fill=PAPER, outline=INK)
    d.rectangle([12, 12, 19, 16], fill=PORTAL, outline=INK)   # 窗
    return img


def icon_ruin(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.line([3, 26, 29, 26], fill=INK)
    d.rectangle([6, 14, 12, 26], fill=PAPER, outline=INK)
    d.rectangle([14, 9, 18, 26], fill=PAPER_SHADE, outline=INK)
    d.rectangle([21, 18, 26, 26], fill=PAPER, outline=INK)
    d.line([9, 14, 9, 8], fill=INK)
    return img


def icon_shop(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.rectangle([5, 13, 26, 26], fill=PAPER, outline=INK)
    d.polygon([(3, 13), (28, 13), (25, 7), (6, 7)], fill=WAX, outline=INK)
    d.rectangle([13, 18, 18, 26], fill=LEATHER, outline=INK)
    return img


def icon_quest(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.rectangle([7, 4, 25, 27], fill=PAPER_LIGHT, outline=INK)
    d.line([10, 10, 22, 10], fill=INK)
    d.line([10, 14, 22, 14], fill=INK)
    d.line([10, 18, 18, 18], fill=INK)
    d.ellipse([18, 18, 28, 28], fill=GOLD, outline=INK)
    return img


def icon_danger(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    d.polygon([(16, 3), (30, 27), (2, 27)], fill=WAX, outline=DEEP_INK)
    d.rectangle([14, 11, 18, 20], fill=PAPER_LIGHT)
    d.rectangle([14, 22, 18, 25], fill=PAPER_LIGHT)
    return img


def icon_portal(size: int = 32) -> Image.Image:
    img, d = canvas(size, size)
    c = size / 2.0
    d.ellipse([c - 9, 3, c + 9, 21], outline=PORTAL, width=3)
    d.ellipse([c - 5, 7, c + 5, 17], fill=(158, 196, 236, 255), outline=INK)
    d.polygon([(c, 28), (c - 5, 21), (c + 5, 21)], fill=PORTAL, outline=INK)
    return img


def make_license(size: int = 16) -> Image.Image:
    """区域许可证物品图标：一卷纸 + 火漆封印。"""
    img, d = canvas(size, size)
    d.rectangle([2, 2, 13, 13], fill=PAPER_LIGHT, outline=INK)
    d.line([4, 5, 11, 5], fill=SECONDARY)
    d.line([4, 8, 11, 8], fill=SECONDARY)
    d.ellipse([9, 9, 14, 14], fill=WAX, outline=DEEP_INK)
    d.ellipse([10.5, 10.5, 12.5, 12.5], fill=GOLD)
    return img


def icon_bestiary(size: int = 16) -> Image.Image:
    """区域图鉴魔杖：一本小书 + 一只眼睛。"""
    img, d = canvas(size, size)
    d.rectangle([3, 2, 12, 13], fill=PAPER_LIGHT, outline=INK)
    d.line([5, 2, 5, 13], fill=LEATHER)
    d.ellipse([6, 6, 11, 10], fill=(232, 213, 169), outline=INK)
    d.ellipse([8, 7, 9, 9], fill=WAX)
    d.line([1, 4, 3, 6], fill=LEATHER)
    return img


def main() -> None:
    ensure(UI)
    ensure(MARKER)
    item_dir = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "item")
    ensure(item_dir)
    make_license().save(os.path.join(item_dir, "region_license.png"))
    print("wrote item/region_license.png  16x16")
    icon_wayshard().save(os.path.join(item_dir, "waypoint_wand.png"))
    print("wrote item/waypoint_wand.png  16x16")
    icon_bestiary().save(os.path.join(item_dir, "bestiary_wand.png"))
    print("wrote item/bestiary_wand.png  16x16")

    controls = {
        "frame.png": make_frame(),
        "banner.png": make_banner(),
        "button.png": make_button("default"),
        "button_hover.png": make_button("hover"),
        "button_disabled.png": make_button("disabled"),
        "tab.png": make_tab(False),
        "tab_selected.png": make_tab(True),
        "toolbar.png": make_toolbar(),
        "divider.png": make_divider(),
        "scroll_track.png": make_scroll_track(),
        "scroll_thumb.png": make_scroll_thumb(),
        "plate.png": make_plate(),
        "compass.png": make_compass(),
    }
    for i in range(8):
        controls[f"arrow_{i}.png"] = make_arrow(i)

    markers = {
        "town.png": icon_town(),
        "village.png": icon_village(),
        "camp.png": icon_camp(),
        "tower.png": icon_tower(),
        "ruin.png": icon_ruin(),
        "shop.png": icon_shop(),
        "quest.png": icon_quest(),
        "danger.png": icon_danger(),
        "portal.png": icon_portal(),
        "tree.png": icon_tree(),
        "forest.png": icon_forest(),
        "mountain.png": icon_mountain(),
        "bridge.png": icon_bridge(),
        "gate.png": icon_gate(),
    }

    for name, image in controls.items():
        path = os.path.join(UI, name)
        image.save(path)
        print(f"wrote ui/{name}  {image.size[0]}x{image.size[1]}")
    for name, image in markers.items():
        path = os.path.join(MARKER, name)
        image.save(path)
        print(f"wrote atlas/marker/{name}  {image.size[0]}x{image.size[1]}")


if __name__ == "__main__":
    main()
