# 1.2-Test — changes since 1.1 (test version)

## English
**Added** (only with **CBC Enchanced Shells**, tested 1.2.2 and 1.5.1)
- **Heat-seeking rocket nose**: placed last on a launchpad stack; after launch it finds a target of the chosen types ahead (the computer's target types: Sable ships, players, hostile mobs, other entities, shells, rockets) and steers the rocket to it. The locked target never changes.
- **Programmable rocket nose**: joins a radar network like a camera (right-click a network link with the item). The rocket flies to the network's selected target — designated on a monitor, otherwise the fire control computer's target, even beyond gun range. The target is locked at launch and never changes, even if the network no longer tracks it.
- Programmable nose modes: **automatic launch** when the network has a target of the allowed type (any or one type), **automatic launch on a designated target** only (designated by hand on a network monitor or with the Laser Designator; the computer's own targets do not count), or **launch by redstone** on the nose. Engineer's Goggles show the network, mode and state.
- **Rockets tab** in the fire control computer (only with CBC Enchanced Shells): how the network's programmable noses launch automatically — **single** (one rocket per target in flight, time between launches) or **queue** (rockets per queue, delay between rockets, pause between queues).
- Rockets with these noses steer to the predicted intercept point up to ~6° per tick (the stock guidance of the mod turns too slowly and overshoots fast rockets).
- Ponder scene for the rocket noses.
- The **Create Wrench** picks rocket noses up with their settings; **crafting** a configured nose with unconfigured noses of the same kind copies its settings (the configured one stays in the grid).
- Radar and gun network links, cameras and rocket noses keep their **network in Create schematics**.

**Fixed**
- A rocket with our nose that runs out of fuel now falls instead of circling around the target forever.
- An interrupted rocket queue (out of rockets or the target was lost) is no longer continued: the next queue is a full one.

**Note**
- CBC: Firepower Components 0.4.4 does not load with CBC Enchanced Shells newer than 1.3.0 (its own restriction).

## Русский
**Добавлено** (только с **CBC Enchanced Shells**, проверено 1.2.2 и 1.5.1)
- **Нос ракеты с тепловым наведением**: ставится последним в стек стартовой площадки; после пуска сам находит впереди цель выбранных типов (типы целей вычислителя: корабли Sable, игроки, враждебные мобы, другое, снаряды, ракеты) и ведёт к ней ракету. Захваченную цель не меняет.
- **Программируемый нос ракеты**: подключается к радарной сети как камера (ПКМ предметом по передатчику сети). Ракета летит на выбранную цель сети — назначенную на мониторе, иначе цель вычислителя, даже вне досягаемости пушек. Цель захватывается при пуске и не меняется, даже если сеть её больше не ведёт.
- Режимы программируемого носа: **автопуск**, когда у сети есть цель допустимого типа (любая или один тип), **автопуск по назначенной цели** (только по цели, назначенной вручную на мониторе сети или лазерным целеуказателем; цели вычислителя без назначения не в счёт) или **пуск по редстоуну** на нос. Очки инженера показывают сеть, режим и состояние.
- **Вкладка «Ракеты»** в вычислителе (только с CBC Enchanced Shells): как программируемые носы его сети пускают ракеты автопуском — **одиночными** (одна ракета на цель в полёте, время между пусками) или **очередью** (ракет в очереди, задержка между ракетами, пауза между очередями).
- Ракеты с этими носами доворачивают на упреждённую точку встречи до ~6° за тик (штатное наведение мода поворачивает медленно и проскакивает быструю цель).
- Сцена Ponder для носов ракет.
- **Ключ Create** снимает носы ракет с настройками; **крафт** настроенного носа с ненастроенными того же вида копирует его настройки (настроенный остаётся в сетке).
- Передатчики радарной и орудийной сети, камеры и носы ракет сохраняют **сеть в схематиках Create**.

**Исправлено**
- Ракета с нашим носом, у которой кончилось топливо, теперь падает, а не кружит вокруг цели бесконечно.
- Прерванная очередь ракет (ракеты кончились или цель пропала) больше не продолжается: следующая очередь — полная.

**Важно**
- CBC: Firepower Components 0.4.4 сам не грузится с CBC Enchanced Shells новее 1.3.0.
