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
    private static BlockPos rbase;
    private static final java.util.List<Mob> radarTargets = new java.util.ArrayList<>();

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
        // вторая сцена: радары мода и монитор
        if (tick == 340) server.execute(() -> buildRadars(server.overworld(), server.getPlayerList().getPlayers().get(0)));
        if (!radarTargets.isEmpty() && tick > 340) {
            final int t = tick;
            server.execute(() -> {
                for (int i = 0; i < radarTargets.size(); i++) {
                    Mob m = radarTargets.get(i);
                    int ph = (t + i * 37) % 120;
                    double off = ph < 60 ? ph * 0.1 : (120 - ph) * 0.1;
                    if (m.isAlive()) m.setPos(rbase.getX() + 34.5 + i * 6, rbase.getY(), rbase.getZ() - 8 + i * 7 + off);
                }
            });
        }
        if (tick == 470) shot(mc, "radar_overview");
        if (tick == 480) server.execute(() -> server.getPlayerList().getPlayers().get(0).teleportTo(server.overworld(),
                rbase.getX() - 2.4, rbase.getY(), rbase.getZ() + 1.5, -90f, 12f));
        if (tick == 540) shot(mc, "radar_monitor");
        if (tick == 550) server.execute(() -> server.getPlayerList().getPlayers().get(0).teleportTo(server.overworld(),
                rbase.getX() + 5.5, rbase.getY(), rbase.getZ() - 3.0, -130f, 4f));
        if (tick == 600) shot(mc, "radar_goggles");
        if (tick == 610) server.execute(() -> server.getPlayerList().getPlayers().get(0).teleportTo(server.overworld(),
                rbase.getX() + 3.5, rbase.getY() + 2, rbase.getZ() + 14.5, -150f, 18f));
        if (tick == 660) shot(mc, "radar_array");
        // экран настроек вычислителя первой сцены: все вкладки
        if (tick == 662) server.execute(() -> {
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
        if (tick >= 690 && tick < 740 && (tick - 690) % 10 == 0) {
            int tab = (tick - 690) / 10;
            if (tab == 0) dev.radarfcs.client.FcsClientHooks.openFcsSettings(base);
            if (mc.screen instanceof dev.radarfcs.client.FcsSettingsScreen sc) sc.showTab(tab);
        }
        if (tick >= 695 && tick < 745 && (tick - 695) % 10 == 0) shot(mc, "fcs_settings_" + (tick - 695) / 10);
        // радарный линк: станок и радар Create Radars связаны с вычислителем; линк в руке с выбранным вычислителем
        if (tick == 750) {
            if (mc.screen != null) mc.setScreen(null);
            server.execute(() -> {
                ServerLevel level = server.overworld();
                ServerPlayer p = server.getPlayerList().getPlayers().get(0);
                BlockPos mount = base.offset(4, 0, 0);
                placeLink(level, mount, Direction.SOUTH, base);
                if (level.getBlockEntity(base.offset(0, 0, 3)) != null) placeLink(level, base, Direction.UP, base.offset(0, 0, 3));
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(FcsRegistry.RADAR_LINK_ITEM.get(), 8));
                Vec3 hit = Vec3.atCenterOf(base).add(0, 0.5, 0);
                p.getMainHandItem().getItem().useOn(new net.minecraft.world.item.context.UseOnContext(p,
                        net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(hit, Direction.UP, base, false)));
                Vec3 eye = new Vec3(mount.getX() + 1.5, mount.getY() + 1.62, mount.getZ() + 3.5);
                Vec3 look = Vec3.atCenterOf(mount.relative(Direction.SOUTH)).add(0, 0, -0.3).subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance()));
                p.teleportTo(level, eye.x, eye.y - 1.62, eye.z, yaw, pitch);
            });
        }
        if (tick == 790) shot(mc, "radar_link");
        if (tick == 800) mc.stop();
    }

    private static void placeLink(ServerLevel level, BlockPos attached, Direction face, BlockPos other) {
        BlockPos at = attached.relative(face);
        level.setBlock(at, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, face), 3);
        if (level.getBlockEntity(at) instanceof dev.radarfcs.block.RadarLinkBlockEntity be) be.setOther(other);
    }

    private static void buildRadars(ServerLevel level, ServerPlayer player) {
        rbase = base.offset(0, 0, 32);
        int y = rbase.getY();
        // монитор 3x2 экраном на запад
        var mon = FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(dev.radarfcs.block.RadarMonitorBlock.FACING, Direction.WEST);
        for (int z = 0; z < 3; z++) for (int dy = 0; dy < 2; dy++) level.setBlock(rbase.offset(0, dy, z), mon, 3);
        // радарный подшипник: мотор, подшипник, облучатель на восток перед отражателем 3x3
        BlockPos b = rbase.offset(8, 1, -6);
        level.setBlock(b.above(), FcsRegistry.RADAR_RECEIVER.get().defaultBlockState()
                .setValue(dev.radarfcs.block.RadarReceiverBlock.FACING, Direction.EAST), 3);
        for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++)
            level.setBlock(b.above().offset(-1, dy, dz), FcsRegistry.RADAR_DISH.get().defaultBlockState(), 3);
        level.setBlock(b, FcsRegistry.RADAR_BEARING.get().defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        level.setBlock(b.below(), block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP), 3);
        // фазированная решётка 3x3 лицом на юг, мотор сзади
        BlockPos c = rbase.offset(5, 2, 10);
        var panel = FcsRegistry.PHASED_ARRAY_PANEL.get().defaultBlockState().setValue(dev.radarfcs.block.PhasedArrayPanelBlock.FACING, Direction.SOUTH);
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) level.setBlock(c.offset(dx, dy, 0), panel, 3);
        level.setBlock(c, FcsRegistry.PHASED_ARRAY_CONTROLLER.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        level.setBlock(c.north(), block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH), 3);
        // купол
        level.setBlock(rbase.offset(2, 0, -6), FcsRegistry.RADAR_DOME.get().defaultBlockState(), 3);
        // цели
        for (int i = 0; i < 3; i++) {
            Mob m = EntityType.HUSK.create(level);
            if (m == null) continue;
            m.setNoAi(true);
            m.setPersistenceRequired();
            m.setPos(rbase.getX() + 34.5 + i * 6, y, rbase.getZ() - 8 + i * 7);
            level.addFreshEntity(m);
            radarTargets.add(m);
        }
        player.teleportTo(level, rbase.getX() - 8.5, y + 2, rbase.getZ() + 6.5, -118f, 14f);
        FcsMod.LOG.info("[radar_fcs dev] radar scene built at {}", rbase);
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
        // вычислитель, радар, транспондеры разных каналов
        level.setBlock(base, FcsRegistry.FIRE_CONTROL.get().defaultBlockState().setValue(FireControlBlock.FACING, Direction.WEST), 3);
        level.setBlock(base.offset(0, 0, 3), block("create_radar:plane_radar").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), 3);
        DyeColor[] colors = {DyeColor.WHITE, DyeColor.RED, DyeColor.LIME, DyeColor.BLUE};
        for (int i = 0; i < colors.length; i++) {
            level.setBlock(base.offset(8, 0, -3 - i * 2), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState()
                    .setValue(IffTransponderBlock.CHANNEL, colors[i]), 3);
        }
        husk = EntityType.HUSK.create(level);
        if (husk != null) {
            husk.setNoAi(true);
            husk.setPersistenceRequired();
            husk.setPos(base.getX() + 36.5, y, base.getZ() - 6);
            level.addFreshEntity(husk);
        }
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:goggles"))));
        player.teleportTo(level, base.getX() - 6.5, y + 3, base.getZ() + 7.5, -120f, 15f);
        FcsMod.LOG.info("[radar_fcs dev] scene built at {}", base);
    }
}
