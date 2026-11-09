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
 */
final class FcsScenes {
    private FcsScenes() {}

    /** Подпись (текст - из перевода по порядку). */
    private static TextElementBuilder say(SceneBuilder s, int ticks) {
        return s.overlay().showText(ticks).text("");
    }

    private static void show(SceneBuilder s, Selection sel, Direction from, int idle) {
        s.world().showSection(sel, from);
        s.idle(idle);
    }

    private static ItemStack stack(net.minecraft.world.level.ItemLike item) {
        return new ItemStack(item);
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
        say(s, 80).pointAt(u.vector().topOf(3, 4, 3)).placeNearTarget().attachKeyFrame();          // 1 пушка
        s.idle(90);
        show(s, u.select().position(2, 1, 3), Direction.EAST, 5);
        show(s, u.select().fromTo(2, 2, 4, 2, 2, 5), Direction.NORTH, 5);
        s.world().setKineticSpeed(u.select().position(2, 0, 3).add(u.select().position(2, 1, 3)), 64);
        s.world().setKineticSpeed(u.select().fromTo(2, 2, 4, 2, 2, 5), 64);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 4), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 2 контроллеры
        s.idle(100);
        show(s, u.select().position(5, 1, 5), Direction.DOWN, 10);
        say(s, 70).pointAt(u.vector().topOf(5, 1, 5)).placeNearTarget().attachKeyFrame();          // 3 радар
        s.idle(80);
        show(s, u.select().position(5, 1, 1), Direction.DOWN, 10);
        say(s, 80).pointAt(u.vector().blockSurface(new BlockPos(5, 1, 1), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 4 вычислитель
        s.idle(90);
        show(s, u.select().position(1, 2, 3), Direction.EAST, 3);
        show(s, u.select().position(5, 2, 1), Direction.DOWN, 3);
        show(s, u.select().position(5, 2, 5), Direction.DOWN, 5);
        s.overlay().showOutline(PonderPalette.GREEN, "net", u.select().position(1, 2, 3)
                .add(u.select().position(5, 2, 1)).add(u.select().position(5, 2, 5)), 90);
        say(s, 90).pointAt(u.vector().topOf(5, 2, 1)).placeNearTarget().attachKeyFrame();          // 5 линки
        s.idle(100);
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(1, 4, 3, 5, 4, 3));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(2, 4, 3));
        s.world().rotateSection(cannon, 0, 35, 0, 25);
        say(s, 90).pointAt(u.vector().topOf(4, 4, 3)).placeNearTarget().attachKeyFrame();          // 6 наведение и огонь
        s.idle(100);
        s.overlay().showControls(u.vector().topOf(5, 2, 1), Pointing.DOWN, 50).rightClick();
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(5, 1, 1), Direction.WEST)).placeNearTarget(); // 7 ПКМ и питание
        s.idle(100);
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
        say(s, 80).pointAt(u.vector().blockSurface(new BlockPos(2, 1, 2), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 1 горизонтальный
        s.idle(90);
        show(s, u.select().fromTo(2, 2, 3, 2, 2, 4), Direction.NORTH, 10);
        say(s, 80).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 3), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 2 вертикальный
        s.idle(90);
        Selection kin = u.select().position(2, 0, 2).add(u.select().position(2, 1, 2)).add(u.select().fromTo(2, 2, 3, 2, 2, 4));
        s.world().setKineticSpeed(kin, 32);
        say(s, 80).pointAt(u.vector().topOf(2, 0, 2)).placeNearTarget().attachKeyFrame();          // 3 скорость
        s.idle(90);
        s.world().setKineticSpeed(kin, 256);
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(1, 4, 2, 4, 4, 2));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(2, 4, 2));
        s.world().rotateSection(cannon, 0, 60, 0, 7);
        say(s, 90).pointAt(u.vector().topOf(3, 4, 2)).placeNearTarget();                          // 4 256 об/мин
        s.idle(100);
        s.world().setKineticSpeed(kin, 0);
        say(s, 80).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 3), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 5 без вращения
        s.idle(90);
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
        say(s, 80).pointAt(u.vector().topOf(1, 1, 1)).placeNearTarget().attachKeyFrame();          // 1 линки как передатчики
        s.idle(90);
        s.overlay().showControls(u.vector().topOf(1, 1, 1), Pointing.DOWN, 40).rightClick()
                .withItem(stack(FcsRegistry.RADAR_LINK_ITEM.get()));
        s.idle(10);
        show(s, u.select().position(1, 2, 1), Direction.DOWN, 10);
        say(s, 80).pointAt(u.vector().topOf(1, 2, 1)).placeNearTarget().attachKeyFrame();          // 2 новая сеть
        s.idle(90);
        s.overlay().showControls(u.vector().topOf(1, 2, 1), Pointing.DOWN, 40).rightClick()
                .withItem(stack(FcsRegistry.RADAR_LINK_ITEM.get()));
        say(s, 80).pointAt(u.vector().topOf(1, 2, 1)).placeNearTarget();                          // 3 настроить предмет
        s.idle(90);
        show(s, u.select().position(4, 1, 1), Direction.EAST, 3);
        show(s, u.select().position(5, 2, 5), Direction.DOWN, 10);
        s.overlay().showOutline(PonderPalette.GREEN, "net", u.select().position(1, 2, 1)
                .add(u.select().position(4, 1, 1)).add(u.select().position(5, 2, 5)), 90);
        say(s, 90).pointAt(u.vector().topOf(5, 2, 5)).placeNearTarget().attachKeyFrame();          // 4 сеть без дальности
        s.idle(100);
        show(s, u.select().fromTo(1, 1, 5, 1, 2, 5), Direction.DOWN, 10);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(1, 1, 5), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 5 лимит и память
        s.idle(100);
        show(s, u.select().position(2, 2, 1), Direction.DOWN, 5);
        s.effects().indicateRedstone(new BlockPos(1, 2, 1));
        say(s, 70).pointAt(u.vector().topOf(1, 2, 1)).placeNearTarget().attachKeyFrame();          // 6 сигнал 15
        s.idle(80);
        s.world().hideSection(u.select().position(2, 2, 1), Direction.UP);
        s.idle(10);
        say(s, 70).pointAt(u.vector().topOf(1, 2, 1)).placeNearTarget();                          // 7 сброс предмета
        s.idle(80);
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
        say(s, 80).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.NORTH)).placeNearTarget().attachKeyFrame(); // 1 стена
        s.idle(90);
        show(s, u.select().position(2, 1, 3), Direction.NORTH, 10);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 1, 3), Direction.UP)).placeNearTarget().attachKeyFrame(); // 2 линк, карта
        s.idle(100);
        s.overlay().showControls(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.NORTH), Pointing.DOWN, 50)
                .rightClick().whileSneaking();
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.NORTH)).placeNearTarget().attachKeyFrame(); // 3 окно
        s.idle(100);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(1, 1, 2), Direction.NORTH)).placeNearTarget(); // 4 назначить, исключить
        s.idle(100);
        say(s, 70).pointAt(u.vector().blockSurface(new BlockPos(3, 1, 2), Direction.NORTH)).placeNearTarget(); // 5 питание
        s.idle(80);
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
        say(s, 80).pointAt(u.vector().topOf(3, 2, 3)).placeNearTarget().attachKeyFrame();          // 1 свои
        s.idle(90);
        s.overlay().showControls(u.vector().topOf(3, 2, 3), Pointing.DOWN, 50).rightClick();
        say(s, 90).pointAt(u.vector().topOf(3, 2, 3)).placeNearTarget().attachKeyFrame();          // 2 экран
        s.idle(100);
        s.overlay().chaseBoundingBoxOutline(PonderPalette.GREEN, "zone", new AABB(0.5, 1, 0.5, 6.5, 4.5, 6.5), 100);
        say(s, 100).pointAt(new Vec3(5.5, 3, 5.5)).placeNearTarget().attachKeyFrame();             // 3 режимы зоны
        s.idle(110);
        say(s, 90).pointAt(u.vector().topOf(3, 2, 3)).placeNearTarget();                          // 4 список кодов
        s.idle(100);
        say(s, 90).pointAt(u.vector().topOf(3, 2, 3)).placeNearTarget().attachKeyFrame();          // 5 наследование, питание
        s.idle(100);
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
        say(s, 80).pointAt(u.vector().centerOf(2, 3, 4)).placeNearTarget().attachKeyFrame();       // 1 камера
        s.idle(90);
        show(s, u.select().fromTo(4, 1, 1, 4, 2, 1), Direction.DOWN, 10);
        s.overlay().showControls(u.vector().topOf(4, 2, 1), Pointing.DOWN, 50).rightClick()
                .withItem(stack(FcsRegistry.CAMERA_ITEM.get()));
        say(s, 100).pointAt(u.vector().topOf(4, 2, 1)).placeNearTarget().attachKeyFrame();         // 2 подключение без линка
        s.idle(110);
        s.overlay().showControls(u.vector().blockSurface(new BlockPos(4, 1, 1), Direction.NORTH), Pointing.DOWN, 50).rightClick();
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(4, 1, 1), Direction.NORTH)).placeNearTarget().attachKeyFrame(); // 3 монитор
        s.idle(100);
        s.overlay().showLine(PonderPalette.RED, u.vector().centerOf(2, 3, 4), new Vec3(6.5, 1.2, 6.5), 90);
        say(s, 90).pointAt(u.vector().centerOf(2, 3, 4)).placeNearTarget().attachKeyFrame();       // 4 наведение и ЛКМ
        s.idle(100);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(4, 1, 1), Direction.NORTH)).placeNearTarget(); // 5 орудия камеры
        s.idle(100);
        say(s, 80).pointAt(u.vector().centerOf(2, 3, 4)).placeNearTarget();                       // 6 взгляд на само орудие
        s.idle(90);
        s.markAsFinished();
    }

    // ------------------------------------------------------------------ орудие на вращателях
    static void turret(SceneBuilder builder, SceneBuildingUtil u) {
        CreateSceneBuilder s = new CreateSceneBuilder(builder);
        s.title("turret", "");
        s.configureBasePlate(0, 0, 5);
        s.showBasePlate();
        s.idle(10);
        show(s, u.select().position(2, 1, 2), Direction.DOWN, 10);
        s.world().setKineticSpeed(u.select().position(2, 0, 2).add(u.select().position(2, 1, 2)), 32);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 1, 2), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 1 вращатель
        s.idle(100);
        ElementLink<WorldSectionElement> top = s.world().showIndependentSection(u.select().fromTo(2, 2, 2, 4, 2, 2), Direction.DOWN);
        s.idle(10);
        s.overlay().showControls(u.vector().blockSurface(new BlockPos(2, 1, 2), Direction.WEST), Pointing.LEFT, 40).rightClick();
        say(s, 100).pointAt(u.vector().topOf(3, 2, 2)).placeNearTarget().attachKeyFrame();         // 2 сборка, схема
        s.idle(110);
        s.world().showSectionAndMerge(u.select().position(3, 3, 2), Direction.DOWN, top);
        s.idle(10);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(3, 3, 2), Direction.EAST)).placeNearTarget().attachKeyFrame(); // 3 прицел
        s.idle(100);
        s.world().showSectionAndMerge(u.select().position(3, 4, 2), Direction.DOWN, top);
        show(s, u.select().position(1, 1, 2), Direction.EAST, 10);
        say(s, 90).pointAt(u.vector().topOf(3, 4, 2)).placeNearTarget().attachKeyFrame();          // 4 орудийный линк
        s.idle(100);
        show(s, u.select().position(2, 1, 1), Direction.SOUTH, 10);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 1, 1), Direction.UP)).placeNearTarget().attachKeyFrame(); // 5 радарный линк
        s.idle(100);
        s.world().configureCenterOfRotation(top, u.vector().centerOf(2, 2, 2));
        s.world().rotateSection(top, 0, 45, 0, 30);
        s.idle(30);
        s.effects().indicateRedstone(new BlockPos(3, 3, 2));
        say(s, 90).pointAt(u.vector().centerOf(3, 3, 2)).placeNearTarget().attachKeyFrame();       // 6 сигнал прицела
        s.idle(100);
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
        say(s, 100).pointAt(u.vector().blockSurface(new BlockPos(3, 1, 2), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 1 привод
        s.idle(110);
        show(s, u.select().fromTo(3, 2, 3, 3, 2, 4), Direction.NORTH, 10);
        s.overlay().showControls(u.vector().blockSurface(new BlockPos(3, 2, 3), Direction.UP), Pointing.DOWN, 50).rightClick();
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(3, 2, 3), Direction.WEST)).placeNearTarget().attachKeyFrame(); // 2 курс/наклон
        s.idle(100);
        Selection yaw = u.select().position(3, 0, 2).add(u.select().position(3, 1, 2));
        Selection pitch = u.select().fromTo(3, 2, 3, 3, 2, 4);
        s.world().setKineticSpeed(yaw, 64);
        s.world().setKineticSpeed(pitch, 64);
        ElementLink<WorldSectionElement> cannon = s.world().makeSectionIndependent(u.select().fromTo(2, 4, 2, 6, 4, 2));
        s.world().configureCenterOfRotation(cannon, u.vector().centerOf(3, 4, 2));
        s.world().rotateSection(cannon, 0, 30, 0, 30);
        say(s, 90).pointAt(u.vector().topOf(4, 4, 2)).placeNearTarget().attachKeyFrame();          // 3 обучение
        s.idle(100);
        s.world().setKineticSpeed(yaw, 0);
        s.world().setKineticSpeed(pitch, 0);
        show(s, u.select().position(2, 2, 2), Direction.EAST, 3);
        show(s, u.select().position(2, 1, 2), Direction.EAST, 3);
        show(s, u.select().position(4, 2, 3), Direction.WEST, 10);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.UP)).placeNearTarget().attachKeyFrame(); // 4 орудийные линки
        s.idle(100);
        s.overlay().showControls(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.UP), Pointing.DOWN, 50)
                .rightClick().whileSneaking();
        say(s, 110).pointAt(u.vector().blockSurface(new BlockPos(2, 2, 2), Direction.UP)).placeNearTarget().attachKeyFrame(); // 5 настройки орудия
        s.idle(120);
        say(s, 90).pointAt(u.vector().blockSurface(new BlockPos(3, 2, 2), Direction.WEST)).placeNearTarget(); // 6 огонь станком, радарный линк
        s.idle(100);
        s.markAsFinished();
    }
}
