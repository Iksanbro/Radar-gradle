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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedBigCannonContraption;
import rbasamoyai.createbigcannons.cannons.ICannonBlockEntity;
import rbasamoyai.createbigcannons.cannons.ItemCannonBehavior;
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

    /**
     * @param spread разброс CBC (inaccuracy выстрела), NaN - неизвестен
     * @param reach  на сколько CBC расширяет габарит цели при проверке попадания: половина размера снаряда, блоков
     */
    public record Estimate(CbcFlightModel model, boolean loaded, String ammo, String fingerprint, boolean exact,
                           double spread, double reach) {
        public static double defaultReach(CbcMount.Kind kind) {
            return kind == CbcMount.Kind.RAPID ? 0.1 : 0.4;
        }
    }

    private static final net.minecraft.tags.TagKey<Block> REDUCES_SPREAD = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.BLOCK,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createbigcannons", "reduces_spread"));

    private CbcBallistics() {}

    public static Estimate estimate(ServerLevel level, CbcMount mount) {
        AbstractMountedCannonContraption c = mount.cannon();
        CbcMount.Kind kind = mount.kind();
        CbcFlightModel fallback = kind == CbcMount.Kind.RAPID ? FcsConfig.rapidFallback() : FcsConfig.bigFallback();
        double reach0 = Estimate.defaultReach(kind);
        if (c == null) return new Estimate(fallback, false, "-", "none", false, Double.NaN, reach0);
        try {
            if (c instanceof MountedBigCannonContraption big && kind == CbcMount.Kind.BIG) return bigCannon(level, big, fallback);
            if (NeoWarfare.isMedium(c)) return medium(level, c, fallback);
            if (kind == CbcMount.Kind.RAPID) return rapid(level, c, fallback);
        } catch (Throwable t) {
            FcsMod.LOG.debug("[radar_fcs] ballistics estimate failed for {}: {}", c.getClass().getName(), t.toString());
        }
        return new Estimate(fallback, true, "?", c.getClass().getSimpleName(), false, Double.NaN, reach0);
    }

    // ------------------------------------------------------------ большие пушки

    private static Estimate bigCannon(ServerLevel level, MountedBigCannonContraption big, CbcFlightModel fallback) {
        Direction o = big.initialOrientation();
        BlockPos cur = big.getStartPos();
        float charges = 0;
        // разброс как в CBC: каждый заряд добавляет свой, каждый ствол от снаряда до дульного среза убирает
        double spreadSum = 0;
        int spreadBarrels = 0;
        List<StructureBlockInfo> projBlocks = new ArrayList<>();
        AbstractBigCannonProjectile projectile = null;
        StringBuilder fp = new StringBuilder("big");
        // CBC-AT со своей физикой выстрела: параллельно считаем её же классом
        Object at = CbcAtPhysics.newContext();
        int guard = 0;
        while (guard++ < 128 && big.presentBlockEntities.get(cur) instanceof IBigCannonBlockEntity cbe) {
            StructureBlockInfo info = cbe.cannonBehavior().block();
            BlockState st = info.state();
            Block b = st.getBlock();
            if (st.isAir()) {
                if (projectile == null && projBlocks.isEmpty()) {
                    charges = Math.max(0, charges - 1);         // каждый пустой блок до снаряда съедает заряд, как в CBC
                }
            } else if (projectile == null && b instanceof BigCannonPropellantBlock cp && CbcAtPhysics.isCartridge(b)) {
                // гильза CBC-AT: заряд и снаряд в одном блоке
                charges += Math.max(0, cp.getChargePower(info));
                spreadSum += spreadOf(cp, info);
                CbcAtPhysics.propellant(at, cp, info, o);
                projBlocks.add(info);
                fp.append('|').append(BuiltInRegistries.BLOCK.getKey(b).getPath());
                projectile = CbcAtPhysics.cartridgeProjectile(b, level, projBlocks);
                if (projectile != null) {
                    charges += projectile.addedChargePower();
                    CbcAtPhysics.addCharge(at, projectile.addedChargePower());
                }
            } else if (b instanceof BigCannonPropellantBlock p && !(b instanceof ProjectileBlock)) {
                charges += Math.max(0, p.getChargePower(info));
                spreadSum += spreadOf(p, info);
                CbcAtPhysics.propellant(at, p, info, o);
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
                    if (projectile != null) {
                        charges += projectile.addedChargePower();
                        CbcAtPhysics.addCharge(at, projectile.addedChargePower());
                    }
                    if (projectile instanceof IntegratedPropellantProjectile ip) {
                        charges += Math.max(0, ip.getChargePower());
                        try {
                            spreadSum += ip.getSpread();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (!projBlocks.isEmpty() && reducesSpread(big, cur)) spreadBarrels++;
            StructureBlockInfo cannonInfo = big.getBlocks().get(cur);
            if (cannonInfo != null) CbcAtPhysics.barrel(at, cannonInfo.state().getBlock());
            cur = cur.relative(o);
        }
        boolean loaded = projectile != null && charges > 0;
        double spread = Double.NaN;
        Object props = Reflect.call0(Reflect.get(big, "cannonMaterial"), "properties");
        double perBarrel = Reflect.number(Reflect.call0(props, "spreadReductionPerBarrel"), Double.NaN);
        double minSpread = Reflect.number(Reflect.call0(props, "minimumSpread"), 0);
        if (projectile != null && !Double.isNaN(perBarrel)) spread = Math.max(minSpread, spreadSum - perBarrel * spreadBarrels);
        // скорость выстрела CBC = сумма зарядов; у CBC-AT - своя (газ заряда минус сопротивление стволов)
        double speed = charges;
        if (at != null && projectile != null) {
            double v = CbcAtPhysics.velocity(at), sp = CbcAtPhysics.spread(at);
            if (Double.isFinite(v) && v > 0) speed = v;
            if (Double.isFinite(sp)) spread = sp;
        }
        String ammo = projectile != null ? BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).getPath() : "empty";
        CbcFlightModel base = projectile != null ? fromProjectile(level, projectile, speed, 600, fallback) : fallback;
        if (projectile != null && speed > 0) base = base.withSpeed(speed);
        double reach = projectile != null ? reachOf(projectile, 0.4) : 0.4;
        return new Estimate(base, loaded, ammo + " x" + fmt(charges), fp.toString(), projectile != null, spread, reach);
    }

    private static double spreadOf(BigCannonPropellantBlock p, StructureBlockInfo info) {
        try {
            return Math.max(0, p.getSpread(info));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Ствол, уменьшающий разброс (тег CBC reduces_spread). */
    private static boolean reducesSpread(MountedBigCannonContraption big, BlockPos pos) {
        StructureBlockInfo info = big.getBlocks().get(pos);
        if (info == null) return false;
        return info.state().is(REDUCES_SPREAD);
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
        double baseSpread = Reflect.number(Reflect.call0(props, "baseSpread"), Double.NaN);
        double spreadPerBarrel = Reflect.number(Reflect.call0(props, "spreadReductionPerBarrel"), 0);

        int barrels = itemBarrels(c);
        double speed = Double.isNaN(baseSpeed) ? fallback.speed() : baseSpeed + perBarrel * Math.min(barrels, maxIncreases);
        // как в CBC: каждый ствол уменьшает разброс, но не ниже нуля
        double spread = Double.isNaN(baseSpread) ? Double.NaN : Math.max(0, baseSpread - spreadPerBarrel * barrels);
        double reach = 0.1;

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
        } else if (breechBe != null) {
            // казённики аддонов (роторная пушка Neo Warfare и др.) - тем же API по именам методов
            round = addonRound(breechBe);
            if (round != null) loaded = !round.isEmpty();
            else round = ItemStack.EMPTY;
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
                reach = reachOf(acp, 0.1);
            }
            ammo = BuiltInRegistries.ITEM.getKey(round.getItem()).getPath();
        }
        String fp = c.getClass().getSimpleName() + "|" + barrels + "|" + ammo;
        return new Estimate(base, loaded, ammo, fp, exact, spread, reach);
    }

    /**
     * Стволы предметной пушки (автопушки CBC, роторные и средние пушки Neo Warfare) от блока за казёнником:
     * так их считает сама пушка при выстреле (пружина отката и подшипник тоже).
     */
    static int itemBarrels(AbstractMountedCannonContraption c) {
        Direction o = c.initialOrientation();
        int barrels = 0;
        BlockPos cur = c.getStartPos().relative(o);
        for (int guard = 0; guard < 64 && c.presentBlockEntities.get(cur) instanceof ICannonBlockEntity<?> cbe
                && cbe.cannonBehavior() instanceof ItemCannonBehavior; guard++) {
            barrels++;
            cur = cur.relative(o);
        }
        return barrels;
    }

    /** Следующий патрон в казённике аддона: getInputBuffer() (очередь или один предмет), затем getMagazine(); null - не понять. */
    @Nullable
    private static ItemStack addonRound(BlockEntity breech) {
        Object in = Reflect.call0(breech, "getInputBuffer");
        if (in instanceof Deque<?> q) {
            if (!q.isEmpty() && q.peekFirst() instanceof ItemStack st) return st;
        } else if (in instanceof ItemStack st && !st.isEmpty()) {
            return st;
        }
        Object mag = Reflect.call0(breech, "getMagazine");
        if (mag instanceof ItemStack ms && !ms.isEmpty()) {
            try {
                if (AutocannonAmmoContainerItem.getTotalAmmoCount(ms) > 0) return AutocannonAmmoContainerItem.getMainAmmoStack(ms);
            } catch (Throwable ignored) {
            }
            return null;                                                     // магазин аддона: заряжена ли - неизвестно
        }
        return in == null && mag == null ? null : ItemStack.EMPTY;
    }

    // ------------------------------------------------------------ средняя пушка CBC Neo Warfare

    /**
     * Средняя пушка (CBC Modern Warfare / Neo Warfare): снаряд из казённика, скорость и разброс - по материалу
     * и числу стволов, как у автопушки; стреляет по фронту сигнала, как большая пушка.
     */
    private static Estimate medium(ServerLevel level, AbstractMountedCannonContraption c, CbcFlightModel fallback) {
        Object props = Reflect.call0(Reflect.get(c, "cannonMaterial"), "properties");
        double baseSpeed = Reflect.number(Reflect.call0(props, "baseSpeed"), Double.NaN);
        double perBarrel = Reflect.number(Reflect.call0(props, "speedIncreasePerBarrel"), 0);
        int maxIncreases = (int) Reflect.number(Reflect.call0(props, "maxSpeedIncreases"), 0);
        double baseSpread = Reflect.number(Reflect.call0(props, "baseSpread"), Double.NaN);
        double spreadPerBarrel = Reflect.number(Reflect.call0(props, "spreadReductionPerBarrel"), 0);
        int barrels = itemBarrels(c);
        double speed = Double.isNaN(baseSpeed) ? fallback.speed() : baseSpeed + perBarrel * Math.min(barrels, maxIncreases);
        double spread = Double.isNaN(baseSpread) ? Double.NaN : Math.max(0, baseSpread - spreadPerBarrel * barrels);

        BlockEntity breech = c.presentBlockEntities.get(c.getStartPos());
        ItemStack round = breech == null ? ItemStack.EMPTY : addonRound(breech);
        if (round == null) round = ItemStack.EMPTY;
        CbcFlightModel base = fallback.withSpeed(speed);
        String ammo = round.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(round.getItem()).getPath();
        boolean loaded = false, exact = false;
        double reach = 0.4;
        // заряжена, если в казённике патрон средней пушки со снарядом (после выстрела там пустая гильза)
        if (!round.isEmpty() && Reflect.implementsNamed(round.getItem().getClass(), "MediumcannonAmmoItem")) {
            Method m = Reflect.method(round.getItem().getClass(), "getMediumcannonProjectile", ItemStack.class, Level.class);
            try {
                if (m != null && m.invoke(round.getItem(), round, level) instanceof AbstractCannonProjectile acp) {
                    loaded = true;
                    base = fromProjectile(level, acp, speed, fallback.maxTicks(), base);
                    exact = !Double.isNaN(baseSpeed);
                    reach = reachOf(acp, 0.4);
                }
            } catch (Throwable ignored) {
            }
        }
        String fp = c.getClass().getSimpleName() + "|" + barrels + "|" + ammo;
        return new Estimate(base, loaded, ammo, fp, exact, spread, reach);
    }

    /** Половина размера снаряда: на столько CBC расширяет габарит цели при проверке попадания. */
    private static double reachOf(Entity projectile, double fallback) {
        double r = Math.max(projectile.getBbWidth(), projectile.getBbHeight()) * 0.5;
        return r > 0 && r < 4 ? r : fallback;
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
