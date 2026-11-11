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
        ('Вычислитель управления огнём наводит пушки Create Big Cannons и стреляет сам. Пушка — на станке CBC или на '
         'компактном станке CBC: Firepower Components; подходят большие пушки, автопушки и пушки аддонов',
         'The Fire Control Computer aims Create Big Cannons and fires on its own. The cannon sits on a CBC mount or a '
         'compact mount from CBC: Firepower Components; big cannons, autocannons and addon guns all work'),
        ('Станку нужны контроллеры наводки: горизонтальной — под станком, вертикальной — сбоку. Вращение подаётся в '
         'контроллеры, а не в станок: от скорости вала зависит, как быстро пушка поворачивается',
         'The mount needs aim controllers: yaw below it, pitch on its side. Feed rotation into the controllers, not the '
         'mount: shaft speed sets how fast the cannon turns'),
        ('Цели дают радары Create: Radars — плоский радар, радарный подшипник и другие. Без работающего радара в сети '
         'огня нет (кроме цели, назначенной вручную)',
         'Targets come from Create: Radars radars: plane radar, radar bearing and others. Without a working radar in the '
         'network there is no fire (except a manually designated target)'),
        ('Вычислитель можно поставить где угодно. Ему нужно электричество — 1024 FE/т; без энергии он не видит целей',
         'The computer can stand anywhere. It needs 1024 FE/t of electricity; without power it sees no targets'),
        ('Всё соединяется радарными линками одной сети — как складские передатчики Create. Расстояние не важно',
         'Everything connects with Radar Links of one network, like Create Stock Links. Distance does not matter'),
        ('Вычислитель выбирает цель, считает упреждение по баллистике CBC (заряды, стволы, снаряд), поворачивает пушку '
         'и стреляет. Баллистика уточняется по каждому выстрелу',
         'The computer picks a target, leads it with CBC ballistics (charges, barrels, shell), turns the cannon and '
         'fires. Each shot refines the ballistics'),
        ('Огонь — только когда расчётный выстрел из текущего положения ствола попадёт и на линии огня нет своих и блоков',
         'It fires only when a shot from the current barrel position will hit and no friends or blocks are in the way'),
        ('ПКМ по вычислителю — настройки (подробно — в следующей сцене). Shift+ПКМ — запретить или разрешить огонь: '
         'наведение продолжается',
         'Right-click the computer for settings (see the next scene). Shift+right-click holds or allows fire; aiming '
         'continues'),
    ]),
    'computer_settings': (('Настройки вычислителя', 'Fire control computer settings'), [
        ('ПКМ по вычислителю — экран настроек. У каждого вычислителя свои настройки, они хранятся в блоке. Числа меняются '
         'колесом мыши или кнопками «−/+» (с Shift — шаг больше)',
         'Right-click the computer to open its settings. Each computer has its own, stored in the block. Change numbers '
         'with the mouse wheel or the -/+ buttons (Shift for bigger steps)'),
        ('Вкладка «Огонь» — режим огня. Одиночный: выстрел, затем пауза. Автоматический: огонь, пока выстрел попадёт. '
         'Очередь: N выстрелов, затем пауза (в секундах). Выстрелы считаются по снарядам, вылетевшим из ствола',
         'Fire tab: fire mode. Single: one shot, then a pause. Automatic: fire while the shot will hit. Burst: N shots, '
         'then a pause in seconds. Shots are counted by projectiles leaving the barrel'),
        ('Вкладка «Цели» — какие цели брать: корабли, игроки, мобы, сущности (лодки, вагонетки, контрапшены). У каждого '
         'типа — уровень приоритета 1–5 (выше — раньше) и окно скорости «от … до …» м/с',
         'Targets tab: what to engage: ships, players, mobs, entities (boats, minecarts, contraptions). Each type has a '
         'priority level 1-5 (higher first) and a speed window "from ... to ..." m/s'),
        ('Вкладка «Списки»: белый список — стрелять всегда, в обход типов; чёрный — никогда. Запись: id (minecraft:zombie), '
         'тег (#minecraft:undead), весь мод (create:*) или имя игрока, корабля, моба с биркой. 16 записей + 16 за блок памяти',
         'Lists tab: the whitelist is always engaged, regardless of types; the blacklist never. Entries: an id '
         '(minecraft:zombie), a tag (#minecraft:undead), a whole mod (create:*) or a player, ship or name-tagged mob name. '
         '16 entries + 16 per Memory Block'),
        ('Вкладка «Сброс» — когда цель бросить: статичные (стоят дольше «времени стояния»; короткая остановка не в счёт), '
         'движущиеся, после N выстрелов — каждое орудие делает по цели свои N выстрелов. Сохранение настроек возвращает '
         'сброшенные цели',
         'Drop tab: when to give up a target: static ones (standing longer than the "standing time"; a short stop does not '
         'count), moving ones, or after N shots - each gun fires its own N shots. Saving the settings restores dropped targets'),
        ('Вкладка «Выбор» — какая цель главная: ближайшая, наиболее ранняя, дальняя, крупнейшая, наименьшая, быстрая, '
         'медленная. «Переключаться на цель выше по приоритету» и правило смены цели: после N выстрелов, через время, '
         'при остановке или никогда',
         'Pick tab: which target comes first: nearest, earliest, farthest, largest, smallest, fastest or slowest. "Switch '
         'to a higher priority target" and the switching rule: after N shots, after a time, when it stops, or never'),
        ('Вкладка «Свой-чужой» — белый или чёрный список кодов транспондеров: какие транспондеры считать своими',
         'IFF tab: a whitelist or blacklist of transponder codes - which transponders count as friendly'),
        ('Вкладка «Перехват» — сбивать снаряды больших пушек и ракеты (нужен мод CBC Interception). Фильтр угрозы: '
         'угрожающие (упадут рядом со своими), приближающиеся или все чужие; «раньше долетит — раньше цель»',
         'Intercept tab: shoot down big cannon shells and rockets (needs the CBC Interception mod). Threat filter: '
         'threatening (landing near friends), approaching or all hostile; "earlier impact first"'),
        ('Вкладка «Пушки» — свои цели для отдельной пушки: какие типы и с какими уровнями она берёт, например одна — '
         'по кораблям, другая — по мобам',
         'Guns tab: targets for a single gun - which types and levels it engages, e.g. one gun for ships, another for mobs'),
        ('Вкладка «Состояние» — сводка: радары, пушки и их статус, промах и разброс ствола, цели. Кнопки «Собрать пушки» и '
         '«Разобрать пушки» — для перезарядки и перестройки',
         'Status tab: a summary of radars, guns and their status, miss and barrel spread, targets. The "Assemble cannons" '
         'and "Disassemble cannons" buttons help with reloading and rebuilding'),
        ('Shift+ПКМ — запретить или разрешить огонь. Очки инженера Create показывают ту же сводку, Display Link — '
         'источник «Цели СУО», компаратор — число целей',
         'Shift+right-click holds or allows fire. Create\'s Engineer\'s Goggles show the same summary, a Display Link has a '
         '"Fire control targets" source, a comparator reads the target count'),
    ]),
    'aim_controllers': (('Контроллеры наводки', 'Aim Controllers'), [
        ('Контроллер горизонтальной наводки ставится вплотную под станком (у подвесного — над ним). Вал подаётся в него снизу',
         'The Yaw Controller goes right below the mount (above a hanging one). Its shaft comes in from below'),
        ('Контроллер вертикальной наводки — сбоку станка, вал — с противоположной от станка стороны',
         'The Pitch Controller goes on the side of the mount, with its shaft on the far side'),
        ('Контроллер поворачивает пушку ровно на нужный угол по команде вычислителя. Скорость — как от валов CBC: '
         '0,0375° за тик на 1 об/мин',
         'The controller turns the cannon by exactly the commanded angle. Speed works like CBC shafts: 0.0375° per tick for '
         'each RPM'),
        ('256 об/мин — 9,6° за тик (192° в секунду), 64 об/мин — 2,4°. Для быстрых целей подавайте 128–256 об/мин',
         '256 RPM gives 9.6° per tick (192° per second), 64 RPM gives 2.4°. For fast targets use 128-256 RPM'),
        ('Нагрузка — 4 единицы стресса на 1 об/мин. Валы самого станка вычислитель блокирует, пока управляет им. '
         'Выходной полувал контроллера показывает угол пушки',
         'Load: 4 stress units per RPM. While the computer controls the mount, its own shafts are locked. The output '
         'half-shaft shows the cannon angle'),
        ('Без контроллера («нет контроллера наводки») или без вращения пушка не наводится и не стреляет. Очки инженера '
         'показывают скорость поворота',
         'Without a controller ("no aim controller") or rotation the cannon neither aims nor fires. Engineer\'s Goggles show '
         'the turn speed'),
        ('Предел поворота — два значка на боковой грани контроллера, по одному на направление (удерживать ПКМ — ползунок, '
         'как у торсионной пружины). Курс — влево и вправо от направления ствола при сборке, наклон — вверх и вниз от горизонта, 1–360°; '
         'правый край — без предела. Цель за пределом пушка пропускает, а если других нет — стоит у предела («вне сектора»)',
         'Rotation limit: two boxes on the side of the controller, one per direction (hold right-click for a slider like the '
         'Torsion Spring). Yaw - left and right of the barrel\'s assembled direction, pitch - up and down from the horizon, 1-360°; the right '
         'end means no limit. Targets beyond the limit are skipped; with no others the cannon waits at the limit ("out of arc")'),
    ]),
    'network': (('Радарная сеть и блок памяти', 'Radar networks and the Memory Block'), [
        ('Вычислитель, станки, радары, мониторы, камеры и части орудий соединяются только радарными линками — автоматического '
         'подключения по радиусу нет',
         'Computers, mounts, radars, monitors, cameras and gun parts connect only through Radar Links; nothing connects by '
         'range on its own'),
        ('Поставьте линк на аппаратуру. Ненастроенный линк начинает новую радарную сеть (в чате — её имя, например «#3F2A»)',
         'Place a link on a device. An untuned link starts a new radar network (its name, like "#3F2A", appears in chat)'),
        ('ПКМ линком в руке по стоящему линку — предмет настроен на его сеть и светится. Следующие линки встают в эту сеть',
         'Right-click a placed link with a link item to tune the item to its network; it glows. Next links join that network'),
        ('Вся аппаратура одной сети связана, расстояние не ограничено (в пределах измерения). Взгляд на линк подсвечивает '
         'его сеть',
         'All devices of one network are connected at any distance within a dimension. Looking at a link highlights its network'),
        ('ПКМ по линку и очки инженера — имя сети и сколько в ней вычислителей, пушек, радаров и мониторов. Аппаратура может '
         'стоять в нескольких сетях — по линку на разных гранях',
         'Right-clicking a link and Engineer\'s Goggles show the network name and how many computers, guns, radars and monitors '
         'it has. A device can join several networks with links on different faces'),
        ('В сети — 16 линков аппаратуры и ещё 16 за каждый блок памяти с питанием (240 FE/т). Блок памяти также добавляет 16 '
         'записей каждому списку вычислителя. Лишние линки пишут «сеть переполнена»',
         'A network holds 16 device links plus 16 per powered Memory Block (240 FE/t). Each Memory Block also adds 16 entries '
         'to every list of the computer. Extra links report "network full"'),
        ('Сигнал редстоуна силы 15 отключает линк; снимите сигнал — линк снова в сети',
         'A redstone signal of strength 15 disables a link; remove the signal to bring it back'),
        ('Shift+ПКМ в воздух или сетка крафта — сбросить настройку линка в руке',
         'Shift+right-click in the air or the crafting grid resets the link item'),
    ]),
    'radar_monitor': (('Радарный монитор', 'Radar Monitor'), [
        ('Мониторы ставятся стеной до 6×6 — стена складывается в один экран',
         'Place monitors as a wall up to 6x6: it forms a single screen'),
        ('Линк — на любой блок экрана. Монитор рисует карту целей и радаров своей радарной сети; монитор — в центре, вверх — '
         'направление от вас, буква «С» — север',
         'Put a link on any screen block. The monitor draws a map of targets and radars of its network: the monitor is in '
         'the center, up points away from you, "N" marks north'),
        ('На карте: свои, угрозы, мирные и неактивные цели разными цветами, цели под огнём, лучи радаров и их зоны, '
         'транспондеры, шкала дальности по осям',
         'On the map: friendly, threat, peaceful and inactive targets in different colors, targets under fire, radar beams '
         'and zones, transponders and range marks on the axes'),
        ('Shift+ПКМ — окно монитора. Вкладка «Экран» — та же карта: клик по цели назначает её, сторона карты вводится числом '
         '(16 – 1 000 000 блоков)',
         'Shift+right-click opens the monitor window. The Screen tab shows the same map: click a target to designate it, type '
         'the map size (16 - 1,000,000 blocks)'),
        ('Назначенную цель вычислители сети ведут вне очереди, в обход типов и скорости. Вкладка «Цели» — список целей с '
         'назначением и снятием',
         'Computers of the network engage a designated target first, ignoring types and speed. The Targets tab lists targets '
         'to designate or clear'),
        ('Вкладка «Вид»: фильтры показа (свои, угрозы, мирные, неактивные, радары, зоны, транспондеры, метки), значки для '
         'каждого типа — форма, цвет, размер — и подписи имён: всегда или при наведении',
         'The View tab: display filters (friendly, threats, peaceful, inactive, radars, zones, transponders, pins), icons for '
         'each type - shape, color, size - and name labels: always or on hover'),
        ('Метки — ПКМ по карте в окне или вкладка «Метки»: координаты, имя и цвет. Метки только рисуются, на огонь не влияют',
         'Pins: right-click the map in the window or use the Pins tab: coordinates, name and color. Pins are only drawn and '
         'do not affect fire'),
        ('В мире: взгляд на цель показывает сведения о ней, нажатие колеса мыши исключает цель — вычислители её не берут '
         '(повторно — вернуть)',
         'In the world: looking at a target shows its details, a middle-click excludes it from the computers (again to restore)'),
        ('Питание — 32 FE/т за каждый блок экрана, энергию можно подать в любой блок стены',
         'Power: 32 FE/t per screen block, fed into any block of the wall'),
    ]),
    'transponder': (('Транспондер «свой-чужой»', 'IFF Transponder'), [
        ('Транспондер помечает своих: вычислитель не стреляет по тому, что в его зоне, и не стреляет через неё',
         'The transponder marks friends: computers do not fire at anything in its zone or through it'),
        ('ПКМ — экран транспондера. Имя (до 32 символов) подписывает цель и отметку на мониторе. Код — 1–8 цифр: по нему '
         'вычислитель решает, свой это транспондер или нет',
         'Right-click opens the transponder screen. The name (up to 32 characters) labels the target and its monitor mark. '
         'The code (1-8 digits) tells computers whether it is friendly'),
        ('Режим «радиус» — шар заданного радиуса вокруг транспондера; на корабле зона едет вместе с ним',
         'Radius mode: a sphere of the set radius around the transponder; on a ship the zone moves with it'),
        ('Режим «сублевел» — корабль Sable или контрапшен, на котором стоит транспондер (границы + 2 блока); «сублевел и '
         'расстояние» — корабль и заданное расстояние вокруг. По умолчанию — по месту',
         'Sub-level mode: the Sable ship or contraption it stands on (bounds + 2 blocks); "sub-level and distance": the ship '
         'plus a set distance around. The default depends on where it stands'),
        ('Вкладка «Наследование» — кто в зоне защищён: игроки, враждебные и мирные мобы, корабли, контрапшены, снаряды, '
         'прочие. Списки «всегда» и «никогда» — как списки целей вычислителя',
         'The Inheritance tab sets who in the zone is protected: players, hostile and peaceful mobs, ships, contraptions, '
         'projectiles, others. "Always" and "never" lists work like the computer target lists'),
        ('У вычислителя на вкладке «Свой-чужой» — белый или чёрный список кодов: какие транспондеры свои',
         'The computer IFF tab holds a whitelist or blacklist of codes - which transponders are friendly'),
        ('Коды видят и меняют только владелец, его команда и операторы. Питание — 64 FE/т; без энергии транспондер не защищает',
         'Only the owner, their team and operators see and change codes. Power: 64 FE/t; without it the transponder protects nothing'),
    ]),
    'camera': (('Камеры и монитор камер', 'Cameras and the Camera Monitor'), [
        ('Камера ставится на стену, потолок или пол. Голова поворачивается туда, куда через неё смотрят, и остаётся в '
         'последнем положении',
         'A camera goes on a wall, ceiling or floor. Its head turns where you look through it and keeps its last position'),
        ('Подключение — радарным линком или без него: ПКМ предметом камеры по радарному линку (или по камере в сети) '
         'настраивает предмет на сеть. Shift+ПКМ в воздух — сброс',
         'Connect it with a Radar Link or without one: right-click a Radar Link (or a networked camera) with the camera item '
         'to tune it. Shift+right-click in the air resets it'),
        ('Монитор камер (ПКМ) — список камер его сети. Имя камеры можно поменять (Enter), «Смотреть» — вид из центра камеры',
         'The Camera Monitor (right-click) lists the cameras of its network. Rename a camera with Enter; "View" looks from '
         'the camera center'),
        ('Пока вы смотрите, автоприцеливание орудий сети на паузе: они наводятся туда, куда смотрит камера, до 384 блоков, '
         'с упреждением по движущимся кораблям и сущностям',
         'While you look, auto aim of the network guns pauses: they aim where the camera looks, up to 384 blocks, leading '
         'moving ships and entities'),
        ('ЛКМ — огонь всеми управляемыми орудиями, как редстоун 15 (зажатая — непрерывно). Shift — выход',
         'Left-click fires all controlled guns like redstone 15 (hold for continuous fire). Shift exits'),
        ('Кнопка «Орудия» в строке камеры — какими орудиями она управляет. Остальные в это время ведут цели сами или '
         'слушаются другой камеры. Ничего не отмечено — все орудия',
         'The "Guns" button in a camera row chooses which guns it controls. The others keep tracking targets or follow '
         'another camera. Nothing checked means all guns'),
        ('Если смотреть на само управляемое орудие (или с камеры, стоящей на нём, — на его же части), оно не поворачивается; '
         'ЛКМ по-прежнему стреляет',
         'Looking at a controlled gun itself (or, from a camera mounted on it, at its own parts) keeps it still; left-click '
         'still fires'),
        ('Питание монитора — 64 FE/т за каждую камеру сети. Отошли от монитора или вышли — орудия сразу возвращаются к '
         'автоприцеливанию',
         'The monitor uses 64 FE/t per camera in its network. Walk away or exit and the guns go back to auto aim at once'),
    ]),
    'swivel_turret': (('Своё орудие на подшипниках-шарнирах', 'A custom gun on Swivel Bearings'), [
        ('Своё орудие на Sable строится на подшипниках-шарнирах Create Aeronautics. Курс — подшипник выходом вверх',
         'A custom Sable gun is built on Create Aeronautics Swivel Bearings. Yaw: a bearing facing up'),
        ('Подшипник крутится через свою шестерню. Вращение подаёт привод орудия в режиме «курс» — через шестерни. Скорость и '
         'направление выхода привода задаёт вычислитель',
         'The bearing is driven through its cog. A Gun Drive in yaw mode feeds it through cogwheels; the computer sets the '
         'drive output speed and direction'),
        ('Поворотную часть выделите суперклеем. Подшипник собирает её в постройку Sable, когда на шестерню подано вращение',
         'Select the rotating part with Super Glue. The bearing assembles it into a Sable structure once its cog gets rotation'),
        ('На поворотной части — второй подшипник выходом вбок: это наклон. Его поворотную часть (ствол) тоже выделите клеем; '
         'сначала собирайте внутренний подшипник',
         'On the rotating part goes a second bearing facing sideways for pitch. Glue its rotating part (the barrel) too; '
         'assemble the inner bearing first'),
        ('Подшипник наклона крутит второй привод: ПКМ пустой рукой — режим «наклон». Вращение на поворотную часть можно '
         'провести валом сквозь подшипник курса',
         'A second drive turns the pitch bearing: right-click it with an empty hand for pitch mode. Rotation can reach the '
         'rotating part through a shaft passing through the yaw bearing'),
        ('Ствол — любые блоки. Прицел ставится объективом вдоль ствола: вычислитель наводит направление прицела, поэтому '
         'форма постройки не важна',
         'The barrel can be any blocks. Place the Sight facing along the barrel: the computer aims the sight direction, so '
         'the shape does not matter'),
        ('Орудийные линки на приводах и прицеле объединяют их в одно орудие (ненастроенный линк — новое орудие, ПКМ им по '
         'стоящему — настроить предмет)',
         'Gun Links on the drives and the sight join them into one gun (an untuned link starts a new gun, right-click a '
         'placed one to tune the item)'),
        ('Радарный линк — на любую часть орудия: всё орудие подключается к вычислителю одним линком. Своя постройка '
         'орудия — не цель и не помеха огню',
         'Put a Radar Link on any part of the gun: one link connects the whole gun. The gun\'s own structure is neither a '
         'target nor an obstacle'),
        ('Передаточное число и направление приводов вычислитель выучивает сам по тому, как поворачивается прицел; если '
         'наводка поначалу «гуляет», через несколько секунд она успокаивается',
         'The computer learns the drive ratio and direction from how the sight turns; if aiming wobbles at first, it '
         'settles within seconds'),
        ('Когда пора стрелять, прицел выдаёт сигнал редстоуна 15 — подведите его к своему оружию. Станки CBC в орудии '
         'стреляют сами по команде',
         'When it is time to fire, the sight emits redstone 15 - wire it to your weapon. CBC mounts in the gun fire on '
         'command by themselves'),
        ('Shift+ПКМ по орудийному линку — настройки орудия. Для своего оружия выберите тип: автопушка, среднее, большая '
         '(баллистика и вид сигнала) или «кастом» — наводится прямо на цель, без упреждения',
         'Shift+right-click a Gun Link for gun settings. For a custom weapon pick its type: autocannon, medium, big '
         '(ballistics and signal kind) or "custom" - aims straight at the target without lead'),
    ]),
    'gun_rotator': (('Вращатель орудия', 'Gun Rotator'), [
        ('Вращатель орудия (нужен Sable) — свой подшипник мода: поворачивает постройку перед выходом. Вращение — валом сзади, '
         'скорость поворота — как у контроллеров наводки',
         'The Gun Rotator (needs Sable) is the mod\'s own bearing: it turns the structure in front of its output. Feed '
         'rotation from behind; turn speed works like aim controllers'),
        ('ПКМ пустой рукой — собрать (по правилам клея Create), повторно — показать угол. Схема: горизонтальный вращатель, '
         'на его поворотной части — вертикальный. Сначала собирайте внутренний',
         'Right-click with an empty hand to assemble (Create glue rules), again to show the angle. Layout: a horizontal '
         'rotator carrying a vertical one. Assemble the inner one first'),
        ('Прицел ставится объективом вдоль ствола: вычислитель наводит направление прицела',
         'Place the Sight facing along the barrel: the computer aims the sight direction'),
        ('Орудийные линки объединяют прицел и вращатели в одно орудие, радарный линк на любой части подключает его к вычислителю',
         'Gun Links join the sight and rotators into one gun; a Radar Link on any part connects it to the computer'),
        ('Вращатель держит угол сам. С Create Aeronautics можно строить и на подшипниках-шарнирах с приводами орудия — '
         'см. сцену «Своё орудие на подшипниках-шарнирах»',
         'The rotator holds its angle itself. With Create Aeronautics you can also build on Swivel Bearings with Gun Drives - '
         'see the "custom gun on Swivel Bearings" scene'),
    ]),
    'gun_drive': (('Привод орудия и настройки орудия', 'Gun Drive and gun settings'), [
        ('Привод орудия — как коробка передач: вход сзади, выход спереди (от −100 % до 100 % входа). Выход задаёт вычислитель. '
         'Здесь привод крутит вал поворота станка вместо контроллера наводки',
         'The Gun Drive works like a gearbox: input behind, output in front (-100% to 100% of input). The computer sets the '
         'output. Here it turns the yaw shaft of the mount instead of an aim controller'),
        ('ПКМ пустой рукой — режим привода: курс (↔) или наклон (↕)',
         'Right-click with an empty hand to switch the drive between yaw (↔) and pitch (↕)'),
        ('Выход может крутить подшипник, шестерни или вал станка. Передаточное число и направление вычислитель выучивает сам',
         'The output can drive a bearing, gears or a mount shaft. The computer learns the ratio and direction by itself'),
        ('Предел поворота — два значка на боковой грани привода, по одному на направление (удерживать ПКМ — ползунок), 1–360°, правый '
         'край — без предела. Курс отсчитывается от направления орудия в момент, когда предел включили, наклон — от '
         'горизонта. Через запретный сектор орудие не поворачивает — идёт в обход',
         'Rotation limit: two boxes on the side of the drive, one per direction (hold right-click for a slider), 1-360°, the right end '
         'means no limit. Yaw counts from where the gun pointed when the limit was enabled, pitch from the horizon. The gun '
         'never turns through the forbidden arc - it goes the other way round'),
        ('Приводы, станки и прицелы связываются орудийными линками в одно орудие',
         'Gun Links join drives, mounts and sights into one gun'),
        ('Shift+ПКМ по орудийному линку — настройки орудия (общие для всех его линков). Имя — видно в отчёте вычислителя и в '
         'мониторе камер',
         'Shift+right-click a Gun Link for gun settings (shared by all its links). The name shows in the computer report and '
         'the camera monitor'),
        ('Тип задаёт баллистику и сигнал орудия без станка CBC: автопушка — сигнал держится, пока орудие на цели; среднее и '
         'большая — импульсами; кастом — наводится прямо на цель, без упреждения. Со станком CBC баллистика — по станку',
         'The type sets ballistics and the signal for guns without a CBC mount: autocannon - the signal holds while on target; '
         'medium and big - pulses; custom - aims straight at the target without lead. With a CBC mount, ballistics come from it'),
        ('«Автоприцеливание» выключено — орудие наводится только с камеры. «Стрельба» выключена — орудие наводится, но само не '
         'стреляет (ЛКМ с камеры работает)',
         'Auto aim off: the gun aims only from a camera. Firing off: the gun aims but never fires by itself (camera '
         'left-click still works)'),
        ('На линке, стоящем на станке CBC, есть «Основная пушка»: её ствол наводится, если в орудии нет прицела, и по ней '
         'считается баллистика',
         'A link on a CBC mount also has "Main cannon": its barrel is aimed when the gun has no sight, and its ballistics are used'),
        ('Станок CBC в орудии стреляет по команде вычислителя, его валы вычислитель не блокирует. Радарный линк — на любую '
         'часть орудия',
         'The CBC mount of the gun fires on command; the computer does not lock its shafts. Put a Radar Link on any part of the gun'),
    ]),
}


def say_counts():
    src = open(SCENES_JAVA, encoding='utf-8').read()
    counts = {}
    for m in re.finditer(r's\.title\("(\w+)"', src):
        name = m.group(1)
        start = m.end()
        end = src.find('markAsFinished', start)
        counts[name] = len(re.findall(r'\bsay(?:At)?\(s,', src[start:end]))
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
            data[f'radar_fcs.ponder.{name}.header'] = header[i].replace('%', '%%')
            for n, t in enumerate(texts, 1):
                # Ponder пропускает текст через String.format: одиночный % давал «Format error»
                data[f'radar_fcs.ponder.{name}.text_{n}'] = t[i].replace('%', '%%')
        with open(path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write('\n')
        print(f'{path}: {sum(len(t) + 1 for _, t in SCENES.values()) + 2 * len(TAG)} ponder keys')


if __name__ == '__main__':
    main()
