# CLAUDE.md — Create: Radar Fire Control (radar_fcs)

Контекст для Claude Code. Отвечай пользователю на русском.

## 1. Цель
Аддон для **Create** + **Create Big Cannons** (NeoForge 1.21.1): свои радары, монитор, автоматическое наведение и огонь.
Create: Radars — необязательная интеграция (по просьбе пользователя заменён своими радарами; его код не копировать,
лицензия source-available). Требования пользователя (выполнены, см. игровые тесты):
1. Plug & play: станки CBC и радары находятся сами (радиус `linkRadius`).
2. Свой-чужой по транспондеру (база, корабль Sable, контрапшен), каналы — цвета красителей.
3. Неабсолютная точность: допуск `max(aimTolerance, угловой размер цели)` + мёртвая зона `deadband`.
4. Быстрое наведение (`slewDegPerTick`, по умолчанию 6°/тик).
5. Неподвижные и медленные цели (`minSpeed`) неактивны.
6. Приоритет ближайшей к каждой пушке цели (с гистерезисом `targetSwitchRatio`).
7. Отображение: свечение, частицы, отчёт, очки инженера Create, Display Link, компаратор.
8. Цели: мобы, игроки (опция), контрапшены Create, корабли Sable / Create Aeronautics.
   Пушки: CBC и аддоны на станке CBC (CBC Advanced Technologies и др.).
9. Динамическое упреждение: скорость + ускорение цели, обучение баллистики по выстрелам.
10. Свои радары вместо Create Radars: радарный подшипник с антенной-контрапшеном (мультиблок), фазированная решётка
    (мультиблок), купол; сеть по каналам; монитор (мультиблок) с назначением цели кликом.

## 2. Окружение и сборка
| Что | Значение |
|---|---|
| Minecraft / NeoForge | 1.21.1 / 21.1.228 (все зависимости требуют ≥ 21.1.228) |
| Java / Gradle / MDG | 21 / 8.14.5 / ModDevGradle 2.0.141 |
| Зависимости | Modrinth Maven, `compileOnly` без транзитивных (см. `gradle.properties`) |

Мод живёт в подпапке `radar_fcs/` репозитория (корень репозитория — форк ModDevGradle, не трогать).

```bash
./gradlew build                     # jar: build/libs/radar_fcs-<ver>.jar
./gradlew coreTest                  # офлайн-тест ядра (devtools/SolverTest.java)
./gradlew runGameTestServer         # игровые тесты (моды качаются в run/mods)
./gradlew runGameTestServer -PfcsMinimal    # только Create + CBC (+ companion), без Create Radars и Sable
./gradlew runGameTestServer -PfcsNoRadars   # без Create Radars
./gradlew runGameTestServer -Pcbc_version=bsGaXKEd -Pradars_runtime_version=0.4.9.4-1.21.1   # CBC 5.11.3 + CR 0.4.9.4
./gradlew runServer -PfcsAutostop   # сгенерировать мир run/world и остановиться
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -PfcsScreenshot   # скриншоты (нужен run/saves/fcs_test)
```
Компиляция идёт против Create Radars 0.4.9.4 (минимальное API). Sable companion 1.6.0 и Flywheel извлекаются при конфигурации
из jar-in-jar Sable и Create в `build/embedded/`; companion встраивается в наш jar (jarJar), Flywheel — только для компиляции.
Скриншоты: каждый запуск `-PfcsScreenshot` уходит на 640 блоков от прошлой сцены (`-PfcsWorld=<сохранение>` — другой мир).
У CBC на Modrinth Forge и NeoForge сборки имеют один номер версии: для NeoForge-сборки старых версий указывай ID версии
(5.11.3 = `bsGaXKEd`).

## 3. Архитектура (`src/main/java/dev/radarfcs/`)
- `core/` — чистая Java, без Minecraft:
  - `FlightModel`, `CbcFlightModel` — интегратор CBC: `a = -v̂·min(k|v|^n,|v|) + g; pos += v + a/2; v += a`, фаза тяги ракет;
  - `FlightCorrection` — поправки полёта по наблюдённым снарядам (скорость, ускорения по тикам);
  - `BallisticSolver` — бисекция угла (настильная/навесная), итеративное упреждение, смещение дульного среза;
  - `Track`, `TrackTable` — взвешенный МНК по окну (позиция, скорость, ускорение), гистерезис подвижности;
  - `TargetMotion`, `Aim`, `Vec`.
- `compat/` — вся связь с чужими модами:
  - `CbcMount` — станок CBC (прямые вызовы): блокировка валов `setSequencedAngleLimit(0)`, углы, сборка и огонь
    через `onRedstoneUpdate`, фактическое направление ствола `applyRotation(нормаль, partialTicks)`;
  - `CbcBallistics` — априорная баллистика: заряды большой пушки, материал/стволы автопушек, свойства снаряда (рефлексия);
  - `ShotObserver` — ловит вылетевший снаряд (EntityJoinLevelEvent) и пишет его скорость по тикам;
  - `RadarLink` — Create Radars (необязательно, за `Mods.CREATE_RADARS`): `IRadar`/`RadarTrack` (общее API 0.4.9.x и 5.x);
  - `SableCompat` — Sable companion: корабли в области, плот→мир, скорость (Sable отдаёт бл/с!);
  - `ContraptionCompat`, `FcsDisplaySource`, `Reflect`.
- `radar/` — радары мода: `RadarScanner` (общая модель обнаружения: дальность x размер цели, зона `Coverage`,
  прямая видимость с кэшем), `ContactBook`/`RadarContact` (отметки с затуханием и оценкой скорости),
  `RadarNetwork` (радары по измерению и каналу, назначение цели с монитора, цели под огнём), `RadarEmitter`, `RadarInfo`.
- `block/` радаров: `RadarBearingBlockEntity` (наследник `MechanicalBearingBlockEntity`: антенна - контрапшен Create,
  облучатель задаёт луч, развёртка - по развёрнутому азимуту между обзорами), `AntennaBlock` (сцепляется с антенной через
  `canStickTo`, поршни не тянет), `PhasedArrayBlockEntity` (KineticBlockEntity, BFS панелей в плоскости, нагрузка
  `calculateStressApplied`), `RadarDomeBlockEntity`, `RadarMonitorBlockEntity` (ведущий - нижний левый блок стены;
  отбор отметок как у вычислителя; `screenPointFor` - точка экрана для теста клика), `client/RadarMonitorRenderer`.
- `fcs/` — логика вычислителя: `TargetSensor` (радар/свой сенсор, свой-чужой), `FcsControl` (наведение и огонь),
  `FireSafety` (свои на траектории, блоки), `MountState`, `MountClaims`, `Status`.
- `block/`, `iff/IffRegistry`, `registry/`, `client/FcsClient` (цвет канала транспондера).
- `src/gametest/` — игровые тесты и dev-автоматизация (в jar не попадают).

## 4. Проверенные факты о зависимостях
- CBC: `cannonYaw` = yaw Minecraft, `cannonPitch > 0` — вверх для всех сторон. **Направление выстрела CBC берёт
  по положению ствола прошлого тика** (`toGlobalVector(..., 0)`), поэтому огонь разрешается, только когда в допуске
  и текущее, и прошлое положение ствола. Без этого первый выстрел после быстрого доворота уходил на 7°.
- CBC: блоки пушки должны быть соединены (`cannonBehavior().setConnectedFace`), иначе «нет открытого конца».
- CBC: большая пушка стреляет по фронту сигнала, скорость снаряда = сумма силы зарядов; автопушки — мощность сигнала = темп.
- Create Radars: плоский радар сканирует в сторону, **противоположную** FACING. Треки радара — позиции ног сущности;
  вычислитель уточняет их по живой сущности.
- **CBC-AT 0.1.4c несовместима с CBC 5.11.4+** (`NoSuchMethodError onRecoil`): ракетные установки и др. падают при выстреле
  независимо от нашего мода. Работает с CBC ≤ 5.11.3.
- Sable: `SubLevelAssemblyHelper.assembleBlocks` + `RigidBodyHandle.addLinearAndAngularVelocity` — для тестов
  (`SableTestSupport`: отдельный класс, чтобы тесты грузились без Sable).
- Create: подшипник сам собирает контрапшен при появлении скорости (`onSpeedChanged` -> `assembleNextTick`).
  Контрапшен берёт соседние блоки, если `state.canStickTo(other) && other.canStickTo(state)` (NeoForge) — так антенна
  собирается без клея. Верх подшипника рисует `BearingVisual` (Flywheel), без Flywheel — `BearingRenderer`.
- Рендер монитора: квады (`RenderType.debugQuads`) рисовать до текста — `font.drawInBatch` берёт другой тип рендера,
  и ранее полученный `VertexConsumer` становится недействительным (падение «Not building!»).

## 5. Статус проверки
| Часть | Статус |
|---|---|
| core | `coreTest`: 100 % промахов < 1 блока (пушки, автопушки, квадр. сопротивление); ракеты 0 % → 100 % после обучения |
| Игровые тесты (13) | все проходят: большая пушка, автопушка, неподвижная цель, транспондер, радар CR, ракеты CBC-AT, корабль Sable, корабль со своим транспондером; купол, радарный подшипник (антенна без клея, попадание), решётка (сектор, укрытие за стеной, нагрузка), монитор 2x2 (отбор отметок, клик - назначение, переключение вычислителя), каналы |
| Комбинации | CBC 5.11.7 + CR 5.0.1; CBC 5.11.3 + CR 0.4.9.4 (+ CBC-AT); только Create + CBC (`-PfcsMinimal`) |
| Чистый сервер | собранный jar + Create + CBC + RPL на NeoForge-сервере из установщика: загрузка, companion из jar-in-jar |
| Клиент | запущен под Xvfb: модели, текстуры, монитор, вращение антенны, очки инженера (скриншоты в `docs/`) |

## 6. Что можно улучшить
- Пушки на кораблях Sable (батарея на корабле): поддержано через перевод направлений в плот-координаты,
  но не проверено игровым тестом; наследует ли снаряд CBC скорость корабля — не проверено (`inheritPlatformVelocity`).
- Радары на кораблях Sable: позиции и направления переводятся в мир, но игровым тестом не проверено.
- Монитор на корабле рисует картинку в осях блока (без учёта поворота корабля).
- Распределение целей между пушками (сейчас каждая берёт ближайшую к себе).
- Lua-версия (`lua/`) — исходный прототип для CC:Tweaked, в моде не используется.
