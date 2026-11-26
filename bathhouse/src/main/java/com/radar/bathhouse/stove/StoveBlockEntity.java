package com.radar.bathhouse.stove;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.climate.HeatSource;
import com.radar.bathhouse.climate.SourceInfo;
import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.session.BathSessionTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * The firebox of a multiblock stove: burns fuel (or draws redstone power), keeps the temperature of the stove's
 * core (firebox + chamber + stones + cladding) and hands heat, steam and smoke to the room's gauge.
 */
public class StoveBlockEntity extends BlockEntity implements HeatSource {
    public static final int FUEL_EFFICIENCY = 4;
    public static final int MAX_FUEL = 24000;
    /** Fuel ticks that leave one portion of ash in the ash pit. */
    public static final int ASH_INTERVAL = 2400;
    public static final double STONE_MASS = 1.0;
    public static final double CLADDING_MASS = 4.0;
    public static final double CLADDING_SOFTENING = 0.03;
    /** Draft without an ash pit (or with a full one) — and with the damper shut while burning. */
    public static final double POOR_DRAFT = 0.6;
    public static final double SMOKE_RATE = 2.0;
    /** Core cooling per gram of water evaporated, kJ. */
    public static final double LATENT_HEAT = 0.6;
    private static final double BOILER_STEAM = 8.0;
    private static final double BOILER_DRAW = 5.0;
    private static final int LINK_TIMEOUT = 60;

    private double core = Double.NaN;
    private int fuel;
    private int ashProgress;
    private boolean running;
    @Nullable
    private StoveStructure.Assembly assembly;

    @Nullable
    private BlockPos gauge;
    private long linkTime = Long.MIN_VALUE / 2;
    private double roomTemperature;
    private double pendingHeat;
    private double pendingVapour;
    private double pendingSmoke;
    private double drying;

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

    public StoveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STOVE.get(), pos, state);
    }

    public FireboxType firebox() {
        return getBlockState().getBlock() instanceof FireboxBlock block ? block.type() : FireboxType.STEEL;
    }

    public StoveStructure.Assembly assembly() {
        if (assembly == null && level != null) {
            assembly = StoveStructure.assemble(level, worldPosition);
        }
        return assembly;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, StoveBlockEntity stove) {
        stove.tickFuel(1);
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0) {
            stove.step(1.0);
        }
    }

    /** One second at once (game tests). */
    public void advanceSecond() {
        tickFuel(20);
        step(1.0);
    }

    private boolean burning() {
        return firebox().burnsFuel() && fuel > 0;
    }

    private void tickFuel(int ticks) {
        if (!burning() || level == null) {
            return;
        }
        boolean throttled = !Double.isNaN(core) && core >= firebox().maxCore();
        int use = Math.min(fuel, throttled ? Math.max(1, ticks / 4) : ticks);
        fuel -= use;
        ashProgress += use;
        if (ashProgress >= ASH_INTERVAL) {
            ashProgress -= ASH_INTERVAL;
            StovePartBlockEntity ashPit = part(assembly() == null ? null : assembly().ashPit());
            if (ashPit != null) {
                ashPit.addAsh();
            }
        }
        if (fuel == 0) {
            setChanged();
        }
    }

    @Nullable
    private StovePartBlockEntity part(@Nullable BlockPos pos) {
        return pos != null && level != null && level.getBlockEntity(pos) instanceof StovePartBlockEntity part ? part : null;
    }

    /** Draft factor: an ash pit that is not full lets the fire breathe. */
    public double draft() {
        if (!firebox().burnsFuel()) {
            return 1.0;
        }
        StoveStructure.Assembly asm = assembly();
        StovePartBlockEntity ashPit = part(asm.ashPit());
        double draft = ashPit != null && !ashPit.ashFull() ? 1.0 : POOR_DRAFT;
        if (burning() && asm.damper() != null && !asm.damperOpen()) {
            draft *= 0.5;
        }
        return draft;
    }

    public double mass() {
        StoveStructure.Assembly asm = assembly();
        StovePartBlockEntity chamber = part(asm.chamberPos());
        int stones = chamber == null ? 0 : chamber.stoneCount();
        return firebox().mass() + asm.chamber().mass() + stones * STONE_MASS + asm.cladding() * CLADDING_MASS;
    }

    public double transfer() {
        StoveStructure.Assembly asm = assembly();
        return firebox().transfer() * asm.chamber().transferFactor() * (1.0 - CLADDING_SOFTENING * asm.cladding());
    }

    private void step(double seconds) {
        if (level == null) {
            return;
        }
        assembly = StoveStructure.assemble(level, worldPosition);
        StoveStructure.Assembly asm = assembly;
        FireboxType type = firebox();
        double dt = seconds * BathConfig.SIMULATION_SPEED.get();
        boolean linked = isLinked();
        double ambient = Climate.ambientTemperature(level, worldPosition);
        double environment = linked ? roomTemperature : ambient;
        if (Double.isNaN(core)) {
            core = environment;
        }

        boolean burning = burning();
        double draft = draft();
        double power;
        if (type.burnsFuel()) {
            power = burning ? type.power() * draft : 0.0;
        } else {
            power = type.power() * level.getBestNeighborSignal(worldPosition) / 15.0;
        }
        if (core >= type.maxCore()) {
            power = 0.0;
        }

        double toRoom = transfer() * (core - environment);
        double flueLoss = !burning && type.burnsFuel() && asm.chimney() && asm.damperOpen() ? 0.02 * (core - ambient) : 0.0;

        double steam = 0.0;
        double boilerDraw = 0.0;
        StovePartBlockEntity chamber = part(asm.chamberPos());
        if (asm.chamber() == ChamberType.BOILER && chamber != null && chamber.water() != null) {
            int need = (int) Math.ceil(BOILER_STEAM / 2.0 * dt);
            boolean boiling = core >= 105.0 && chamber.waterAmount() >= need;
            if (boiling) {
                chamber.water().drain(need, IFluidHandler.FluidAction.EXECUTE);
                steam = BOILER_STEAM * dt;
                boilerDraw = BOILER_DRAW;
            }
            chamber.setActive(boiling);
        }

        core += (power - toRoom - flueLoss - boilerDraw) * dt / mass();

        for (BlockPos tankPos : asm.tanks()) {
            StovePartBlockEntity tank = part(tankPos);
            if (tank != null) {
                tank.heatWater(burning && core > 150.0, ambient, dt);
            }
        }

        boolean backdraft = burning && asm.damper() != null && !asm.damperOpen();
        if (linked) {
            pendingHeat += toRoom * dt;
            pendingVapour += steam;
            if (burning && (!asm.chimney() || backdraft)) {
                pendingSmoke += SMOKE_RATE * (draft < 1.0 ? 1.5 : 1.0) * dt;
            }
        } else {
            pendingHeat = 0.0;
            pendingVapour = 0.0;
            pendingSmoke = 0.0;
        }
        drying = burning ? type.drying() : 0.0;

        running = type.burnsFuel() ? burning : power > 0.0;
        BlockState state = getBlockState();
        if (state.getValue(FireboxBlock.LIT) != running) {
            level.setBlock(worldPosition, state.setValue(FireboxBlock.LIT, running), Block.UPDATE_ALL);
        }
        setChanged();
    }

    // ------------------------------------------------------------------------------------------- HeatSource

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
        Output out = new Output(pendingHeat, pendingVapour, pendingSmoke, drying);
        pendingHeat = 0.0;
        pendingVapour = 0.0;
        pendingSmoke = 0.0;
        return out;
    }

    @Override
    public SourceInfo info() {
        if (level != null) {
            assembly = StoveStructure.assemble(level, worldPosition); // parts may have changed since the last step
        }
        StoveStructure.Assembly asm = assembly();
        StovePartBlockEntity chamber = part(asm.chamberPos());
        int stones = chamber == null ? 0 : chamber.stoneCount();
        double quality = chamber == null ? 0.0 : chamber.stoneQuality();
        return new SourceInfo(SourceInfo.Kind.STOVE, firebox(), asm.chamber(), asm.ashPit() != null, asm.chimney(),
                asm.cladding(), stones, asm.chamber().stoneCapacity(), quality, running,
                firebox().burnsFuel() && !asm.chimney());
    }

    // ------------------------------------------------------------------------------------------- interaction

    private static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING) * FUEL_EFFICIENCY;
    }

    /** Loads fuel; directly through the door (not through a fuel tunnel) a puff of smoke enters the room. */
    public void addFuel(ItemStack stack, Player player, InteractionHand hand, boolean viaTunnel) {
        int burn = burnTicks(stack);
        if (burn <= 0 || level == null || !firebox().burnsFuel()) {
            return;
        }
        int fits = (MAX_FUEL - fuel) / burn;
        if (fits <= 0) {
            player.displayClientMessage(Component.translatable("bathhouse.appliance.fuel_full").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        int count = player.isShiftKeyDown() && !stack.hasCraftingRemainingItem() ? Math.min(fits, stack.getCount()) : 1;
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
        if (!viaTunnel && isLinked()) {
            pendingSmoke += 1.5;
        }
        level.playSound(null, worldPosition, wasCold ? SoundEvents.FIRECHARGE_USE : SoundEvents.WOOD_PLACE, SoundSource.BLOCKS,
                0.7F, 0.9F + level.random.nextFloat() * 0.2F);
        setChanged();
        player.displayClientMessage(statusLine(), true);
    }

    /** Temperature of the stones of this stove's heater. */
    public double stoneTemperature() {
        double environment = isLinked() ? roomTemperature : (level == null ? 15.0 : Climate.ambientTemperature(level, worldPosition));
        double c = Double.isNaN(core) ? environment : core;
        return environment + assembly().chamber().stoneHeat() * (c - environment);
    }

    /**
     * Throws water on the heater stones.
     *
     * @return whether the water was used
     */
    public boolean throwWater(ServerLevel level, StovePartBlockEntity heater, int grams, boolean hot, Player player) {
        if (heater.stoneCount() == 0) {
            player.displayClientMessage(Component.translatable("bathhouse.stove.no_stones").withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        if (Double.isNaN(core)) {
            core = Climate.ambientTemperature(level, worldPosition);
        }
        double stones = stoneTemperature();
        double efficiency = Mth.clamp((stones - 100.0) / 150.0 + (hot ? 0.25 : 0.0), 0.0, 1.0);
        BlockPos at = heater.getBlockPos();
        double x = at.getX() + 0.5;
        double y = at.getY() + 1.0;
        double z = at.getZ() + 0.5;
        if (efficiency < 0.05) {
            level.playSound(null, at, SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.5F, 1.2F);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, 10, 0.25, 0.05, 0.25, 0.1);
            player.displayClientMessage(Component.translatable("bathhouse.appliance.stones_cold", Math.round(stones))
                    .withStyle(ChatFormatting.AQUA), true);
            return true;
        }
        double steam = grams * efficiency;
        core -= steam * LATENT_HEAT * (hot ? 0.6 : 1.0) / mass();
        if (isLinked()) {
            pendingVapour += steam;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            BathSessionTracker.recordThrow(serverPlayer, efficiency >= 0.95);
        }
        level.playSound(null, at, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.6F + (float) efficiency * 0.4F,
                0.8F + level.random.nextFloat() * 0.4F);
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.2, z, 4 + (int) (steam / 50.0), 0.3, 0.3, 0.3, 0.04);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, x, y, z, 6, 0.3, 0.1, 0.3, 0.02);
        if (!hot && heater.crackPoorStone(level.random)) {
            level.playSound(null, at, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1.0F, 0.7F);
            player.displayClientMessage(Component.translatable("bathhouse.stove.stone_cracked").withStyle(ChatFormatting.RED), false);
        }
        setChanged();
        return true;
    }

    public Component statusLine() {
        StoveStructure.Assembly asm = assembly();
        FireboxType type = firebox();
        MutableComponent line = Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD);
        if (asm.chamber() != ChamberType.NONE) {
            line.append(Component.literal(" + ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable(asm.chamber().translationKey()).withStyle(ChatFormatting.GOLD));
        }
        line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        line.append(Component.translatable("bathhouse.appliance.core", Math.round(Double.isNaN(core) ? 0 : core)));
        sep(line);
        if (type.burnsFuel()) {
            line.append(fuel > 0
                    ? Component.translatable("bathhouse.appliance.fuel", StringUtil.formatTickDuration(fuel, 20f))
                    : Component.translatable("bathhouse.appliance.no_fuel").withStyle(ChatFormatting.RED));
            sep(line);
            StovePartBlockEntity ashPit = part(asm.ashPit());
            line.append(ashPit == null
                    ? Component.translatable("bathhouse.stove.no_ash_pit").withStyle(ChatFormatting.RED)
                    : Component.translatable("bathhouse.stove.ash", ashPit.ash(), StovePartBlockEntity.MAX_ASH)
                    .withStyle(ashPit.ashFull() ? ChatFormatting.RED : ChatFormatting.GRAY));
            sep(line);
            line.append(asm.chimney()
                    ? Component.translatable("bathhouse.stove.chimney").withStyle(ChatFormatting.GRAY)
                    : Component.translatable("bathhouse.stove.no_chimney").withStyle(ChatFormatting.DARK_GRAY));
            if (asm.damper() != null && !asm.damperOpen()) {
                sep(line);
                line.append(Component.translatable("bathhouse.stove.damper_closed").withStyle(burning() ? ChatFormatting.RED : ChatFormatting.YELLOW));
            }
        } else {
            int signal = level == null ? 0 : level.getBestNeighborSignal(worldPosition);
            line.append(signal > 0
                    ? Component.translatable("bathhouse.appliance.power", Math.round(signal * 100 / 15.0))
                    : Component.translatable("bathhouse.appliance.no_power").withStyle(ChatFormatting.RED));
        }
        if (asm.cladding() > 0) {
            sep(line);
            line.append(Component.translatable("bathhouse.stove.cladding", asm.cladding()).withStyle(ChatFormatting.GRAY));
        }
        sep(line);
        line.append(isLinked()
                ? Component.translatable("bathhouse.appliance.linked").withStyle(ChatFormatting.GREEN)
                : Component.translatable("bathhouse.appliance.unlinked").withStyle(ChatFormatting.DARK_GRAY));
        return line;
    }

    private static void sep(MutableComponent line) {
        line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
    }

    // ------------------------------------------------------------------------------------------- accessors

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

    @Nullable
    public IItemHandler fuelHandler() {
        return firebox().burnsFuel() ? fuelHandler : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(core)) {
            tag.putDouble("Core", core);
        }
        tag.putInt("Fuel", fuel);
        tag.putInt("AshProgress", ashProgress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        core = tag.contains("Core") ? tag.getDouble("Core") : Double.NaN;
        fuel = Mth.clamp(tag.getInt("Fuel"), 0, MAX_FUEL);
        ashProgress = tag.getInt("AshProgress");
    }
}
