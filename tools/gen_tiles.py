"""生成 Antique Atlas 风格的"手绘地形瓦片"图集（自绘，零许可风险）。

图集布局：横向 4 个变体，纵向每个地形类别一行，单格 16x16。
用法: python tools/gen_tiles.py
输出: src/main/resources/assets/biyi_atlas/textures/atlas/terrain.png
"""
from __future__ import annotations

import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "atlas")

TILE = 16
VARIANTS = 4

# 羊皮纸墨线配色（与 UI 一致）
INK = (58, 42, 27)
PAPER = (232, 213, 169)

# 每个类别：底色 + 纹理色（手绘低饱和）
CATEGORIES = [
    ("unknown", (214, 196, 158), (196, 176, 138)),
    ("water", (150, 176, 186), (120, 152, 168)),
    ("sand", (224, 208, 164), (206, 186, 138)),
    ("plains", (196, 198, 152), (176, 182, 132)),
    ("forest", (150, 168, 118), (120, 144, 96)),
    ("taiga", (168, 178, 140), (140, 156, 118)),
    ("desert", (228, 214, 170), (210, 192, 140)),
    ("savanna", (208, 200, 148), (188, 180, 126)),
    ("jungle", (128, 152, 104), (104, 132, 88)),
    ("swamp", (150, 156, 122), (124, 134, 104)),
    ("mountain", (186, 180, 166), (158, 152, 140)),
    ("snow", (232, 232, 224), (208, 212, 208)),
    ("mushroom", (170, 156, 176), (146, 132, 154)),
    ("nether", (176, 132, 126), (146, 106, 102)),
    ("end", (198, 194, 176), (168, 166, 152)),
    ("other", (204, 190, 158), (182, 168, 138)),
]


def shade(color, factor):
    return tuple(max(0, min(255, int(c * factor))) for c in color)


def draw_tile(category: str, base, accent, variant: int, rng: random.Random) -> Image.Image:
    img = Image.new("RGBA", (TILE, TILE), base + (255,))
    d = ImageDraw.Draw(img)

    def dot(x, y, color):
        if 0 <= x < TILE and 0 <= y < TILE:
            d.point((x, y), fill=color)

    def stroke(x0, y0, x1, y1, color, width=1):
        d.line([(x0, y0), (x1, y1)], fill=color, width=width)

    if category == "water":
        for i in range(4):
            y = 3 + i * 3 + variant % 2
            stroke(1, y, 6, y + 1, accent)
            stroke(8, y + 1, 14, y, accent)
    elif category in ("forest", "jungle"):
        for _ in range(5 + variant):
            x = rng.randint(2, TILE - 3)
            y = rng.randint(3, TILE - 3)
            d.polygon([(x, y - 3), (x + 3, y + 2), (x - 3, y + 2)], fill=accent, outline=INK)
    elif category == "taiga":
        for _ in range(4 + variant % 2):
            x = rng.randint(2, TILE - 3)
            y = rng.randint(4, TILE - 3)
            d.polygon([(x, y - 4), (x + 3, y + 2), (x - 3, y + 2)], fill=accent, outline=INK)
            stroke(x, y + 2, x, y + 3, INK)
    elif category in ("plains", "savanna"):
        for _ in range(4 + variant):
            x = rng.randint(2, TILE - 3)
            y = rng.randint(6, TILE - 3)
            stroke(x, y, x, y - 2, accent)
            stroke(x + 1, y, x + 1, y - 1, accent)
    elif category == "desert":
        for i in range(3):
            y = 4 + i * 4
            stroke(1, y, 7, y - 1, accent)
            stroke(9, y - 1, 14, y, accent)
    elif category == "sand":
        for _ in range(6):
            dot(rng.randint(1, TILE - 2), rng.randint(1, TILE - 2), accent)
        stroke(1, TILE - 4, 6, TILE - 5, accent)
    elif category == "mountain":
        d.polygon([(2, 13), (7, 4), (12, 13)], fill=shade(accent, 1.1), outline=INK)
        d.polygon([(8, 13), (12, 6), (15, 13)], fill=accent, outline=INK)
        stroke(7, 4, 7, 8, shade(accent, 1.25))
    elif category == "snow":
        for _ in range(7):
            x, y = rng.randint(1, TILE - 2), rng.randint(1, TILE - 2)
            dot(x, y, accent)
            dot(x + 1, y, shade(accent, 0.95))
    elif category == "swamp":
        for _ in range(3):
            x, y = rng.randint(3, TILE - 5), rng.randint(4, TILE - 4)
            stroke(x, y, x + 4, y, accent)
            stroke(x + 1, y + 1, x + 5, y + 1, accent)
    elif category == "mushroom":
        for _ in range(3):
            x, y = rng.randint(3, TILE - 4), rng.randint(4, TILE - 4)
            d.ellipse([x, y, x + 3, y + 2], fill=(178, 96, 96), outline=INK)
            stroke(x + 1, y + 2, x + 1, y + 4, INK)
    elif category == "nether":
        for i in range(4):
            stroke(1 + i * 4, 2, 2 + i * 4, 14, accent)
    elif category == "end":
        for _ in range(5):
            dot(rng.randint(1, TILE - 2), rng.randint(1, TILE - 2), shade(accent, 1.15))
    else:  # unknown / other
        for _ in range(4):
            dot(rng.randint(1, TILE - 2), rng.randint(1, TILE - 2), accent)

    # 统一的旧纸化处理：轻微噪点 + 压暗边缘
    px = img.load()
    for y in range(TILE):
        for x in range(TILE):
            r, g, b, a = px[x, y]
            n = rng.randint(-5, 5)
            edge = 1.0 if 2 <= x < TILE - 2 and 2 <= y < TILE - 2 else 0.94
            px[x, y] = (
                max(0, min(255, int(r * edge) + n)),
                max(0, min(255, int(g * edge) + n)),
                max(0, min(255, int(b * edge) + n)),
                a,
            )
    return img


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    atlas = Image.new("RGBA", (TILE * VARIANTS, TILE * len(CATEGORIES)), (0, 0, 0, 0))
    for row, (name, base, accent) in enumerate(CATEGORIES):
        for variant in range(VARIANTS):
            rng = random.Random(hash((name, variant)) & 0xFFFF)
            tile = draw_tile(name, base, accent, variant, rng)
            atlas.paste(tile, (variant * TILE, row * TILE))
    atlas = atlas.filter(ImageFilter.SMOOTH)
    path = os.path.join(OUT, "terrain.png")
    atlas.save(path)
    print(f"wrote {os.path.relpath(path, ROOT)}  {atlas.size[0]}x{atlas.size[1]}  "
          f"(tile {TILE}px, {len(CATEGORIES)} categories x {VARIANTS} variants)")


if __name__ == "__main__":
    main()
