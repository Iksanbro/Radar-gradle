package dev.radarfcs.compat.sable;

import com.simibubi.create.content.contraptions.AssemblyException;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.RotaryConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.util.SimAssemblyHelper;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Подшипник орудия на Sable - как поворотный подшипник Create Aeronautics (мод Simulated): постройка перед подшипником
 * собирается сборщиком Simulated (клей, «липкие» блоки) в под-уровень, в его плот ставится пластина подшипника (в клетке
 * самого подшипника), под-уровень держится шарниром Sable (точки крепления - центр блока перед подшипником и он же в
 * плоте), поворачивает его мотор шарнира - сервопривод к углу ({@link #servo}). Класс загружается только при
 * установленных Sable и Simulated (их API, без копирования кода).
 */
public final class SableRotor {
    private SableRotor() {}

    /** Результат сборки: id под-уровня и позиция пластины подшипника в его плоте. */
    public record Assembled(UUID subLevel, BlockPos platePos) {}

    /**
     * Собрать постройку перед подшипником (как подшипник Aeronautics): блоки - сборщиком Simulated; перед подшипником
     * пусто - под-уровень из одной пластины (на ней можно строить). Пластина plateState ставится в плот в клетку
     * подшипника. Подшипник на другом под-уровне (наклон на поворотной части) - новый под-уровень выносится из него и
     * получает его ориентацию.
     */
    public static Assembled assemble(ServerLevel level, BlockPos bearingPos, Direction facing, BlockState plateState)
            throws AssemblyException {
        BlockPos front = bearingPos.relative(facing);
        SimAssemblyHelper.AssemblyResult r = SimAssemblyHelper.assembleFromSingleBlock(level, bearingPos, front, false, false);
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        ServerSubLevel sub;
        BlockPos offset;
        if (r != null) {
            sub = (ServerSubLevel) r.subLevel();
            offset = r.offset();
            level.setBlockAndUpdate(bearingPos.offset(offset), plateState);
        } else {
            // перед подшипником пусто: под-уровень из одной пластины
            Pose3d pose = new Pose3d();
            pose.position().set(bearingPos.getX() + 0.5, bearingPos.getY() + 0.5, bearingPos.getZ() + 0.5);
            sub = (ServerSubLevel) container.allocateNewSubLevel(pose);
            var plot = sub.getPlot();
            plot.newEmptyChunk(plot.getCenterChunk());
            plot.getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, plateState, 3);
            BlockPos center = plot.getCenterBlock();
            Vector3d at = JOMLConversion.atLowerCornerOf(bearingPos);
            Vector3dc com = sub.getMassTracker() != null ? sub.getMassTracker().getCenterOfMass() : null;
            if (com != null) {
                at.add(com.x() - center.getX(), com.y() - center.getY(), com.z() - center.getZ());
            } else {
                sub.logicalPose().rotationPoint().set(center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5);
            }
            sub.logicalPose().position().set(at.x, at.y, at.z);
            offset = center.subtract(bearingPos);
        }
        SubLevel containing = Sable.HELPER.getContaining(level, bearingPos);
        SubLevelPhysicsSystem physics = container.physicsSystem();
        PhysicsPipeline pipeline = physics.getPipeline();
        if (containing instanceof ServerSubLevel parent) {
            // собран с поворотной части: новый под-уровень - отдельно от неё, в её ориентации
            SubLevelAssemblyHelper.kickFromContainingSubLevel(level, physics, pipeline, sub, parent);
            sub.logicalPose().orientation().set(parent.logicalPose().orientation());
        }
        pipeline.teleport(sub, sub.logicalPose().position(), sub.logicalPose().orientation());
        sub.updateLastPose();
        return new Assembled(sub.getUniqueId(), bearingPos.offset(offset));
    }

    /**
     * Разобрать: блоки под-уровня - обратно в мир (сборщиком Simulated), пластина platePos - на место подшипника
     * bearingPos (сама пластина к этому времени снята). false - под-уровня уже нет.
     */
    public static boolean disassemble(ServerLevel level, UUID id, BlockPos platePos, BlockPos bearingPos) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return false;
        SimAssemblyHelper.disassembleSubLevel(level, sub, platePos, bearingPos, Rotation.NONE, true);
        return true;
    }

    /** Под-уровень подшипник стоит на самом себе (разобрать некуда). */
    public static boolean containedIn(ServerLevel level, BlockPos bearingPos, UUID id) {
        SubLevel c = Sable.HELPER.getContaining(level, bearingPos);
        return c != null && id.equals(c.getUniqueId());
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
     * Шарнир между основанием подшипника и его под-уровнем (как у подшипника Aeronautics): точки крепления - центр блока
     * перед подшипником (в координатах основания) и он же в плоте (перед пластиной, на тысячную блока назад по оси),
     * оси - нормаль подшипника и пластины; касания основания и поворотной части выключены. null - под-уровня нет.
     */
    @Nullable
    public static Object attach(ServerLevel level, BlockPos bearingPos, Direction facing, UUID id, BlockPos platePos) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(level, bearingPos);
        if (containing == sub) return null;
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vector3d p1 = JOMLConversion.toJOML(bearingPos.relative(facing).getCenter());
        Vector3d p2 = JOMLConversion.toJOML(platePos.relative(facing).getCenter()).sub(new Vector3d(n).mul(0.001));
        PhysicsPipeline pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        RotaryConstraintHandle h = pipeline.addConstraint(containing instanceof ServerSubLevel s ? s : null, sub,
                new RotaryConstraintConfiguration(p1, p2, n, n));
        if (h != null) h.setContactsEnabled(false);
        return h;
    }

    public static boolean valid(@Nullable Object handle) {
        return handle instanceof RotaryConstraintHandle h && h.isValid();
    }

    public static void remove(@Nullable Object handle) {
        if (handle instanceof RotaryConstraintHandle h && h.isValid()) h.remove();
    }

    /** Мотор шарнира - сервопривод к углу goalRad (от положения при сборке): жёсткость и демпфирование. */
    public static void servo(@Nullable Object handle, double goalRad, double stiffness, double damping) {
        if (!(handle instanceof RotaryConstraintHandle h) || !h.isValid()) return;
        h.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, goalRad, stiffness, damping, false, 0);
        h.setContactsEnabled(false);
    }

    /** Мотор шарнира без цели - только трение (подшипник не заблокирован: поворотная часть крутится свободно). */
    public static void free(@Nullable Object handle, double friction) {
        if (!(handle instanceof RotaryConstraintHandle h) || !h.isValid()) return;
        h.setMotor(RotaryConstraintHandle.DEFAULT_AXIS, 0, 0, friction, false, 0);
        h.setContactsEnabled(false);
    }

    /**
     * Момент инерции вокруг оси подшипника всего, что поворачивается: под-уровни parts (поворотная часть и части
     * подшипников на ней) - собственный момент вокруг параллельной оси через центр масс плюс масса·расстояние².
     */
    public static double inertia(ServerLevel level, BlockPos bearingPos, Direction facing, List<UUID> parts) {
        SubLevel containing = Sable.HELPER.getContaining(level, bearingPos);
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vector3d p1 = JOMLConversion.toJOML(bearingPos.relative(facing).getCenter());
        Vector3d hinge = containing != null ? containing.logicalPose().transformPosition(p1, new Vector3d()) : p1;
        Vector3d axis = containing != null ? containing.logicalPose().transformNormal(new Vector3d(n), new Vector3d()).normalize() : n;
        double sum = 0;
        for (UUID id : parts) {
            ServerSubLevel sub = subLevel(level, id);
            if (sub == null) continue;
            var md = sub.getMassTracker();
            if (md == null || md.isInvalid()) continue;
            Vector3d local = new Quaterniond(sub.logicalPose().orientation()).conjugate().transform(new Vector3d(axis));
            double own = md.getInertiaTensor().transform(new Vector3d(local), new Vector3d()).dot(local);
            Vector3d com = sub.logicalPose().transformPosition(new Vector3d(md.getCenterOfMass()), new Vector3d()).sub(hinge);
            double d2 = com.lengthSquared() - Math.pow(com.dot(axis), 2);
            sum += own + md.getMass() * Math.max(0, d2);
        }
        return sum;
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

    /** Доля физического тика (для плавного хода цели сервопривода между тиками; в событии до шага - на конец шага). */
    public static double partialPhysicsTick(ServerLevel level) {
        SubLevelPhysicsSystem s = SubLevelPhysicsSystem.get(level);
        return s == null ? 1 : s.getPartialPhysicsTick();
    }

    /** Физика Sable на паузе (подшипник не накручивает угол). */
    public static boolean paused(ServerLevel level) {
        SubLevelPhysicsSystem s = SubLevelPhysicsSystem.get(level);
        return s != null && s.getPaused();
    }

    /** Подписка на шаги физики Sable: перед каждым шагом - сервоприводы подшипников измерения этой физики. */
    public static void init(java.util.function.ObjDoubleConsumer<ServerLevel> onPhysicsStep) {
        // событие приходит от физики каждого измерения - ведём только подшипники этого
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent e) ->
                        onPhysicsStep.accept(e.getPhysicsSystem().getLevel(), e.getTimeStep()));
    }
}
