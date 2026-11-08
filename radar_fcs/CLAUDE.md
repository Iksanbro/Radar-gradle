# CLAUDE.md — Create: Radar Fire Control (radar_fcs)

Контекст для Claude Code. Отвечай пользователю на русском.

## 1. Цель
Аддон для **Create** + **Create Big Cannons** + **Create: Radars** (NeoForge 1.21.1): вычислитель, контроллеры наводки,
радарный линк, монитор; автоматическое наведение и огонь. Цели - только с радаров Create: Radars (обязательная
зависимость; его код не копировать, лицензия source-available). Своих радаров нет (удалены по просьбе пользователя в 0.7).
Требования пользователя (выполнены, см. игровые тесты):
1. Подключение только радарным линком (автоподключения по радиусу нет). С 0.8 линки - **радарные сети как у складских
   передатчиков Create** (Stock Link): ненастроенный линк - новая сеть, ПКМ предметом по стоящему линку - настроить
   предмет на его сеть, вся аппаратура сети связана со всей без ограничения дальности, сигнал 15 отключает линк,
   сброс - сетка крафта или Shift+ПКМ в воздух. Старые парные линки (0.6-0.7) - сеть из двух концов (UUID от концов).
2. Свой-чужой (с 0.9) - **по кодам транспондеров**: у транспондера код (1-8 цифр, можно без), режим выделения
   (`iff/TransponderMode`: SUBLEVEL - корабль Sable / контрапшен, на котором стоит; RADIUS - сфера заданного радиуса,
   1..`maxTransponderRadius`; не задан - по месту) и радиус; экран транспондера (ПКМ). У вычислителя вкладка
   «Свой-чужой»: белый или чёрный список кодов (`core/IffRule`, поля `iffWhitelist`/`iffCodes` в `EngageSettings`).
   Корабль/контрапшен с принятым транспондером свой в любом режиме. Через свою зону по цели снаружи огня нет
   (`FireSafety`). Транспорт со своим игроком внутри - свой. Коды видят и меняют
   только владелец блока, его команда и операторы (`iff/IffAccess`): коды не уходят в обновления блоков, экраны
   открываются пакетами с сервера (`FcsOpenPayload`, `TransponderPayloads`). Каналы-красители удалены (краситель
   транспондера - только цвет). Монитор берёт правило у первого вычислителя своей сети.
   С 0.10 зона своего транспондера бережёт **всё** внутри (мобов и врагов, игроков, снаряды, корабли) - так просил
   пользователь; режимы: SUBLEVEL (границы корабля + `SUBLEVEL_MARGIN` 2), RADIUS (шар), SUBLEVEL_AND_DISTANCE
   (границы корабля + радиус; не на корабле - шар). Зоны - `IffRegistry.Zone` (шар или AABB), пересчёт раз в тик,
   включая контрапшены Create с транспондерами (`ContraptionCompat.withTransponders`, режим/радиус из данных блока).
3. Неабсолютная точность: огонь, когда расчётный промах выстрела из фактического положения ствола
   не больше `max(размер цели, fireToleranceDeg на дальности)`; мёртвая зона `deadband`.
4. Наведение через контроллеры наводки (горизонтальной - под станком, вертикальной - сбоку), в которые подано
   вращение (`requireRotation`): скорость поворота как от валов CBC (0.0375°/тик на об/мин, 256 об/мин = 9.6°/тик)
   × `shaftSpeedMultiplier`; без кинетики — `slewDegPerTick`.
   Цель — сбивать цели до 70 м/с и быстрее (игровые тесты 70 и 100 м/с, корабль 70 м/с).
5. Неподвижные и медленные цели (`minSpeed`) неактивны (по умолчанию; настройки «сбрасывать статичные» и «время стояния до сброса» — короткая остановка не сбрасывает).
6. Приоритет ближайшей к каждой пушке цели (с гистерезисом `targetSwitchRatio`) — по умолчанию; настройки вычислителя
   (ПКМ, у каждого блока свои): режим огня (одиночный, автоматический, очередь + пауза), типы целей (сублевелы, игроки,
   мобы, сущности) с уровнем приоритета 1–5 и окном скорости, сброс (статичные, движущиеся, после N выстрелов),
   приоритет (ближайшая, ранняя, дальняя, крупнейшая, наименьшая, быстрая, медленная), переключение по приоритету,
   переключение между целями (после N выстрелов, через время, при остановке, нет). Конфиг сервера `targets.*` — верхняя граница.
7. Отображение: свечение, частицы, отчёт, очки инженера Create, Display Link, компаратор.
8. Цели: мобы (по умолчанию «только враждебные», мирные - белым списком), игроки (тип выключен по умолчанию в
   настройках вычислителя), контрапшены Create, снаряды, прочие сущности (лодки, вагонетки...; `Track.Kind.EXTERNAL`,
   категория «сущности»), корабли Sable / Create Aeronautics. Белый и чёрный списки у каждого вычислителя
   (`fcs/TargetLists`: `ns:id`, `#тег`, `ns:*`, имя игрока/корабля/сущности с биркой). Конфиг `targets.allow*` -
   верхняя граница (всё разрешено). Кнопки «Собрать»/«Разобрать» на вкладке «Состояние» (`FcsCommandPayload`,
   `FireControlBlockEntity.assembleWanted`, статус `DISASSEMBLED`).
   Пушки: CBC и аддоны на станке CBC (CBC Advanced Technologies и др.).
9. Динамическое упреждение: скорость + ускорение цели, обучение баллистики по выстрелам.
10. Радары - только Create: Radars (плоский радар, подшипник и др.) в сети; монитор (мультиблок) рисует все их цели
    (свои / угрозы / мирные / неактивные, фильтры показа, сведения под взглядом), окно монитора (Shift+ПКМ:
    список целей, назначение, фильтры, масштаб; `MonitorCommandPayload`). Ручное наведение: цель, назначенная на своём
    мониторе или выбранная на **мониторе Create Radars** в сети (`RadarLink.monitorSelection` - поле `selectedEntity`
    ведущего блока), - вне очереди, в обход типов, списков, скорости и движения, но не своих и не запретов сервера;
    ведётся и без радара (`TargetSensor.trackDirectly`).
11. Радарный линк (свой блок, код Create Radars не копировать): аппаратура сети - вычислители, станки, радары CR,
    свои мониторы, мониторы CR (`FireControlBlockEntity.discover`, `RadarMonitorBlockEntity.refresh`).
12. Списки (белый/чёрный целей, коды) - 16 записей + 16 за каждый **блок памяти** с питанием в сети вычислителя
   (`MemoryBlock(Entity)`, `LinkKind.MEMORY`, `FireControlBlockEntity.capacity/effectiveSettings` = `settings.limited`);
   хранится до 256, сверх памяти - серые и не действуют; экран прокручивает списки колесом.
13. **Электропитание FE** (`energy/EnergyBuffer`: приём от любых модов через `Capabilities.EnergyStorage.BLOCK`,
   регистрация в `FcsRegistry.registerCapabilities`, запас 40 тиков): вычислитель 1024 FE/т (без энергии - статус
   `NO_ENERGY`, цели не видит), блок памяти 240, транспондер 64 (без энергии снимается из `IffRegistry`; в контрапшене -
   без питания), монитор 32 за блок (ведущий забирает из общего запаса стены). Конфиг `energy.*`, `requirePower`.
   Проверено с Create Crafts & Additions 1.6.0 (`createaddition:creative_energy` и alternator сами отдают FE соседям;
   alternator на 256 об/мин ~360 FE/т; CC&A 1.7.x требует NeoForge 21.1.248+, в dev-окружении 21.1.228).
14. (0.11) **Наследование защиты** у транспондера (`iff/InheritPolicy`: маска `core/TargetCategory` - игроки,
   враждебные, мирные, корабли, контрапшены, снаряды, прочие; белый «всегда» и чёрный «никогда» списки по 16 записей
   в формате `TargetLists`, чёрный важнее): в зоне защищены только наследующие (`IffRegistry.protects`), корабль-носитель
   свой всегда (`shipFriendly`). **Имя транспондера** (до 32 символов, `TransponderSettings.cleanName`): имя трека корабля
   (`IffRegistry.shipName`) и контрапшена (`ContraptionCompat.name`), `Track.tagName`. Свой монитор: значки по типам
   (`block/MonitorIcons`: форма, цвет «по состоянию»/постоянный, размер; у ведущего блока, NBT `Icons`), фильтр
   «Транспондеры» (`Show.TRANSPONDERS`, отметки `Mark` наземных транспондеров с именами), подписи имён всегда или при
   наведении (`LABELS_ALWAYS`/`LABELS_HOVER`, и в мире, и в окне; без наложения - `client/LabelLayout`), окно с
   вкладками Экран / Цели / Вид. Стыки блоков стены не видны: рамка - один квад на всю стену, фон непрозрачный,
   текстура лицевой стороны бесшовная, рамка выделения блока на лицевой стороне не рисуется
   (`FcsClient.highlight`, `RenderHighlightEvent.Block`) - именно она выдавала стык при взгляде на монитор.
15. (0.12) **Перехват снарядов** (просьба пользователя: «отслеживание и перехват только больших пушек и ракет, кроме
   мелких; фильтры, типы, приоритеты»). Сбивать позволяет мод пользователя **CBC Interception** (`cbcintercept`,
   github iksanbro/drone, ветка claude/neoforge-gun-parts-assembly-w70xcp; jar не на Modrinth - тесты подключают его
   `-PfcsExtraMods`); без него снаряды видны, но огня нет (`interception.requireInterceptionMod`). Отслеживаются
   только снаряды больших пушек (наследники `AbstractBigCannonProjectile`) и ракеты (CBC-AT `AbstractMediumRocket`,
   классы с Rocket/Missile в имени над `AbstractBigCannonProjectile`: cbcatfix, Enchanced Shells) - `compat/Munitions`
   (теги `radar_fcs:intercept_shells/rockets/ignored`); мелкие (автопушки, пулемёты, тяжёлые автопушки CBC-AT, средние
   пушки Neo Warfare, ракеты ракетных блоков `AbstractRocket`, стрелы) - не кандидаты вовсе. Категории `SHELLS`,
   `ROCKETS` в `EngageSettings` (уровень 4), фильтр `Threat` (THREATENING по `threatRadius` 16 / APPROACHING / ALL),
   `impactFirst` (при равном уровне - раньше долетит, `TargetPicker.sameLevel`), вкладка «Перехват». Свой снаряд - по
   месту запуска (`fcs/ProjectileTracking.Origin`: пушка этого вычислителя, вычислителя того же владельца/команды -
   `MountState.onShotCounted -> markFired`; свой игрок; запуск из своей зоны), не по текущему месту.
16. (0.13) Окно скорости - **у каждого типа целей своё** (`EngageSettings.speedMin/speedMax[CATEGORIES]`, NBT
   `SpeedMins/SpeedMaxs`; старые `SpeedMin/SpeedMax` - во все типы), минимум 0.1 м/с. Числа на экране настроек -
   `client/NumberField` (колесо мыши + «−/+»), шаг `core/WheelStep`: дробные до 1 по 0.1, дальше по 1, Shift - x10;
   целые по 1 (Shift - 10). Время (пауза, время стояния, переключение) - секунды с шагом 0.1 с, хранится в тиках
   (`switchTicks`, NBT `SwitchTicks`; старое `SwitchSeconds` x20).
17. (0.14) **Свои цели отдельных пушек** (вкладка «Пушки»): `EngageSettings.mountTargets` (ключ - `BlockPos.asLong`
   станка, `core/MountTargets`: разрешённые типы и уровни; NBT `MountTargets`, до 64). `FcsControl`: пул целей пушки -
   по `effectiveSettings().forMount(key)` (тип разрешён, или белый список, или назначена вручную), порядок - её уровнями;
   сенсор - по `sensorUnion()` (тип разрешён вычислителю или хоть одной пушке). Сводка пушки: `Q` - позиция, `C` - свои цели.
18. (1.1) Монитор: лучи радаров сети вместо «бегущего» сектора - `Site.angle/fov/speed/range/stamp` (`RadarLink.angle` =
   `IRadar.getGlobalAngle()`, градусы, 0 - юг, 90 - восток; `fov`/`sweepSpeed` - рефлексией `getDisplayFovDegrees`/
   `getDisplayAngularSpeedDegreesPerTick` в CR 5.x, у 0.4.9 нет - 30/45° и 0), клиент доворачивает луч между обновлениями
   (`Site.angleAt`), веер `RadarMonitorRenderer.beam` из точки радара, обрезка краем круга. Авто-масштаб - только по
   дальности радаров (`niceRange(reach)`), от целей не зависит. «Сетка» издалека - z-fighting слоёв (зазоры 0.0002 и 0.003
   от грани): отступ 0.01, слои растянуты `ps.scale(1,1,10)`, лицевая текстура однотонная.
   Баги из тестов стрельбы: `FireSafety` не считает треки снарядов помехой (свой только что выпущенный снаряд давал
   `BLOCKED_FRIENDLY`); `MountState.flightModel` на перезарядке - по последней заряженной оценке (раньше - скорость по
   умолчанию, ствол уходил на 16° и обратно).

19. (1.2) Монитор - **карта** (просьба: «карта с настраиваемым масштабом 64-2048 блока сторона, центр - монитор, радары -
   отключаемыми и изменяемыми метками, как и их зона действия; сканирование связать с вращением радара, минимум 60 fps;
   проверять на 256 об/мин»). `RadarMonitorBlockEntity.SCALES` (индекс `MapScale`, по умолчанию 256), `mapK/onMap` -
   квадрат по меньшей стороне стены; `Show.RADARS/RADAR_ZONES` и значки `MonitorIcons.RADAR/RADAR_ZONE` (у зоны: форма
   RING - контур, иначе заливка; размер - яркость `zoneAlpha`); NBT `ShowV3` - старым мониторам включаются оба фильтра.
   Рисование - `client/MapCanvas` (общий для мира и окна): проекция, обрезка выпуклых фигур краем карты
   (Сазерленд - Ходжмен, точка на краю - от «меньшего» конца ребра, чтобы у соседних клиньев она совпадала до бита),
   сектора/дуги с общими вершинами (у полного круга последняя = первая) - нет щелей и двойного наложения.
   Слой экрана в мире - свой `RenderType` `MapCanvas.SCREEN`: без записи глубины, без сортировки, `VIEW_OFFSET_Z_LAYERING`
   - квады идут строго по порядку, z-fighting между слоями невозможен; сетка - непрозрачными смешанными цветами.
   Луч: `RadarMonitorRenderer.liveHeading` - угол клиентского BE радара (`Site.pos`) + `sweepSpeed * partialTick`
   (так же Create доворачивает антенну), иначе `Site.angleAt`. В окне partialTick берётся из `getTimer()`
   (параметр `render` у экрана - дельта кадра, не доля тика). DevScreenshots: подшипник на 256 об/мин, кадры луча - в лог.

20. (1.3) «Масштаб монитора вписываемым» - сторона карты вводится числом: `RadarMonitorBlockEntity.mapSide`
   (`MIN_SIDE` 16..`MAX_SIDE` 4096, NBT `MapSide`; старый `MapScale` - индекс в `OLD_SCALES`), `setSide`, колесо -
   `stepSide` по `SIDE_STEPS`; поле `RadarMonitorScreen.SideField` (EditBox: Enter / уход с поля - `MonitorCommandPayload.SCALE`
   со стороной). **Исключение цели колесом мыши** (нажатие): в мире - `FcsClient.pick` (`InputEvent.InteractionKeyMappingTriggered`,
   клавиша «выбрать блок»; отметку под взглядом даёт рендер - `RadarMonitorRenderer.hoverMonitor/hoverId`), в окне -
   кнопка 2 на карте и в списке; `MonitorCommandPayload.EXCLUDE` -> `toggleExclusion` (повтор - вернуть). Исключённые -
   `excluded` у ведущего блока (не сохраняются), снимаются, когда трек монитора пропал (`TRACK_TIMEOUT` без радара),
   и при назначении; флаг `FLAG_EXCLUDED` (серая, крест `crossOut`). Вычислитель берёт `exclusions()` своих мониторов
   сети как сброшенные цели (`TargetSensor.sense`, предикат `dropped`). Тест `LinkGameTests.monitorExclusionStopsFire`.
   Наборы игровых тестов с радаром CR должны звать `beforeRadar` (иначе `FcsDev.radarless` остаётся от прошлого набора
   и цели видны без радара - исключение «не снималось» при уходе из зоны).

21. (1.4) Сторона карты до 1 000 000 (`MAX_SIDE`, шаги `SIDE_STEPS` до 1e6, поле на 7 цифр). **Метки игрока**
   (`RadarMonitorBlockEntity.Pin`: x, z блока, имя до 24, цвет - индекс `MonitorIcons.PALETTE` 1..; до `MAX_PINS` 32,
   NBT `MapPins`, в данных клиента `Pins`; фильтр `Show.PINS`, старым мониторам включается по `ShowV4`) - только
   рисуются: `MonitorCommandPayload.PIN_SET` (номер или -1, «x;z;цвет;имя») / `PIN_REMOVE`; окно - ПКМ по карте,
   вкладка «Метки» (`CommitBox`: Enter / уход с поля), в мире - `drawPin` + подпись, взгляд - `describePin`.
   **Шкала дальности**: `SCALE_F` (1.4.2: 1/4, 2/4, 3/4, 4/4; в 1.4.0 - 1/3, 1/2, 2/3, 1) половины стороны - линии `drawGrid` и засечки с подписями
   `scale()` - (1.4.1) на обеих осях в обе стороны, как координатная плоскость: вправо/вверх «+», влево/вниз «-»;
   подписи поочерёдно по сторонам оси, по важности (край, половина, четверти), без наложения на занятое (`blocked`: север,
   строки состояния, сведения, подписи целей). **Линк не прилипает**:
   `RadarLinkBlock.canStickTo` = false - отражатели CR (`AbstractRadarFrame.isStickyBlock`) при сборке Create-
   контрапшена забирают соседей с `canStickTo` по умолчанию; у CR 5.0.1 `RadarContraption.movementAllowed` берёт
   только части радара, у 0.4.9.x и у подшипников Create - всё. Тест `LinkGameTests.radarLinkDoesNotStickToDish`
   (подшипник Create, отражатель над линком; контроль - наблюдатель уезжает), `monitorPinsAndSide`.
22. (1.5) **Общий лимит подключений радарной сети** («16 подключений любой аппаратуры: 16 линков + 16 за блок памяти»):
   `LinkRegistry.BASE_LINKS`/`LINKS_PER_MEMORY`, работающие линки - `active(level, net)` (кэш на тик `ACTIVE`/`ACTIVE_TICK`):
   линки блоков памяти всегда и не в счёт, остальные по порядку первой регистрации (`ORDER`, `sequence`) до ёмкости
   `capacityFor(памяти с питанием)`. `members`/`networksOf`/`linkedTo` - только через работающие. Линк: `usage`/`overflow`
   (NBT `Usage`/`Overflow`), сообщение `radar_fcs.link.overflow`. Тест `LinkGameTests.networkLinkLimit`.
23. (1.5) **Камеры** (`block/CameraBlock(Entity)`: FACING = грань, `eye()`, луч до `RANGE` 384 по загруженным чанкам +
   сущности; управление - `startControl`/`control`/`stopControl`, истекает через `TIMEOUT` 10 тиков без пакетов) и
   **монитор камер** (`CameraMonitorBlock(Entity)`, `client/CameraMonitorScreen` - список, имя, «Смотреть»).
   `net/CameraPayloads`: Open/Start (S->C, данные в `CompoundTag` - у `StreamCodec.composite` максимум 6 полей),
   View/Control/Stop/Rename (C->S; проверки - рядом с монитором `MAX_DIST`, `LinkRegistry.linked`, тот же игрок).
   `client/CameraClient`: камера игрока - сущность `Marker`, мышь крутит игрока, углы - в `ComputeCameraAngles`,
   движение обнулено, ЛКМ отменён (`InteractionKeyMappingTriggered`) и идёт в пакет огня, рука и HUD скрыты, Shift - выход
   (после отпускания). Вычислитель: `fcs/ManualAim` (точка, огонь, камера) от камеры с последним управлением -
   цель `Track.point(MANUAL_ID)`, автоприцеливание пропущено, огонь только при `manual.fire()`, статус `CAMERA`.
   Тест `CameraGameTests.cameraManualAimAndFire`.
24. (1.5) **Орудия на вращателях Sable** («прицел для конструкций... вращатели, связываемые орудийным линком;
   радарный линк к любой части орудия; вращателям нужно вращение»):
   - `block/GunRotatorBlock(Entity)` (`KineticBlockEntity`, FACING - выход, вал сзади, нагрузка 2x контроллера):
     ПКМ - `compat/sable/SableRotor.assemble` (блоки - `BearingContraption.assemble`, т.е. правила клея Create;
     `SubLevelAssemblyHelper.assembleBlocks`; вращатель внутри под-уровня - выкинуть из содержащего и перенести позу),
     шарнир `RotaryConstraintConfiguration` + сервомотор `setMotor(goal, 1600*I, 40*I)` на каждом
     `ForgeSablePrePhysicsTickEvent` (`physicsStepAll`, цель интерполируется по `partialPhysicsTick`). Шарнир не
     сохраняется - переподключается в `tick`, когда под-уровень загружен. Угол - закрутка conj(qContaining)*qSub вокруг
     оси (диапазон atan2*2: -343° = 17°); `target` идёт к `want` кратчайшим путём (`wrap180`) со скоростью вала;
     переход через ±180° мотор Sable проходит правильно (проверено: 170 -> -170 через 180). Класс с API Sable
     грузится только при `Mods.SABLE`.
   - `block/SightBlock(Entity)` - прицел: FACING = взгляд игрока при установке, `worldDirection()` через позу
     под-уровня, сигнал редстоуна `FIRING` (`fire(on)` держится 2 тика, иначе гаснет сам). Линки игнорируют сигнал
     своей аппаратуры (`RadarLinkBlock.controlSignal`) - иначе прицел, стреляя, отключал линк на себе.
   - `link/GunLinks` + `block/GunLinkBlock(Entity)` (наследник `RadarLinkBlock`) + `item/GunLinkItem` (`GunNetwork`) -
     орудийные сети без лимита; `group(level, pos)` - всё орудие по цепочке сетей. Ставится на прицел, вращатель,
     станок CBC (`GunLinkBlockEntity.isGunPart`).
   - `LinkKind.SIGHT`/`GUN_ROTATOR`. `FireControlBlockEntity.addTurret`: часть орудия на радарном линке (или станок в
     орудийной сети с прицелом) -> `fcs/Turret` (прицел, вращатели, станки); состояния `MountState.turret` - на каждом
     станке орудия или одно на прицеле (без станков, `mount == null`, модель полёта `DIRECT` - прямая наводка).
     Ведущий (`Turret.leader`, первое состояние) наводит, вторые станки - `FcsControl.follow` (стреляют с ним).
     Поворотные части орудия - в `platformShips` (не цель и не помеха).
   - Наведение (`Turret.aim`): цепочка вращателей от прицела вверх по `containingId` (`Turret.chain`), от внешнего:
     поворот = угол между проекциями направления прицела и решения на плоскость ⊥ оси вращателя, затем этот поворот
     применяется к прицелу и осям внутренних (Родриг); `want = фактический угол + поворот` (замкнутый контур каждый тик).
     Станок орудия углы не получает (с 1.6 валы не блокируются - их крутят приводы), промах - по направлению прицела.
   - Проверка: dev-клиент `-PfcsScreenshot -PfcsTurretOnly` (`DevScreenshots.turretScene`): прицел ведёт идущего
     хаска с ошибкой 0.1-0.3°, статус FIRE, сигнал прицела. Игровой тест `TurretGameTests.gunTurretGrouping`
     (сети, обнаружение, станок орудия не поворачивается, прицел без вращателей); физику Sable в игровых тестах не
     проверяем.

25. (1.6) **Привод орудия** («вращатель как для пушек CBC, выдающий просто вращение: поворот подшипником, наклон -
   вращением на станок или наоборот»; «станки CBC подключаемые орудийным линком, огонь ими; сигнал прицела не убирать»):
   - `block/GunDriveBlock(Entity)`: `SplitShaftBlockEntity` (как коробка передач), FACING - выход, вал по оси с двух
     сторон; `getRotationSpeedModifier` = `output` (-1..1) на гранях не со стороны источника. Смена выхода -
     `detachKinetics(); output = q; attachKinetics()` (как sequenced gearshift), шаг 1/200, мелкие изменения
     того же знака < 0.02 не перестраивают сеть; без команды `HOLD_TICKS` 2 - выход 0. Свойство `PITCH` (ПКМ).
     Отрисовка - `client/GunDriveRenderer` (два полувала, выход x модификатор).
   - Отклик учится (`learn`): градусов/тик на 1 об/мин выхода, начальное 0.3 (подшипник), EMA 0.3 по модулю,
     знак меняется после двух подряд движений «наоборот»; наблюдение - поворот направления наведения за тик вокруг
     оси привода (курс - «верх» основания первого привода курса, наклон - aim x up). `Turret.drive`: курс, затем
     наклон уже повёрнутого направления; выход = поворот / (отклик x 3 тика).
   - Орудие без прицела: `Turret.sight == null`, `key` - первый станок, направление наведения - ствол станка
     (`localBarrelDirection(1)` -> мир). `addTurret`: орудие = прицел или станок с вращателями/приводами.
     Станки орудия не блокируются (`prepareMount(..., lock=false)`, снятие старой блокировки). Сигнал прицела
     (`sight.fire`) - и у орудия со станками.
   - Проверка: игровой тест `TurretGameTests.gunDrivesTurnMountByShafts` (автопушка, вместо контроллеров - приводы
     курса и наклона, без прицела: 3 попадания на 32-м тике, поворот 38°); dev-клиент `-PfcsSwivelOnly`
     (`DevScreenshots.swivelScene`): подшипник-шарнир Simulated (`simulated:swivel_bearing`, вращение - через
     шестерню, `assemble()`), на нём станок CBC с автопушкой, наклон - привод в вал станка: огонь по идущему хаску,
     ошибка 0.1-1.7°, отклик курса выучен со знаком минус.

26. (1.6.1) **Камера поворачивается** («визуально вращалась, сохраняя последнее положение; вид - как поворотная
   камера наблюдения с фото пользователя: кронштейн, цилиндр, шар с чёрной панелью»): `CameraBlockEntity.view()` -
   последний взгляд игрока (`viewYaw/viewPitch`, NBT `ViewYaw/ViewPitch`, в мире; иначе `defaultView` от грани),
   `startControl` начинается с него (и пакет `Start`), рассылка клиентам не чаще раза в 2 тика; клиент поворачивает
   плавно за 2 тика (`renderView`). Модели: блок - только крепление (`camera` - стена, `camera_ceiling`, `camera_floor`),
   `camera_pan` (цилиндр, курс) и `camera_head` (шар, курс и наклон; центр (8, 5, 8), объектив на север) - отдельные
   модели (`ModelEvent.RegisterAdditional`), их крутит `client/CameraRenderer` (взгляд переводится в координаты блока
   через `SableCompat.dirToLocal`); у камеры на полу цилиндр снизу, шар сверху. `pivot()` - центр шара (с 1.7 - и глаз). Тест `CameraGameTests.cameraKeepsLastView`; dev-клиент `-PfcsCameraOnly`
   (`cameraGallery`: стена по умолчанию / повёрнутая, потолок, пол).

27. (1.7) «Точка обзора камеры - от её центра, корпус в своём виде не виден; ЛКМ - сигнал огня как редстоун 15 всем
   орудиям (зажатая - непрерывно); монитор камер 64 FE за камеру; настройки орудия по Shift+ПКМ на орудийном линке:
   тип (автопушка, среднее, большая, кастом без упреждения), участие в автоприцеливании и стрельбе, имя»:
   - `CameraBlockEntity.eye()` = `pivot()` (центр шара); луч не видит свой блок (`loadedOnly(sl, self)`);
     `CameraRenderer` не рисует цилиндр и шар камеры, через которую смотрит этот клиент (`CameraClient.viewedCamera`).
   - `FcsControl.manualTrigger`: при управлении с камеры огонь = ЛКМ (`setFire(on, 15)` станкам, `sight.fire`,
     `Turret.fire` вторым станкам), без проверки наводки, режима огня и безопасности; и без решения (NO_SOLUTION).
   - `CameraMonitorBlockEntity`: `EnergyBuffer`, камеры сети раз в секунду, `usage = камеры x cameraMonitorUsagePerCamera`
     (64); без питания `View` отклоняется, `Control` снимает управление и шлёт клиенту `CameraPayloads.Kick`.
   - `fcs/GunSettings` (тип, `autoAim`, `fire`, имя до 24, `stamp`): хранится в `GunLinkBlockEntity` (NBT `Gun`),
     в `GunLinks.SETTINGS` по сетям (новее - по `stamp`, время сохранения); `saveSettings` раздаёт всем загруженным линкам
     сети. Экран `client/GunSettingsScreen` (Shift+ПКМ по линку), `net/GunPayloads` (Open S->C, Save C->S, 8 блоков,
     `mayBuild`). В `FcsControl`: `Turret.networks` -> `settings(level)`; `autoAim` выкл и нет камеры - статус
     `GUN_AUTO_OFF`, привод стоит; `fire` выкл - огня нет, статус HOLD (ЛКМ с камеры работает); тип - модель полёта
     орудия без станка (`MountState.gunSettings` -> `GunSettings.model()`: автопушка/большая - запасные модели CBC из
     конфига, среднее - среднее их, кастом - «луч» 64 бл/тик) и сигнал прицела (средняя/большая - импульс не чаще
     `bigPulseInterval`), кастом - цель без упреждения (`TargetMotion` в текущей точке). Имя - в сводке (`G`).
   - Тесты: `TurretGameTests.gunSettingsAutoAimAndFire`, `CameraGameTests.cameraMonitorUsagePerCamera`,
     `cameraKeepsLastView` (глаз = центр шара).

28. (1.7.1) «На конструкции Sable можно наводиться по камере»: `SableCompat.clipShips(level, from, to, ignore, blocks,
   velocity)` - луч переводится в координаты каждой постройки на пути (`logicalPose().transformPositionInverse`), клип по
   блокам её плота, ближайшее попадание обратно в мир (`transformPosition`) + скорость точки (`pointVelocity`). На клиенте
   (оверлей расстояния) ошибка API не выключает совместимость (`fail` только на сервере). `CameraBlockEntity.lookPoint`:
   мир -> постройки Sable (своя - `containingId` камеры - пропускается) -> сущности; скорость точки (`aimVelocity`; у
   сущности - перемещение за тик, на земле без вертикали) идёт в `ManualAim.velocity` -> `Track.point` со скоростью
   (`vel`, `knownVel`, `moving`) - решатель берёт упреждение. `CameraClient.tick`: Marker вида каждый тик в `eye()`
   (камера на движущейся постройке). Тест `CameraGameTests.cameraAimsAtSableShip`.

29. (1.7.2) «Улучши текстуры камеры; камера подключается к системе без линка, как фабричный датчик Create»:
   текстуры 32x32 (`camera_white/bracket/pan_side/pan_top/ball/face`, генератор - рисование в 8x со сглаживанием),
   в моделях явные UV (шар - [3,3,13,13], панель и цилиндр - вся текстура). `item/CameraItem`: ПКМ по радарному
   линку (не орудийному) или по камере в сети (`tunableNetwork`) - предмет настроен (`RadarNetwork`, как у
   `RadarLinkItem`), в `FcsMod` для этого `setUseBlock(FALSE)`; Shift+ПКМ в воздух - сброс. `CameraBlockEntity.network`
   (NBT `Network`): камера регистрирует себя в `LinkRegistry` (linkPos = equipment = камера; занимает подключение в
   лимите), раз в секунду, снятие в `setRemoved`/`onChunkUnloaded`, `requestDiscover` вычислителям сети. Сеть из
   предмета - `applyImplicitComponents(CUSTOM_DATA)`, в выпавший предмет - `collectImplicitComponents` + лут
   `copy_components`. Тест `CameraGameTests.cameraJoinsNetworkWithoutLink`.

30. (1.8) «Улучши текстуры всему в моде; основная пушка орудия в орудийном линке на станке (прицел, если его нет);
   в мониторе камер - какие пушки или орудия управляются с конкретной камеры»:
   - Текстуры 32x32, генератор в 8x со сглаживанием (латунь/тёмный металл в духе Create): `fcs_front(_engaged)`,
     `memory_front(_off)`, `camera_monitor_front`, `radar_monitor_side`, `radar_link_*`, `gun_link_*`, `iff_antenna`,
     `iff_band` (красится каналом - в оттенках серого), `gun_rotator_band`, `gun_drive_*`, `*_controller_plate`,
     `sight_*`. Разметка UV прежняя (полосы, антенны в тех же строках/столбцах). `radar_monitor_front` - чёрный
     нарочно (под рендер карты).
   - `GunSettings.mainMount` (NBT `Main`): экран орудия получает `ThisMount`, если линк на станке, - переключатель
     «Основная пушка». `addTurret`: ключ орудия без прицела = основная пушка (если она в орудии), `Turret.main`,
     ведущий = основная пушка (её ствол - направление наведения, её баллистика).
   - Камера: `guns` (NBT `Guns`, ключи `FireControlBlockEntity.gunKey` - станок или ключ орудия; пусто - все),
     `ManualAim.guns/stamp/controls(key)`. Вычислитель: `manualAims` - все камеры под управлением (новые первыми),
     `manualFor(m)` - первая, что управляет этой пушкой; остальные пушки - автоприцел. Монитор камер: `Open` несёт
     `guns` (ключ + подпись `gunLabels`: имя орудия или тип станка, мировые координаты) и `g` у камер; пакет
     `CameraPayloads.Guns`; `client/CameraGunsScreen` (отметки, «Все орудия», сохранить, назад).
   - Тесты `TurretGameTests.gunMainMountIsAimReference`, `CameraGameTests.cameraControlsChosenGuns`.

31. (1.9) «Поддержка другого типа станков (CBC Firepower Components)»: компактные станки FPC
   (`CompactCannonMountBlockEntity`, блоки `compact_cannon_mount`, `compact_autocannon_mount`, вертикальный) - не
   наследники станка CBC, но тот же смысл углов (`cannonYaw` = yaw Minecraft, `cannonPitch > 0` - вверх) и
   `onRedstoneUpdate`. `CbcMount` теперь обёртка над `CbcMount.Ops`: `CbcOps` (станок CBC) и `compat/FpcMount`
   (грузится только при `Mods.FIREPOWER`): углы - `setLimitedYaw/Pitch` (ограничитель станка, станок сам поворачивает
   контрапшен и шлёт данные - вызываем только при изменении), блокировка валов - интерфейсы из
   `getAllKineticBlockEntities()`, свойства сигналов - `CompactCannonMountBlock.FIRE/ASSEMBLY_POWERED`. Пушка у
   компактного станка - сбоку (`HORIZONTAL_FACING.getCounterClockWise()`), ствол по `HORIZONTAL_FACING`. Огонь FPC -
   ИЛИ физического сигнала и своего автоконтроллера огня. Сборка против FPC 0.4.4 (`modCompile`, Modrinth
   `cbc-firepower-components`, `fpc_version`), в тестах - `devMods` (`-PfcsNoFpc` - без него). Тесты
   `FirepowerGameTests.compactAutocannonMountHits`, `compactCannonMountHits`.

32. (2.0) «При наведении через камеру на орудие, которым управляешь, наводить не нужно (сейчас лагает) - запретить
   вращение пушке, на которую навелись»: `CameraBlockEntity.lookPoint` запоминает, во что упёрся луч -
   `ManualAim.Hit` (блок в мире или в плоте Sable - `SableCompat.ShipHit.block`, постройка Sable, id сущности; у
   сущности - постройка, на которой она едет). `FcsControl.aimsAtItself`: блок - часть орудия (станок, прицел,
   вращатели, приводы, станки орудия) или линк/контроллер наводки на ней; сущность - контрапшен пушки станка;
   постройка - под-уровень вращателя цепочки или (если есть приводы) постройка, на которой стоит прицел/станок
   (подшипник-шарнир). Тогда орудие стоит: углы станка не меняются, вращателям `setWant(angle())`, приводам
   `command(0)`, статус `CAMERA_SELF`, ЛКМ стреляет (`manualTrigger`). Тест `CameraGameTests.cameraLookingAtOwnGunHolds`.
   Там же (2.0.0, без смены версии) «сброс после числа выстрелов стреляет 1 раз»: счёт был общий на все пушки
   вычислителя (`FireControlBlockEntity.shotsAt`), и при нескольких пушках цель сбрасывалась после первого залпа.
   Теперь `onShotAt(gun, id)`: счёт и сброс у каждого орудия (`MountState.shotsAt/dropped`; станки орудия - за
   ведущего, их залп в одном тике - один выстрел, `lastSharedShot`), пул целей пушки без своих сброшенных
   (`FcsControl.gunDropped`); общий `dropped` (цель уходит из сенсора) - когда сбросили все орудия. Снаряды одного
   тика от одной пушки (картечь, спаренные стволы) - один выстрел (`MountState.onShotCounted`, `lastShotTick`).
   Тест `SettingsGameTests.dropAfterShotsPerGun` (две автопушки, по 5 выстрелов каждая).

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
  `FireSafety` (свои на траектории, блоки), `MountState`, `MountClaims`, `Status`, `SettingsNbt`.
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
- `block/`, `registry/`, `client/FcsClient` (цвет транспондера).
- `src/gametest/` — игровые тесты и dev-автоматизация (в jar не попадают).

## 4. Проверенные факты о зависимостях
- CBC: `cannonYaw` = yaw Minecraft, `cannonPitch > 0` — вверх для всех сторон. **Направление выстрела CBC берёт
  по положению на начало тика** (`toGlobalVector(..., 0)` = `prevYaw/prevPitch` контрапшена; `prevYaw = yaw` в начале
  тика контрапшена). `CbcMount.setAngles` сразу пишет углы и в контрапшен (как `applyRotation` станка), поэтому задержки
  не зависят от порядка тиков блоков: **большая пушка** стреляет синхронно по сигналу — по углам прошлой команды
  (`localBarrelDirection(0)`), снаряд появляется сейчас; **автопушка** стреляет в тике контрапшена следующего тика —
  по только что выставленным углам (`localBarrelDirection(1)`), снаряд на тик позже. Команда считается для вылета через тик.
  Старое правило «оба положения ствола в допуске» на быстрых целях не давало стрелять вовсе (ствол всегда отстаёт на тик).
- CBC: скорость поворота станка от валов = `convertToAngular(rpm) * 0.125` (0.0375°/тик на об/мин). Вал поворота —
  со стороны `VERTICAL_DIRECTION` (снизу), вал наклона — сбоку по оси, перпендикулярной `HORIZONTAL_FACING`.
- CBC: разброс (`projectile.shoot(..., spread)`, ванильный `Projectile.shoot`: к направлению добавляется
  `triangle(0, 0.0172275*spread)` по каждой оси) — у большой пушки сумма `added_spread` зарядов (порох 2) минус
  `spreadReductionPerBarrel` за каждый блок с тегом `reduces_spread` от снаряда до дульного среза (не ниже
  `minimumSpread`); у автопушки `baseSpread − spreadReductionPerBarrel * стволы` (не ниже 0). Обычно длинный ствол
  даёт ~0. Считается в `CbcBallistics` (`Estimate.spread`), показывается в отчёте, запас очереди автопушки — от него.
- **CBC-AT переписывает физику большой пушки** (миксин `MountedBigCannonContraptionMixin`, включён, пока в его конфиге
  `disablePhysicRework = false`): скорость = газ зарядов минус сопротивление стволов, а не сумма зарядов (тестовая
  пушка: 6.9 вместо 8.0), свой разброс. `CbcAtPhysics` считает их классом CBC-AT `CustomPropellantContext` через
  рефлексию. Без этого первый выстрел каждой большой пушки по быстрой цели мимо.
- **CBC Neo Warfare** (`cbcmodernwarfare`, порт CBC Modern Warfare, CurseForge 1552960; нет на Modrinth):
  роторная пушка (`MountedRotarycannonContraption`, ItemCannon) стреляет в тике контрапшена, пока есть сигнал
  (мощность сигнала = темп, 15 = кулдаун 1 тик), снаряд - центр блока за последним стволом + 0.75 по оси
  (`front + 2.75` от оси); средняя пушка (`MountedMediumcannonContraption`, ItemCannon) стреляет **по фронту сигнала
  синхронно** (`onRedstoneUpdate(togglePower && firePower > 0)`), как большая, снаряд - как у автопушки (`front + 0.5`).
  Скорость и разброс обеих - `baseSpeed + speedIncreasePerBarrel * min(стволы, maxSpeedIncreases)`, `baseSpread -
  spreadReductionPerBarrel * стволы` по материалу. Казённик средней - `getInputBuffer()/setInputBuffer(ItemStack)`, патрон -
  `MediumcannonAmmoItem` со снарядом (`getCreativeTabCartridgeItem()`), после выстрела там пустая гильза.
  Поддержка - по именам классов (`compat/NeoWarfare`), без зависимости при сборке.
- **CBC: Advanced technologies crash fix** (`cbcatfix`, CurseForge 1603954, требует CBC 5.11.7+, CBC-AT 0.1.4+): чинит
  `onRecoil`; физику больших пушек CBC-AT не меняет; ракеты - старт 0.15 бл/тик, разгон к крейсерской скорости
  (ускорение = недобор / время отклика) без гравитации, пока есть топливо (`CbcatFixFuelTicks` или
  `CbcatFixAssemblyFuel` в данных предмета; без топлива ракета падает), пусковая не длиннее 3 блоков. Нарезной ствол
  CBC-AT (`RifledBarrelBlock`) сужает разброс в 0.45 раза за блок (обычный в 0.8) - учитывается через `CbcAtPhysics`.
- Обучение по выстрелам: бронебойный снаряд, пробив цель, резко тормозит (-28 % скорости); раньше это записывалось в
  модель как торможение на этом тике полёта и портило дальние выстрелы. Признак удара - сильное и внезапное торможение
  относительно прошлого тика; разгон вперёд (тяга ракет до 4 бл/тик²) учится.
- Попадание CBC: отрезок полёта за тик против габарита цели, **расширенного на половину размера снаряда**
  (`reach = max(bbWidth, bbHeight)/2`: пуля автопушки 0.1, снаряд большой пушки 0.4). Габарит сущности — по оси,
  точный: промах считается до него (`Track.half`), у кораблей и контрапшенов — до центра с допуском по размеру.
- Попадание в Minecraft: отрезок полёта снаряда за тик против цели на конец тика (снаряд новее цели и тикает после неё;
  в игровых тестах цель двигается после тика уровня — то же самое). Отсюда потиковый перехват. Встреча у самого конца
  или начала отрезка неустойчива (доля блока вдоль траектории переносит её на соседний тик) — такие точки не считаются
  (`BallisticSolver.ALONG_TOL`). Измерено: расчётный путь снаряда совпадает с фактическим до 0.00 блока.
- Обучение вылета дульного среза: проекцию точки появления снаряда брать на направление **его скорости** — ствол быстро
  ведущей пушки за тик до выстрела смотрел иначе, проекция на него занижала вынос на 0.09 блока (сдвиг встречи по времени).
- Проверка линии огня кэшировалась на 3 тика — по цели 100 м/с точка встречи за это время уходит на 15 блоков, и очередь
  шла в стену. Теперь проверка повторяется, как только точка встречи сместилась больше чем на 0.75 блока.
- CBC: блоки пушки должны быть соединены (`cannonBehavior().setConnectedFace`), иначе «нет открытого конца».
- CBC: большая пушка стреляет по фронту сигнала, скорость снаряда = сумма силы зарядов (без CBC-AT); автопушки — мощность сигнала = темп.
- Create Radars: плоский радар сканирует в сторону, **противоположную** FACING (конус ±22.5°), работает и на земле.
  Треки радара — позиции ног сущности; вычислитель уточняет их по живой сущности.
- Игровые тесты: `GameTestHelper.relativePos` считает от другого начала, чем `absolutePos` - относительную позицию
  брать как `abs.subtract(h.absolutePos(BlockPos.ZERO))`. Помощник `fcs()` ставит линки на все станки площадки,
  `powerMount` - контроллеры наводки (горизонтальный на y=0 под станком, мотор под ним на y=-1) и моторы.
- **CBC-AT 0.1.4c несовместима с CBC 5.11.4+** (`NoSuchMethodError onRecoil`): ракетные установки и др. падают при выстреле
  независимо от нашего мода. Работает с CBC ≤ 5.11.3.
- Sable: `SubLevelAssemblyHelper.assembleBlocks` — для тестов (`SableTestSupport`: отдельный класс, чтобы тесты
  грузились без Sable). **В игровом тесте физика Sable корабль по горизонтали не смещает** (скорость тела есть,
  поза не меняется), поэтому тестовый корабль двигается `RigidBodyHandle.teleport` каждый тик + та же скорость тела.
  `boundingBox()` отстаёт от `logicalPose()` на тик; прицел — центр масс `logicalPose().transformPosition(rotationPoint)`
  (принимается, если лежит в габарите, растянутом на перемещение за тик: у быстрого корабля он впереди габарита,
  и центр габарита давал бы цель с опозданием на тик). Тест быстрого корабля ждёт, пока долетят все три снаряда.
- Игровые тесты: площадка окружена барьерами, за её пределами чанки не грузятся — трассы быстрых целей требуют
  `forceArea` (принудительная загрузка чанков, снимается в `cleanup`). **Барьеры своей и старых соседних площадок
  стоят на пути быстрых целей** (манекен пролетает сквозь стену, снаряды в неё упираются) — тесты быстрых целей
  убирают барьеры в зоне полёта (`removeBarriers`). Урон снарядов CBC приходит без прямой сущности —
  попадания считаются по событию урона с источником `createbigcannons.*`; какой снаряд попал — ближайший к цели в этот момент.
- Игровые тесты: порядок колбэков `onEachTick` разных регистраций внутри тика случаен (хеш-таблица) — снимать
  положение цели в колбэке и сравнивать с другим колбэком нельзя (скачет на тик). Мгновенный разворот цели-манекена
  портит снаряды, уже летящие в этот момент, — тесты отдельно считают снаряды, выпущенные не в такой момент.
- Счёт выстрелов для режимов огня: автопушка стреляет в тике контрапшена (сущности тикают раньше блоков), снаряд
  ловится `EntityJoinLevelEvent` у дульного среза, в том же тике вычислитель снимает сигнал — очередь точная.
  Цель на тик пропадает (манекен на развороте «стоит») — очередь не обнуляется; перерыв в стрельбе не короче паузы
  сам считается паузой. Заряжание (`CbcBallistics.estimate`) перепроверяется раз в 20 тиков, а пока пушка пуста —
  каждые 2 тика (иначе автомат заряжания на 5 тиков давал выстрел раз в 21 тик).
- Игровые тесты: бронебойные снаряды при попадании дают осколки (тоже `AbstractCannonProjectile`, по 4-5 за тик у цели) -
  выстрелами считаются только снаряды, впервые замеченные у пушки (`MUZZLE_ZONE`), попадания - по выпущенным снарядам.
  Моды не с Modrinth подключаются `-PfcsExtraMods=<папка с jar>`.
- Конфиг сервера тестов лежит в `run/config/radar_fcs-server.toml` и переживает запуски: после смены значения по
  умолчанию его надо удалить, иначе тесты идут со старым значением.
- Диагностика: `./gradlew runGameTestServer -PfcsProbe` — разбор каждого снаряда (расчёт против факта, ошибка пути,
  блок на пути), выстрелы больших пушек и корабль по тикам. `-PfcsNoCbcAt` — без CBC-AT.
- Create: `KineticBlockEntityRenderer` при включённом Flywheel ничего не рисует (ждёт визуал), поэтому
  `AimControllerRenderer` - свой `SafeBlockEntityRenderer` на `CachedBuffers.partialFacing(SHAFT_HALF)`.
- Игровые тесты идут с `energy.requirePower = false` (`commonConfig`), питание проверяет `EnergyGameTests` (подаёт FE
  через capability каждый тик или ставит `createaddition:creative_energy`).
- Игровые тесты: манекен `makeMockServerPlayerInLevel` всегда в творческом режиме (не цель), а своё подключение
  ServerPlayer-манекена ломает Sable (пакет `simulated:end_sea` нельзя отправить) — цель-игрок в тестах -
  `makeMockPlayer(SURVIVAL)`, добавленный в мир `addFreshEntity`. Выбор на мониторе Create Radars без его сети CR
  снимает сам CR — тест ставит `selectedEntity` каждый тик.
- Раньше зона наземного транспондера прикрывала всё и `FireSafety` запрещала огонь в её сторону целиком — враг
  на базе был недосягаем (жалоба «не стреляет по мобам»), плюс серверные `hostileOnly`/`targetPlayers=false`.
- CBC: полёт снаряда за тик - `a = getForces(pos, v)` = `-v̂·min(drag·плотность·|v|^(1|2), |v|) + (0, g, 0)`,
  `pos += v + a/2`, `v += a` (как `CbcFlightModel`); ракеты CBC-AT переопределяют `getForces` (тяга растёт на `thrust`
  за тик до `maxThrust`, пока есть топливо), без `setLifetime` ракета исчезает на первом тике, без `setFuel` - без тяги.
- CBC Interception проверяет встречу снарядов в `LevelTickEvent.Post` (после всех сущностей и блоков) по точкам
  `position()` (не центру габарита) и `xo..x`; сбитый снаряд исчезает в конце тика - трек снимается при следующем
  опросе. Радар Create Radars ещё какое-то время отдаёт отметку сбитого снаряда - по ней трек не заводится
  (снаряд без сущности - не цель; отсеянные сенсором цели помнятся в `filtered`).
- **RPL снимает чужую принудительную загрузку.** `ChunkManager` RPL (CBC: `projectilesCanChunkload`) грузит чанк по
  пути снаряда, если его нет в `getForcedChunks()`, и через `projectileChunkAge` тиков зовёт `updateChunkForced(false)` -
  без счётчика владельцев. Если сервер тестов за это время загрузил там новую площадку (`setChunkForced`), тикет
  пропадает: чанк числится загруженным, но не тикает для сущностей - пули автопушки висят у ствола (счёт выстрелов
  стоит, попаданий 0). Так падал `statictargetengagedwhenallowed` (перед ним `far_qf` бьёт на 150 блоков над местом
  следующей площадки; зависело от выравнивания случайной точки тестов по чанкам). Сторож `DevAutostop.keepForcedTickets`
  (только `GameTestServer`) возвращает тикет чанку, который тикал и перестал; за прогон - ~400 таких чанков.
  Проверка - `InfraGameTests.forcedChunkSurvivesRpl` (без сторожа 0 попаданий, со сторожем 64 из 64). В игре это
  касается только чанков, взятых `/forceload` в те несколько тиков, пока над ними летит снаряд (тикеты NeoForge
  `TicketController` RPL не трогает).
- Рендер монитора: квады (`RenderType.debugQuads`) рисовать до текста — `font.drawInBatch` берёт другой тип рендера,
  и ранее полученный `VertexConsumer` становится недействительным (падение «Not building!»).

## 5. Статус проверки
| Часть | Статус |
|---|---|
| core | `coreTest`: свои цели пушки (`forMount`, `sensorUnion`, копия, чистка); шаг колеса (0.1 до 1, дальше 1, Shift x10, «нет»); окно скорости по типам; перехват - путь по тикам, встреча снаряда в лоб (промах 0.000, сближение 11 бл/тик), взвод, порядок «раньше долетит» и уровни; 100 % промахов < 1 блока (пушки, автопушки, квадр. сопротивление); быстрые цели 30–80 м/с: попадание за ≤ 5 тиков ожидания 99.6–100 %, оценка промаха совпадает с эталоном; разворот на 5 бл/тик — скорость восстанавливается за 6 тиков; ракеты 0 % → 100 % после обучения |
| Игровые тесты (101) | все проходят: метки и сторона карты (`monitorPinsAndSide`: 1e6 принят, больше - обрезано, метки ставятся/правятся/убираются, не больше 32, сохраняются); линк не уезжает с отражателем (`radarLinkDoesNotStickToDish`); исключение с монитора (`monitorExclusionStopsFire`: исключение - 0 попаданий, повтор - огонь, цель ушла с радара - исключение снято через ~54 тика, вернулась - попадание); разные цели (`TargetVarietyGameTests`, автопушка: зомби и железный голем с ИИ, фантом, гаст, ифрит, пчела, прыгающий большой слизень, цель на 24 блока выше, батарея на башне 12 блоков, за стеной - огня нет и `BLOCKED_TERRAIN`, стену убрали - попадания); дальняя скорострельная большая пушка CBC-AT по цели в 150 блоках - 69 % (82 % без разворотов, остальное - разброс CBC-AT); цель 60 м/с в лоб - 86 % (100 %); свои цели пушек (`SettingsGameTests.perMountTargets`: у вычислителя сущности выключены, пушка A - только мобы, B - только сущности уровня 5: A ведёт хаска и никогда лодку, B - лодку и никогда хаска, настройки сохраняются); окно скорости по типам (`SettingsGameTests.speedWindowPerCategory`: у мобов «от 5 м/с» - хаск 3 м/с не цель, у сущностей - не влияет; перенос старого окна и секунд); перехват (`InterceptGameTests`, автопушка 4 ствола, пулемётные патроны, с CBC Interception: болванки 100 м/с с 90 блоков - 6 из 6 сбиты в воздухе за 15 патронов; 160 м/с с трёх сторон - 6 из 6 за 10; ракеты CBC-AT под тягой - 5 из 5 за 10; цели только с плоского радара CR - 5 из 5, призрачных треков нет; падающие в 45 блоках - не цели при «угрожающих», цели при «все чужие»; запущенные из своей зоны и снаряд своей большой пушки - свои; пулемётные пули не отслеживаются, классификация снарядов CBC, CBC-AT, Neo Warfare; без мода перехвата - огня нет, в сводке «нет мода»); наследование (`IffGameTests`: без типа «враждебные» хаск в зоне поражён, корова цела; все типы выключены, корова в «всегда» - цела, хаск поражён; все типы, `#minecraft:undead` в «никогда» - хаск поражён; имя транспондера - имя трека корабля Sable; монитор показывает наземный транспондер с именем как своего, фильтр прячет, значки и подписи сохраняются, имя чистится до 32 символов); питание (`EnergyGameTests`: вычислитель без FE - «нет энергии», огня нет, с FE - попадание; блок памяти: 17-я запись не действует при 16, блок памяти без питания не считается, с питанием - 32 и огонь; транспондер теряет зону без питания; монитор погашен без питания, энергия в один блок стены включает экран; творческий источник Crafts & Additions питает вычислитель); зоны (`IffGameTests`: враг в зоне радиуса не цель, вне зоны - цель; «сублевел и расстояние» на земле - шар; моб на палубе корабля свой; охранение в 4 блоках свой при «сублевел и расстояние» 8 и чужой при «сублевел»); свой-чужой (`IffGameTests`: белый список кодов - транспондер 1234 бережёт корову, список сменили - огонь; чёрный список - код 666 открыт, 777 бережёт; радиус 12 бережёт, 3 - нет; режим «сублевел» на земле ничего не защищает; коды не утекают в обновления блоков, чужой игрок их не меняет, код чистится, радиус ограничен; корабль Sable с чужим кодом поражён, со своим - нет); сети линков (`LinkGameTests`: линк предметом как в игре - новая сеть, настройка ПКМ по линку, станок в 29 блоках, сброс Shift+ПКМ; сигнал 15 отключает и снятие подключает; монитор Create Radars - выбор цели наводит вычислитель без радара; свой монитор - назначение только своей сети и снятие; клик по дальней цели на карте - пушка переходит с ближней и попадает в назначенную, по ближней больше не стреляет - `monitorDesignationIsEngaged`); цели (`TargetGameTests`: мирная корова - только белым списком, чёрный список по тегу, лодка разбита, игрок в выживании по имени в белом списке, «Разобрать» - не собирает снова и сохраняет, «Собрать»); зона транспондера - враг в зоне поражён, своя корова цела; контроллеры (`ControllerGameTests`: без контроллеров - «нет контроллера наводки», огня нет; контроллеры, поставленные игроком предметом, 64 об/мин - поворот ровно 2.4°/тик, 62°, попадание); линк (`LinkGameTests`: станок в 29 блоках подключён предметом как в игре, станок без линка никем не управляется; монитор на плоском радаре CR показывает цели, назначение идёт только вычислителю на линке монитора); радар CR на линке (без него «нет радара»); время стояния; настройки (`SettingsGameTests`: очереди ровно по N с паузой - автопушка, роторная, средняя; одиночный; типы целей; статичные; сброс после 5 выстрелов; «наиболее дальняя»; переключение после 3 выстрелов); большая пушка, автопушка, неподвижная цель, транспондер, ракеты CBC-AT, корабль Sable, корабль со своим транспондером; без вращения на контроллерах — нет наведения и огня; быстрые цели (не в момент разворота / все): автопушка 70 м/с 97 % / 85 %, 100 м/с 100 % / 78 %, пикирование 70 м/с 100 % / 86 %; три большие пушки по кораблю 70 м/с — 2–3 из 3; пушки из сборки пользователя против цели 70 м/с: стальная автопушка 3 ствола 100 % / 83 %, бронзовая роторная Neo Warfare 96 % / 75 %, незерстальная средняя Neo Warfare 100 % / 73 %, стальная большая со скорострельным затвором и 4 нарезными CBC-AT 100 % / 79 % |
| Комбинации | CBC 5.11.7 + CR 5.0.1 + CBC-AT 0.1.4c + cbcatfix 1.1.4 + CBC Neo Warfare 0.0.6v (`-PfcsExtraMods`); CBC 5.11.7 + CR 5.0.1 (+ CBC-AT и без него, `-PfcsNoCbcAt`); CBC 5.11.3 + CR 0.4.9.4 (+ CBC-AT); только Create + CBC + CR (`-PfcsMinimal`) |
| Чистый сервер | собранный jar + Create + CBC + Create Radars 5.0.1 + RPL на NeoForge-сервере из установщика: загрузка, companion из jar-in-jar |
| Клиент | запущен под Xvfb: модели, текстуры, контроллеры наводки (валы, очки инженера), монитор на радаре CR (подписи имён, без стыков), окно монитора (3 вкладки), экран транспондера (2 вкладки), линк, экран настроек (скриншоты в `docs/`) |

## 6. Что можно улучшить
- Пушки на кораблях Sable (батарея на корабле): поддержано через перевод направлений в плот-координаты,
  но не проверено игровым тестом; наследует ли снаряд CBC скорость корабля — не проверено (`inheritPlatformVelocity`).
- Радары CR на кораблях Sable: позиция радара переводится в мир, но игровым тестом не проверено.
- Контроллеры наводки на станках на кораблях Sable не проверены игровым тестом.
- Монитор на корабле рисует картинку в осях блока (без учёта поворота корабля).
- Распределение целей между пушками (сейчас каждая выбирает сама по настройкам вычислителя).
- Манекен в тестах разворачивается мгновенно: на пару тиков он «стоит», и при «сбрасывать статичные» выпадает из
  целей — тест приоритета это учитывает.
- Lua-версия (`lua/`) — исходный прототип для CC:Tweaked, в моде не используется.
