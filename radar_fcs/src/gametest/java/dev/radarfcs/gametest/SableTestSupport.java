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
     * Корабль Sable из куба железных блоков. Летит физически: каждый тик ему досыпается скорость до заданной
     * (компенсация гравитации), направление меняется каждые span блоков. Так Sable сообщает честную скорость.
     */
    static Object ship(GameTestHelper h, BlockPos minRel, int size, Vec3 velPerTick, double span,
                                       boolean transponder) {
        for (int x = 0; x < size; x++) for (int y = 0; y < size; y++) for (int z = 0; z < size; z++)
            h.setBlock(minRel.offset(x, y, z), Blocks.IRON_BLOCK.defaultBlockState());
        if (transponder) h.setBlock(minRel.offset(size / 2, size - 1, size / 2), FcsRegistry.IFF_TRANSPONDER.get().defaultBlockState());
        BlockPos min = h.absolutePos(minRel), max = min.offset(size - 1, size - 1, size - 1);
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(h.getLevel(), min, BlockPos.betweenClosed(min, max),
                new BoundingBox3i(min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ()));
        double period = velPerTick.length() < 1e-9 ? Double.MAX_VALUE : span / velPerTick.length();
        AtomicInteger tick = new AtomicInteger();
        h.onEachTick(() -> {
            if (sub.isRemoved()) return;
            int t = tick.getAndIncrement();
            double sign = ((int) (t / period)) % 2 == 0 ? 1 : -1;
            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null || !body.isValid()) return;
            org.joml.Vector3d cur = body.getLinearVelocity(new org.joml.Vector3d());
            org.joml.Vector3d ang = body.getAngularVelocity(new org.joml.Vector3d());
            org.joml.Vector3d want = new org.joml.Vector3d(velPerTick.x * sign, velPerTick.y * sign, velPerTick.z * sign).mul(20.0);
            body.addLinearAndAngularVelocity(want.sub(cur), ang.negate());
        });
        return sub;
    }

    /** Снаряд CBC прошёл через габарит корабля. */
    static AtomicInteger watchShipHits(GameTestHelper h, Object handle, String name) {
        ServerSubLevel sub = (ServerSubLevel) handle;
        AtomicInteger hits = new AtomicInteger();
        java.util.Map<Integer, Vec3> last = new java.util.HashMap<>();
        java.util.Set<Integer> counted = new java.util.HashSet<>();
        h.onEachTick(() -> {
            if (sub.isRemoved()) return;
            var b = sub.boundingBox();
            AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.3);
            for (AbstractCannonProjectile p : h.getLevel().getEntitiesOfClass(AbstractCannonProjectile.class, box.inflate(80))) {
                Vec3 prev = last.getOrDefault(p.getId(), p.position()), cur = p.position();
                last.put(p.getId(), cur);
                if (counted.contains(p.getId())) continue;
                if (box.contains(cur) || box.clip(prev, cur).isPresent()) {
                    counted.add(p.getId());
                    hits.incrementAndGet();
                    FcsMod.LOG.info("[radar_fcs test] {}: projectile #{} hit ship box {}", name, p.getId(), box);
                }
            }
        });
        return hits;
    }
}
