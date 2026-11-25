# Требования и история функций (бывший раздел 1 CLAUDE.md)

> Заметки разработчика мода для видеоигры **Minecraft** (NeoForge 1.21.1, аддон к Create и Create Big Cannons).
> Все пушки, цели, снаряды, радары и «свой-чужой» здесь — внутриигровые блоки и сущности Minecraft, проверяемые игровыми тестами.
> Файл большой: не выводи его целиком, ищи нужное через `grep -n`.

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
   `-PfcsExtraMods`); без него снаряды видны мониторам, но не цели и вкладка «Перехват» выключена (с 1.0 Release, п. 37). Отслеживаются
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
   текстуры 32x32 (`camera_white/bracket/pan_side/pan_top/ball/face`, генератор - рисование в 8x со сглаживанием; с 1.1 - 16x16, п. 42),
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

33. (2.1) «Обучающие видео (как в Create) для аппаратуры» - сцены Ponder: `client/ponder/FcsPonderPlugin`
   (регистрация - `PonderIndex.addPlugin` из конструктора `FcsMod` только на клиенте, как у Create; тег
   `radar_fcs:fire_control` со всеми блоками) и `client/ponder/FcsScenes` (8 сцен: battery, aim_controllers, network,
   radar_monitor, transponder, camera, turret, gun_drive; `CreateSceneBuilder` - скорости валов). Схемы -
   `assets/radar_fcs/ponder/*.nbt`, генератор `devtools/ponder_schematics.py` (структура NBT, свойства блоков - только
   нужные). Тексты - `radar_fcs.ponder.<сцена>.header/text_N` в файлах перевода, N - порядок вызова `say()` в сцене;
   генератор `devtools/ponder_lang.py` (ru и en, проверяет число `say()` в каждой сцене). **Камера Ponder смотрит с
   северо-запада** - видны грани на север и запад: лицевые стороны блоков в схемах - на север. `SableCompat` не
   спрашивает Sable про миры-модели (`virtual`: уровни `net.createmod.*` - Ponder, схематики), иначе ошибка его API
   выключила бы совместимость на всю игру. Проверка: dev-клиент `-PfcsScreenshot -PfcsPonderOnly`
   (`DevScreenshots.ponderTour`: окно Ponder каждой сцены, перемотка `seekToTime`, кадры `ponder_<сцена>_1..3`).

34. (2.2) «Подробнее объясни настройки в обучалках; обучалки орудия (кастомного Sable) - на подшипниках из Sable;
   пофиксь лаги и ошибки при наведении камерой, стоящей на орудии, на это же орудие»:
   - Сцены: `computer_settings` (все вкладки вычислителя), подробнее `radar_monitor`, `transponder`, `camera`,
     `gun_drive` (+ настройки орудия); `swivel_turret` - своё орудие на `simulated:swivel_bearing` (курс - подшипник
     вверх через шестерни от привода, наклон - подшипник вбок на поворотной части, свой привод, ствол из блоков, прицел;
     регистрируется только при `ModList.isLoaded("simulated")`); старая сцена вращателя - `gun_rotator`. `sayAt` -
     реплика + пауза; `ponder_lang.py` считает `say(`/`sayAt(`. Подсказка вкладки «Сброс» исправлена (счёт у орудия).
   - Камера на постройке Sable: `CameraClient.frame` (`RenderFrameEvent.Pre`) ставит вид каждый кадр в
     `SableCompat.toWorldRender` (`ClientSubLevelAccess.renderPose(partialTick)`), xo = x - раньше вид шёл по
     `logicalPose` раз в тик и дёргался относительно постройки.
   - (2.2.1, по жалобе «ухудшил наведение, а баг остался») радиус 4 и удержание из 2.2.0 убраны. `FcsControl.gunShips`:
     постройки Sable орудия - `SableCompat.cluster` (плоты, где стоят прицел, станки, приводы наклона, вращатели, и
     соседние с ними, габарит +1, 1 шаг) + под-уровни вращателей, без основания (постройки под приводами курса и
     внешними вращателями). Камера на одной из них (`ManualAim.cameraPos`) - `CameraBlockEntity.ignoreGun`: луч
     (`clipShips` с предикатом) и сущности на этих постройках пропускаются 10 тиков - камера смотрит сквозь своё орудие.
     Камера вне орудия, взгляд на эти постройки - `aimsAtItself` (CAMERA_SELF). Тест
     `CameraGameTests.cameraOnGunLooksThroughOwnGun` (две неподвижные постройки `SableTestSupport.staticSub`: A -
     прицел и камера, B вплотную; без `ignoreGun` падает «gun froze»). Тексты Ponder: `%` -> `%%` (`ponder_lang.py`),
     Ponder прогоняет их через `String.format` - иначе «Format error».

35. (2.2.2) «Баг с камерой ещё есть: камера на части орудия, поворотной по двум осям; навёлся на часть орудия -
   зависание, после него ни одна пушка не наводится до перезахода в камеру». До этого проверялся только сервер
   (`controlForTest`), а причины были в клиенте и в загрузке чанков - воспроизведено в настоящем клиенте
   (`-PfcsScreenshot -PfcsCamView`: `DevScreenshots.camView` входит в камеру пакетами клиента, смотрит на цель, на
   внешнюю часть своего орудия, сквозь свой ствол и снова на цель, делает паузу клиента 1.5 с; сторож
   `fcs-camview-watchdog` пишет стек потока рендера или сервера при остановке дольше 400 мс). Найдено и исправлено:
   - **Клиент.** Оверлей расстояния (`CameraClient.overlay`, каждый кадр) звал `SableCompat.clipShips(..., mc.level, ...)`,
     тот - `level.clip` с лучом в координатах плота. Sable переписывает `clip` (см. раздел 4): конец луча, попавший в
     плот, «переводится в мир», второй - нет, луч выходит длиной в миллионы блоков - 4-7 секунд на кадр, пока взгляд
     пересекает габарит любой постройки. Теперь луч идёт через `SableCompat.plain(level)` (обёртка-`BlockGetter`, не
     `Level` - Sable её не трогает), `clipWorld` (только блоки мира, мировые координаты), `clipShips` сам заменяет
     `Level` обёрткой и не трассирует лучи длиннее `MAX_RAY` 1024 и с позой-мусором; расстояние считается раз в тик
     (`CameraClient.lookDistance`), постройки своего орудия пропускаются как на сервере (`Ign` в теге камеры).
   - **Потеря управления.** `handleControl` сверял игрока с `controller(now)` - после паузы дольше `TIMEOUT` (клиент
     завис, лаг) это null, и пакеты молча отбрасывались до перезахода. Теперь `acceptsControl(who)` (тот же игрок,
     камеру никто не занял): следующий пакет продолжает управление; настоящий отказ шлёт клиенту `Kick`.
   - **Сервер, луч.** `FireSafety.terrainBlocked` и взгляд камеры шли через `level.clip`/`getBlockState`, а те на
     сервере догружают и генерируют чанки прямо в тике (точка взгляда «в небо» - траектория на 384 блока за край
     загруженного). Теперь `SableCompat.plain` на сервере = `ReadyBlocks`: чанк только готовый
     (`ServerChunkCache.getChunkNow`, плоты Sable он тоже отдаёт), иначе - пустота. `ProjectileTracking.loaded` - тоже
     `getChunkNow`; точка удара на постройке переводится из координат плота (`SableCompat.toWorld`).
   - **Выход из мира.** Сервер зависал на «Saving worlds» (см. раздел 4, «выгружаемый чанк»): `CbcMount.isValid()`
     читал блок станка из мира. Теперь сначала `level.isLoaded`; заодно `hasRadarControllers` и обращение к камере
     в `FcsControl` - под `isLoaded`. Спуск, оставшийся зажатым после перезахода (темп огня CBC хранит в пушке, а
     станок при совместной выгрузке уже не отпускается), вычислитель берёт на себя при первом захвате
     (`prepareMount`: `fresh && d.firePowered()` -> `m.triggerOn`). Блок станка сменился (чанк станка перезагрузился,
     пока вычислитель не тикал) - `MountState.bind` сбрасывает `locked/claimed/triggerOn`: валы блокируются заново.
   - `CameraBlockEntity.control` ловит сбой луча (точка - по направлению взгляда, предупреждение раз в 10 с); набор
     пропускаемых построек неизменяемый - сущность без постройки проверяется через `onShips` (`Set.of().contains(null)`
     бросает NPE).
   - Тесты: `CameraGameTests.cameraControlResumesAfterPause`, `shipRayNeverGoesThroughSableClip`, `raysDoNotLoadChunks`,
     `InfraGameTests.mountInUnloadingChunkIsNotRevived`, `staleTriggerReleasedOnClaim` (два последних без исправлений
     падают - проверено). `-PfcsTests=подстрока[,подстрока]` - запустить только часть игровых тестов
     (`DevAutostop.onAboutToStart` чистит `GameTestServer.testFunctions`).

36. (2.3) «Настраиваемый (через интерфейс ползунок как на торсионной пружине) макс угол, для каждого направления
   вращения, поворота в приводах орудия и контроллерах наводки (от 1 до 360, по умолчанию неограничен)»:
   - `core/AngleLimit`: `FREE` = 361 (∞, правый край ползунка), `plan(turned, delta, neg, pos)` - накопленный угол от
     нуля (с оборотами) и нужный поворот: из вариантов `delta + 360k` - ближайший внутри [-neg, pos] (возможно, в обход
     запретного сектора), иначе - к пределу, ближайшему к цели по кругу, `reachable = false`; `planLinear` - наклон
     (зажим); `unwrap`. Тест в `coreTest` (`angleLimits`).
   - (2.3.1, «сделай независимыми макс градусы для каждой стороны») `block/AngleLimits` - два независимых ползунка
     `block/AngleLimitBehaviour` (наследник `ScrollValueBehaviour`) - **по значку на направление**: свои `BehaviourType`
     (`FIRST`/`SECOND`; Create ищет поведение доски по типу) и `netId` 7300/7301 (по нему пакет Create находит поведение),
     значки на боковых гранях (`Sided`, не на оси FACING, масштаб 0.4): курс - слева ◀ влево и справа ▶ вправо, наклон -
     сверху ▲ и снизу ▼; доска `ValueSettingsBoard(…, 361, 45, 1 ряд)`, заголовок «Предел поворота: ◀ влево». NBT
     `AngleLimit0/1` (2.3.0 - массив `AngleLimits` в одном значке, читается). ⟲/⟳ шрифт Minecraft рисует кружками -
     только ◀▶▲▼. Подпись подсказки (`label`) обновляется в `isActive()` (привод переключают курс/наклон).
   - Контроллер наводки: ноль курса - `CbcMount.baseYaw()` = **`initialOrientation` собранной пушки** (2.3.1; без пушки -
     HORIZONTAL_FACING). Так считают нейтраль CBC и FPC; ствол на компактном станке бывает собран против стороны
     станка (зеркальные станки с двух сторон постройки) - с нулём от HORIZONTAL_FACING сектор оказывался за спиной, пушка
     разворачивалась на 180° (жалоба пользователя; тест `FirepowerGameTests.compactMountReversedCannonLimit` без
     исправления падает). Курс Minecraft растёт вправо; `trackYaw(yaw, zero)` (NBT `Turned`, `TurnedZero`) накапливает
     обороты по `cmdYaw`, другой ноль - счёт заново. `FcsControl`: наклон -
     `planLinear(0, wantPitch, вниз, вверх)`, курс - `plan(turned, wrap(want - cmd), влево, вправо)` и шаг линейно
     (`stepLinear` к `cmdYaw + delta`, не кратчайшим путём).
   - Привод орудия: ноль курса - направление наведения при первом `trackYaw` после включения предела (NBT `YawZero`,
     `Turned`; сняли все пределы - ноль забыт), угол - `atan2(x, z)` в осях основания (+ влево, как поворот вокруг
     «верха» в `Turret.drive`): `plan(turned, yaw, вправо, влево)`. Наклон - от горизонта основания. `Turret.outOfArc`.
   - Выбор цели: решение вне пределов (`Turret.inArc` / `FcsControl.mountInArc`) - следующая цель; если вне все -
     первая (стоит у предела, `OUT_OF_ARC`). Камера - так же.
   - Тесты `LimitGameTests`: `controllerYawLimitKeepsSector` (±30°: ближняя цель слева не берётся, дальняя по курсу
     поражена, пушка не выходит за 30°, без дальней - у левого предела «вне сектора», предел сняли - попадание),
     `controllerPitchLimit` (вверх 5°: ствол не выше 5°), `gunDriveYawLimit` (влево 30, вправо 20: стоит на 20°, предел 60 -
     попадает). Проверено в клиенте (`-PfcsScreenshot -PfcsLimitUi`, `DevScreenshots.limitUi`: значок с подсказкой
     «Удерживайте для изменения», доски контроллера и привода наклона).

37. (1.0 Release - первая публичная версия, «приписка релиз в названии»: `mod_version=1.0-Release`, jar
   `radar_fcs-1.0-Release.jar`; внутренние версии 0.1-2.3.1 - в журнале README). Для публикации на CurseForge:
   - Лицензия MIT - `radar_fcs/LICENSE` (в jar как `LICENSE_radar_fcs`, `build.gradle` jar `from('LICENSE')`); LICENSE в корне
     репозитория (LGPL) - шаблона ModDevGradle. `mod_description` переписан (свои радары давно удалены).
   - Иконка: `devtools/mod_icon.py` (PIL, рисует с нуля в 2048 и уменьшает до 512): круглый значок в стиле образца
     пользователя (синяя сетка, белое кольцо с тёмной каймой) с вычислителем в изометрии (латунь, заклёпки, экран радара
     с разверткой и отметками, три лампы). `src/main/resources/logo.png` (`logoFile` в `neoforge.mods.toml`) и
     `release/icon.png` (страница мода).
   - «Без нашего CBC Interception перехват и его страница не работают»: настройка `interception.requireInterceptionMod`
     убрана, `FireControlBlockEntity.interceptAllowed()` = мод установлен и включён (`Interception.available()`); снаряды
     без мода - не цели (`TargetSensor.wanted`, раньше всего, и для назначенных). Тестам - `FcsDev.interceptWithoutMod`
     (огонь по снарядам без мода) и `FcsDev.hideInterceptMod` (как будто мода нет). Экран вычислителя: вкладка «Перехват»
     с тёмной подписью, её элементы выключены (`interceptWidgets`, `active` каждый кадр - сводка «Intercept» приходит
     позже, чем строится экран; смена - `rebuildWidgets`), серая пелена поверх (`translate z 300` - иначе под виджетами) и
     надпись «Требуется дополнительный мод CBC Interception»; на вкладке «Пушки» переключатели снарядов и ракет выключены
     с подсказкой. Тест `InterceptGameTests.noFireWithoutInterceptionMod`: мод спрятан - трек есть, целей и выстрелов нет,
     в сводке нет перехвата; мод вернули - огонь. Без мода CBC Interception и с CBC-AT 0.1.4c на CBC 5.11.7 сервер
     тестов падает, когда болванка бьётся о землю (`NoSuchMethodError PartialBlockDamageManager.damageBlock` - известная
     несовместимость CBC-AT, не наша): такие прогоны - с `-PfcsNoCbcAt`. `-PfcsSettingsOnly` - снимки экрана
     вычислителя (`fcs_settings_0..8`) и выход.
   - Галерея страницы мода - `release/gallery/*.jpg` (1920x1080) и `captions.md` (подписи EN/RU). Снимаются
     `-PfcsScreenshot -PfcsGallery -PfcsWidth=1920 -PfcsHeight=1080` (+ `-PfcsPonderOnly`, `-PfcsCameraOnly`): в режиме
     галереи нет руки, хотбара и прицела (`DevScreenshots.galleryHud/galleryHand`), крупные ракурсы `galleryShots`
     (`gal_*`), перед видом из камеры рука пустая. Для них в `run/options.txt` ставили guiScale 3, графику «детально»,
     прорисовку 8 (потом вернули прежнее).

38. (1.1; сначала собиралась как «1.1-Release», потом пользователь попросил выпустить всё вместе с п. 39 как «1.1») «Блок через ресурсы Flight Control, только с установленным Flight Control; мост между Flight Control и
   нашей системой: задаёт автопилоту цель радарной системы; режим поворота корпуса в сторону цели (через индикатор
   направления), режимы только дальность и дальность + высота с вписываемыми параметрами»:
   - **Create: Flight Control** (`create_flight_control`, Modrinth `create-flight-control`, лицензия ARR - только его API,
     код не копировать). Компиляция против 0.8.3 (`flight_control_version` = id версии Modrinth), в dev-сервере с
     geckolib (`-PfcsNoFlight` - без него). API, которым пользуемся, в 0.8.4b тот же (сверено `javap`).
     Как устроен FC: регуляторы (стабилизаторы ориентации/высоты, регуляторы скорости) - `ace.flight.api.WrenchContributor`:
     компьютер управления полётом в шаге физики Sable зовёт `contributeWrench(subLevel, handle, dt, force, torque)` у
     связанных (`isLinkedTo`, `isContributing`), сила (масса x ускорение) и момент (тензор инерции x угловое ускорение) -
     **в осях корабля**, гравитацию компьютер учитывает сам, затем раздаёт всё двигателям. Связь - инструментальным
     пистолетом: регулятор выбирается пистолетом (любой `WrenchContributor`), ПКМ по компьютеру -
     `addController` + `setLinkedComputer`. Нос корабля - `ForwardIndicatorLinkTarget` (индикатор выбирается
     пистолетом, ПКМ по блоку-цели - `tryHandleToolGunLink` в `useItemOn` блока), `BodyForwardFacing` - направление в
     осях корабля (модель индикатора смотрит наоборот: вперёд = `FACING.getOpposite()`). Скорость Sable - бл/с,
     `SableVelocitySemantics.controlLinearVelocityWorld`, положение - `AttitudeTargetUtil.getLogicalPosition`.
   - `core/Autopilot` (чистая математика, `coreTest` `autopilot`: симуляция погони за целью 6 бл/с): ошибка курса вокруг
     +Y, угловое ускорение (ошибка -> скорость поворота до 0.8 рад/с -> ускорение), ускорение по дальности (сближение
     `POS_GAIN` x ошибка до предела скорости + скорость цели, отклик `VEL_GAIN`, не больше `ACC_MAX`), режимы `OFF`,
     `DISTANCE` (только по горизонтали), `DISTANCE_ALTITUDE` (+ высота над целью).
   - `block/AutopilotBlockEntity` (без классов FC; `LinkKind.AUTOPILOT`): своя аппаратура сети (радары, мониторы, мониторы
     CR, вычислители) раз в секунду, цель раз в 2 тика - назначенная на мониторе сети, на мониторе CR, у вычислителя, иначе
     цель, по которой вычислитель ведёт огонь (`FIRE`/`AIMING`); свой `TargetSensor` (все типы, назначенная - напрямую),
     свой корабль не цель. Для физики - неизменяемая `Goal` в volatile (положение и скорость цели в бл/с на тик,
     настройки; `posAt` - экстраполяция). Состояние (`ACTIVE`, `PAUSED` - редстоун, `NO_SHIP`, `NO_COMPUTER`,
     `NO_TARGET`) и цель - в данных клиента для очков инженера. Настройки - `Autopilot` NBT, экран
     `client/AutopilotScreen` (EditBox числами), `net/AutopilotPayloads` (Open/Save, 8 блоков, на корабле - от мировой
     позиции).
   - `compat/flight/` (грузится только при FC): `FlightBridge` (регистрация блока, предмета, BE из
     `FcsRegistry.register`, если `Mods.FLIGHT_CONTROL`; вкладка творчества и Ponder - так же), `FlightBridgeBlock`
     (`HorizontalDirectionalBlock`, пистолет FC в руке - `SKIP_DEFAULT_BLOCK_INTERACTION`, чтобы сработал сам пистолет;
     при сломе - отвязать индикатор и `removeControllerAt` у компьютера), `FlightBridgeBlockEntity`
     (`WrenchContributor` + `ForwardIndicatorLinkTarget`; без индикатора нос - лицевая сторона блока; момент только
     вокруг мировой вертикали - не спорит со стабилизатором ориентации в режиме без курса).
   - Без FC: рецепт и лут - `neoforge:conditions` `mod_loaded`, в теге кирки - `required: false`; язык и модели есть всегда.
   - Тест `FlightGameTests.bridgeSteersToDesignatedTarget` (вспомогательный `FlightTestSupport` - классы FC): мост на
     неподвижной постройке Sable, монитор назначает хаска к востоку: нос на север - момент вправо (-Y) и сила к цели
     (+X), без вертикали; индикатор «вперёд = восток» - момента по курсу почти нет (4 %); «дальность и высота» - сила
     вверх; связь с компьютером - `ACTIVE`; редстоун - `PAUSED`; мост сломан - компьютер его забыл. Полёт настоящего
     корабля с двигателями не проверялся (физика Sable в игровых тестах корабль не двигает, см. раздел 4).
   - Dev-клиент: `-PfcsScreenshot -PfcsBridgeUi` (`DevScreenshots.bridgeUi`: блок с индикатором и компьютером FC, очки
     инженера, экран), сцена Ponder `flight_bridge` (`-PfcsPonderOnly`).

39. (вошло в 1.1; промежуточно собиралось как «1.2») «Добавь мосту возможность включать отклонения (рыскание, крен и тангаж) для ведения
   цели»: `Autopilot.attitudeAccel(нос, верх, угловая скорость, направление на цель, оси, пределы)` - угловое ускорение
   в мире по трём осям, каждая - `axisAccel` (ошибка -> скорость поворота до 0.8 рад/с -> ускорение): рыскание вокруг
   мировой +Y (ошибка курса, как в 1.1); тангаж вокруг «вправо» = нос x +Y (угол места цели, не больше `maxPitch`);
   крен вокруг носа (+ - вправо; цель: -ошибка курса / 30° x `maxRoll`, т.е. крен в сторону поворота, нос на цели -
   ровно). Выключенные тангаж и крен - цель 0 (ровно): без этого при повороте с поднятым носом корабль заваливался по
   крену (найдено в симуляции `coreTest`); выключенное рыскание - без момента; все три выключены - момента нет.
   Настройки `Pitch`, `Roll`, `MaxPitch` (1-89, 45), `MaxRoll` (1-80, 30) рядом со старым `Heading` (= рыскание), в `Goal`;
   экран - три переключателя и два поля. `coreTest` `attitude`: тело с единичной инерцией - курс и угол места цели
   выходят в 0 ошибку, крен в повороте влево отрицательный (до ~32° при пределе 30°), в конце ровно; предел тангажа 10° -
   нос на 10°. Игровой тест моста: + крен - момент по -Z при носе на север и правом повороте, + тангаж - нос вниз при
   цели ниже.

40. (вошло в 1.1; промежуточно 1.1.1) «Не работает, почини как наведение так и движение; опиши минимальный рабочий сетап». Игровые тесты физику
   Sable не крутят, поэтому собран **настоящий полёт** в dev-клиенте: `-PfcsScreenshot -PfcsFlight=<конфигурация>`
   (`DevScreenshots.flight` + `FlightScene`: палуба 5x5 на высоте base+30 (не выше 200 - иначе за пределом мира блоки не
   ставятся), компьютер FC (`setWorking(true)`, `workDryRun = false` - по умолчанию компьютер выключен и в предпросмотре
   тяги!), 4 `creative_smart_vector_thruster` соплом вниз (`addThruster` + `setLinkedComputer`, как пистолет), мост,
   индикатор, линк; хаск на столбе в 60 блоках, монитор сети; `FcsDev.radarless`; лог каждые 10 тиков - позиция, ошибка
   курса, дальность, `workTargetF/T` компьютера; слова конфигурации: `alt`, `off`, `pitch`, `roll`, `direct`, `nodes` -
   без назначения, `move` - цель идёт на север 3 бл/с). geckolib - в `gametestSable` (классы двигателей FC).
   Найдено: (1) мост с обычным компьютером работал (нос на цель, дальность), но в «только дальность» высоту не держал
   никто - компьютер учитывает тяжесть точно, корабль без стабилизатора высоты уходил вверх 6 бл/с бесконечно;
   (2) **творческий компьютер FC** (`CreativeFlightControlComputerBlockEntity`) не принимает `WrenchContributor` вовсе
   (встроенные регуляторы), с ним мост молчал; (3) без назначенной цели мост ничего не делал.
   Исправлено: «только дальность» держит высоту, на которой взята цель (`holdY`/`holdFor` в
   `FlightBridgeBlockEntity`, `Autopilot.accel(..., holdY)`); настройка **`direct`** - мост реализует
   `BlockEntitySubLevelActor.sable$physicsTick` и сам прикладывает импульсы (оси корабля, `applyLinearAndAngularImpulse`,
   + вес `-gravity x mass`), компьютеру тогда ничего не отдаёт (`isContributing` false); без назначения - ближайшая
   чужая цель сенсора (`AutopilotBlockEntity.nearest`: не свои, не снаряды и прочие сущности, мобы - только враждебные).
   Проверено полётом: через компьютер и напрямую нос на цели < 1°, дальность 24.7-24.9 при 24, высота держится, за целью
   3 бл/с идёт. С тангажем «вертолёт» (двигатели вниз, конус ±30°) с наклонённым носом почти не может тормозить и
   проскакивает цель - ограничение такого корабля, в README.

41. (вошло в 1.1; промежуточно 1.1.2) «Для движения нужен регулятор скорости, для наклонов - регулятор угловой скорости, и чтобы прочее не мешало,
   как автопилот этого мода (Flight Control), который при наличии приборов сам управляет постройкой, забив на
   стабилизаторы». Мост больше не даёт силу сам (`contributeWrench` пуст, `isContributing` false; `direct` и
   `sable$physicsTick` из 1.1.1 убраны) - он работает через **навигационную аренду Flight Control**, как навигационный
   терминал: каждый тик (`AutopilotBlockEntity.drive` -> `FlightBridgeBlockEntity.steer`) `snapshotControlNetwork(мост)`
   (регуляторы компьютера: индекс, вид `NavigationControllerKind`, `nodeIncarnation`, возможности, локальная власть
   редстоун-прокси), первый пригодный `BODY_VELOCITY` (регулятор скорости) и `BODY_ANGULAR_VELOCITY` (угловой скорости):
   домены `participation` = `REQUEST_ON` и оси `BODY_VELOCITY_X/Y/Z` / `BODY_ANGULAR_VELOCITY_X/Y/Z` = `Axis.track(цель)` -
   цели в осях постройки (`AttitudeTargetUtil.worldToBody`): `Autopilot.desiredVelocity` (бл/с) и `desiredRates` (рад/с,
   как `attitudeAccel`, но скорость поворота); регулятор сам обрезает цель своими пределами из интерфейса (по умолчанию
   5 бл/с и 0.2 рад/с). Спорящие (`ATTITUDE_HOLD`, `ALTITUDE_HOLD`, `DISTANCE`, `MOUSE_FLIGHT`, лишние регуляторы скорости)
   - `participation` = `REQUEST_OFF`. Аренда: `NavigationLeaseIdentity(измерение, structureId из
   getNavigationVehicleStateSnapshot, позиция моста, UUID)`, `tryAcquireAndPublish` / каждый тик `renew` + `publish`
   (ревизия +1) / `release` (нет цели, пауза, сломан, выгружен). Срок аренды 0.75 с. **Компьютер выдаёт аренду через свой
   API только навигационному терминалу** (`validNavigationIdentity`: блок по позиции - `NavigationTerminalBlockEntity`),
   поэтому мост берёт его `navigationLeaseBroker` рефлексией и зовёт распределитель напрямую, предварительно
   `snapshotControlNetwork` (внутри `prepareNavigationBroker`: топология, рабочий режим). Состояния `COMPUTER_OFF`,
   `NEED_VELOCITY`, `NEED_ANGULAR`, `NEED_COUPLERS`, `BUSY` (аренда не взята: терминал или прокси). Творческий
   компьютер не поддерживается (встроенные регуляторы, свой `CreativeNavigationBridge`).
   Тест `FlightGameTests.bridgeLeasesFlightControllers` (команды аренды читаются из `broker.effectiveFrame()`):
   без регуляторов - `NEED_COUPLERS`; с ними - оба `REQUEST_ON`, стабилизаторы `REQUEST_OFF`, скорость X > 0 к цели,
   поворот Y < 0 вправо; индикатор «восток» - поворота нет, тангаж - нос вниз (Z < 0); «дальность и высота» - Y > 0; без
   назначения - ближайшая цель; редстоун - аренда отдана; мост сломан - компьютер забыл. Полёт (`-PfcsFlight=distance-holds-move`,
   `FlightScene`: регуляторы с творческими моторами под ними, пределы 15 бл/с и 0.6 рад/с; `holds` - стабилизатор
   ориентации «держать текущую» и высоты «на 20 ниже»): активны 2 регулятора, нос на цели < 1°, дальность 22.7-23 при 24,
   высота держится, за целью 3 бл/с идёт. С тангажем «вертолёт» по-прежнему проскакивает цель (физика корабля).

42. (1.1, итог) «Перепроверь оптимизацию и баги, переделай локализации, собери всё в 1.1»: `mod_version=1.1`,
   промежуточные 1.1.1/1.1.2 слиты в 1.1 (README, `release/changelog_1.1.md` - против 1.0 Release).
   - Мост: `AutopilotBlockEntity` шлёт `sendBlockUpdated` только при смене сводки (состояние, цель, дальность до метра),
     без `setChanged`; ближайшая цель (`nearest`) - только враждебные мобы, игроки, корабли, не свои, не свой корабль, не
     исключённые на мониторах сети (`exclusions()`), с гистерезисом 1.3x к прошлой; назначенная/цель вычислителя -
     снаряд не берётся; сенсор моста снаряды и ракеты не ведёт (`allTargets`). `FlightBridgeBlockEntity.subLevel` -
     кэш постройки Sable (обновление раз в 20 тиков или если `isRemoved`).
   - Тексты (`lang/*.json`, обновляются скриптом из сессии; Ponder - `devtools/ponder_lang.py`): «корабль» вместо
     «сублевел» (типы целей, зоны транспондера - «Зона: корабль…»), «Create: Radars», тире «—», «бл» без точки,
     «рыскание» (не «рысканье»), названия Flight Control как в его переводе (en: Attitude/Altitude Stabilizer, Velocity
     Coupler, Angular Velocity Coupler, Distance Coupler, Flight Control Terminal, Flight Control Tool Gun; ru:
     стабилизатор ориентации/высоты, регулятор скорости/угловой скорости/дистанции, терминал управления полётом,
     инструментальный пистолет). en - американское написание (color), «fire control computer» вместо «fire control»,
     Right-click/Left-click вместо RMB/LMB. Режим моста `OFF` - «только поворот». Затем («не меняй версию; названия можно
     менять на более правильные, кроме предметов других модов») русские названия: «Передатчик радарной сети» / «Передатчик
     орудийной сети» (было «радарный/орудийный линк», в текстах «линк» -> «передатчик»), «Механизм горизонтальной/вертикальной
     наводки» (было «контроллер»); «контроллеры Create: Radars» и названия Flight Control не менялись. Версия осталась 1.1.
   - Текстуры: сначала («в стилистике Minecraft и Create, разрешение как у Create») 16x16; затем («всё-же 32x32,
     стилистику сохрани, но более плавные переходы цвета и разнообразь палитру - выглядят как из 2013») - **32x32**,
     генератор `devtools/textures.py`: лестницы цветов `ramp` с оттенками (тени холоднее/краснее, блики теплее),
     градиенты (`grad`, радиальные), лёгкий шум `noise`, свечение ламп `glow`, рамки `bevel_frame` (латунь/сталь, свет
     сверху-слева). UV моделей прежние (0..16 = пиксели x2). Камера: попытка «в стиле ока Эндера» (корпус из обсидиана,
     око внутри, око Эндера в рецепте) пользователю не понравилась - вернули белую камеру наблюдения 1.0 Release,
     перерисованную в новом стиле (`PLASTIC`: тени холодные, блики тёплые; объектив `LENS`), и старый рецепт (стеклянная
     панель). `radar_monitor_front` - как в 1.0 Release (не менялся с 1.1.0 внутренней нумерации; пользователь просил не трогать).


43. (1.1-Test; пользователь: «версию пока сделай 1.1 Test и не меняй, пока я не попрошу» - `mod_version=1.1-Test`,
   как 1.0-Release) **Стыковочный контроллер** (`docs/dev/task-docking.md`). Ответы пользователя: один блок с
   переключателем роли, связь с портом - **инструментальным пистолетом Flight Control**; если все порты с номером
   заняты - выбор в настройке моста («ждать» / «не лететь»); «стыковка по редстоуну» - сигнал на мосту значит «на
   стыковку», а не пауза; порты выдвигают сами контроллеры.
   - Порт Create Aeronautics - это `simulated:docking_connector` из Create Simulated (вложен в jar Aeronautics).
     API «подлетел - соединись» нет: порт выдвигается по сигналу (`POWERED`, ванильное свойство), выдвинутые порты
     ближе 4 бл (`DockingConnectorPair.canConnect`: |d|² < 16) и навстречу друг другу притягиваются магнитом и
     сцепляются (`isLocked()`, `FixedConstraint` Sable), без сигнала - расстыковка. Конец порта - центр блока +
     1.5 по `FACING`. `compat/DockingPorts`: блок по имени, `isLocked` рефлексией, выдвижение - `setBlock(POWERED, флаг 2)`
     каждый тик, пока нужно (порт сам перечитывает сигнал только при изменении соседей); команда кончилась - свойство
     возвращается к `hasNeighborSignal`.
   - Блок `block/DockingControllerBlock(Entity)` (`DockingBlocks.register` - только с Flight Control; рецепт - с FC и
     Simulated; лут, тег кирки - с условиями), свойство `plug` - другая лицевая текстура (`docking_controller_port/plug_front`,
     `devtools/textures.py: docking_front`). Экран `client/DockingControllerScreen` (роль, номер 0..9999, связь,
     состояние), `net/DockingPayloads`. `LinkKind.DOCKING`. Связь с портом - `compat/flight/DockingToolLink` (событие
     `RightClickBlock` с приоритетом HIGH раньше обработчика Flight Control: ПКМ пистолетом по контроллеру - выбран, по
     порту на той же постройке не дальше 16 бл - связаны, Shift+ПКМ по контроллеру - снято; выбор по игроку, минута).
   - Роль «порт»: `dock/DockingRegistry` - номер, сети контроллера, мировой конец и направление порта, скорость (порт на
     корабле), сцеплен ли; обновление раз в 10 тиков, у занятого - каждый тик; выгруженный чанк - запись остаётся с
     последним положением (корабль летит к ней; после перезапуска сервера - пока чанк порта не загрузится). Брони:
     штекер (ключ - его позиция) продлевает каждый тик, срок 40 тиков, флаг «выдвигать» - порт выдвигается сам.
     `choose`: прежний порт, если свободен для этого штекера; иначе ближайший свободный; иначе ближайший занятый
     (`free=false`); порты на корабле самого штекера не берутся.
   - Роль «штекер»: стоит на корабле в сети моста; мост находит его в `discover` (тот же корабль Sable).
   - Мост: `dockTrigger` (`Docking.Trigger`: выкл / нет цели - цели нет 2 с / редстоун) и `dockBusy` (`WAIT`/`STAY`) в
     настройках и на экране (`AutopilotScreen`, высота 312). Подлёт - `core/Docking.steer` (без Minecraft): дальний
     перелёт к точке подхода (8 бл перед портом) не ниже начальной высоты (дальше 24 бл по горизонтали), подход к
     точке, разворот (корпус ровно, рыскание штекера навстречу порту; штекер вертикальный - без рыскания), ближний
     подход по оси порта до 1 бл (1.5 бл/с, боковая поправка; разворот хуже 12° или увод > 1.5 бл - назад к точке);
     на ближнем подходе оба порта выдвигаются, сцепку делает Simulated. Наклон штекера к «верху» корабля должен
     совпадать с наклоном порта (±20°) - иначе `DOCK_TILT`. Пристыкован (`isLocked`) - аренда с выключенными
     регуляторами скорости, угловой скорости, стабилизаторами, регулятором дистанции и терминалом мыши (корабль держит
     сцепка). Стыковка кончилась после сцепки - 3 с отход по направлению порта (3 бл/с), потом обычный полёт.
     `FlightBridgeBlockEntity`: общий `lease(...)` для цели, стыковки, удержания и отхода; новые состояния `DOCK_*`,
     `DOCKED`, `DOCK_DEPART`, на очках - «Порт №N, X бл».
   - Тесты: `coreTest` `docking()` - боковой порт с разворотом на 180°, площадка с креном, платформа 2 бл/с, порт занят
     (висит у точки подхода), несовместимый наклон. Игровые `DockingGameTests`: `controllerRegistersAndPowersConnector`
     (реестр, бронь выдвигает порт и снятие задвигает, штекер выдвигает по команде, блок убран - порт задвинут, порт в
     29 бл не связать), `bridgeDocksByNumber` (нет цели - к ближнему №7 с бронью; он занят - к дальнему; оба заняты -
     «ждать» к ближнему без брони, «не лететь» - аренды нет; штекер №3 - к южному; «редстоун»: без сигнала - нет цели,
     сигнал - стыковка, не пауза; сигнал снят - бронь отпущена). Физику подлёта и сцепку игровой тест не проверяет
     (постройка Sable в тесте сама не летит) - нужна проверка в игре.

44. (1.1-Test, версия не менялась) Доработка стыковки и моста: «1) контроллер можно подключать к нескольким портам, у всех
   один номер (для систем на много портов - точнее положение при стыковке); 2) без цели корабль не наводится на другой
   объект, а в зависимости от режима зависает на месте или описывает орбиту вокруг координаты или специального блока;
   3) пока пристыкован - двигатели Flight Control выключены, пушки на корабле неактивны».
   - Несколько портов: `DockingControllerBlockEntity` - до 8 портов Simulated (`Connectors` - массив смещений; старый
     одиночный `Connector` читается), все смотрят как первый (`toggleConnector` -> `FACING`); пистолет: выбор контроллера
     держится минуту, каждый ПКМ по порту - добавить/убрать, Shift+ПКМ по контроллеру - снять все. Порт в реестре и
     штекер в мосту - середина концов (`localTip`) и расстановка `pattern` (от одного из двух самых далёких концов к
     другому). `Docking.steer(..., plugPattern, ...)` + `patternYaw`: у вертикальных порта и штекера (вверх/вниз) корабль
     доворачивает по курсу, чтобы расстановки легли вдоль (ближайший из двух вариантов); ошибка доворота входит в
     ошибку разворота (точка подхода -> ближний подход только при ≤ 5°). Горизонтальные порты - курс и так задан
     направлением, расстановка совпадает сама. Сцеплен - хоть один порт. Разное число портов у штекера и порта не
     проверяется (номер - на совести игрока): совмещаются середины.
   - Без цели: «ближайшая чужая цель радаров» убрана (`nearest`); цели нет (ничего не назначено, вычислители сети не
     стреляют) - сенсор моста не работает. Вкладка «Без цели» экрана моста (`AutopilotScreen`: вкладки «Цель» / «Без
     цели», высота 272): `Autopilot.Idle` - висеть (точка, где цели не стало; `Docking.toward`, корпус ровно, курс
     держится), орбита вокруг координаты (X/Y/Z, кнопка «Здесь» - позиция игрока), орбита вокруг **маяка орбиты**
     (новый блок `orbit_beacon`, только с FC, `LinkKind.BEACON`, в сеть моста передатчиком; может стоять на другом
     корабле - орбита идёт за ним; маяка нет - висит, `IDLE_NO_BEACON`). Радиус (4..2048), высота над центром,
     направление; скорость - предел скорости моста. `Autopilot.orbitVelocity`: касательная + P к радиусу и высоте +
     упреждение к центру `speed²·ORBIT_LAG/r` (без него из-за запаздывания регулятора 0.5 с корабль уходит на 4 бл
     наружу); нос по ходу, если включено рыскание, крен в поворот - если крен. Режим «только поворот» без цели не
     управляет (`NO_TARGET`). Состояния `IDLE_HOVER`, `IDLE_ORBIT`, `IDLE_NO_BEACON`. Стыковка «нет цели» - через 2 с
     висения.
   - Пристыкован: мост выключает компьютер управления полётом (`setWorking(false)`, только если он был включён -
     `isWorkingDefaultEnabled`; компьютер сам отзывает аренды и снимает тягу двигателей) и сохраняет `DockPoweredDown`;
     расстыковка (или мост убран) - `setWorking(true)`. `dock/DockHold` - отметка корабля каждый тик; вычислитель не
     наводит и не стреляет пушками, стоящими на отмеченном корабле (`Status.DOCKED`, «Корабль пристыкован»).
   - Порты Simulated: выдвинутые по команде - повтор и в конце тика мира (`LevelTickEvent.Post`): пластина порта в
     тике блоков сбрасывает `POWERED` у порта, тикающего после контроллера, - клиент видел бы мигание.
   - Тесты: `coreTest` - «two pad ports, yaw by pattern» (доворот 90° по расстановке), `orbit()` (выход на круг 30 из
     точки в 100 бл, обе стороны). Игровые: `controllerRegistersAndPowersConnector` - второй порт, порт на север не
     принят, середина и расстановка в реестре, оба выдвинуты; `bridgeIdleHoverAndOrbit` (висит - скорость ~0; орбита
     точки против/по часовой; маяка нет - висит; маяк в сети - к нему); `dockedShipPowersDown` - **настоящая сцепка**
     порта корабля (вниз) с портом на земле (вверх) за 23 тика: «пристыкован», компьютер выключен, корабль отмечен;
     сигнал снят - расстыковка, компьютер работает. `bridgeLeasesFlightControllers`: без назначения хаск рядом не цель,
     «висит». Пушка на пристыкованном корабле (`Status.DOCKED`) игровым тестом не проверена.

45. (1.1-Test) «1) маякам орбиты - коды для идентификации; 2) при подключении инструментом Flight Control подсвечивать
   подключаемые элементы, как в самом Flight Control; 3) стыковка на несколько портов - правильно на все, по
   необходимости заранее разворачивая корабль».
   - Коды маяков: `OrbitBeaconBlockEntity.code` (до 16 символов, без служебных, `BeaconRegistry.clean`), ПКМ - экран
     `client/OrbitBeaconScreen` (`net/BeaconPayloads`), очки инженера - код. `dock/BeaconRegistry` - маяки с кодом по
     измерению (мировая точка и скорость, на корабле - каждые 5 тиков, на земле - раз в секунду; выгруженный - с
     последним положением). Мост: `orbitCode` (вкладка «Без цели», поле на месте координат в режиме «орбита вокруг
     маяка»): есть код - ближайший маяк с этим кодом в измерении (без учёта регистра, сеть не нужна); пусто - первый
     маяк своей сети, как раньше; кода ни у кого нет - висит (`IDLE_NO_BEACON`).
   - Подсветка: `compat/flight/DockingToolClient` (клиент, только с FC; `ClientTickEvent.Post`): пистолет FC в руке -
     выбранный контроллер оранжевый толстый, его порты голубые с линиями к контроллеру, порт под прицелом - белый
     (с белой линией, если ещё не связан); взгляд на невыбранный контроллер - белый и его связи. Цвета и толщины - как
     у Flight Control (`ToolGunLinkVisualization`: 0xFFAA00, 0x66CCFF, белый; 1/16 и 1/8). Точки на кораблях Sable -
     через `SableCompat.toWorldRender`. Пистолет убран из рук - выбор забыт (`clientForget`).
   - Несколько портов: `Docking.layout` - смещения концов штекеров от их середины поворачиваются так, как повернётся
     корпус, когда штекер встанет навстречу порту, и сравниваются со смещениями концов портов поперёк оси порта.
     Порт вверх/вниз - `matchYaw`: кандидаты «самая далёкая точка штекера -> каждая точка порта» (и 0), соответствие по
     ближайшим, уточнение доворота Прокрустом, выбор по наименьшему промаху (из равных - меньший доворот); порт в стене -
     курс задан, только промах. Доворот идёт в угловую скорость на всём подлёте (корабль разворачивается заранее) и в
     ошибку разворота (ближний подход - когда всё ≤ 5°). `Command.layoutError/layoutOk` (число портов равно и промах
     ≤ 0.6 бл), состояние `DOCK_LAYOUT` у точки подхода и на ближнем подходе, если расстановки не совпадают.
     `DockingRegistry.PortInfo.tips` - мировые концы всех портов; `choose` - сначала порты с тем же числом портов.
   - Тесты: `coreTest` - два порта на площадке с доворотом 90°, три порта «уголком» с доворотом 130° (каждый штекер на
     своём месте с точностью < 0.3 бл; на самом деле 0.00), два порта в стене один над другим, разные расстановки -
     предупреждение. Игровые: `dockedShipPowersDown` - сцепились **оба** порта корабля с двумя портами на земле;
     `bridgeTurnsForPortLayout` - порты станции поперёк портов корабля, мост командует поворот; `bridgeIdleHoverAndOrbit` -
     маяк по коду вне сети (код чистится, регистр не важен), неизвестный код - висит. Dev-снимки (`-PfcsBridgeUi`):
     вкладка «Без цели», подсветка пистолетом, экраны контроллера и маяка.

46. (1.1-Test) «1) маяки должны быть в одной радарной сети с мостом; 2) почини подсветку на сублевелах; 3) в стыковочном
   контроллере - настройка дистанции обхода: на каком минимальном расстоянии держаться в манёврах до выхода на прямую».
   - Маяки: только маяки радарной сети моста (`LinkKind.BEACON`); код моста - маяк сети с этим кодом (без учёта
     регистра), пусто - первый маяк сети. Глобальный `BeaconRegistry` (п. 45) убран, код чистит
     `OrbitBeaconBlockEntity.cleanCode`; тикер маяка больше не нужен.
   - Подсветка на кораблях Sable: на снимке пользователя рамки были выровнены по осям мира и висели рядом с блоками
     повёрнутого корабля. Теперь `DockingToolClient.onRender` (`RenderLevelStageEvent`, `AFTER_PARTICLES`, доля тика
     кадра) рисует рамку 12 рёбрами `Outliner.showLine` между углами блока, каждый угол - через
     `SableCompat.toWorldRender` (поза отрисовки корабля): рамки поворачиваются с кораблём и не отстают при движении.
     Тик клиента только сбрасывает выбор, когда пистолет убран.
   - Дистанция обхода: `DockingControllerBlockEntity.approach` (3..64, по умолчанию 8; экран контроллера, очки
     инженера), в реестре порта `PortInfo.clearance`; мост берёт большую из порта и штекера
     (`Docking.Port.withClearance`). Точка подхода - на этой дистанции по оси порта. `Docking.detour`: корабль сзади или
     сбоку от порта (вдоль оси < 0.7 дистанции) - сначала вбок (1.3 дистанции от оси, у плоскости порта), затем вперёд
     на дистанцию, потом к точке подхода; `keepOut` - отталкивание ближе 0.9 дистанции до ближнего подхода.
     `coreTest`: корабль за стеной порта, дистанция 8 и 16 - ближе 8.0 и 14.1 бл до выхода на ось не подходит, садится.

47. (1.1-Test) «Добавь настройки скорости и направления корабля во время движения по орбите». Вкладка «Без цели» моста:
   `orbitSpeed` (бл/с, 1..200, по умолчанию 10) - скорость по касательной; выход на круг и высоту - не быстрее
   предела скорости моста (`Autopilot.orbitVelocity(..., speed, reach, ...)`, упреждение к центру - по скорости
   орбиты). `orbitFacing` (`Autopilot.OrbitFacing`): нос по ходу / против хода / к центру / наружу / не поворачивать
   (курс держится, корпус ровно); крен в поворот (если включён крен моста) - только по ходу и против хода. Кнопка
   «Направление» и кнопка «Нос» - в один ряд, подсказка стыковки - всплывающая (экран той же высоты, 272).
   `coreTest`: скорость орбиты 8 при пределе 20 - на круге 8.00 бл/с, радиус 29.9; направления носа. Игровой
   `bridgeIdleHoverAndOrbit`: по касательной ровно 5 бл/с (к центру быстрее), нос к центру - поворот вправо, наружу -
   влево.

48. (1.1-Test, предфинальная) «Проверь на баги и оптимизацию, подготовь предфинальную версию». Исправлено:
   - пауза редстоуном (режим «стыковка: нет цели») расстыковывала пристыкованный корабль - теперь пауза только
     отпускает управление: порты выдвинуты, порт занят, компьютер FC выключен, корабль отмечен (`holdDocked`); пауза не
     сбрасывает отсчёт «нет цели», после неё корабль не расстыковывается на 2 с;
   - сломанный (во время команды) порт Simulated оставался в списке выдвигаемых - новый порт на том же месте выдвинулся
     бы сам: `reassert` удаляет позиции без порта, контроллер без портов снимает свои команды;
   - оптимизации: сцепка портов (рефлексия по каждому порту) - только для сводки раз в 10 тиков; `endDock` без
     стыковки выходит сразу (раньше каждый тик искал корабль Sable); сети моста и штекера при стыковке - раз в секунду
     (раньше дважды за тик обход всех передатчиков измерения); блок порта Simulated кешируется (без поиска в реестре);
   - тексты: сцена Ponder моста (редстоун в режиме стыковки, «Без цели»), `release/changelog_1.1-Test.md`, описания
     CurseForge, README (зависимости). Проверено: все ключи переводов из кода и перечислений есть в ru и en.
   - Тесты: `dockedShipPowersDown` - пауза пристыкованного корабля (сцепка держится, компьютер выключен), после
     паузы - снова «пристыкован», стыковка выключена - расстыковка; допуск скорости орбиты в `bridgeIdleHoverAndOrbit`
     0.5 бл/с (статичная постройка Sable в тесте чуть поворачивается, команды - в осях корабля). 117 игровых тестов -
     с Sable/FC (`-PfcsNoCbcAt`) и `-PfcsMinimal`.
   - Не сделано (на финальную): сцены Ponder стыковочного контроллера и маяка орбиты; проверка в игре подлёта,
     подсветки и экранов (dev-клиент без видеокарты не доходит до мира).

49. (1.1-Test) «1) при отстыковке тоже соблюдать дистанцию обхода; 2) сломанное оборудование, подключённое пистолетом
   Flight Control, автоматически отключается от всего; 3) выяснить, почему корабли не атакуют друг друга без
   транспондеров (на экране - угроза)» («корабль двигался»).
   - Отход: `Docking.depart` - по оси порта, пока середина портов корабля не окажется дальше дистанции обхода (большая
     из порта и штекера; пристыкован не нами - ось штекера и его дистанция), скорость от 3 бл/с до предела моста, не
     дольше 20 с; отошёл - обычный полёт.
   - Сломанное: контроллер раз в секунду убирает из связей порты Simulated, которых нет (чанк загружен); мост раз в
     секунду (`maintainLinks`) отвязывается от сломанного компьютера FC (`clearLinkedComputer`) и проверяет связь с
     индикатором (`validateForwardIndicatorLink` Flight Control). Свой блок сломан - как раньше (компьютер забывает мост,
     порты возвращают сигнал).
   - Причина у пользователя (нашёл сам): на корабле-цели стояли пушки той же радарной сети - вычислитель считает
     корабли со своими станками своей платформой (`platformShips`: иначе он стрелял бы по своим орудиям), а монитор
     эту логику не применяет и показывал корабль угрозой. Пушки убрали - корабль стал целью. Так задумано.
   - **Попутно найден настоящий баг - «сбрасывать статичные» (по умолчанию включено) отсеивал корабли.** Цель «движется»
     от 0.6 бл/с (`minSpeed`, гистерезис 0.6); медленнее 20 тиков - статичная, не двигавшаяся ни разу - сразу. Корабль,
     который висит, стоит или держит дистанцию (мост в режиме дальности подводит корабль и держит его), - статичный, и
     вычислитель его не берёт, хотя монитор (без этого фильтра) показывает его угрозой. Теперь `motionAllowed`:
     статичный корабль Sable не сбрасывается (настройка - «Сбрасывать статичные цели (кроме кораблей)»).
     Игровые тесты: `staticShipIsEngaged` (до исправления - `targets=0 tracks=1`, пушка IDLE; после - попадание);
     `shipBatteryEngagesShip` - вся батарея (станок, механизмы наводки, моторы, пушка, вычислитель, передатчики)
     собрана в постройку Sable и попадает по движущемуся кораблю (батарея на корабле работает; свой корабль - свой).
     Радарный путь корабля (`observeContact` «SABLE» -> `SableCompat.find` по UUID постройки) проверен по коду.

50. (1.1-Test) Уточнение пользователя к п. 49: «одна сеть, один вычислитель» (пушки атакующего корабля и корабля-цели в
   одной сети) - «пусть свой-чужой для платформы работает только за счёт транспондера, но не допусти, чтобы пушки,
   стоящие на корабле, атаковали этот же корабль».
   - Вычислитель больше не считает своими корабли, на которых стоят его пушки (`platformShips` в контексте сенсора
     вычислителя - пустой набор): свой-чужой для кораблей у вычислителя - только транспондер. Монитор и мост по-прежнему
     передают корабль, на котором стоят сами (свой корабль на мониторе - свой, мост свой корабль не ведёт).
   - `MountState.ownShips` (раз в `discover`): корабль, на котором стоит пушка, и поворотные части её орудия.
     `FcsControl.onOwnShip` - такая цель (сам корабль или сущность на нём, `trackingShipId`) убирается из целей этой
     пушки (в том числе назначенная вручную); `FireSafety` не считает помехой корпус своего корабля пушки; камера,
     наведённая на свой корабль пушки, - пушка не наводится и не стреляет (`BLOCKED_FRIENDLY`). Другие пушки того же
     вычислителя на других кораблях такой корабль атакуют (если на нём нет транспондера «свой»).
   - Игровой тест `gunsNeverTargetTheirOwnShip`: одна сеть, один вычислитель, два корабля с пушками без транспондеров -
     пушка на A целится в B и попадает, пушка на B - в A, ни одна не берёт свой корабль.
   - Тесты: постройки Sable, собранные тестами, убираются между пакетами (`SableTestSupport.removeAll`,
     `SubLevelContainer.removeSubLevel`) - после п. 49 стоящие корабли прошлых тестов стали целями и отвлекали батарею
     `autocannonHitsDivingTarget` (5 попаданий из 70; с очисткой - 30 из 35).

51. (1.1-Test) Запрос пользователя: «у сборного орудия, когда стоят пушка и прицел, оно не может определиться, чем
   целится - у прицела максимальный приоритет» (прицел на поворотной платформе, симптом - «дёргается туда-сюда»);
   «информация о цели - над целью при наведении, а не в верхнем углу».
   - Первая попытка (курс с прицела, угол места со ствола - `Aim.sightAim`) отменена в п. 52: прицел у пользователя
     поворачивается и наклоняется вместе с поворотной частью.
   - Монитор радара: сведения о цели под взглядом (и о назначенной цели, когда взгляд ни на что не наведён) - рамкой
     над самой целью (`RadarMonitorRenderer.infoBox`): по центру над рамкой наведения, у верхнего края - под целью, в
     пределах карты и между строками состояния; подписи целей, на которые легла рамка, не рисуются, отдельная подпись
     имени у наведённой цели убрана (имя - в первой строке сведений).

52. (1.2-Test; пользователь: «прицел был на поворотной части, которая и поворачивалась, и наклонялась. Сделай так,
   чтобы при наличии у орудия прицела прицеливание производилось только от него, неважно сколько и каких у него
   пушек. Дай моду версию 1.2-test»). Версия `1.2-Test` (`changelog_1.1-Test.md` -> `changelog_1.2-Test.md`).
   - Орудие с прицелом: направление наведения ведущего и ведомых станков - только `SightBlockEntity.worldDirection()`
     (`Aim.sightAim` убран). Стволы в наведении не участвуют; от станка берутся только баллистика (снаряд, заряды) и
     точка вылета.
   - Станки пушек орудия с прицелом блокируются, как у обычной пушки (`prepareMount(..., lock = tu == null ||
     tu.sight != null)`, у ведомых - `tu.sight != null`): от своих валов (вращение поворотной части, приводы) они не
     поворачиваются, ствол держится вместе с прицелом и не уходит от направления, по которому считается выстрел.
     Без прицела станки орудия по-прежнему не блокируются - их поворачивают приводы (наводится ствол).

53. (1.2-Test; пользователь, со скриншотом тестовой установки: «вот так выглядит тест установка. Её шатает
   независимо от того, с какой камеры навожу»). Орудие на двух вращателях Sable: курс на земле, наклон на его
   поворотной части, на качающейся части - ствол и прицел.
   - Игровой тест `gunRotatorsSettleWithoutWobble` (такая же постройка, цель сбоку и выше) показал причины:
     1) команда вращателю «фактический угол + вся ошибка прицела» каждый тик: физика отвечает с запаздыванием,
        фактический угол скачет (площадка качается под стволом) - команда металась на ±5° за тик. Теперь интегральный
        контур: уставка + 0.15 ошибки за тик (`Turret.AIM_GAIN`), уставка не дальше чем на ошибку + 10° от
        фактического угла (`MAX_SAG`: выбирает провисание и сдвиг шарнира, но не накручивает уставку - иначе отдача
        привода наклона опрокидывала площадку и орудие ходило по кругу 35 тиков);
     2) привод курса считал момент инерции только лёгкой площадки (ствол на вложенном вращателе - отдельное тело):
        был в десятки раз слабее и не доворачивал. Теперь момент инерции вокруг оси шарнира: свой, масса x плечо до
        центра масс, и поворотные части вращателей, стоящих на этой части (`SableRotor.treeInertia`);
     3) касания основания и поворотной части не были выключены (флаг ставился один раз, Sable его не держит) -
        части тёрлись и опирались друг на друга; теперь `setContactsEnabled(false)` на каждом шаге физики, как у
        поворотного подшипника Simulated;
     4) сервопривод 1600/40 против гравитации Sable мягкий - длинный ствол провисал на 30-50°; теперь 16000/250
        (около критического демпфирования);
     5) при сборке курса вращатель наклона переезжает в его часть, и его шарнир пересоздавался только через 2 тика -
        ствол это время падал на площадку; теперь сразу при сборке (`SableRotor.Assembled.moved`).
     До исправления в тесте орудие застревало в 17-24° от цели (3 из 3); после - на цели без раскачки в 10 из 11
     запусков (ствол из досок - 4 из 4). Ствол из камня или железа (в 5-7 раз тяжелее площадки курса) Sable держит
     неустойчиво: площадка наклоняется на шарнире курса; с тяжёлой площадкой (железо) - на цели.
   - `GunRotatorBlockEntity.angle` - в (-180, 180].

54. (1.2-Test; «исправь проблему с настройкой орудийного вращателя и регулятора, что при нажатии ПКМ он меняет режим,
   сделай на Shift+ПКМ»). `GunDriveBlock.useWithoutItem` (режим курс/наклон) и `GunRotatorBlock.useWithoutItem`
   (сборка, показ угла) - только с Shift, иначе `PASS`: простой ПКМ, в том числе удержание на ползунке предела
   поворота привода (`AngleLimits`, Create value settings), режим больше не переключает. Подсказки, Ponder
   (`whileSneaking`), README, ченджлог.

55. (1.2-Test; «в предыдущем обновлении ты полностью сломал наведение со сборной пушки, чини» - всё вместе: дёргается,
   не туда, потом отказывается двигаться; «убери вообще вращатель орудия, будут чисто подшипники Aeronautics»).
   - Причина: в п. 52 станки сборного орудия с прицелом стали блокироваться (`prepareMount(..., lock)`). Приводы,
     которые крутят валы станка (или вращение, идущее через них), переставали его поворачивать: привод учил нулевой
     отклик, дёргался и вставал («нет вращения»). Теперь валы блокируются только у обычной пушки (`tu == null`), как
     до 1.2; наводка орудия с прицелом - по прицелу (п. 52), стволы в наводке не участвуют.
   - Вращатель орудия убран целиком: `GunRotatorBlock(Entity)`, `GunRotatorRenderer`, `compat/sable/SableRotor`,
     `LinkKind.GUN_ROTATOR`, `Turret.rotators/chain/aim` (интегральный контур п. 53), регистрация, рецепт, лут, модели,
     текстура, сцена Ponder, тексты; тест `gunRotatorsSettleWithoutWobble` удалён, `gunTurretGrouping` - на приводе
     (станок орудия не поворачивается сам и не заблокирован). В старых мирах блок пропадает (нет в реестре).
     Свои постройки пушки (`MountState.ownShips`) - постройки Sable под прицелом, приводами и станками орудия.

56. (1.2-Test; «добавь штуку (лазерный целеуказатель), которая при наведении и активации им по цели (издалека) делала
   её целью, как ПКМ по ней в мониторе»). `item/LaserDesignatorItem` (`radar_fcs:laser_designator`):
   - Shift+ПКМ по любому блоку стены монитора - привязка к ведущему блоку (`CUSTOM_DATA`: `Monitor`, `Dim`).
   - ПКМ - луч от глаз до 512 блоков: блоки мира только загруженных чанков (`SableCompat.plain`), постройки Sable
     (`clipShips`, свой корабль игрока пропускается), сущности с допуском 0.3 + 0.005 x дальность (≈0.3°, издалека
     легче попасть). Кандидаты по порядку: сущность, корабль, на котором она; иначе корабль под лучом.
     `RadarMonitorBlockEntity.designateTracked` назначает, только если монитор видит цель среди чужих (как ПКМ по
     отметке), иначе сообщение и назначение не меняется. Луч - красные частицы, звук, перезарядка 0.5 с.
   - Shift+ПКМ в воздух - снять назначение. Монитор не найден/в другом измерении/не привязан - сообщение.
   - Рецепт: стеклянная панель, редстоун, латунный слиток, 2 железных листа, прецизионный механизм. Тест
     `laserDesignatorDesignates` (привязка, назначение дальней из двух целей издалека, луч в небо назначение не
     меняет, снятие).

57. (1.2-Test; со скриншотом устройства: «целеуказатель пусть выглядит примерно как на картинке; полностью верни
   наведение орудия как в версии 1.0 Release»). Сверено с 1.0 Release (`b06706a^`): `Turret.drive` и наводка по
   прицелу/стволу те же; отличия и что сделано:
   - главная поломка с 1.1-Test: свой-чужой кораблей только по транспондеру (п. 50) и неподвижные корабли не
     сбрасываются (п. 49) - а поворотные части сборного орудия на подшипниках Aeronautics для радара тоже «корабли».
     В 1.0 они были своими (`platformShips` в сенсоре вычислителя), с 1.1-Test стали целями, и орудие наводилось на
     собственную поворотную часть (дёргалось, «не туда», вставало). Теперь постройки самих орудий
     (`FcsControl.gunShips`: части орудия и соседние постройки без основания) - свои для всего вычислителя
     (`FireControlBlockEntity.turretShips` -> контекст сенсора) и в `MountState.ownShips` каждой пушки. Корабль, на
     котором орудие стоит, сюда не входит (п. 50 сохраняется). Тест `gunNeverTargetsItsOwnTurretParts` (прицел на
     постройке Sable и платформа без блоков мода рядом: без исправления орудие берёт свою платформу «ship-…»,
     с исправлением - хаска);
   - ведомые станки орудия (вторая и следующие пушки) тоже не блокируются при прицеле (в п. 55 исправлен только
     ведущий) - как в 1.0;
   - вращатель орудия убран (п. 55), привод - Shift+ПКМ (п. 54).
   - Текстура целеуказателя 16x16 по картинке пользователя: серый корпус, прицел сверху с синей линзой, два объектива
     спереди, жёлтый огонёк, рукоять-пистолет; модель `item/generated`.

58. (1.2-Test; «1) текстуру целеуказателя в 3D и нормальном разрешении (128x128), смотрящий вперёд; 2) добавь копию
   подшипника из Aeronautics, но не дёргающийся»).
   - Код Aeronautics не копируется: свой `block/GunBearingBlock(Entity)` (`radar_fcs:gun_bearing`, рендер вала
     `client/GunBearingRenderer`) на `compat/sable/SableRotor` (сборка постройки перед подшипником в под-уровень Sable,
     шарнир у грани). Угол target идёт за валом, как у подшипника Create: `convertToAngular(об/мин)` за тик (знак - по
     направлению FACING), Shift+ПКМ пустой рукой - собрать / показать угол.
   - Без дёрганья: поворотная часть не тянется пружиной мотора (у вращателя п. 53 и у подшипника-шарнира пружина
     давала провисание 5-30°, отставание и дрожь, по которой привод орудия ещё и «учился» неверному отклику),
     а ставится в позу на каждом шаге физики (`SableRotor.drive`: `RigidBodyHandle.teleport` в угол вокруг точки шарнира
     относительно основания, скорость - как у основания в этой точке; мотор шарнира без жёсткости, касания
     выключаются каждый шаг). Внешние подшипники ставятся первыми (`depth`), вложенные - от уже поставленной позы
     основания. Подшипники, уехавшие в собранную часть, переподключаются сразу.
   - Проверка: без вычислителя углы 0, -20/-30, 10/-60 держатся точно (ошибка 0.00°, раньше пружина - 5-25°); тест
     `gunBearingsAimWithoutWobble`: мотор -> привод курса -> подшипник курса, на площадке привод наклона с мотором ->
     подшипник наклона, ствол из 8 блоков камня и прицел, цель сбоку и выше - на цели (ошибка 0.03-0.3°) без раскачки
     (0.000°); со стволом из железа - на цели, раскачка до 1° (доводка приводами).
   - Целеуказатель: 3D-модель `models/item/laser_designator.json` (корпус, светлая передняя панель с двумя объективами,
     прицел сверху с синей линзой, жёлтые огоньки по бокам, наклонная рукоять со спуском), атлас 128x128
     (`texture_size`), «вперёд» - север (-Z), как у пистолета Flight Control; вид в инвентаре - сбоку-спереди.

59. (1.2-Test; «сделай указателю нормальные тени и градиенты, прицел - по центру, а не несимметрично смещённый, и дай
   возможность целиться»).
   - Текстура 128x128 заново: плавные градиенты сверху вниз, затенение к краям (AO), светлая/тёмная фаска, панельный
     шов и винты на боках корпуса, цилиндрический градиент с бликом у трубы прицела, стеклянные объективы и синяя
     линза с отражением, рифлёная скруглённая рукоять. Прицел на модели - по центру корпуса и по ширине, и по длине
     (z 4-12 при корпусе 3-13), крепление посередине.
   - Прицеливание: ПКМ держать - `UseAnim.SPYGLASS` (рука у глаза в третьем лице), `client/DesignatorClient`: от
     первого лица приближение (`ComputeFovModifierEvent`, колесо мыши - 2/4/6/10x, по умолчанию 4x), чувствительность
     мыши по приближению (`CalculatePlayerTurnEvent`), сетка прицела (`textures/misc/designator_scope.png`: затемнение
     вне круга, перекрестие с делениями, красная точка), рука и обычное перекрестие скрыты, внизу - цель под
     перекрестием и дальность (луч на клиенте - только подсказка). Отпустить ПКМ - `releaseUsing` на сервере: луч и
     назначение, как раньше. Shift+ПКМ в воздух - снять назначение. Тест `laserDesignatorDesignates` - через
     использование и отпускание предмета.

60. (1.2-Test; «1) сделай целеуказателю лазерный луч, видимый при прицеливании; 2) сделай подшипнику текстуру +- как в
   Aeronautics, и функционал точь-в-точь (вращение шестернёй и вал насквозь); 3) пусть целеуказатель назначает цель на
   ЛКМ по ней (луч меняет цвет с зелёного на красный), а убирает - ЛКМ по воздуху»).
   - Подшипник: поворот - от шестерни Create рядом (`GunBearingBlockEntity.ringSpeed`: малая вплотную сбоку с той же
     осью - навстречу, большая по диагонали - x2 навстречу; читается её скорость, кинетической связи с ней нет, поэтому
     поворот и вал независимы). Вал насквозь: `hasShaftTowards` по оси, после сборки - связь с первым блоком поворотной
     части в плоте (`addPropagationLocations`/`propagateRotationTo` = 1, раз в секунду переподключение, если тот без
     вращения). Модель своя: основание и верхняя плита «железного корпуса» с отверстием под вал, стойки по углам,
     между ними кольцо-шестерня (`AllPartialModels.SHAFTLESS_COGWHEEL`, крутится в зацеплении со скоростью кольца) и
     вал насквозь (`SHAFT_HALF` x2) в `client/GunBearingRenderer`. Код и текстуры Aeronautics не копируются.
   - Без дёрганья (по трассе теста): угол считается на первом шаге физики тика (после команд вычислителя, иначе
     поворот запаздывал на тик и привод учился не на той команде), спящую физику Sable будит подшипник (без шагов
     физики угол не менялся; запасной расчёт - в тике блока); поза ставится и до, и после каждого шага физики, мотор
     шарнира не задаётся (с мотором часть уходила за шаг на 1.7-6°, вычислитель видел скачки, привод выучивал отклик
     в 10 раз больше настоящего и вставал). Тест `gunBearingsAimWithoutWobble` (шестерни у подшипников) - 6 из 6
     одинаково: ошибка 0.08°, раскачка 0.000°; `gunBearingShaftPassesThrough` - вал на поворотной части 64 об/мин от
     мотора под подшипником, поворотная часть стоит.
   - Целеуказатель: зелёный луч у всех игроков в прицеливании (`DesignatorClient.beams`, линия `Outliner` от передней
     линзы до точки под перекрестием); ЛКМ при прицеливании (`ClientTickEvent.Pre` забирает нажатия - при
     использовании предмета игра их глотает) -> `net/DesignatorPayloads.Fire` -> `LaserDesignatorItem.fire`: цель на
     луче - назначить, нет цели - снять; ответ `Result` - луч на 0.75 с красный. Отпускание ПКМ больше не назначает.
     Тест `laserDesignatorDesignates`: прицел + ЛКМ по дальней цели - назначена; ЛКМ в небо - снята; снова; Shift+ПКМ.

61. (1.2-Test; «да скопируй ты текстуру из Aeronautics для этого подшипника»; затем «раз не можешь скопировать,
   просто возьми текстуру Aeronautics, перекрась подшипник в чёрный (сохраняя тени и градиенты), а сторону, которая
   физически, - с краёв жёлто-чёрным обозначающим узором»). Ассеты Create Simulated/Aeronautics - «All Rights
   Reserved» (LICENSE.md в их jar; MIT - только код): ни их PNG, ни перекрашенную копию в наш jar класть нельзя.
   Сделано у игрока при запуске: `client/AeroBearingLook.build` (из `AddPackFindersEvent`, только при `Mods.SIMULATED`)
   читает `assets/simulated/textures/block/swivel_bearing.png` и модель `swivel_bearing/block.json` из файла мода
   Simulated, перекрашивает (чёрный: 12 + 105 x яркость оригинала; жёлтые диагональные полосы на верхних гранях
   элементов рамки плиты - `to.y = 16`, грань up, по их uv; яркость оригинала сохраняется) и пишет пакет в
   `<игра>/radar_fcs/aeronautics_bearing_pack` (модели `gun_bearing`, `item/gun_bearing`, `gun_bearing_cog` - их модели
   с нашей текстурой `radar_fcs:block/gun_bearing_aero`), пакет подключается обязательным, поверх остальных
   (`PathResourcesSupplier`). Кольцо - `radar_fcs:block/gun_bearing_cog` (`PartialModel`), нет модели - шестерня
   Create. Проверено запуском клиента: пакет `radar_fcs_aero_bearing` последним в списке ресурсов, ошибок моделей
   нет. Без Aeronautics - свой вид (п. 60).

62. (1.2-Test; «сделай этот подшипник доступным только с Aeronautics, без него и так 0 смысла»). Подшипник
   регистрируется только при `Mods.SIMULATED` (мод Simulated из состава Aeronautics), как мост Flight Control:
   `block/GunBearings.register()` из `FcsRegistry.register` (блок, предмет, тип BE), вкладка творчества, нагрузка
   Create, рендер, шаг физики `SableRotor.init` - тоже по условию. Рецепт и лут - `neoforge:conditions` mod_loaded
   simulated, тег кирки - `required: false`. Без Aeronautics (`-PfcsMinimal`) ошибок про подшипник в логе нет, тесты
   подшипника пропускаются.
63. (1.2-Test; «может всё-же сделать физичную сборку»). Поворотная часть подшипника орудия не проходит сквозь блоки мира:
   в `GunBearingBlockEntity.integrate` шаг угла за тик режется `SableRotor.freeFraction` - точки блоков поворотной части
   и частей подшипников на ней (`childSubs`, центр и 8 углов с отступом 0.3) поворачиваются вокруг оси шарнира шагами
   ≤ 3°, первая точка в форме столкновения блока мира (загруженные чанки) останавливает поворот; точки, уже стоящие в
   блоках, не держат. Упёрлась - угол не накручивается (шестерня проскальзывает), помеху убрали - поворот дальше.
   Поза по-прежнему ставится точно (без раскачки). Пробовали вести часть скоростью Rapier на каждом шаге
   (`addLinearAndAngularVelocity` до цели за шаг, по живой позе `PhysicsPipeline.readPose` и центру масс) - тело
   проходило лишь 1-15 % заданного поворота даже без вложенной части и без касаний, раскачка 3-18°; отказались.
   Заодно: событие шага физики Sable приходит от физики каждого измерения - ведутся только подшипники этого
   (`ForgeSablePrePhysicsTickEvent.getPhysicsSystem().getLevel()`). Тест `gunBearingStopsAtBlocks`: рука из камня
   упирается в камень и стоит при крутящейся шестерне, камень убрали - поворачивается.
64. (1.2-Test; «а можно всё-же sable структурой орудие физичить; уменьшить скорость поворота в начале и в конце для
   стабильности»). Поворотная часть - тело физики Sable, без постановки позы и без своей проверки блоков (п. 63 заменён):
   - `SableRotor.drive` перед каждым шагом физики добавляет телу ускорение, как от силы: пружина с демпфером
     (`OMEGA` 25 рад/с, критическое) к позе «угол вокруг оси относительно фактической позы основания» + скорость
     движения цели (упреждение) + компенсация веса (`DimensionPhysicsData.getGravity`). Сила конечная - столкновения
     решает физика: часть упирается и толкает. Явная схема: `k·dt² + 2c·dt < 4` при dt 0.025 с (с ω 60 - раскачка);
   - угол сервопривода `prof` догоняет угол от шестерни `target` с ускорением ≤ `ACCEL` 4°/тик² - плавный разгон и
     торможение; упёрлась - `target`/`prof` не дальше `MAX_LEAD` 4° от фактического (шестерня проскальзывает);
   - связь Sable - `GenericConstraint` без закреплённых осей, только `setContactsEnabled(false)` (основание и часть не
     сталкиваются). Шарнир `RotaryConstraint` не годится: точки крепления в плоте (~2·10⁷) нативная физика считает во
     float с шагом 2 блока - точка на поворотной части съезжала к центру масс, часть вращалась вокруг него и упиралась
     в связь (один блок - работал, рука из 3 камней - ±15°). Отсюда и прошлые неудачи со скоростью и мотором шарнира;
   - после сборки физике сообщается смена каждого ушедшего блока (`SableCommonEvents.handleBlockChange`) - иначе
     коллайдеры старых блоков оставались «призраками» в мире;
   - приводы орудия: не учатся, пока часть отстаёт от угла вала больше 5° (`GunBearingBlockEntity.settling`, после
     сборки, при упоре), знак отклика меняют после 6 тиков движения против команды (`AGAINST_TICKS`, было 2) -
     физичная часть доезжает с запаздыванием в пару тиков;
   - игровые тесты - у начала координат (±4096, `DevAutostop.NearOrigin`): ванильный сервер ставит их в ±1.5·10⁷, где
     float-физика Sable имеет точность около блока. Тесты подшипника - в трёх отдельных пакетах (постройка одного
     теста - «корабль» для вычислителя другого). Наведение: ошибка 0.36°, раскачка 0.000° (6 из 6), упор о камень -
     около −17.5° каждый раз.

65. (1.2-Test; «буквально повтори функционал подшипника из Aeronautics, но гасящий инерцию»; «почини прицеливание орудия
   до состояния как в 1.0 release - прицел шалит, пушки наводит корректно»). Подшипник орудия - повтор
   `SwivelBearingBlockEntity` Simulated через их API (код не копируется; Simulated - `compileOnly` из jar-in-jar Aeronautics,
   `build/embedded/simulated-neoforge-1.21.1.jar`):
   - кольцо-шестерня - `ExtraKinetics` (вложенный `GunBearingBlockEntity.Cog`, позиция `ExtraBlockPos` - по ней миксины
     Simulated находят вторую кинетику; `shouldConnectExtraKinetics` = false; блок - `ExtraKineticsBlock` с малой
     шестернёй на оси), нагрузки нет; закрутилось у несобранного - `assembleNextTick`; секвенсорный редуктор -
     `sequencedAngleLimit`; предел скорости - их конфиг `maxSwivelBearingSpeed`, выше - скрежет;
   - сборка `SimAssemblyHelper.assembleFromSingleBlock`, пусто - под-уровень из одной пластины; пластина
     `GunBearingPlateBlock(Entity)` в плоте в клетке подшипника (форма 12..16, вал вперёд, ПКМ - разобрать, сломали -
     ломается подшипник); подшипник собранный - `ASSEMBLED` (форма 0..11.9), вал только сзади; разборка
     `SimAssemblyHelper.disassembleSubLevel`; перенос в чужую постройку - `BlockSubLevelAssemblyListener`
     (`beforeMove/afterMove`: пластина узнаёт новое место, шарнир сразу на новое основание);
   - шарнир `RotaryConstraint` как у них (центр блока перед подшипником и он же в плоте − 0.001 по оси), мотор
     `setMotor` к углу между тиками; блокировка `LockMode` (4 режима, редстоун, `POWERED`), без неё - трение 0.3;
   - гашение инерции: жёсткость 40²·I, демпфирование 2·1·40·I (у Aeronautics 40·I - ζ 0.5), I - момент инерции
     вокруг оси всего поворачиваемого (`SableRotor.inertia`, части подшипников на нём, Штейнер), цель + скорость·c/k;
     упёрлась - угол не дальше 10° от фактического.
   П. 64 (тело на силах, плавный разгон `prof`) заменён. Вывод п. 64 «шарнир Sable в плоте неточен» был ошибочным:
   точка крепления «уплывала» только у тестов на координатах ±10⁷ (float-физика), у начала координат шарнир точен.
   Наведение: в `FcsControl`/`Turret` код наведения по прицелу совпадает с 1.0 Release; обучение привода
   (`GunDriveBlockEntity.learn`) возвращено к 1.0 (2 тика против команды, без `skipLearn`/«усадки»).
   Тесты `gunBearingsAimWithoutWobble` (ошибка 0.1°, раскачка 0.000°, огонь с 40-го тика), `gunBearingStopsAtBlocks`
   (стоит у камня на −22.3°), `gunBearingShaftPassesThrough`.
66. (1.2-Test; «пусть даже когда курсор не смотрит на объект, пушка наводится на курсор: на объекте - как было, в
   воздухе - туда, где он»). `FcsControl.aimsAtAir`: взгляд камеры ни во что не упёрся (`ManualAim.Hit` пустой, скорость
   0) - решение не баллистическое, а направление от пушки на точку взгляда (конец луча), время полёта - дальность /
   скорость снаряда. Тест `cameraAimsAlongCursorInAir` (барьеры теста на пути взгляда убираются): ствол по взгляду 0.26°.67. (1.1, итоговая; «1) в крафте подшипника орудия используй подшипник из аэронавтики вместо вращателя и добавь 4 пружины;
   2) у целеуказателя пофикси отображаемую дистанцию до сублевела; 3) обнови пондер-сцены, описания больше 1 предложения
   замени на пондер-сцены; 4) редстоун-сигнал по порту в режиме порт должен его блокировать (пока пристыкован - запретить
   отстыковку, пока не пристыкован - запрещать стыковку). Проверь на баги или неоптимизированные моменты и сделай в
   качестве версии 1.1»). Версия `1.1` (вместо 1.2-Test; `changelog_1.2-Test.md` слит в `release/changelog_1.1.md` -
   против 1.0 Release, с разделом «по сравнению с 1.2-Test»; `release/radar_fcs-1.1.jar` заменён).
   - Рецепт подшипника орудия: `PBP/SMS/PGP`, B - `simulated:swivel_bearing`, P - `simulated:spring` (4), S - железный
     лист, M - точный механизм, G - коробка передач (условие `mod_loaded simulated`).
   - Целеуказатель: `p.pick` по блоку корабля Sable возвращает точку в координатах плота (миллионы блоков) -
     `SableCompat.toWorld` в `DesignatorClient.hitPoint` и в надписи дальности.
   - Ponder: новые сцены `gun_bearing` (сборка, кольцо-шестерня, вал насквозь, блокировка, разборка), `docking`
     (роли, связь пистолетом, номер, дистанция обхода, блокировка редстоуном), `orbit_beacon`, `designator`;
     `swivel_turret` - на подшипниках орудия; схемы - `devtools/ponder_schematics.py`, тексты - `devtools/ponder_lang.py`
     (блок переопределений 1.1). Все `radar_fcs.tooltip.*` - одно предложение, подробности - в сценах.
   - Блокировка порта: контроллер в роли порта под сигналом (`hasNeighborSignal`) - `held`; сцепку на начало сигнала
     (`heldLocked`) смотрим раз в полсекунды: сцеплен - порты выдвинуты, статус `HELD_DOCKED`, мост `portHeld` ->
     `State.DOCK_HELD` (не расстыковывает, держит сцепку `holdDocked`); свободен - порты не выдвигаются, `HELD_CLOSED`,
     `DockingRegistry.freeFor` - false (мост выбирает другой порт или ждёт). Смена `held` сразу пишется в реестр.
     Тест `dockedShipPowersDown`, фазы 6-7.
   - Проверка/оптимизация: подшипник орудия собирает поворачиваемые части (под-уровни на нём, до 32) и их момент
     инерции раз в тик, а не на каждом шаге физики (2 шага за тик). Пластина ломает подшипник только в `remove()` -
     Create вызывает его лишь при настоящем удалении блока (`SmartBlockEntity.setRemoved`: не при выгрузке чанка).
   Тесты: 126/126 в `-PfcsNoCbcAt` и `-PfcsMinimal`, `coreTest` - все.
68. (1.1, обновлённая сборка без смены версии; «1) в прицеле целеуказателя и камере видно предмет в руках и длиннохват;
   2) баг с нашим подшипником: при разборке во время прицеливания разобранная часть пропадает; 3) если в мосту режим
   стыковки «нет цели» и настроена орбита, при занятых и заблокированных портах пусть по ней летает; 4) целеуказателю
   на колёсико мыши - исключение цели»).
   - Рука: `RenderHandEvent` у целеуказателя (`DesignatorClient.hand`) и камеры (`CameraClient.hand`) отменяется с
     `EventPriority.HIGHEST` - обработчик длиннохвата Create (`ExtendoGripRenderHandler`) сам рисует руку с предметами
     и отменяет событие, а с обычным приоритетом он успевал раньше нашего.
   - Исключение: клавиша «выбрать блок» (нажатие колеса) при прицеливании глотается игрой - читаем её в
     `ClientTickEvent.Pre` (как ЛКМ), `DesignatorPayloads.Exclude` -> `LaserDesignatorItem.exclude` (тот же луч, что у
     `fire`) -> `RadarMonitorBlockEntity.toggleExclusionTracked` (цель видна экрану или уже исключена) ->
     `toggleExclusion`, как нажатие колеса по отметке. Тест `laserDesignatorDesignates` - фаза 3.
   - Подшипник: (а) `SableCompat.fail` выключал всю совместимость с Sable при любой ошибке, а сканер вычислителя
     (`shipsIn` -> `describe` -> скорость) в тике разборки спрашивал скорость удалённого тела («Body has been removed») -
     дальше корабли, прицел на поворотных частях и `toWorld` не работали до перезапуска. Теперь выключает только
     `LinkageError` (несовместимая версия), разовая ошибка - пропуск вызова (лог не чаще раза в минуту), корабль с
     удалённым телом в `shipsIn` пропускается. (б) Привод наводки крутит кольцо - разобранный ПКМ подшипник в следующем
     тике собирался обратно (`Cog.onSpeedChanged`), разборка и сборка постройки Sable в соседних тиках, обучение
     приводов сбивалось (наклон уходил в упор, курс крутился). Теперь `holdDisassembled` (в NBT): разобранный вручную
     от кольца не собирается, только ПКМ. Тесты `gunBearingDisassemblesWhileTurning` (рука на 44°, вторая поворотная
     часть остаётся на своём подшипнике), `gunTurretDisassemblesWhileAiming` (башня с вычислителем: курс разобран на
     ходу, 40 тиков не собирается при кольце 64 об/мин, ствол на подшипнике наклона, ПКМ - собран, огонь с ошибкой 0.15°).
   - Мост: `dockSteer` - стыковка `NO_TARGET`, без цели орбита (`idle` не `HOVER`), а выбранный порт не свободен (бронь
     чужая, сцеплен, держат редстоуном) - `idleSteer` (орбита, маяка нет - висеть в `busyHold`), состояние
     `DOCK_BUSY_ORBIT`; порт проверяется каждый тик, освободился - стыковка. Тест `bridgeDocksByNumber` - фазы 30-31.
69. (1.1, без смены версии; «прогони полные тесты с аддонами, которые поддерживает мод (CBC-AT вполне работает с новыми
   версиями у меня); если что-то нужно, исправь»). Аддоны: с Modrinth - CBC-AT 0.1.4c (новее нет ни там, ни на
   CurseForge), Sable 2.0.6, Aeronautics 1.3.2, Flight Control, FPC, Crafts & Additions 1.6.0; с CurseForge (CDN
   `mediafilez.forgecdn.net/files/<id/1000>/<id%1000>/<имя>`, номера файлов - `api.cfwidget.com/<проект>`) - cbcatfix
   1.1.4 (9200297), CBC Neo Warfare 0.0.6v (8232556), CBC Interception 1.0-release (9601033), через `-PfcsExtraMods`.
   CBC 5.11.7 - последняя. Без cbcatfix CBC-AT 0.1.4c роняет сервер (ракета CBC-AT бьёт блок -
   `PartialBlockDamageManager.damageBlock` нет в CBC 5.11.7; выстрел - `onRecoil`): у пользователя он работает с cbcatfix.
   Со всеми аддонами падал `gunsNeverTargetTheirOwnShip`: пушки двух кораблей стреляли друг в друга в один тик по одной
   линии, с CBC Interception встречные снаряды сбивали друг друга (баллистика верна: расчёт 4.186 бл/тик, факт
   4.16-4.22 с разбросом CBC-AT). Пушка B теперь без заряда (цель она выбирает так же, тест требует, чтобы она вела A).
   `gunDriveYawLimit` (изредка падал в `-PfcsMinimal`): станок разбивал снаряд соседнего теста того же пакета - тесты
   пакета стоят в ряд, станки в одной точке площадки и стреляют вдоль ряда (станок, передатчики выпадали предметами на
   ~25-м тике, сразу после чужого FIRE). Тест вынесен в свой пакет `limits_drive`; пропал станок - сразу ошибка с
   окружением, а не таймаут. Итог: все 128 - со всеми аддонами (4 прогона), `-PfcsNoCbcAt`, `-PfcsMinimal` (8 из 8),
   `coreTest`; `interceptsFastCrossingShells` раз упал под нагрузкой (первый снаряд 50 тиков без решения), в повторах -
   6 из 6.
70. (1.1, без смены версии; скриншот: прицел целеуказателя на корабле Sable, под перекрестием «Медовый клей», дальность
   29018124 м; «исправь баг, не фиксируй клей целеуказателем»). Клей (суперклей Create, медовый клей Aeronautics) -
   сущности в плоте постройки; клиентский `ProjectileUtil.getEntityHitResult` их находил (у Sable они пересекаются
   с лучом мира), а позиция у них плотовая - дальность в миллионах. `LaserDesignatorItem.targetable`: цели - существа,
   снаряды, транспорт (`VehicleEntity`), контрапшены Create; клей, рамки, выпавшие предметы и прочее - нет (луч сквозь
   них). Фильтр - в луче сервера (`trace`) и клиента (`DesignatorClient.hitPoint`/`overlay`); точки сущностей - через
   `SableCompat.toWorld`; блок корабля под перекрестием - имя корабля (`SableCompat.name`). Точка сущности под взглядом
   камеры (`CameraBlockEntity.lookPoint`, клей там уже отсеивал `isPickable`) - тоже в мир. Тест `designatorIgnoresGlue`
   (суперклей на луче перед мобом - целью моб; правило `targetable` для клея, моба, предмета, стрелы).
