# CLAUDE.md — Create Radars: Fire Control (radar_fcs)

Контекст для Claude Code. Отвечай пользователю на русском.

## 1. Цель
Аддон для **Create: Radars** + **Create Big Cannons** (NeoForge 1.21.1): автоматическое наведение и огонь.
Требования пользователя (выполнены, см. игровые тесты):
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
./gradlew runGameTestServer -Pcbc_version=bsGaXKEd -Pradars_runtime_version=0.4.9.4-1.21.1   # CBC 5.11.3 + CR 0.4.9.4
./gradlew runServer -PfcsAutostop   # сгенерировать мир run/world и остановиться
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -PfcsScreenshot   # скриншоты (нужен run/saves/fcs_test)
```
Компиляция идёт против Create Radars 0.4.9.4 (минимальное API), Sable companion берётся из jar-in-jar Create Radars.
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
  - `RadarLink` — Create Radars: `IRadar`/`RadarTrack` (общее API 0.4.9.x и 5.x), `IDManager` (опция);
  - `SableCompat` — Sable companion: корабли в области, плот→мир, скорость (Sable отдаёт бл/с!);
  - `ContraptionCompat`, `FcsDisplaySource`, `Reflect`.
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
- Sable: `SubLevelAssemblyHelper.assembleBlocks` + `RigidBodyHandle.addLinearAndAngularVelocity` — для тестов.

## 5. Статус проверки
| Часть | Статус |
|---|---|
| core | `coreTest`: 100 % промахов < 1 блока (пушки, автопушки, квадр. сопротивление); ракеты 0 % → 100 % после обучения |
| Игровые тесты (8) | все проходят: большая пушка, автопушка, неподвижная цель, транспондер, радар CR, ракеты CBC-AT, корабль Sable, корабль со своим транспондером |
| Комбинации | CBC 5.11.7 + CR 5.0.1; CBC 5.11.3 + CR 0.4.9.4 (+ CBC-AT) |
| Клиент | запущен под Xvfb: модели, текстуры, подкраска, очки инженера — без ошибок (скриншоты в `docs/`) |

## 6. Что можно улучшить
- Пушки на кораблях Sable (батарея на корабле): поддержано через перевод направлений в плот-координаты,
  но не проверено игровым тестом; наследует ли снаряд CBC скорость корабля — не проверено (`inheritPlatformVelocity`).
- Монитор Create Radars: показ только активных чужих целей (API монитора есть только в CR 5.x).
- Распределение целей между пушками (сейчас каждая берёт ближайшую к себе).
- Lua-версия (`lua/`) — исходный прототип для CC:Tweaked, в моде не используется.
