#!/usr/bin/env python3
"""Текстуры блоков 32x32 в стиле Create -> src/main/resources/assets/radar_fcs/textures/block.

Попиксельно, но с плавными переходами: цвета берутся из «лестниц» (ramp) с оттенками, сдвинутыми к холодному в тенях
и к тёплому в бликах, градиенты по высоте и по радиусу, лёгкий шум против плоских заливок. Рамки - как у обшивок
Create (латунь, свет сверху-слева), экраны и лампы со свечением. Разметка UV моделей прежняя (координаты 0..16 -
пиксели x2). radar_monitor_front не трогается (под рендер карты).

Камера - «око Эндера в корпусе»: корпус тёмный (обсидиан) с латунью, наружу видно только зрачок ока в оправе.

Запуск: python3 devtools/textures.py
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'radar_fcs',
                   'textures', 'block')
N = 32


def hx(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(stops, t):
    """Цвет на лестнице stops (тёмный -> светлый), t в 0..1."""
    t = max(0.0, min(1.0, t)) * (len(stops) - 1)
    i = min(int(t), len(stops) - 2)
    return mix(stops[i], stops[i + 1], t - i)


def noise(x, y, seed=0):
    h = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xffffffff
    h = (h ^ (h >> 13)) * 1274126177 & 0xffffffff
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0


def shade(c, k):
    """Светлее (k > 0) или темнее (k < 0), в долях."""
    return mix(c, (255, 255, 255), k) if k > 0 else mix(c, (0, 0, 0), -k)


# лестницы: тени уходят в холодное/красное, блики - в тёплое
BRASS = [hx('3b1a14'), hx('6a3420'), hx('9a5a2c'), hx('c98a3c'), hx('e9b552'), hx('f9dc7c'), hx('fff4c0')]
WOOD = [hx('1c0f0b'), hx('2e1a10'), hx('432816'), hx('5a3a1f')]
STEEL = [hx('0e1018'), hx('1a1d29'), hx('282c3b'), hx('3a3f52'), hx('535a70'), hx('77809a'), hx('aab3c8')]
SCREEN = [hx('020b08'), hx('061a12'), hx('0b2b1d'), hx('12432c')]
PHOS = [hx('1a6b3c'), hx('2fb565'), hx('5cf09a'), hx('c8ffe0')]
PCB = [hx('0b2618'), hx('123d26'), hx('1b5634'), hx('2a7446')]
OBS = [hx('07050c'), hx('120c1c'), hx('1d142d'), hx('2b1f42'), hx('3e2d5e'), hx('5a4486')]
EYE = [hx('03120d'), hx('0a3327'), hx('13604a'), hx('1f9470'), hx('47c99c'), hx('b0ffe4')]
GLOW_END = [hx('3a1460'), hx('7a34b8'), hx('b86bff'), hx('f0d0ff')]
BLUE = [hx('0c2448'), hx('1c4f8e'), hx('2f86d8'), hx('6cc0ff'), hx('d6f0ff')]
ORANGE = [hx('4a1806'), hx('93380e'), hx('dc6e1c'), hx('ffa94a'), hx('ffe2b0')]
RED = [hx('3a0a0a'), hx('8a1c16'), hx('e0402c'), hx('ff9a80')]
YELLOW = [hx('4a3206'), hx('a07410'), hx('f2c230'), hx('fff0a0')]
GREEN = [hx('0e3a14'), hx('2a8a30'), hx('6be36b'), hx('d8ffd0')]


class Tex:
    def __init__(self):
        self.im = Image.new('RGBA', (N, N), (0, 0, 0, 0))

    def px(self, x, y, c):
        if 0 <= x < N and 0 <= y < N:
            self.im.putpixel((x, y), tuple(c[:3]) + (255,))

    def get(self, x, y):
        return self.im.getpixel((x, y))[:3]

    def blend(self, x, y, c, a):
        if 0 <= x < N and 0 <= y < N and self.im.getpixel((x, y))[3]:
            self.px(x, y, mix(self.get(x, y), c, a))

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def grad(self, x0, y0, x1, y1, stops, t0, t1, jitter=0.03, seed=1, diag=0.0):
        """Заливка с градиентом сверху вниз по лестнице (t0 -> t1), diag - доля по горизонтали, шум jitter."""
        h = max(1, y1 - y0)
        w = max(1, x1 - x0)
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                t = t0 + (t1 - t0) * ((y - y0) / h * (1 - diag) + (x - x0) / w * diag)
                t += (noise(x, y, seed) - 0.5) * 2 * jitter
                self.px(x, y, ramp(stops, t))

    def glow(self, cx, cy, r, c, a=0.6):
        """Мягкое свечение вокруг точки (поверх того, что уже нарисовано)."""
        for y in range(int(cy - r) - 1, int(cy + r) + 2):
            for x in range(int(cx - r) - 1, int(cx + r) + 2):
                d = math.hypot(x - cx, y - cy)
                if d < r:
                    self.blend(x, y, c, a * (1 - d / r) ** 1.5)

    def save(self, name):
        self.im.save(os.path.join(OUT, name + '.png'))


def bevel_frame(t, stops, width=4, seed=3):
    """Рамка обшивки: внешний тёмный край, фаска (свет сверху-слева, тень снизу-справа), внутренний тёмный шов."""
    for y in range(N):
        for x in range(N):
            d = min(x, y, N - 1 - x, N - 1 - y)
            if d >= width:
                continue
            light = (N - 1 - x + N - 1 - y) / (2 * (N - 1))      # 1 - верх-лево, 0 - низ-право
            top_left = (y == d and x < N - 1 - d) or (x == d and y < N - 1 - d)
            if d == 0:
                k = 0.18 + 0.12 * light
            elif d == width - 1:
                k = 0.22
            else:
                k = (0.8 if top_left else 0.42) + 0.18 * (light - 0.5) - 0.07 * (d - 1)
            k += (noise(x, y, seed) - 0.5) * 0.05
            t.px(x, y, ramp(stops, k))


def rivet(t, x, y, stops):
    t.px(x, y, ramp(stops, 0.95))
    t.px(x + 1, y, ramp(stops, 0.7))
    t.px(x, y + 1, ramp(stops, 0.6))
    t.px(x + 1, y + 1, ramp(stops, 0.3))


def lamp(t, x, y, stops, on=True):
    """Лампа 2x2 со свечением."""
    if on:
        t.glow(x + 1, y + 1, 3.2, stops[-2], 0.45)
        t.px(x, y, stops[-1])
        t.px(x + 1, y, stops[-2])
        t.px(x, y + 1, stops[-2])
        t.px(x + 1, y + 1, stops[-3])
    else:
        t.px(x, y, stops[1])
        t.px(x + 1, y, stops[0])
        t.px(x, y + 1, stops[0])
        t.px(x + 1, y + 1, stops[0])


def console(t, lamps):
    """Лицевая панель прибора: латунная рамка, тёмный экран (6..25, 5..21) в утопленной оправе, панель с лампами."""
    bevel_frame(t, BRASS)
    t.grad(4, 4, 27, 27, WOOD, 0.9, 0.2, jitter=0.06, seed=5)
    # оправа экрана: тень сверху-слева (утоплен), блик снизу-справа
    for x in range(5, 27):
        t.px(x, 4, ramp(STEEL, 0.05))
        t.px(x, 22, ramp(STEEL, 0.45))
    for y in range(4, 23):
        t.px(5, y, ramp(STEEL, 0.08))
        t.px(26, y, ramp(STEEL, 0.4))
    t.rect(6, 5, 25, 21, SCREEN[0])
    # кнопки и лампы на панели
    for i, (stops, on) in enumerate(lamps):
        lamp(t, 7 + 4 * i, 24, stops, on)
    for x0 in (19, 23):
        t.grad(x0, 24, x0 + 2, 25, STEEL, 0.75, 0.45, jitter=0.0)
        t.px(x0, 24, ramp(STEEL, 0.95))


def screen_bg(t, x0, y0, x1, y1, stops, cx, cy, r):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d = math.hypot(x - cx, y - cy) / r
            v = 1 - min(1.0, d)
            v += (noise(x, y, 9) - 0.5) * 0.06
            t.px(x, y, ramp(stops, 0.15 + 0.85 * v))
    # блик на стекле
    for i in range(6):
        t.blend(x0 + 1 + i, y0 + 1, (255, 255, 255), 0.10 - i * 0.015)


def radar(t, engaged):
    x0, y0, x1, y1 = 6, 5, 25, 21
    cx, cy = 15.5, 13.0
    screen_bg(t, x0, y0, x1, y1, SCREEN, cx, cy, 11)
    beam = RED if engaged else PHOS
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)
            a = math.degrees(math.atan2(dx, -dy)) % 360
            if abs(d - 7.5) < 0.5 or abs(d - 4.0) < 0.45:
                t.blend(x, y, PHOS[0], 0.75)
            if (abs(dx) < 0.6 or abs(dy) < 0.6) and d < 7.6:
                t.blend(x, y, PHOS[0], 0.45)
            # луч и шлейф: ярко на переднем крае, затухает назад по углу
            if d < 8.0:
                back = (48.0 - a) % 360
                if back < 70:
                    k = 1 - back / 70
                    t.blend(x, y, ramp(beam, 0.4 + 0.6 * k), 0.25 + 0.7 * k ** 1.6)
    for bx, by in ([(20, 9), (11, 17)] if not engaged else [(11, 17)]):
        t.glow(bx + 0.5, by + 0.5, 2.6, PHOS[2], 0.6)
        t.px(bx, by, PHOS[3])
    if engaged:
        t.glow(20.5, 9.5, 3.2, RED[2], 0.7)
        t.px(20, 9, RED[3])
        for dx, dy in ((-2, -2), (2, -2), (-2, 2), (2, 2)):
            t.px(20 + dx, 9 + dy, RED[2])


def fcs_front(engaged):
    t = Tex()
    console(t, [(GREEN, True), (YELLOW, True), (RED, engaged)])
    radar(t, engaged)
    return t


def flight_bridge_front():
    t = Tex()
    console(t, [(GREEN, True), (BLUE, True), (YELLOW, False)])
    sky = [hx('0f2c63'), hx('1f56a6'), hx('3f8ad8'), hx('9fd2ff')]
    ground = [hx('7a4f22'), hx('5a3a1a'), hx('3a230f'), hx('22140a')]
    horizon = 14
    for y in range(5, 22):
        for x in range(6, 26):
            if y < horizon:
                c = ramp(sky, (y - 5) / (horizon - 5))
            else:
                c = ramp(ground, (y - horizon) / (21 - horizon))
            t.px(x, y, shade(c, (noise(x, y, 4) - 0.5) * 0.06))
    for x in range(6, 26):
        t.px(x, horizon, hx('f2f6ff'))
    # шкала тангажа
    for y, w in ((9, 3), (11, 2), (17, 2), (19, 3)):
        for x in range(16 - w, 16 + w):
            t.blend(x, y, (255, 255, 255), 0.55)
    # силуэт самолёта: жёлтый с тёмной обводкой
    wing = [(x, 15) for x in list(range(9, 14)) + list(range(18, 23))] + [(14, 16), (15, 17), (16, 17), (17, 16)]
    for x, y in wing:
        t.px(x, y + 1, hx('2a1a06'))
    for x, y in wing:
        t.px(x, y, YELLOW[2])
    t.px(15, 15, YELLOW[3])
    t.px(16, 15, YELLOW[3])
    # лента курса сверху
    t.grad(6, 5, 25, 6, STEEL, 0.15, 0.25, jitter=0.0)
    for x in range(7, 25, 3):
        t.px(x, 6, hx('c9e4ff'))
    t.px(15, 5, ORANGE[3])
    t.px(16, 5, ORANGE[3])
    return t


def memory_front(on):
    t = Tex()
    bevel_frame(t, BRASS)
    t.grad(4, 4, 27, 27, PCB, 0.95, 0.35, jitter=0.05, seed=8, diag=0.3)
    gold = [hx('5a3a10'), hx('a07428'), hx('e0b450'), hx('fff0a0')]
    for y in (19, 26):
        for x in range(5, 27):
            if noise(x, y, 2) > 0.3:
                t.px(x, y, ramp(gold, 0.35))
    for x in (8, 15, 23):
        for y in range(16, 20):
            t.px(x, y, ramp(gold, 0.35))
    for x0 in (6, 17):
        for y in (7, 10, 13):
            for x in (x0, x0 + 8):
                t.px(x, y, ramp(gold, 0.8))
                t.px(x, y + 1, ramp(gold, 0.45))
        t.grad(x0 + 1, 6, x0 + 7, 15, STEEL, 0.32, 0.08, jitter=0.02, seed=x0)
        t.px(x0 + 2, 7, ramp(STEEL, 0.55))
        for x in range(x0 + 1, x0 + 8):
            t.px(x, 6, ramp(STEEL, 0.45))
    for i, c in enumerate([GREEN, GREEN, YELLOW, GREEN, BLUE, GREEN]):
        lamp(t, 6 + 4 * i, 22, c, on)
    return t


def camera_monitor_front():
    t = Tex()
    console(t, [(GREEN, True), (YELLOW, True), (RED, False)])
    sky = [hx('2f6fb8'), hx('7fb8ef'), hx('cfe6ff')]
    grass = [hx('4f9a34'), hx('2c6a20'), hx('1d4a16')]
    for i, (x0, y0) in enumerate(((7, 6), (17, 6), (7, 14), (17, 14))):
        for y in range(y0, y0 + 7):
            for x in range(x0, x0 + 8):
                hill = 3.5 + 1.5 * math.sin((x + i * 3) * 0.7)
                if y - y0 < hill:
                    c = ramp(sky, (y - y0) / hill)
                else:
                    c = ramp(grass, (y - y0 - hill) / (7 - hill))
                t.px(x, y, shade(c, (noise(x, y, i) - 0.5) * 0.08))
        for x in range(x0, x0 + 8):
            t.blend(x, y0 + 6, (0, 0, 0), 0.35)
    t.glow(8.5, 7.5, 2.4, RED[2], 0.7)
    t.px(8, 7, RED[3])
    return t


def dark_casing():
    """Тёмная обшивка (боковины мониторов): стальная рамка с фаской, панель с жалюзи, болты."""
    t = Tex()
    bevel_frame(t, STEEL)
    t.grad(4, 4, 27, 27, STEEL, 0.42, 0.26, jitter=0.03, seed=6)
    for y0 in (8, 13, 18, 23):
        for x in range(8, 24):
            t.px(x, y0, ramp(STEEL, 0.02))
            t.px(x, y0 + 1, ramp(STEEL, 0.12))
            t.px(x, y0 + 2, ramp(STEEL, 0.58))
    for x, y in ((5, 5), (25, 5), (5, 25), (25, 25)):
        rivet(t, x, y, STEEL)
    return t


def arrow_plate(vertical, frame, arrow):
    """Накладка 20x20 (6..25) с двойной стрелкой: курс - ↔, наклон - ↕; остальное прозрачно."""
    t = Tex()
    for y in range(6, 26):
        for x in range(6, 26):
            d = min(x - 6, y - 6, 25 - x, 25 - y)
            if d == 0:
                t.px(x, y, ramp(frame, 0.15))
            elif d == 1:
                tl = (y == 7 and x < 24) or (x == 7 and y < 24)
                t.px(x, y, ramp(frame, (0.88 if tl else 0.42) + (noise(x, y, 7) - 0.5) * 0.05))
            else:
                t.px(x, y, ramp(STEEL, 0.34 - 0.18 * (y - 8) / 16 + (noise(x, y, 3) - 0.5) * 0.04))
    shape = set()
    for x in range(9, 23):
        for y in (14, 15, 16, 17):
            shape.add((x, y))
    for i in range(5):
        for y in range(15 - i - 1, 17 + i + 1):
            shape.add((9 + i, y))
            shape.add((22 - i, y))
    pts = {(y, x) if vertical else (x, y) for x, y in shape}
    for x, y in pts:                       # тень
        if (x + 1, y + 1) not in pts:
            t.px(x + 1, y + 1, ramp(STEEL, 0.02))
    for x, y in pts:
        k = 0.85 - 0.45 * (y - 8) / 16
        if (x, y - 1) not in pts or (x - 1, y) not in pts:
            k += 0.15
        t.px(x, y, ramp(arrow, k))
    return t


def hazard_band():
    """Полоса вращателя: строки 12..19, жёлто-чёрные полосы с фаской."""
    t = Tex()
    for x in range(N):
        t.px(x, 12, ramp(STEEL, 0.2))
        t.px(x, 13, ramp(STEEL, 0.55))
        t.px(x, 18, ramp(STEEL, 0.3))
        t.px(x, 19, ramp(STEEL, 0.08))
        for y in range(14, 18):
            stripe = (x + y) % 8 < 4
            t.px(x, y, ramp(YELLOW, 0.8 - 0.12 * (y - 14)) if stripe else ramp(STEEL, 0.12 + 0.05 * (17 - y)))
    return t


def iff_band():
    """Полоса транспондера (строки 10..21) - в оттенках серого, красится цветом транспондера."""
    t = Tex()
    grey = [(40, 40, 40), (90, 90, 90), (150, 150, 150), (205, 205, 205), (255, 255, 255)]
    for x in range(N):
        t.px(x, 10, ramp(grey, 0.15))
        t.px(x, 11, ramp(grey, 0.62))
        t.px(x, 21, ramp(grey, 0.1))
        for y in range(12, 21):
            t.px(x, y, ramp(grey, 0.55 - 0.03 * (y - 12) + (noise(x, y, 2) - 0.5) * 0.04))
    for x0 in (2, 10, 18, 26):
        for y in range(14, 19):
            for x in range(x0, x0 + 4):
                t.px(x, y, ramp(grey, 1.0 - 0.07 * (y - 14) - 0.04 * (x - x0)))
        for x in range(x0, x0 + 4):
            t.px(x, 13, ramp(grey, 0.25))
    return t


def cylinder(t, x0, x1, y0, y1, stops, lo=0.25, hi=0.9):
    """Вертикальный стержень x0..x1: свет слева, тень справа."""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            t.px(x, y, ramp(stops, hi - (hi - lo) * (x - x0) / max(1, x1 - x0)))


def iff_antenna():
    t = Tex()
    cylinder(t, 14, 17, 8, 23, STEEL, 0.35, 0.85)
    for y in (12, 17, 22):
        cylinder(t, 14, 17, y, y, STEEL, 0.15, 0.4)
    cylinder(t, 14, 17, 0, 7, RED, 0.35, 1.0)
    for y in range(0, 4):
        for x in range(12, 20):
            t.px(x, y, ramp(RED, 0.95 - 0.12 * y - 0.03 * (x - 12)))
    return t


def link_antenna():
    t = Tex()
    cylinder(t, 14, 17, 4, 7, STEEL, 0.35, 0.85)
    for y in range(0, 4):
        for x in range(14, 18):
            t.px(x, y, ramp(BLUE, 1.0 - 0.15 * y - 0.08 * (x - 14)))
    return t


def link_side(c):
    """Бок плоского передатчика (UV строки 13..16 -> 26..31): латунь, светящаяся полоса, тёмная латунь."""
    t = Tex()
    rows = [0.9, 0.7, None, None, 0.4, 0.22]
    for y in range(N):
        k = rows[(y - 26) % 6]
        for x in range(N):
            if k is None:
                v = (0.85 if (y - 26) % 6 == 2 else 0.6) + 0.12 * math.sin(x * 0.8)
                t.px(x, y, ramp(c, v))
            else:
                t.px(x, y, ramp(BRASS, k + (noise(x, y, 4) - 0.5) * 0.05))
    return t


def link_top(c):
    """Верх передатчика (UV 2..14 -> 4..27): латунная рамка, тёмная плата, дорожки к углам и светящийся узел."""
    t = Tex()
    for y in range(4, 28):
        for x in range(4, 28):
            d = min(x - 4, y - 4, 27 - x, 27 - y)
            if d == 0:
                t.px(x, y, ramp(BRASS, 0.2))
            elif d == 1:
                t.px(x, y, ramp(BRASS, 0.88 if (y == 5 and x < 26) or (x == 5 and y < 26) else 0.45))
            else:
                t.px(x, y, ramp(STEEL, 0.25 - 0.12 * (y - 6) / 20 + (noise(x, y, 5) - 0.5) * 0.04))
    for i in range(7, 13):
        for x, y in ((i, i), (31 - i, i), (i, 31 - i), (31 - i, 31 - i)):
            t.px(x, y, ramp(c, 0.45))
    for x, y in ((6, 6), (24, 6), (6, 24), (24, 24)):
        lamp(t, x, y, c, True)
    t.glow(15.5, 15.5, 6, c[3], 0.5)
    for y in range(13, 19):
        for x in range(13, 19):
            d = math.hypot(x - 15.5, y - 15.5)
            if d < 3.2:
                t.px(x, y, ramp(c, 1.0 - d / 4.5))
    return t


def sight_body():
    t = Tex()
    t.grad(0, 0, 31, 31, STEEL, 0.48, 0.2, jitter=0.03, seed=11)
    for x in range(N):
        t.px(x, 0, ramp(STEEL, 0.62))
        t.px(x, 31, ramp(STEEL, 0.08))
        t.px(x, 14, ramp(BRASS, 0.95))
        t.px(x, 15, ramp(BRASS, 0.72))
        t.px(x, 16, ramp(BRASS, 0.5))
        t.px(x, 17, ramp(BRASS, 0.2))
    for x, y in ((4, 4), (26, 4), (4, 26), (26, 26)):
        rivet(t, x, y, STEEL)
    return t


def sight_lens(firing):
    t = Tex()
    glass = ORANGE if firing else [hx('06262a'), hx('0f4f56'), hx('1e8a92'), hx('5fd2d8'), hx('d0fbff')]
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - 15.5, y - 15.5)
            if d < 11.5:
                light = 1 - math.hypot(x - 11, y - 10) / 18
                t.px(x, y, ramp(glass, 0.25 + 0.6 * light - 0.25 * (d / 11.5) ** 3))
            elif d < 14.2:
                t.px(x, y, ramp(BRASS, (0.85 if x + y < 31 else 0.35) - 0.15 * (d - 11.5) / 2.7))
            else:
                t.px(x, y, ramp(STEEL, 0.2 if d < 15.4 else 0.35))
    for i in list(range(6, 11)) + list(range(21, 26)):
        t.px(i, 15, ramp(STEEL, 0.05))
        t.px(15, i, ramp(STEEL, 0.05))
    t.glow(15.5, 15.5, 3, RED[3] if not firing else (255, 255, 230), 0.8)
    t.px(15, 15, RED[2] if not firing else (255, 255, 240))
    for x, y in ((10, 9), (11, 9), (10, 10)):
        t.blend(x, y, (255, 255, 255), 0.8)
    return t


# --- камера: корпус-обсидиан с латунью, око Эндера внутри (виден только зрачок) ---

def obsidian(t, x0=0, y0=0, x1=N - 1, y1=N - 1, top=0.62, bottom=0.3, seed=21):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            k = top + (bottom - top) * (y - y0) / max(1, y1 - y0)
            n = noise(x, y, seed)
            c = ramp(OBS, k + (n - 0.5) * 0.1)
            if n > 0.992:
                c = ramp(GLOW_END, 0.55)          # искры Энда в камне
            t.px(x, y, c)


def camera_white():
    t = Tex()
    obsidian(t)
    for i in range(N):
        t.px(i, 0, ramp(OBS, 0.9))
        t.px(0, i, ramp(OBS, 0.8))
        t.px(i, 31, ramp(OBS, 0.08))
        t.px(31, i, ramp(OBS, 0.12))
    return t


def camera_bracket():
    t = camera_white()
    for x in range(1, 31):
        t.px(x, 2, ramp(BRASS, 0.92))
        t.px(x, 3, ramp(BRASS, 0.6))
        t.px(x, 28, ramp(BRASS, 0.55))
        t.px(x, 29, ramp(BRASS, 0.25))
    for x, y in ((5, 7), (25, 7), (5, 23), (25, 23)):
        rivet(t, x, y, BRASS)
    t.glow(15.5, 15.5, 5, GLOW_END[2], 0.5)
    for y in range(13, 19):
        for x in range(13, 19):
            if math.hypot(x - 15.5, y - 15.5) < 2.8:
                t.px(x, y, ramp(GLOW_END, 1 - math.hypot(x - 14.5, y - 14.5) / 4))
    return t


def camera_pan_side():
    t = camera_white()
    for x in range(N):
        for y, k in ((3, 0.95), (4, 0.75), (5, 0.45), (24, 0.85), (25, 0.6), (26, 0.3)):
            t.px(x, y, ramp(BRASS, k + (noise(x, y, 3) - 0.5) * 0.05))
    for x0 in (6, 14, 22):
        t.rect(x0, 10, x0 + 3, 19, ramp(OBS, 0.05))
        on = x0 == 14
        for y in range(12, 18):
            for x in range(x0 + 1, x0 + 3):
                t.px(x, y, ramp(GLOW_END, (0.95 if on else 0.4) - 0.06 * abs(y - 14.5)))
        if on:
            t.glow(x0 + 2, 15, 5, GLOW_END[2], 0.35)
    return t


def camera_pan_top():
    t = camera_white()
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - 15.5, y - 15.5)
            if 10 < d < 12.5:
                t.px(x, y, ramp(BRASS, 0.85 - 0.5 * (x + y) / 62 - 0.1 * (d - 10)))
            elif d < 4:
                t.px(x, y, ramp(GLOW_END, 1 - d / 5))
    t.glow(15.5, 15.5, 7, GLOW_END[2], 0.3)
    return t


def camera_ball():
    """Корпус головы (UV 3..13 -> 6..25): обсидиан с латунным поясом посередине - закрывает око целиком."""
    t = Tex()
    obsidian(t, top=0.7, bottom=0.28, seed=33)
    for x in range(N):
        t.px(x, 14, ramp(BRASS, 0.95))
        t.px(x, 15, ramp(BRASS, 0.7))
        t.px(x, 16, ramp(BRASS, 0.45))
        t.px(x, 17, ramp(BRASS, 0.2))
    for x in (7, 15, 23):
        rivet(t, x, 15, BRASS)
    return t


def camera_face():
    """Лицо головы: корпус, латунная оправа и в окошке - только зрачок ока Эндера со свечением."""
    t = Tex()
    obsidian(t, top=0.6, bottom=0.25, seed=44)
    for y in range(N):
        for x in range(N):
            d = math.hypot(x - 15.5, y - 15.5)
            if d < 6.5:
                t.px(x, y, ramp(EYE, 0.3 - 0.25 * d / 6.5))       # радужка в глубине окошка почти не видна
            elif d < 9.8:
                t.px(x, y, ramp(BRASS, (0.9 if x + y < 31 else 0.35) - 0.2 * (d - 6.5) / 3.3))
            elif d < 10.8:
                t.px(x, y, ramp(OBS, 0.02))
    # зрачок ока: светящийся миндаль с узкой тёмной щелью посередине
    for y in range(10, 22):
        half = 3.0 * math.sqrt(max(0.0, 1 - ((y - 15.5) / 6.0) ** 2))
        for x in range(11, 21):
            dx = abs(x - 15.5)
            if dx < half:
                k = 1 - dx / half
                t.px(x, y, ramp(EYE, 0.35 + 0.6 * k - 0.15 * abs(y - 15.5) / 6))
        if half > 1.4:
            t.px(15, y, ramp(EYE, 0.02))
            t.px(16, y, ramp(EYE, 0.06))
    t.glow(15.5, 15.5, 7, EYE[4], 0.12)
    t.blend(12, 11, (255, 255, 255), 0.7)
    return t


def main():
    textures = {
        'fcs_front': fcs_front(False),
        'fcs_front_engaged': fcs_front(True),
        'flight_bridge_front': flight_bridge_front(),
        'memory_front': memory_front(True),
        'memory_front_off': memory_front(False),
        'camera_monitor_front': camera_monitor_front(),
        'radar_monitor_side': dark_casing(),
        'yaw_controller_plate': arrow_plate(False, BRASS, BLUE),
        'pitch_controller_plate': arrow_plate(True, BRASS, BLUE),
        'gun_drive_yaw': arrow_plate(False, STEEL, ORANGE),
        'gun_drive_pitch': arrow_plate(True, STEEL, ORANGE),
        'gun_rotator_band': hazard_band(),
        'iff_band': iff_band(),
        'iff_antenna': iff_antenna(),
        'radar_link_antenna': link_antenna(),
        'radar_link_side': link_side(BLUE),
        'radar_link_top': link_top(BLUE),
        'gun_link_side': link_side(ORANGE),
        'gun_link_top': link_top(ORANGE),
        'sight_body': sight_body(),
        'sight_lens': sight_lens(False),
        'sight_lens_firing': sight_lens(True),
        'camera_white': camera_white(),
        'camera_bracket': camera_bracket(),
        'camera_pan_side': camera_pan_side(),
        'camera_pan_top': camera_pan_top(),
        'camera_ball': camera_ball(),
        'camera_face': camera_face(),
    }
    for name, t in textures.items():
        t.save(name)
    print(f'{len(textures)} textures -> {os.path.normpath(OUT)}')


if __name__ == '__main__':
    main()
