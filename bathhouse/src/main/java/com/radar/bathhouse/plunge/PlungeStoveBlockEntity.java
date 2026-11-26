package com.radar.bathhouse.plunge;

import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.stove.StoveBlockEntity;
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

/** Fuel of a plunge stove. It burns only while the plunge's thermometer asks for heat (an automatic damper). */
public class PlungeStoveBlockEntity extends BlockEntity {
    public static final int MAX_FUEL = 12000;
    private static final int DEMAND_WINDOW = 40;

    private int fuel;
    private long lastDemand = Long.MIN_VALUE / 2;

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

    public PlungeStoveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLUNGE_STOVE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PlungeStoveBlockEntity stove) {
        boolean burning = stove.isBurning();
        if (burning) {
            stove.fuel--;
            if (stove.fuel == 0) {
                stove.setChanged();
            }
        }
        if (state.getValue(PlungeStoveBlock.LIT) != burning && level.getGameTime() % 10 == 0) {
            level.setBlock(pos, state.setValue(PlungeStoveBlock.LIT, burning), Block.UPDATE_ALL);
        }
    }

    /** The thermometer wants heat this second. */
    public void demand(long gameTime) {
        lastDemand = gameTime;
    }

    public boolean isBurning() {
        return fuel > 0 && level != null && level.getGameTime() - lastDemand <= DEMAND_WINDOW;
    }

    /** One second of burning at once (game tests). */
    public void burnSecond() {
        if (isBurning()) {
            fuel = Math.max(0, fuel - 20);
        }
    }

    private static int burnTicks(ItemStack stack) {
        return stack.isEmpty() ? 0 : stack.getBurnTime(RecipeType.SMELTING) * StoveBlockEntity.FUEL_EFFICIENCY;
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
        MutableComponent line = Component.translatable("block.bathhouse.plunge_stove").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        line.append(fuel > 0
                ? Component.translatable("bathhouse.appliance.fuel", StringUtil.formatTickDuration(fuel, 20f))
                : Component.translatable("bathhouse.appliance.no_fuel").withStyle(ChatFormatting.RED));
        if (fuel > 0 && !isBurning()) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("bathhouse.plunge.stove_idle").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    public int fuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = Mth.clamp(fuel, 0, MAX_FUEL);
        setChanged();
    }

    public IItemHandler fuelHandler() {
        return fuelHandler;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Fuel", fuel);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fuel = Mth.clamp(tag.getInt("Fuel"), 0, MAX_FUEL);
    }
}
