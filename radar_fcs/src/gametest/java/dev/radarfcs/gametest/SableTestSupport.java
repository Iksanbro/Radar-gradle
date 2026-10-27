package dev.radarfcs.gametest;

import dev.radarfcs.FcsMod;
import dev.radarfcs.registry.FcsRegistry;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Корабли Sable для игровых тестов. Отдельный класс: сигнатуры FcsGameTests не ссылаются на Sable,
 * поэтому тесты загружаются и без него (тесты кораблей тогда пропускаются).
 */
final class SableTestSupport {
    private SableTestSupport() {}


    /**
     * Корабль Sable из куба железных блоков, летящий туда-обратно по прямой со скоростью velPerTick
     * (span блоков в одну сторону). В игровом тесте физика Sable корабль по горизонтали не смещает,
     * поэтому траектория задаётся телепортом каждый тик, а скорость тела выставляется той же -
     * так Sable сообщает честную скорость, а корабль действительно летит.
     */
    static Object ship(GameTestHelper h, BlockPos minRel, int size, Vec3 velPerTick, double span,
                       boolean transponder) {
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++)
            h.setBlock(minRel.offset(x, y, z), Blocks.IRON_BLOCK.defaultBlockState());
        if (transponder) h.setBlock(minRel.offset(size / 2, size - 1, size / 2), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        BlockPos min = h.absolutePos(minRel), max = min.offset(size - 1, size - 1, size - 1);
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(h.getLevel(), min, BlockPos.betweenClosed(min, max),
                new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        double speed = velPerTick.length();
        int leg = speed < 1e-9 ? Integer.MAX_VALUE : Math.max(1, (int) Math.round(span / speed));
        org.joml.Vector3d p0 = new org.joml.Vector3d(sub.logicalPose().position());
        org.joml.Quaterniond q0 = new org.joml.Quaterniond(sub.logicalPose().orientation());
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (sub.isRemoved()) return;
            int t = tick.getAndIncrement();
            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null || !body.isValid()) return;
            int ph = leg == Integer.MAX_VALUE ? t : t % (2 * leg);
            double k = ph < leg ? ph : 2.0 * leg - ph;
            double sign = ph < leg ? 1 : -1;
            org.joml.Vector3d pos = new org.joml.Vector3d(p0).add(velPerTick.x * k, velPerTick.y * k, velPerTick.z * k);
            body.teleport(pos, q0);
            org.joml.Vector3d cur = body.getLinearVelocity(new org.joml.Vector3d());
            org.joml.Vector3d ang = body.getAngularVelocity(new org.joml.Vector3d());
            org.joml.Vector3d want = new org.joml.Vector3d(velPerTick.x * sign, velPerTick.y * sign, velPerTick.z * sign).mul(20.0);
            body.addLinearAndAngularVelocity(want.sub(cur), ang.negate());
            if (Boolean.getBoolean("radar_fcs.shipdebug") || t % 10 == 0) {
                var b = sub.boundingBox();
                var pp = sub.logicalPose().position();
                FcsMod.LOG.info("[radar_fcs test] ship t={} box=({} {} {}) pose=({} {} {}) vel/tick=({} {} {})", t,
                        f((b.minX() + b.maxX()) / 2), f((b.minY() + b.maxY()) / 2), f((b.minZ() + b.maxZ()) / 2),
                        f(pp.x()), f(pp.y()), f(pp.z()), f(cur.x / 20), f(cur.y / 20), f(cur.z / 20));
            }
        });
        return sub;
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * Снаряд CBC прошёл через корабль. Габарит берётся вокруг текущей позы (boundingBox Sable отстаёт на тик),
     * учитывается и прошлый габарит - попадание засчитывается, если отрезок полёта за тик задел любой из них.
     */
    static AtomicInteger watchShipHits(GameTestHelper h, Object handle, String name) {
        ServerSubLevel sub = (ServerSubLevel) handle;
        AtomicInteger hits = new AtomicInteger();
        java.util.Map<Integer, Vec3> last = new java.util.HashMap<>();
        java.util.Set<Integer> counted = new java.util.HashSet<>();
        AABB[] prevBox = {null};
        h.onEachTick(() -> {
            if (sub.isRemoved()) return;
            var b = sub.boundingBox();
            var pp = sub.logicalPose().position();
            double hx = (b.maxX() - b.minX()) / 2, hy = (b.maxY() - b.minY()) / 2, hz = (b.maxZ() - b.minZ()) / 2;
            AABB box = new AABB(pp.x() - hx, pp.y() - hy, pp.z() - hz, pp.x() + hx, pp.y() + hy, pp.z() + hz).inflate(0.3);
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, box.inflate(80))) {
                Vec3 prev = last.getOrDefault(p.getId(), p.position()), cur = p.position();
                last.put(p.getId(), cur);
                if (counted.contains(p.getId())) continue;
                boolean hit = box.contains(cur) || box.clip(prev, cur).isPresent();
                if (!hit && prevBox[0] != null) hit = prevBox[0].contains(cur) || prevBox[0].clip(prev, cur).isPresent();
                if (hit) {
                    counted.add(p.getId());
                    hits.incrementAndGet();
                    FcsMod.LOG.info("[radar_fcs test] {}: projectile #{} hit ship box {}", name, p.getId(), box);
                } else if (prev.distanceTo(cur) > 0.1) {
                    double d = Math.sqrt(box.distanceToSqr(cur));
                    if (d < 8) FcsMod.LOG.info("[radar_fcs test] {}: projectile #{} near ship, gap {}", name, p.getId(),
                            String.format(java.util.Locale.ROOT, "%.2f", d));
                }
            }
            prevBox[0] = box;
        });
        return hits;
    }
}
