package dev.radarfcs.client.ponder;

import dev.radarfcs.FcsMod;
import dev.radarfcs.registry.FcsRegistry;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ItemLike;

/**
 * Обучающие сцены Ponder (как в Create: удерживать W над предметом). Схемы - assets/radar_fcs/ponder/*.nbt
 * (генератор devtools/ponder_schematics.py), тексты - ключи radar_fcs.ponder.&lt;сцена&gt;.header / text_N в файлах
 * перевода (генератор devtools/ponder_lang.py). Класс грузится только на клиенте.
 */
public final class FcsPonderPlugin implements PonderPlugin {
    public static final ResourceLocation TAG = ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, "fire_control");

    /** Вызывается из конструктора мода на клиенте - как это делает Create. */
    public static void init() {
        PonderIndex.addPlugin(new FcsPonderPlugin());
    }

    @Override
    public String getModId() {
        return FcsMod.MODID;
    }

    private static ResourceLocation id(ItemLike item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.asItem());
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // своё орудие на Sable - на подшипниках-шарнирах Create Aeronautics (Simulated), если он установлен
        boolean swivel = net.neoforged.fml.ModList.get().isLoaded("simulated");
        helper.forComponents(id(FcsRegistry.FIRE_CONTROL.get()))
                .addStoryBoard("battery", FcsScenes::battery, TAG)
                .addStoryBoard("computer", FcsScenes::computerSettings, TAG)
                .addStoryBoard("network", FcsScenes::network, TAG);
        helper.forComponents(id(FcsRegistry.RADAR_LINK_ITEM.get()))
                .addStoryBoard("network", FcsScenes::network, TAG)
                .addStoryBoard("battery", FcsScenes::battery, TAG);
        helper.forComponents(id(FcsRegistry.YAW_CONTROLLER.get()), id(FcsRegistry.PITCH_CONTROLLER.get()))
                .addStoryBoard("aim_controllers", FcsScenes::aimControllers, TAG);
        helper.forComponents(id(FcsRegistry.MEMORY.get()))
                .addStoryBoard("network", FcsScenes::network, TAG);
        helper.forComponents(id(FcsRegistry.RADAR_MONITOR.get()))
                .addStoryBoard("radar_monitor", FcsScenes::radarMonitor, TAG);
        helper.forComponents(id(FcsRegistry.IFF_TRANSPONDER.get()))
                .addStoryBoard("transponder", FcsScenes::transponder, TAG);
        helper.forComponents(id(FcsRegistry.CAMERA_ITEM.get()), id(FcsRegistry.CAMERA_MONITOR.get()))
                .addStoryBoard("camera", FcsScenes::camera, TAG);
        var gun = helper.forComponents(id(FcsRegistry.SIGHT.get()), id(FcsRegistry.GUN_LINK_ITEM.get()), id(FcsRegistry.GUN_DRIVE.get()));
        if (swivel) gun.addStoryBoard("swivel_turret", FcsScenes::swivelTurret, TAG);
        gun.addStoryBoard("gun_drive", FcsScenes::gunDrive, TAG);
        var rotator = helper.forComponents(id(FcsRegistry.GUN_ROTATOR.get()));
        if (swivel) rotator.addStoryBoard("swivel_turret", FcsScenes::swivelTurret, TAG);
        rotator.addStoryBoard("gun_rotator", FcsScenes::gunRotator, TAG);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TAG)
                .addToIndex()
                .item(FcsRegistry.FIRE_CONTROL.get(), true, false)
                .title("Fire Control")
                .description("Radar fire control: computer, links, aim controllers, monitors, cameras and guns")
                .register();
        helper.addToTag(TAG)
                .add(id(FcsRegistry.FIRE_CONTROL.get()))
                .add(id(FcsRegistry.RADAR_LINK_ITEM.get()))
                .add(id(FcsRegistry.YAW_CONTROLLER.get()))
                .add(id(FcsRegistry.PITCH_CONTROLLER.get()))
                .add(id(FcsRegistry.MEMORY.get()))
                .add(id(FcsRegistry.RADAR_MONITOR.get()))
                .add(id(FcsRegistry.IFF_TRANSPONDER.get()))
                .add(id(FcsRegistry.CAMERA_ITEM.get()))
                .add(id(FcsRegistry.CAMERA_MONITOR.get()))
                .add(id(FcsRegistry.GUN_ROTATOR.get()))
                .add(id(FcsRegistry.SIGHT.get()))
                .add(id(FcsRegistry.GUN_LINK_ITEM.get()))
                .add(id(FcsRegistry.GUN_DRIVE.get()));
    }
}
