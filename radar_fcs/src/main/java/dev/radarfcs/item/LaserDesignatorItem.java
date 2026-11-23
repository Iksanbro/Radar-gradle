package dev.radarfcs.item;

import dev.radarfcs.block.RadarMonitorBlockEntity;
import dev.radarfcs.compat.SableCompat;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Лазерный целеуказатель: Shift+ПКМ по монитору радара - привязать к нему. ПКМ держать - прицеливание (как подзорная
 * труба: приближение, сетка прицела, зелёный луч, см. {@code client/DesignatorClient}); ЛКМ при прицеливании - луч до
 * {@link #RANGE} блоков: цель под перекрестием (сущность, снаряд, корабль Sable) назначается на привязанном мониторе,
 * как ПКМ по её отметке на экране (вычислители сети ведут её вне очереди), луч на миг краснеет; ЛКМ в воздух (на луче
 * нет цели) - снять назначение. Shift+ПКМ в воздух - тоже снять. Цель должна быть видна радарам сети монитора.
 */
public class LaserDesignatorItem extends Item {
    /** Дальность луча, блоков (по загруженным чанкам). */
    public static final double RANGE = 512;
    /** Допуск попадания по сущности: запас габарита на блок дальности (≈0.3°) - издалека легче попасть. */
    private static final double SLACK_PER_BLOCK = 0.005;

    public LaserDesignatorItem(Properties props) {
        super(props.stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Player p = ctx.getPlayer();
        Level level = ctx.getLevel();
        if (p == null || !p.isShiftKeyDown()) return InteractionResult.PASS;     // простой ПКМ - луч (use)
        if (!(level.getBlockEntity(ctx.getClickedPos()) instanceof RadarMonitorBlockEntity mon)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            BlockPos m = mon.master().getBlockPos();
            CompoundTag t = new CompoundTag();
            t.putLong("Monitor", m.asLong());
            t.putString("Dim", level.dimension().location().toString());
            ctx.getItemInHand().set(DataComponents.CUSTOM_DATA, CustomData.of(t));
            p.displayClientMessage(Component.translatable("radar_fcs.designator.bound", m.getX(), m.getY(), m.getZ())
                    .withStyle(ChatFormatting.GREEN), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Прицеливание - как у подзорной трубы (поза руки у глаза в третьем лице). */
    @Override
    public net.minecraft.world.item.UseAnim getUseAnimation(ItemStack stack) {
        return net.minecraft.world.item.UseAnim.SPYGLASS;
    }

    @Override
    public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
        return 72000;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player p, InteractionHand hand) {
        ItemStack stack = p.getItemInHand(hand);
        if (p.isShiftKeyDown()) {
            // Shift+ПКМ в воздух - снять назначение
            if (level instanceof ServerLevel sl) {
                RadarMonitorBlockEntity mon = monitor(sl, stack);
                if (mon == null) unboundMessage(p, stack);
                else mon.designate(null, p);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        // ПКМ держать - прицеливаться, отпустить - назначить цель под перекрестием
        p.playSound(SoundEvents.SPYGLASS_USE, 1.0f, 1.0f);
        p.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, net.minecraft.world.entity.LivingEntity entity, int timeLeft) {
        if (entity instanceof Player p) p.playSound(SoundEvents.SPYGLASS_STOP_USING, 1.0f, 1.0f);
    }

    private static void unboundMessage(Player p, ItemStack stack) {
        p.displayClientMessage(Component.translatable(bound(stack) == null ? "radar_fcs.designator.unbound"
                : "radar_fcs.designator.no_monitor").withStyle(ChatFormatting.GOLD), true);
    }

    /**
     * ЛКМ при прицеливании (сервер): луч от глаз игрока; цель на нём - назначить, на луче нет цели - снять назначение.
     *
     * @return цель назначена
     */
    public boolean fire(ServerLevel sl, Player p, ItemStack stack) {
        RadarMonitorBlockEntity mon = monitor(sl, stack);
        if (mon == null) {
            unboundMessage(p, stack);
            return false;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(RANGE));
        Aim aim = trace(sl, p, eye, end);
        sl.playSound(null, p.blockPosition(), SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6f, 1.8f);
        if (aim.ids().isEmpty()) {
            mon.designate(null, p);                                        // ЛКМ в воздух - снять назначение
            return false;
        }
        for (UUID id : aim.ids()) {
            if (mon.designateTracked(id, p)) return true;
        }
        p.displayClientMessage(Component.translatable("radar_fcs.designator.not_tracked").withStyle(ChatFormatting.GOLD), true);
        return false;
    }

    /** Что под лучом: точка конца и id возможных целей по порядку (сущность, затем корабль, на котором она). */
    public record Aim(Vec3 point, List<UUID> ids) {}

    /** Луч от глаз игрока: блоки мира (только загруженные чанки), постройки Sable, сущности с допуском по дальности. */
    public static Aim trace(ServerLevel sl, Player p, Vec3 eye, Vec3 end) {
        BlockHitResult bh = SableCompat.plain(sl).clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        Vec3 stop = bh.getType() == HitResult.Type.MISS ? end : bh.getLocation();
        UUID own = SableCompat.trackingShipId(p);
        UUID shipId = null;
        SableCompat.ShipHit ship = SableCompat.clipShips(sl, eye, stop, id -> id.equals(own), SableCompat.plain(sl), false);
        if (ship != null) {
            stop = ship.point();
            shipId = ship.ship();
        }
        Entity best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (Entity e : sl.getEntities(p, new AABB(eye, stop).inflate(2.0), e -> !e.isSpectator() && e != p.getVehicle())) {
            double slack = 0.3 + SLACK_PER_BLOCK * e.position().distanceTo(eye);
            var hit = e.getBoundingBox().inflate(slack).clip(eye, stop);
            if (hit.isEmpty()) continue;
            double d = hit.get().distanceToSqr(eye);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        List<UUID> ids = new java.util.ArrayList<>();
        if (best != null) {
            ids.add(best.getUUID());
            UUID on = SableCompat.trackingShipId(best);
            if (on != null && !on.equals(own)) ids.add(on);
            return new Aim(best.getBoundingBox().getCenter(), ids);
        }
        if (shipId != null) ids.add(shipId);
        return new Aim(stop, ids);
    }

    /** Привязанный монитор (в том же измерении, загруженный) или null. */
    @Nullable
    static RadarMonitorBlockEntity monitor(ServerLevel sl, ItemStack stack) {
        BlockPos pos = bound(stack);
        if (pos == null) return null;
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!sl.dimension().location().toString().equals(t.getString("Dim"))) return null;
        if (!sl.isLoaded(pos)) return null;
        return sl.getBlockEntity(pos) instanceof RadarMonitorBlockEntity m ? m.master() : null;
    }

    @Nullable
    static BlockPos bound(ItemStack stack) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return t.contains("Monitor") ? BlockPos.of(t.getLong("Monitor")) : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        BlockPos b = bound(stack);
        tooltip.add(b == null ? Component.translatable("radar_fcs.designator.tooltip_unbound").withStyle(ChatFormatting.GOLD)
                : Component.translatable("radar_fcs.designator.tooltip_bound", b.getX(), b.getY(), b.getZ()).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("radar_fcs.tooltip.laser_designator").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
