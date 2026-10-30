package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsDev;
import dev.radarfcs.compat.ContraptionCompat;
import dev.radarfcs.compat.Munitions;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.IffRule;
import dev.radarfcs.core.ProjectileType;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.TrackTable;
import dev.radarfcs.core.Vec;
import dev.radarfcs.iff.IffRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

/**
 * Сенсор вычислителя и монитора. Радары Create Radars на линках обнаруживают цели, сенсор сопровождает их
 * по живой сущности (точная позиция каждые scanInterval тиков), а корабли Sable - по их физике.
 */
public final class TargetSensor {

    /**
     * Кто «свои» для этого вычислителя: владелец и его команда, правило кодов транспондеров, корабли-носители батареи.
     * @param guard     что защищать от снарядов (вычислитель и его пушки): угроза - прогноз снаряда проходит рядом
     * @param self      позиция блока вычислителя (его снаряды - свои) или null (монитор)
     * @param intercept можно ли сбивать снаряды (установлен CBC Interception); монитор показывает их всегда
     */
    public record Context(@Nullable UUID owner, String ownerTeam, IffRule iff, Set<UUID> platformShips, Vec origin,
                          List<Vec> guard, @Nullable net.minecraft.core.BlockPos self, boolean intercept) {
        public Context(@Nullable UUID owner, String ownerTeam, IffRule iff, Set<UUID> platformShips, Vec origin) {
            this(owner, ownerTeam, iff, platformShips, origin, List.of(origin), null, true);
        }
    }

    private final TrackTable table = new TrackTable();
    /** Треки, у которых раньше была живая сущность: если она пропала - цель уничтожена или ушла. */
    private final Set<String> liveSeen = new HashSet<>();
    /**
     * Цели радара, отсеянные сенсором (мелкие снаряды, служебные сущности): когда их сущность пропадёт, радар ещё
     * какое-то время отдаёт старую отметку - она не должна стать «призрачной» целью по данным радара.
     */
    private Set<String> filtered = new HashSet<>(), filteredNow = new HashSet<>();
    private List<Track> active = List.of();
    private int contacts;
    private boolean usedRadar;

    public List<Track> active() { return active; }

    public Collection<Track> all() { return table.all(); }

    @Nullable
    public Track get(String id) { return table.get(id); }

    public int contacts() { return contacts; }

    public boolean usedRadar() { return usedRadar; }

    public void clear() {
        table.clear();
        liveSeen.clear();
        active = List.of();
        contacts = 0;
    }

    public void sense(ServerLevel level, List<BlockEntity> radars, Context ctx, long now) {
        sense(level, radars, ctx, now, new EngageSettings(), t -> false, null);
    }

    /**
     * @param radars   радары Create Radars на линках
     * @param settings настройки вычислителя: типы целей, окно скорости, стоящие/движущиеся
     * @param dropped  цели, сброшенные вычислителем (после N выстрелов)
     * @param forcedId цель, назначенная вручную (монитор): сопровождается напрямую, даже если радары её не видят, и
     *                 берётся в обход типов, списков, скорости и движения (но не своих и не запретов сервера)
     */
    public void sense(ServerLevel level, List<BlockEntity> radars, Context ctx, long now,
                      EngageSettings settings, java.util.function.Predicate<Track> dropped, @Nullable String forcedId) {
        TrackTable.Settings s = new TrackTable.Settings(FcsConfig.TRACK_WINDOW.get(), 0.12);
        Set<String> seen = new HashSet<>();
        contacts = 0;
        usedRadar = false;
        filteredNow = new HashSet<>();
        if (FcsDev.radarless) {
            directScan(level, ctx, FcsDev.radarlessRange, now, s);
        } else if (RadarLink.apiAvailable() && !radars.isEmpty()) {
            usedRadar = true;
            for (BlockEntity r : radars) {
                List<RadarLink.Contact> list = RadarLink.contacts(r);
                if (list == null) continue;
                for (RadarLink.Contact c : list) {
                    if (!seen.add(c.id())) continue;
                    contacts++;
                    observeContact(level, c, ctx, now, s);
                }
            }
        }
        filtered = filteredNow;
        if (forcedId != null && !seen.contains(forcedId)) trackDirectly(level, forcedId, now, s);
        table.prune(now, FcsConfig.TRACK_TIMEOUT.get());
        liveSeen.removeIf(id -> table.get(id) == null);
        assessThreats(level, ctx);
        table.classify(FcsConfig.MIN_SPEED.get() / 20.0, FcsConfig.MIN_SAMPLES.get(),
                t -> isFriendly(level, t, ctx, now),
                t -> wanted(t, ctx, settings, dropped, forcedId),
                ctx.origin(), FcsConfig.MIN_RANGE.get(), FcsConfig.MAX_RANGE.get(),
                t -> t.id.equals(forcedId) || settings.motionAllowed(t));
        active = TrackTable.sortedByDistance(table.active(), ctx.origin());
    }

    /**
     * Нужна ли цель вычислителю: разрешена сервером; назначенная вручную - всегда; чёрный список - никогда;
     * белый список - в обход выбора типов и «только враждебных»; остальное - по типам целей и окну скорости.
     */
    private boolean wanted(Track t, Context ctx, EngageSettings settings, java.util.function.Predicate<Track> dropped,
                           @Nullable String forcedId) {
        if (!isEligible(t, ctx)) return false;
        // снаряды сбиваются только с CBC Interception (без него снаряды пролетают друг сквозь друга)
        if (t.kind == Track.Kind.PROJECTILE && !ctx.intercept()) return false;
        if (t.id.equals(forcedId)) return true;
        if (TargetLists.matches(settings.blacklist, t) || dropped.test(t)) return false;
        if (t.kind == Track.Kind.PROJECTILE && !threatens(t, ctx, settings)) return false;
        if (!settings.speedAllowed(EngageSettings.Category.of(t), t.speedPerTick() * 20)) return false;
        if (TargetLists.matches(settings.whitelist, t)) return true;
        if (!settings.enabled(EngageSettings.Category.of(t))) return false;
        return t.kind != Track.Kind.MOB || !settings.hostileOnly || t.hostile;
    }

    /** Снаряд-цель по фильтру угрозы: угрожающий своим, приближающийся или любой чужой. */
    static boolean threatens(Track t, Context ctx, EngageSettings settings) {
        return switch (settings.threat) {
            case ALL -> true;
            case APPROACHING -> t.vel.dot(ctx.origin().sub(t.pos)) > 0;
            case THREATENING -> t.threatDistance <= settings.threatRadius;
        };
    }

    // ------------------------------------------------------------- угроза

    /**
     * Насколько близко прогноз полёта каждого снаряда-цели проходит от своих: вычислителя и его пушек (guard)
     * и своих зон транспондеров (по правилу вычислителя), и через сколько тиков.
     */
    private void assessThreats(ServerLevel level, Context ctx) {
        List<IffRegistry.Zone> zones = null;
        for (Track t : table.all()) {
            if (t.kind != Track.Kind.PROJECTILE || t.path == null) continue;
            if (zones == null) zones = IffRegistry.zones(level, ctx.iff());
            Vec[] pts = t.path.points();
            double best = Double.POSITIVE_INFINITY, bestT = Double.POSITIVE_INFINITY;
            for (int k = 0; k + 1 < pts.length; k++) {
                Vec a = pts[k], b = pts[k + 1];
                for (Vec g : ctx.guard()) {
                    double u = segmentParam(g, a, b);
                    double d = a.lerp(b, u).distance(g);
                    if (d < best) { best = d; bestT = k + u; }
                }
                for (IffRegistry.Zone z : zones) {
                    double d = zoneDistance(z, a, b);
                    if (d < best) { best = d; bestT = k + 0.5; }
                }
                if (best <= 0) break;                       // уже в зоне: раньше не бывает
            }
            if (pts.length == 1) {
                for (Vec g : ctx.guard()) best = Math.min(best, pts[0].distance(g));
                bestT = 0;
            }
            t.threatDistance = Math.max(0, best);
            t.threatTicks = bestT;
        }
    }

    /** Параметр (0..1) ближайшей к точке p точки отрезка a-b. */
    private static double segmentParam(Vec p, Vec a, Vec b) {
        Vec ab = b.sub(a);
        double l2 = ab.lengthSqr();
        return l2 < 1e-12 ? 0 : Math.max(0, Math.min(1, p.sub(a).dot(ab) / l2));
    }

    /** Расстояние от отрезка до зоны (шар - точно, прямоугольник - по точкам отрезка), 0 - внутри. */
    private static double zoneDistance(IffRegistry.Zone z, Vec a, Vec b) {
        if (z.box() == null) {
            double u = segmentParam(z.center(), a, b);
            return a.lerp(b, u).distance(z.center()) - z.radius();
        }
        AABB box = z.box();
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 4; i++) {
            Vec p = a.lerp(b, i / 4.0);
            double dx = Math.max(0, Math.max(box.minX - p.x(), p.x() - box.maxX));
            double dy = Math.max(0, Math.max(box.minY - p.y(), p.y() - box.maxY));
            double dz = Math.max(0, Math.max(box.minZ - p.z(), p.z() - box.maxZ));
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    /** Назначенная вручную цель, которую радары не видят: сопровождать по живой сущности. */
    private void trackDirectly(ServerLevel level, String id, long now, TrackTable.Settings s) {
        UUID u = uuid(id);
        Entity e = u == null ? null : level.getEntity(u);
        if (e == null || !isCandidateEntity(e)) return;
        observeEntity(e, id, now, s);
        liveSeen.add(id);
    }

    // ------------------------------------------------------ радар Create Radars

    private void observeContact(ServerLevel level, RadarLink.Contact c, Context ctx, long now, TrackTable.Settings s) {
        String cat = c.category();
        switch (cat) {
            case "ITEM" -> { return; }
            case "SABLE" -> {
                UUID id = uuid(c.id());
                SableCompat.Ship ship = id == null ? null : SableCompat.find(level, id, c.position(), 64);
                if (ship != null) observeShip(level, ship, now, s);
                else observeRaw(c, Track.Kind.SHIP, false, s);
                return;
            }
            default -> {
            }
        }
        UUID id = uuid(c.id());
        Entity e = id == null ? null : level.getEntity(id);
        if (e != null) {
            if (!e.isAlive()) {
                table.remove(c.id());
                liveSeen.remove(c.id());
                return;
            }
            if (!isCandidateEntity(e)) {
                filteredNow.add(c.id());
                table.remove(c.id());
                return;
            }
            observeEntity(e, c.id(), now, s);
            liveSeen.add(c.id());
            return;
        }
        if (liveSeen.remove(c.id())) {          // сущность была и исчезла: уничтожена или выгружена
            table.remove(c.id());
            return;
        }
        if (filtered.contains(c.id())) {        // отсеянная цель исчезла, а радар ещё помнит её отметку
            filteredNow.add(c.id());
            return;
        }
        // снаряд без живой сущности (уже сбит или упал; ложная отметка) - не цель: ни прогноза, ни свой-чужой
        if ("PROJECTILE".equals(cat) || "MISSILE".equals(cat)) {
            table.remove(c.id());
            return;
        }
        Track.Kind kind = switch (cat) {
            case "PLAYER" -> Track.Kind.PLAYER;
            case "CONTRAPTION" -> Track.Kind.CONTRAPTION;
            case "PROJECTILE", "MISSILE" -> Track.Kind.PROJECTILE;
            case "MISC" -> Track.Kind.EXTERNAL;
            default -> Track.Kind.MOB;
        };
        observeRaw(c, kind, "HOSTILE".equals(cat), s);
    }

    /** Цель без живой сущности (например, ложная цель от глушилки): по данным радара. */
    private void observeRaw(RadarLink.Contact c, Track.Kind kind, boolean hostile, TrackTable.Settings s) {
        Vec p = new Vec(c.position().x, c.position().y + 0.5, c.position().z);
        String name = c.entityType();
        int colon = name.lastIndexOf('.');
        if (colon >= 0) name = name.substring(colon + 1);
        Track t = table.observe(c.id(), kind, name, p, 1.0, c.scannedTime(), false, null, s);
        t.hostile = hostile;
        t.typeId = typeIdOf(c.entityType());
        t.handle = null;
        t.volume = 1;
    }

    // ---------------------------------------------------------- свой сенсор

    private void directScan(ServerLevel level, Context ctx, double range, long now, TrackTable.Settings s) {
        Vec o = ctx.origin();
        AABB box = new AABB(o.x() - range, o.y() - range, o.z() - range, o.x() + range, o.y() + range, o.z() + range);
        double r2 = range * range;
        for (Entity e : level.getEntities((Entity) null, box, TargetSensor::isCandidateEntity)) {
            Vec3 c = e.getBoundingBox().getCenter();
            if (c.distanceToSqr(o.x(), o.y(), o.z()) > r2) continue;
            String id = e.getUUID().toString();
            observeEntity(e, id, now, s);
            liveSeen.add(id);
        }
        // сущности, которые пропали из области
        List<String> gone = new ArrayList<>();
        for (Track t : table.all()) {
            if (t.handle instanceof Entity e && (!e.isAlive() || e.isRemoved())) gone.add(t.id);
        }
        gone.forEach(table::remove);
        for (SableCompat.Ship ship : SableCompat.shipsIn(level, box)) observeShip(level, ship, now, s);
    }

    /** Может ли сущность быть целью вообще: живые, игроки, контрапшены, снаряды, транспорт и прочее, кроме служебных. */
    public static boolean isCandidateEntity(Entity e) {
        if (!e.isAlive() || e.isSpectator() || e.isRemoved()) return false;
        if (e instanceof Player) return true;
        if (ContraptionCompat.isContraption(e)) return !ContraptionCompat.isIgnored(e);
        // снаряды: только крупные (снаряды больших пушек и ракеты) и только летящие; мелких бывает очень много
        if (e instanceof Projectile || Munitions.classify(e) != null) {
            return Munitions.classify(e) != null && Munitions.flying(e) && !isIgnoredEntity(e);
        }
        if (e instanceof net.minecraft.world.entity.item.ItemEntity || e instanceof net.minecraft.world.entity.ExperienceOrb
                || e instanceof net.minecraft.world.entity.decoration.HangingEntity || e instanceof net.minecraft.world.entity.Marker
                || e instanceof net.minecraft.world.entity.Display || e instanceof net.minecraft.world.entity.Interaction
                || e instanceof net.minecraft.world.entity.AreaEffectCloud || e instanceof net.minecraft.world.entity.LightningBolt
                || e instanceof net.minecraft.world.entity.projectile.EvokerFangs || e instanceof net.minecraft.world.entity.projectile.FishingHook) {
            return false;
        }
        return !isIgnoredEntity(e);
    }

    /** "entity.minecraft.zombie" (имя типа от Create Radars) -> "minecraft:zombie". */
    @Nullable
    private static String typeIdOf(String entityType) {
        if (entityType == null || entityType.isEmpty()) return null;
        String s = entityType.startsWith("entity.") ? entityType.substring(7) : entityType;
        int dot = s.indexOf('.');
        return dot > 0 && s.indexOf(':') < 0 ? s.substring(0, dot) + ":" + s.substring(dot + 1) : s;
    }

    /** Сущность из списка игнорируемых (конфиг) или служебный контрапшен. */
    public static boolean isIgnoredEntity(Entity e) {
        if (ContraptionCompat.isContraption(e)) return ContraptionCompat.isIgnored(e);
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        return FcsConfig.IGNORED_ENTITIES.get().contains(id);
    }

    public static Track.Kind kindOf(Entity e) {
        if (e instanceof Player) return Track.Kind.PLAYER;
        if (ContraptionCompat.isContraption(e)) return Track.Kind.CONTRAPTION;
        if (e instanceof Projectile || Munitions.classify(e) != null) return Track.Kind.PROJECTILE;
        if (e instanceof net.minecraft.world.entity.Mob) return Track.Kind.MOB;
        return Track.Kind.EXTERNAL;
    }

    // ------------------------------------------------------------- замеры

    private void observeEntity(Entity e, String id, long now, TrackTable.Settings s) {
        AABB bb = e.getBoundingBox();
        Vec3 c = bb.getCenter();
        Track.Kind kind = kindOf(e);
        if (kind == Track.Kind.PROJECTILE && e.level() instanceof ServerLevel level) {
            observeProjectile(level, e, id, now, s);
            return;
        }
        double hr = kind == Track.Kind.CONTRAPTION
                ? Math.min(6.0, Math.max(1.0, Math.min(bb.getXsize(), bb.getZsize()) * 0.5))
                : Math.max(0.35, Math.min(bb.getXsize(), bb.getYsize()) * 0.5);
        String named = kind == Track.Kind.CONTRAPTION ? ContraptionCompat.name(e, now) : null;   // имя с транспондера
        Track t = table.observe(id, kind, named != null ? named : e.getName().getString(), new Vec(c.x, c.y, c.z), hr, now,
                isGrounded(e), null, s);
        t.tagName = named;
        t.handle = e;
        t.hostile = e instanceof Enemy;
        t.typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        t.playerName = e instanceof Player p ? p.getGameProfile().getName() : null;
        // габарит сущности - точный прямоугольник по осям, как его проверяет CBC; у контрапшена он лишь описанный
        t.half = kind == Track.Kind.CONTRAPTION ? null : new Vec(bb.getXsize() * 0.5, bb.getYsize() * 0.5, bb.getZsize() * 0.5);
        t.volume = bb.getXsize() * bb.getYsize() * bb.getZsize();
    }

    /**
     * Снаряд-цель: положение - точка сущности (по ней CBC Interception проверяет встречу снарядов), скорость точная,
     * прогноз полёта по тикам; радиус - половина размера снаряда (как его считает CBC Interception).
     */
    private void observeProjectile(ServerLevel level, Entity e, String id, long now, TrackTable.Settings s) {
        ProjectileType type = Munitions.classify(e);
        if (type == null) type = ProjectileType.SHELL;
        Vec3 v = e.getDeltaMovement();
        Track t = table.observe(id, Track.Kind.PROJECTILE, e.getName().getString(), new Vec(e.getX(), e.getY(), e.getZ()),
                Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5, now, false, new Vec(v.x, v.y, v.z), s);
        t.handle = e;
        t.hostile = true;
        t.munition = type;
        t.path = ProjectileTracking.path(level, e, type);
        t.typeId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        t.playerName = null;
        t.half = null;
        t.volume = e.getBbWidth() * e.getBbWidth() * e.getBbHeight();
    }

    private void observeShip(ServerLevel level, SableCompat.Ship ship, long now, TrackTable.Settings s) {
        Vec3 c = ship.center();
        Vec3 v = ship.velocity();
        String named = IffRegistry.shipName(level, ship.id());                // имя корабля с его транспондера
        Track t = table.observe(ship.id().toString(), Track.Kind.SHIP, named != null ? named : ship.name(), new Vec(c.x, c.y, c.z),
                ship.radius(), now, false, new Vec(v.x, v.y, v.z), s);
        t.handle = ship;
        t.hostile = true;
        t.tagName = named;
        t.volume = ship.box().getXsize() * ship.box().getYsize() * ship.box().getZsize();
    }

    /** Наземная цель: вертикаль не экстраполируется (прыжки мобов - шум). */
    private static boolean isGrounded(Entity e) {
        if (e.isNoGravity() || e.isInWater() || e.isInLava()) return false;
        if (e instanceof FlyingMob || e instanceof FlyingAnimal || e instanceof Bat || e instanceof Blaze
                || e instanceof Vex || e instanceof WitherBoss || e instanceof EnderDragon) return false;
        if (e instanceof Player p) return !p.isFallFlying() && !p.getAbilities().flying;
        if (e instanceof Projectile || ContraptionCompat.isContraption(e)) return false;
        return e instanceof LivingEntity;
    }

    // ----------------------------------------------------------- свой-чужой

    private boolean isFriendly(ServerLevel level, Track t, Context ctx, long now) {
        return isFriendly(level, t, ctx, now, true);
    }

    /**
     * Свой ли объект для вычислителя (или монитора) с этим контекстом.
     * @param handle сущность, корабль Sable ({@link SableCompat.Ship}) или null
     */
    public static boolean isFriendly(ServerLevel level, Track t, Context ctx, long now, boolean zones) {
        Object handle = t.handle;
        Track.Kind kind = t.kind;
        String id = t.id;
        IffRule iff = ctx.iff();
        if (handle instanceof SableCompat.Ship ship) {
            UUID sid = ship.id();
            if (ctx.platformShips().contains(sid)) return true;
            if (IffRegistry.shipFriendly(level, sid, iff)) return true;
            if (RadarLink.shipHasFriendlyRadarId(sid, FcsConfig.FRIENDLY_RADAR_IDS.get())) return true;
        } else if (kind == Track.Kind.SHIP) {
            UUID sid = uuid(id);
            if (sid != null && (ctx.platformShips().contains(sid) || IffRegistry.shipFriendly(level, sid, iff))) return true;
            if (sid != null && RadarLink.shipHasFriendlyRadarId(sid, FcsConfig.FRIENDLY_RADAR_IDS.get())) return true;
        }
        if (kind == Track.Kind.PROJECTILE && handle instanceof Entity e) {
            Boolean own = projectileFriendly(level, e, ctx);
            if (own != null) return own;
        }
        if (handle instanceof Entity e) {
            if (e instanceof Player p && isFriendlyPlayer(p, ctx)) return true;
            // транспорт (лодка, вагонетка, контрапшен), в котором сидит свой игрок, - свой
            for (Entity pass : e.getPassengers()) if (pass instanceof Player pp && isFriendlyPlayer(pp, ctx)) return true;
            if (e instanceof OwnableEntity own && own.getOwnerUUID() != null) {
                if (own.getOwnerUUID().equals(ctx.owner())) return true;
                if (own.getOwner() instanceof Player op && isFriendlyPlayer(op, ctx)) return true;
            }
            if (ContraptionCompat.isContraption(e) && ContraptionCompat.hasTransponder(e, iff, now)) return true;
            Entity vehicle = e.getVehicle();
            if (vehicle != null && ContraptionCompat.isContraption(vehicle) && ContraptionCompat.hasTransponder(vehicle, iff, now))
                return true;
            UUID onShip = SableCompat.trackingShipId(e);
            if (onShip != null && (ctx.platformShips().contains(onShip) || IffRegistry.shipFriendly(level, onShip, iff)))
                return true;
        } else if (kind == Track.Kind.PLAYER && !FcsConfig.TARGET_PLAYERS.get()) {
            return true;
        }
        // зона своего транспондера (корабль, радиус, корабль + расстояние) бережёт тех, кто наследует её защиту
        // (типы и списки наследования транспондера)
        return zones && IffRegistry.protects(level, t, iff);
    }

    /**
     * Снаряд свой по тому, откуда и кем выпущен: пушкой этого вычислителя, вычислителя того же владельца или его
     * команды, своим игроком, из своей зоны транспондера. Где он сейчас - не важно: чужой снаряд в своей зоне
     * остаётся целью. null - точка запуска неизвестна (снаряд появился раньше, чем его увидели): по общим правилам.
     */
    @Nullable
    private static Boolean projectileFriendly(ServerLevel level, Entity e, Context ctx) {
        if (e instanceof Projectile pr && pr.getOwner() instanceof Player p && isFriendlyPlayer(p, ctx)) return true;
        ProjectileTracking.Origin o = ProjectileTracking.origin(e);
        if (o == null) return null;
        if (o.fcs() != null && o.fcs().equals(ctx.self())) return true;
        if (o.fcsOwner() != null && o.fcsOwner().equals(ctx.owner())) return true;
        if (!o.fcsTeam().isEmpty() && o.fcsTeam().equals(ctx.ownerTeam())) return true;
        Vec from = new Vec(o.pos().x, o.pos().y, o.pos().z);
        for (IffRegistry.Zone z : IffRegistry.zones(level, ctx.iff())) if (z.contains(from)) return true;
        return false;
    }

    private static boolean isFriendlyPlayer(Player p, Context ctx) {
        if (!FcsConfig.TARGET_PLAYERS.get() || p.isCreative() || p.isSpectator()) return true;
        if (p.getUUID().equals(ctx.owner())) return true;
        if (FcsConfig.FRIENDLY_PLAYERS.get().contains(p.getGameProfile().getName())) return true;
        Team team = p.getTeam();
        return !ctx.ownerTeam().isEmpty() && team != null && team.getName().equals(ctx.ownerTeam());
    }

    private boolean isEligible(Track t, Context ctx) {
        return isEligible(t.kind, t.hostile, t.pos, t.vel, ctx.origin());
    }

    /** Подходит ли объект как цель по настройкам (без проверки свой-чужой и скорости). */
    public static boolean isEligible(Track.Kind kind, boolean hostile, Vec pos, Vec vel, Vec origin) {
        return switch (kind) {
            case MOB -> FcsConfig.TARGET_MOBS.get();
            case PLAYER -> FcsConfig.TARGET_PLAYERS.get();
            case SHIP -> FcsConfig.TARGET_SHIPS.get();
            case CONTRAPTION -> FcsConfig.TARGET_CONTRAPTIONS.get();
            // снаряды и ракеты: что из них брать - решает фильтр угрозы вычислителя
            case PROJECTILE -> FcsConfig.TARGET_PROJECTILES.get();
            case EXTERNAL -> FcsConfig.TARGET_OTHER.get();
        };
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
