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

    /** Шарнир между основанием подшипника и его под-уровнем; null - под-уровня нет. */
    @Nullable
    public static Object attach(ServerLevel level, BlockPos rotatorPos, Direction facing, UUID id, BlockPos plotFront) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return null;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        if (containing == sub) return null;
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        BlockPos front = rotatorPos.relative(facing);
        // точки шарнира - центр грани между подшипником и первым блоком: в координатах основания и под-уровня
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
     * Жёсткое ведение поворотной части (подшипник орудия): поза под-уровня ставится на каждом шаге физики точно в угол
     * angleRad вокруг оси шарнира относительно основания (мир или поворотная часть другого подшипника), скорость -
     * как у основания в этой точке. Пружины нет - нет провисания под весом, отставания и раскачки; шарнир остаётся
     * только ради выключенных касаний основания и поворотной части (без мотора, чтобы не спорить с позой). Поза ставится
     * и до, и после каждого шага физики: за шаг часть уходит от позы, и вычислитель видел бы скачки.
     */
    public static void drive(ServerLevel level, @Nullable Object handle, BlockPos rotatorPos, Direction facing, UUID id,
                             BlockPos plotFront, double angleRad) {
        ServerSubLevel sub = subLevel(level, id);
        if (sub == null) return;
        if (handle instanceof RotaryConstraintHandle h && h.isValid()) h.setContactsEnabled(false);
        dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(sub);
        if (body == null || !body.isValid()) return;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        Quaterniond qb = new Quaterniond();
        if (containing != null) qb.set(containing.logicalPose().orientation());
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        BlockPos front = rotatorPos.relative(facing);
        // точка шарнира: центр грани между подшипником и первым блоком - в координатах основания и поворотной части
        Vector3d p1 = new Vector3d(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        Vector3d p2 = new Vector3d(plotFront.getX() + 0.5, plotFront.getY() + 0.5, plotFront.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        Vector3d p1w = containing != null ? containing.logicalPose().transformPosition(p1, new Vector3d()) : p1;
        Quaterniond q = new Quaterniond(qb).mul(new Quaterniond().rotationAxis(angleRad, n.x, n.y, n.z)).normalize();
        Vector3d rp = new Vector3d(sub.logicalPose().rotationPoint());
        Vector3d pos = new Vector3d(p1w).sub(q.transform(new Vector3d(p2).sub(rp)));
        body.teleport(pos, q);
        // скорость: как у основания в центре масс поворотной части (на земле - ноль)
        Vector3d v = new Vector3d(), w = new Vector3d();
        if (containing instanceof ServerSubLevel c) {
            dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle base = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(c);
            if (base != null && base.isValid()) {
                Vector3d vb = base.getLinearVelocity(new Vector3d()), wb = base.getAngularVelocity(new Vector3d());
                Vector3d r = new Vector3d(pos).sub(c.logicalPose().position());
                v.set(vb).add(new Vector3d(wb).cross(r));
                w.set(wb);
            }
        }
        Vector3d dv = v.sub(body.getLinearVelocity(new Vector3d())), dw = w.sub(body.getAngularVelocity(new Vector3d()));
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

    /** Подписка на шаги физики Sable: каждый шаг - ведение поворотных частей всех собранных подшипников орудия. */
    /**
     * Какую долю поворота на deltaRad (вокруг оси подшипника от текущего угла) поворотная часть пройдёт, не войдя в блоки
     * мира: 1 - свободно, 0 - упирается сразу. Проверяются блоки самой части и частей подшипников, стоящих на ней
     * (subs), - по точкам каждого блока (центр и углы, отступ 0.3 от граней - касания соседей не мешают), с шагом поворота
     * не больше 3° (за шаг край части сдвигается не больше чем на полблока на 10 блоках вылета). Блоки, в которые часть
     * уже входит до поворота, не держат (иначе застрявшую часть нельзя было бы вывести).
     */
    public static double freeFraction(ServerLevel level, BlockPos rotatorPos, Direction facing, java.util.Collection<UUID> subs,
                                      double deltaRad) {
        if (deltaRad == 0) return 1;
        SubLevel containing = Sable.HELPER.getContaining(level, rotatorPos);
        BlockPos front = rotatorPos.relative(facing);
        Vector3d n = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vector3d p1 = new Vector3d(front.getX() + 0.5, front.getY() + 0.5, front.getZ() + 0.5).sub(new Vector3d(n).mul(0.5));
        Vector3d hinge = containing != null ? containing.logicalPose().transformPosition(p1, new Vector3d()) : p1;
        Vector3d axis = containing != null ? containing.logicalPose().transformNormal(new Vector3d(n), new Vector3d()) : n;
        // точки блоков частей в мире сейчас
        List<Vector3d> pts = new java.util.ArrayList<>();
        for (UUID id : subs) {
            ServerSubLevel sub = subLevel(level, id);
            if (sub == null) continue;
            var bb = sub.getPlot().getBoundingBox();
            long volume = (long) (bb.maxX() - bb.minX() + 1) * (bb.maxY() - bb.minY() + 1) * (bb.maxZ() - bb.minZ() + 1);
            if (volume > 200_000) continue;                                       // огромная постройка - не проверяем
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int x = bb.minX(); x <= bb.maxX(); x++) for (int y = bb.minY(); y <= bb.maxY(); y++)
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    mp.set(x, y, z);
                    var st = level.getBlockState(mp);
                    if (st.isAir() || st.getCollisionShape(level, mp).isEmpty()) continue;
                    for (int c = 0; c < 9; c++) {
                        double ox = c == 0 ? 0.5 : ((c & 1) != 0 ? 0.8 : 0.2), oy = c == 0 ? 0.5 : ((c & 2) != 0 ? 0.8 : 0.2),
                                oz = c == 0 ? 0.5 : ((c & 4) != 0 ? 0.8 : 0.2);
                        pts.add(sub.logicalPose().transformPosition(new Vector3d(x + ox, y + oy, z + oz), new Vector3d()));
                    }
                    if (pts.size() > 40_000) break;
                }
        }
        if (pts.isEmpty()) return 1;
        // уже занятые точки (часть стоит в блоках) не считаются
        boolean[] stuck = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) stuck[i] = solidAt(level, pts.get(i));
        int steps = Math.max(1, (int) Math.ceil(Math.abs(Math.toDegrees(deltaRad)) / 3));
        for (int k = 1; k <= steps; k++) {
            double f = (double) k / steps;
            Quaterniond r = new Quaterniond().rotationAxis(deltaRad * f, axis.x, axis.y, axis.z);
            for (int i = 0; i < pts.size(); i++) {
                if (stuck[i]) continue;
                Vector3d w = r.transform(new Vector3d(pts.get(i)).sub(hinge)).add(hinge);
                if (solidAt(level, w)) return (double) (k - 1) / steps;
            }
        }
        return 1;
    }

    /** Точка мира внутри формы столкновения блока мира (только загруженные чанки). */
    private static boolean solidAt(ServerLevel level, Vector3d w) {
        BlockPos bp = BlockPos.containing(w.x, w.y, w.z);
        if (!level.isLoaded(bp)) return false;
        var st = level.getBlockState(bp);
        if (st.isAir()) return false;
        var shape = st.getCollisionShape(level, bp);
        if (shape.isEmpty()) return false;
        double lx = w.x - bp.getX(), ly = w.y - bp.getY(), lz = w.z - bp.getZ();
        for (var box : shape.toAabbs()) if (box.contains(lx, ly, lz)) return true;
        return false;
    }

    /** Подписка на шаги физики Sable (до и после шага) - ведение поворотных частей подшипников измерения этой физики. */
    public static void init(java.util.function.Consumer<ServerLevel> onPhysicsStep) {
        // событие приходит от физики каждого измерения - ведём только подшипники этого
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent e) -> onPhysicsStep.accept(e.getPhysicsSystem().getLevel()));
        // и после шага: за шаг физики поворотная часть уходит от поставленной позы (шарнир, вес) - вернуть её, иначе
        // вычислитель видел бы то точную, то сдвинутую позу (скачки прицела портили обучение привода)
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent e) -> onPhysicsStep.accept(e.getPhysicsSystem().getLevel()));
    }
}
