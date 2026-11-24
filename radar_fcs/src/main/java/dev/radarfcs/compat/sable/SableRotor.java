package dev.radarfcs.compat.sable;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Подшипник орудия на Sable: блоки перед подшипником (по правилам Create - клей, «липкие» блоки) собираются в
 * отдельный под-уровень, который держится на шарнире (rotary constraint) у грани подшипника; поза поворотной части
 * ставится точно в угол вала ({@link #drive}). Подшипник может стоять и на другом под-уровне (наклон на поворотной
 * части). Класс загружается только при установленном Sable.
 */
public final class SableRotor {
    private SableRotor() {}

    /**
     * Результат сборки: id под-уровня, позиция блока перед подшипником внутри под-уровня и где теперь (в плоте) блоки,
     * которые были собраны (мир -> плот).
     */
    public record Assembled(UUID subLevel, BlockPos plotFront, java.util.List<BlockPos> moved) {}

    /**
     * Собрать блоки перед подшипником в под-уровень. null - собирать нечего; AssemblyException - ошибка сборки Create
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
        // физика Sable могла не узнать, что блоки ушли из мира: их коллайдеры остаются «призраками» на старых местах, и
        // поворотная часть упирается в собственные прежние блоки - сообщить о смене каждого блока
        for (var e : c.getBlocks().entrySet()) {
            BlockPos w = e.getKey().offset(c.anchor);
            if (!level.isLoaded(w) || !level.getBlockState(w).isAir()) continue;
            dev.ryanhcode.sable.SableCommonEvents.handleBlockChange(level, level.getChunkAt(w), w.getX(), w.getY(), w.getZ(),
                    e.getValue().state(), level.getBlockState(w));
        }
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

    /**
     * Связь основания подшипника и его под-уровня; null - под-уровня нет. Связь Sable без закреплённых осей - только
     * чтобы основание и поворотная часть не сталкивались друг с другом: держит и поворачивает часть {@link #drive}.
     * Шарнир Sable с точкой на оси не годится: координаты плота огромные (~2·10⁷), нативная физика считает их с шагом
     * в 2 блока, и точка крепления съезжала к центру масс - часть вращалась вокруг него и упиралась в связь.
     */
    @Nullable
    public static Object attach(ServerLevel level, BlockPos rotatorPos, Direction facing, UUID id, BlockPos plotFront) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        if (containing == sub) return null;
        BlockPos front = rotatorPos.relative(facing);
        Vector3d p1 = new Vector3d(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5);
        Vector3d p2 = new Vector3d(plotFront.getX() + 0.5, plotFront.getY() + 0.5, plotFront.getZ() + 0.5);
        PhysicsPipeline pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        var cfg = new dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration(p1, p2, new Quaterniond(),
                new Quaterniond(), java.util.EnumSet.noneOf(dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis.class));
        var h = pipeline.addConstraint(containing instanceof ServerSubLevel s ? s : null, sub, cfg);
        if (h != null) h.setContactsEnabled(false);
        return h;
    }

    public static boolean valid(@Nullable Object handle) {
        return handle instanceof PhysicsConstraintHandle h && h.isValid();
    }

    public static void remove(@Nullable Object handle) {
        if (handle instanceof PhysicsConstraintHandle h && h.isValid()) h.remove();
    }

    /**
     * Зазор между гранью подшипника и поворотной частью, блоков: вплотную часть лежала бы на подшипнике и соседних
     * блоках (шестерне), и трение касания мешало бы повороту. 1/32 блока глазом не видно.
     */
    static final double GAP = 1.0 / 32;

    /** Собственная частота привода поворотной части, рад/с, и демпфирование (1 - критическое, без колебаний). */
    static final double OMEGA = 25, ZETA = 1;

    /**
     * Физичное ведение поворотной части (подшипник орудия): перед шагом физики dt (секунды) телу добавляется ускорение,
     * как от силы и момента: пружина с демпфером тянет его в позу «повёрнута на angleRad вокруг оси подшипника
     * относительно основания» (основание - мир, корабль или поворотная часть другого подшипника - в его фактической
     * позе), скорость - к скорости этого движения (rateRad, рад/с, - упреждение: на ходу часть не отстаёт), вес
     * компенсируется. Демпфирование критическое - без раскачки. Сила конечная: столкновения решает физика - часть
     * упирается в блоки и постройки, толкает сущности. Всё считается в double на стороне Java.
     */
    public static void drive(ServerLevel level, @Nullable Object handle, BlockPos rotatorPos, Direction facing, UUID id,
                             BlockPos plotFront, double angleRad, double rateRad, double dt) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null || !(dt > 0)) return;
        if (handle instanceof PhysicsConstraintHandle h && h.isValid()) h.setContactsEnabled(false);
        dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub);
        if (body == null || !body.isValid()) return;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        BlockPos front = rotatorPos.relative(facing);
        // ось: центр грани подшипника (в координатах основания) и точка поворотной части напротив неё (в плоте), с зазором
        Vector3d face = new Vector3d(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        Vector3d hinge2 = new Vector3d(plotFront.getX() + 0.5, plotFront.getY() + 0.5, plotFront.getZ() + 0.5).sub(new Vector3d(n).mul(0.5 + GAP));
        Quaterniond qb = new Quaterniond();
        Vector3d hinge1 = new Vector3d(face), vb = new Vector3d(), wb = new Vector3d(), baseCom = new Vector3d();
        if (containing != null) {
            qb.set(containing.logicalPose().orientation());
            hinge1 = containing.logicalPose().transformPosition(face, new Vector3d());
            if (containing instanceof ServerSubLevel cs) {
                var bh = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(cs);
                if (bh != null && bh.isValid()) {
                    bh.getLinearVelocity(vb);
                    bh.getAngularVelocity(wb);
                    var bmd = cs.getMassTracker();
                    baseCom.set(bmd != null && !bmd.isInvalid() ? bmd.getCenterOfMass() : cs.logicalPose().rotationPoint());
                    cs.logicalPose().transformPosition(baseCom);
                }
            }
        }
        Vector3d axis = qb.transform(new Vector3d(n));
        Quaterniond q = new Quaterniond(qb).mul(new Quaterniond().rotationAxis(angleRad, n.x, n.y, n.z)).normalize();
        var pose = sub.logicalPose();
        Vector3d com = new Vector3d(pose.rotationPoint());
        var md = sub.getMassTracker();
        if (md != null && !md.isInvalid()) com.set(md.getCenterOfMass());
        Vector3d comNow = pose.transformPosition(com, new Vector3d());
        Vector3d comGoal = q.transform(new Vector3d(com).sub(hinge2)).add(hinge1);
        // скорость движения цели: основание (поступательно и вращение) плюс поворот вокруг оси
        Vector3d wt = new Vector3d(wb).add(new Vector3d(axis).mul(rateRad));
        Vector3d vt = new Vector3d(vb).add(new Vector3d(wb).cross(new Vector3d(comGoal).sub(baseCom)))
                .add(new Vector3d(axis).mul(rateRad).cross(new Vector3d(comGoal).sub(hinge1)));
        Quaterniond err = new Quaterniond(q).mul(new Quaterniond(pose.orientation()).conjugate()).normalize();
        if (err.w() < 0) err.set(-err.x(), -err.y(), -err.z(), -err.w());
        double half = Math.acos(Math.min(1, err.w())), sin = Math.sin(half);
        Vector3d rotErr = sin < 1e-9 ? new Vector3d() : new Vector3d(err.x(), err.y(), err.z()).mul(2 * half / sin);
        double k = OMEGA * OMEGA, c = 2 * ZETA * OMEGA;
        Vector3d v = body.getLinearVelocity(new Vector3d()), w = body.getAngularVelocity(new Vector3d());
        Vector3d g = dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData.getGravity(level, comNow);
        Vector3d dv = new Vector3d(comGoal).sub(comNow).mul(k).add(new Vector3d(vt).sub(v).mul(c)).sub(g).mul(dt);
        Vector3d dw = new Vector3d(rotErr).mul(k).add(new Vector3d(wt).sub(w).mul(c)).mul(dt);
        body.addLinearAndAngularVelocity(dv, dw);
        SubLevelContainer.getContainer(level).physicsSystem().getPipeline().wakeUp(sub);
    }

    /** Разбудить под-уровень (физика Sable не шагает спящие постройки). */
    public static void wake(ServerLevel level, UUID id) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub != null) SubLevelContainer.getContainer(level).physicsSystem().getPipeline().wakeUp(sub);
    }

    /** Текущий угол поворота под-уровня относительно основания вокруг оси подшипника, градусов (NaN - нет под-уровня). */
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

    /** Подписка на шаги физики Sable: перед каждым шагом - сервоприводы подшипников измерения этой физики. */
    public static void init(java.util.function.ObjDoubleConsumer<ServerLevel> onPhysicsStep) {
        // событие приходит от физики каждого измерения - ведём только подшипники этого
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent e) ->
                        onPhysicsStep.accept(e.getPhysicsSystem().getLevel(), e.getTimeStep()));
    }
}
