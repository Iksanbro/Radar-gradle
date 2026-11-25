#!/usr/bin/env python3
"""Иконка мода: вычислитель управления огнём блоком Minecraft (пиксельные текстуры 32x32, изометрия как в инвентаре)
на голубом круге с крупной сеткой и светлой каймой.

Лицо блока - наша текстура fcs_front.png; бока и верх - своя латунная текстура (рисуется здесь же в цветах рамки
лицевой текстуры). Ассеты Create (латунный корпус) не используются - у них «All Rights Reserved».
Выход: src/main/resources/logo.png (логотип в списке модов) и release/icon.png (страница мода), 512x512.
Запуск: python3 devtools/mod_icon.py
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
TEX = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'radar_fcs', 'textures', 'block')
S = 2048                      # рабочее разрешение (итог 512 - сглаживание краёв x4)
C = S // 2


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


# ------------------------------------------------------------------ фон

def background():
    """Круг: голубой радиальный градиент (светлее к центру и вверх-влево), крупная сетка, светлая мягкая кайма."""
    R = S * 0.48
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    px = img.load()
    cx, cy = C - S * 0.06, C - S * 0.08          # «свет» чуть выше и левее центра
    inner, mid, outer = (132, 202, 250), (78, 160, 236), (40, 116, 212)
    step = S / 5.6                                # крупные клетки, как на образце
    off = (C % step)
    for y in range(S):
        for x in range(S):
            dc = math.hypot(x - C, y - C)
            if dc > R + 2:
                continue
            t = math.hypot(x - cx, y - cy) / (R * 1.08)
            col = lerp(inner, mid, t / 0.6) if t < 0.6 else lerp(mid, outer, (t - 0.6) / 0.4)
            # сетка: тонкие светлые линии, к краю слабее
            gx = abs(((x - off) % step) - step / 2)
            gy = abs(((y - off) % step) - step / 2)
            dline = min(step / 2 - gx, step / 2 - gy)          # расстояние до ближайшей линии сетки
            if dline < 6:
                k = (1 - dline / 6) * 0.55 * (1 - min(1.0, dc / R) ** 3)
                col = lerp(col, (190, 228, 255), k)
            # кайма: светлое свечение изнутри края
            e = (R - dc) / (S * 0.05)
            if e < 1:
                col = lerp(col, (226, 242, 255), (1 - max(0.0, e)) ** 2 * 0.85)
            a = 255 if dc <= R - 1 else int(255 * max(0.0, (R + 1 - dc) / 2))
            px[x, y] = col + (a,)
    return img


# ------------------------------------------------------------------ текстуры

def brass_colors(front):
    """Цвета латуни (как светлая рамка лицевой текстуры): блик, основной, тень, тёмный шов."""
    light = front.load()[1, 1][:3]
    return light, (226, 174, 82), (164, 110, 46), (88, 54, 22)


def brass_face(cols, kind):
    """Своя латунная грань 32x32: фаска (блик сверху-слева, тень снизу-справа), утопленная панель с прорезями
    (бок) или решёткой (верх), заклёпки по углам."""
    light, base, shadow, dark = cols
    mid_dark = lerp(base, shadow, 0.4)
    im = Image.new('RGBA', (32, 32), base + (255,))
    d = ImageDraw.Draw(im)
    # фаска рамки
    d.rectangle([0, 0, 31, 31], outline=dark)
    d.line([(1, 1), (30, 1)], fill=light)
    d.line([(1, 1), (1, 30)], fill=light)
    d.line([(2, 2), (29, 2)], fill=lerp(light, base, 0.5))
    d.line([(2, 2), (2, 29)], fill=lerp(light, base, 0.5))
    d.line([(1, 30), (30, 30)], fill=shadow)
    d.line([(30, 1), (30, 30)], fill=shadow)
    d.line([(2, 29), (29, 29)], fill=lerp(shadow, base, 0.5))
    d.line([(29, 2), (29, 29)], fill=lerp(shadow, base, 0.5))
    # утопленная панель
    d.rectangle([5, 5, 26, 26], fill=mid_dark)
    d.line([(5, 5), (26, 5)], fill=shadow)
    d.line([(5, 5), (5, 26)], fill=shadow)
    d.line([(5, 26), (26, 26)], fill=lerp(light, base, 0.4))
    d.line([(26, 5), (26, 26)], fill=lerp(light, base, 0.4))
    slot, slot_lip = lerp(dark, (0, 0, 0), 0.3), lerp(mid_dark, light, 0.35)
    if kind == 'side':
        for y in range(8, 25, 4):                                 # горизонтальные прорези
            d.line([(8, y), (23, y)], fill=slot)
            d.line([(8, y + 1), (23, y + 1)], fill=slot_lip)
    else:
        for x in range(8, 25, 3):                                 # решётка вентиляции
            d.line([(x, 8), (x, 23)], fill=slot)
            d.line([(x + 1, 8), (x + 1, 23)], fill=slot_lip)
    for (x, y) in ((3, 3), (28, 3), (3, 28), (28, 28)):           # заклёпки
        d.point((x, y), fill=light)
        d.point((x + 1, y + 1), fill=shadow)
    return im


def shade(im, k):
    r, g, b, a = im.split()
    f = (lambda v: max(0, min(255, int(v * k))))
    return Image.merge('RGBA', (r.point(f), g.point(f), b.point(f), a))


def warp(face, tl, tr, bl):
    """Текстуру грани - на параллелограмм холста (углы: верх-лево, верх-право, низ-лево), пиксели без размытия."""
    w, h = face.size
    ax, ay = (tr[0] - tl[0]) / w, (tr[1] - tl[1]) / w
    bx, by = (bl[0] - tl[0]) / h, (bl[1] - tl[1]) / h
    det = ax * by - bx * ay
    ia, ib, id_, ie = by / det, -bx / det, -ay / det, ax / det
    c = -(ia * tl[0] + ib * tl[1])
    f = -(id_ * tl[0] + ie * tl[1])
    return face.transform((S, S), Image.AFFINE, (ia, ib, c, id_, ie, f), resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))


# ------------------------------------------------------------------ блок

def block(front, side, top):
    """Блок как в инвентаре Minecraft: верх светлее, лицо (слева) - среднее, бок (справа) - темнее."""
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    L = S * 0.31                                                  # длина ребра
    a, b = L * math.cos(math.radians(30)), L * math.sin(math.radians(30))
    T = (C, C - L * 0.5 + b + S * 0.005)                          # ближнее верхнее ребро; фигура - по центру круга
    B = (T[0], T[1] + L)
    lt, rt, top_pt = (T[0] - a, T[1] - b), (T[0] + a, T[1] - b), (T[0], T[1] - 2 * b)
    # мягкая тень под блоком
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([C - a * 1.05, B[1] - b * 0.75, C + a * 1.05, B[1] + b * 0.45], fill=(8, 40, 100, 120))
    out = Image.alpha_composite(out, sh.filter(ImageFilter.GaussianBlur(S * 0.025)))
    up = 32 * 32
    big = lambda im: im.resize((up, up), Image.NEAREST)
    out = Image.alpha_composite(out, warp(big(shade(front, 0.86)), lt, T, (B[0] - a, B[1] - b)))
    out = Image.alpha_composite(out, warp(big(shade(side, 0.64)), T, rt, B))
    out = Image.alpha_composite(out, warp(big(shade(top, 1.0)), top_pt, rt, lt))
    return out


def main():
    front = Image.open(os.path.join(TEX, 'fcs_front.png')).convert('RGBA')
    cols = brass_colors(front)
    img = Image.alpha_composite(background(), block(front, brass_face(cols, 'side'), brass_face(cols, 'top')))
    logo = img.resize((512, 512), Image.LANCZOS)
    for path in (os.path.join(ROOT, 'src', 'main', 'resources', 'logo.png'), os.path.join(ROOT, 'release', 'icon.png')):
        logo.save(path, optimize=True)
        print(path)


if __name__ == '__main__':
    main()
