#!/usr/bin/env python3
"""Иконка мода: круглый значок (синяя сетка, белое кольцо) с вычислителем управления огнём в изометрии.

Рисуется с нуля (PIL), с запасом разрешения и сглаживанием. Выход: src/main/resources/logo.png (логотип в списке модов)
и release/icon.png (страница мода, 512x512).
Запуск: python3 devtools/mod_icon.py
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
S = 2048                      # рабочее разрешение (итог 512 - сглаживание x4)
C = S // 2


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def vgrad(w, h, top, bottom):
    im = Image.new('RGBA', (w, h))
    d = ImageDraw.Draw(im)
    for y in range(h):
        d.line([(0, y), (w, y)], fill=lerp(top, bottom, y / max(1, h - 1)))
    return im


def radial(w, h, inner, outer, cx=None, cy=None, r=None):
    cx = w / 2 if cx is None else cx
    cy = h / 2 if cy is None else cy
    r = max(w, h) / 2 if r is None else r
    im = Image.new('RGBA', (w, h))
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = min(1.0, math.hypot(x - cx, y - cy) / r)
            px[x, y] = lerp(inner, outer, t)
    return im


def rivet(d, x, y, r):
    """Золотая заклёпка с бликом (как точки на снаряде образца)."""
    d.ellipse([x - r - 4, y - r - 4, x + r + 4, y + r + 4], fill=(60, 36, 10, 255))
    d.ellipse([x - r, y - r, x + r, y + r], fill=(214, 150, 40, 255))
    d.ellipse([x - r * 0.75, y - r * 0.8, x + r * 0.45, y + r * 0.3], fill=(246, 196, 82, 255))
    d.ellipse([x - r * 0.5, y - r * 0.6, x - r * 0.05, y - r * 0.15], fill=(255, 238, 170, 255))


def brass_panel(w, h, vents=False, grille=False):
    """Латунная панель: рамка с фаской, середина темнее, заклёпки по углам."""
    im = vgrad(w, h, (236, 186, 92, 255), (170, 112, 40, 255))
    d = ImageDraw.Draw(im)
    b = int(w * 0.09)
    d.rectangle([0, 0, w - 1, h - 1], outline=(92, 56, 18, 255), width=10)
    d.rectangle([10, 10, w - 11, 22], fill=(252, 220, 140, 255))                 # блик верхней кромки
    inner = vgrad(w - 2 * b, h - 2 * b, (196, 138, 56, 255), (140, 90, 30, 255))
    im.paste(inner, (b, b))
    d.rectangle([b, b, w - b, h - b], outline=(110, 68, 22, 255), width=8)
    d.line([(b, h - b), (w - b, h - b)], fill=(250, 214, 130, 255), width=6)      # нижняя фаска - светлая
    if vents:
        for i in range(5):
            y = int(h * 0.30 + i * h * 0.095)
            d.rounded_rectangle([int(w * 0.24), y, int(w * 0.76), y + int(h * 0.045)], radius=14, fill=(40, 26, 10, 255))
            d.line([(int(w * 0.25), y + int(h * 0.045) + 3), (int(w * 0.75), y + int(h * 0.045) + 3)], fill=(240, 196, 110, 255), width=4)
    if grille:
        m = int(w * 0.28)
        d.rounded_rectangle([m, m, w - m, h - m], radius=30, fill=(46, 30, 12, 255))
        for i in range(1, 6):
            x = m + (w - 2 * m) * i / 6
            d.line([(x, m + 14), (x, h - m - 14)], fill=(150, 100, 36, 255), width=10)
    r = int(w * 0.045)
    for x, y in ((b // 2 + 6, b // 2 + 6), (w - b // 2 - 6, b // 2 + 6), (b // 2 + 6, h - b // 2 - 6), (w - b // 2 - 6, h - b // 2 - 6)):
        rivet(d, x, y, r)
    return im


def front_panel(w, h):
    """Лицевая сторона: латунная рамка, экран радара (развёртка, отметки целей), строка ламп."""
    im = brass_panel(w, h)
    d = ImageDraw.Draw(im)
    b = int(w * 0.11)
    sx0, sy0, sx1, sy1 = b, b, w - b, int(h * 0.70)
    # экран
    scr = radial(sx1 - sx0, sy1 - sy0, (18, 70, 40, 255), (6, 22, 14, 255))
    sd = ImageDraw.Draw(scr)
    sw, sh = scr.size
    cx, cy = sw / 2, sh / 2
    R = min(sw, sh) * 0.46
    for k in (1.0, 0.68, 0.36):
        sd.ellipse([cx - R * k, cy - R * k, cx + R * k, cy + R * k], outline=(60, 190, 100, 255), width=6)
    sd.line([(cx - R, cy), (cx + R, cy)], fill=(50, 160, 85, 255), width=4)
    sd.line([(cx, cy - R), (cx, cy + R)], fill=(50, 160, 85, 255), width=4)
    # луч развёртки: веер с затуханием
    wedge = Image.new('RGBA', scr.size, (0, 0, 0, 0))
    wd = ImageDraw.Draw(wedge)
    for i in range(40):
        a0 = -65 - i * 1.4
        alpha = int(170 * (1 - i / 40))
        wd.pieslice([cx - R, cy - R, cx + R, cy + R], a0 - 1.6, a0, fill=(90, 255, 140, alpha))
    wd.line([(cx, cy), (cx + R * math.cos(math.radians(-65)), cy + R * math.sin(math.radians(-65)))], fill=(190, 255, 205, 255), width=7)
    scr = Image.alpha_composite(scr, wedge)
    sd = ImageDraw.Draw(scr)
    # отметки: свои зелёные, цель - красная с кольцом захвата
    glow = Image.new('RGBA', scr.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    blips = [(cx - R * 0.45, cy + R * 0.25, (120, 255, 150)), (cx + R * 0.35, cy + R * 0.45, (120, 255, 150)),
             (cx + R * 0.52, cy - R * 0.55, (255, 90, 60))]
    for x, y, col in blips:
        gd.ellipse([x - 34, y - 34, x + 34, y + 34], fill=col + (110,))
    glow = glow.filter(ImageFilter.GaussianBlur(14))
    scr = Image.alpha_composite(scr, glow)
    sd = ImageDraw.Draw(scr)
    for x, y, col in blips:
        sd.ellipse([x - 15, y - 15, x + 15, y + 15], fill=col + (255,))
    tx, ty = blips[2][0], blips[2][1]
    sd.ellipse([tx - 42, ty - 42, tx + 42, ty + 42], outline=(255, 120, 90, 255), width=6)
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        sd.line([(tx + dx * 48, ty + dy * 48), (tx + dx * 66, ty + dy * 66)], fill=(255, 120, 90, 255), width=6)
    # стеклянный блик
    gl = Image.new('RGBA', scr.size, (0, 0, 0, 0))
    ImageDraw.Draw(gl).polygon([(0, 0), (sw * 0.55, 0), (sw * 0.25, sh * 0.45), (0, sh * 0.62)], fill=(255, 255, 255, 34))
    scr = Image.alpha_composite(scr, gl)
    mask = Image.new('L', scr.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, sw - 1, sh - 1], radius=40, fill=255)
    d.rounded_rectangle([sx0 - 14, sy0 - 14, sx1 + 14, sy1 + 14], radius=52, fill=(36, 24, 10, 255))
    im.paste(scr, (sx0, sy0), mask)
    # строка ламп и кнопок
    py0, py1 = int(h * 0.75), h - b
    d.rounded_rectangle([b, py0, w - b, py1], radius=26, fill=(30, 34, 40, 255), outline=(20, 14, 6, 255), width=6)
    ly = (py0 + py1) / 2
    lr = (py1 - py0) * 0.28
    for i, col in enumerate(((70, 230, 90), (250, 200, 50), (240, 70, 60))):
        lx = b + (w - 2 * b) * (0.12 + i * 0.15)
        d.ellipse([lx - lr - 6, ly - lr - 6, lx + lr + 6, ly + lr + 6], fill=(12, 12, 14, 255))
        d.ellipse([lx - lr, ly - lr, lx + lr, ly + lr], fill=col + (255,))
        d.ellipse([lx - lr * 0.55, ly - lr * 0.65, lx - lr * 0.05, ly - lr * 0.2], fill=(255, 255, 255, 200))
    for i in range(3):
        bx = b + (w - 2 * b) * (0.62 + i * 0.12)
        d.rounded_rectangle([bx - lr * 0.9, ly - lr * 1.1, bx + lr * 0.9, ly + lr * 1.1], radius=10, fill=(150, 156, 166, 255))
        d.rounded_rectangle([bx - lr * 0.9, ly - lr * 1.1, bx + lr * 0.9, ly - lr * 0.4], radius=10, fill=(200, 206, 214, 255))
    return im


def shade(im, k):
    """Освещённость грани: k < 1 - темнее, k > 1 - светлее (альфа без изменений)."""
    r, g, b, a = im.split()
    f = (lambda v: min(255, int(v * k)))
    return Image.merge('RGBA', (r.point(f), g.point(f), b.point(f), a))


def warp(face, tl, tr, bl):
    """Картинку грани - на параллелограмм холста (углы: верх-лево, верх-право, низ-лево)."""
    w, h = face.size
    # прямое: X = tl.x + u*(tr.x-tl.x)/w + v*(bl.x-tl.x)/h ; обратное для PIL
    ax, ay = (tr[0] - tl[0]) / w, (tr[1] - tl[1]) / w
    bx, by = (bl[0] - tl[0]) / h, (bl[1] - tl[1]) / h
    det = ax * by - bx * ay
    ia, ib, id_, ie = by / det, -bx / det, -ay / det, ax / det
    c = -(ia * tl[0] + ib * tl[1])
    f = -(id_ * tl[0] + ie * tl[1])
    return face.transform((S, S), Image.AFFINE, (ia, ib, c, id_, ie, f), resample=Image.BICUBIC, fillcolor=(0, 0, 0, 0))


def badge():
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    R_out = S * 0.485
    R_ring = S * 0.455
    # фон круга: синий с сеткой и свечением в центре
    bg = radial(S, S, (84, 160, 248, 255), (34, 100, 210, 255), r=R_ring)
    gd = ImageDraw.Draw(bg)
    step = S / 13
    for i in range(-1, 15):
        p = i * step + (C % step)
        gd.line([(p, 0), (p, S)], fill=(140, 198, 255, 255), width=5)
        gd.line([(0, p), (S, p)], fill=(140, 198, 255, 255), width=5)
    glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([C - S * 0.3, C - S * 0.3, C + S * 0.3, C + S * 0.3], fill=(150, 210, 255, 90))
    bg = Image.alpha_composite(bg, glow.filter(ImageFilter.GaussianBlur(S * 0.06)))
    # затемнение к краю (объём)
    vig = radial(S, S, (0, 0, 0, 0), (0, 20, 70, 90), r=R_ring)
    bg = Image.alpha_composite(bg, vig)
    inner = Image.new('L', (S, S), 0)
    ImageDraw.Draw(inner).ellipse([C - R_ring, C - R_ring, C + R_ring, C + R_ring], fill=255)
    # кольцо: тёмная кайма, белое кольцо
    d = ImageDraw.Draw(out)
    d.ellipse([C - R_out, C - R_out, C + R_out, C + R_out], fill=(16, 30, 58, 255))
    d.ellipse([C - R_out + 12, C - R_out + 12, C + R_out - 12, C + R_out - 12], fill=(246, 250, 255, 255))
    d.ellipse([C - R_ring - 8, C - R_ring - 8, C + R_ring + 8, C + R_ring + 8], fill=(150, 175, 205, 255))
    out.paste(bg, (0, 0), inner)

    # вычислитель в изометрии
    L = S * 0.30
    a, b = L * math.cos(math.radians(30)), L * math.sin(math.radians(30))
    T = (C, C - L * 0.5 + b * 0.5 + S * 0.01)        # ближнее верхнее ребро (центр фигуры - в центре круга)
    B = (T[0], T[1] + L)
    lt = (T[0] - a, T[1] - b)
    rt = (T[0] + a, T[1] - b)
    top = (T[0], T[1] - 2 * b)
    # тень под корпусом
    sh = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([C - a * 1.15, B[1] - b * 0.55, C + a * 1.15, B[1] + b * 0.35], fill=(0, 10, 30, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(S * 0.02))
    out = Image.alpha_composite(out, Image.composite(sh, Image.new('RGBA', (S, S), (0, 0, 0, 0)), inner))
    F = 1024
    out = Image.alpha_composite(out, warp(shade(front_panel(F, F), 1.0), lt, T, (B[0] - a, B[1] - b)))
    out = Image.alpha_composite(out, warp(shade(brass_panel(F, F, vents=True), 0.72), T, rt, B))
    out = Image.alpha_composite(out, warp(shade(brass_panel(F, F, grille=True), 1.12), top, rt, lt))
    # контур фигуры и светлые верхние рёбра
    d = ImageDraw.Draw(out)
    sil = [lt, top, rt, (B[0] + a, B[1] - b), B, (B[0] - a, B[1] - b)]
    d.line(sil + [sil[0]], fill=(28, 16, 4, 255), width=14, joint='curve')
    d.line([lt, T, rt], fill=(255, 226, 150, 255), width=8)
    d.line([T, B], fill=(120, 76, 26, 255), width=8)
    return out


def main():
    img = badge()
    logo = img.resize((512, 512), Image.LANCZOS)
    for path in (os.path.join(ROOT, 'src', 'main', 'resources', 'logo.png'), os.path.join(ROOT, 'release', 'icon.png')):
        logo.save(path, optimize=True)
        print(path)


if __name__ == '__main__':
    main()
