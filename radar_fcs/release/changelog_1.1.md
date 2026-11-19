# 1.1 — changes since 1.0 Release

## English
- **New: Autopilot Bridge** (only with **Create: Flight Control** 0.8.3+, crafted from its parts). Flies a Flight Control ship to the radar network's target: the one designated on a monitor, otherwise the fire control computer's target, otherwise the nearest hostile radar contact.
- Works like the Flight Control Navigation Terminal: movement through a **Velocity Coupler**, turning through an **Angular Velocity Coupler** linked to the same Flight Control Computer. While flying to the target the bridge switches off the Attitude and Altitude Stabilizers, the Distance Coupler and the Flight Control Terminal; with no target or when paused, control returns to them.
- Tracking deflections toggled separately: **yaw**, **roll** (bank into the turn) and **pitch** (nose on the target elevation), with typed roll and pitch limits. Ship nose taken from the Forward Indicator.
- Modes **"turn only"**, **"distance only"** (keeps the altitude at which the target was acquired) and **"distance and altitude"** with typed distance, height above the target and speed limit.
- Linked with the Flight Control Tool Gun; redstone pauses it; Engineer's Goggles show the state, the target and what is missing.
- Ponder scene for the bridge.
- Reworked English and Russian texts: Flight Control block names as in that mod, "ships" instead of "sublevels", consistent terms and wording. Russian block names: «Передатчик радарной сети» / «Передатчик орудийной сети» (was «линк»), «Механизм горизонтальной/вертикальной наводки» (was «контроллер»).
- Redrawn 32x32 textures in the Create style with smooth gradients and a richer palette; the camera is an Eye of Ender enclosed in an obsidian-and-brass housing (only the pupil shows), and its recipe uses an Eye of Ender.
- Minor optimizations: fewer block updates from the bridge, cached ship lookup.

## Русский
- **Новое: мост автопилота** (только с **Create: Flight Control** 0.8.3+, крафт из его деталей). Ведёт корабль Flight Control к цели радарной сети: назначенной на мониторе, иначе цели вычислителя, иначе ближайшей чужой цели радаров.
- Работает как навигационный терминал Flight Control: движение — через **регулятор скорости**, повороты — через **регулятор угловой скорости**, связанные с тем же компьютером управления полётом. На время полёта к цели мост выключает стабилизаторы ориентации и высоты, регулятор дистанции и терминал управления полётом; нет цели или пауза — управление возвращается к ним.
- Отклонения для ведения цели включаются по отдельности: **рыскание**, **крен** (в сторону поворота) и **тангаж** (нос на угол места цели), с вписываемыми пределами крена и тангажа. Нос корабля — по индикатору направления вперёд.
- Режимы **«только поворот»**, **«только дальность»** (держит высоту, на которой взята цель) и **«дальность и высота»** с вписываемыми дальностью, высотой над целью и пределом скорости.
- Связь — инструментальным пистолетом Flight Control; редстоун — пауза; очки инженера показывают состояние, цель и чего не хватает.
- Обучающая сцена Ponder для моста.
- Переписаны тексты на русском и английском: названия блоков Flight Control — как в самом моде, «корабли» вместо «сублевелов», единые термины и формулировки. Новые русские названия блоков: «Передатчик радарной сети» и «Передатчик орудийной сети» (были «линки»), «Механизм горизонтальной/вертикальной наводки» (были «контроллеры»).
- Перерисованы текстуры 32x32 в стиле Create — с плавными градиентами и более богатой палитрой; камера — око Эндера в корпусе из обсидиана и латуни (снаружи виден только зрачок), око Эндера входит в её рецепт.
- Мелкие оптимизации: меньше обновлений блока у моста, кэш поиска корабля.
