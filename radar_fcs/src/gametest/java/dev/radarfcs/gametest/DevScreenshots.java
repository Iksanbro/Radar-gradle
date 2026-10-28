package dev.radarfcs.gametest;

import net.minecraft.world.phys.Vec3;

import dev.radarfcs.FcsMod;
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
        // контроллеры наводки у станка: общий вид и очки инженера
        if (tick == 340) server.execute(() -> look(server, new Vec3(base.getX() + 4.5, base.getY() - 0.2, base.getZ() + 0.9),
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
                be.applySettings(st, null);
            }
        });
        if (tick >= 470 && tick < 540 && (tick - 470) % 10 == 0) {
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
        if (tick >= 475 && tick < 545 && (tick - 475) % 10 == 0) shot(mc, "fcs_settings_" + (tick - 475) / 10);
        // радарный линк: настроенный на сеть сцены линк в руке, взгляд на линк станка - вся сеть подсвечена
        if (tick == 550) {
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
        // окно монитора: список целей, фильтры
        if (tick == 600) dev.radarfcs.client.FcsClientHooks.openMonitor(base.offset(-3, 0, -3));
        if (tick == 610) shot(mc, "monitor_gui");
        // экран транспондера
        if (tick == 620) dev.radarfcs.client.FcsClientHooks.openTransponder(new dev.radarfcs.net.TransponderPayloads.Open(
                base.offset(8, 0, -3), "2077", dev.radarfcs.iff.TransponderMode.RADIUS.ordinal(), 16, false, 64));
        if (tick == 630) shot(mc, "transponder_gui");
        if (tick == 640) mc.stop();
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
        // монитор 3x2 экраном на запад: линки с радаром и вычислителем
        var mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.WEST);
        for (int dz = -3; dz <= -1; dz++) for (int dy = 0; dy < 2; dy++) level.setBlock(base.offset(-3, dy, dz), mon, 3);
        placeLink(level, base.offset(-3, 1, -2), Direction.UP, base);
        placeLink(level, base, Direction.NORTH, base);
        DyeColor[] colors = {DyeColor.WHITE, DyeColor.RED, DyeColor.LIME, DyeColor.BLUE};
        for (int i = 0; i < colors.length; i++) {
            level.setBlock(base.offset(8, 0, -3 - i * 2), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState()
                    .setValue(IffTransponderBlock.CHANNEL, colors[i]), 3);
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
        player.teleportTo(level, base.getX() - 6.5, y + 3, base.getZ() + 7.5, -120f, 15f);
        FcsMod.LOG.info("[radar_fcs dev] scene built at {}", base);
    }
}
