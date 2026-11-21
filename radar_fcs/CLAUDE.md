# CLAUDE.md — Create: Radar Fire Control (radar_fcs)

**Это мод для видеоигры Minecraft** (Java Edition 1.21.1, NeoForge) — аддон к модам Create, Create Big Cannons (CBC)
и Create: Radars. Весь код работает только внутри Minecraft: «пушки», «снаряды», «цели», «радары», «транспондеры»
и «свой-чужой» — это игровые блоки, сущности и механики из этих модов, а проверяются они игровыми тестами
(`runGameTestServer`) в тестовом мире. К реальной технике и реальному оружию проект отношения не имеет.

Контекст для Claude Code. Отвечай пользователю на русском.
**Всегда давай ссылку на скачивание jar** после сборки (jar лежит в `radar_fcs/release/`, ветка запушена):
`https://github.com/Iksanbro/Radar-gradle/raw/<ветка>/radar_fcs/release/radar_fcs-<версия>.jar`.

## 1. Что делает мод
Игровые блоки для автоматизации пушек CBC в Minecraft: вычислитель (блок с экраном настроек), контроллеры поворота
станка, радарный линк (связывает блоки в сеть как складские передатчики Create), монитор радара (мультиблок-карта),
транспондер (метит «своих» игроков, мобов и корабли), блок памяти, камера, мост автопилота для Create: Flight Control,
стыковочный контроллер (автостыковка кораблей к портам Create Aeronautics через мост), маяк орбиты.
Цели берутся только с радаров Create: Radars (его код не копировать — лицензия source-available).

Подробности:
- `docs/dev/features.md` — все требования пользователя и история функций по версиям (≈560 строк);
- `docs/dev/dependencies.md` — проверенные особенности API CBC, CBC-AT, Neo Warfare, Create Radars, Sable;
- `docs/dev/test-status.md` — что покрыто игровыми тестами и какие комбинации модов проверены;
- `docs/dev/task-docking.md` — задача стыковочного контроллера (сделано в 1.1-Test/1.2-Test, итог — `features.md` п. 43-52).

Эти файлы и `README.md` большие: **не выводи их целиком** (`cat`), ищи нужное через `grep -n` и читай куски.

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
./gradlew runGameTestServer -PfcsMinimal    # только обязательные Create + CBC + Create Radars (без Sable и CBC-AT)
./gradlew runGameTestServer -PfcsTests=camera,infra   # только тесты с этими подстроками в имени (класс.метод)
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
  - `BallisticSolver` — бисекция угла (настильная/навесная), упреждение методом секущих, потиковый перехват
    (`discrete`: точка прицеливания — цель на конец тика, в котором снаряд её проходит), `miss` — промах выстрела
    из заданного направления (потиково, как проверяет Minecraft), смещение дульного среза;
  - `Track`, `TrackTable` — взвешенный МНК по окну (позиция, скорость, ускорение), невязка `residual`: при манёвре
    окно укорачивается (до 6 тиков) и огонь запрещён; гистерезис подвижности;
  - `EngageSettings` (настройки вычислителя), `TargetPicker` (порядок целей одной пушки: уровень типа -> режим
    приоритета, удержание текущей, переключение по приоритету и по правилу, обход оставленных целей по кругу);
  - `TargetMotion`, `Aim`, `Vec`.
- `compat/` — вся связь с чужими модами:
  - `CbcMount` — станок CBC (прямые вызовы): блокировка валов `setSequencedAngleLimit(0)`, углы, сборка и огонь
    через `onRedstoneUpdate`, фактическое направление ствола `applyRotation(нормаль, partialTicks)`;
  - `CbcBallistics` — априорная баллистика: заряды большой пушки, материал/стволы автопушек, свойства снаряда (рефлексия);
  - `ShotObserver` — ловит вылетевший снаряд (EntityJoinLevelEvent) и пишет его скорость по тикам;
  - `RadarLink` — Create Radars: `IRadar`/`RadarTrack` (общее API 0.4.9.x и 5.x);
  - `SableCompat` — Sable companion: корабли в области, плот→мир, скорость (Sable отдаёт бл/с!);
  - `ContraptionCompat`, `FcsDisplaySource`, `Reflect`.
- `block/AimControllerBlock(Entity)` — контроллеры наводки (`DirectionalKineticBlock`, FACING - на станок, вал только
  сзади, к станку кинетической связи нет): `degPerTick()` = |convertToAngular(rpm)| x 0.125, `find(level, mount, pitch)`;
  `client/AimControllerRenderer` - входной полувал крутится с сетью, выходной повёрнут на угол станка.
- `block/RadarMonitorBlockEntity` (ведущий - нижний левый блок стены; свой `TargetSensor` по радарам сети любого
  блока стены, все типы целей (`SHOW_ALL`); отметки `Blip` с id, именем, скоростью и флагами свой/неактивная/угроза,
  фильтры показа `Show`; назначение цели - `localDesignation` (держится, пока цель сопровождается), отметки «под огнём» -
  `markEngaged` от вычислителей сети; `screenPointFor` - точка экрана для теста клика; метки транспондеров `Mark`,
  значки `MonitorIcons`, режим подписей), `client/RadarMonitorRenderer` (рамка на всю стену, значки `shape`, цвета
  `iconColor`/`blipColor`, подписи, курсор и сведения о цели под взглядом; карта - `client/MapCanvas`, сетка `drawGrid`,
  радары/зоны/лучи `drawRadars`), `client/RadarMonitorScreen` (вкладки Экран - карта с кликом, Цели - список, Вид - фильтры и значки).
- `FcsDev.radarless` - только для игровых тестов баллистики: цели без радара (иначе - только радары CR на линках).
- `fcs/` — логика вычислителя: `TargetSensor` (радар/свой сенсор, свой-чужой, фильтр по настройкам), `FcsControl`
  (наведение, огонь, режимы огня: выстрелы считаются по снарядам у дульного среза через `ShotObserver.count`),
  `FireSafety` (свои на траектории, блоки), `MountState`, `MountClaims`, `Status`, `SettingsNbt`. Корабли для вычислителя -
  свой-чужой только по транспондеру; пушка не стреляет по кораблю, на котором стоит (`MountState.ownShips`,
  `FcsControl.onOwnShip`); статичные корабли не сбрасываются (`EngageSettings.motionAllowed`).
- `net/FcsSettingsPayload` — настройки с экрана на сервер (проверка расстояния, `sanitize`); на клиент настройки идут
  в теге обновления блока; `net/FcsCommandPayload` (собрать/разобрать), `net/MonitorCommandPayload` (назначить, снять,
  масштаб, фильтры). `client/FcsSettingsScreen` — экран (вкладки Огонь, Цели, Списки, Сброс, Выбор, Состояние),
  `client/RadarMonitorScreen` — окно монитора,
  `client/FcsClientHooks` — открытие экрана из `FireControlBlock` (только на клиенте).
- `link/` — радарный линк: `LinkKind` (вид аппаратуры по block entity), `LinkRegistry` (участники сетей загруженных
  линков по измерению: linkPos -> [аппаратура, сеть]; линк регистрируется в `onLoad`/раз в секунду, снимается в
  `setRemoved`/`onChunkUnloaded`/при сигнале 15; при входе и выходе зовёт `requestDiscover` вычислителей сети).
  `block/RadarLinkBlock(Entity)` — блок на грани аппаратуры, UUID сети в NBT `Network` (старые - смещение `Target`);
  `item/RadarLinkItem` — сеть предмета в `CUSTOM_DATA` (`RadarNetwork`), рецепт сброса `radar_link_reset`; ПКМ линком
  по аппаратуре и по линку идёт в предмет, а не в блок (`RightClickBlock.setUseBlock(FALSE)` в `FcsMod`).
  `client/RadarLinkClient` — подсветка сети через `Outliner` (catnip).
- Перехват: `compat/Munitions` (что отслеживать, точная ли физика CBC у класса), `compat/Interception` (правила
  CBC Interception: взвод 2 тика, «один залп» < 1 бл/тик, `extraHitRadius` из его конфига рефлексией),
  `fcs/ProjectileTracking` (точка запуска и кем выпущен; прогноз полёта по тикам - физика CBC для снарядов, замеры для
  ракет; удар о блоки `level.clip` раз в 5 тиков, только по загруженным чанкам), `core/PathMotion` / `TargetPath`
  (прогноз цели, общий для решателя), `Track.munition/path/threatDistance/threatTicks`, `TargetSensor.assessThreats`.
  В `FcsControl` по снаряду: решатель и промах непрерывные (`discrete=false`, отрезки обоих за тик, как в моде),
  допуск = радиусы снарядов + `extraHitRadius` - запас, `armTicks` = 2, скорость сближения ≥ 1.25 бл/тик.
- `iff/` — `IffRegistry` (транспондеры по измерениям: код, имя, режим, радиус, наследование; `zones(level, rule)`,
  `protects`, `shipFriendly`, `shipName`, `infos`), `TransponderMode`, `TransponderSettings` (все настройки
  транспондера одним тегом), `InheritPolicy`, `IffAccess`; `block/IffTransponderBlock(Entity)` (владелец, код, режим, радиус; `apply` проверяет
  доступ и чистит значения), `client/TransponderScreen`, `net/TransponderPayloads` (Open S->C, Save C->S);
  `compat/ContraptionCompat.hasTransponder(e, rule, now)` - коды из данных блоков контрапшена.
- Стыковка (только с Flight Control): `block/DockingControllerBlock(Entity)` + `DockingBlocks` (роль порт/штекер, номер,
  до 8 портов `simulated:docking_connector` в одну сторону - пистолетом FC, `compat/flight/DockingToolLink`; середина
  концов, подбор доворота и соответствия по всем портам - `Docking.layout/matchYaw`; дистанция обхода - `approach`,
  `Docking.detour/keepOut`), `compat/DockingPorts` (порт Simulated: выдвижение через `POWERED` с повтором в начале и конце
  тика мира, `isLocked` рефлексией), `dock/DockingRegistry` (порты по сетям и номерам, брони), `dock/DockHold`
  (пристыкованные корабли - пушки на них `Status.DOCKED`), `core/Docking` (фазы подлёта, развороты, доворот по
  расстановке), в мосту - `dockTrigger`/`dockBusy` и `FlightBridgeBlockEntity.dockSteer` через ту же аренду (`lease`);
  пристыкован - компьютер FC выключен (`setWorking(false)`, `DockPoweredDown`). Экран `client/DockingControllerScreen`,
  `net/DockingPayloads`.
- Мост без цели (других объектов не ведёт): `Autopilot.Idle` - висеть / орбита вокруг координаты / вокруг маяка орбиты
  (`block/OrbitBeaconBlock(Entity)` с кодом, только маяки радарной сети моста - `LinkKind.BEACON`),
  `Autopilot.orbitVelocity` (скорость орбиты, `OrbitFacing` - куда нос), `FlightBridgeBlockEntity.idleSteer`; экран моста -
  вкладки «Цель» и «Без цели».
- Подсветка связей стыковочного контроллера с пистолетом FC в руке - `compat/flight/DockingToolClient` (цвета FC;
  рамки 12 рёбрами через позу отрисовки корабля Sable, каждый кадр).
- `block/`, `registry/`, `client/FcsClient` (цвет транспондера).
- `src/gametest/` — игровые тесты и dev-автоматизация (в jar не попадают).


## 4. Что можно улучшить
- Пушки на кораблях Sable (батарея на корабле): поддержано через перевод направлений в плот-координаты,
  но не проверено игровым тестом; наследует ли снаряд CBC скорость корабля — не проверено (`inheritPlatformVelocity`).
- Радары CR на кораблях Sable: позиция радара переводится в мир, но игровым тестом не проверено.
- Контроллеры наводки на станках на кораблях Sable не проверены игровым тестом.
- Монитор на корабле рисует картинку в осях блока (без учёта поворота корабля).
- Распределение целей между пушками (сейчас каждая выбирает сама по настройкам вычислителя).
- Манекен в тестах разворачивается мгновенно: на пару тиков он «стоит», и при «сбрасывать статичные» выпадает из
  целей — тест приоритета это учитывает.
- Сцены Ponder для стыковочного контроллера и маяка орбиты (их пока нет).
- Стыковка: физика подлёта в игре игровым тестом не проверена (корабль Sable в тесте сам не летит; сцепка порта
  корабля с портом на земле и отключение двигателей - проверены); пушка на пристыкованном корабле - не проверена;
  порты выгруженных чанков после перезапуска сервера неизвестны, пока их чанк не загрузится.
- Lua-версия (`lua/`) — исходный прототип для CC:Tweaked, в моде не используется.
