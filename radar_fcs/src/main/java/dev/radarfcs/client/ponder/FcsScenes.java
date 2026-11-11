package dev.radarfcs.client.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import dev.radarfcs.registry.FcsRegistry;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.TextElementBuilder;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Сцены Ponder. Тексты берутся из файлов перевода по порядку вызова {@link #say}: radar_fcs.ponder.&lt;сцена&gt;.text_1,
 * text_2... (devtools/ponder_lang.py - там тексты на русском и английском; число вызовов say в сцене должно совпадать).
 * Камера Ponder смотрит с северо-запада: видны грани на север и запад.
 */
final class FcsScenes {
    private FcsScenes() {}

    /** Подпись (текст - из перевода по порядку); время показа - по длине реплики задаётся вызывающим. */
    private static TextElementBuilder say(SceneBuilder s, int ticks) {
        return s.overlay().showText(ticks).text("");
    }

    /** Реплика и пауза на её чтение. */
    private static void sayAt(SceneBuilder s, int ticks, Vec3 at, boolean key) {
        TextElementBuilder t = say(s, ticks).pointAt(at).placeNearTarget();
        if (key) t.attachKeyFrame();
        s.idle(ticks + 10);
    }

    private static void show(SceneBuilder s, Selection sel, Direction from, int idle) {
        s.world().showSection(sel, from);
        s.idle(idle);
    }

    private static ItemStack stack(net.minecraft.world.level.ItemLike item) {
        return new ItemStack(item);
    }

    private static Vec3 face(SceneBuildingUtil u, int x, int y, int z, Direction d) {
        return u.vector().blockSurface(new BlockPos(x, y, z), d);
    }

    // ------------------------------------------------------------------ батарея
    static void battery(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("battery", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(2, 2, 3), Direction.DOWN, 5);
        show(s, u.select().fromTo(1, 4, 3, 5, 4, 3), Direction.DOWN, 10);
        sayAt(s, 90, u.vector().topOf(3, 4, 3), true);                                            // 1 пушка
        show(s, u.select().position(2, 1, 3), Direction.EAST, 5);
        show(s, u.select().fromTo(2, 2, 4, 2, 2, 5), Direction.NORTH, 5);
        s.world().setKineticSpeed(u.select().position(2, 0, 3).add(u.select().position(2, 1, 3)), 64);
        s.world().setKineticSpeed(u.select().fromTo(2, 2, 4, 2, 2, 5), 64);
        sayAt(s, 100, face(u, 2, 2, 4, Direction.WEST), true);                                    // 2 контроллеры
        show(s, u.select().position(5, 1, 5), Direction.DOWN, 10);
        sayAt(s, 80, u.vector().topOf(5, 1, 5), true);                                            // 3 радар
        show(s, u.select().position(5, 1, 1), Direction.DOWN, 10);
        sayAt(s, 90, face(u, 5, 1, 1, Direction.NORTH), true);                                    // 4 вычислитель
        show(s, u.select().position(1, 2, 3), Direction.EAST, 3);
        show(s, u.select().position(5, 2, 1), Direction.DOWN, 3);
        show(s, u.select().position(5, 2, 5), Direction.DOWN, 5);
        s.overlay().showOutline(PonderPalette.GREEN, "net", u.select().position(1, 2, 3)
                .add(u.select().position(5, 2, 1)).add(u.select().position(5, 2, 5)), 100);
        sayAt(s, 100, u.vector().topOf(5, 2, 1), true);                                           // 5 линки
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(1, 4, 3, 5, 4, 3));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(2, 4, 3));
        s.world().rotateSection(cannon, 0, 35, 0, 25);
        sayAt(s, 100, u.vector().topOf(4, 4, 3), true);                                           // 6 наведение и огонь
        sayAt(s, 100, u.vector().topOf(4, 4, 3), false);                                          // 7 точность огня
        s.overlay().showControls(u.vector().topOf(5, 2, 1), Pointing.DOWN, 50).rightClick();
        sayAt(s, 100, face(u, 5, 1, 1, Direction.NORTH), true);                                   // 8 ПКМ и Shift+ПКМ
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ настройки вычислителя
    static void computerSettings(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("computer_settings", "");
        s.configureBasePlate(0, 0, 5);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().fromTo(2, 1, 2, 2, 2, 2), Direction.DOWN, 10);
        Vec3 front = face(u, 2, 1, 2, Direction.NORTH);
        s.overlay().showControls(u.vector().topOf(2, 2, 2), Pointing.DOWN, 60).rightClick();
        sayAt(s, 100, front, true);                                                               // 1 экран настроек
        sayAt(s, 140, front, true);                                                               // 2 Огонь
        sayAt(s, 150, front, true);                                                               // 3 Цели
        sayAt(s, 150, front, true);                                                               // 4 Списки
        sayAt(s, 150, front, true);                                                               // 5 Сброс
        sayAt(s, 150, front, true);                                                               // 6 Выбор
        sayAt(s, 120, front, true);                                                               // 7 Свой-чужой
        sayAt(s, 150, front, true);                                                               // 8 Перехват
        sayAt(s, 120, front, true);                                                               // 9 Пушки
        sayAt(s, 130, front, true);                                                               // 10 Состояние
        s.overlay().showControls(u.vector().topOf(2, 2, 2), Pointing.DOWN, 60).rightClick().whileSneaking();
        sayAt(s, 130, front, true);                                                               // 11 Shift+ПКМ, очки, Display Link
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ контроллеры наводки
    static void aimControllers(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("aim_controllers", "");
        s.configureBasePlate(0, 0, 5);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(2, 2, 2), Direction.DOWN, 5);
        show(s, u.select().fromTo(1, 4, 2, 4, 4, 2), Direction.DOWN, 10);
        show(s, u.select().position(2, 1, 2), Direction.EAST, 10);
        sayAt(s, 90, face(u, 2, 1, 2, Direction.WEST), true);                                     // 1 горизонтальный
        show(s, u.select().fromTo(2, 2, 3, 2, 2, 4), Direction.NORTH, 10);
        sayAt(s, 90, face(u, 2, 2, 3, Direction.WEST), true);                                     // 2 вертикальный
        Selection kin = u.select().position(2, 0, 2).add(u.select().position(2, 1, 2)).add(u.select().fromTo(2, 2, 3, 2, 2, 4));
        s.world().setKineticSpeed(kin, 32);
        sayAt(s, 90, u.vector().topOf(2, 0, 2), true);                                            // 3 скорость
        s.world().setKineticSpeed(kin, 256);
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(1, 4, 2, 4, 4, 2));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(2, 4, 2));
        s.world().rotateSection(cannon, 0, 60, 0, 7);
        sayAt(s, 100, u.vector().topOf(3, 4, 2), false);                                          // 4 256 об/мин
        sayAt(s, 100, face(u, 2, 2, 3, Direction.WEST), false);                                   // 5 нагрузка, станок не крутить валами
        s.world().setKineticSpeed(kin, 0);
        sayAt(s, 90, face(u, 2, 2, 3, Direction.WEST), true);                                     // 6 без вращения
        s.overlay().showControls(face(u, 2, 1, 2, Direction.WEST), Pointing.LEFT, 60).rightClick();
        sayAt(s, 130, face(u, 2, 1, 2, Direction.WEST), true);                                    // 7 пределы поворота
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ радарная сеть
    static void network(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("network", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(1, 1, 1), Direction.DOWN, 3);
        show(s, u.select().position(5, 1, 1), Direction.DOWN, 3);
        show(s, u.select().position(5, 1, 5), Direction.DOWN, 10);
        sayAt(s, 90, u.vector().topOf(1, 1, 1), true);                                            // 1 линки как передатчики
        s.overlay().showControls(u.vector().topOf(1, 1, 1), Pointing.DOWN, 40).rightClick()
                .withItem(stack(FcsRegistry.RADAR_LINK_ITEM.get()));
        s.idle(10);
        show(s, u.select().position(1, 2, 1), Direction.DOWN, 10);
        sayAt(s, 90, u.vector().topOf(1, 2, 1), true);                                            // 2 новая сеть
        s.overlay().showControls(u.vector().topOf(1, 2, 1), Pointing.DOWN, 40).rightClick()
                .withItem(stack(FcsRegistry.RADAR_LINK_ITEM.get()));
        sayAt(s, 90, u.vector().topOf(1, 2, 1), false);                                           // 3 настроить предмет
        show(s, u.select().position(4, 1, 1), Direction.EAST, 3);
        show(s, u.select().position(5, 2, 5), Direction.DOWN, 10);
        s.overlay().showOutline(PonderPalette.GREEN, "net", u.select().position(1, 2, 1)
                .add(u.select().position(4, 1, 1)).add(u.select().position(5, 2, 5)), 100);
        sayAt(s, 100, u.vector().topOf(5, 2, 5), true);                                           // 4 сеть без дальности
        sayAt(s, 100, u.vector().topOf(5, 2, 5), false);                                          // 5 очки инженера, несколько сетей
        show(s, u.select().fromTo(1, 1, 5, 1, 2, 5), Direction.DOWN, 10);
        sayAt(s, 110, face(u, 1, 1, 5, Direction.NORTH), true);                                   // 6 лимит и память
        show(s, u.select().position(2, 2, 1), Direction.DOWN, 5);
        s.effects().indicateRedstone(new BlockPos(1, 2, 1));
        sayAt(s, 80, u.vector().topOf(1, 2, 1), true);                                            // 7 сигнал 15
        s.world().hideSection(u.select().position(2, 2, 1), Direction.UP);
        s.idle(10);
        sayAt(s, 80, u.vector().topOf(1, 2, 1), false);                                           // 8 сброс предмета
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ радарный монитор
    static void radarMonitor(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("radar_monitor", "");
        s.configureBasePlate(0, 0, 5);
        s.showBasePlate();
        s.idle(10);
        for (int x = 1; x <= 3; x++) {
            show(s, u.select().position(x, 1, 2), Direction.DOWN, 2);
            show(s, u.select().position(x, 2, 2), Direction.DOWN, 2);
        }
        s.idle(5);
        Vec3 screen = face(u, 2, 2, 2, Direction.NORTH);
        sayAt(s, 90, screen, true);                                                               // 1 стена
        show(s, u.select().position(2, 1, 3), Direction.NORTH, 10);
        sayAt(s, 100, face(u, 2, 1, 3, Direction.UP), true);                                      // 2 линк, карта
        sayAt(s, 120, screen, false);                                                             // 3 что на карте
        s.overlay().showControls(screen, Pointing.DOWN, 50).rightClick().whileSneaking();
        sayAt(s, 110, screen, true);                                                              // 4 окно: Экран
        sayAt(s, 120, screen, false);                                                             // 5 Цели, назначение
        sayAt(s, 130, screen, false);                                                             // 6 Вид
        sayAt(s, 120, screen, false);                                                             // 7 Метки
        sayAt(s, 110, face(u, 1, 1, 2, Direction.NORTH), true);                                   // 8 в мире: колесо
        sayAt(s, 80, face(u, 3, 1, 2, Direction.NORTH), false);                                   // 9 питание
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ транспондер
    static void transponder(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("transponder", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().fromTo(2, 1, 2, 4, 1, 4), Direction.DOWN, 5);
        show(s, u.select().position(3, 2, 3), Direction.DOWN, 10);
        Vec3 top = u.vector().topOf(3, 2, 3);
        sayAt(s, 90, top, true);                                                                  // 1 свои
        s.overlay().showControls(top, Pointing.DOWN, 50).rightClick();
        sayAt(s, 110, top, true);                                                                 // 2 имя и код
        s.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "zone", new AABB(0.5, 1, 0.5, 6.5, 4.5, 6.5), 120);
        sayAt(s, 120, new Vec3(5.5, 3, 5.5), true);                                               // 3 режим «радиус»
        s.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "zone", new AABB(1.5, 1, 1.5, 5.5, 3, 5.5), 120);
        sayAt(s, 120, new Vec3(5.5, 2, 5.5), false);                                              // 4 «сублевел», «сублевел и расстояние»
        sayAt(s, 120, top, true);                                                                 // 5 наследование
        sayAt(s, 110, top, true);                                                                 // 6 коды у вычислителя
        sayAt(s, 100, top, false);                                                                // 7 доступ, питание
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ камера
    static void camera(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("camera", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().fromTo(1, 1, 4, 1, 3, 4), Direction.DOWN, 5);
        show(s, u.select().position(2, 3, 4), Direction.WEST, 10);
        Vec3 cam = u.vector().centerOf(2, 3, 4);
        sayAt(s, 90, cam, true);                                                                  // 1 камера
        show(s, u.select().fromTo(4, 1, 1, 4, 2, 1), Direction.DOWN, 10);
        s.overlay().showControls(u.vector().topOf(4, 2, 1), Pointing.DOWN, 50).rightClick()
                .withItem(stack(FcsRegistry.CAMERA_ITEM.get()));
        sayAt(s, 110, u.vector().topOf(4, 2, 1), true);                                           // 2 подключение без линка
        Vec3 mon = face(u, 4, 1, 1, Direction.NORTH);
        s.overlay().showControls(mon, Pointing.DOWN, 50).rightClick();
        sayAt(s, 110, mon, true);                                                                 // 3 монитор: список, имя
        s.overlay().showLine(PonderPalette.RED, cam, new Vec3(6.5, 1.2, 6.5), 110);
        sayAt(s, 110, cam, true);                                                                 // 4 вид и наведение
        sayAt(s, 100, cam, false);                                                                // 5 ЛКМ и Shift
        sayAt(s, 110, mon, true);                                                                 // 6 «Орудия»
        sayAt(s, 100, cam, false);                                                                // 7 взгляд на само орудие
        sayAt(s, 90, mon, false);                                                                 // 8 питание, выход
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ своё орудие на подшипниках-шарнирах
    static void swivelTurret(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("swivel_turret", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(3, 2, 3), Direction.DOWN, 10);
        sayAt(s, 100, face(u, 3, 2, 3, Direction.WEST), true);                                    // 1 подшипник курса
        show(s, u.select().fromTo(1, 1, 3, 1, 2, 3), Direction.DOWN, 3);
        show(s, u.select().position(2, 2, 3), Direction.DOWN, 10);
        Selection yawKin = u.select().position(1, 0, 3).add(u.select().fromTo(1, 1, 3, 1, 2, 3)).add(u.select().position(2, 2, 3));
        sayAt(s, 110, face(u, 1, 1, 3, Direction.WEST), true);                                    // 2 привод курса через шестерню
        ElementLink<WorldSectionElement> top = s.world().showIndependentSection(
                u.select().fromTo(2, 3, 3, 3, 3, 4).add(u.select().position(3, 4, 3)), Direction.DOWN);
        s.idle(10);
        sayAt(s, 110, u.vector().topOf(3, 4, 3), true);                                           // 3 клей и сборка
        s.world().showSectionAndMerge(u.select().position(3, 4, 2), Direction.SOUTH, top);
        s.idle(10);
        sayAt(s, 110, face(u, 3, 4, 2, Direction.NORTH), true);                                   // 4 подшипник наклона
        s.world().showSectionAndMerge(u.select().fromTo(2, 4, 2, 2, 4, 4), Direction.DOWN, top);
        s.idle(10);
        sayAt(s, 120, face(u, 2, 4, 3, Direction.WEST), true);                                    // 5 привод наклона
        ElementLink<WorldSectionElement> barrel = s.world().showIndependentSection(u.select().fromTo(3, 4, 1, 6, 4, 1), Direction.SOUTH);
        s.idle(10);
        s.world().showSectionAndMerge(u.select().position(4, 5, 1), Direction.DOWN, barrel);
        s.idle(10);
        sayAt(s, 120, face(u, 4, 5, 1, Direction.NORTH), true);                                   // 6 ствол и прицел
        show(s, u.select().position(0, 1, 3), Direction.EAST, 3);
        s.world().showSectionAndMerge(u.select().position(2, 5, 3), Direction.DOWN, top);
        s.world().showSectionAndMerge(u.select().position(4, 6, 1), Direction.DOWN, barrel);
        s.idle(10);
        sayAt(s, 110, u.vector().topOf(4, 6, 1), true);                                           // 7 орудийные линки
        show(s, u.select().position(1, 1, 2), Direction.SOUTH, 10);
        sayAt(s, 100, face(u, 1, 1, 2, Direction.UP), true);                                      // 8 радарный линк
        s.world().setKineticSpeed(yawKin, 32);
        s.world().configureCenterOfRotation(top, u.vector().centerOf(3, 3, 3));
        s.world().configureCenterOfRotation(barrel, u.vector().centerOf(3, 4, 3));
        s.world().rotateSection(top, 0, 40, 0, 40);
        s.world().rotateSection(barrel, 0, 40, 0, 40);
        s.idle(40);
        s.world().setKineticSpeed(yawKin, 0);
        sayAt(s, 120, u.vector().topOf(4, 4, 1), true);                                           // 9 обучение приводов
        s.effects().indicateRedstone(new BlockPos(4, 5, 1));
        sayAt(s, 120, face(u, 4, 5, 1, Direction.NORTH), true);                                   // 10 сигнал прицела, огонь
        s.overlay().showControls(u.vector().topOf(4, 6, 1), Pointing.DOWN, 50).rightClick().whileSneaking();
        sayAt(s, 130, u.vector().topOf(4, 6, 1), true);                                           // 11 настройки орудия: тип кастом
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ вращатель орудия (свой блок, нужен Sable)
    static void gunRotator(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("gun_rotator", "");
        s.configureBasePlate(0, 0, 5);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(2, 1, 2), Direction.DOWN, 10);
        s.world().setKineticSpeed(u.select().position(2, 0, 2).add(u.select().position(2, 1, 2)), 32);
        sayAt(s, 100, face(u, 2, 1, 2, Direction.WEST), true);                                    // 1 вращатель
        ElementLink<WorldSectionElement> top = s.world().showIndependentSection(u.select().fromTo(2, 2, 2, 4, 2, 2), Direction.DOWN);
        s.idle(10);
        s.overlay().showControls(face(u, 2, 1, 2, Direction.WEST), Pointing.LEFT, 40).rightClick();
        sayAt(s, 110, u.vector().topOf(3, 2, 2), true);                                           // 2 сборка, схема
        s.world().showSectionAndMerge(u.select().position(3, 3, 2), Direction.DOWN, top);
        s.idle(10);
        sayAt(s, 100, face(u, 3, 3, 2, Direction.NORTH), true);                                   // 3 прицел
        s.world().showSectionAndMerge(u.select().position(3, 4, 2), Direction.DOWN, top);
        show(s, u.select().position(1, 1, 2), Direction.EAST, 10);
        show(s, u.select().position(2, 1, 1), Direction.SOUTH, 10);
        sayAt(s, 110, u.vector().topOf(3, 4, 2), true);                                           // 4 линки
        s.world().configureCenterOfRotation(top, u.vector().centerOf(2, 2, 2));
        s.world().rotateSection(top, 0, 45, 0, 30);
        s.idle(30);
        sayAt(s, 100, u.vector().centerOf(3, 3, 2), true);                                        // 5 когда что выбрать
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ привод орудия
    static void gunDrive(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("gun_drive", "");
        s.configureBasePlate(0, 0, 7);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(3, 2, 2), Direction.DOWN, 5);
        show(s, u.select().fromTo(2, 4, 2, 6, 4, 2), Direction.DOWN, 10);
        show(s, u.select().position(3, 1, 2), Direction.EAST, 10);
        sayAt(s, 110, face(u, 3, 1, 2, Direction.WEST), true);                                    // 1 привод
        show(s, u.select().fromTo(3, 2, 3, 3, 2, 4), Direction.NORTH, 10);
        s.overlay().showControls(face(u, 3, 2, 3, Direction.UP), Pointing.DOWN, 50).rightClick();
        sayAt(s, 100, face(u, 3, 2, 3, Direction.WEST), true);                                    // 2 курс/наклон
        Selection yaw = u.select().position(3, 0, 2).add(u.select().position(3, 1, 2));
        Selection pitch = u.select().fromTo(3, 2, 3, 3, 2, 4);
        s.world().setKineticSpeed(yaw, 64);
        s.world().setKineticSpeed(pitch, 64);
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(2, 4, 2, 6, 4, 2));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(3, 4, 2));
        s.world().rotateSection(cannon, 0, 30, 0, 30);
        sayAt(s, 110, u.vector().topOf(4, 4, 2), true);                                           // 3 обучение
        s.overlay().showControls(face(u, 3, 1, 2, Direction.WEST), Pointing.LEFT, 60).rightClick();
        sayAt(s, 130, face(u, 3, 1, 2, Direction.WEST), true);                                    // 4 пределы поворота
        s.world().setKineticSpeed(yaw, 0);
        s.world().setKineticSpeed(pitch, 0);
        show(s, u.select().position(2, 2, 2), Direction.EAST, 3);
        show(s, u.select().position(2, 1, 2), Direction.EAST, 3);
        show(s, u.select().position(4, 2, 3), Direction.WEST, 10);
        Vec3 link = face(u, 2, 2, 2, Direction.UP);
        sayAt(s, 100, link, true);                                                                // 5 орудийные линки
        s.overlay().showControls(link, Pointing.DOWN, 50).rightClick().whileSneaking();
        sayAt(s, 110, link, true);                                                                // 6 настройки: имя
        sayAt(s, 150, link, false);                                                               // 7 тип
        sayAt(s, 120, link, false);                                                               // 8 автоприцеливание и стрельба
        sayAt(s, 120, link, false);                                                               // 9 основная пушка
        sayAt(s, 100, face(u, 3, 2, 2, Direction.WEST), true);                                    // 10 огонь станком, радарный линк
        s.markAsFinished();
    }
}
