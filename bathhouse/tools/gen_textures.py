#!/usr/bin/env python3
"""Procedurally draws the mod's 16x16 pixel-art textures (and 18x18 effect icons).

Run from the mod root:  python3 tools/gen_textures.py
Requires Pillow.
"""
import math
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'bathhouse', 'textures')


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(4))


def new(size=16, fill=(0, 0, 0, 0)):
    return Image.new('RGBA', (size, size), fill)


def noise(img, base, var, seed, box=None):
    rnd = random.Random(seed)
    x0, y0, x1, y1 = box or (0, 0, img.width, img.height)
    for y in range(y0, y1):
        for x in range(x0, x1):
            img.putpixel((x, y), shade(base, 1 + rnd.uniform(-var, var)))


def rect(img, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if 0 <= x < img.width and 0 <= y < img.height:
                img.putpixel((x, y), c)


def frame(img, x0, y0, x1, y1, c):
    for x in range(x0, x1 + 1):
        img.putpixel((x, y0), c)
        img.putpixel((x, y1), c)
    for y in range(y0, y1 + 1):
        img.putpixel((x0, y), c)
        img.putpixel((x1, y), c)


def save(img, path):
    full = os.path.join(ROOT, path + '.png')
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)


# ---------------------------------------------------------------- surfaces

def stones(seed, base='#2b2b2e'):
    """Top of a heater: a heap of rounded stones."""
    img = new()
    noise(img, hexc(base), 0.15, seed)
    rnd = random.Random(seed)
    palette = [hexc('#8d8a86'), hexc('#6f6c69'), hexc('#a29d97'), hexc('#5d5a58'), hexc('#7b746c')]
    spots = [(2, 2), (7, 1), (12, 2), (4, 6), (10, 6), (14, 7), (1, 10), (6, 11), (11, 11), (14, 13), (3, 14), (8, 15)]
    for cx, cy in spots:
        r = rnd.choice([2, 2, 3])
        col = rnd.choice(palette)
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                d = math.hypot(x - cx, (y - cy) * 1.15)
                if d <= r + 0.2 and 0 <= x < 16 and 0 <= y < 16:
                    f = 1.25 if (x - cx) + (y - cy) < -r * 0.6 else (0.75 if (x - cx) + (y - cy) > r * 0.7 else 1.0)
                    img.putpixel((x, y), shade(col, f * (1 + rnd.uniform(-0.05, 0.05))))
    return img


def bricks(seed, mortar='#b9ad9c', reds=('#9b4a35', '#8a3f2d', '#a85640', '#7d3a2a'), h=4, w=8):
    img = new()
    rnd = random.Random(seed)
    m = hexc(mortar)
    for y in range(16):
        row = y // h
        off = (w // 2) if row % 2 else 0
        for x in range(16):
            if y % h == h - 1 or (x + off) % w == w - 1:
                img.putpixel((x, y), shade(m, 1 + rnd.uniform(-0.06, 0.06)))
    for row in range(16 // h):
        off = (w // 2) if row % 2 else 0
        for bx in range(-1, 16 // w + 2):
            col = hexc(rnd.choice(reds))
            for y in range(row * h, row * h + h - 1):
                for x in range(bx * w - off, bx * w - off + w - 1):
                    if 0 <= x < 16:
                        f = 1.12 if y == row * h else 1.0
                        img.putpixel((x, y), shade(col, f * (1 + rnd.uniform(-0.07, 0.07))))
    return img


def planks(seed, base='#a87a4f', dark='#6e4a2b'):
    img = new()
    rnd = random.Random(seed)
    noise(img, hexc(base), 0.06, seed)
    for y in range(16):
        for x in range(16):
            if y % 4 == 3:
                img.putpixel((x, y), hexc(dark))
            elif rnd.random() < 0.08:
                img.putpixel((x, y), shade(hexc(base), 0.85))
    for row in range(4):
        x = rnd.randrange(2, 14)
        img.putpixel((x, row * 4 + 1), hexc(dark))
    return img


def steel(seed, base='#3c4046', var=0.05, vertical=True):
    img = new()
    rnd = random.Random(seed)
    b = hexc(base)
    for y in range(16):
        for x in range(16):
            t = (x if vertical else y) / 15.0
            f = 1.08 - 0.16 * abs(t - 0.35)
            img.putpixel((x, y), shade(b, f * (1 + rnd.uniform(-var, var))))
    return img


def rivets(img, color, pts):
    for x, y in pts:
        img.putpixel((x, y), shade(color, 1.5))
        if x + 1 < 16:
            img.putpixel((x + 1, y), shade(color, 0.6))
        if y + 1 < 16:
            img.putpixel((x, y + 1), shade(color, 0.6))


# ---------------------------------------------------------------- fireboxes

def fire_glow(img, x0, y0, x1, y1, lit, seed):
    rnd = random.Random(seed)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if lit:
                t = (y - y0) / max(1, (y1 - y0))
                c = mix(hexc('#ffe08a'), hexc('#e2531c'), 1 - t)
                if rnd.random() < 0.25:
                    c = hexc('#fff3b0') if t > 0.5 else hexc('#c23a12')
                img.putpixel((x, y), c)
            else:
                img.putpixel((x, y), shade(hexc('#1a1716'), 1 + rnd.uniform(-0.2, 0.2)))


def grate(img, x0, y0, x1, y1, color, lit, seed, vertical=True):
    fire_glow(img, x0, y0, x1, y1, lit, seed)
    if vertical:
        for x in range(x0, x1 + 1, 2):
            for y in range(y0, y1 + 1):
                img.putpixel((x, y), color)
    else:
        for y in range(y0, y1 + 1, 2):
            for x in range(x0, x1 + 1):
                img.putpixel((x, y), color)


def inner_heater():
    side = steel(11, '#34383d')
    frame(side, 0, 0, 15, 15, hexc('#202326'))
    for y in (3, 12):
        for x in range(1, 15):
            side.putpixel((x, y), hexc('#2a2d31'))
    rivets(side, hexc('#4a4f55'), [(2, 2), (13, 2), (2, 13), (13, 13)])
    save(side, 'block/inner_heater_side')
    save(stones(12), 'block/inner_heater_top')
    for lit in (False, True):
        f = side.copy()
        rect(f, 3, 7, 12, 14, hexc('#1f2124'))
        grate(f, 4, 8, 11, 13, hexc('#454a50'), lit, 13)
        rect(f, 3, 4, 12, 5, hexc('#26292c'))
        for x in range(4, 12, 2):
            f.putpixel((x, 4), hexc('#11120f') if not lit else hexc('#7a2c10'))
        f.putpixel((12, 10), hexc('#8a8f96'))
        save(f, 'block/inner_heater_front' + ('_on' if lit else ''))


def outer_heater():
    side = bricks(21, mortar='#c9c0b3', reds=('#b9b2a7', '#aba398', '#c2bbb0', '#a0988c'))
    frame(side, 0, 0, 15, 15, hexc('#6d665d'))
    save(side, 'block/outer_heater_side')
    save(stones(22, '#3a3633'), 'block/outer_heater_top')
    for lit in (False, True):
        f = bricks(23)
        rect(f, 2, 6, 13, 15, hexc('#2b2d30'))
        frame(f, 2, 6, 13, 15, hexc('#4b4f55'))
        grate(f, 4, 8, 11, 13, hexc('#3a3d42'), lit, 24)
        f.putpixel((12, 11), hexc('#a7adb5'))
        rect(f, 3, 2, 12, 3, hexc('#3b3e43'))
        save(f, 'block/outer_heater_front' + ('_on' if lit else ''))


def brick_stove():
    side = bricks(31, mortar='#e6e0d4', reds=('#f2eee6', '#e9e4da', '#ddd6ca'))
    for x in range(16):
        side.putpixel((x, 15), hexc('#8c8273'))
    save(side, 'block/brick_stove_side')
    top = steel(32, '#2f2f31', vertical=False)
    frame(top, 0, 0, 15, 15, hexc('#c9c1b4'))
    for r, c in ((5, '#1d1d1f'), (3, '#47474b')):
        for a in range(0, 360, 10):
            x = int(round(8 + r * math.cos(math.radians(a)) - 0.5))
            y = int(round(8 + r * math.sin(math.radians(a)) - 0.5))
            top.putpixel((x, y), hexc(c))
    save(top, 'block/brick_stove_top')
    for lit in (False, True):
        f = side.copy()
        rect(f, 3, 8, 12, 14, hexc('#2a2b2e'))
        frame(f, 3, 8, 12, 14, hexc('#55585e'))
        grate(f, 5, 10, 10, 13, hexc('#404349'), lit, 33)
        # arch
        for x in range(4, 12):
            f.putpixel((x, 7), hexc('#9d9282'))
        rect(f, 6, 2, 9, 4, hexc('#35373b'))
        f.putpixel((9, 3), hexc('#8a8f96'))
        save(f, 'block/brick_stove_front' + ('_on' if lit else ''))


def iron_stove():
    side = steel(41, '#232325', var=0.08)
    for y in range(2, 15, 3):
        for x in range(1, 15):
            side.putpixel((x, y), hexc('#353538'))
            side.putpixel((x, y + 1), hexc('#151516'))
    frame(side, 0, 0, 15, 15, hexc('#0f0f10'))
    save(side, 'block/iron_stove_side')
    top = steel(42, '#29292b', vertical=False)
    frame(top, 0, 0, 15, 15, hexc('#0f0f10'))
    for r, c in ((5, '#121213'), (4, '#3d3d40')):
        for a in range(0, 360, 8):
            x = int(round(8 + r * math.cos(math.radians(a)) - 0.5))
            y = int(round(8 + r * math.sin(math.radians(a)) - 0.5))
            top.putpixel((x, y), hexc(c))
    top.putpixel((8, 8), hexc('#5a5a5f'))
    save(top, 'block/iron_stove_top')
    for lit in (False, True):
        f = steel(43, '#232325', var=0.08)
        frame(f, 0, 0, 15, 15, hexc('#0f0f10'))
        # arched door
        rect(f, 3, 5, 12, 14, hexc('#18181a'))
        for x in range(4, 12):
            f.putpixel((x, 4), hexc('#18181a'))
        fire_glow(f, 5, 7, 10, 12, lit, 44)
        for y in range(7, 13, 2):
            for x in range(5, 11):
                f.putpixel((x, y), hexc('#2a2a2d'))
        f.putpixel((11, 10), hexc('#b9873a'))
        rect(f, 2, 1, 13, 2, hexc('#3a3a3e'))
        save(f, 'block/iron_stove_front' + ('_on' if lit else ''))


def electric_heater():
    side = steel(51, '#9aa3ab', var=0.04)
    for y in range(4, 13, 2):
        for x in range(3, 13):
            side.putpixel((x, y), hexc('#4c5359'))
    frame(side, 0, 0, 15, 15, hexc('#6c747b'))
    save(side, 'block/electric_heater_side')
    save(stones(52, '#4b4f53'), 'block/electric_heater_top')
    for lit in (False, True):
        f = steel(53, '#a3acb4', var=0.04)
        frame(f, 0, 0, 15, 15, hexc('#6c747b'))
        rect(f, 3, 3, 12, 8, hexc('#2c3136'))
        frame(f, 3, 3, 12, 8, hexc('#5d656c'))
        # display
        rect(f, 4, 4, 9, 7, hexc('#ff6a3d') if lit else hexc('#3a2a24'))
        if lit:
            for x in (5, 7):
                f.putpixel((x, 5), hexc('#ffd0b8'))
        # knob
        for (x, y) in ((10, 11), (11, 11), (10, 12), (11, 12)):
            f.putpixel((x, y), hexc('#30353a'))
        f.putpixel((10, 11), hexc('#d0d5da'))
        f.putpixel((5, 11), hexc('#e43b2c') if lit else hexc('#5a3a37'))
        for y in range(13, 15):
            for x in range(3, 13, 2):
                f.putpixel((x, y), hexc('#4c5359'))
        save(f, 'block/electric_heater_front' + ('_on' if lit else ''))


def steam_generator():
    side = new()
    noise(side, hexc('#e9eef0'), 0.03, 61)
    for x in range(16):
        side.putpixel((x, 11), hexc('#3f7fb8'))
        side.putpixel((x, 12), hexc('#336a9b'))
    rect(side, 11, 0, 12, 15, hexc('#a8b0b6'))
    for y in range(0, 16, 5):
        side.putpixel((11, y), hexc('#6e767c'))
        side.putpixel((12, y), hexc('#6e767c'))
    frame(side, 0, 0, 15, 15, hexc('#aab3b9'))
    save(side, 'block/steam_generator_side')
    top = steel(62, '#b9c1c7', vertical=False)
    frame(top, 0, 0, 15, 15, hexc('#8a9399'))
    rect(top, 6, 6, 9, 9, hexc('#59626a'))
    rect(top, 7, 7, 8, 8, hexc('#1f2326'))
    save(top, 'block/steam_generator_top')
    for lit in (False, True):
        f = side.copy()
        # round dial
        for y in range(2, 10):
            for x in range(3, 11):
                d = math.hypot(x - 6.5, y - 5.5)
                if d <= 3.8:
                    f.putpixel((x, y), hexc('#3a4045') if d > 3.0 else hexc('#fbfbf7'))
        f.putpixel((6, 5), hexc('#202020'))
        f.putpixel((7, 4) if lit else (5, 6), hexc('#d22b2b'))
        f.putpixel((8, 3) if lit else (4, 7), hexc('#d22b2b'))
        f.putpixel((13, 3), hexc('#43d16a') if lit else hexc('#2f4a37'))
        f.putpixel((13, 5), hexc('#e5c13a') if lit else hexc('#4d4527'))
        rect(f, 3, 13, 9, 14, hexc('#7c858c'))
        save(f, 'block/steam_generator_front' + ('_on' if lit else ''))


# ---------------------------------------------------------------- small blocks

def gauge():
    wood = planks(71, '#b58456', '#7b5130')
    save(wood, 'block/bath_gauge_side')
    cx = cy = 7.5
    for active in (False, True):
        f = new()
        for y in range(3, 13):
            for x in range(3, 13):
                d = math.hypot(x - cx, y - cy)
                if d <= 5.1:
                    f.putpixel((x, y), shade(hexc('#9a6a3e'), 1.12 if x + y < 15 else 0.85))
                if d <= 4.0:
                    f.putpixel((x, y), hexc('#f4efe2'))
        # scale along the upper half: cold blue -> hot red
        for i, c in enumerate(('#3d7fd6', '#3d7fd6', '#e6b23a', '#e06a2a', '#c4302b', '#c4302b')):
            a = math.radians(200 + i * 28)
            f.putpixel((int(round(cx + 3.3 * math.cos(a))), int(round(cy + 3.3 * math.sin(a)))), hexc(c))
        # needle from the hub
        angle = math.radians(318 if active else 225)
        for t in (0.0, 0.8, 1.6, 2.4):
            x = int(round(cx + t * math.cos(angle)))
            y = int(round(cy + t * math.sin(angle)))
            f.putpixel((x, y), hexc('#b3241f') if t > 1.0 else hexc('#2b2b2b'))
        # status lamp
        f.putpixel((7, 10), hexc('#3fe06b') if active else hexc('#7a7a7a'))
        f.putpixel((8, 10), hexc('#2fb556') if active else hexc('#5f5f5f'))
        save(f, 'block/bath_gauge_front' + ('_active' if active else ''))


def chimney():
    img = bricks(81, mortar='#b5a998', reds=('#8f3e2c', '#7f3626', '#9a4834'), h=4, w=6)
    save(img, 'block/chimney')
    top = img.copy()
    rect(top, 5, 5, 10, 10, hexc('#1b1614'))
    rect(top, 6, 6, 9, 9, hexc('#0b0908'))
    save(top, 'block/chimney_top')


def smoke_vent():
    for open_ in (False, True):
        img = planks(91, '#8b6440', '#563a22')
        frame(img, 0, 0, 15, 15, hexc('#4a311c'))
        frame(img, 1, 1, 14, 14, hexc('#6b4a2c'))
        rect(img, 4, 5, 11, 10, hexc('#5b3f25'))
        if open_:
            rnd = random.Random(92)
            for y in range(5, 11):
                for x in range(4, 12):
                    img.putpixel((x, y), shade(hexc('#121010'), 1 + rnd.uniform(-0.3, 0.3)))
            for x in range(3, 13):
                img.putpixel((x, 4), hexc('#2d2520'))
            # board slid aside
            rect(img, 12, 5, 14, 10, hexc('#7d5a38'))
        else:
            for y in range(5, 11):
                img.putpixel((4, y), hexc('#3f2a17'))
                img.putpixel((11, y), hexc('#3f2a17'))
            img.putpixel((10, 8), hexc('#2a2a2a'))
        save(img, 'block/smoke_vent' + ('_open' if open_ else ''))


# ---------------------------------------------------------------- items

def ladle():
    for filled in (False, True):
        img = new()
        handle = hexc('#9c6b3d')
        for i in range(8):
            x, y = 13 - i, 2 + i
            img.putpixel((x, y), handle)
            img.putpixel((x, y + 1) if i % 2 else (x - 1, y), shade(handle, 0.75))
        img.putpixel((14, 1), shade(handle, 0.7))
        # bowl
        for y in range(8, 15):
            for x in range(1, 9):
                d = math.hypot(x - 4.5, (y - 10.5) * 1.15)
                if d <= 3.9:
                    img.putpixel((x, y), shade(hexc('#a8763f'), 0.75 if d > 3.0 else 1.0))
        if filled:
            for y in range(9, 12):
                for x in range(2, 8):
                    d = math.hypot(x - 4.5, (y - 10.5) * 1.15)
                    if d <= 2.6:
                        img.putpixel((x, y), hexc('#3f76e4') if (x + y) % 3 else hexc('#7fa6f2'))
        else:
            for y in range(9, 12):
                for x in range(2, 8):
                    d = math.hypot(x - 4.5, (y - 10.5) * 1.15)
                    if d <= 2.6:
                        img.putpixel((x, y), hexc('#6b4a28'))
        save(img, 'item/ladle' + ('_filled' if filled else ''))


def broom():
    img = new()
    rnd = random.Random(101)
    # twig handle bottom-left
    for i in range(6):
        img.putpixel((1 + i, 14 - i), hexc('#6b4a2b'))
        img.putpixel((2 + i, 14 - i), hexc('#8a6440'))
    # string tie
    img.putpixel((6, 9), hexc('#e8e2d0'))
    img.putpixel((7, 9), hexc('#e8e2d0'))
    img.putpixel((7, 8), hexc('#e8e2d0'))
    # leafy head
    greens = [hexc('#5f9b3a'), hexc('#7bb446'), hexc('#4b7f2d'), hexc('#8fc45a'), hexc('#a3c94f')]
    for y in range(0, 10):
        for x in range(6, 16):
            d = math.hypot((x - 11.5) * 0.9, (y - 4.5) * 1.05)
            if d <= 4.9 + rnd.uniform(-0.6, 0.4) and not (x - 7 + (9 - y)) < 1:
                img.putpixel((x, y), rnd.choice(greens))
    for (x, y) in ((9, 6), (11, 4), (13, 3), (10, 2)):
        img.putpixel((x, y), hexc('#e9e5d6'))  # birch twig highlights
    save(img, 'item/bath_broom')


# ---------------------------------------------------------------- effect icons (18x18)

def effect_icons():
    # purity: water drop
    img = new(18)
    for y in range(18):
        for x in range(18):
            cx, cy = 8.5, 11.0
            d = math.hypot(x - cx, y - cy)
            top = y < cy and abs(x - cx) <= (y - 2) * 0.55
            if d <= 5.2 or (top and y >= 2):
                c = hexc('#4fb3d9')
                if d <= 5.2 and d > 4.3 or (top and abs(x - cx) > (y - 2) * 0.55 - 1):
                    c = hexc('#2c7fa8')
                img.putpixel((x, y), c)
    for (x, y) in ((6, 10), (6, 11), (7, 9), (6, 12)):
        img.putpixel((x, y), hexc('#d9f3ff'))
    save(img, 'mob_effect/purity')

    # fortitude: heart with a leaf of steam above
    img = new(18)
    for y in range(18):
        for x in range(18):
            X = (x - 8.5) / 6.5
            Y = (y - 9.0) / 6.5
            v = (X * X + Y * Y - 1) ** 3 - X * X * (-Y) ** 3
            if v <= 0:
                img.putpixel((x, y), hexc('#c9452f'))
    for y in range(18):
        for x in range(18):
            if img.getpixel((x, y))[3] and (x == 0 or y == 0 or not img.getpixel((x - 1, y))[3] or not img.getpixel((x, y - 1))[3]
                                             or x == 17 or not img.getpixel((min(17, x + 1), y))[3] or not img.getpixel((x, min(17, y + 1)))[3]):
                img.putpixel((x, y), hexc('#7d2216'))
    for (x, y) in ((5, 6), (6, 5), (5, 7)):
        img.putpixel((x, y), hexc('#f3a08d'))
    save(img, 'mob_effect/fortitude')

    # tempering: flame
    img = new(18)
    for y in range(18):
        for x in range(18):
            t = y / 17.0
            half = 6.0 * math.sin(math.pi * min(1.0, t * 1.15)) * (0.55 + 0.45 * t)
            sway = 1.2 * math.sin(t * 5.0)
            if abs(x - 8.5 - sway * (1 - t)) <= half and y >= 1:
                inner = abs(x - 8.5 - sway * (1 - t)) <= half * 0.5 and t > 0.45
                img.putpixel((x, y), hexc('#ffe27a') if inner else (hexc('#f08a24') if t > 0.3 else hexc('#d9481e')))
    save(img, 'mob_effect/lightness')

    # tempering (contrast): ice crystal / snowflake
    img = new(18)
    c1, c2 = hexc('#bfe6ff'), hexc('#3f8fd6')
    for i in range(-7, 8):
        for (x, y) in ((8 + i, 8), (8, 8 + i), (8 + i, 8 + i), (8 + i, 8 - i)):
            if 0 <= x < 18 and 0 <= y < 18:
                img.putpixel((x, y), c2 if abs(i) > 4 else c1)
    for (dx, dy) in ((-5, -3), (-3, -5), (5, -3), (3, -5), (-5, 3), (-3, 5), (5, 3), (3, 5)):
        img.putpixel((8 + dx, 8 + dy), c2)
    img.putpixel((8, 8), hexc('#ffffff'))
    save(img, 'mob_effect/tempering')

    # bliss: warm pool with rising steam
    img = new(18)
    for y in range(11, 17):
        for x in range(1, 17):
            if math.hypot((x - 8.5) / 8.0, (y - 13.5) / 3.2) <= 1.0:
                img.putpixel((x, y), hexc('#d47aa6') if y > 12 else hexc('#f2b6d2'))
    for k, x0 in enumerate((4, 8, 12)):
        for y in range(1, 10):
            x = int(round(x0 + 1.2 * math.sin(y * 0.9 + k)))
            img.putpixel((x, y), hexc('#f7e3ee') if y % 3 else hexc('#e7a9c8'))
    save(img, 'mob_effect/bliss')


# ---------------------------------------------------------------- plunges and lounger

def staves(seed, base, seam, hoop='#6b7279'):
    img = new()
    rnd = random.Random(seed)
    b = hexc(base)
    for y in range(16):
        for x in range(16):
            stave = x // 4
            f = (1.06 if stave % 2 else 0.97) * (1 + rnd.uniform(-0.05, 0.05))
            img.putpixel((x, y), shade(b, f))
        for x in range(3, 16, 4):
            img.putpixel((x, y), hexc(seam))
    for hy in (2, 12):
        for x in range(16):
            img.putpixel((x, hy), hexc(hoop))
            img.putpixel((x, hy + 1), shade(hexc(hoop), 0.7))
        for x in range(1, 16, 5):
            img.putpixel((x, hy), shade(hexc(hoop), 1.45))
    return img


def plunges():
    side = staves(111, '#a77446', '#5e3d22')
    save(side, 'block/plunge_side')
    inner = new()
    noise(inner, hexc('#6f4b2c'), 0.06, 112)
    for y in range(16):
        for x in range(3, 16, 4):
            inner.putpixel((x, y), hexc('#4a311c'))
    save(inner, 'block/plunge_inner')
    rim = new()
    noise(rim, hexc('#c29563'), 0.06, 113)
    for i in range(16):
        rim.putpixel((i, 0), hexc('#7d5634'))
        rim.putpixel((i, 15), hexc('#7d5634'))
    save(rim, 'block/plunge_rim')
    save(planks(114, '#946a40', '#5c3c22'), 'block/plunge_bottom')

    cedar = staves(121, '#b5654a', '#6a3121', hoop='#3d3f43')
    save(cedar, 'block/sawdust_plunge_side')
    for name, base in (('hot_plunge', side), ('sawdust_plunge', cedar)):
        for lit in (False, True):
            f = base.copy()
            rect(f, 5, 7, 10, 14, hexc('#2b2d30'))
            frame(f, 5, 7, 10, 14, hexc('#55595f'))
            fire_glow(f, 6, 9, 9, 13, lit, 122)
            for x in (6, 8):
                for y in range(9, 14):
                    f.putpixel((x, y), hexc('#3d4046'))
            f.putpixel((10, 11), hexc('#a7adb5'))
            # flue pipe stub
            rect(f, 7, 4, 8, 6, hexc('#4b4f55'))
            save(f, 'block/' + name + '_front' + ('_on' if lit else ''))

    saw = new()
    rnd = random.Random(131)
    pal = [hexc('#e2c48f'), hexc('#d4b07a'), hexc('#c99f68'), hexc('#efd9a8'), hexc('#b98c55')]
    for y in range(16):
        for x in range(16):
            saw.putpixel((x, y), rnd.choice(pal))
    for _ in range(10):
        x, y = rnd.randrange(16), rnd.randrange(16)
        saw.putpixel((x, y), hexc('#7aa34a'))  # bits of herbs
    save(saw, 'block/sawdust')

    item = new()
    for y in range(6, 15):
        for x in range(1, 16):
            if math.hypot((x - 8) / 7.0, (y - 14) / 7.5) <= 1.0:
                item.putpixel((x, y), rnd.choice(pal[:4]))
    for x in range(2, 15):
        item.putpixel((x, 14), hexc('#a77c48'))
    save(item, 'item/sawdust')


def lounger():
    top = new()
    noise(top, hexc('#efeeea'), 0.025, 141)
    rnd = random.Random(142)
    x, y = 0.0, 3.0
    for _ in range(40):
        x += 0.45
        y += rnd.uniform(-0.6, 0.8)
        xi, yi = int(x) % 16, int(y) % 16
        top.putpixel((xi, yi), hexc('#b9b8b4'))
    for i in range(10):
        top.putpixel((12 - i // 2, 9 + i // 3), hexc('#cfcdc8'))
    save(top, 'block/warm_lounger_top')
    side = new()
    noise(side, hexc('#e6e4df'), 0.03, 143)
    for x in range(16):
        side.putpixel((x, 0), hexc('#ffffff'))
        side.putpixel((x, 11), hexc('#b87333'))
        side.putpixel((x, 12), hexc('#8e5a2a'))
        side.putpixel((x, 15), hexc('#a9a7a2'))
    save(side, 'block/warm_lounger_side')
    for lit in (False, True):
        f = side.copy()
        rect(f, 6, 5, 9, 7, hexc('#5c4a3a'))
        rect(f, 7, 6, 8, 6, hexc('#ff8a3d') if lit else hexc('#3a2a24'))
        save(f, 'block/warm_lounger_front' + ('_on' if lit else ''))


def mitt():
    img = new()
    rnd = random.Random(151)
    base = hexc('#c9b48e')
    for y in range(16):
        for x in range(16):
            palm = math.hypot((x - 8) / 5.0, (y - 7.5) / 6.5) <= 1.0
            thumb = math.hypot((x - 3.5) / 2.0, (y - 9) / 3.0) <= 1.0
            if (palm or thumb) and y <= 13:
                img.putpixel((x, y), shade(base, 1 + rnd.uniform(-0.15, 0.1)))
    for x in range(4, 13):
        img.putpixel((x, 13), hexc('#7d5f3a'))
        img.putpixel((x, 14), hexc('#9c7b50'))
    for (x, y) in ((6, 5), (9, 4), (7, 8), (10, 9), (8, 11)):
        img.putpixel((x, y), hexc('#a28d68'))
    save(img, 'item/scrub_mitt')


if __name__ == '__main__':
    inner_heater()
    outer_heater()
    brick_stove()
    iron_stove()
    electric_heater()
    steam_generator()
    gauge()
    chimney()
    smoke_vent()
    ladle()
    broom()
    effect_icons()
    plunges()
    lounger()
    mitt()
    print('textures written to', os.path.normpath(ROOT))
