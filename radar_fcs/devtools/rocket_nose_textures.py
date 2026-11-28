#!/usr/bin/env python3
"""Текстуры носов ракет (32x32): тепловая головка (оранжевая полоса, тёмно-красная линза) и программируемый нос
(синяя полоса, панель с зелёным огнём). Раскладка (в единицах UV 0..16):
  корпус  - u 0..16, v 0..8  (бока ступеней: сталь с полосой и заклёпками);
  крышка  - u 0..8,  v 8..16 (верх ступеней: стальная пластина);
  вершина - u 8..16, v 8..16 (линза головки / панель с огнём).
Запуск: python3 devtools/rocket_nose_textures.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
OUT = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'radar_fcs', 'textures', 'block')


def lerp(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


STEEL_HI, STEEL, STEEL_LO, STEEL_DARK = (196, 202, 210), (150, 157, 168), (104, 110, 122), (58, 62, 72)


def body(d, stripe, stripe_lo):
    # корпус 32x16: вертикальный градиент стали, тёмные швы сверху и снизу, цветная полоса, заклёпки
    for y in range(16):
        for x in range(32):
            t = y / 15
            c = lerp(STEEL_HI, STEEL_LO, t)
            if (x * 7 + y * 3) % 11 == 0:
                c = lerp(c, STEEL_DARK, 0.15)                     # лёгкая зернистость
            d.point((x, y), fill=c)
    d.line([(0, 0), (31, 0)], fill=STEEL_DARK)
    d.line([(0, 15), (31, 15)], fill=STEEL_DARK)
    d.line([(0, 1), (31, 1)], fill=lerp(STEEL_HI, (255, 255, 255), 0.4))
    for x in range(32):                                            # полоса
        d.point((x, 6), fill=stripe)
        d.point((x, 7), fill=stripe)
        d.point((x, 8), fill=stripe_lo)
    for x in range(3, 32, 8):                                      # заклёпки
        d.point((x, 11), fill=STEEL_HI)
        d.point((x, 12), fill=STEEL_DARK)


def cap(d):
    # крышка 16x16 (u 0..8, v 8..16): пластина с фаской
    for y in range(16, 32):
        for x in range(0, 16):
            d.point((x, y), fill=lerp(STEEL_HI, STEEL, (x + y - 16) / 30))
    d.rectangle([0, 16, 15, 31], outline=STEEL_DARK)
    d.line([(1, 17), (14, 17)], fill=lerp(STEEL_HI, (255, 255, 255), 0.35))
    d.line([(1, 17), (1, 30)], fill=lerp(STEEL_HI, (255, 255, 255), 0.35))


def lens(d, inner, outer, glare):
    # вершина 16x16 (u 8..16, v 8..16): тёмное стекло головки с радиальным свечением и бликом
    cx, cy = 23.5, 23.5
    for y in range(16, 32):
        for x in range(16, 32):
            r = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 / 8.5
            d.point((x, y), fill=lerp(inner, outer, r))
    d.rectangle([16, 16, 31, 31], outline=(30, 16, 12))
    for (x, y) in ((19, 19), (20, 19), (19, 20)):
        d.point((x, y), fill=glare)


def panel(d, led):
    # вершина программируемого носа: тёмная панель, зелёный огонь, решётка
    for y in range(16, 32):
        for x in range(16, 32):
            d.point((x, y), fill=lerp((70, 76, 88), (40, 44, 54), (y - 16) / 15))
    d.rectangle([16, 16, 31, 31], outline=STEEL_DARK)
    d.rectangle([21, 20, 26, 23], fill=(20, 24, 30))
    d.point((22, 21), fill=led)
    d.point((23, 21), fill=lerp(led, (255, 255, 255), 0.4))
    d.point((22, 22), fill=lerp(led, (0, 0, 0), 0.35))
    for x in range(19, 29, 2):
        d.line([(x, 26), (x, 29)], fill=(28, 30, 36))


def make(name, stripe, stripe_lo, top):
    im = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    body(d, stripe, stripe_lo)
    cap(d)
    top(d)
    path = os.path.join(OUT, name + '.png')
    im.save(path, optimize=True)
    print(path)


if __name__ == '__main__':
    make('heat_seeker_nose', (236, 128, 36), (176, 84, 20),
         lambda d: lens(d, (255, 120, 48), (70, 12, 10), (255, 230, 200)))
    make('network_nose', (60, 140, 230), (32, 86, 170), lambda d: panel(d, (80, 240, 110)))
