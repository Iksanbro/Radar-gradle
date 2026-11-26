package com.radar.bathhouse.stove;

import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.registry.ModBlockEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/** Contents of a stove part: heater stones, boiler or tank water, ash. The firebox drives the rest. */
public class StovePartBlockEntity extends BlockEntity {
    public static final int MAX_ASH = 8;
    /** Water this hot (°C) or more counts as hot water for the ladle. */
    public static final double HOT_WATER = 70.0;

    private final List<Item> stones = new ArrayList<>();
    @Nullable
    private final FluidTank water;
    private double waterTemperature = Double.NaN;
    private int ash;

    public StovePartBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STOVE_PART.get(), pos, state);
        int capacity = state.getBlock() instanceof StovePartBlock block ? block.type().waterCapacity() : 0;
        this.water = capacity <= 0 ? null : new FluidTank(capacity, fluid -> fluid.is(Fluids.WATER)) {
            @Override
            protected void onContentsChanged() {
                setChanged();
            }
        };
    }

    public StovePartType type() {
        return getBlockState().getBlock() instanceof StovePartBlock block ? block.type() : StovePartType.CLADDING;
    }

    // ---------------------------------------------------------------------------------------------- stones

    public int stoneCount() {
        return stones.size();
    }

    public int stoneCapacity() {
        return type().chamber().stoneCapacity();
    }

    /** Average stone quality, 1..3; 0 when empty. */
    public double stoneQuality() {
        if (stones.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Item stone : stones) {
            sum += StoneQuality.of(new ItemStack(stone));
        }
        return sum / stones.size();
    }

    /** Puts up to {@code count} stones from the stack into the heater; returns how many went in. */
    public int addStones(ItemStack stack, int count) {
        if (StoneQuality.of(stack) <= 0) {
            return 0;
        }
        int added = Math.min(count, stoneCapacity() - stones.size());
        for (int i = 0; i < added; i++) {
            stones.add(stack.getItem());
        }
        if (added > 0) {
            changed();
        }
        return added;
    }

    public ItemStack removeStone() {
        if (stones.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Item stone = stones.removeLast();
        changed();
        return new ItemStack(stone);
    }

    /** Water on poor stones may crack one of them. */
    public boolean crackPoorStone(RandomSource random) {
        int poor = 0;
        for (Item stone : stones) {
            if (StoneQuality.of(new ItemStack(stone)) == 1) {
                poor++;
            }
        }
        if (poor == 0 || random.nextFloat() >= 0.04F * poor) {
            return false;
        }
        for (int i = stones.size() - 1; i >= 0; i--) {
            if (StoneQuality.of(new ItemStack(stones.get(i))) == 1) {
                stones.remove(i);
                changed();
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------- ash

    public int ash() {
        return ash;
    }

    public boolean ashFull() {
        return ash >= MAX_ASH;
    }

    public void addAsh() {
        if (ash < MAX_ASH) {
            ash++;
            changed();
        }
    }

    public int takeAsh() {
        int taken = ash;
        ash = 0;
        changed();
        return taken;
    }

    // ---------------------------------------------------------------------------------------------- water

    @Nullable
    public FluidTank water() {
        return water;
    }

    public int waterAmount() {
        return water == null ? 0 : water.getFluidAmount();
    }

    public double waterTemperature() {
        if (Double.isNaN(waterTemperature) && level != null) {
            return Climate.ambientTemperature(level, worldPosition);
        }
        return waterTemperature;
    }

    public boolean hotWater() {
        return waterAmount() > 0 && waterTemperature() >= HOT_WATER;
    }

    /** Water tank: the hot flue gases heat the water; otherwise it cools down. */
    public void heatWater(boolean flueHot, double ambient, double seconds) {
        double t = Double.isNaN(waterTemperature) ? ambient : waterTemperature;
        if (flueHot && waterAmount() > 0) {
            t = Math.min(95.0, t + 0.4 * seconds);
        } else {
            t -= Math.min(1.0, 0.01 * seconds) * (t - ambient);
        }
        waterTemperature = t;
        setActive(hotWater());
        setChanged();
    }

    // ---------------------------------------------------------------------------------------------- state

    /** Updates the block's ACTIVE flag (stones present, ash full, boiling, hot water…). */
    public void setActive(boolean active) {
        BlockState state = getBlockState();
        if (level != null && state.hasProperty(StovePartBlock.ACTIVE) && state.getValue(StovePartBlock.ACTIVE) != active) {
            level.setBlock(worldPosition, state.setValue(StovePartBlock.ACTIVE, active), Block.UPDATE_ALL);
        }
    }

    private void changed() {
        StovePartType type = type();
        if (type.chamber().hasStones()) {
            setActive(!stones.isEmpty());
        } else if (type == StovePartType.ASH_PIT) {
            setActive(ashFull());
        }
        setChanged();
    }

    public void dropContents(Level level, BlockPos pos) {
        for (Item stone : stones) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(stone));
        }
        stones.clear();
        if (ash > 0) {
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(Items.BONE_MEAL, ash));
            ash = 0;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!stones.isEmpty()) {
            ListTag list = new ListTag();
            for (Item stone : stones) {
                list.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(stone).toString()));
            }
            tag.put("Stones", list);
        }
        if (water != null && !water.isEmpty()) {
            tag.put("Water", water.writeToNBT(registries, new CompoundTag()));
        }
        if (!Double.isNaN(waterTemperature)) {
            tag.putDouble("WaterTemperature", waterTemperature);
        }
        tag.putInt("Ash", ash);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stones.clear();
        for (Tag entry : tag.getList("Stones", Tag.TAG_STRING)) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getAsString());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                stones.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        if (water != null && tag.contains("Water")) {
            water.readFromNBT(registries, tag.getCompound("Water"));
        }
        waterTemperature = tag.contains("WaterTemperature") ? tag.getDouble("WaterTemperature") : Double.NaN;
        ash = tag.getInt("Ash");
    }
}
