# 1.1 — changes since 1.0 Release

## English
**Added**
- **Autopilot Bridge** (with Create: Flight Control): flies a ship to the network's target; yaw/roll/pitch tracking, distance and altitude modes; with no target it hovers or orbits a point or an **Orbit Beacon**.
- **Docking Controller** (with Flight Control + Create Aeronautics): automatic docking to numbered ports, up to 8 connectors; engines off and guns inactive while docked; redstone locks a port.
- **Gun Bearing** (with Create Aeronautics): a swivel bearing that damps inertia — no overshoot or sway.
- **Laser Target Designator**: designate (LMB) or exclude (mouse wheel click) targets from afar for a radar monitor.
- Camera: with the cursor in the air the guns point along your view.
- Ponder scenes for all new blocks; redrawn 32x32 textures; reworked English and Russian texts.

**Removed**
- **Gun Rotator** — assembled guns are turned by Gun Drives only. Remove rotators from your builds before updating.

**Fixed**
- "Drop static targets" no longer drops ships that hover or keep distance.
- Friend or foe for ships — by transponders only; a gun never targets the ship it stands on.
- Assembled guns with a Gun Sight aim only by the sight and no longer jerk; cannon mounts are not locked.
- Gun Drive mode switches with Shift+RMB (a plain RMB no longer changes it).
- Target info on the radar monitor is shown above the target.
- A one-off Sable error no longer disables ship support until restart.
- Optimizations: fewer block updates and lookups from the bridge and docking.

## Русский
**Добавлено**
- **Мост автопилота** (с Create: Flight Control): ведёт корабль к цели сети; рыскание/крен/тангаж, режимы дальности и высоты; без цели — висит или летает по орбите вокруг точки или **маяка орбиты**.
- **Стыковочный контроллер** (с Flight Control + Create Aeronautics): автостыковка к портам по номеру, до 8 портов; пристыкованный корабль — двигатели выключены, пушки неактивны; редстоун блокирует порт.
- **Подшипник орудия** (с Create Aeronautics): поворотный подшипник, гасящий инерцию, — без перелёта и раскачки.
- **Лазерный целеуказатель**: назначение (ЛКМ) и исключение (нажатие колеса) целей издалека для монитора радара.
- Камера: курсор в воздухе — орудия смотрят по направлению взгляда.
- Сцены Ponder для всех новых блоков; перерисованные текстуры 32x32; переписаны тексты на русском и английском.

**Убрано**
- **Вращатель орудия** — сборные орудия поворачивают только приводы. Уберите вращатели из построек до обновления.

**Исправлено**
- «Сбрасывать статичные» больше не отсеивает висящие и держащие дистанцию корабли.
- Свой-чужой для кораблей — только по транспондеру; пушка никогда не стреляет по кораблю, на котором стоит.
- Сборное орудие с прицелом наводится только по прицелу и не дёргается; станки не блокируются.
- Режим привода орудия — Shift+ПКМ (простой ПКМ его больше не меняет).
- Сведения о цели на мониторе радара — над целью.
- Разовая ошибка Sable больше не выключает поддержку кораблей до перезапуска.
- Оптимизации: меньше обновлений блоков и поисков у моста и стыковки.
