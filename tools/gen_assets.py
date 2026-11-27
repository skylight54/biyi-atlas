"""生成避役图志的内置纹理资源（可复现）。

用法: python tools/gen_assets.py
输出: src/main/resources/assets/biyi_atlas/textures/**
"""
from __future__ import annotations

import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "biyi_atlas", "textures")


def ensure(path: str) -> str:
    os.makedirs(path, exist_ok=True)
    return path


def wrap_gaussian(a: np.ndarray, sigma: float) -> np.ndarray:
    """在环绕（tileable）前提下做高斯模糊，保证纹理可无缝平铺。"""
    fy = np.fft.fftfreq(a.shape[0])[:, None]
    fx = np.fft.fftfreq(a.shape[1])[None, :]
    kernel = np.exp(-2.0 * (math.pi ** 2) * (sigma ** 2) * (fx ** 2 + fy ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(a) * kernel))


def make_paper(size: int = 128, seed: int = 20261009) -> Image.Image:
    rng = np.random.default_rng(seed)

    # 大尺度污渍 + 细颗粒
    blotch = wrap_gaussian(rng.normal(0.0, 1.0, (size, size)), sigma=9.0)
    blotch /= max(1e-6, np.abs(blotch).max())
    grain = wrap_gaussian(rng.normal(0.0, 1.0, (size, size)), sigma=0.8)
    grain /= max(1e-6, np.abs(grain).max())

    lum = 0.94 + 0.055 * blotch + 0.035 * grain

    # 纤维：少量深浅不一的横竖短线
    fibers = np.zeros((size, size), dtype=np.float32)
    for _ in range(90):
        y = rng.integers(0, size)
        x0 = rng.integers(0, size)
        length = int(rng.integers(size // 8, size // 2))
        horizontal = rng.random() < 0.7
        strength = float(rng.normal(0.0, 0.05))
        for i in range(length):
            if horizontal:
                fibers[y, (x0 + i) % size] += strength
            else:
                fibers[(y + i) % size, x0] += strength

    lum = lum + fibers

    base = np.array([216.0, 195.0, 152.0], dtype=np.float32)
    warm = np.array([10.0, 4.0, -8.0], dtype=np.float32)  # 亮处偏暖
    img = base[None, None, :] * lum[:, :, None] + warm[None, None, :] * (lum[:, :, None] - 1.0)

    # 边缘轻微变暗（做成"叠起来的纸"感），四周对称所以仍可平铺
    yy = np.linspace(-1.0, 1.0, size, endpoint=False)
    xx = np.linspace(-1.0, 1.0, size, endpoint=False)
    r = np.sqrt(xx[None, :] ** 2 + yy[:, None] ** 2) / math.sqrt(2.0)
    img *= (1.0 - 0.10 * r ** 3)[:, :, None]

    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")
    return out.filter(ImageFilter.SMOOTH)


def make_item_map(size: int = 16) -> Image.Image:
    """物品栏里的卷起来的地图。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    paper = (222, 201, 158, 255)
    paper_shadow = (186, 163, 118, 255)
    ink = (92, 70, 44, 255)
    strap = (150, 60, 52, 255)

    # 卷轴主体
    d.rounded_rectangle([3, 1, 12, 14], radius=1, fill=paper, outline=ink)
    # 左右卷边
    d.rectangle([3, 1, 5, 14], fill=paper_shadow)
    d.rectangle([10, 1, 12, 14], fill=paper_shadow)
    # 绑带
    d.rectangle([3, 7, 12, 9], fill=strap)

    return img


def arrow_points(size: int = 32) -> list[tuple[float, float]]:
    c = size / 2.0
    return [
        (c, 2.0),               # 尖端
        (c + 6.5, 13.0),
        (c + 2.6, 12.0),
        (c + 2.6, 26.0),
        (c - 2.6, 26.0),
        (c - 2.6, 12.0),
        (c - 6.5, 13.0),
    ]


def make_arrow(size: int = 32) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pts = arrow_points(size)
    d.polygon(pts, fill=(255, 255, 255, 255), outline=(60, 44, 30, 255))
    d.polygon([(p[0], p[1] + 0.8) for p in pts], outline=(60, 44, 30, 200))
    return img


def make_pin(size: int = 32, color: tuple[int, int, int, int] = (150, 60, 52, 255)) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = size / 2.0
    d.ellipse([c - 7, 4, c + 7, 18], fill=color, outline=(60, 44, 30, 255))
    d.polygon([(c - 5.0, 15.0), (c + 5.0, 15.0), (c, 28.0)], fill=color, outline=(60, 44, 30, 255))
    d.ellipse([c - 2.5, 8.5, c + 2.5, 13.5], fill=(240, 232, 214, 255))
    return img


def make_flag(size: int = 32) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.line([(10, 4), (10, 28)], fill=(60, 44, 30, 255), width=2)
    d.polygon([(11, 5), (25, 9), (11, 14)], fill=(150, 60, 52, 255), outline=(60, 44, 30, 255))
    return img


def make_wand(size: int = 16) -> Image.Image:
    """地图魔杖（作者工具）。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.line([(4, 14), (11, 5)], fill=(96, 72, 48, 255), width=2)
    d.line([(4, 14), (11, 5)], fill=(168, 138, 96, 255), width=1)
    d.ellipse([8, 0, 14, 6], fill=(74, 140, 168, 255), outline=(60, 44, 30, 255))
    d.ellipse([10, 2, 12, 4], fill=(216, 240, 246, 255))
    return img


def make_portal(size: int = 32) -> Image.Image:
    """传送点图标。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = size / 2.0
    d.ellipse([c - 9, 3, c + 9, 21], outline=(63, 92, 158, 255), width=3)
    d.ellipse([c - 5, 7, c + 5, 17], fill=(158, 196, 236, 255))
    d.polygon([(c, 28), (c - 5, 21), (c + 5, 21)], fill=(63, 92, 158, 255))
    return img


def make_pixel(size: int = 16) -> Image.Image:
    """纯白实心贴图：地块/道路用它 + setColor 染色来画。
    刻意做成 16x16 而不是 1x1——1x1 贴图在带光影的整合包里容易采不到样。"""
    return Image.new("RGBA", (size, size), (255, 255, 255, 255))


def main() -> None:
    ensure(TEX)
    ensure(os.path.join(TEX, "atlas"))
    ensure(os.path.join(TEX, "atlas", "marker"))
    ensure(os.path.join(TEX, "item"))

    outputs = {
        os.path.join(TEX, "atlas", "paper.png"): make_paper(),
        os.path.join(TEX, "atlas", "arrow.png"): make_arrow(),
        os.path.join(TEX, "atlas", "pixel.png"): make_pixel(),
        os.path.join(TEX, "item", "atlas.png"): make_item_map(),
        os.path.join(TEX, "item", "map_wand.png"): make_wand(),
        os.path.join(TEX, "atlas", "marker", "pin.png"): make_pin(),
        os.path.join(TEX, "atlas", "marker", "flag.png"): make_flag(),
        os.path.join(TEX, "atlas", "marker", "portal.png"): make_portal(),
    }
    for path, image in outputs.items():
        image.save(path)
        print(f"wrote {os.path.relpath(path, ROOT)}  {image.size[0]}x{image.size[1]} {image.mode}")


if __name__ == "__main__":
    main()
