"""生成"地形交界墨线"贴图：4 个方向各一格 16x16，共 64x16。

相邻区块地形类别不同时，在那一侧叠一层墨线，做出 Antique Atlas 那种手绘边界感。
用法: python tools/gen_edges.py
输出: src/main/resources/assets/biyi_atlas/textures/atlas/terrain_edges.png
"""
from __future__ import annotations

import os
import random

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "atlas")

TILE = 16
INK = (58, 42, 27)


def make_edge(side: str) -> Image.Image:
    img = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    rng = random.Random(hash(side) & 0xFFFF)
    for i in range(TILE):
        jitter = rng.randint(0, 1)
        if side == "top":
            d.point((i, 0), fill=INK + (170,))
            d.point((i, 1 + jitter), fill=INK + (90,))
            if i % 3 == 0:
                d.point((i, 2 + jitter), fill=INK + (45,))
        elif side == "bottom":
            d.point((i, TILE - 1), fill=INK + (170,))
            d.point((i, TILE - 2 - jitter), fill=INK + (90,))
            if i % 3 == 0:
                d.point((i, TILE - 3 - jitter), fill=INK + (45,))
        elif side == "left":
            d.point((0, i), fill=INK + (170,))
            d.point((1 + jitter, i), fill=INK + (90,))
            if i % 3 == 0:
                d.point((2 + jitter, i), fill=INK + (45,))
        else:
            d.point((TILE - 1, i), fill=INK + (170,))
            d.point((TILE - 2 - jitter, i), fill=INK + (90,))
            if i % 3 == 0:
                d.point((TILE - 3 - jitter, i), fill=INK + (45,))
    return img


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    atlas = Image.new("RGBA", (TILE * 4, TILE), (0, 0, 0, 0))
    for index, side in enumerate(("top", "right", "bottom", "left")):
        atlas.paste(make_edge(side), (index * TILE, 0))
    path = os.path.join(OUT, "terrain_edges.png")
    atlas.save(path)
    print(f"wrote {os.path.relpath(path, ROOT)}  {atlas.size[0]}x{atlas.size[1]}")


if __name__ == "__main__":
    main()
