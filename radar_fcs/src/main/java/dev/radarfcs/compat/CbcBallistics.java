package dev.radarfcs.compat;

import dev.radarfcs.FcsConfig;
import dev.radarfcs.FcsMod;
import dev.radarfcs.core.CbcFlightModel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannons.autocannon.IAutocannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.autocannon.breech.AbstractAutocannonBreechBlockEntity;
import rbasamoyai.createbigcannons.cannons.big_cannons.IBigCannonBlockEntity;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AutocannonAmmoItem;
import rbasamoyai.createbigcannons.munitions.autocannon.ammo_container.AutocannonAmmoContainerItem;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.ProjectileBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.IntegratedPropellantProjectile;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

/**
 * Априорная баллистика пушки CBC по её содержимому (до первого выстрела).
 * Большая пушка: скорость = сумма силы зарядов (как в CBC), гравитация и сопротивление - из свойств снаряда.
 * Автопушки: скорость по материалу и числу стволов, время жизни снаряда - по материалу.
 * Аддоны (CBC-AT и др.) с другими формулами уточняются наблюдением за выстрелами (ShotObserver).
 */
public final class CbcBallistics {

    public record Estimate(CbcFlightModel model, boolean loaded, String ammo, String fingerprint, boolean exact) {}

    private CbcBallistics() {}

    public static Estimate estimate(ServerLevel level, CbcMount mount) {
        AbstractMountedCannonContraption c = mount.cannon();
        CbcMount.Kind kind = mount.kind();
        CbcFlightModel fallback = kind == CbcMount.Kind.RAPID ? FcsConfig.rapidFallback() : FcsConfig.bigFallback();
        if (c == null) return new Estimate(fallback, false, "-", "none", false);
        try {
            if (c instanceof MountedBigCannonContraption big && kind == CbcMount.Kind.BIG) return bigCannon(level, big, fallback);
            if (kind == CbcMount.Kind.RAPID) return rapid(level, c, fallback);
        } catch (Throwable t) {
            FcsMod.LOG.debug("[radar_fcs] ballistics estimate failed for {}: {}", c.getClass().getName(), t.toString());
        }
        return new Estimate(fallback, true, "?", c.getClass().getSimpleName(), false);
    }

    // ------------------------------------------------------------ большие пушки

    private static Estimate bigCannon(ServerLevel level, MountedBigCannonContraption big, CbcFlightModel fallback) {
        Direction o = big.initialOrientation();
        BlockPos cur = big.getStartPos();
        float charges = 0;
        List<StructureBlockInfo> projBlocks = new ArrayList<>();
        AbstractBigCannonProjectile projectile = null;
        StringBuilder fp = new StringBuilder("big");
        int guard = 0;
        while (guard++ < 128 && big.presentBlockEntities.get(cur) instanceof IBigCannonBlockEntity cbe) {
            StructureBlockInfo info = cbe.cannonBehavior().block();
            BlockState st = info.state();
            Block b = st.getBlock();
            if (st.isAir()) {
                if (projectile == null && projBlocks.isEmpty()) {
                    charges = Math.max(0, charges - 1);         // каждый пустой блок до снаряда съедает заряд, как в CBC
                }
            } else if (b instanceof BigCannonPropellantBlock p && !(b instanceof ProjectileBlock)) {
                charges += Math.max(0, p.getChargePower(info));
                fp.append('|').append(BuiltInRegistries.BLOCK.getKey(b).getPath());
            } else if (b instanceof ProjectileBlock<?> pb && projectile == null) {
                projBlocks.add(info);
                fp.append('|').append(BuiltInRegistries.BLOCK.getKey(b).getPath());
                if (pb.isComplete(projBlocks, o)) {
                    try {
                        projectile = pb.getProjectile(level, projBlocks);
                    } catch (Throwable ignored) {
                        projectile = null;
                    }
                    if (projectile != null) charges += projectile.addedChargePower();
                    if (projectile instanceof IntegratedPropellantProjectile ip) charges += Math.max(0, ip.getChargePower());
                }
            }
            cur = cur.relative(o);
        }
        boolean loaded = projectile != null && charges > 0;
        String ammo = projectile != null ? BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).getPath() : "empty";
        CbcFlightModel base = projectile != null ? fromProjectile(level, projectile, charges, 600, fallback) : fallback;
        if (projectile != null && charges > 0) base = base.withSpeed(charges);
        return new Estimate(base, loaded, ammo + " x" + fmt(charges), fp.toString(), projectile != null);
    }

    // ------------------------------------------------------------ автопушки

    private static Estimate rapid(ServerLevel level, AbstractMountedCannonContraption c, CbcFlightModel fallback) {
        Direction o = c.initialOrientation();
        BlockPos start = c.getStartPos();
        BlockEntity breechBe = c.presentBlockEntities.get(start);
        // материал: базовая скорость, прирост за ствол, время жизни снаряда
        Object material = Reflect.get(c, "cannonMaterial");
        Object props = Reflect.call0(material, "properties");
        double baseSpeed = Reflect.number(Reflect.call0(props, "baseSpeed"), Double.NaN);
        double perBarrel = Reflect.number(Reflect.call0(props, "speedIncreasePerBarrel"), 0);
        int maxIncreases = (int) Reflect.number(Reflect.call0(props, "maxSpeedIncreases"), 0);
        int lifetime = (int) Reflect.number(Reflect.call0(props, "projectileLifetime"), fallback.maxTicks());

        int barrels = 0;
        BlockPos cur = start.relative(o);
        for (int guard = 0; guard < 64 && c.presentBlockEntities.get(cur) instanceof IAutocannonBlockEntity; guard++) {
            barrels++;
            cur = cur.relative(o);
        }
        double speed = Double.isNaN(baseSpeed) ? fallback.speed() : baseSpeed + perBarrel * Math.min(barrels, maxIncreases);

        ItemStack round = ItemStack.EMPTY;
        boolean loaded = true;
        if (breechBe instanceof AbstractAutocannonBreechBlockEntity breech) {
            Deque<ItemStack> input = breech.getInputBuffer();
            if (input != null && !input.isEmpty()) round = input.peekFirst();
            else if (!breech.getMagazine().isEmpty()) {
                if (AutocannonAmmoContainerItem.getTotalAmmoCount(breech.getMagazine()) > 0)
                    round = AutocannonAmmoContainerItem.getMainAmmoStack(breech.getMagazine());
            }
            loaded = round != null && !round.isEmpty();
        }
        CbcFlightModel base = fallback.withSpeed(speed).withMaxTicks(Math.max(5, lifetime));
        String ammo = "?";
        boolean exact = false;
        if (round != null && !round.isEmpty() && round.getItem() instanceof AutocannonAmmoItem ammoItem) {
            Entity proj = null;
            try {
                proj = ammoItem.getAutocannonProjectile(round, level);
            } catch (Throwable ignored) {
            }
            if (proj instanceof AbstractCannonProjectile acp) {
                base = fromProjectile(level, acp, speed, Math.max(5, lifetime), base);
                exact = !Double.isNaN(baseSpeed);
            }
            ammo = BuiltInRegistries.ITEM.getKey(round.getItem()).getPath();
        }
        String fp = c.getClass().getSimpleName() + "|" + barrels + "|" + ammo;
        return new Estimate(base, loaded, ammo, fp, exact);
    }

    // ------------------------------------------------------------ свойства снаряда

    /** Гравитация и сопротивление снаряда (с множителями измерения), как их считает CBC. */
    public static CbcFlightModel fromProjectile(ServerLevel level, AbstractCannonProjectile p, double speed, int maxTicks,
                                               CbcFlightModel fallback) {
        BallisticPropertiesComponent bp = ballistics(p);
        if (bp == null) return fallback.withSpeed(speed).withMaxTicks(maxTicks);
        double gMul = 1, dMul = 1;
        try {
            var dim = DimensionMunitionPropertiesHandler.getProperties(level);
            gMul = dim.gravityMultiplier();
            dMul = dim.dragMultiplier();
        } catch (Throwable ignored) {
        }
        double g = p.isNoGravity() ? 0 : bp.gravity() * gMul;
        return CbcFlightModel.ballistic(speed, g, bp.drag() * dMul, bp.isQuadraticDrag(), maxTicks);
    }

    @Nullable
    public static BallisticPropertiesComponent ballistics(AbstractCannonProjectile p) {
        Method m = Reflect.method0(p.getClass(), "getBallisticProperties");
        if (m == null) return null;
        try {
            return m.invoke(p) instanceof BallisticPropertiesComponent b ? b : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
