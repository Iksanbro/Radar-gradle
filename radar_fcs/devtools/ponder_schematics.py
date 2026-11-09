#!/usr/bin/env python3
"""Схемы сцен Ponder (обучающих сцен, как в Create): src/main/resources/assets/radar_fcs/ponder/<сцена>.nbt.

Формат - структура Minecraft (gzip NBT): size, palette (Name + Properties - только нужные свойства, остальные по
умолчанию), blocks (pos, state). Основание - шахматка из снега и белого бетона, как у Create. Блок-энтити создаются
самим миром Ponder при установке блока.

Запуск: python3 devtools/ponder_schematics.py
"""
import gzip
import os
import struct
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'radar_fcs', 'ponder')
DATA_VERSION = 3955  # 1.21.1


# ---------------------------------------------------------------- NBT
def _s(v):
    b = v.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def _payload(t, v):
    if t == 3:
        return struct.pack('>i', v)
    if t == 8:
        return _s(v)
    if t == 9:
        et, items = v
        out = struct.pack('>bi', et, len(items))
        for it in items:
            out += _payload(et, it)
        return out
    if t == 10:
        out = b''
        for k, (tt, vv) in v.items():
            out += struct.pack('>b', tt) + _s(k) + _payload(tt, vv)
        return out + b'\x00'
    raise ValueError(t)


def Int(v): return (3, v)
def Str(v): return (8, v)
def Comp(d): return (10, d)
def IntList(vs): return (9, (3, list(vs)))
def CompList(ds): return (9, (10, list(ds)))


# ---------------------------------------------------------------- схема
class Schematic:
    def __init__(self, size_x, size_y, size_z):
        self.size = (size_x, size_y, size_z)
        self.blocks = {}

    def plate(self):
        for x in range(self.size[0]):
            for z in range(self.size[2]):
                self.set(x, 0, z, 'minecraft:snow_block' if (x + z) % 2 == 0 else 'minecraft:white_concrete')
        return self

    def set(self, x, y, z, name, **props):
        assert 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2], (name, x, y, z)
        self.blocks[(x, y, z)] = (name, tuple(sorted((k, str(v).lower()) for k, v in props.items())))
        return self

    def save(self, name):
        palette, index, blocks = [], {}, []
        for pos in sorted(self.blocks, key=lambda p: (p[1], p[2], p[0])):
            st = self.blocks[pos]
            if st not in index:
                index[st] = len(palette)
                entry = {'Name': Str(st[0])}
                if st[1]:
                    entry['Properties'] = Comp({k: Str(v) for k, v in st[1]})
                palette.append(entry)
            blocks.append({'pos': IntList(pos), 'state': Int(index[st])})
        root = {
            'DataVersion': Int(DATA_VERSION),
            'size': IntList(self.size),
            'palette': CompList(palette),
            'blocks': CompList(blocks),
            'entities': CompList([]),
        }
        data = struct.pack('>b', 10) + _s('') + _payload(10, root)
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, name + '.nbt')
        with open(path, 'wb') as raw, gzip.GzipFile(fileobj=raw, mode='wb', mtime=0) as f:
            f.write(data)
        print(f'{path}: {len(self.blocks)} blocks, {len(palette)} states')


# ---------------------------------------------------------------- блоки
FCS = 'radar_fcs:'


def autocannon(s, pivot, dir_, barrels):
    """Чугунная автопушка: казённик за осью, пружина на оси, стволы вперёд (ось - pivot, ствол вдоль +X или +Z)."""
    dx, dz = {'east': (1, 0), 'south': (0, 1)}[dir_]
    x, y, z = pivot
    s.set(x - dx, y, z - dz, 'createbigcannons:cast_iron_autocannon_breech', facing=dir_)
    s.set(x, y, z, 'createbigcannons:cast_iron_autocannon_recoil_spring', facing=dir_)
    for i in range(1, barrels + 1):
        s.set(x + dx * i, y, z + dz * i, 'createbigcannons:cast_iron_autocannon_barrel', facing=dir_)


def mount(s, x, y, z, facing='east'):
    s.set(x, y, z, 'createbigcannons:cannon_mount', facing=facing, vertical_direction='down')


def link(s, x, y, z, facing, gun=False):
    s.set(x, y, z, FCS + ('gun_link' if gun else 'radar_link'), facing=facing)


def motor(s, x, y, z, facing):
    s.set(x, y, z, 'create:creative_motor', facing=facing)


def battery():
    s = Schematic(7, 5, 7).plate()
    mount(s, 2, 2, 3)
    motor(s, 2, 0, 3, 'up')
    s.set(2, 1, 3, FCS + 'yaw_controller', facing='up')
    s.set(2, 2, 4, FCS + 'pitch_controller', facing='north')
    motor(s, 2, 2, 5, 'north')
    autocannon(s, (2, 4, 3), 'east', 3)
    link(s, 1, 2, 3, 'west')
    s.set(5, 1, 1, FCS + 'fire_control_computer', facing='north')
    link(s, 5, 2, 1, 'up')
    s.set(5, 1, 5, 'create_radar:plane_radar', facing='west')
    link(s, 5, 2, 5, 'up')
    s.save('battery')


def aim_controllers():
    s = Schematic(5, 5, 5).plate()
    mount(s, 2, 2, 2)
    motor(s, 2, 0, 2, 'up')
    s.set(2, 1, 2, FCS + 'yaw_controller', facing='up')
    s.set(2, 2, 3, FCS + 'pitch_controller', facing='north')
    motor(s, 2, 2, 4, 'north')
    autocannon(s, (2, 4, 2), 'east', 2)
    s.save('aim_controllers')


def network():
    s = Schematic(7, 3, 7).plate()
    s.set(1, 1, 1, FCS + 'fire_control_computer', facing='north')
    link(s, 1, 2, 1, 'up')
    s.set(2, 2, 1, 'minecraft:redstone_block')
    mount(s, 5, 1, 1, 'south')
    link(s, 4, 1, 1, 'west')
    s.set(5, 1, 5, 'create_radar:plane_radar', facing='west')
    link(s, 5, 2, 5, 'up')
    s.set(1, 1, 5, FCS + 'memory_block', facing='north')
    link(s, 1, 2, 5, 'up')
    s.save('network')


def radar_monitor():
    s = Schematic(5, 4, 5).plate()
    for x in range(1, 4):
        for y in range(1, 3):
            s.set(x, y, 2, FCS + 'radar_monitor', facing='north')
    link(s, 2, 1, 3, 'south')
    s.save('radar_monitor')


def transponder():
    s = Schematic(7, 3, 7).plate()
    for x in range(2, 5):
        for z in range(2, 5):
            s.set(x, 1, z, 'minecraft:spruce_planks')
    s.set(3, 2, 3, FCS + 'iff_transponder')
    s.save('transponder')


def camera():
    s = Schematic(7, 5, 7).plate()
    for y in range(1, 4):
        s.set(1, y, 4, 'minecraft:stone_bricks')
    s.set(2, 3, 4, FCS + 'camera', facing='east')
    s.set(4, 1, 1, FCS + 'camera_monitor', facing='north')
    link(s, 4, 2, 1, 'up')
    s.save('camera')


def turret():
    s = Schematic(5, 6, 5).plate()
    motor(s, 2, 0, 2, 'up')
    s.set(2, 1, 2, FCS + 'gun_rotator', facing='up')
    link(s, 2, 1, 1, 'north')
    link(s, 1, 1, 2, 'west', gun=True)
    s.set(2, 2, 2, FCS + 'gun_rotator', facing='east')
    s.set(3, 2, 2, 'create:andesite_casing')
    s.set(4, 2, 2, 'create:andesite_casing')
    s.set(3, 3, 2, FCS + 'sight', facing='east')
    link(s, 3, 4, 2, 'up', gun=True)
    s.save('turret')


def gun_drive():
    s = Schematic(7, 5, 6).plate()
    mount(s, 3, 2, 2)
    motor(s, 3, 0, 2, 'up')
    s.set(3, 1, 2, FCS + 'gun_drive', facing='up', pitch='false')
    s.set(3, 2, 3, FCS + 'gun_drive', facing='north', pitch='true')
    motor(s, 3, 2, 4, 'north')
    autocannon(s, (3, 4, 2), 'east', 3)
    link(s, 2, 2, 2, 'west', gun=True)
    link(s, 2, 1, 2, 'west', gun=True)
    link(s, 4, 2, 3, 'east', gun=True)
    s.save('gun_drive')


if __name__ == '__main__':
    for f in (battery, aim_controllers, network, radar_monitor, transponder, camera, turret, gun_drive):
        f()
    sys.exit(0)
