package com.radar.bathhouse.block;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.registry.ModBlockEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** Temperature of a plunge's water or shavings, its built-in stove and herbs. */
public class PlungeBlockEntity extends BlockEntity {
    public static final int MAX_FUEL = 12000;
    /** Above this the tub visibly steams. */
    private static final double WARM_THRESHOLD = 30.0;
    private static final double MIN_WATER_TEMPERATURE = 0.5;

    private double temperature = Double.NaN;
    private int fuel;
    private boolean heating;
    private boolean herbs;

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

    public PlungeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLUNGE.get(), pos, state);
    }

    public PlungeType type() {
        return getBlockState().getBlock() instanceof PlungeBlock block ? block.type() : PlungeType.COLD;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PlungeBlockEntity plunge) {
        if (plunge.heating && plunge.fuel > 0) {
            plunge.fuel--;
        }
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0) {
            plunge.step(1.0);
        }
    }

    /** Advances the plunge by one second at once. Used by game tests. */
    public void advanceSecond() {
        if (heating) {
            fuel = Math.max(0, fuel - 20);
        }
        step(1.0);
    }

    private void step(double seconds) {
        if (level == null) {
            return;
        }
        PlungeType type = type();
        double ambient = Climate.ambientTemperature(level, worldPosition);
        int content = contentLevel();
        if (Double.isNaN(temperature) || content == 0) {
            temperature = ambient;
        }
        double dt = seconds * BathConfig.SIMULATION_SPEED.get();
        heating = type.heated() && content > 0 && fuel > 0 && temperature < type.throttle();
        double power = heating ? type.power() : 0.0;
        double capacity = type.capacity() * Math.max(1, content) / PlungeType.MAX_LEVEL;
        temperature += (power - type.loss() * (temperature - ambient)) * dt / capacity;
        if (type.medium() == PlungeType.Medium.WATER) {
            temperature = Math.max(MIN_WATER_TEMPERATURE, temperature);
        }

        BlockState state = getBlockState();
        boolean warm = content > 0 && temperature >= WARM_THRESHOLD;
        if (state.getValue(PlungeBlock.LIT) != heating || state.getValue(PlungeBlock.WARM) != warm) {
            level.setBlock(worldPosition, state.setValue(PlungeBlock.LIT, heating).setValue(PlungeBlock.WARM, warm), Block.UPDATE_ALL);
        }
        setChanged();
    }

    // ----------------------------------------------------------------------------------------------------------
    // Content

    public int contentLevel() {
        BlockState state = getBlockState();
        return state.hasProperty(PlungeBlock.LEVEL) ? state.getValue(PlungeBlock.LEVEL) : 0;
    }

    /** Adds water or shavings at outside temperature, mixing with what is already there. */
    public void addContent(int amount) {
        if (level == null) {
            return;
        }
        int before = contentLevel();
        int after = Mth.clamp(before + amount, 0, PlungeType.MAX_LEVEL);
        if (after == before) {
            return;
        }
        double ambient = Climate.ambientTemperature(level, worldPosition);
        if (Double.isNaN(temperature) || before == 0) {
            temperature = ambient;
        } else {
            temperature = (temperature * before + ambient * (after - before)) / after;
        }
        setLevel(after);
    }

    public void removeContent(int amount) {
        int after = Mth.clamp(contentLevel() - amount, 0, PlungeType.MAX_LEVEL);
        if (after == 0) {
            herbs = false;
        }
        setLevel(after);
    }

    private void setLevel(int contentLevel) {
        if (level != null) {
            level.setBlock(worldPosition, getBlockState().setValue(PlungeBlock.LEVEL, contentLevel), Block.UPDATE_ALL);
        }
        setChanged();
    }

    public void chill(double degrees) {
        if (Double.isNaN(temperature) && level != null) {
            temperature = Climate.ambientTemperature(level, worldPosition);
        }
        temperature = Math.max(MIN_WATER_TEMPERATURE, temperature - degrees * PlungeType.MAX_LEVEL / Math.max(1, contentLevel()));
        setChanged();
    }

    private static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING) * ApplianceBlockEntity.FUEL_EFFICIENCY;
    }

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
        int count = player.isShiftKeyDown() && !stack.hasCraftingRemainingItem() ? Math.min(fits, stack.getCount()) : 1;
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
        level.playSound(null, worldPosition, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.0F);
        setChanged();
        player.displayClientMessage(statusLine(), true);
    }

    public Component statusLine() {
        PlungeType type = type();
        MutableComponent line = Component.translatable(type.translationKey()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        int content = contentLevel();
        if (content == 0) {
            return line.append(Component.translatable(type.medium() == PlungeType.Medium.WATER
                    ? "bathhouse.plunge.empty_water" : "bathhouse.plunge.empty_sawdust").withStyle(ChatFormatting.RED));
        }
        double t = temperature();
        line.append(Component.translatable("bathhouse.plunge.temperature", Math.round(t), type.minTemperature(), type.maxTemperature())
                .withStyle(type.inRange(t) ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
        line.append(Component.translatable("bathhouse.plunge.level", content, PlungeType.MAX_LEVEL)
                .withStyle(content == PlungeType.MAX_LEVEL && type.heated() ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
        if (type.heated()) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(fuel > 0
                    ? Component.translatable("bathhouse.appliance.fuel", StringUtil.formatTickDuration(fuel, 20f)).withStyle(ChatFormatting.GRAY)
                    : Component.translatable("bathhouse.appliance.no_fuel").withStyle(ChatFormatting.RED));
        }
        if (herbs) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            line.append(Component.translatable("bathhouse.plunge.herbs").withStyle(ChatFormatting.GREEN));
        }
        return line;
    }

    // ----------------------------------------------------------------------------------------------------------
    // Accessors

    public double temperature() {
        if (Double.isNaN(temperature)) {
            return level == null ? 15.0 : Climate.ambientTemperature(level, worldPosition);
        }
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
        setChanged();
    }

    public int fuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = Mth.clamp(fuel, 0, MAX_FUEL);
        setChanged();
    }

    public boolean hasHerbs() {
        return herbs;
    }

    public void setHerbs(boolean herbs) {
        this.herbs = herbs;
        setChanged();
    }

    @Nullable
    public IItemHandler fuelHandler() {
        return type().heated() ? fuelHandler : null;
    }

    // ----------------------------------------------------------------------------------------------------------
    // Persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(temperature)) {
            tag.putDouble("Temperature", temperature);
        }
        tag.putInt("Fuel", fuel);
        tag.putBoolean("Herbs", herbs);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        temperature = tag.contains("Temperature") ? tag.getDouble("Temperature") : Double.NaN;
        fuel = Mth.clamp(tag.getInt("Fuel"), 0, MAX_FUEL);
        herbs = tag.getBoolean("Herbs");
    }
}
