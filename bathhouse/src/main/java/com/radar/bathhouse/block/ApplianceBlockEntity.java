package com.radar.bathhouse.block;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.climate.HeatSource;
import com.radar.bathhouse.climate.SourceInfo;
import com.radar.bathhouse.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/** Steam generator and warm lounger: redstone-powered, hand heat and steam to the room's gauge. */
public class ApplianceBlockEntity extends BlockEntity implements HeatSource {
    public static final int TANK_CAPACITY = 8000;
    /** Grams of vapour per millibucket of water. */
    public static final double GRAMS_PER_MB = 2.0;
    private static final int LINK_TIMEOUT = 60;

    private double core = Double.NaN;
    private boolean running;
    @Nullable
    private BlockPos gauge;
    private long linkTime = Long.MIN_VALUE / 2;
    private double roomTemperature;
    private double pendingHeat;
    private double pendingVapour;

    private final FluidTank tank = new FluidTank(TANK_CAPACITY, fluid -> fluid.is(Fluids.WATER)) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    public ApplianceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.APPLIANCE.get(), pos, state);
    }

    public ApplianceType type() {
        return getBlockState().getBlock() instanceof ApplianceBlock block ? block.type() : ApplianceType.STEAM_GENERATOR;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ApplianceBlockEntity be) {
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0) {
            be.step(1.0);
        }
    }

    /** One second at once (game tests). */
    public void advanceSecond() {
        step(1.0);
    }

    private void step(double seconds) {
        if (level == null) {
            return;
        }
        ApplianceType type = type();
        double dt = seconds * BathConfig.SIMULATION_SPEED.get();
        boolean linked = isLinked();
        double environment = linked ? roomTemperature : Climate.ambientTemperature(level, worldPosition);
        if (Double.isNaN(core)) {
            core = environment;
        }
        double fraction = level.getBestNeighborSignal(worldPosition) / 15.0;
        double steam = 0.0;
        if (type == ApplianceType.STEAM_GENERATOR && fraction > 0) {
            int water = (int) Math.ceil(type.steamRate() * fraction * dt / GRAMS_PER_MB);
            FluidStack drained = tank.drain(water, IFluidHandler.FluidAction.EXECUTE);
            fraction *= water == 0 ? 0.0 : (double) drained.getAmount() / water;
            steam = type.steamRate() * fraction * dt;
        }
        double power = core >= type.maxCore() ? 0.0 : type.power() * fraction;
        double toRoom = type.transfer() * (core - environment);
        core += (power - toRoom) * dt / type.mass();
        if (linked) {
            pendingHeat += toRoom * dt;
            pendingVapour += steam;
        } else {
            pendingHeat = 0.0;
            pendingVapour = 0.0;
        }
        running = fraction > 0;
        BlockState state = getBlockState();
        if (state.getValue(ApplianceBlock.LIT) != running) {
            level.setBlock(worldPosition, state.setValue(ApplianceBlock.LIT, running), Block.UPDATE_ALL);
        }
        setChanged();
    }

    @Override
    public void link(BlockPos gaugePos, double roomTemperature, long gameTime) {
        if (gauge != null && !gauge.equals(gaugePos) && isLinked()) {
            return;
        }
        this.gauge = gaugePos.immutable();
        this.roomTemperature = roomTemperature;
        this.linkTime = gameTime;
    }

    public boolean isLinked() {
        return level != null && gauge != null && level.getGameTime() - linkTime <= LINK_TIMEOUT;
    }

    @Override
    public Output drainOutput(BlockPos gaugePos) {
        if (gauge == null || !gauge.equals(gaugePos)) {
            return Output.NONE;
        }
        Output out = new Output(pendingHeat, pendingVapour, 0.0, 0.0);
        pendingHeat = 0.0;
        pendingVapour = 0.0;
        return out;
    }

    @Override
    public SourceInfo info() {
        return SourceInfo.appliance(type().kind(), running);
    }

    public Component statusLine() {
        ApplianceType type = type();
        MutableComponent line = Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        int signal = level == null ? 0 : level.getBestNeighborSignal(worldPosition);
        if (type == ApplianceType.STEAM_GENERATOR) {
            line.append(Component.translatable("bathhouse.appliance.water",
                    String.format(java.util.Locale.ROOT, "%.1f", tank.getFluidAmount() / 1000.0), TANK_CAPACITY / 1000));
            line.append(" · ");
            if (tank.isEmpty()) {
                line.append(Component.translatable("bathhouse.appliance.no_water").withStyle(ChatFormatting.RED));
                line.append(" · ");
            }
        } else {
            line.append(Component.translatable("bathhouse.appliance.core", Math.round(Double.isNaN(core) ? 0 : core)));
            line.append(" · ");
        }
        line.append(signal > 0
                ? Component.translatable("bathhouse.appliance.power", Math.round(signal * 100 / 15.0))
                : Component.translatable("bathhouse.appliance.no_power").withStyle(ChatFormatting.RED));
        line.append(" · ");
        line.append(isLinked()
                ? Component.translatable("bathhouse.appliance.linked").withStyle(ChatFormatting.GREEN)
                : Component.translatable("bathhouse.appliance.unlinked").withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    public boolean running() {
        return running;
    }

    public FluidTank tank() {
        return tank;
    }

    @Nullable
    public FluidTank waterHandler() {
        return type() == ApplianceType.STEAM_GENERATOR ? tank : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(core)) {
            tag.putDouble("Core", core);
        }
        if (!tank.isEmpty()) {
            tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core = tag.contains("Core") ? tag.getDouble("Core") : Double.NaN;
        if (tag.contains("Tank")) {
            tank.readFromNBT(registries, tag.getCompound("Tank"));
        }
    }
}
