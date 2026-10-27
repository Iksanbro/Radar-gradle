package dev.radarfcs.compat;

import dev.radarfcs.FcsMod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.propellant.BigCannonPropellantBlock;

/**
 * Переработанная физика больших пушек CBC Advanced Technologies (включена по умолчанию): скорость снаряда
 * и разброс там считаются по газу заряда и сопротивлению стволов, а не «скорость = число зарядов», как в CBC.
 * Расчёт делается собственным классом CBC-AT (через рефлексию), сам мод для сборки не нужен.
 */
final class CbcAtPhysics {
    private static boolean init;
    @Nullable private static Class<?> ctxClass, physClass, cartridgeClass;
    private static Method addPropellant, addBarrel, getVelocity, cartridgeProjectile;
    private static Field chargesUsed, spread;
    @Nullable private static Object disableOption;

    private CbcAtPhysics() {}

    private static synchronized boolean available() {
        if (!init) {
            init = true;
            if (!ModList.get().isLoaded("cbc_at")) return false;
            try {
                ctxClass = Class.forName("com.dsvv.cbcat.base.CustomPropellantContext");
                physClass = Class.forName("com.dsvv.cbcat.base.IBigCannonBlockPhysics");
                cartridgeClass = Class.forName("com.dsvv.cbcat.cartridge.IProjectileCartridgeBlock");
                addPropellant = ctxClass.getMethod("addPropellant", BigCannonPropellantBlock.class, StructureBlockInfo.class, Direction.class);
                addBarrel = ctxClass.getMethod("addBarrel", physClass);
                getVelocity = ctxClass.getMethod("getVelocity");
                chargesUsed = ctxClass.getField("chargesUsed");
                spread = ctxClass.getField("spread");
                cartridgeProjectile = cartridgeClass.getMethod("getProjectile", Level.class, List.class);
            } catch (Throwable t) {
                FcsMod.LOG.info("[radar_fcs] CBC-AT big cannon physics not recognised ({}), using CBC formulas", t.toString());
                ctxClass = null;
            }
        }
        return ctxClass != null;
    }

    /** Новый расчёт выстрела CBC-AT или null: мода нет либо переработка физики выключена в его конфиге. */
    @Nullable
    static Object newContext() {
        if (!available() || reworkDisabled()) return null;
        try {
            return ctxClass.getConstructor().newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean reworkDisabled() {
        try {
            if (disableOption == null) {
                Object server = Class.forName("com.dsvv.cbcat.config.CBCATConfigs").getField("SERVER").get(null);
                if (server == null) return false;
                Object big = server.getClass().getField("bigCannons").get(server);
                disableOption = big.getClass().getField("disablePhysicRework").get(big);
            }
            return Boolean.TRUE.equals(Reflect.call0(disableOption, "get"));
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isCartridge(Block b) {
        return available() && cartridgeClass.isInstance(b);
    }

    @Nullable
    static AbstractBigCannonProjectile cartridgeProjectile(Block b, Level level, List<StructureBlockInfo> blocks) {
        try {
            return cartridgeProjectile.invoke(b, level, blocks) instanceof AbstractBigCannonProjectile p ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void propellant(@Nullable Object ctx, BigCannonPropellantBlock b, StructureBlockInfo info, Direction o) {
        if (ctx == null) return;
        try {
            addPropellant.invoke(ctx, b, info, o);
        } catch (Throwable ignored) {
        }
    }

    static void addCharge(@Nullable Object ctx, float power) {
        if (ctx == null) return;
        try {
            chargesUsed.setFloat(ctx, chargesUsed.getFloat(ctx) + power);
        } catch (Throwable ignored) {
        }
    }

    /** Блок пушки (камора, ствол): у CBC-AT каждый расходует газ, тормозит снаряд и сужает разброс. */
    static void barrel(@Nullable Object ctx, Block cannonBlock) {
        if (ctx == null || !physClass.isInstance(cannonBlock)) return;
        try {
            addBarrel.invoke(ctx, cannonBlock);
        } catch (Throwable ignored) {
        }
    }

    static double velocity(Object ctx) {
        try {
            return ((Number) getVelocity.invoke(ctx)).doubleValue();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    static double spread(Object ctx) {
        try {
            return spread.getFloat(ctx);
        } catch (Throwable t) {
            return Double.NaN;
        }
    }
}
