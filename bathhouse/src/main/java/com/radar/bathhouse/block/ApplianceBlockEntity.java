package com.radar.bathhouse.block;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Burns fuel (or draws redstone power), keeps the temperature of its core and hands heat, vapour and smoke to the
 * {@link BathGaugeBlockEntity} of the room it borders. Without a gauge it just warms the outside air.
 */
public class ApplianceBlockEntity extends BlockEntity {
    /** Bath heaters burn slowly with a closed damper: one item of fuel lasts four times longer than in a furnace. */
    public static final int FUEL_EFFICIENCY = 4;
    /** Maximum stored fuel, ticks (20 minutes). */
    public static final int MAX_FUEL = 24000;
    public static final int TANK_CAPACITY = 8000;
    /** Grams of vapour per millibucket of water in the steam generator. */
    public static final double GRAMS_PER_MB = 2.0;
    /** Core cooling per gram of water evaporated on the stones, kJ. */
    public static final double LATENT_HEAT = 0.6;
    /** A link older than this (ticks) is considered broken. */
    private static final int LINK_TIMEOUT = 60;

    private double core = Double.NaN;
    private int fuel;
    private boolean running;

    @Nullable
    private BlockPos gauge;
    private long linkTime = Long.MIN_VALUE / 2;
    private double roomTemperature;
    private boolean smokesIntoRoom;

    private double pendingHeat;
    private double pendingVapour;
    private double pendingSmoke;
    private double drying;
    private double steamFraction;

    private final FluidTank tank = new FluidTank(TANK_CAPACITY, fluid -> fluid.is(Fluids.WATER)) {
        @Override
        protected void onContentsChanged() {
            setChanged();
        }
    };

    private final IItemHandler fuelHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            int burn = burnTicks(stack);
            if (burn <= 0 || stack.hasCraftingRemainingItem()) {
                return stack;
            }
            int fits = Math.min(stack.getCount(), (MAX_FUEL - fuel) / burn);
            if (fits <= 0) {
                return stack;
            }
            if (!simulate) {
                fuel += fits * burn;
                setChanged();
            }
            return stack.copyWithCount(stack.getCount() - fits);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return burnTicks(stack) > 0;
        }
    };

    public ApplianceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.APPLIANCE.get(), pos, state);
    }

    public ApplianceType type() {
        return getBlockState().getBlock() instanceof ApplianceBlock block ? block.type() : ApplianceType.INNER_HEATER;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ApplianceBlockEntity be) {
        be.tickFuel(1);
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0) {
            be.thermalStep(level, 1.0);
        }
    }

    /** Advances the appliance by one second at once. Used by game tests. */
    public void advanceSecond() {
        tickFuel(20);
        thermalStep(level, 1.0);
    }

    private void tickFuel(int ticks) {
        if (!type().isFueled() || fuel <= 0) {
            return;
        }
        // With the stones at their limit the damper closes and the fire smoulders.
        boolean throttled = !Double.isNaN(core) && core >= type().maxCore();
        int use = throttled ? Math.max(1, ticks / 4) : ticks;
        fuel = Math.max(0, fuel - use);
        if (fuel == 0) {
            setChanged();
        }
    }

    private void thermalStep(Level level, double seconds) {
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

        double power = 0.0;
        steamFraction = 0.0;
        switch (type.source()) {
            case FUEL -> power = fuel > 0 ? type.power() : 0.0;
            case ELECTRIC -> power = type.power() * level.getBestNeighborSignal(worldPosition) / 15.0;
            case STEAM -> {
                int signal = level.getBestNeighborSignal(worldPosition);
                if (signal > 0) {
                    steamFraction = signal / 15.0;
                    int water = (int) Math.ceil(type.steamRate() * steamFraction * dt / GRAMS_PER_MB);
                    FluidStack drained = tank.drain(water, FluidTank.FluidAction.EXECUTE);
                    steamFraction *= water == 0 ? 0.0 : (double) drained.getAmount() / water;
                    power = type.power() * steamFraction;
                }
            }
        }
        if (core >= type.maxCore()) {
            power = 0.0;
        }

        double toRoom = type.transfer() * (core - environment);
        core += (power - toRoom) * dt / type.mass();

        boolean burning = type.isFueled() && fuel > 0;
        if (linked) {
            pendingHeat += toRoom * dt;
            pendingVapour += type.steamRate() * steamFraction * dt;
            if (burning && smokesIntoRoom) {
                pendingSmoke += type.smokeRate() * dt;
            }
        } else {
            pendingHeat = 0.0;
            pendingVapour = 0.0;
            pendingSmoke = 0.0;
        }
        drying = burning ? type.drying() : 0.0;

        running = type.isFueled() ? burning : power > 0.0;
        BlockState state = getBlockState();
        if (state.getValue(ApplianceBlock.LIT) != running) {
            level.setBlock(worldPosition, state.setValue(ApplianceBlock.LIT, running), Block.UPDATE_ALL);
        }
        setChanged();
    }

    /** Called every second by the gauge of the room this appliance borders. */
    public void link(BlockPos gaugePos, double roomTemperature, long gameTime, boolean smokesIntoRoom) {
        if (gauge != null && !gauge.equals(gaugePos) && isLinked()) {
            return; // already serving another room this second
        }
        this.gauge = gaugePos.immutable();
        this.roomTemperature = roomTemperature;
        this.linkTime = gameTime;
        this.smokesIntoRoom = smokesIntoRoom;
    }

    public boolean isLinked() {
        return level != null && gauge != null && level.getGameTime() - linkTime <= LINK_TIMEOUT;
    }

    /** Heat (kJ), vapour (g) and smoke produced since the last call; drying is a current rate. */
    public Output drainOutput(BlockPos gaugePos) {
        if (gauge == null || !gauge.equals(gaugePos)) {
            return Output.NONE;
        }
        Output out = new Output(pendingHeat, pendingVapour, pendingSmoke, drying);
        pendingHeat = 0.0;
        pendingVapour = 0.0;
        pendingSmoke = 0.0;
        return out;
    }

    public record Output(double heat, double vapour, double smoke, double drying) {
        public static final Output NONE = new Output(0, 0, 0, 0);
    }

    // ----------------------------------------------------------------------------------------------------------
    // Interaction

    private static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING) * FUEL_EFFICIENCY;
    }

    /** Loads one item of fuel (or as many as fit when sneaking). */
    public void addFuel(ItemStack stack, Player player, InteractionHand hand) {
        int burn = burnTicks(stack);
        if (burn <= 0 || level == null) {
            return;
        }
        int fits = (MAX_FUEL - fuel) / burn;
        if (fits <= 0) {
            player.displayClientMessage(Component.translatable("bathhouse.appliance.fuel_full").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        int count = player.isShiftKeyDown() ? Math.min(fits, stack.getCount()) : 1;
        if (stack.hasCraftingRemainingItem()) {
            count = 1;
        }
        boolean wasCold = fuel <= 0;
        fuel += burn * count;
        if (!player.getAbilities().instabuild) {
            ItemStack remainder = stack.getCraftingRemainingItem();
            stack.shrink(count);
            if (!remainder.isEmpty()) {
                if (stack.isEmpty()) {
                    player.setItemInHand(hand, remainder);
                } else if (!player.addItem(remainder)) {
                    player.drop(remainder, false);
                }
            }
        }
        level.playSound(null, worldPosition, wasCold ? SoundEvents.FIRECHARGE_USE : SoundEvents.WOOD_PLACE,
                SoundSource.BLOCKS, 0.7F, 0.9F + level.random.nextFloat() * 0.2F);
        setChanged();
        player.displayClientMessage(statusLine(), true);
    }

    /**
     * Throws water on the stones. Hot stones turn it into steam, cold stones just get wet.
     *
     * @return whether the water was used
     */
    public boolean throwWater(ServerLevel level, int grams, Player player) {
        if (Double.isNaN(core)) {
            core = Climate.ambientTemperature(level, worldPosition);
        }
        double efficiency = Mth.clamp((core - 100.0) / 100.0, 0.0, 1.0);
        double x = worldPosition.getX() + 0.5;
        double y = worldPosition.getY() + 1.0;
        double z = worldPosition.getZ() + 0.5;
        if (efficiency < 0.05) {
            level.playSound(null, worldPosition, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.5F, 1.2F);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 10, 0.25, 0.05, 0.25, 0.1);
            player.displayClientMessage(Component.translatable("bathhouse.appliance.stones_cold", Math.round(core))
                    .withStyle(ChatFormatting.AQUA), true);
            return true;
        }
        double steam = grams * efficiency;
        core -= steam * LATENT_HEAT / type().mass();
        if (isLinked()) {
            pendingVapour += steam;
        }
        level.playSound(null, worldPosition, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS,
                0.6F + (float) efficiency * 0.4F, 0.8F + level.random.nextFloat() * 0.4F);
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 4 + (int) (steam / 50.0), 0.3, 0.3, 0.3, 0.04);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, x, y, z, 6, 0.3, 0.1, 0.3, 0.02);
        setChanged();
        return true;
    }

    public Component statusLine() {
        ApplianceType type = type();
        MutableComponent line = Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        double coreTemp = Double.isNaN(core) ? 0 : core;
        switch (type.source()) {
            case FUEL -> {
                line.append(Component.translatable("bathhouse.appliance.core", Math.round(coreTemp)));
                line.append(" · ");
                line.append(fuel > 0
                        ? Component.translatable("bathhouse.appliance.fuel", StringUtil.formatTickDuration(fuel, 20f))
                        : Component.translatable("bathhouse.appliance.no_fuel").withStyle(ChatFormatting.RED));
            }
            case ELECTRIC -> {
                int signal = level == null ? 0 : level.getBestNeighborSignal(worldPosition);
                line.append(Component.translatable("bathhouse.appliance.core", Math.round(coreTemp)));
                line.append(" · ");
                line.append(signal > 0
                        ? Component.translatable("bathhouse.appliance.power", Math.round(signal * 100 / 15.0))
                        : Component.translatable("bathhouse.appliance.no_power").withStyle(ChatFormatting.RED));
            }
            case STEAM -> {
                int signal = level == null ? 0 : level.getBestNeighborSignal(worldPosition);
                line.append(Component.translatable("bathhouse.appliance.water",
                        String.format(java.util.Locale.ROOT, "%.1f", tank.getFluidAmount() / 1000.0), TANK_CAPACITY / 1000));
                line.append(" · ");
                if (tank.isEmpty()) {
                    line.append(Component.translatable("bathhouse.appliance.no_water").withStyle(ChatFormatting.RED));
                } else if (signal <= 0) {
                    line.append(Component.translatable("bathhouse.appliance.no_power").withStyle(ChatFormatting.RED));
                } else {
                    line.append(Component.translatable("bathhouse.appliance.power", Math.round(signal * 100 / 15.0)));
                }
            }
        }
        line.append(" · ");
        line.append(isLinked()
                ? Component.translatable("bathhouse.appliance.linked").withStyle(ChatFormatting.GREEN)
                : Component.translatable("bathhouse.appliance.unlinked").withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    // ----------------------------------------------------------------------------------------------------------
    // Accessors

    public double coreTemperature() {
        return core;
    }

    public void setCoreTemperature(double core) {
        this.core = core;
    }

    public int fuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = Mth.clamp(fuel, 0, MAX_FUEL);
        setChanged();
    }

    public boolean running() {
        return running;
    }

    public FluidTank tank() {
        return tank;
    }

    @Nullable
    public IItemHandler fuelHandler() {
        return type().isFueled() ? fuelHandler : null;
    }

    @Nullable
    public FluidTank waterHandler() {
        return type() == ApplianceType.STEAM_GENERATOR ? tank : null;
    }

    // ----------------------------------------------------------------------------------------------------------
    // Persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(core)) {
            tag.putDouble("Core", core);
        }
        tag.putInt("Fuel", fuel);
        if (!tank.isEmpty()) {
            tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core = tag.contains("Core") ? tag.getDouble("Core") : Double.NaN;
        fuel = Mth.clamp(tag.getInt("Fuel"), 0, MAX_FUEL);
        if (tag.contains("Tank")) {
            tank.readFromNBT(registries, tag.getCompound("Tank"));
        }
    }
}
