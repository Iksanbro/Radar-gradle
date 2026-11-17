package dev.radarfcs.gametest;

import ace.flight.block.FlightControlComputerBlockEntity;
import ace.flight.block.ForwardIndicatorBlockEntity;
import ace.flight.block.SmartVectorThrusterBlockEntity;
import dev.radarfcs.FcsDev;
import dev.radarfcs.FcsMod;
import dev.radarfcs.block.AutopilotBlockEntity;
import dev.radarfcs.block.RadarMonitorBlock;
import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.compat.flight.FlightBridge;
import dev.radarfcs.compat.flight.FlightBridgeBlockEntity;
import dev.radarfcs.core.Autopilot;
import dev.radarfcs.registry.FcsRegistry;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Настоящий полёт корабля Create: Flight Control с мостом автопилота (dev-клиент, -PfcsFlight): палуба 5x5, компьютер
 * управления полётом, 4 творческих векторных двигателя соплами вниз, мост, индикатор «вперёд = север», радарный линк;
 * цель - хаск на столбе к востоку, назначается на мониторе сети. Каждые 10 тиков в лог: положение, курс носа, ошибка
 * курса, дальность, состояние моста, сила и момент компьютера.
 */
final class FlightScene {
    private static final UUID NET = UUID.randomUUID();
    private static BlockPos deck, monitor;
    private static Husk target;
    private static ServerSubLevel ship;
    private static FlightBridgeBlockEntity bridge;
    private static FlightControlComputerBlockEntity computer;

    private FlightScene() {}

    static Block block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** Построить корабль на высоте 30 над base, всё связать и собрать в постройку Sable. */
    static void build(MinecraftServer server, BlockPos base, Autopilot.Mode mode, boolean pitch, boolean roll, boolean holds, boolean couplers) {
        ServerLevel level = server.overworld();
        FcsDev.radarless = true;
        FcsDev.radarlessRange = 300;
        deck = new BlockPos(base.getX(), Math.min(base.getY() + 30, 200), base.getZ() + 60);   // ниже предела высоты мира
        var air = Blocks.AIR.defaultBlockState();
        for (int dx = -6; dx <= 6; dx++) for (int dy = -3; dy <= 4; dy++) for (int dz = -6; dz <= 6; dz++) level.setBlock(deck.offset(dx, dy, dz), air, 3);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.setBlock(deck.offset(dx, 0, dz), block("create:andesite_casing").defaultBlockState(), 3);
        BlockPos cc = deck.above();
        level.setBlock(cc, block("create_flight_control:flight_control_computer").defaultBlockState()
                .trySetValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH), 3);
        BlockPos br = deck.offset(0, 1, 1);
        level.setBlock(br, FlightBridge.BLOCK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 3);
        BlockPos ind = deck.offset(1, 1, 0);
        level.setBlock(ind, block("create_flight_control:forward_indicator").defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH), 3);             // вперёд = север
        BlockPos link = br.above();
        level.setBlock(link, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, Direction.UP), 3);
        if (level.getBlockEntity(link) instanceof dev.radarfcs.block.RadarLinkBlockEntity l) l.setNetwork(NET);
        List<BlockPos> thrusters = new ArrayList<>();
        for (int sx : new int[]{-2, 2}) for (int sz : new int[]{-2, 2}) {
            BlockPos t = deck.offset(sx, -1, sz);
            level.setBlock(t, block("create_flight_control:creative_smart_vector_thruster").defaultBlockState()
                    .trySetValue(BlockStateProperties.FACING, Direction.DOWN), 3);
            thrusters.add(t);
        }
        if (!(level.getBlockEntity(cc) instanceof FlightControlComputerBlockEntity c)) throw new IllegalStateException("no computer");
        for (BlockPos t : thrusters) {
            if (level.getBlockEntity(t) instanceof SmartVectorThrusterBlockEntity svt) {
                c.addThruster(t, svt.getNozzleDirection(), svt.getEffectiveMaxThrustForComputer(), svt.halfAngleDeg, svt.getBodyThrustCenterOffset());
                svt.setLinkedComputer(t, cc);
            } else {
                FcsMod.LOG.warn("[radar_fcs flight] no thruster at {}", t);
            }
        }
        if (!(level.getBlockEntity(br) instanceof FlightBridgeBlockEntity b)) throw new IllegalStateException("no bridge");
        c.addController(br);
        b.setLinkedComputer(br, cc);
        // регуляторы скорости и угловой скорости (и, для проверки подавления, стабилизаторы ориентации и высоты):
        // верхом вверх, вал снизу - творческий мотор в палубе под каждым
        java.util.List<BlockPos> ctrls = new java.util.ArrayList<>();
        if (couplers) {
            ctrls.add(place(level, deck.offset(-1, 1, -1), "velocity_coupler"));
            ctrls.add(place(level, deck.offset(1, 1, -1), "angular_velocity_coupler"));
        }
        if (holds) {
            ctrls.add(place(level, deck.offset(-1, 1, 1), "attitude_hold"));
            ctrls.add(place(level, deck.offset(1, 1, 1), "altitude_hold"));
        }
        for (BlockPos p : ctrls) {
            if (level.getBlockEntity(p) instanceof ace.flight.api.WrenchContributor wc) {
                c.addController(p);
                wc.setLinkedComputer(p, cc);
            }
            if (level.getBlockEntity(p) instanceof ace.flight.block.VelocityCouplerBlockEntity v) {
                v.setConfig(false, v.kpX, v.kpY, v.kpZ, v.kdX, v.kdY, v.kdZ, 15, 15, 15, 15, 15, 15);
            }
            if (level.getBlockEntity(p) instanceof ace.flight.block.AngularVelocityCouplerBlockEntity w) {
                w.setConfig(false, w.kpX, w.kpY, w.kpZ, w.kdX, w.kdY, w.kdZ, 0.6, 0.6, 0.6, 0.6, 0.6, 0.6);
            }
            if (level.getBlockEntity(p) instanceof ace.flight.block.AttitudeHoldBlockEntity at) {
                at.setConfig(at.kp, at.kd, ace.flight.control.AttitudeHoldMode.HOLD_CURRENT, at.getBodyForwardFacing(), true);
            }
            if (level.getBlockEntity(p) instanceof ace.flight.block.AltitudeHoldBlockEntity al) {
                al.setConfig(true, al.kp, al.kd, 1, true, deck.getY() - 20, 0, 1);       // держать на 20 ниже - мешало бы
            }
        }
        if (level.getBlockEntity(ind) instanceof ForwardIndicatorBlockEntity i) b.linkForwardIndicator(i);
        b.mode = mode;
        b.distance = 24;
        b.height = 10;
        b.maxSpeed = 15;
        b.heading = true;
        b.pitch = pitch;
        b.roll = roll;
        c.setWorking(true);
        c.workDryRun = false;
        FcsMod.LOG.info("[radar_fcs flight] built: thrusters {}, computer working {}, dry {}, mode {}, bridge forward {}",
                thrusters.size(), c.working, c.workDryRun, c.mode, b.forwardFacing());
        // цель - хаск на столбе в 60 блоках к востоку, монитор сети на земле
        BlockPos pillar = deck.offset(60, -10, 0);
        for (int y = -40; y <= 0; y++) for (int z = -60; z <= 0; z++) level.setBlock(pillar.offset(0, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : air, 3);
        Husk h = EntityType.HUSK.create(level);
        h.moveTo(pillar.getX() + 0.5, pillar.getY() + 1, pillar.getZ() + 0.5, 0, 0);
        h.setNoAi(true);
        h.setInvulnerable(true);
        h.setPersistenceRequired();
        level.addFreshEntity(h);
        target = h;
        monitor = base.offset(-4, 1, 60);
        level.setBlock(monitor, FcsRegistry.RADAR_MONITOR.get().defaultBlockState().setValue(RadarMonitorBlock.FACING, Direction.SOUTH), 3);
        BlockPos ml = monitor.above();
        level.setBlock(ml, FcsRegistry.RADAR_LINK.get().defaultBlockState().setValue(dev.radarfcs.block.RadarLinkBlock.FACING, Direction.UP), 3);
        if (level.getBlockEntity(ml) instanceof dev.radarfcs.block.RadarLinkBlockEntity l) l.setNetwork(NET);
        BlockPos min = deck.offset(-2, -1, -2), max = deck.offset(2, 2, 2);
        ship = SubLevelAssemblyHelper.assembleBlocks(level, min, BlockPos.betweenClosed(min, max),
                new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        bridge = null;
        computer = null;
    }

    private static BlockPos place(ServerLevel level, BlockPos p, String id) {
        var st = block("create_flight_control:" + id).defaultBlockState();
        for (var prop : st.getProperties()) {
            if (prop.getName().equals("top") && prop instanceof net.minecraft.world.level.block.state.properties.DirectionProperty dp) st = st.setValue(dp, Direction.UP);
        }
        level.setBlock(p, st, 3);
        level.setBlock(p.below(), block("create:creative_motor").defaultBlockState().trySetValue(BlockStateProperties.FACING, Direction.UP), 3);
        if (level.getBlockEntity(p.below()) instanceof com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity m) m.generatedSpeed.setValue(64);
        return p;
    }

    /** Найти блоки на постройке (после сборки они в плоте). */
    private static void resolve(ServerLevel level) {
        if (bridge != null) return;
        for (BlockPos p : dev.radarfcs.link.LinkRegistry.members(level, NET)) {
            if (level.getBlockEntity(p) instanceof FlightBridgeBlockEntity b) {
                bridge = b;
                BlockPos c = b.getLinkedComputerPos();
                if (c != null && level.getBlockEntity(c) instanceof FlightControlComputerBlockEntity cc) computer = cc;
            }
        }
    }

    /** Цель идёт на север 3 бл/с (телепорт каждый тик). */
    static void move(MinecraftServer server) {
        if (target != null) target.teleportTo(target.getX(), target.getY(), target.getZ() - 0.15);
    }

    static void designate(MinecraftServer server) {
        if (server.overworld().getBlockEntity(monitor) instanceof RadarMonitorBlockEntity m) {
            m.master().designate(target.getUUID(), null);
            FcsMod.LOG.info("[radar_fcs flight] designated: {}", m.master().localDesignation(server.overworld().getGameTime()));
        }
    }

    static void log(MinecraftServer server, String tag) {
        ServerLevel level = server.overworld();
        resolve(level);
        if (ship == null || ship.isRemoved()) {
            FcsMod.LOG.info("[radar_fcs flight] {}: ship removed", tag);
            return;
        }
        Vector3d p = new Vector3d(ship.logicalPose().position());
        Quaterniond q = new Quaterniond(ship.logicalPose().orientation());
        Vector3d fwd = q.transform(new Vector3d(0, 0, -1));
        Vector3d up = q.transform(new Vector3d(0, 1, 0));
        Vec3 t = target.position();
        tag = tag + " tz=" + f(t.z);
        double dx = t.x - p.x, dz = t.z - p.z;
        double yawErr = Math.toDegrees(Autopilot.yawError(new dev.radarfcs.core.Vec(fwd.x, fwd.y, fwd.z), new dev.radarfcs.core.Vec(dx, 0, dz)));
        String st = bridge == null ? "no bridge" : bridge.state() + " goal=" + (bridge.goal() != null) + " contrib=" + bridge.isContributing()
                + " target=" + bridge.targetName() + " state=" + bridge.state();
        String cc = computer == null ? "no computer" : "working=" + computer.working + " dry=" + computer.workDryRun
                + " F=(" + f(Double.longBitsToDouble(field(computer, "workTargetFX"))) + ", " + f(Double.longBitsToDouble(field(computer, "workTargetFY")))
                + ", " + f(Double.longBitsToDouble(field(computer, "workTargetFZ"))) + ") T=(" + f(Double.longBitsToDouble(field(computer, "workTargetTX")))
                + ", " + f(Double.longBitsToDouble(field(computer, "workTargetTY"))) + ", " + f(Double.longBitsToDouble(field(computer, "workTargetTZ"))) + ")"
                + " active=" + field(computer, "ctrlActiveCount");
        FcsMod.LOG.info("[radar_fcs flight] {}: pos ({}, {}, {}) yawErr {} up.y {} dist {} dy {} | {} | {}", tag, f(p.x), f(p.y), f(p.z), f(yawErr),
                f(up.y), f(Math.hypot(dx, dz)), f(p.y - t.y), st, cc);
    }

    private static long field(Object o, String name) {
        try {
            var fl = o.getClass().getDeclaredField(name);
            fl.setAccessible(true);
            Object v = fl.get(o);
            return v instanceof Number n ? n.longValue() : -1;
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    static Vec3 camera() {
        return ship == null ? Vec3.atCenterOf(deck) : new Vec3(ship.logicalPose().position().x(), ship.logicalPose().position().y(), ship.logicalPose().position().z());
    }
}
