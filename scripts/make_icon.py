#!/usr/bin/env python3
# WeKite 正式图标生成：全部密度 webp（foreground 圆角全幅 / round 渐变 / monochrome 白飞机）
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import os

ROOT = '/vol1/@appdata/trim.hermes/workspace/wekite-repo/app/src/main/res'
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'

def grad_bg(size, c1, c2):
    arr = np.zeros((size, size, 4), np.uint8)
    for i in range(3):
        arr[:, :, i] = np.linspace(c1[i], c2[i], size)[:, None].astype(np.uint8)
    arr[:, :, 3] = 255
    return Image.fromarray(arr, 'RGBA')

def rounded_corners(img, radius):
    size = img.size[0]
    mask = Image.new('L', (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size-1, size-1], radius=radius, fill=255)
    out = img.copy()
    out.putalpha(Image.composite(img.split()[3], Image.new('L', (size, size), 0), mask))
    return out

def draw_plane(d, size, cx, cy, s, lw, fill=(255,255,255,255), line=(170,205,240,255)):
    base_pts = [(-90, 58), (106, -38), (-26, 6), (116, -88), (-73, -53), (-108, -18)]
    plane = [(int(cx + dx*s), int(cy + dy*s)) for dx, dy in base_pts]
    d.polygon(plane, fill=fill)
    d.line([(int(cx-73*s), int(cy-53*s)), (int(cx-26*s), int(cy+6*s))], fill=line, width=lw)
    d.line([(int(cx+106*s), int(cy-38*s)), (int(cx-26*s), int(cy+6*s))], fill=line, width=lw)
    d.line([(int(cx-108*s), int(cy-18*s)), (int(cx-73*s), int(cy-53*s))], fill=line, width=lw)

def full_icon(size, bg=True):
    """完整图标：渐变圆角底 + 白纸飞机 + WeKite 文字。
    bg=True → 低版本 round 用全幅渐变圆角方;
    bg=False → adaptive foreground: 透明底 + 圆形渐变底(直径=安全区66%) + 缩小居中图形
    """
    s = size / 432.0
    if bg:
        base = grad_bg(size, (11, 147, 246), (0, 168, 224))
        base = rounded_corners(base, int(size * 0.222))
        scale = 1.18 * s
        plane_cy = size * 0.444
        text_size = int(76 * s)
        text_y = size * 0.694
    else:
        base = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        # 圆形渐变底：直径 = 安全区 66% (留 3px 余量防抗锯齿边缘超出)
        r = int(size * 0.33) - 3
        circ = grad_bg(int(r * 2), (11, 147, 246), (0, 168, 224))
        cmask = Image.new('L', circ.size, 0)
        ImageDraw.Draw(cmask).ellipse([0, 0, circ.size[0]-1, circ.size[1]-1], fill=255)
        circ.putalpha(cmask)
        base.alpha_composite(circ, (size//2 - r, size//2 - r))
        # 图形缩小到安全区内居中 (飞机+文字整体都要落在 66% 圆内, 否则 launcher 裁边)
        scale = 1.18 * s * 0.55
        plane_cy = size * 0.40
        text_size = max(8, int(38 * s))
        text_y = size * 0.62
    d = ImageDraw.Draw(base)
    cx, cy = size * 0.5, plane_cy
    ps = scale
    plane = [(int(cx + dx*ps), int(cy + dy*ps)) for dx, dy in [(-90,58),(106,-38),(-26,6),(116,-88),(-73,-53),(-108,-18)]]
    # 投影
    sh = Image.new('RGBA', (size, size), (0,0,0,0))
    ds = ImageDraw.Draw(sh)
    ds.polygon([(p[0]+int(6*s), p[1]+int(8*s)) for p in plane], fill=(0, 40, 90, 200))
    sh = sh.filter(ImageFilter.GaussianBlur(max(1, int(4*s))))
    base.alpha_composite(sh)
    draw_plane(d, size, cx, cy, ps, max(3, int(5*ps)))
    # 文字
    font = ImageFont.truetype(FONT, text_size)
    txt = 'WeKite'
    w = d.textlength(txt, font=font)
    x = (size - w) / 2
    y = text_y
    sh2 = Image.new('RGBA', (size, size), (0,0,0,0))
    ds2 = ImageDraw.Draw(sh2)
    ds2.text((x+int(3*s), y+int(4*s)), txt, font=font, fill=(0, 40, 90, 170))
    sh2 = sh2.filter(ImageFilter.GaussianBlur(max(1, int(2*s))))
    base.alpha_composite(sh2)
    d.text((x, y), txt, font=font, fill=(255, 255, 255, 255))
    return base

def mono_icon(size):
    """monochrome：透明底 + 白色纸飞机（无文字），安全区内居中"""
    m = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(m)
    s = size / 432.0
    cx, cy = size * 0.5, size * 0.5
    ps = 1.3 * s * 0.62
    base_pts = [(-90, 58), (106, -38), (-26, 6), (116, -88), (-73, -53), (-108, -18)]
    plane = [(int(cx + dx*ps), int(cy + dy*ps)) for dx, dy in base_pts]
    d.polygon(plane, fill=(255, 255, 255, 255))
    return m

def save_webp(img, path):
    img.convert('RGBA').save(path, 'WEBP', quality=100, method=6)

# foreground 密度尺寸
FG = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
# round 密度尺寸（48 起）
RD = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}

for dpi, sz in FG.items():
    d = os.path.join(ROOT, f'mipmap-{dpi}')
    save_webp(full_icon(sz, bg=False), os.path.join(d, 'ic_launcher_foreground.webp'))
    save_webp(mono_icon(sz), os.path.join(d, 'ic_launcher_monochrome.webp'))
    print('fg/mono', dpi, sz)
for dpi, sz in RD.items():
    d = os.path.join(ROOT, f'mipmap-{dpi}')
    save_webp(full_icon(sz, bg=True), os.path.join(d, 'ic_launcher_round.webp'))
    print('round', dpi, sz)
print('ALL DONE')
