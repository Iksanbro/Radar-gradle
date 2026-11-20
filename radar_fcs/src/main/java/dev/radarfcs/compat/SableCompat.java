package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Физические конструкции Sable / Create Aeronautics через Sable companion (MIT).
 * Companion встроен в Create Radars, поэтому доступен всегда; без Sable он возвращает пустые результаты.
 * Блоки корабля живут в «плот»-координатах: для мировых позиций нужен перевод через позу корабля.
 */
public final class SableCompat {
    private static boolean broken;

    private SableCompat() {}

    /** Корабль: id, имя, мировой центр, мировые границы, скорость (блоков/тик). */
    public record Ship(UUID id, String name, Vec3 center, AABB box, Vec3 velocity, SubLevelAccess access) {
        public double radius() {
            return Math.max(1.0, Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())) * 0.5);
        }
    }

    @Nullable
    private static SableCompanion api() {
        if (broken) return null;
        try {
            return SableCompanion.INSTANCE;
        } catch (Throwable t) {
            broken = true;
            FcsMod.LOG.warn("[radar_fcs] Sable companion unavailable: {}", t.toString());
            return null;
        }
    }

    private static void fail(Throwable t) {
        if (!broken) FcsMod.LOG.warn("[radar_fcs] Sable companion call failed, ships disabled: {}", t.toString());
        broken = true;
    }

    /**
     * Мир-модель (сцены Ponder, схематики Create - уровни из net.createmod): у Sable в нём ничего нет, а ошибка его
     * API выключила бы совместимость на всю игру - такие уровни не спрашиваем.
     */
    private static boolean virtual(Level level) {
        return level == null || level.getClass().getName().startsWith("net.createmod.");
    }

    /** Корабль, в плоте которого находится блок, или null (блок в обычном мире). */
    @Nullable
    public static SubLevelAccess containing(Level level, BlockPos pos) {
        SableCompanion api = api();
        if (api == null || virtual(level)) return null;
        try {
            return api.getContaining(level, (net.minecraft.core.Vec3i) pos);
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    @Nullable
    public static UUID containingId(Level level, BlockPos pos) {
        SubLevelAccess s = containing(level, pos);
        return s == null ? null : s.getUniqueId();
    }

    /** Корабль, на котором стоит или едет сущность. */
    @Nullable
    public static UUID trackingShipId(Entity e) {
        SableCompanion api = api();
        if (api == null) return null;
        try {
            SubLevelAccess s = api.getTrackingOrVehicleSubLevel(e);
            if (s == null) s = api.getContaining(e);
            return s == null ? null : s.getUniqueId();
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /** Мировая позиция точки (если точка в плоте корабля - через позу корабля). */
    public static Vec3 toWorld(Level level, Vec3 pos) {
        SableCompanion api = api();
        if (api == null || virtual(level)) return pos;
        try {
            return api.projectOutOfSubLevel(level, (net.minecraft.core.Position) pos);
        } catch (Throwable t) {
            fail(t);
            return pos;
        }
    }

    /**
     * Клиент: мировая точка в позе, в которой постройка Sable рисуется в этом кадре (между тиками), - чтобы вид камеры
     * на движущейся постройке не дёргался относительно неё. Точка не в постройке - как есть.
     */
    public static Vec3 toWorldRender(Level level, BlockPos pos, Vec3 local, float partialTick) {
        SableCompanion api = api();
        if (api == null || virtual(level) || !level.isClientSide) return local;
        try {
            dev.ryanhcode.sable.companion.ClientSubLevelAccess c = api.getContainingClient((net.minecraft.core.Vec3i) pos);
            return c == null ? local : c.renderPose(partialTick).transformPosition(local);
        } catch (Throwable t) {
            return toWorld(level, local);                                  // на клиенте совместимость не выключаем
        }
    }

    /** Направление из плот-координат корабля в мировые. */
    public static Vec3 dirToWorld(@Nullable SubLevelAccess ship, Vec3 dir) {
        if (ship == null) return dir;
        try {
            Quaterniondc q = ship.logicalPose().orientation();
            Vector3d v = q.transform(new Vector3d(dir.x, dir.y, dir.z));
            return new Vec3(v.x, v.y, v.z);
        } catch (Throwable t) {
            fail(t);
            return dir;
        }
    }

    /** Направление из мировых координат в плот-координаты корабля. */
    public static Vec3 dirToLocal(@Nullable SubLevelAccess ship, Vec3 dir) {
        if (ship == null) return dir;
        try {
            Quaterniondc q = ship.logicalPose().orientation();
            Vector3d v = q.transformInverse(new Vector3d(dir.x, dir.y, dir.z));
            return new Vec3(v.x, v.y, v.z);
        } catch (Throwable t) {
            fail(t);
            return dir;
        }
    }

    /** Скорость точки корабля (в плот-координатах), блоков/тик. */
    public static Vec3 pointVelocity(Level level, @Nullable SubLevelAccess ship, Vec3 localPoint) {
        SableCompanion api = api();
        if (api == null || ship == null) return Vec3.ZERO;
        try {
            Vector3d v = api.getVelocity(level, ship, new Vector3d(localPoint.x, localPoint.y, localPoint.z), new Vector3d());
            return new Vec3(v.x / 20.0, v.y / 20.0, v.z / 20.0);
        } catch (Throwable t) {
            fail(t);
            return Vec3.ZERO;
        }
    }

    /** Скорость центра масс корабля, блоков/тик (Sable отдаёт блоки/сек). */
    public static Vec3 velocity(Level level, SubLevelAccess ship) {
        try {
            Vector3dc rp = ship.logicalPose().rotationPoint();
            return pointVelocity(level, ship, new Vec3(rp.x(), rp.y(), rp.z()));
        } catch (Throwable t) {
            fail(t);
            return Vec3.ZERO;
        }
    }

    public static Ship describe(Level level, SubLevelAccess s) {
        BoundingBox3dc b = s.boundingBox();
        AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        String name = s.getName();
        if (name == null || name.isBlank()) name = "ship-" + s.getUniqueId().toString().substring(0, 4);
        // центр масс: к нему относится скорость от физики Sable (центр габарита при вращении «гуляет»).
        // Габарит Sable отстаёт от позы на тик: у быстрого корабля центр масс впереди габарита на перемещение
        // за тик, и центр габарита дал бы цель с опозданием на тик (3.5 блока на 70 м/с)
        Vec3 vel = velocity(level, s);
        Vec3 center = box.getCenter();
        try {
            Vector3dc rp = s.logicalPose().rotationPoint();
            Vec3 com = s.logicalPose().transformPosition(new Vec3(rp.x(), rp.y(), rp.z()));
            if (box.expandTowards(vel).expandTowards(vel.scale(-1)).inflate(1.0).contains(com)) center = com;
        } catch (Throwable ignored) {
        }
        return new Ship(s.getUniqueId(), name, center, box, vel, s);
    }

    /** Мировые границы корабля, в плоте которого блок, или null (блок в обычном мире). */
    @Nullable
    public static AABB worldBox(Level level, BlockPos pos) {
        SubLevelAccess s = containing(level, pos);
        if (s == null) return null;
        try {
            BoundingBox3dc b = s.boundingBox();
            return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /** Корабли, пересекающие мировую область. */
    public static List<Ship> shipsIn(Level level, AABB area) {
        List<Ship> out = new ArrayList<>();
        SableCompanion api = api();
        if (api == null || virtual(level)) return out;
        try {
            for (SubLevelAccess s : api.getAllIntersecting(level, new BoundingBox3d(area))) {
                if (s != null) out.add(describe(level, s));
            }
        } catch (Throwable t) {
            fail(t);
        }
        return out;
    }

    @Nullable
    public static Ship find(Level level, UUID id, Vec3 near, double searchRadius) {
        for (Ship s : shipsIn(level, new AABB(near, near).inflate(searchRadius))) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    /** Лучи длиннее этого не трассируем: такой луч - признак смешанных координат (мир и плот), а не взгляда. */
    public static final double MAX_RAY = 1024;

    /**
     * Блоки уровня «как есть», без надстройки Sable. Sable переписывает {@code BlockGetter.clip}: у {@code Level} он
     * добавляет к лучу постройки (точка попадания - в координатах плота!) и переводит в мир концы луча, лежащие в
     * плоте, - каждый отдельно. Луч в координатах плота, у которого в плоте только один конец, превращается в луч на
     * миллионы блоков (мир - плот), и трассировка зависает на секунды. Через эту обёртку (не {@code Level}) трассировка
     * идёт только по блокам, как в ванили.
     *
     * <p>На сервере чанк берётся только готовый ({@code getChunkNow}; плоты Sable он тоже отдаёт): незагруженные и ещё
     * загружающиеся чанки - пустота. Обычный {@code Level.getBlockState} на сервере чанк догружает или генерирует прямо
     * в тике (луч на сотни блоков в сторону от игрока - зависание сервера на секунды).
     */
    public static net.minecraft.world.level.BlockGetter plain(Level level) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) return new ReadyBlocks(sl);
        return new net.minecraft.world.level.BlockGetter() {
            @Nullable
            @Override
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                return level.getBlockEntity(pos);
            }

            @Override
            public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
                return level.getBlockState(pos);
            }

            @Override
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
                return level.getFluidState(pos);
            }

            @Override
            public int getHeight() {
                return level.getHeight();
            }

            @Override
            public int getMinBuildHeight() {
                return level.getMinBuildHeight();
            }
        };
    }

    /** Блоки серверного мира только из готовых чанков. На один луч - один экземпляр (помнит последний чанк). */
    private static final class ReadyBlocks implements net.minecraft.world.level.BlockGetter {
        private final net.minecraft.server.level.ServerLevel level;
        private boolean cached;
        private long cachedKey;
        @Nullable
        private net.minecraft.world.level.chunk.LevelChunk cachedChunk;

        ReadyBlocks(net.minecraft.server.level.ServerLevel level) {
            this.level = level;
        }

        @Nullable
        private net.minecraft.world.level.chunk.LevelChunk chunk(BlockPos pos) {
            if (level.isOutsideBuildHeight(pos)) return null;
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
            if (!cached || key != cachedKey) {
                cachedChunk = level.getChunkSource().getChunkNow(cx, cz);
                cachedKey = key;
                cached = true;
            }
            return cachedChunk;
        }

        @Nullable
        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            net.minecraft.world.level.chunk.LevelChunk c = chunk(pos);
            return c == null ? null : c.getBlockEntity(pos);
        }

        @Override
        public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            net.minecraft.world.level.chunk.LevelChunk c = chunk(pos);
            return c == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : c.getBlockState(pos);
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            net.minecraft.world.level.chunk.LevelChunk c = chunk(pos);
            return c == null ? net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState() : c.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }
    }

    /**
     * Луч from-to только по блокам самого мира (без построек Sable), точка попадания - в мировых координатах.
     * null - промах или луч негодный (длиннее {@link #MAX_RAY}, NaN).
     */
    @Nullable
    public static Vec3 clipWorld(Level level, Vec3 from, Vec3 to) {
        double len = from.distanceTo(to);
        if (!(len <= MAX_RAY)) return null;
        net.minecraft.world.phys.BlockHitResult bh = plain(level).clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return bh.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? null : bh.getLocation();
    }

    /**
     * Постройки одного орудия: те, в плотах которых стоят его части, и соседние с ними (габариты касаются, rounds
     * раз) - поворотная платформа без частей мода между подшипниками тоже попадает. exclude - основание (корабль
     * или постройка под приводом курса): его и всё, что достаётся только через него, не берём.
     */
    public static java.util.Set<UUID> cluster(Level level, java.util.Collection<BlockPos> parts, java.util.Set<UUID> exclude, int rounds) {
        java.util.Map<UUID, SubLevelAccess> found = new java.util.HashMap<>();
        SableCompanion api = api();
        if (api == null || virtual(level)) return java.util.Set.of();
        try {
            for (BlockPos p : parts) {
                SubLevelAccess s = containing(level, p);
                if (s != null && !exclude.contains(s.getUniqueId())) found.put(s.getUniqueId(), s);
            }
            java.util.List<SubLevelAccess> frontier = new ArrayList<>(found.values());
            for (int r = 0; r < rounds && !frontier.isEmpty(); r++) {
                java.util.List<SubLevelAccess> next = new ArrayList<>();
                for (SubLevelAccess s : frontier) {
                    BoundingBox3dc b = s.boundingBox();
                    AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(1.0);
                    for (SubLevelAccess o : api.getAllIntersecting(level, new BoundingBox3d(box))) {
                        if (o == null || exclude.contains(o.getUniqueId()) || found.containsKey(o.getUniqueId())) continue;
                        found.put(o.getUniqueId(), o);
                        next.add(o);
                    }
                }
                frontier = next;
            }
        } catch (Throwable t) {
            fail(t);
        }
        return found.keySet();
    }

    /** Попадание луча в постройку Sable: точка в мире, скорость этой точки (блоков/тик), id постройки. */
    public record ShipHit(Vec3 point, Vec3 velocity, UUID ship, net.minecraft.core.BlockPos block) {}

    /**
     * Луч from-to по блокам построек Sable (кораблей, поворотных частей): луч переводится в координаты каждой постройки
     * на пути (её плот) и проверяется по её блокам. Ближайшее попадание или null. ignore - своя постройка (камера на ней).
     *
     * @param blocks   откуда брать блоки плота (на сервере - только загруженные чанки). {@code Level} сюда передавать
     *                 нельзя (см. {@link #plain}) - он заменяется обёрткой
     * @param velocity считать ли скорость точки (на клиенте не нужна)
     */
    @Nullable
    public static ShipHit clipShips(Level level, Vec3 from, Vec3 to, java.util.function.Predicate<UUID> ignore, net.minecraft.world.level.BlockGetter blocks,
                                    boolean velocity) {
        SableCompanion api = api();
        if (api == null || virtual(level)) return null;
        double len = from.distanceTo(to);
        if (!(len <= MAX_RAY)) return null;                              // NaN или концы в разных системах координат
        if (blocks instanceof Level l) blocks = plain(l);                // иначе Sable «переведёт» луч плота в мир
        ShipHit best = null;
        double bestD = Double.POSITIVE_INFINITY;
        try {
            AABB area = new AABB(from, to).inflate(1);
            for (SubLevelAccess s : api.getAllIntersecting(level, new BoundingBox3d(area))) {
                if (s == null || ignore.test(s.getUniqueId())) continue;
                BoundingBox3dc b = s.boundingBox();
                AABB box = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(0.5);
                if (!box.contains(from) && box.clip(from, to).isEmpty()) continue;
                var pose = s.logicalPose();
                Vec3 lf = pose.transformPositionInverse(from), lt = pose.transformPositionInverse(to);
                if (!(lf.distanceTo(lt) <= len + 8)) continue;             // поза с масштабом или мусором - не трассируем
                net.minecraft.world.phys.BlockHitResult bh = blocks.clip(new net.minecraft.world.level.ClipContext(lf, lt,
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                        net.minecraft.world.phys.shapes.CollisionContext.empty()));
                if (bh.getType() == net.minecraft.world.phys.HitResult.Type.MISS) continue;
                Vec3 w = pose.transformPosition(bh.getLocation());
                double d = w.distanceToSqr(from);
                if (d < bestD) {
                    bestD = d;
                    Vec3 v = velocity ? pointVelocity(level, s, bh.getLocation()) : Vec3.ZERO;
                    best = new ShipHit(w, v, s.getUniqueId(), bh.getBlockPos().immutable());
                }
            }
        } catch (Throwable t) {
            // на клиенте часть API Sable может быть недоступна - без построек, совместимость не выключаем
            if (!level.isClientSide) fail(t);
            return null;
        }
        return best;
    }
}
