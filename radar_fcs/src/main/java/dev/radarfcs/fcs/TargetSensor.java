package dev.radarfcs.fcs;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.compat.ContraptionCompat;
import dev.radarfcs.compat.RadarLink;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.core.EngageSettings;
import dev.radarfcs.core.Track;
import dev.radarfcs.core.TrackTable;
import dev.radarfcs.core.Vec;
import dev.radarfcs.iff.IffRegistry;
import dev.radarfcs.radar.RadarContact;
import dev.radarfcs.radar.RadarEmitter;
import dev.radarfcs.radar.RadarScanner;
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
 * Сенсор вычислителя. Радары (свои радары мода по каналу и Create Radars рядом) обнаруживают цели,
 * вычислитель сопровождает их по живой сущности (точная позиция каждые scanInterval тиков),
 * а корабли Sable - по их физике. Без радаров (или в режиме DIRECT) используется собственный сенсор.
 */
public final class TargetSensor {

    /** Кто «свои» для этого вычислителя. */
    public record Context(@Nullable UUID owner, String ownerTeam, int channel, Set<UUID> platformShips, Vec origin) {}

    private final TrackTable table = new TrackTable();
    /** Треки, у которых раньше была живая сущность: если она пропала - цель уничтожена или ушла. */
    private final Set<String> liveSeen = new HashSet<>();
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

    public void sense(ServerLevel level, List<BlockEntity> radars, List<RadarEmitter> own, Context ctx, long now) {
        sense(level, radars, own, ctx, now, new EngageSettings(), t -> false);
    }

    /**
     * @param settings настройки вычислителя: типы целей, окно скорости, стоящие/движущиеся
     * @param dropped  цели, сброшенные вычислителем (после N выстрелов)
     */
    public void sense(ServerLevel level, List<BlockEntity> radars, List<RadarEmitter> own, Context ctx, long now,
                      EngageSettings settings, java.util.function.Predicate<Track> dropped) {
        TrackTable.Settings s = new TrackTable.Settings(FcsConfig.TRACK_WINDOW.get(), 0.12);
        FcsConfig.SensorMode mode = FcsConfig.SENSOR_MODE.get();
        Set<String> seen = new HashSet<>();
        contacts = 0;
        usedRadar = false;
        double radarRange = 0;
        if (mode != FcsConfig.SensorMode.DIRECT && !own.isEmpty()) {
            usedRadar = true;
            for (RadarEmitter r : own) {
                radarRange = Math.max(radarRange, r.radarRange());
                for (RadarContact c : r.radarContacts().all()) {
                    String id = c.id.toString();
                    if (!seen.add(id)) continue;
                    contacts++;
                    observeOwnContact(level, c, id, now, s);
                }
            }
        }
        if (mode != FcsConfig.SensorMode.DIRECT && RadarLink.apiAvailable() && !radars.isEmpty()) {
            boolean ok = true;
            for (BlockEntity r : radars) {
                List<RadarLink.Contact> list = RadarLink.contacts(r);
                if (list == null) {
                    ok = false;
                    break;
                }
                radarRange = Math.max(radarRange, RadarLink.range(r));
                for (RadarLink.Contact c : list) {
                    if (!seen.add(c.id())) continue;
                    contacts++;
                    observeContact(level, c, ctx, now, s);
                }
            }
            usedRadar |= ok;
        }
        if (!usedRadar && mode != FcsConfig.SensorMode.RADAR) {
            double range = radarRange > 0 ? radarRange : FcsConfig.SENSOR_RANGE.get();
            directScan(level, ctx, range, now, s);
        }
        table.prune(now, FcsConfig.TRACK_TIMEOUT.get());
        liveSeen.removeIf(id -> table.get(id) == null);
        table.classify(FcsConfig.MIN_SPEED.get() / 20.0, FcsConfig.MIN_SAMPLES.get(),
                t -> isFriendly(level, t, ctx, now),
                t -> isEligible(t, ctx) && settings.enabled(EngageSettings.Category.of(t.kind))
                        && settings.speedAllowed(t.speedPerTick() * 20) && !dropped.test(t),
                ctx.origin(), FcsConfig.MIN_RANGE.get(), FcsConfig.MAX_RANGE.get(), t -> settings.motionAllowed(t.moving));
        active = TrackTable.sortedByDistance(table.active(), ctx.origin());
    }

    // ------------------------------------------------------------ свои радары

    private void observeOwnContact(ServerLevel level, RadarContact c, String id, long now, TrackTable.Settings s) {
        if (c.isShip()) {
            SableCompat.Ship ship = SableCompat.find(level, c.id, c.pos, 64);
            if (ship != null) {
                observeShip(level, ship, now, s);
            } else {
                Track t = table.observe(id, Track.Kind.SHIP, c.name, new Vec(c.pos.x, c.pos.y, c.pos.z), c.size, c.lastSeen,
                        false, new Vec(c.vel.x, c.vel.y, c.vel.z), s);
                t.hostile = true;
                t.handle = null;
                t.volume = 8 * c.size * c.size * c.size;
            }
            return;
        }
        Entity e = c.entity != null && !c.entity.isRemoved() ? c.entity : level.getEntity(c.id);
        if (e != null && e.isAlive()) {
            if (RadarScanner.isIgnoredEntity(e)) return;
            observeEntity(e, id, now, s);
            liveSeen.add(id);
            return;
        }
        // сущность была и исчезла: уничтожена или выгружена
        table.remove(id);
        liveSeen.remove(id);
    }

    // ------------------------------------------------------ радар Create Radars

    private void observeContact(ServerLevel level, RadarLink.Contact c, Context ctx, long now, TrackTable.Settings s) {
        String cat = c.category();
        switch (cat) {
            case "ITEM", "MISC" -> { return; }
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
            if (RadarScanner.isIgnoredEntity(e)) return;
            observeEntity(e, c.id(), now, s);
            liveSeen.add(c.id());
            return;
        }
        if (liveSeen.remove(c.id())) {          // сущность была и исчезла: уничтожена или выгружена
            table.remove(c.id());
            return;
        }
        Track.Kind kind = switch (cat) {
            case "PLAYER" -> Track.Kind.PLAYER;
            case "CONTRAPTION" -> Track.Kind.CONTRAPTION;
            case "PROJECTILE", "MISSILE" -> Track.Kind.PROJECTILE;
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

    private static boolean isCandidateEntity(Entity e) {
        return RadarScanner.detectable(e);
    }

    // ------------------------------------------------------------- замеры

    private void observeEntity(Entity e, String id, long now, TrackTable.Settings s) {
        AABB bb = e.getBoundingBox();
        Vec3 c = bb.getCenter();
        Track.Kind kind = RadarScanner.kindOf(e);
        double hr = kind == Track.Kind.CONTRAPTION
                ? Math.min(6.0, Math.max(1.0, Math.min(bb.getXsize(), bb.getZsize()) * 0.5))
                : Math.max(0.35, Math.min(bb.getXsize(), bb.getYsize()) * 0.5);
        Track t = table.observe(id, kind, e.getName().getString(), new Vec(c.x, c.y, c.z), hr, now,
                isGrounded(e), null, s);
        t.handle = e;
        t.hostile = e instanceof Enemy;
        // габарит сущности - точный прямоугольник по осям, как его проверяет CBC; у контрапшена он лишь описанный
        t.half = kind == Track.Kind.CONTRAPTION ? null : new Vec(bb.getXsize() * 0.5, bb.getYsize() * 0.5, bb.getZsize() * 0.5);
        t.volume = bb.getXsize() * bb.getYsize() * bb.getZsize();
    }

    private void observeShip(ServerLevel level, SableCompat.Ship ship, long now, TrackTable.Settings s) {
        Vec3 c = ship.center();
        Vec3 v = ship.velocity();
        Track t = table.observe(ship.id().toString(), Track.Kind.SHIP, ship.name(), new Vec(c.x, c.y, c.z),
                ship.radius(), now, false, new Vec(v.x, v.y, v.z), s);
        t.handle = ship;
        t.hostile = true;
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
        return isFriendly(level, t.handle, t.kind, t.id, t.pos, ctx, now);
    }

    /**
     * Свой ли объект для вычислителя (или монитора) с этим контекстом.
     * @param handle сущность, корабль Sable ({@link SableCompat.Ship}) или null
     */
    public static boolean isFriendly(ServerLevel level, @Nullable Object handle, Track.Kind kind, String id, Vec pos,
                                     Context ctx, long now) {
        int ch = ctx.channel();
        if (handle instanceof SableCompat.Ship ship) {
            UUID sid = ship.id();
            if (ctx.platformShips().contains(sid)) return true;
            if (IffRegistry.shipFriendly(level, sid, ch)) return true;
            if (RadarLink.shipHasFriendlyRadarId(sid, FcsConfig.FRIENDLY_RADAR_IDS.get())) return true;
        } else if (kind == Track.Kind.SHIP) {
            UUID sid = uuid(id);
            if (sid != null && (ctx.platformShips().contains(sid) || IffRegistry.shipFriendly(level, sid, ch))) return true;
            if (sid != null && RadarLink.shipHasFriendlyRadarId(sid, FcsConfig.FRIENDLY_RADAR_IDS.get())) return true;
        }
        if (handle instanceof Entity e) {
            if (e instanceof Player p && isFriendlyPlayer(p, ctx)) return true;
            if (e instanceof OwnableEntity own && own.getOwnerUUID() != null) {
                if (own.getOwnerUUID().equals(ctx.owner())) return true;
                if (own.getOwner() instanceof Player op && isFriendlyPlayer(op, ctx)) return true;
            }
            if (ContraptionCompat.isContraption(e) && ContraptionCompat.hasTransponder(e, ch, now)) return true;
            Entity vehicle = e.getVehicle();
            if (vehicle != null && ContraptionCompat.isContraption(vehicle) && ContraptionCompat.hasTransponder(vehicle, ch, now))
                return true;
            UUID onShip = SableCompat.trackingShipId(e);
            if (onShip != null && (ctx.platformShips().contains(onShip) || IffRegistry.shipFriendly(level, onShip, ch)))
                return true;
        } else if (kind == Track.Kind.PLAYER && !FcsConfig.TARGET_PLAYERS.get()) {
            return true;
        }
        return IffRegistry.protectsPoint(level, pos, ch, FcsConfig.IFF_RADIUS.get());
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
            case MOB -> FcsConfig.TARGET_MOBS.get() && (!FcsConfig.HOSTILE_ONLY.get() || hostile);
            case PLAYER -> FcsConfig.TARGET_PLAYERS.get();
            case SHIP -> FcsConfig.TARGET_SHIPS.get();
            case CONTRAPTION -> FcsConfig.TARGET_CONTRAPTIONS.get();
            // только приближающиеся снаряды: свои уходящие снаряды не цели
            case PROJECTILE -> FcsConfig.TARGET_PROJECTILES.get() && vel.dot(origin.sub(pos)) > 0;
            case EXTERNAL -> true;
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
