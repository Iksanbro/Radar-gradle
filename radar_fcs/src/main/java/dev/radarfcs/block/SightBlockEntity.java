package dev.radarfcs.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import dev.radarfcs.compat.CbcMount;
import dev.radarfcs.compat.SableCompat;
import dev.radarfcs.link.GunLinks;
import dev.radarfcs.registry.FcsRegistry;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Прицел орудия: направление наведения в мире (через позу под-уровня Sable) и сигнал огня. */
public class SightBlockEntity extends BlockEntity implements IHaveGoggleInformation {
    /** Сигнал огня гаснет сам, если вычислитель перестал его подтверждать. */
    private static final int FIRE_HOLD_TICKS = 2;
    private long fireUntil = Long.MIN_VALUE / 2;
    /** Состав орудия для очков инженера (считается на сервере): вращатели, станки CBC. */
    private int[] counts = {0, 0};

    public SightBlockEntity(BlockPos pos, BlockState state) {
        super(FcsRegistry.SIGHT_BE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SightBlockEntity be) {
        if (state.getValue(SightBlock.FIRING) && level.getGameTime() > be.fireUntil) be.setFiringState(false);
        if (level.getGameTime() % 20 == Math.floorMod(pos.hashCode(), 20)) {
            int[] c = be.composition();
            if (!java.util.Arrays.equals(c, be.counts)) {
                be.counts = c;
                level.sendBlockUpdated(pos, state, state, 3);
            }
        }
    }

    public Direction facing() {
        return getBlockState().getValue(SightBlock.FACING);
    }

    /** Направление объектива в мире (единичный вектор). */
    public Vec3 worldDirection() {
        Vec3 n = Vec3.atLowerCornerOf(facing().getNormal());
        return level == null ? n : SableCompat.dirToWorld(SableCompat.containing(level, worldPosition), n).normalize();
    }

    /** Центр прицела в мире. */
    public Vec3 worldCenter() {
        Vec3 c = Vec3.atCenterOf(worldPosition);
        return level == null ? c : SableCompat.toWorld(level, c);
    }

    /** Сигнал огня на этот тик (без станка CBC в орудии - для своих схем на редстоуне). */
    public void fire(boolean on) {
        if (level == null) return;
        if (on) fireUntil = level.getGameTime() + FIRE_HOLD_TICKS;
        else fireUntil = Long.MIN_VALUE / 2;
        setFiringState(on);
    }

    public boolean firing() {
        return getBlockState().getValue(SightBlock.FIRING);
    }

    private void setFiringState(boolean on) {
        if (level == null || level.isClientSide) return;
        BlockState st = getBlockState();
        if (st.getValue(SightBlock.FIRING) == on) return;
        level.setBlock(worldPosition, st.setValue(SightBlock.FIRING, on), 3);
    }

    /** Состав орудия по орудийным линкам: {вращатели, станки CBC}. */
    public int[] composition() {
        int rot = 0, mounts = 0;
        if (level == null) return new int[]{0, 0};
        for (BlockPos p : GunLinks.group(level, worldPosition)) {
            if (!level.isLoaded(p)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof GunRotatorBlockEntity) rot++;
            else if (be != null && CbcMount.of(be) != null) mounts++;
        }
        return new int[]{rot, mounts};
    }

    public Component describe() {
        Vec3 d = worldDirection();
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d.y))));
        int[] c = counts;
        return Component.translatable("radar_fcs.sight.info", String.format(Locale.ROOT, "%.1f", yaw),
                String.format(Locale.ROOT, "%.1f", pitch), c[0], c[1]).withStyle(ChatFormatting.AQUA);
    }

    @Override
    protected void saveAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putIntArray("Counts", counts);
    }

    @Override
    protected void loadAdditional(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int[] c = tag.getIntArray("Counts");
        counts = c.length == 2 ? c : new int[]{0, 0};
    }

    @Override
    public net.minecraft.nbt.CompoundTag getUpdateTag(net.minecraft.core.HolderLookup.Provider registries) {
        net.minecraft.nbt.CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(Component.literal("    ").append(Component.translatable("block.radar_fcs.sight").withStyle(ChatFormatting.GRAY)));
        tooltip.add(Component.literal("    ").append(describe()));
        if (counts[0] == 0) tooltip.add(Component.literal("    ")
                .append(Component.translatable("radar_fcs.sight.no_rotators").withStyle(ChatFormatting.GOLD)));
        return true;
    }
}
