"""生成战争迷雾贴图（云纹线描风 v3，参考作者给的手绘云朵）。

设计要点（对应作者要求）：
- **线描云纹**：如意云轮廓 + 卷曲（螺线）收尾，细墨线勾边；
- **不太密集**：整张只有 7 朵云，留大量空白；
- **不硬截断**：①云朵内部只有很淡的纸色（低 alpha），②贴图整体 alpha 乘一个柔和径向蒙版，
  使贴图自身边界自然淡出（区域多边形的边界另在渲染时用"云朵印章"做羽化）。
"""
from PIL import Image, ImageDraw, ImageChops, ImageFilter
import math
import random

SIZE = 512
random.seed(20261010)

INK = (58, 42, 27)
PAPER = (250, 247, 240)

# ---------- 1) 云朵几何：一堆交叠的圆 + 两端的螺线卷 ----------
def cloud_circles(cx, cy, scale):
    """返回组成一朵云的一组圆 (x, y, r)。控制横向尺寸，避免相邻云朵连成长条。"""
    circles = []
    bumps = random.randint(3, 4)
    r = scale * random.uniform(0.50, 0.68)
    x = cx - r * (bumps - 1) * 0.55
    for i in range(bumps):
        rr = scale * random.uniform(0.50, 0.68)
        circles.append((x, cy + random.uniform(-0.20, 0.20) * scale, rr))
        x += rr * random.uniform(0.80, 1.00)
    # 底部垫一圈，让云肚更饱满
    circles.append((cx, cy + scale * 0.42, scale * 0.62))
    return circles


def spiral(cx, cy, radius, turns=1.6, start=0.0, cw=True, steps=64):
    """阿基米德螺线（云头卷曲）。"""
    pts = []
    for i in range(steps + 1):
        t = i / steps
        angle = start + (t * turns * 2 * math.pi) * (-1 if cw else 1)
        r = radius * (1.0 - t * 0.92)
        pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    return pts


# ---------- 2) 在两张蒙版上分别画"云肚"和"墨线" ----------
fill_mask = Image.new("L", (SIZE, SIZE), 0)
edge_mask = Image.new("L", (SIZE, SIZE), 0)
fd = ImageDraw.Draw(fill_mask)
ed = ImageDraw.Draw(edge_mask)

clouds = []
# 稀疏且均匀：3×3 网格 + 抖动（避免全挤在中间；拉伸到区域包围盒后分布才均匀）
grid = [(-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (0, 1), (1, 1)]
for (gx, gy) in grid:
    cx = SIZE / 2 + gx * SIZE * 0.34 + random.randint(-20, 20)
    cy = SIZE / 2 + gy * SIZE * 0.34 + random.randint(-20, 20)
    scale = random.uniform(28, 40)
    clouds.append((cx, cy, scale, cloud_circles(cx, cy, scale)))

for (cx, cy, scale, circles) in clouds:
    for (x, y, r) in circles:
        fd.ellipse([x - r, y - r, x + r, y + r], fill=255)
    # 卷曲：一边顺时针、一边逆时针，模拟如意云头
    for (dx, dy, cw) in ((-scale * 1.5, scale * 0.15, True), (scale * 1.6, scale * 0.10, False)):
        pts = spiral(cx + dx, cy + dy, scale * 0.62, turns=1.7, cw=cw)
        ed.line(pts, fill=200, width=3, joint="curve")
        # 卷曲处也补一点云肚，避免空心
        fd.ellipse([cx + dx - scale * 0.62, cy + dy - scale * 0.62,
                    cx + dx + scale * 0.62, cy + dy + scale * 0.62], fill=255)

# 云肚的并集轮廓 = 蒙版 - 腐蚀(蒙版)
eroded = fill_mask.filter(ImageFilter.MinFilter(5))
outline = ImageChops.difference(fill_mask, eroded)
edge_mask = ImageChops.lighter(edge_mask, outline)

# ---------- 3) 合成 RGBA ----------
image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
# 云肚：很淡的纸色，让地图还能透出来（"不用太密集"）
body_alpha = fill_mask.filter(ImageFilter.GaussianBlur(3.0)).point(lambda v: int(v * 0.55))
image.paste(Image.new("RGBA", (SIZE, SIZE), PAPER + (255,)), (0, 0), body_alpha)
# 墨线：轮廓 + 卷曲
line_alpha = edge_mask.filter(ImageFilter.GaussianBlur(0.6)).point(lambda v: int(min(255, v * 0.45)))
image.paste(Image.new("RGBA", (SIZE, SIZE), INK + (255,)), (0, 0), line_alpha)

# 少量飞白，打散平面感
draw = ImageDraw.Draw(image, "RGBA")
for _ in range(40):
    x, y = random.randint(0, SIZE - 1), random.randint(0, SIZE - 1)
    length = random.randint(4, 14)
    angle = random.uniform(0, math.pi)
    draw.line([(x, y), (x + length * math.cos(angle), y + length * math.sin(angle))],
              fill=INK + (random.randint(10, 24),), width=1)

# ---------- 4) 柔和径向蒙版：贴图边界自然淡出（避免生硬的贴图接缝） ----------
mask = Image.new("L", (SIZE, SIZE), 0)
md = ImageDraw.Draw(mask)
inset = SIZE * 0.12
md.ellipse([inset, inset, SIZE - inset, SIZE - inset], fill=255)
mask = mask.filter(ImageFilter.GaussianBlur(SIZE * 0.10))
alpha = image.getchannel("A")
image.putalpha(ImageChops.multiply(alpha, mask))

out = r"D:\DSH开发\biyi-atlas\src\main\resources\assets\biyi_atlas\textures\atlas\fog.png"
image.save(out)
print("saved", out, SIZE)
