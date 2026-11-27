"""生成一张示例"作者手绘区域地图"（羊皮纸风格 PNG），演示 maps.json 的用法。

用法: python tools/gen_map_demo.py
输出: src/main/resources/assets/biyi_atlas/textures/atlas/map/demo_ch1.png
"""
from __future__ import annotations

import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures", "atlas", "map")

# 找一个能写中文的系统字体，找不到就退回默认（英文可读，中文会缺字）
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]


def load_font(size: int):
    for path in FONT_CANDIDATES:
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()

PAPER = (232, 213, 169)
PAPER_LIGHT = (242, 228, 196)
AGED = (196, 164, 107)
INK = (58, 42, 27)
WATER = (122, 152, 168)
LAND = (198, 196, 160)
FOREST = (108, 128, 86)
MOUNTAIN = (150, 138, 116)
ROAD = (150, 112, 66)
SEAL = (168, 50, 45)
GOLD = (212, 166, 74)

SIZE = 512


def aged_paper() -> Image.Image:
    rng = random.Random(20261009)
    img = Image.new("RGB", (SIZE, SIZE), PAPER)
    px = img.load()
    for y in range(SIZE):
        for x in range(SIZE):
            n = rng.randint(-9, 9)
            edge = min(x, y, SIZE - 1 - x, SIZE - 1 - y) / (SIZE / 2.0)
            fade = 0.86 + 0.14 * min(1.0, edge * 3.0)
            px[x, y] = (
                max(0, min(255, int(PAPER[0] * fade) + n)),
                max(0, min(255, int(PAPER[1] * fade) + n)),
                max(0, min(255, int(PAPER[2] * fade) + n)),
            )
    return img.filter(ImageFilter.GaussianBlur(0.4))


def blob(d: ImageDraw.ImageDraw, cx: float, cy: float, rx: float, ry: float, color) -> None:
    points = []
    for i in range(24):
        a = i / 24.0 * math.tau
        jitter = 0.78 + 0.22 * math.sin(a * 3.0 + cx)
        points.append((cx + math.cos(a) * rx * jitter, cy + math.sin(a) * ry * jitter))
    d.polygon(points, fill=color, outline=INK)


def mountains(d: ImageDraw.ImageDraw, cx: float, cy: float, count: int) -> None:
    for i in range(count):
        x = cx + i * 26 - count * 13
        h = 20 + (i % 3) * 8
        d.polygon([(x, cy), (x + 13, cy - h), (x + 26, cy)], fill=MOUNTAIN, outline=INK)
        d.line([(x + 13, cy - h), (x + 13, cy - h * 0.35)], fill=(240, 238, 230))


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    img = aged_paper()
    d = ImageDraw.Draw(img)
    font_big = load_font(20)
    font_small = load_font(16)
    font_tiny = load_font(13)

    # 海与海岸线
    d.polygon([(0, 0), (SIZE, 0), (SIZE, 90), (330, 130), (180, 96), (0, 150)], fill=WATER, outline=INK)
    d.line([(0, 150), (180, 96), (330, 130), (SIZE, 90)], fill=INK, width=2)

    # 陆地色块（淡）
    d.polygon([(0, 150), (180, 96), (330, 130), (SIZE, 90), (SIZE, SIZE), (0, SIZE)],
              fill=LAND)
    d.line([(0, 150), (180, 96), (330, 130), (SIZE, 90)], fill=INK, width=2)

    # 森林
    for cx, cy, rx, ry in ((90, 250, 62, 44), (168, 330, 54, 40), (330, 400, 72, 48), (420, 250, 46, 34)):
        blob(d, cx, cy, rx, ry, FOREST)

    # 山脉
    mountains(d, 250, 200, 5)

    # 河流
    d.line([(150, 130), (170, 220), (140, 300), (185, 420), (170, SIZE)], fill=WATER, width=4)

    # 道路（虚线）
    def dashed(points, color, width=3):
        for i in range(len(points) - 1):
            x0, y0 = points[i]
            x1, y1 = points[i + 1]
            length = math.hypot(x1 - x0, y1 - y0)
            steps = int(length / 8)
            for s in range(steps):
                if s % 2:
                    continue
                t0 = s / steps
                t1 = min(1.0, (s + 1) / steps)
                d.line([(x0 + (x1 - x0) * t0, y0 + (y1 - y0) * t0),
                        (x0 + (x1 - x0) * t1, y0 + (y1 - y0) * t1)], fill=color, width=width)

    dashed([(120, 210), (250, 260), (330, 330), (430, 300)], ROAD)
    dashed([(250, 260), (250, 420)], ROAD, 2)

    # 主城（方框 + 塔）
    d.rectangle([232, 246, 268, 274], fill=PAPER_LIGHT, outline=INK, width=2)
    d.rectangle([236, 236, 246, 250], fill=SEAL, outline=INK)
    d.rectangle([254, 236, 264, 250], fill=SEAL, outline=INK)
    d.text((196, 278), "避役主城", fill=INK, font=font_small)

    # 罗盘
    cx, cy = 440, 430
    d.ellipse([cx - 34, cy - 34, cx + 34, cy + 34], outline=INK, width=2)
    d.polygon([(cx, cy - 30), (cx + 8, cy), (cx, cy + 30), (cx - 8, cy)], fill=PAPER_LIGHT, outline=INK)
    d.polygon([(cx - 30, cy), (cx, cy - 8), (cx + 30, cy), (cx, cy + 8)], fill=GOLD, outline=INK)
    d.text((cx - 6, cy - 52), "N", fill=INK, font=font_tiny)

    # 边框与章节标题
    d.rectangle([8, 8, SIZE - 9, SIZE - 9], outline=INK, width=3)
    d.line([8, 46, SIZE - 9, 46], fill=INK, width=1)
    d.text((22, 18), "第一章 · 避役谷", fill=INK, font=font_big)
    d.text((SIZE - 182, SIZE - 30), "作者手绘示例 · demo_ch1", fill=(120, 100, 76), font=font_tiny)

    path = os.path.join(OUT, "demo_ch1.png")
    img.save(path)
    print(f"wrote {os.path.relpath(path, ROOT)}  {img.size[0]}x{img.size[1]}")


if __name__ == "__main__":
    main()
