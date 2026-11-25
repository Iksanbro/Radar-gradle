#!/usr/bin/env python3
"""Иконка мода: вычислитель управления огнём блоком Minecraft (пиксельные текстуры 32x32, изометрия инвентаря,
отражённая - экран справа) на синем круге с мягкой светлой сеткой и белой каймой.

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

# профиль круга от центра к краю (доля радиуса синего круга -> цвет): стальной синий, светлее к центру
PROFILE = [(0.0, (96, 168, 222)), (0.45, (89, 158, 214)), (0.61, (83, 150, 208)), (0.66, (81, 148, 206)),
           (0.71, (78, 144, 201)), (0.76, (74, 137, 193)), (0.81, (68, 129, 183)), (0.87, (62, 121, 172)),
           (0.92, (58, 114, 163)), (0.97, (57, 111, 159)), (1.0, (53, 108, 156))]


def profile(t):
    for (t0, c0), (t1, c1) in zip(PROFILE, PROFILE[1:]):
        if t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0))
    return PROFILE[-1][1]


def background():
    """Белая кайма, внутри синий круг: радиальный градиент и мягкие светлые полосы сетки (через центр, шаг ~0.24
    размера иконки)."""
    R_white, R_blue = S * 0.488, S * 0.461
    step, sigma = S * 0.242, S * 0.0125
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    px = img.load()
    for y in range(S):
        dy = y - C
        by = abs(dy) % step
        band_y = math.exp(-(min(by, step - by) / sigma) ** 2 / 2)
        for x in range(S):
            dx = x - C
            d = math.hypot(dx, dy)
            if d > R_white + 2:
                continue
            if d > R_blue:
                a = 255 if d <= R_white - 1 else int(255 * max(0.0, (R_white + 1 - d) / 2))
                w = min(1.0, (d - R_blue) / 3)                    # стык синего и белого - 3 px
                col = lerp(profile(1.0), (255, 255, 255), w)
                px[x, y] = col + (a,)
                continue
            col = profile(d / R_blue)
            bx = abs(dx) % step
            band = max(band_y, math.exp(-(min(bx, step - bx) / sigma) ** 2 / 2))
            if band > 0.01:
                col = lerp(col, (150, 210, 248), band * 0.34)
            px[x, y] = col + (255,)
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
    """Блок в изометрии инвентаря Minecraft, отражённый: верх светлее, лицо (справа) - среднее, бок (слева) - темнее."""
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    L = S * 0.31                                                  # длина ребра
    a, b = L * math.cos(math.radians(30)), L * math.sin(math.radians(30))
    T = (C, C - L * 0.5 + b + S * 0.005)                          # ближнее верхнее ребро; фигура - по центру круга
    B = (T[0], T[1] + L)
    lt, rt, top_pt = (T[0] - a, T[1] - b), (T[0] + a, T[1] - b), (T[0], T[1] - 2 * b)
    up = 32 * 32
    big = lambda im: im.resize((up, up), Image.NEAREST)
    # зеркально к инвентарю Minecraft: экран - на правой грани, свет справа (правая грань светлее левой)
    out = Image.alpha_composite(out, warp(big(shade(side, 0.64)), lt, T, (B[0] - a, B[1] - b)))
    out = Image.alpha_composite(out, warp(big(shade(front, 0.88)), T, rt, B))
    out = Image.alpha_composite(out, warp(big(shade(top, 1.0)), rt, top_pt, (T[0], T[1])))
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
