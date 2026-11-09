package dev.radarfcs.gametest;

import net.minecraft.world.phys.Vec3;

import dev.radarfcs.FcsMod;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.block.FireControlBlock;
import dev.radarfcs.block.IffTransponderBlock;
import dev.radarfcs.registry.FcsRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import rbasamoyai.createbigcannons.cannons.ICannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;

/**
 * Dev-клиент: -Dradar_fcs.screenshot=true строит сцену в мире, ждёт наведения и делает скриншоты
 * (общий вид, вид с очками инженера на вычислитель), затем закрывает игру.
 */
@EventBusSubscriber(modid = FcsMod.MODID, value = Dist.CLIENT)
public final class DevScreenshots {
    private static int tick = -1;
    private static Mob husk;
    private static BlockPos base;

    private DevScreenshots() {}

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("radar_fcs.screenshot")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
        tick++;
        if (Boolean.getBoolean("radar_fcs.screenshot.ponder")) {
            ponderTour(mc);
            return;
        }
        var server = mc.getSingleplayerServer();
        // каждый запуск - на новом месте плоского мира, чтобы сцены прошлых запусков не попадали в кадр
        if (tick == 2) server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), p.getX() + 640, p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        });
        if (tick == 20) server.execute(() -> build(server.overworld(), server.getPlayerList().getPlayers().get(0)));
        if (husk != null && tick > 20) {
            final int t = tick;
            server.execute(() -> {
                int ph = t % 96;
                double off = ph < 48 ? ph * 0.125 : (96 - ph) * 0.125;          // туда-обратно 6 блоков, 2.5 бл/с
                if (husk.isAlive()) husk.setPos(base.getX() + 36.5, base.getY(), base.getZ() - 6 + off);
            });
        }
        if (tick == 100) server.execute(() -> {
            // подписи имён с транспондеров на экране монитора - всегда
            if (server.overworld().getBlockEntity(base.offset(-3, 0, -3)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity mon) {
                mon.setLabels(dev.radarfcs.block.RadarMonitorBlockEntity.LABELS_ALWAYS);
                // метки игрока на карте (только для глаз)
                while (!mon.pins().isEmpty()) mon.removePin(0);
                mon.setPin(-1, (base.getX() + 30) + ";" + (base.getZ() + 25) + ";3;Сбор", null);
                mon.setPin(-1, (base.getX() - 20) + ";" + (base.getZ() - 40) + ";8;Мост", null);
                mon.setPin(-1, (base.getX() + 45) + ";" + (base.getZ() - 30) + ";4;", null);
            }
        });
        if (tick == 40 && radarBearing != null) server.execute(() -> {
            var be = server.overworld().getBlockEntity(radarBearing);
            try {
                if (be != null) be.getClass().getMethod("assemble").invoke(be);
            } catch (ReflectiveOperationException | RuntimeException e) {
                FcsMod.LOG.warn("[radar_fcs dev] radar bearing not assembled: {}", e.toString());
            }
        });
        if (tick == 50) shot(mc, "fcs_aiming");
        if (tick == 200) shot(mc, "fcs_overview");
        if (tick == 240) server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            // встать перед вычислителем и посмотреть на него в очках инженера
            p.teleportTo(server.overworld(), base.getX() - 2.5, base.getY(), base.getZ() + 3.0, -130f, 19f);
        });
        if (tick == 280) shot(mc, "fcs_goggles");
        if (tick == 300) server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().get(0);
            p.teleportTo(server.overworld(), base.getX() + 12.5, base.getY() + 1.0, base.getZ() - 1.5, 140f, 14f);
        });
        if (tick == 330) shot(mc, "fcs_transponders");
        // монитор на линках с радаром Create Radars и вычислителем
        // взгляд на отметку цели - сведения о ней на экране
        if (tick == 332) server.execute(() -> {
            Vec3 pt = new Vec3(base.getX() - 3.5, base.getY() + 1.0, base.getZ() - 1.5);
            if (husk != null && husk.isAlive()
                    && server.overworld().getBlockEntity(base.offset(-3, 0, -3)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity mon) {
                pt = mon.master().screenPointFor(husk.getBoundingBox().getCenter());
            }
            look(server, pt, new Vec3(base.getX() - 7.5, base.getY() + 1.4, base.getZ() - 1.5));
        });
        if (tick == 338) shot(mc, "radar_monitor");
        if (tick == 339) shot(mc, "radar_monitor_t1");
        if (tick == 340) shot(mc, "radar_monitor_t2");
        // исключить цель из огня (как нажатием колеса): на экране - перечёркнута
        if (tick == 345) server.execute(() -> {
            if (husk != null && server.overworld().getBlockEntity(base.offset(-3, 0, -3)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity mon) {
                mon.toggleExclusion(husk.getUUID(), null);
            }
        });
        if (tick == 358) shot(mc, "radar_monitor_b");
        if (tick == 336 || tick == 356) {
            if (mc.level != null && mc.level.getBlockEntity(base.offset(-3, 0, -3)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity mon) {
                FcsMod.LOG.info("[radar_fcs dev] monitor sites at {}: {} range {}", tick, mon.sites(), mon.displayRange());
            }
            server.execute(() -> {
                var be = radarBearing == null ? null : server.overworld().getBlockEntity(radarBearing);
                if (be != null) FcsMod.LOG.info("[radar_fcs dev] bearing {} angle {} speed {} running {}", be.getClass().getSimpleName(),
                        dev.radarfcs.compat.RadarLink.angle(be), dev.radarfcs.compat.RadarLink.sweepSpeed(be), dev.radarfcs.compat.RadarLink.isRunning(be));
            });
        }
        // контроллеры наводки у станка: общий вид и очки инженера
        if (tick == 360) server.execute(() -> look(server, new Vec3(base.getX() + 4.5, base.getY() - 0.2, base.getZ() + 0.9),
                new Vec3(base.getX() + 7.8, base.getY() + 2.2, base.getZ() + 4.2)));
        if (tick == 380) shot(mc, "aim_controllers");
        if (tick == 390) server.execute(() -> look(server, base.offset(4, 0, 1).getCenter(),
                new Vec3(base.getX() + 6.9, base.getY() + 1.75, base.getZ() + 2.4)));
        if (tick == 430) shot(mc, "controller_goggles");
        // экран настроек вычислителя: все вкладки
        if (tick == 440) server.execute(() -> {
            server.getPlayerList().getPlayers().get(0).teleportTo(server.overworld(),
                    base.getX() - 2.5, base.getY(), base.getZ() + 3.0, -130f, 19f);
            // настройки меняются на сервере и доходят до клиента с обновлением блока
            if (server.overworld().getBlockEntity(base) instanceof dev.radarfcs.block.FireControlBlockEntity be) {
                var st = be.settings().copy();
                st.fireMode = dev.radarfcs.core.EngageSettings.FireMode.BURST;
                st.burstLength = 5;
                st.switchMode = dev.radarfcs.core.EngageSettings.SwitchMode.AFTER_SHOTS;
                // у первой пушки - свои цели (вкладка «Пушки»): только снаряды и ракеты, ракеты главнее
                if (!be.mountStates().isEmpty()) {
                    var mt = dev.radarfcs.core.MountTargets.from(st);
                    java.util.Arrays.fill(mt.enabled, false);
                    mt.enabled[dev.radarfcs.core.EngageSettings.Category.SHELLS.ordinal()] = true;
                    mt.enabled[dev.radarfcs.core.EngageSettings.Category.ROCKETS.ordinal()] = true;
                    mt.level[dev.radarfcs.core.EngageSettings.Category.ROCKETS.ordinal()] = 5;
                    st.mountTargets.put(be.mountStates().get(0).pos.asLong(), mt);
                }
                be.applySettings(st, null);
            }
        });
        if (tick >= 470 && tick < 560 && (tick - 470) % 10 == 0) {
            int tab = (tick - 470) / 10;
            // настройки клиентского блока (без кодов «свой-чужой» - примеры кодов подставляются для снимка)
            if (tab == 0 && mc.level != null && mc.level.getBlockEntity(base) instanceof dev.radarfcs.block.FireControlBlockEntity be) {
                dev.radarfcs.client.FcsClientHooks.openFcsSettings(base, be.settings().copy(), true);
            }
            if (mc.screen instanceof dev.radarfcs.client.FcsSettingsScreen sc) {
                if (tab == 2) sc.fillListsForScreenshot();
                sc.showTab(tab);
            }
        }
        if (tick >= 475 && tick < 565 && (tick - 475) % 10 == 0) shot(mc, "fcs_settings_" + (tick - 475) / 10);
        // радарный линк: настроенный на сеть сцены линк в руке, взгляд на линк станка - вся сеть подсвечена
        if (tick == 568) {
            if (mc.screen != null) mc.setScreen(null);
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                ItemStack st = new ItemStack(FcsRegistry.RADAR_LINK_ITEM.get(), 8);
                dev.radarfcs.item.RadarLinkItem.tune(st, SCENE_NET);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, st);
                BlockPos link = base.offset(4, 0, -1);
                look(server, link.getCenter().add(0, 0, 0.3), new Vec3(link.getX() + 1.5, link.getY() + 1.62, link.getZ() - 2.5));
            });
        }
        if (tick == 590) shot(mc, "radar_link");
        // окно монитора: развёртка, список целей, вид (фильтры, значки, подписи)
        if (tick == 600) dev.radarfcs.client.FcsClientHooks.openMonitor(base.offset(-3, 0, -3));
        if (tick == 610) shot(mc, "monitor_gui_0");
        if (tick == 612 && mc.screen instanceof dev.radarfcs.client.RadarMonitorScreen ms) ms.showTab(1);
        if (tick == 618) shot(mc, "monitor_gui_1");
        if (tick == 620 && mc.screen instanceof dev.radarfcs.client.RadarMonitorScreen ms) ms.showTab(2);
        if (tick == 623) shot(mc, "monitor_gui_pins");
        if (tick == 625 && mc.screen instanceof dev.radarfcs.client.RadarMonitorScreen ms) ms.showTab(3);
        if (tick == 628) shot(mc, "monitor_gui_2");
        // экран транспондера: основное и наследование
        if (tick == 630) dev.radarfcs.client.FcsClientHooks.openTransponder(new dev.radarfcs.net.TransponderPayloads.Open(
                base.offset(8, 0, -3), new dev.radarfcs.iff.TransponderSettings("2077", "Аврора", dev.radarfcs.iff.TransponderMode.SUBLEVEL_AND_DISTANCE, 16,
                        new dev.radarfcs.iff.InheritPolicy(dev.radarfcs.core.TargetCategory.ALL & ~dev.radarfcs.core.TargetCategory.HOSTILE.bit,
                                java.util.List.of("minecraft:zombie_villager"), java.util.List.of("minecraft:creeper", "#minecraft:raiders"))).write(), 3, 64));
        if (tick == 638) shot(mc, "transponder_gui_0");
        if (tick == 640 && mc.screen instanceof dev.radarfcs.client.TransponderScreen ts) ts.showTab(1);
        if (tick == 646) shot(mc, "transponder_gui_1");
        // карта монитора вблизи, на 64 и на 2048 блоков, и издалека (нет мерцания слоёв)
        if (tick == 650) {
            if (mc.screen != null) mc.setScreen(null);
            server.execute(() -> look(server, new Vec3(base.getX() - 3, base.getY() + 1.0, base.getZ() - 1.5),
                    new Vec3(base.getX() - 5.6, base.getY() + 1.0, base.getZ() - 1.5)));
        }
        if (tick == 658) shot(mc, "map_close");
        if (tick == 660) server.execute(() -> monitorScale(server, 64));
        if (tick == 668) shot(mc, "map_64");
        if (tick == 670) server.execute(() -> monitorScale(server, 2048));
        if (tick == 678) shot(mc, "map_2048");
        if (tick == 680) server.execute(() -> {
            monitorScale(server, 128);
            look(server, new Vec3(base.getX() - 3, base.getY() + 1.0, base.getZ() - 1.5),
                    new Vec3(base.getX() - 34, base.getY() + 4.0, base.getZ() - 1.5));
        });
        if (tick == 690) shot(mc, "map_far");
        // камера: вид от её лица с монитора камер
        if (tick == 692) server.execute(() -> look(server, base.offset(-1, 0, 6).getCenter(),
                new Vec3(base.getX() - 1.5, base.getY() + 1.62, base.getZ() + 8.5)));
        if (tick == 696) net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new dev.radarfcs.net.CameraPayloads.View(base.offset(-1, 0, 6), base.offset(2, 3, -1)));
        // повернуть взгляд камеры: вниз и в сторону - голова камеры должна повернуться и остаться так после выхода
        if (tick >= 699 && tick <= 703 && mc.player != null) {
            mc.player.setYRot(-55f);
            mc.player.setXRot(22f);
        }
        if (tick == 706) shot(mc, "camera_view");
        if (tick == 708) dev.radarfcs.client.CameraClient.stop(true);
        if (tick == 709) dev.radarfcs.client.FcsClientHooks.openCameraList(base.offset(-1, 0, 6));
        if (tick == 714) shot(mc, "camera_monitor_gui");
        if (tick == 716 && mc.screen != null) mc.setScreen(null);
        if (tick == 716) server.execute(() -> {
            cameraGallery(server.overworld());
            look(server, base.offset(-6, 2, -14).getCenter(), new Vec3(base.getX() - 5.5, base.getY() + 3.6, base.getZ() - 9.0));
        });
        if (tick == 721) shot(mc, "camera_head");
        if (Boolean.getBoolean("radar_fcs.screenshot.camera")) {
            // окно настроек орудия (Shift+ПКМ по орудийному линку)
            if (tick == 722) dev.radarfcs.client.FcsClientHooks.openGunSettings(new dev.radarfcs.net.GunPayloads.Open(base, "#3F2A",
                    new dev.radarfcs.fcs.GunSettings(dev.radarfcs.fcs.GunSettings.Type.AUTOCANNON, true, false, "Зенитка-1", 1).write()));
            if (tick == 728) shot(mc, "gun_settings");
            // витрина блоков мода (текстуры)
            if (tick == 730) {
                if (mc.screen != null) mc.setScreen(null);
                server.execute(() -> {
                    blockGallery(server.overworld());
                    look(server, base.offset(-6, 1, -32).getCenter(), new Vec3(base.getX() - 5.5, base.getY() + 3.0, base.getZ() - 23.0));
                });
            }
            if (tick == 740) shot(mc, "blocks_gallery");
            if (tick == 742) mc.stop();
            if (tick > 721) return;
        }
        // орудие на вращателях Sable: горизонтальный вращатель, на нём вертикальный, ствол из досок с прицелом
        if (tick == 60 && Boolean.getBoolean("radar_fcs.screenshot.turret")) tick = 720;   // только башня
        if (tick == 400 && Boolean.getBoolean("radar_fcs.screenshot.camera")) tick = 690;   // только камера
        if (tick == 60 && Boolean.getBoolean("radar_fcs.screenshot.swivel")) tick = 1100;  // только подшипник-шарнир
        if (tick == 722) server.execute(() -> turretScene(server));
        if (tick == 726) server.execute(() -> look(server, base.offset(-8, 2, 10).getCenter(),
                new Vec3(base.getX() - 8.5, base.getY() + 5.5, base.getZ() + 19.5)));
        if (tick == 730) shot(mc, "rotator_before");
        if (tick == 732) server.execute(() -> {
            assembleRotator(server, base.offset(-8, 2, 10));        // вертикальный (ось север-юг) - сначала
            assembleRotator(server, base.offset(-9, 0, 10));         // горизонтальный (ось вверх)
        });
        // переход через ±180°: поворот на 170°, затем на -170° - кратчайшим путём через 180
        if (tick == 740) server.execute(() -> setRotator(server, base.offset(-9, 0, 10), 170));
        if (tick == 790) server.execute(() -> setRotator(server, base.offset(-9, 0, 10), -170));
        if (tick >= 740 && tick <= 830 && tick % 5 == 0) server.execute(() -> logRotators(server));
        if (tick == 832) server.execute(() -> setRotator(server, base.offset(-9, 0, 10), 0));
        // радарный линк на основании орудия: вычислитель сцены наводит его по прицелу на цель
        if (tick == 870) server.execute(() -> placeLink(server.overworld(), base.offset(-9, 0, 10), Direction.SOUTH, base));
        if (tick >= 880 && tick <= 1060 && tick % 10 == 0) server.execute(() -> logTurret(server));
        if (tick == 1000) shot(mc, "turret_aim");
        if (tick == 1062 && !Boolean.getBoolean("radar_fcs.screenshot.swivel")) tick = 1099;
        // привод орудия: курс - подшипник-шарнир Create Aeronautics через шестерню, наклон - вал станка CBC
        if (tick == 1102) server.execute(() -> swivelScene(server));
        if (tick == 1106) server.execute(() -> look(server, base.offset(-8, 4, 24).getCenter(),
                new Vec3(base.getX() - 8.5, base.getY() + 8.5, base.getZ() + 34.5)));
        if (tick == 1110) server.execute(() -> assembleSwivel(server));
        if (tick == 1130) server.execute(() -> placeLink(server.overworld(), base.offset(-12, 1, 24), Direction.SOUTH, base));
        if (tick >= 1140 && tick <= 1500 && tick % 10 == 0) server.execute(() -> logTurret(server));
        if (tick == 1400) shot(mc, "swivel_aim");
        if (tick == 1502) mc.stop();
    }

    private static long lastFrame;
    private static float lastLogged = Float.NaN;

    /** Курс луча на каждом кадре (ticks 341-346): шаги между кадрами должны быть ровными - луч крутится плавно. */
    @SubscribeEvent
    public static void onFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean("radar_fcs.screenshot") || tick < 341 || tick > 346) return;
        long now = System.nanoTime();
        float hd = dev.radarfcs.client.RadarMonitorRenderer.lastHeading;
        float step = Float.isFinite(lastLogged) ? (float) Math.IEEEremainder(hd - lastLogged, 360) : Float.NaN;
        FcsMod.LOG.info("[radar_fcs dev] frame tick {} dt {} ms heading {} step {} partial {}", tick,
                lastFrame == 0 ? 0 : (now - lastFrame) / 1e6, hd, step, event.getPartialTick().getGameTimeDeltaPartialTick(false));
        lastFrame = now;
        lastLogged = hd;
    }

    private static void monitorScale(net.minecraft.server.MinecraftServer server, int side) {
        if (server.overworld().getBlockEntity(base.offset(-3, 0, -3)) instanceof dev.radarfcs.block.RadarMonitorBlockEntity mon) {
            mon.setSide(side);
        }
    }

    private static void turretScene(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -12; dx <= -2; dx++) for (int dy = 0; dy <= 5; dy++) for (int dz = 6; dz <= 14; dz++) level.setBlock(base.offset(dx, dy, dz), air, 3);
        BlockPos y = base.offset(-9, 0, 10);                         // горизонтальный вращатель, выход вверх
        motor(level, y.below(), Direction.UP, 128);
        level.setBlock(y, FcsRegistry.GUN_ROTATOR.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        BlockPos plat = y.above();
        level.setBlock(plat, block("create:andesite_casing").defaultBlockState(), 3);
        level.setBlock(plat.east(), block("create:andesite_casing").defaultBlockState(), 3);
        BlockPos p = plat.east().above();                            // вертикальный вращатель: выход на север
        level.setBlock(p, FcsRegistry.GUN_ROTATOR.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.NORTH), 3);
        motor(level, p.south(), Direction.NORTH, 128);
        for (int i = 0; i < 4; i++) level.setBlock(p.north().east(i), block("minecraft:oak_planks").defaultBlockState(), 3);
        // прицел на стволе (смотрит вдоль ствола - на восток), орудийные линки на прицеле и обоих вращателях
        BlockPos sight = p.north().east(1).above();
        level.setBlock(sight, FcsRegistry.SIGHT.get().defaultBlockState().setValue(dev.radarfcs.block.SightBlock.FACING, Direction.EAST), 3);
        gunLink(level, sight, Direction.UP);
        gunLink(level, p, Direction.EAST);
        gunLink(level, y, Direction.WEST);
        // клей: площадка + вертикальный вращатель с мотором и линком; ствол с прицелом и линком - отдельно
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                new net.minecraft.world.phys.AABB(plat.getX(), plat.getY(), p.getZ(), plat.getX() + 3, p.getY() + 1, p.south().getZ() + 1)));
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                new net.minecraft.world.phys.AABB(p.north().getX(), p.getY(), p.north().getZ(), p.north().getX() + 4, p.getY() + 3, p.north().getZ() + 1)));
    }

    /** Витрина камер у стены: по умолчанию, с повёрнутой головой, на потолке, на полу. */
    private static void cameraGallery(ServerLevel level) {
        BlockPos w = base.offset(-9, 0, -15);
        for (int dx = 0; dx < 7; dx++) for (int dy = 0; dy < 5; dy++) level.setBlock(w.offset(dx, dy, 0), block("minecraft:smooth_stone").defaultBlockState(), 3);
        for (int dx = 0; dx < 7; dx++) level.setBlock(w.offset(dx, 4, 1), block("minecraft:smooth_stone").defaultBlockState(), 3);
        cam(level, w.offset(1, 2, 1), Direction.SOUTH, Float.NaN, 0);
        cam(level, w.offset(3, 2, 1), Direction.SOUTH, -40f, 25f);
        cam(level, w.offset(5, 3, 1).below(), Direction.DOWN, 30f, 35f);
        cam(level, w.offset(5, 0, 2), Direction.UP, -20f, -20f);
    }

    /** Блоки мода в ряд (для снимка текстур). */
    private static void blockGallery(ServerLevel level) {
        BlockPos w = base.offset(-12, 0, -32);
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -1; dx < 14; dx++) for (int dy = 0; dy < 5; dy++) for (int dz = 0; dz < 5; dz++) level.setBlock(w.offset(dx, dy, dz), air, 3);
        for (int dx = -1; dx < 14; dx++) for (int dy = 0; dy < 5; dy++) level.setBlock(w.offset(dx, dy, -1), block("minecraft:smooth_stone").defaultBlockState(), 3);
        var S = Direction.SOUTH;
        level.setBlock(w.offset(0, 0, 0), FcsRegistry.FIRE_CONTROL.get().defaultBlockState().setValue(FireControlBlock.FACING, S), 3);
        level.setBlock(w.offset(0, 1, 0), FcsRegistry.FIRE_CONTROL.get().defaultBlockState().setValue(FireControlBlock.FACING, S)
                .setValue(FireControlBlock.ENGAGED, true), 3);
        level.setBlock(w.offset(2, 0, 0), FcsRegistry.MEMORY.get().defaultBlockState().trySetValue(BlockStateProperties.HORIZONTAL_FACING, S)
                .trySetValue(dev.radarfcs.block.MemoryBlock.POWERED, true), 3);
        level.setBlock(w.offset(2, 1, 0), FcsRegistry.MEMORY.get().defaultBlockState().trySetValue(BlockStateProperties.HORIZONTAL_FACING, S), 3);
        level.setBlock(w.offset(4, 0, 0), FcsRegistry.CAMERA_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.CameraMonitorBlock.FACING, S), 3);
        level.setBlock(w.offset(4, 1, 0), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState().setValue(IffTransponderBlock.CHANNEL, DyeColor.LIME), 3);
        level.setBlock(w.offset(6, 0, 0), FcsRegistry.YAW_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        level.setBlock(w.offset(6, 1, 0), FcsRegistry.PITCH_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.EAST), 3);
        level.setBlock(w.offset(8, 0, 0), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        level.setBlock(w.offset(8, 1, 0), FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP).setValue(dev.radarfcs.block.GunDriveBlock.PITCH, true), 3);
        level.setBlock(w.offset(10, 0, 0), FcsRegistry.GUN_ROTATOR.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        level.setBlock(w.offset(10, 1, 0), FcsRegistry.SIGHT.get().defaultBlockState().setValue(dev.radarfcs.block.SightBlock.FACING, S), 3);
        level.setBlock(w.offset(12, 0, 0), FcsRegistry.SIGHT.get().defaultBlockState().setValue(dev.radarfcs.block.SightBlock.FACING, S)
                .setValue(dev.radarfcs.block.SightBlock.FIRING, true), 3);
        level.setBlock(w.offset(12, 1, 0), FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.EAST), 3);
        level.setBlock(w.offset(0, 2, 0), FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, Direction.UP), 3);
        level.setBlock(w.offset(8, 2, 0), FcsRegistry.GUN_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, Direction.UP), 3);
    }

    private static void cam(ServerLevel level, BlockPos at, Direction facing, float yaw, float pitch) {
        level.setBlock(at, FcsRegistry.CAMERA.get().defaultBlockState().setValue(dev.radarfcs.block.CameraBlock.FACING, facing), 3);
        if (!Float.isNaN(yaw) && level.getBlockEntity(at) instanceof dev.radarfcs.block.CameraBlockEntity c) {
            java.util.UUID who = java.util.UUID.randomUUID();
            c.controlForTest(who, yaw, pitch, false);
            c.stopControl(who);
        }
    }

    private static final java.util.UUID TURRET_NET = java.util.UUID.randomUUID();
    private static final java.util.UUID SWIVEL_NET = java.util.UUID.randomUUID();

    /** Подшипник-шарнир (курс) на шестерне от привода, на нём автопушка; наклон - привод в вал станка. */
    private static void swivelScene(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (int dx = -14; dx <= -2; dx++) for (int dy = 0; dy <= 8; dy++) for (int dz = 19; dz <= 29; dz++) level.setBlock(base.offset(dx, dy, dz), air, 3);
        BlockPos b = base.offset(-9, 2, 24);                          // подшипник-шарнир, выход вверх
        level.setBlock(b, block("simulated:swivel_bearing").defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP), 3);
        // шестерня рядом с шестернёй подшипника, под ней привод курса и мотор
        BlockPos cog = b.west(3);
        level.setBlock(b.west(), block("create:cogwheel").defaultBlockState().trySetValue(BlockStateProperties.AXIS, Direction.Axis.Y), 3);
        level.setBlock(b.west(2), block("create:cogwheel").defaultBlockState().trySetValue(BlockStateProperties.AXIS, Direction.Axis.Y), 3);
        level.setBlock(cog, block("create:cogwheel").defaultBlockState().trySetValue(BlockStateProperties.AXIS, Direction.Axis.Y), 3);
        BlockPos yawDrive = cog.below();
        level.setBlock(yawDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        motor(level, yawDrive.below(), Direction.UP, 64);
        // станок с автопушкой на подшипнике, привод наклона сбоку (вал станка), мотор за ним
        BlockPos m = b.above();
        Direction dir = Direction.EAST;
        level.setBlock(m, block("createbigcannons:cannon_mount").defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, dir), 3);
        BlockPos pivot = m.above(2);
        level.setBlock(pivot.relative(dir, -1), block("createbigcannons:cast_iron_autocannon_breech").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir), 3);
        level.setBlock(pivot, block("createbigcannons:cast_iron_autocannon_recoil_spring").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir), 3);
        for (int i = 1; i <= 4; i++) level.setBlock(pivot.relative(dir, i), block("createbigcannons:cast_iron_autocannon_barrel").defaultBlockState()
                .setValue(BlockStateProperties.FACING, dir), 3);
        for (int i = -1; i < 4; i++) {
            if (level.getBlockEntity(pivot.relative(dir, i)) instanceof ICannonBlockEntity<?> x
                    && level.getBlockEntity(pivot.relative(dir, i + 1)) instanceof ICannonBlockEntity<?> y) {
                x.cannonBehavior().setConnectedFace(dir, true);
                y.cannonBehavior().setConnectedFace(dir.getOpposite(), true);
            }
        }
        if (level.getBlockEntity(pivot.relative(dir, -1)) instanceof rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity br) {
            var round = BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:machine_gun_round"));
            for (int i = 0; i < 200; i++) br.getInputBuffer().add(new ItemStack(round));
        }
        BlockPos pitchDrive = m.south();
        level.setBlock(pitchDrive, FcsRegistry.GUN_DRIVE.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.NORTH)
                .setValue(dev.radarfcs.block.GunDriveBlock.PITCH, true), 3);
        motor(level, pitchDrive.south(), Direction.NORTH, 64);
        swivelLink(level, m, Direction.NORTH);
        swivelLink(level, pitchDrive, Direction.EAST);
        swivelLink(level, yawDrive, Direction.WEST);
        // клей: всё над подшипником (станок, пушка, привод наклона с мотором и линками)
        level.addFreshEntity(new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(level,
                new net.minecraft.world.phys.AABB(m.getX() - 2, m.getY(), m.getZ() - 1, m.getX() + 6, m.getY() + 4, m.getZ() + 3)));
        swivelBearing = b;
    }

    private static BlockPos swivelBearing;

    private static void swivelLink(ServerLevel level, BlockPos attached, Direction face) {
        BlockPos at = attached.relative(face);
        level.setBlock(at, FcsRegistry.GUN_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, face), 3);
        if (level.getBlockEntity(at) instanceof dev.radarfcs.block.GunLinkBlockEntity be) be.setNetwork(SWIVEL_NET);
    }

    private static void assembleSwivel(net.minecraft.server.MinecraftServer server) {
        var be = server.overworld().getBlockEntity(swivelBearing);
        try {
            if (be != null) be.getClass().getMethod("assemble").invoke(be);
            FcsMod.LOG.info("[radar_fcs dev] swivel {} assembled {}", swivelBearing,
                    be == null ? null : be.getClass().getMethod("isAssembled").invoke(be));
        } catch (ReflectiveOperationException | RuntimeException e) {
            FcsMod.LOG.warn("[radar_fcs dev] swivel bearing not assembled: {}", e.toString());
        }
    }

    private static void gunLink(ServerLevel level, BlockPos attached, Direction face) {
        BlockPos at = attached.relative(face);
        level.setBlock(at, FcsRegistry.GUN_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, face), 3);
        if (level.getBlockEntity(at) instanceof dev.radarfcs.block.GunLinkBlockEntity be) be.setNetwork(TURRET_NET);
    }

    private static void logTurret(net.minecraft.server.MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level.getBlockEntity(base) instanceof dev.radarfcs.block.FireControlBlockEntity fcs) {
            for (var m : fcs.mountStates()) {
                String sight = "";
                if (m.turret != null && m.turret.sightEntity(level) instanceof dev.radarfcs.block.SightBlockEntity s) {
                    Vec3 d = s.worldDirection();
                    Vec3 to = husk != null ? husk.getBoundingBox().getCenter().subtract(s.worldCenter()).normalize() : Vec3.ZERO;
                    sight = String.format(java.util.Locale.ROOT, "sight dir (%.3f, %.3f, %.3f) toHusk (%.3f, %.3f, %.3f) angle %.2f firing %s",
                            d.x, d.y, d.z, to.x, to.y, to.z, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d.dot(to))))), s.firing());
                }
                if (m.turret != null && m.mount != null && m.mount.localBarrelDirection() != null) {
                    Vec3 d = SableCompat.dirToWorld(SableCompat.containing(level, m.pos), m.mount.localBarrelDirection());
                    sight += String.format(java.util.Locale.ROOT, " barrel (%.3f, %.3f, %.3f) shots %d", d.x, d.y, d.z, m.shotsFired);
                    for (BlockPos dp : m.turret.drives) if (level.getBlockEntity(dp) instanceof dev.radarfcs.block.GunDriveBlockEntity g)
                        sight += String.format(java.util.Locale.ROOT, " drive%s out %.2f rate %.3f", g.pitch() ? "P" : "Y", g.output(), g.rate());
                }
                FcsMod.LOG.info("[radar_fcs dev] turret state {} status {} target {} err {} {}", m.pos, m.status, m.targetName,
                        String.format(java.util.Locale.ROOT, "%.2f", m.aimError), sight);
            }
        }
        logRotators(server);
    }

    private static void assembleRotator(net.minecraft.server.MinecraftServer server, BlockPos pos) {
        ServerPlayer pl = server.getPlayerList().getPlayers().get(0);
        if (server.overworld().getBlockEntity(pos) instanceof dev.radarfcs.block.GunRotatorBlockEntity r) {
            r.toggleAssembly(pl);
            FcsMod.LOG.info("[radar_fcs dev] rotator {} assembled {} sub {}", pos, r.assembled(), r.subLevel());
        } else {
            FcsMod.LOG.warn("[radar_fcs dev] no rotator at {}", pos);
        }
    }

    private static final java.util.List<BlockPos> ROTATORS = new java.util.ArrayList<>();

    private static void setRotator(net.minecraft.server.MinecraftServer server, BlockPos pos, double deg) {
        // после сборки горизонтального вертикальный вращатель - внутри его под-уровня: ищем по типу блока
        for (var be : findRotators(server)) {
            if (be.getBlockPos().equals(pos) || (pos.equals(base.offset(-8, 2, 10)) && !be.getBlockPos().equals(base.offset(-9, 0, 10)))) {
                be.setWant(deg);
                FcsMod.LOG.info("[radar_fcs dev] rotator {} want {}", be.getBlockPos(), deg);
            }
        }
    }

    private static java.util.List<dev.radarfcs.block.GunRotatorBlockEntity> findRotators(net.minecraft.server.MinecraftServer server) {
        java.util.List<dev.radarfcs.block.GunRotatorBlockEntity> out = new java.util.ArrayList<>();
        ServerLevel level = server.overworld();
        BlockPos y = base.offset(-9, 0, 10);
        if (level.getBlockEntity(y) instanceof dev.radarfcs.block.GunRotatorBlockEntity r) out.add(r);
        // вертикальный - в под-уровне горизонтального: по позиции плота
        var c = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        for (var sub : c.getAllSubLevels()) {
            var plot = sub.getPlot();
            BlockPos center = plot.getCenterBlock();
            for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
                if (level.getBlockEntity(center.offset(dx, dy, dz)) instanceof dev.radarfcs.block.GunRotatorBlockEntity r) out.add(r);
            }
        }
        return out;
    }

    private static void logRotators(net.minecraft.server.MinecraftServer server) {
        for (var r : findRotators(server)) {
            FcsMod.LOG.info("[radar_fcs dev] rotator {} assembled {} want {} target {} angle {} axis {} rpm {}", r.getBlockPos(), r.assembled(),
                    r.want(), String.format("%.2f", r.target()), String.format("%.2f", r.angle()), r.worldAxis(), r.getSpeed());
        }
    }

    /** Поставить игрока глазами в eye и повернуть к точке target. */
    private static void look(net.minecraft.server.MinecraftServer server, Vec3 target, Vec3 eye) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        p.getAbilities().flying = true;                                    // висеть в точке съёмки
        p.onUpdateAbilities();
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance()));
        p.teleportTo(server.overworld(), eye.x, eye.y - 1.62, eye.z, yaw, pitch);
    }

    /** Все линки сцены - в одной радарной сети, своей у каждого запуска (сцены прошлых запусков стоят в том же мире). */
    private static final java.util.UUID SCENE_NET = java.util.UUID.randomUUID();

    private static void placeLink(ServerLevel level, BlockPos attached, Direction face, BlockPos other) {
        BlockPos at = attached.relative(face);
        level.setBlock(at, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, face), 3);
        if (level.getBlockEntity(at) instanceof dev.radarfcs.block.RadarLinkBlockEntity be) be.setNetwork(SCENE_NET);
    }

    private static void motor(ServerLevel level, BlockPos pos, Direction facing, int rpm) {
        level.setBlock(pos, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, facing), 3);
        if (level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity m) {
            m.generatedSpeed.setValue(rpm);
        }
    }

    private static BlockPos radarBearing;

    /** Сцены Ponder: по предмету каждой сцены - окно Ponder, перемотка и три кадра (-PfcsPonderOnly). */
    private static final String[][] PONDER = {
            {"fire_control_computer", "battery"}, {"yaw_controller", "aim_controllers"}, {"radar_link", "network"},
            {"radar_monitor", "radar_monitor"}, {"iff_transponder", "transponder"}, {"camera", "camera"},
            {"gun_rotator", "turret"}, {"gun_drive", "gun_drive"}};

    private static void ponderTour(Minecraft mc) {
        int start = 60, step = 50;
        int i = (tick - start) / step, t = (tick - start) % step;
        if (tick < start) return;
        if (i >= PONDER.length) {
            if (t == 2) mc.stop();
            return;
        }
        String item = PONDER[i][0], name = PONDER[i][1];
        try {
            if (t == 0) {
                mc.setScreen(net.createmod.ponder.foundation.ui.PonderUI.of(new net.minecraft.world.item.ItemStack(
                        BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(FcsMod.MODID, item)))));
            }
            if (!(mc.screen instanceof net.createmod.ponder.foundation.ui.PonderUI ui)) {
                if (t == 5) FcsMod.LOG.warn("[radar_fcs dev] ponder {}: no PonderUI ({})", name, mc.screen);
                return;
            }
            var scene = ponderScene(ui);
            if (t == 5 && scene != null) FcsMod.LOG.info("[radar_fcs dev] ponder {}: '{}' {} ticks", name, scene.getTitle(), scene.getTotalTime());
            int total = scene != null ? scene.getTotalTime() : 600;
            if (t == 6) ui.seekToTime(Math.min(total - 5, 130));
            if (t == 16) shot(mc, "ponder_" + name + "_1");
            if (t == 18) ui.seekToTime(total * 55 / 100);
            if (t == 28) shot(mc, "ponder_" + name + "_2");
            if (t == 30) ui.seekToTime(total - 40);
            if (t == 40) shot(mc, "ponder_" + name + "_3");
        } catch (Throwable e) {
            FcsMod.LOG.error("[radar_fcs dev] ponder {} failed", name, e);
        }
    }

    @org.jetbrains.annotations.Nullable
    private static net.createmod.ponder.foundation.PonderScene ponderScene(net.createmod.ponder.foundation.ui.PonderUI ui) {
        try {
            var f = net.createmod.ponder.foundation.ui.PonderUI.class.getDeclaredField("scenes");
            f.setAccessible(true);
            var idx = net.createmod.ponder.foundation.ui.PonderUI.class.getDeclaredField("index");
            idx.setAccessible(true);
            java.util.List<?> list = (java.util.List<?>) f.get(ui);
            Object o = list.get(idx.getInt(ui));
            return (net.createmod.ponder.foundation.PonderScene) o;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(),
                c -> FcsMod.LOG.info("[radar_fcs dev] screenshot {}: {}", name, c.getString()));
    }

    private static void build(ServerLevel level, ServerPlayer player) {
        player.setGameMode(GameType.CREATIVE);
        level.setDayTime(6000);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        base = player.blockPosition().offset(4, 0, 0);
        // слизни плоского мира - тоже цели; для снимков оставляем только свою цель
        for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Slime.class, player.getBoundingBox().inflate(160))) e.discard();
        int y = base.getY();
        BlockPos mount = base.offset(4, 0, 0);
        level.setBlock(mount, block("createbigcannons:cannon_mount").defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 3);
        BlockPos pivot = mount.above(2);
        Direction dir = Direction.EAST, facing = Direction.WEST;
        level.setBlock(pivot.relative(dir, -2), block("createbigcannons:cast_iron_cannon_end").defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing), 3);
        for (int i = 0; i < 2; i++) level.setBlock(pivot.relative(dir, -i), block("createbigcannons:cast_iron_cannon_chamber")
                .defaultBlockState().setValue(BlockStateProperties.FACING, facing), 3);
        for (int i = 1; i <= 4; i++) level.setBlock(pivot.relative(dir, i), block("createbigcannons:cast_iron_cannon_barrel")
                .defaultBlockState().setValue(BlockStateProperties.FACING, facing), 3);
        for (int i = -2; i < 4; i++) {
            if (level.getBlockEntity(pivot.relative(dir, i)) instanceof ICannonBlockEntity<?> a
                    && level.getBlockEntity(pivot.relative(dir, i + 1)) instanceof ICannonBlockEntity<?> b) {
                a.cannonBehavior().setConnectedFace(dir, true);
                b.cannonBehavior().setConnectedFace(dir.getOpposite(), true);
            }
        }
        var powder = block("createbigcannons:powder_charge").defaultBlockState().setValue(BlockStateProperties.AXIS, dir.getAxis());
        for (int i = 0; i < 2; i++) if (level.getBlockEntity(pivot.relative(dir, -i)) instanceof IBigCannonBlockEntity c)
            c.cannonBehavior().loadBlock(new StructureBlockInfo(pivot.relative(dir, -i), powder, new CompoundTag()));
        if (level.getBlockEntity(pivot.relative(dir, 1)) instanceof IBigCannonBlockEntity c)
            c.cannonBehavior().loadBlock(new StructureBlockInfo(pivot.relative(dir, 1), block("createbigcannons:solid_shot")
                    .defaultBlockState().setValue(BlockStateProperties.FACING, dir), new CompoundTag()));
        // контроллеры наводки: горизонтальной под станком (вокруг - яма, чтобы было видно), вертикальной сбоку; моторы
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(mount.offset(dx, -1, dz),
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(mount.below(), FcsRegistry.YAW_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.UP), 3);
        motor(level, mount.below(2), Direction.UP, 128);
        level.setBlock(mount.south(), FcsRegistry.PITCH_CONTROLLER.get().defaultBlockState()
                .setValue(com.simibubi.create.content.kinetics.base.DirectionalKineticBlock.FACING, Direction.NORTH), 3);
        motor(level, mount.south(2), Direction.NORTH, 128);
        // вычислитель, радар Create Radars, линки, транспондеры разных каналов
        level.setBlock(base, FcsRegistry.FIRE_CONTROL.get().defaultBlockState().setValue(FireControlBlock.FACING, Direction.WEST), 3);
        level.setBlock(base.offset(0, 0, 3), block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 3);
        placeLink(level, mount, Direction.NORTH, base);
        placeLink(level, base.offset(0, 0, 3), Direction.UP, base);
        // радарный подшипник Create Radars с антенной: вращающийся луч на мониторе (собирается позже - assembleRadarBearing)
        BlockPos rb = base.offset(-1, 0, 7);
        motor(level, rb.below(), Direction.UP, 256);              // 256 об/мин - проверка луча на мониторе
        level.setBlock(rb, block("create_radar:radar_bearing").defaultBlockState()
                .trySetValue(BlockStateProperties.FACING, Direction.UP), 3);
        level.setBlock(rb.above(), block("create_radar:radar_receiver_block").defaultBlockState(), 3);
        level.setBlock(rb.above().north(), block("create_radar:radar_dish_block").defaultBlockState(), 3);
        level.setBlock(rb.above().south(), block("create_radar:radar_dish_block").defaultBlockState(), 3);
        placeLink(level, rb, Direction.EAST, base);
        radarBearing = rb;
        // монитор 3x2 экраном на запад: линки с радаром и вычислителем
        var mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.WEST);
        for (int dz = -3; dz <= -1; dz++) for (int dy = 0; dy < 2; dy++) level.setBlock(base.offset(-3, dy, dz), mon, 3);
        placeLink(level, base.offset(-3, 1, -2), Direction.UP, base);
        // камера над вычислителем (смотрит на восток, к цели) и монитор камер; оба в сети сцены
        BlockPos cam = base.offset(2, 3, -1);
        level.setBlock(cam, FcsRegistry.CAMERA.get().defaultBlockState().setValue(dev.radarfcs.block.CameraBlock.FACING, Direction.EAST), 3);
        if (level.getBlockEntity(cam) instanceof dev.radarfcs.block.CameraBlockEntity c) c.setName("Батарея-1");
        placeLink(level, cam, Direction.WEST, base);
        BlockPos camMon = base.offset(-1, 0, 6);
        level.setBlock(camMon, FcsRegistry.CAMERA_MONITOR.get().defaultBlockState()
                .setValue(dev.radarfcs.block.CameraMonitorBlock.FACING, Direction.SOUTH), 3);
        placeLink(level, camMon, Direction.UP, base);
        placeLink(level, base, Direction.NORTH, base);
        DyeColor[] colors = {DyeColor.WHITE, DyeColor.RED, DyeColor.LIME, DyeColor.BLUE};
        String[] names = {"База", "Склад", "Ангар", "Порт"};
        for (int i = 0; i < colors.length; i++) {
            BlockPos tp = base.offset(8, 0, -3 - i * 2);
            level.setBlock(tp, FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState().setValue(IffTransponderBlock.CHANNEL, colors[i]), 3);
            if (level.getBlockEntity(tp) instanceof dev.radarfcs.block.IffTransponderBlockEntity t) {
                t.apply(new dev.radarfcs.iff.TransponderSettings("", names[i], dev.radarfcs.iff.TransponderMode.RADIUS, 3,
                        dev.radarfcs.iff.InheritPolicy.ALL), null);
            }
        }
        husk = EntityType.HUSK.create(level);
        if (husk != null) {
            husk.setNoAi(true);
            husk.setPersistenceRequired();
            var hp = husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(1000);                         // живёт до конца съёмки
            husk.setHealth(husk.getMaxHealth());
            husk.setPos(base.getX() + 36.5, y, base.getZ() - 6);
            level.addFreshEntity(husk);
        }
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:goggles"))));
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);     // линк из прошлой съёмки
        dev.radarfcs.FcsConfig.REQUIRE_POWER.set(false);                 // сцена без генераторов
        player.teleportTo(level, base.getX() - 6.5, y + 3, base.getZ() + 7.5, -120f, 15f);
        FcsMod.LOG.info("[radar_fcs dev] scene built at {}", base);
    }
}
