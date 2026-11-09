#!/usr/bin/env python3
"""Тексты сцен Ponder -> src/main/resources/assets/radar_fcs/lang/{ru_ru,en_us}.json.

Ключи Ponder: radar_fcs.ponder.<сцена>.header, radar_fcs.ponder.<сцена>.text_N (N - порядок вызова say() в сцене
client/ponder/FcsScenes.java), radar_fcs.ponder.tag.<тег> и .description. Скрипт проверяет, что число реплик
совпадает с числом say() в каждой сцене, и заменяет все ключи radar_fcs.ponder.* в файлах перевода.

Запуск: python3 devtools/ponder_lang.py
"""
import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
LANG = os.path.join(ROOT, 'src', 'main', 'resources', 'assets', 'radar_fcs', 'lang')
SCENES_JAVA = os.path.join(ROOT, 'src', 'main', 'java', 'dev', 'radarfcs', 'client', 'ponder', 'FcsScenes.java')

TAG = {
    'fire_control': (
        ('Управление огнём', 'Fire Control'),
        ('Вычислитель, линки, контроллеры наводки, мониторы, камеры и орудия',
         'Fire control computer, links, aim controllers, monitors, cameras and guns'),
    ),
}

# сцена: (заголовок, [реплики]) - каждая пара (ru, en)
SCENES = {
    'battery': (('Батарея: вычислитель, пушка и радар', 'A battery: computer, cannon and radar'), [
        ('Вычислитель управления огнём наводит пушки Create Big Cannons и стреляет по целям сам. Пушка — на станке CBC '
         'или на компактном станке CBC: Firepower Components',
         'The Fire Control Computer aims Create Big Cannons and fires at targets on its own. The cannon sits on a CBC '
         'cannon mount or a compact mount from CBC: Firepower Components'),
        ('Станку нужны контроллеры наводки: горизонтальной — под станком, вертикальной — сбоку. В контроллеры подаётся '
         'вращение: от него зависит скорость поворота пушки',
         'The mount needs aim controllers: the yaw controller below it, the pitch controller on its side. Feed them '
         'rotation: it sets how fast the cannon turns'),
        ('Цели дают радары Create: Radars — например, плоский радар',
         'Targets come from Create: Radars radars, for example the plane radar'),
        ('Вычислитель можно поставить где угодно. Ему нужно электричество — 1024 FE/т',
         'The computer can stand anywhere. It needs electricity: 1024 FE/t'),
        ('Всё соединяется радарными линками одной сети — как складские передатчики Create. Расстояние не важно',
         'Everything connects with Radar Links of one network, like Create Stock Links. Distance does not matter'),
        ('Вычислитель выбирает цель, считает упреждение с баллистикой CBC, поворачивает пушку и стреляет, когда '
         'выстрел попадёт',
         'The computer picks a target, leads it using CBC ballistics, turns the cannon and fires when the shot will hit'),
        ('ПКМ по вычислителю — настройки: режим огня, типы целей, приоритеты, списки, свой-чужой. Shift+ПКМ — '
         'запретить или разрешить огонь',
         'Right-click the computer for settings: fire mode, target types, priorities, lists, IFF. Shift+right-click '
         'holds or allows fire'),
    ]),
    'aim_controllers': (('Контроллеры наводки', 'Aim Controllers'), [
        ('Контроллер горизонтальной наводки ставится вплотную под станком. Вал подаётся в него снизу',
         'The Yaw Controller goes right below the mount. Its shaft comes in from below'),
        ('Контроллер вертикальной наводки — сбоку станка, вал — с противоположной от станка стороны',
         'The Pitch Controller goes on the side of the mount, with its shaft on the far side'),
        ('Скорость поворота — как от валов CBC: 0,0375° за тик на 1 об/мин',
         'Turn speed works like CBC shafts: 0.0375° per tick for each RPM'),
        ('256 об/мин — 9,6° за тик. Для быстрых целей подавайте 128–256 об/мин',
         '256 RPM gives 9.6° per tick. For fast targets use 128-256 RPM'),
        ('Без контроллера или без вращения пушка не наводится и не стреляет. Очки инженера показывают скорость поворота',
         'Without a controller or rotation the cannon neither aims nor fires. Engineer\'s Goggles show the turn speed'),
    ]),
    'network': (('Радарная сеть и блок памяти', 'Radar networks and the Memory Block'), [
        ('Вычислитель, станки, радары и мониторы соединяются только радарными линками',
         'Computers, mounts, radars and monitors connect only through Radar Links'),
        ('Поставьте линк на аппаратуру. Ненастроенный линк начинает новую радарную сеть',
         'Place a link on a device. An untuned link starts a new radar network'),
        ('ПКМ линком в руке по стоящему линку — предмет настроен на его сеть. Следующие линки встают в эту сеть',
         'Right-click a placed link with a link item to tune the item to its network. Next links join that network'),
        ('Вся аппаратура одной сети связана, расстояние не ограничено. Взгляд на линк подсвечивает его сеть',
         'All devices of one network are connected at any distance. Looking at a link highlights its network'),
        ('В сети — 16 линков аппаратуры и ещё 16 за каждый блок памяти с питанием (240 FE/т). Блок памяти также '
         'добавляет 16 записей каждому списку вычислителя',
         'A network holds 16 device links plus 16 per powered Memory Block (240 FE/t). Each Memory Block also adds '
         '16 entries to every list of the computer'),
        ('Сигнал редстоуна силы 15 отключает линк', 'A redstone signal of strength 15 disables a link'),
        ('Shift+ПКМ в воздух или сетка крафта — сбросить настройку линка в руке',
         'Shift+right-click in the air or the crafting grid resets the link item'),
    ]),
    'radar_monitor': (('Радарный монитор', 'Radar Monitor'), [
        ('Мониторы ставятся стеной до 6×6 — стена складывается в один экран',
         'Place monitors as a wall up to 6x6: it forms a single screen'),
        ('Линк — на любой блок экрана. Монитор рисует карту целей и радаров своей радарной сети',
         'Put a link on any screen block. The monitor draws a map of targets and radars of its network'),
        ('Shift+ПКМ — окно монитора: карта, список целей, вид, метки, сторона карты',
         'Shift+right-click opens the monitor window: map, target list, view, pins and map size'),
        ('Клик по цели — назначить её: вычислители сети стреляют по ней вне очереди. Нажатие колеса мыши — исключить цель',
         'Click a target to designate it: computers of the network engage it first. Middle-click excludes a target'),
        ('Питание — 32 FE/т за каждый блок экрана, энергию можно подать в любой блок стены',
         'Power: 32 FE/t per screen block, fed into any block of the wall'),
    ]),
    'transponder': (('Транспондер «свой-чужой»', 'IFF Transponder'), [
        ('Транспондер помечает своих: вычислитель не стреляет по тому, что в его зоне, и не стреляет через неё',
         'The transponder marks friends: computers do not fire at anything in its zone or through it'),
        ('ПКМ — экран транспондера: имя, код (до 8 цифр) и режим зоны',
         'Right-click opens the transponder screen: name, code (up to 8 digits) and zone mode'),
        ('Зона: корабль Sable, на котором он стоит, шар заданного радиуса или корабль и расстояние от него',
         'Zone: the Sable ship it stands on, a sphere of a set radius, or the ship plus a distance around it'),
        ('Вычислитель решает, свой ли транспондер, по коду: вкладка «Свой-чужой» — белый или чёрный список кодов',
         'The computer decides by code: the IFF tab holds a whitelist or blacklist of codes'),
        ('Вкладка «Наследование» — кто в зоне защищён: игроки, мобы, корабли, снаряды. Питание — 64 FE/т',
         'The Inheritance tab sets who is protected in the zone: players, mobs, ships, projectiles. Power: 64 FE/t'),
    ]),
    'camera': (('Камеры и монитор камер', 'Cameras and the Camera Monitor'), [
        ('Камера ставится на стену, потолок или пол. Голова поворачивается туда, куда через неё смотрят',
         'A camera goes on a wall, ceiling or floor. Its head turns where you look through it'),
        ('Подключение — радарным линком или без него: ПКМ предметом камеры по радарному линку настраивает его на сеть',
         'Connect it with a Radar Link or without one: right-click a Radar Link with the camera item to tune it'),
        ('Монитор камер (ПКМ) — список камер сети. «Смотреть» — вид из камеры. Питание — 64 FE/т за камеру',
         'The Camera Monitor (right-click) lists the cameras of its network. "View" looks through one. Power: 64 FE/t per camera'),
        ('Пока вы смотрите, пушки сети наводятся туда, куда смотрит камера. ЛКМ — огонь, Shift — выход',
         'While you look, the guns of the network aim where the camera looks. Left-click fires, Shift exits'),
        ('Кнопка «Орудия» у камеры — какими орудиями она управляет; остальные ведут цели сами',
         'The "Guns" button chooses which guns the camera controls; the others keep tracking targets'),
        ('Если смотреть на само управляемое орудие, оно не поворачивается', 'Looking at a controlled gun itself keeps it still'),
    ]),
    'turret': (('Орудие на вращателях', 'Guns on Gun Rotators'), [
        ('Вращатель орудия (нужен Sable) поворачивает постройку перед своим выходом. Вращение — валом сзади',
         'The Gun Rotator (needs Sable) turns the structure in front of its output. Feed rotation from behind'),
        ('ПКМ пустой рукой — собрать. Обычная схема: горизонтальный вращатель, на его поворотной части — вертикальный. '
         'Сначала собирайте внутренний',
         'Right-click with an empty hand to assemble. Usual layout: a horizontal rotator carrying a vertical one. '
         'Assemble the inner one first'),
        ('Прицел ставится объективом туда, куда смотрите. Вычислитель наводит направление прицела, форма постройки не важна',
         'The Sight faces where you look when placing it. The computer aims the sight direction; the shape does not matter'),
        ('Орудийный линк объединяет части одного орудия: прицел, вращатели, приводы и станки CBC',
         'Gun Links join the parts of one gun: sight, rotators, gun drives and CBC mounts'),
        ('Радарный линк — на любую часть орудия: всё орудие подключается к вычислителю одним линком',
         'Put a Radar Link on any part of the gun: one link connects the whole gun to the computer'),
        ('Когда орудие должно стрелять, прицел выдаёт сигнал редстоуна 15; станки CBC орудия стреляют сами',
         'When the gun should fire, the sight emits redstone 15; CBC mounts of the gun fire by themselves'),
    ]),
    'gun_drive': (('Привод орудия', 'Gun Drive'), [
        ('Привод орудия — как коробка передач: вход сзади, выход спереди. Скорость выхода задаёт вычислитель. '
         'Здесь он крутит вал поворота станка вместо контроллера наводки',
         'The Gun Drive works like a gearbox: input behind, output in front. The computer sets the output speed. '
         'Here it turns the yaw shaft of the mount instead of an aim controller'),
        ('ПКМ пустой рукой — режим привода: курс или наклон', 'Right-click with an empty hand to switch between yaw and pitch'),
        ('Выход может крутить подшипник, шестерни или вал станка. Передаточное число и направление вычислитель выучивает сам',
         'The output can drive a bearing, gears or a mount shaft. The computer learns the ratio and direction by itself'),
        ('Приводы и станок связываются орудийными линками в одно орудие', 'Gun Links join the drives and the mount into one gun'),
        ('Shift+ПКМ по орудийному линку — настройки орудия: имя, тип, автоприцеливание, стрельба, основная пушка',
         'Shift+right-click a Gun Link for gun settings: name, type, auto aim, firing and the main gun'),
        ('Станок CBC в орудии стреляет по команде вычислителя. Радарный линк — на любую часть орудия',
         'The CBC mount of the gun fires on command. Put a Radar Link on any part of the gun'),
    ]),
}


def say_counts():
    src = open(SCENES_JAVA, encoding='utf-8').read()
    counts = {}
    for m in re.finditer(r's\.title\("(\w+)"', src):
        name = m.group(1)
        start = m.end()
        end = src.find('markAsFinished', start)
        counts[name] = len(re.findall(r'\bsay\(s,', src[start:end]))
    return counts


def main():
    counts = say_counts()
    bad = False
    for name, (_, texts) in SCENES.items():
        if counts.get(name) != len(texts):
            print(f'scene {name}: {counts.get(name)} say() in code, {len(texts)} texts')
            bad = True
    for name in counts:
        if name not in SCENES:
            print(f'scene {name}: no texts')
            bad = True
    if bad:
        sys.exit(1)
    for lang, i in (('ru_ru', 0), ('en_us', 1)):
        path = os.path.join(LANG, lang + '.json')
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        data = {k: v for k, v in data.items() if not k.startswith('radar_fcs.ponder.')}
        for tag, (title, desc) in TAG.items():
            data[f'radar_fcs.ponder.tag.{tag}'] = title[i]
            data[f'radar_fcs.ponder.tag.{tag}.description'] = desc[i]
        for name, (header, texts) in SCENES.items():
            data[f'radar_fcs.ponder.{name}.header'] = header[i]
            for n, t in enumerate(texts, 1):
                data[f'radar_fcs.ponder.{name}.text_{n}'] = t[i]
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write('\n')
        print(f'{path}: {sum(len(t) + 1 for _, t in SCENES.values()) + 2 * len(TAG)} ponder keys')


if __name__ == '__main__':
    main()
