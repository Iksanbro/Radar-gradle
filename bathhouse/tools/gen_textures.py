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


# ---------------------------------------------------------------- stove parts

def door(img, lit, seed, frame_c='#4b4f55', bar_c='#3d4046', box=(4, 7, 11, 14)):
    x0, y0, x1, y1 = box
    rect(img, x0 - 1, y0 - 1, x1 + 1, y1 + 1, hexc('#1f2124'))
    frame(img, x0 - 1, y0 - 1, x1 + 1, y1 + 1, hexc(frame_c))
    grate(img, x0, y0, x1, y1, hexc(bar_c), lit, seed)
    img.putpixel((x1 + 1, (y0 + y1) // 2), hexc('#a7adb5'))


def steel_side(seed):
    side = steel(seed, '#34383d')
    frame(side, 0, 0, 15, 15, hexc('#202326'))
    for y in (3, 12):
        for x in range(1, 15):
            side.putpixel((x, y), hexc('#2a2d31'))
    rivets(side, hexc('#4a4f55'), [(2, 2), (13, 2), (2, 13), (13, 13)])
    return side


def plate_top(seed, base='#2f2f31', edge='#202022'):
    top = steel(seed, base, vertical=False)
    frame(top, 0, 0, 15, 15, hexc(edge))
    for r, c in ((5, '#1d1d1f'), (3, '#47474b')):
        for a in range(0, 360, 10):
            x = int(round(8 + r * math.cos(math.radians(a)) - 0.5))
            y = int(round(8 + r * math.sin(math.radians(a)) - 0.5))
            top.putpixel((x, y), hexc(c))
    return top


def fireboxes():
    side = steel_side(201)
    save(side, 'block/steel_firebox_side')
    save(plate_top(202, '#3a3e43', '#202326'), 'block/steel_firebox_top')
    for lit in (False, True):
        f = side.copy()
        door(f, lit, 203)
        save(f, 'block/steel_firebox_front' + ('_on' if lit else ''))

    side = steel(211, '#232325', var=0.08)
    for y in range(2, 15, 3):
        for x in range(1, 15):
            side.putpixel((x, y), hexc('#353538'))
            side.putpixel((x, y + 1), hexc('#151516'))
    frame(side, 0, 0, 15, 15, hexc('#0f0f10'))
    save(side, 'block/cast_iron_firebox_side')
    save(plate_top(212, '#29292b', '#0f0f10'), 'block/cast_iron_firebox_top')
    for lit in (False, True):
        f = steel(213, '#232325', var=0.08)
        frame(f, 0, 0, 15, 15, hexc('#0f0f10'))
        rect(f, 3, 5, 12, 14, hexc('#18181a'))
        for x in range(4, 12):
            f.putpixel((x, 4), hexc('#18181a'))
        fire_glow(f, 5, 7, 10, 12, lit, 214)
        for y in range(7, 13, 2):
            for x in range(5, 11):
                f.putpixel((x, y), hexc('#2a2a2d'))
        f.putpixel((11, 10), hexc('#b9873a'))
        save(f, 'block/cast_iron_firebox_front' + ('_on' if lit else ''))

    side = bricks(221, mortar='#e6e0d4', reds=('#f2eee6', '#e9e4da', '#ddd6ca'))
    for x in range(16):
        side.putpixel((x, 15), hexc('#8c8273'))
    save(side, 'block/brick_firebox_side')
    save(bricks(222, mortar='#cfc6b8', reds=('#e9e4da', '#ddd6ca')), 'block/brick_firebox_top')
    for lit in (False, True):
        f = side.copy()
        for x in range(3, 13):
            f.putpixel((x, 6), hexc('#9d9282'))
        door(f, lit, 223, box=(4, 8, 11, 13))
        save(f, 'block/brick_firebox_front' + ('_on' if lit else ''))

    side = steel(231, '#9aa3ab', var=0.04)
    for y in range(4, 13, 2):
        for x in range(3, 13):
            side.putpixel((x, y), hexc('#4c5359'))
    frame(side, 0, 0, 15, 15, hexc('#6c747b'))
    save(side, 'block/heating_element_side')
    top = steel(232, '#8d969e', vertical=False)
    frame(top, 0, 0, 15, 15, hexc('#6c747b'))
    for y in (4, 8, 12):
        for x in range(3, 13):
            top.putpixel((x, y), hexc('#5c3a2e'))
    save(top, 'block/heating_element_top')
    for lit in (False, True):
        f = steel(233, '#a3acb4', var=0.04)
        frame(f, 0, 0, 15, 15, hexc('#6c747b'))
        rect(f, 3, 3, 12, 8, hexc('#2c3136'))
        frame(f, 3, 3, 12, 8, hexc('#5d656c'))
        rect(f, 4, 4, 9, 7, hexc('#ff6a3d') if lit else hexc('#3a2a24'))
        f.putpixel((10, 11), hexc('#d0d5da'))
        f.putpixel((11, 12), hexc('#30353a'))
        f.putpixel((5, 11), hexc('#e43b2c') if lit else hexc('#5a3a37'))
        save(f, 'block/heating_element_front' + ('_on' if lit else ''))


def ash_pit():
    side = steel_side(241)
    save(side, 'block/ash_pit_side')
    top = steel(242, '#2a2c2f', vertical=False)
    for x in range(1, 15, 2):
        for y in range(1, 15):
            top.putpixel((x, y), hexc('#141516'))
    frame(top, 0, 0, 15, 15, hexc('#202326'))
    save(top, 'block/ash_pit_top')
    for full in (False, True):
        f = side.copy()
        rect(f, 3, 9, 12, 13, hexc('#1b1c1e'))
        frame(f, 3, 9, 12, 13, hexc('#55595f'))
        if full:
            rnd = random.Random(243)
            for y in range(10, 13):
                for x in range(4, 12):
                    f.putpixel((x, y), shade(hexc('#9a958e'), 1 + rnd.uniform(-0.15, 0.15)))
        rect(f, 6, 7, 9, 7, hexc('#a7adb5'))
        save(f, 'block/ash_pit_front' + ('_full' if full else ''))


def heaters():
    side = steel_side(251)
    for x in range(1, 15):
        side.putpixel((x, 7), hexc('#43474d'))
    save(side, 'block/closed_heater_side')
    top = plate_top(252, '#33373c', '#202326')
    rect(top, 6, 6, 9, 9, hexc('#151617'))
    save(top, 'block/closed_heater_top')
    for filled in (False, True):
        f = side.copy()
        rect(f, 3, 3, 12, 11, hexc('#1f2124'))
        frame(f, 3, 3, 12, 11, hexc('#55595f'))
        if filled:
            st = stones(253, '#2a2a2c')
            for y in range(4, 11):
                for x in range(4, 12):
                    f.putpixel((x, y), st.getpixel((x, y)))
        else:
            rect(f, 4, 4, 11, 10, hexc('#101112'))
        save(f, 'block/closed_heater_front' + ('_filled' if filled else ''))
    for filled in (False, True):
        if filled:
            base = stones(261, '#3a3633')
        else:
            base = new()
            noise(base, hexc('#1a1a1b'), 0.1, 262)
        side = base.copy()
        for x in range(0, 16, 3):
            for y in range(16):
                side.putpixel((x, y), hexc('#5a5f66'))
        for y in (0, 5, 10, 15):
            for x in range(16):
                side.putpixel((x, y), hexc('#6c727a'))
        save(side, 'block/open_heater_side' + ('' if filled else '_empty'))
        save(base, 'block/open_heater_top' + ('' if filled else '_empty'))


def boiler_and_tank():
    copper = new()
    noise(copper, hexc('#b8693d'), 0.06, 271)
    for y in (2, 13):
        for x in range(16):
            copper.putpixel((x, y), hexc('#7a3f22'))
    rivets(copper, hexc('#d48a5a'), [(2, 4), (13, 4), (2, 11), (13, 11)])
    save(copper, 'block/boiler_side')
    top = new()
    noise(top, hexc('#a35c35'), 0.05, 272)
    rect(top, 6, 6, 9, 9, hexc('#5a3a2a'))
    rect(top, 7, 7, 8, 8, hexc('#1f1512'))
    save(top, 'block/boiler_top')
    f = copper.copy()
    for y in range(3, 11):
        for x in range(3, 11):
            d = math.hypot(x - 6.5, y - 6.5)
            if d <= 3.2:
                f.putpixel((x, y), hexc('#3a2a22') if d > 2.4 else hexc('#f4efe2'))
    f.putpixel((7, 5), hexc('#c4302b'))
    f.putpixel((7, 6), hexc('#2b2b2b'))
    rect(f, 11, 9, 12, 12, hexc('#55595f'))
    f.putpixel((12, 9), hexc('#c4302b'))
    save(f, 'block/boiler_front')

    tank = steel(281, '#b9c1c7', var=0.03)
    for y in (1, 14):
        for x in range(16):
            tank.putpixel((x, y), hexc('#7d868c'))
    for x in (3, 12):
        for y in range(16):
            tank.putpixel((x, y), hexc('#a3abb1'))
    save(tank, 'block/water_tank_side')
    top = steel(282, '#aab2b8', vertical=False)
    for a in range(0, 360, 10):
        x = int(round(8 + 5 * math.cos(math.radians(a)) - 0.5))
        y = int(round(8 + 5 * math.sin(math.radians(a)) - 0.5))
        top.putpixel((x, y), hexc('#7d868c'))
    rect(top, 6, 6, 9, 9, hexc('#4b5359'))
    save(top, 'block/water_tank_top')
    f = tank.copy()
    rect(f, 6, 9, 9, 10, hexc('#4b5359'))
    rect(f, 7, 11, 8, 12, hexc('#4b5359'))
    f.putpixel((7, 8), hexc('#c4302b'))
    f.putpixel((8, 8), hexc('#c4302b'))
    save(f, 'block/water_tank_front')


def damper_and_tunnel():
    for open_ in (False, True):
        img = bricks(291, mortar='#b5a998', reds=('#8f3e2c', '#7f3626', '#9a4834'), h=4, w=6)
        rect(img, 0, 6, 15, 9, hexc('#3d4046'))
        frame(img, 0, 6, 15, 9, hexc('#55595f'))
        if open_:
            rect(img, 10, 5, 15, 10, hexc('#2b2d30'))
            rect(img, 12, 7, 15, 8, hexc('#a7adb5'))
        else:
            rect(img, 2, 7, 13, 8, hexc('#a7adb5'))
        save(img, 'block/damper' + ('_open' if open_ else ''))
    side = steel(301, '#2f3337')
    for y in range(16):
        side.putpixel((0, y), hexc('#1b1d20'))
        side.putpixel((15, y), hexc('#1b1d20'))
    for x in range(16):
        side.putpixel((x, 0), hexc('#4a4f55'))
        side.putpixel((x, 15), hexc('#4a4f55'))
    save(side, 'block/fuel_tunnel_side')
    f = side.copy()
    door(f, False, 302, box=(4, 5, 11, 12))
    save(f, 'block/fuel_tunnel_front')
    clad = new()
    rnd = random.Random(311)
    for y in range(16):
        for x in range(16):
            clad.putpixel((x, y), shade(hexc('#7d8f80'), 1 + rnd.uniform(-0.08, 0.08)))
    for y in (7, 15):
        for x in range(16):
            clad.putpixel((x, y), hexc('#5b6a5e'))
    for x in (7, 15):
        for y in range(0, 7):
            clad.putpixel((x, y), hexc('#5b6a5e'))
    for x in (3, 11):
        for y in range(8, 15):
            clad.putpixel((x, y), hexc('#5b6a5e'))
    for _ in range(14):
        clad.putpixel((rnd.randrange(16), rnd.randrange(16)), hexc('#a5b6a6'))
    save(clad, 'block/stove_cladding')


# ---------------------------------------------------------------- plunge parts

def plunge_parts():
    side = staves(321, '#a77446', '#5e3d22')
    save(side, 'block/plunge_wall_wood')
    rim = new()
    noise(rim, hexc('#c29563'), 0.06, 322)
    frame(rim, 0, 0, 15, 15, hexc('#7d5634'))
    save(rim, 'block/plunge_wall_wood_top')

    comp = new()
    rnd = random.Random(331)
    for y in range(16):
        for x in range(16):
            comp.putpixel((x, y), shade(hexc('#9b6b43'), (1.06 if (y // 3) % 2 else 0.95) * (1 + rnd.uniform(-0.04, 0.04))))
        if y % 3 == 2:
            for x in range(16):
                comp.putpixel((x, y), hexc('#5c3b22'))
    for x in range(16):
        comp.putpixel((x, 0), hexc('#2b2e31'))
        comp.putpixel((x, 15), hexc('#2b2e31'))
    save(comp, 'block/plunge_wall_composite')
    ctop = new()
    noise(ctop, hexc('#3a3e42'), 0.05, 332)
    frame(ctop, 0, 0, 15, 15, hexc('#9b6b43'))
    save(ctop, 'block/plunge_wall_composite_top')

    tile = new()
    rnd = random.Random(341)
    pal = [hexc('#e9f1f5'), hexc('#cfe3ec'), hexc('#7fb6d1'), hexc('#4d8fb3'), hexc('#f5f7f8')]
    for ty in range(4):
        for tx in range(4):
            c = rnd.choice(pal)
            for y in range(ty * 4, ty * 4 + 3):
                for x in range(tx * 4, tx * 4 + 3):
                    tile.putpixel((x, y), shade(c, 1 + rnd.uniform(-0.03, 0.03)))
    for i in range(16):
        for j in (3, 7, 11, 15):
            tile.putpixel((i, j), hexc('#9fa8ad'))
            tile.putpixel((j, i), hexc('#9fa8ad'))
    save(tile, 'block/plunge_wall_tile')

    save(planks(351, '#b98a58', '#7a5232'), 'block/plunge_bench')

    st = steel(361, '#c3cad0', var=0.03)
    frame(st, 0, 0, 15, 15, hexc('#8a9399'))
    save(st, 'block/plunge_stove_side')
    top = steel(362, '#b3bac0', vertical=False)
    frame(top, 0, 0, 15, 15, hexc('#8a9399'))
    rect(top, 5, 5, 10, 10, hexc('#5b6369'))
    rect(top, 6, 6, 9, 9, hexc('#1b1d20'))
    save(top, 'block/plunge_stove_top')
    for lit in (False, True):
        f = st.copy()
        door(f, lit, 363, frame_c='#6c747b', bar_c='#5b6369', box=(5, 7, 10, 13))
        save(f, 'block/plunge_stove_front' + ('_on' if lit else ''))

    ch = steel(371, '#aec7d6', var=0.03)
    rnd = random.Random(372)
    for _ in range(30):
        ch.putpixel((rnd.randrange(16), rnd.randrange(16)), hexc('#eef8ff'))
    frame(ch, 0, 0, 15, 15, hexc('#6b8899'))
    save(ch, 'block/plunge_chiller_side')
    top = ch.copy()
    for y in range(3, 13, 3):
        for x in range(3, 13):
            top.putpixel((x, y), hexc('#4f6f82'))
    save(top, 'block/plunge_chiller_top')
    for powered in (False, True):
        f = ch.copy()
        for y in range(4, 12, 2):
            for x in range(3, 13):
                f.putpixel((x, y), hexc('#4f6f82'))
        for x in (11, 12):
            f.putpixel((x, 13), hexc('#45c7ff') if powered else hexc('#3d4a52'))
        save(f, 'block/plunge_chiller_front' + ('_on' if powered else ''))

    for active in (False, True):
        f = side.copy()
        rect(f, 6, 2, 9, 13, hexc('#e9e3d2'))
        frame(f, 6, 2, 9, 13, hexc('#7d5634'))
        for y in range(4, 12, 2):
            f.putpixel((9, y), hexc('#7d5634'))
        for y in range(6 if active else 9, 13):
            f.putpixel((7, y), hexc('#c4302b'))
            f.putpixel((8, y), hexc('#c4302b'))
        f.putpixel((7, 12), hexc('#8e1f1b'))
        f.putpixel((8, 12), hexc('#8e1f1b'))
        save(f, 'block/plunge_thermometer_front' + ('_active' if active else ''))

    saw = new()
    rnd = random.Random(381)
    pal = [hexc('#e2c48f'), hexc('#d4b07a'), hexc('#c99f68'), hexc('#efd9a8'), hexc('#b98c55')]
    for y in range(16):
        for x in range(16):
            saw.putpixel((x, y), rnd.choice(pal))
    for _ in range(10):
        saw.putpixel((rnd.randrange(16), rnd.randrange(16)), hexc('#7aa34a'))
    save(saw, 'block/sawdust')


def hot_ladle():
    img = Image.open(os.path.join(ROOT, 'item/ladle_filled.png')).convert('RGBA')
    for y in range(16):
        for x in range(16):
            p = img.getpixel((x, y))
            if p[3] and p[2] > p[0] + 60:
                img.putpixel((x, y), hexc('#e0663a') if (x + y) % 3 else hexc('#f2a074'))
    for (x, y) in ((3, 7), (5, 6), (4, 5)):
        img.putpixel((x, y), hexc('#e8e8e8'))
    save(img, 'item/ladle_hot')


# ---------------------------------------------------------------- effect icons (18x18 ASCII art; '#' body, 'o' highlight)

ICONS = {
    'purity': ('#4fb3d9', '#2c7fa8', '#d9f3ff', [
        "........#.........", ".......###........", ".......###........", "......#####.......",
        "......#####.......", ".....#######......", "....#########.....", "....##o######.....",
        "...##oo#######....", "...##o########....", "...###########....", "...###########....",
        "...###########....", "....#########.....", ".....#######......", "......#####.......",
        "..................", ".................."]),
    'clarity': ('#7fc3d9', '#2f6f86', '#ffffff', [
        "..................", "..................", "..................", ".......#####......",
        ".....#########....", "....###########...", "...####ooo#####...", "..####ooo######..",
        "..####oo#######..", "..#############..", "...###########....", "....#########.....",
        ".....#######......", "..................", "..................", "..................",
        "..................", ".................."]),
    'cleansing': ('#5fb9a5', '#2f7a68', '#d8fff4', [
        "..................", "...........####...", ".........#######..", "........########..",
        ".......#####o###..", "......#####o####..", ".....#####o#####..", "....#####o######..",
        "...#####o######...", "...####o######....", "...###o######.....", "...##o######......",
        "...#########......", "..##.######.......", ".##...............", "##................",
        "..................", ".................."]),
    'fortitude': ('#c9452f', '#7d2216', '#f3a08d', [
        "..................", "..................", "...####....####...", "..######..######..",
        ".##oo####.#######.", ".#oo#############.", ".#o##############.", ".################.",
        ".################.", "..##############..", "...############...", "....##########....",
        ".....########.....", "......######......", ".......####.......", "........##........",
        "..................", ".................."]),
    'prowess': ('#c9cdd2', '#5a5f66', '#ffffff', [
        "...............##.", "..............#o#.", ".............#o#..", "............#o#...",
        "...........#o#....", "..........#o#.....", ".........#o#......", "........#o#.......",
        ".......#o#........", "..##..#o#.........", "...####o#.........", "....###...........",
        "...#####..........", "..##.###..........", ".##...............", "##................",
        "..................", ".................."]),
    'steadfastness': ('#8c6b4a', '#4a3520', '#d8b48a', [
        "..................", "..##############..", "..#oo###########..", "..#o############..",
        "..#o####oo######..", "..#####o##o#####..", "..#####o##o#####..", "..######oo######..",
        "..##############..", "...############...", "...############...", "....##########....",
        ".....########.....", "......######......", ".......####.......", "........##........",
        "..................", ".................."]),
    'lightness': ('#f08a24', '#d9481e', '#ffe27a', [
        "........#.........", "........##........", ".......###........", ".......####.......",
        "......#####.#.....", "......######......", ".....#######......", "....####o####.....",
        "....###ooo###.....", "...###ooooo###....", "...##ooooooo##....", "...##oooooooo#....",
        "...##ooooooo##....", "....##ooooo##.....", ".....#######......", "......#####.......",
        "..................", ".................."]),
    'quick_hands': ('#f0c060', '#9a6a1e', '#fff1c4', [
        "..................", ".....#..#..#......", ".....#..#..#..#...", ".....#..#..#..#...",
        ".....#..#..#..#...", ".....##o##o##o#...", ".....##########...", "..#..##########...",
        "..##.##########...", "...############...", "....##########....", ".....########.....",
        ".....#######......", ".....#######......", "..................", "..oo..oo..oo......",
        "..................", ".................."]),
    'knack': ('#9aa3ab', '#4a3520', '#d0d5da', [
        "..................", "....##########....", "...#oooooooooo#...", "..##...####...##..",
        "..#.....##.....#..", ".........#........", "........##........", "........##........",
        ".......##.........", ".......##.........", "......##..........", "......##..........",
        ".....##...........", ".....##...........", "....##............", "....#.............",
        "..................", ".................."]),
    'tempering': ('#6fb7e8', '#2f6f9e', '#d6efff', [
        "..................", "..####......####..", "..#####....#####..", "..######..######..",
        "...############...", "...##o#########...", "...##o#########...", "...#o##########...",
        "...############...", "...############...", "...############...", "...############...",
        "...############...", "....##########....", "..................", "..................",
        "..................", ".................."]),
    'hardiness': ('#4c8fc0', '#1f4f73', '#c4e4ff', [
        "..................", "........##........", ".......####.......", "......##oo##......",
        ".....##oooo##.....", "....##oo##oo##....", "...##oo####oo##...", "..##oo######oo##..",
        "...##oo####oo##...", "....##oo##oo##....", ".....##oooo##.....", "......##oo##......",
        ".......####.......", "........##........", "..................", "..................",
        "..................", ".................."]),
    'frostproof': ('#bfe6ff', '#3f8fd6', '#ffffff', [
        "........#.........", ".....#..#..#......", "......#.#.#.......", ".......###........",
        "..#.....#.....#...", "...#....#....#....", "....#...#...#.....", ".....#.###.#......",
        "#########o########", ".....#.###.#......", "....#...#...#.....", "...#....#....#....",
        "..#.....#.....#...", ".......###........", "......#.#.#.......", ".....#..#..#......",
        "........#.........", ".................."]),
    'bliss': ('#d47aa6', '#a3507a', '#f7e3ee', [
        "....o...o...o.....", "...o...o...o......", "....o...o...o.....", ".....o...o...o....",
        "....o...o...o.....", "...o...o...o......", "..................", "..................",
        "..##############..", ".#oooooooooooooo#.", "#oo############oo#", "##################",
        ".################.", "..##############..", "...############...", "..................",
        "..................", ".................."]),
    'suppleness': ('#e3a6c6', '#a3507a', '#ffffff', [
        "...............#..", "..............##..", ".............#o#..", "............#o##..",
        "...........#o###..", "..........#o####..", ".........#o#####..", "........#o#####...",
        ".......#o#####....", "......#o#####.....", ".....#o#####......", "....#o####........",
        "...#o###..........", "..#o#.............", ".#................", "#.................",
        "..................", ".................."]),
    'featherlight': ('#f4d3e3', '#b07a96', '#ffffff', [
        "..................", "..................", "......####........", "....##oooo##......",
        "...#oooooooo#.##..", "..#oooooooooo#oo#.", ".#oooooooooooooo#.", ".#oooooooooooooo#.",
        "..##############..", "..................", "......#....#......", ".....#....#.......",
        "......#....#......", ".....#....#.......", "..................", "..................",
        "..................", ".................."]),
}


def effect_icons():
    for name, (body, edge, light, rows) in ICONS.items():
        img = new(18)
        for y, row in enumerate(rows):
            for x, ch in enumerate(row[:18]):
                if ch == '#':
                    img.putpixel((x, y), hexc(body))
                elif ch == 'o':
                    img.putpixel((x, y), hexc(light))
        out = img.copy()
        for y in range(18):
            for x in range(18):
                p = img.getpixel((x, y))
                if p[3] == 0 or p[:3] != hexc(body)[:3]:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if not (0 <= nx < 18 and 0 <= ny < 18) or img.getpixel((nx, ny))[3] == 0:
                        out.putpixel((x, y), hexc(edge))
                        break
        save(out, 'mob_effect/' + name)


if __name__ == '__main__':
    import shutil
    shutil.rmtree(ROOT, ignore_errors=True)
    steam_generator()
    gauge()
    chimney()
    smoke_vent()
    ladle()
    hot_ladle()
    broom()
    mitt()
    lounger()
    fireboxes()
    ash_pit()
    heaters()
    boiler_and_tank()
    damper_and_tunnel()
    plunge_parts()
    effect_icons()
    print('textures written to', os.path.normpath(ROOT))
