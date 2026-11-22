package dev.radarfcs.compat.sable;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Вращатель орудия на Sable: блоки перед вращателем (по правилам Create - клей, «липкие» блоки) собираются в
 * отдельный под-уровень, который держится на шарнире (rotary constraint) у грани вращателя; сервопривод шарнира
 * доворачивает его на заданный угол. Вращатель может стоять и на другом под-уровне (вертикальный на поворотной
 * части). Класс загружается только при установленном Sable.
 */
public final class SableRotor {
    /**
     * Жёсткость и демпфирование сервопривода на единицу момента инерции: собственная частота sqrt(16000) ≈ 126 рад/с,
     * демпфирование 250 - около критического. При 1600/40 (как у поворотных подшипников Aeronautics) длинный ствол
     * провисал под своим весом на десятки градусов и раскачивался (подобрано игровым тестом
     * {@code gunRotatorsSettleWithoutWobble}: ствол из досок и корпусов - на цели без раскачки).
     */
    private static final double STIFFNESS = 16000, DAMPING = 250;

    private SableRotor() {}

    /**
     * Результат сборки: id под-уровня, позиция блока перед вращателем внутри под-уровня и где теперь (в плоте) блоки,
     * которые были собраны (мир -> плот).
     */
    public record Assembled(UUID subLevel, BlockPos plotFront, java.util.List<BlockPos> moved) {}

    /**
     * Собрать блоки перед вращателем в под-уровень. null - собирать нечего; AssemblyException - ошибка сборки Create
     * (слишком много блоков, неподвижный блок).
     */
    @Nullable
    public static Assembled assemble(ServerLevel level, BlockPos rotatorPos, Direction facing) throws AssemblyException {
        BearingContraption c = new BearingContraption(false, facing);
        if (!c.assemble(level, rotatorPos) || c.getBlocks().isEmpty()) return null;
        Set<BlockPos> blocks = new HashSet<>();
        for (BlockPos local : c.getBlocks().keySet()) blocks.add(local.offset(c.anchor).immutable());
        BlockPos anchor = rotatorPos.relative(facing);
        if (!blocks.contains(anchor)) return null;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        ServerSubLevel sub = (ServerSubLevel) SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, BoundingBox3i.from(blocks));
        if (sub == null) return null;
        BlockPos plotFront = anchor.offset(sub.getPlot().getCenterBlock().subtract(anchor));
        if (containing instanceof ServerSubLevel parent) {
            // собран с поворотной части: новый под-уровень - отдельно от неё, в её ориентации
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            SubLevelPhysicsSystem physics = container.physicsSystem();
            PhysicsPipeline pipeline = physics.getPipeline();
            SubLevelAssemblyHelper.kickFromContainingSubLevel(level, physics, pipeline, sub, parent);
            sub.logicalPose().orientation().set(parent.logicalPose().orientation());
            pipeline.teleport(sub, sub.logicalPose().position(), sub.logicalPose().orientation());
            sub.updateLastPose();
        }
        BlockPos shift = plotFront.subtract(anchor);
        java.util.List<BlockPos> moved = new java.util.ArrayList<>();
        for (BlockPos b : blocks) moved.add(b.offset(shift));
        return new Assembled(sub.getUniqueId(), plotFront, moved);
    }

    /** Под-уровень по id (или null - выгружен, удалён). */
    @Nullable
    private static ServerSubLevel subLevel(ServerLevel level, UUID id) {
        SubLevel s = SubLevelContainer.getContainer(level).getSubLevel(id);
        return s instanceof ServerSubLevel ss && !ss.isRemoved() ? ss : null;
    }

    public static boolean exists(ServerLevel level, UUID id) {
        return subLevel(level, id) != null;
    }

    /** Шарнир между основанием вращателя и его под-уровнем; null - под-уровня нет. */
    @Nullable
    public static Object attach(ServerLevel level, BlockPos rotatorPos, Direction facing, UUID id, BlockPos plotFront) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        if (containing == sub) return null;
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        BlockPos front = rotatorPos.relative(facing);
        // точки шарнира - центр грани между вращателем и первым блоком: в координатах основания и под-уровня
        Vector3d p1 = new Vector3d(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        Vector3d p2 = new Vector3d(plotFront.getX() + 0.5, plotFront.getY() + 0.5, plotFront.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        PhysicsPipeline pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        RotaryConstraintConfiguration cfg = new RotaryConstraintConfiguration(p1, p2, n, n);
        RotaryConstraintHandle h = pipeline.addConstraint(containing instanceof ServerSubLevel s ? s : null, sub, cfg);
        if (h != null) h.setContactsEnabled(false);
        return h;
    }

    public static boolean valid(@Nullable Object handle) {
        return handle instanceof RotaryConstraintHandle h && h.isValid();
    }

    public static void remove(@Nullable Object handle) {
        if (handle instanceof RotaryConstraintHandle h && h.isValid()) h.remove();
    }

    /**
     * Сервопривод шарнира: угол goalRad (относительно положения при сборке) с жёсткостью по моменту инерции. У
     * вращателя на земле (или на корабле, не на другой поворотной части) момент инерции - вокруг оси шарнира (с плечом
     * от шарнира до центра масс) вместе со всем, что держится на поворотной части на своих вращателях (children -
     * под-уровни вращателей, стоящих на под-уровне): иначе привод курса считал только лёгкую площадку, был для
     * тяжёлого ствола на ней в десятки раз слабее, отставал и раскачивался; привод наклона со своей инерцией ствола
     * (без плеча) давал провисание длинного ствола на 30-40°.
     */
    public static void servo(ServerLevel level, @Nullable Object handle, BlockPos rotatorPos, Direction facing, UUID id,
                             BlockPos plotFront, double goalRad, java.util.function.Function<UUID, java.util.List<UUID>> children) {
        if (!(handle instanceof RotaryConstraintHandle h) || !h.isValid()) return;
        ServerSubLevel sub = subLevel(level, id);
        Vector3d axis = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        double inertiaB = Double.MAX_VALUE, inertiaA = Double.MAX_VALUE;
        Vector3d tmp = new Vector3d();
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        if (sub != null) {
            inertiaB = sub.getMassTracker().getInertiaTensor().transform(axis, tmp).dot(axis);
            try {
                // шарнир в мире: точка и ось - через позу поворотной части (в ней они те же, что при сборке)
                Vector3d p2 = new Vector3d(plotFront.getX() + 0.5, plotFront.getY() + 0.5, plotFront.getZ() + 0.5).sub(new Vector3d(axis).mul(0.5));
                Vector3d pivotW = sub.logicalPose().transformPosition(p2, new Vector3d());
                Vector3d axisW = sub.logicalPose().transformNormal(new Vector3d(axis), new Vector3d()).normalize();
                double tree = treeInertia(level, sub, pivotW, axisW, children, new HashSet<>());
                if (Double.isFinite(tree) && tree > inertiaB) inertiaB = tree;
            } catch (RuntimeException ignored) {
            }
        }
        if (containing instanceof ServerSubLevel c) inertiaA = c.getMassTracker().getInertiaTensor().transform(axis, tmp).dot(axis);
        double inertia = Math.max(10, containing != null && sub != null ? Math.max(inertiaA, inertiaB) : Math.min(inertiaA, inertiaB));
        h.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, goalRad, STIFFNESS * inertia, DAMPING * inertia, false, 0);
        // касания основания и поворотной части - выключать на каждом шаге, как поворотный подшипник Simulated: флаг,
        // выставленный один раз при подключении, не держится, и части тёрлись и опирались друг на друга (застревание,
        // перескоки площадки с наклоном)
        h.setContactsEnabled(false);
        if (sub != null) SubLevelContainer.getContainer(level).physicsSystem().getPipeline().wakeUp(sub);
    }

    /** Момент инерции под-уровня и всех под-уровней на его вращателях вокруг мировой оси через точку pivot. */
    private static double treeInertia(ServerLevel level, ServerSubLevel s, Vector3d pivot, Vector3d axis,
                                      java.util.function.Function<UUID, java.util.List<UUID>> children, Set<UUID> seen) {
        if (!seen.add(s.getUniqueId()) || seen.size() > 16) return 0;
        double sum = bodyInertia(s, pivot, axis);
        for (UUID c : children.apply(s.getUniqueId())) {
            ServerSubLevel cs = subLevel(level, c);
            if (cs != null) sum += treeInertia(level, cs, pivot, axis, children, seen);
        }
        return sum;
    }

    /** Момент инерции тела вокруг мировой оси axis через pivot: свой (у центра масс) плюс масса x плечо². */
    private static double bodyInertia(ServerSubLevel s, Vector3d pivot, Vector3d axis) {
        dev.ryanhcode.sable.api.physics.mass.MassData md = s.getMassTracker();
        dev.ryanhcode.sable.companion.math.Pose3dc pose = s.logicalPose();
        Vector3d aL = pose.transformNormalInverse(new Vector3d(axis), new Vector3d()).normalize();
        double own = md.getInertiaTensor().transform(new Vector3d(aL), new Vector3d()).dot(aL);
        Vector3d com = pose.transformPosition(new Vector3d(pose.rotationPoint()), new Vector3d()).sub(pivot);
        com.sub(new Vector3d(axis).mul(com.dot(axis)));
        double r = own + md.getMass() * com.lengthSquared();
        return Double.isFinite(r) ? r : 0;
    }

    /** Текущий угол поворота под-уровня относительно основания вокруг оси вращателя, градусов (NaN - нет под-уровня). */
    public static double angleDeg(ServerLevel level, BlockPos rotatorPos, Direction facing, UUID id) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return Double.NaN;
        Quaterniond a = new Quaterniond();
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        if (containing != null) a.set(containing.logicalPose().orientation());
        Quaterniond rel = new Quaterniond(a).conjugate().mul(new Quaterniond(sub.logicalPose().orientation()));
        double proj = rel.x() * facing.getStepX() + rel.y() * facing.getStepY() + rel.z() * facing.getStepZ();
        return dev.radarfcs.core.Aim.wrap180(Math.toDegrees(2 * Math.atan2(proj, rel.w())));
    }

    /** Время доли физического тика (для плавного хода цели сервопривода между тиками). */
    public static double partialPhysicsTick(ServerLevel level) {
        SubLevelPhysicsSystem s = SubLevelPhysicsSystem.get(level);
        return s == null ? 1 : s.getPartialPhysicsTick();
    }

    /** Подписка на шаги физики Sable: каждый шаг - сервоприводы всех собранных вращателей. */
    public static void init(Runnable onPhysicsStep) {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent e) -> onPhysicsStep.run());
    }
}
