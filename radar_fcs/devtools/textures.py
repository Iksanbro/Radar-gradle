#!/usr/bin/env python3
"""Текстуры блоков 16x16 в стиле Minecraft / Create -> src/main/resources/assets/radar_fcs/textures/block.

Рисуется попиксельно, без сглаживания: рамки латуни и андезита в духе обшивок Create (светлый верх-лево, тёмный
низ-право), экраны и панели с лампами, накладки на обшивку. Разметка UV моделей прежняя (в координатах 0..16).
radar_monitor_front не трогается (под рендер карты).

Запуск: python3 devtools/textures.py
"""
import math
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'radar_fcs',
                   'textures', 'block')


def hx(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


CLEAR = (0, 0, 0, 0)
# латунь (обшивка Create)
B_EDGE_D, B_EDGE, B_EDGE_L = hx('592424'), hx('724731'), hx('9e6947')
B_HI, B_LIGHT, B_MID, B_DARK = hx('ffeb8c'), hx('f7cb6c'), hx('e4b763'), hx('cea05a')
WOOD_D, WOOD, WOOD_L = hx('301e0e'), hx('3a2411'), hx('4f3218')
# тёмный металл
M_0, M_1, M_2, M_3, M_4, M_5 = hx('15181b'), hx('1f2326'), hx('2b3034'), hx('383e43'), hx('4a5157'), hx('636b73')
SCREEN_BEZEL = hx('17191b')
# лампы
L_GREEN, L_YELLOW, L_RED, L_RED_OFF = hx('6be36b'), hx('ffd23f'), hx('ff4a3a'), hx('6a2420')
L_OFF = hx('2c3a2e')
BTN, BTN_L = hx('7c8389'), hx('a9b0b6')
BLUE, BLUE_L, BLUE_D = hx('4aa8ff'), hx('a6dbff'), hx('2a6fb8')
ORANGE, ORANGE_L, ORANGE_D = hx('ff9a2e'), hx('ffd08a'), hx('b85e14')


class Tex:
    def __init__(self, fill=CLEAR):
        self.im = Image.new('RGBA', (16, 16), fill)

    def px(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.im.putpixel((x, y), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def get(self, x, y):
        return self.im.getpixel((x, y))

    def save(self, name):
        self.im.save(os.path.join(OUT, name + '.png'))


def brass_frame(t):
    """Двойная рамка латуни, как у обшивки Create: тёмный край, светлая фаска (свет сверху-слева)."""
    t.rect(0, 0, 15, 0, B_EDGE_L)
    t.rect(0, 0, 0, 15, B_EDGE_L)
    t.rect(0, 15, 15, 15, B_EDGE)
    t.rect(15, 0, 15, 15, B_EDGE)
    t.px(0, 15, B_EDGE_D)
    t.px(15, 0, B_EDGE_D)
    t.rect(1, 1, 14, 1, B_HI)
    t.rect(1, 1, 1, 14, B_LIGHT)
    t.rect(14, 2, 14, 14, B_DARK)
    t.rect(2, 14, 14, 14, B_DARK)
    for x in (4, 9):
        t.px(x, 14, B_MID)
    t.px(14, 1, B_LIGHT)
    t.px(1, 14, B_MID)


def panel(t, lamps):
    """Внутренность: экран (2..13, 2..11) и панель с лампами (строки 12-13)."""
    t.rect(2, 2, 13, 13, WOOD)
    t.rect(2, 13, 13, 13, WOOD_D)
    t.rect(2, 2, 13, 11, SCREEN_BEZEL)
    for i, c in enumerate(lamps):
        t.px(3 + 2 * i, 12, c)
    t.px(10, 12, BTN_L)
    t.px(11, 12, BTN)
    t.px(12, 12, BTN)


def radar_screen(t, sweep, trail, blip):
    """Экран 10x8 (3..12, 3..10): кольца, луч с шлейфом, отметки."""
    cx, cy = 7.5, 6.5
    for y in range(3, 11):
        for x in range(3, 13):
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)
            a = math.degrees(math.atan2(dx, -dy)) % 360  # 0 - вверх, по часовой
            c = hx('0c1b13')
            if abs(d - 3.9) < 0.45 or abs(d - 1.9) < 0.4:
                c = hx('1d4a2c')
            if 20 <= a <= 50 and d < 4.4:
                c = sweep
            elif 0 <= a < 20 and d < 4.4:
                c = trail
            t.px(x, y, c)
    for x, y in blip:
        t.px(x, y, hx('b6ffb0'))


def fcs_front(engaged):
    t = Tex()
    brass_frame(t)
    panel(t, [L_GREEN, L_YELLOW, L_RED if engaged else L_RED_OFF])
    if engaged:
        radar_screen(t, hx('ff6a4a'), hx('8a2c22'), [(10, 4)])
        t.px(10, 4, hx('ffd0c0'))
        t.px(5, 8, hx('b6ffb0'))
    else:
        radar_screen(t, hx('3fd06a'), hx('1f6e38'), [(10, 4), (5, 8)])
    return t


def flight_bridge_front():
    t = Tex()
    brass_frame(t)
    panel(t, [L_GREEN, hx('4aa8ff'), L_YELLOW])
    # авиагоризонт: небо, земля, линия горизонта, силуэт самолёта
    for y in range(3, 11):
        for x in range(3, 13):
            if y <= 6:
                c = hx('5aa2e6') if y == 3 else hx('3d84cc')
            else:
                c = hx('8a5a2b') if y == 7 else hx('6b4421')
            t.px(x, y, c)
    for x in range(3, 13):
        t.px(x, 7, hx('e9e9e9'))
    for x in (4, 5, 6, 9, 10, 11):
        t.px(x, 6, L_YELLOW)
    t.px(7, 7, L_YELLOW)
    t.px(8, 7, L_YELLOW)
    # шкала курса сверху
    for x in (4, 7, 8, 11):
        t.px(x, 3, hx('d8ecff'))
    return t


def memory_front(on):
    t = Tex()
    brass_frame(t)
    pcb, trace, gold = hx('2f6b3a'), hx('245530'), hx('c9a24a')
    t.rect(2, 2, 13, 13, pcb)
    for x in range(2, 14):
        if x % 3 == 0:
            t.px(x, 10, trace)
            t.px(x, 13, trace)
    # две микросхемы с ножками
    for x0 in (3, 9):
        t.rect(x0 + 1, 3, x0 + 2, 8, hx('1e1f23'))
        t.px(x0 + 1, 3, hx('3a3c42'))
        for y in (4, 6, 8):
            t.px(x0, y, hx('b8bcc2'))
            t.px(x0 + 3, y, hx('b8bcc2'))
    leds = [L_GREEN, L_GREEN, L_YELLOW, L_GREEN, hx('4aa8ff')] if on else [L_OFF] * 5
    for i, c in enumerate(leds):
        t.px(3 + 2 * i, 11, c)
    for x in range(3, 13, 2):
        t.px(x, 12, gold)
    return t


def camera_monitor_front():
    t = Tex()
    brass_frame(t)
    panel(t, [L_GREEN, L_YELLOW, L_RED_OFF])
    t.rect(3, 3, 12, 10, M_0)
    for x0, y0 in ((3, 3), (9, 3), (3, 8), (9, 8)):
        t.rect(x0, y0, x0 + 3, y0, hx('7fbfff'))
        t.rect(x0, y0 + 1, x0 + 3, y0 + 1, hx('4f9a3c'))
        t.rect(x0, y0 + 2, x0 + 3, y0 + 2, hx('3d7a2e'))
    t.px(10, 9, hx('8a5a2b'))
    t.px(4, 4, hx('8a8f94'))
    t.px(3, 3, L_RED)
    return t


def dark_casing():
    """Тёмная металлическая обшивка (боковины мониторов): рамка с фаской и вентиляционные прорези."""
    t = Tex(M_3)
    t.rect(0, 0, 15, 0, M_4)
    t.rect(0, 0, 0, 15, M_4)
    t.rect(0, 15, 15, 15, M_1)
    t.rect(15, 0, 15, 15, M_1)
    t.rect(1, 1, 14, 1, M_5)
    t.rect(1, 1, 1, 14, M_5)
    t.rect(14, 2, 14, 14, M_2)
    t.rect(2, 14, 14, 14, M_2)
    for y in (4, 7, 10):
        t.rect(4, y, 11, y, M_0)
        t.rect(4, y + 1, 11, y + 1, M_4)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        t.px(x, y, M_5)
    return t


def arrow_plate(vertical, frame_light, frame_dark, fill, arrow, arrow_d):
    """Накладка 10x10 (3..12) с двойной стрелкой: курс - ↔, наклон - ↕."""
    t = Tex()
    t.rect(3, 3, 12, 12, frame_dark)
    t.rect(3, 3, 11, 3, frame_light)
    t.rect(3, 3, 3, 11, frame_light)
    t.rect(4, 4, 11, 11, fill)
    pts = [(6, 5), (9, 5), (5, 6), (10, 6), (5, 9), (10, 9), (6, 10), (9, 10)]
    shaft = [(x, y) for x in range(4, 12) for y in (7, 8)]
    for x, y in pts + shaft:
        if vertical:
            x, y = y, x
        t.px(x, y, arrow)
    # тень стрелки снизу-справа
    for x, y in [(11, 8), (10, 10), (9, 11)] if not vertical else [(8, 11), (10, 10), (11, 9)]:
        if t.get(x, y) == fill:
            t.px(x, y, arrow_d)
    return t


def hazard_band():
    t = Tex()
    t.rect(0, 6, 15, 6, M_1)
    t.rect(0, 9, 15, 9, M_1)
    for y in (7, 8):
        for x in range(16):
            t.px(x, y, hx('f2c230') if (x + y) % 4 < 2 else hx('26262a'))
    return t


def iff_band():
    """Полоса транспондера - в оттенках серого (красится цветом транспондера)."""
    t = Tex()
    t.rect(0, 5, 15, 5, hx('5c5c5c'))
    t.rect(0, 10, 15, 10, hx('4a4a4a'))
    t.rect(0, 6, 15, 9, hx('8c8c8c'))
    for x0 in (1, 5, 9, 13):
        t.rect(x0, 7, x0 + 1, 7, hx('ffffff'))
        t.rect(x0, 8, x0 + 1, 8, hx('d6d6d6'))
    return t


def iff_antenna():
    t = Tex()
    for y in range(12):
        if y < 2:
            l, d = hx('ff7a6a'), hx('d63a2e')
        else:
            l, d = (hx('b9bec4'), hx('8e949a')) if y % 3 else (hx('7a8086'), hx('5f656b'))
        t.px(7, y, l)
        t.px(8, y, d)
    t.rect(6, 0, 9, 1, hx('e04a3c'))
    t.px(7, 0, hx('ff9a8a'))
    return t


def link_antenna():
    t = Tex()
    t.px(7, 0, BLUE_L)
    t.px(8, 0, BLUE)
    t.px(7, 1, BLUE)
    t.px(8, 1, BLUE_D)
    for y in (2, 3):
        t.px(7, y, hx('b9bec4'))
        t.px(8, y, hx('8e949a'))
    return t


def link_side(c, cd):
    """Бок плоского передатчика (UV строки 13-15): латунь, цветная полоса, тёмная латунь."""
    t = Tex()
    for y in range(16):
        k = y % 3
        for x in range(16):
            if k == 1:
                t.px(x, y, B_LIGHT)
            elif k == 2:
                t.px(x, y, c if x % 4 else cd)
            else:
                t.px(x, y, B_EDGE_L)
    return t


def link_top(c, cl, cd):
    """Верх передатчика (UV 2..13): латунная рамка, тёмная плата, узел и дорожки к углам."""
    t = Tex()
    t.rect(2, 2, 13, 13, B_EDGE)
    t.rect(2, 2, 12, 2, B_HI)
    t.rect(2, 2, 2, 12, B_LIGHT)
    t.rect(3, 3, 12, 12, M_1)
    for i in range(4, 7):
        for x, y in ((i, i), (15 - i, i), (i, 15 - i), (15 - i, 15 - i)):
            t.px(x, y, cd)
    for x, y in ((4, 4), (11, 4), (4, 11), (11, 11)):
        t.px(x, y, c)
    t.rect(6, 6, 9, 9, cd)
    t.rect(7, 7, 8, 8, c)
    t.px(7, 7, cl)
    return t


def sight_body():
    t = Tex(M_3)
    for y in range(16):
        for x in range(16):
            if (x * 7 + y * 3) % 11 == 0:
                t.px(x, y, M_4)
    t.rect(0, 0, 15, 0, M_5)
    t.rect(0, 15, 15, 15, M_1)
    t.rect(0, 7, 15, 7, B_HI)
    t.rect(0, 8, 15, 8, B_DARK)
    t.rect(0, 9, 15, 9, B_EDGE)
    for x in (2, 13):
        t.px(x, 2, M_5)
        t.px(x, 13, M_5)
    return t


def sight_lens(firing):
    t = Tex(M_2)
    if firing:
        glass, glass_l, glass_d = hx('ff8a2a'), hx('ffc070'), hx('c45a10')
    else:
        glass, glass_l, glass_d = hx('2e8a8f'), hx('6fd0d4'), hx('1d5e62')
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d < 5.6:
                c = glass_l if (x + y < 12 and d > 2.5) else glass
                if x + y > 18 and d > 3.2:
                    c = glass_d
                t.px(x, y, c)
            elif d < 7.0:
                t.px(x, y, B_LIGHT if x + y < 15 else B_DARK)
            elif d < 7.7:
                t.px(x, y, M_1)
    for i in (3, 4, 11, 12):
        t.px(i, 7, M_0)
        t.px(i, 8, M_0)
        t.px(7, i, M_0)
        t.px(8, i, M_0)
    t.rect(7, 7, 8, 8, L_RED if not firing else hx('fff3d0'))
    t.px(5, 4, hx('ffffff'))
    return t


# камера в стиле ока Эндера: корпус - тёмный, как обсидиан, с латунью; шар и объектив - око
OBS, OBS_L, OBS_D, OBS_N = hx('1f1729'), hx('3b2c52'), hx('0e0a14'), hx('281d36')
EYE_0, EYE_1, EYE_2, EYE_3, EYE_4 = hx('0b2a22'), hx('125846'), hx('1d8a68'), hx('35b98c'), hx('8af0c8')
PUPIL = hx('050f0c')
END_GLOW = hx('c77dff')


def camera_white():
    t = Tex(OBS)
    for y in range(16):
        for x in range(16):
            if (x * 5 + y * 9) % 13 == 0:
                t.px(x, y, OBS_N)
    t.rect(0, 0, 15, 0, OBS_L)
    t.rect(0, 0, 0, 15, OBS_L)
    t.rect(0, 15, 15, 15, OBS_D)
    t.rect(15, 1, 15, 15, OBS_D)
    return t


def camera_bracket():
    t = camera_white()
    t.rect(1, 1, 14, 1, B_DARK)
    t.rect(1, 14, 14, 14, B_EDGE)
    for x, y in ((2, 3), (13, 3), (2, 12), (13, 12)):
        t.px(x, y, B_LIGHT)
        t.px(x + 1 if x < 8 else x - 1, y + 1, OBS_D)
    t.rect(6, 6, 9, 9, OBS_D)
    t.rect(7, 7, 8, 8, END_GLOW)
    return t


def camera_pan_side():
    t = camera_white()
    t.rect(0, 2, 15, 2, B_HI)
    t.rect(0, 3, 15, 3, B_DARK)
    t.rect(0, 12, 15, 12, B_LIGHT)
    t.rect(0, 13, 15, 13, B_EDGE)
    for x in (3, 7, 11):
        t.rect(x, 6, x + 1, 9, OBS_D)
        t.rect(x, 7, x + 1, 8, END_GLOW if x == 7 else hx('5c3a7a'))
    return t


def camera_pan_top():
    t = camera_white()
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if abs(d - 5.5) < 0.6:
                t.px(x, y, B_LIGHT if x + y < 15 else B_DARK)
            elif d < 2.2:
                t.px(x, y, END_GLOW if x + y < 15 else hx('8a4fc0'))
    return t


def camera_ball():
    """Шар головы - око Эндера: ровный бирюзово-зелёный с крапинками (голова собрана из нескольких кубов -
    градиент на каждой грани выдал бы их углы)."""
    t = Tex(EYE_2)
    for y in range(16):
        for x in range(16):
            h = (x * 73 + y * 151 + x * y * 7) % 23
            if h < 4:
                t.px(x, y, EYE_1)
            elif h < 6:
                t.px(x, y, EYE_3)
            elif h == 6:
                t.px(x, y, EYE_0)
            elif h == 7 and (x + y) % 3 == 0:
                t.px(x, y, EYE_4)
    return t


def camera_face():
    """Объектив - зрачок ока Эндера: тёмная радужка с кольцом и вертикальный зрачок-щель."""
    t = Tex(EYE_1)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 7.0:
                c = EYE_0
            elif d > 5.6:
                c = EYE_3 if x + y < 15 else EYE_2
            elif d > 4.2:
                c = EYE_2
            else:
                c = EYE_1
            t.px(x, y, c)
    for y in range(3, 13):
        w = 1 if y in (3, 4, 11, 12) else 2
        for x in range(8 - w, 8 + w):
            t.px(x, y, PUPIL)
    t.px(5, 4, EYE_4)
    t.px(4, 5, EYE_3)
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
        'yaw_controller_plate': arrow_plate(False, B_LIGHT, B_EDGE, M_1, BLUE_L, BLUE_D),
        'pitch_controller_plate': arrow_plate(True, B_LIGHT, B_EDGE, M_1, BLUE_L, BLUE_D),
        'gun_drive_yaw': arrow_plate(False, M_4, M_0, M_1, ORANGE, ORANGE_D),
        'gun_drive_pitch': arrow_plate(True, M_4, M_0, M_1, ORANGE, ORANGE_D),
        'gun_rotator_band': hazard_band(),
        'iff_band': iff_band(),
        'iff_antenna': iff_antenna(),
        'radar_link_antenna': link_antenna(),
        'radar_link_side': link_side(BLUE, BLUE_D),
        'radar_link_top': link_top(BLUE, BLUE_L, BLUE_D),
        'gun_link_side': link_side(ORANGE, ORANGE_D),
        'gun_link_top': link_top(ORANGE, ORANGE_L, ORANGE_D),
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
