package com.radar.bathhouse.plunge;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.BathClassifier;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.registry.ModBlockEntities;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Controller of a plunge basin: finds it, keeps the temperature of its water or shavings, drives stoves and chillers. */
public class PlungeControllerBlockEntity extends BlockEntity {
    /** Plunge stove output, kW, scaled by how many faces touch the basin. */
    public static final double STOVE_POWER = 16.0;
    public static final double CHILLER_POWER = 4.0;
    public static final double MIN_WATER_TEMPERATURE = 0.5;
    /** Above this the basin visibly steams. */
    private static final double STEAM_THRESHOLD = 30.0;

    private double temperature = Double.NaN;
    private boolean herbs;
    @Nullable
    private PlungeScan scan;
    @Nullable
    private PlungeKind kind;
    @Nullable
    private Component problem;
    private int seconds;

    public PlungeControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLUNGE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, PlungeControllerBlockEntity plunge) {
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0) {
            plunge.secondTick(level);
        }
    }

    private void secondTick(Level level) {
        if (scan == null || seconds % 2 == 0) {
            rescan();
        }
        seconds++;
        simulate(1.0);
        if (level instanceof ServerLevel serverLevel && kind != null && kind.heated() && temperature >= STEAM_THRESHOLD
                && scan != null && scan.bounds() != null) {
            var box = scan.bounds();
            serverLevel.sendParticles(ParticleTypes.WHITE_SMOKE, box.getCenter().x, box.maxY - 0.1, box.getCenter().z,
                    3, box.getXsize() * 0.3, 0.05, box.getZsize() * 0.3, 0.01);
        }
        setChanged();
    }

    public void rescan() {
        if (level == null) {
            return;
        }
        scan = PlungeScanner.scan(level, worldPosition, getBlockState().getValue(PlungeThermometerBlock.FACING),
                BathConfig.MAX_PLUNGE_VOLUME.get());
        classify();
        boolean active = kind != null;
        if (active) {
            PlungeRegistry.register(level, worldPosition);
        } else {
            PlungeRegistry.unregister(level, worldPosition);
        }
        BlockState state = getBlockState();
        if (state.getValue(PlungeThermometerBlock.ACTIVE) != active) {
            level.setBlock(worldPosition, state.setValue(PlungeThermometerBlock.ACTIVE, active), Block.UPDATE_ALL);
        }
    }

    private void classify() {
        kind = null;
        problem = null;
        if (scan == null || !scan.valid()) {
            problem = scan == null ? Component.translatable("bathhouse.plunge.problem.no_basin") : scan.problem();
            return;
        }
        int medium = scan.water() + scan.sawdust();
        if (medium == 0) {
            problem = Component.translatable("bathhouse.plunge.problem.empty");
        } else if (scan.water() > 0 && scan.sawdust() > 0) {
            problem = Component.translatable("bathhouse.plunge.problem.mixed");
        } else if (medium * 2 < scan.volume()) {
            problem = Component.translatable("bathhouse.plunge.problem.half_empty", medium, scan.volume());
        } else if (scan.volume() < 2) {
            problem = Component.translatable("bathhouse.plunge.problem.too_small");
        } else if (scan.sawdust() > 0) {
            if (scan.stoves().isEmpty()) {
                problem = Component.translatable("bathhouse.plunge.problem.sawdust_needs_stove");
            } else {
                kind = PlungeKind.SAWDUST;
            }
        } else if (scan.stoves().isEmpty()) {
            kind = PlungeKind.COLD;
        } else if (scan.benches() == 0) {
            problem = Component.translatable("bathhouse.plunge.problem.no_bench");
        } else {
            kind = PlungeKind.HOT;
        }
    }

    public void simulate(double realSeconds) {
        if (level == null) {
            return;
        }
        double dt = realSeconds * BathConfig.SIMULATION_SPEED.get();
        double ambient = Climate.ambientTemperature(level, worldPosition);
        if (Double.isNaN(temperature)) {
            temperature = ambient;
        }
        if (scan == null || !scan.valid() || scan.water() + scan.sawdust() == 0) {
            temperature += Math.min(1.0, 0.05 * dt) * (ambient - temperature);
            return;
        }
        double heat = 0.0;
        boolean wantsHeat = kind != null && kind.heated() && temperature < kind.throttle();
        for (Map.Entry<BlockPos, Integer> entry : scan.stoves().entrySet()) {
            if (level.getBlockEntity(entry.getKey()) instanceof PlungeStoveBlockEntity stove) {
                if (!wantsHeat) {
                    continue; // the damper is shut: no heat reaches the water
                }
                stove.demand(level.getGameTime());
                if (stove.isBurning()) {
                    heat += STOVE_POWER * Math.min(1.0, 0.25 + 0.25 * entry.getValue());
                }
            }
        }
        if (temperature > 2.0) {
            for (BlockPos chiller : scan.chillers()) {
                heat -= CHILLER_POWER * level.getBestNeighborSignal(chiller) / 15.0;
            }
        }
        double capacity = scan.capacity();
        temperature += heat * dt / capacity - Math.min(1.0, scan.conductance() * dt / capacity) * (temperature - ambient);
        if (scan.sawdust() == 0) {
            temperature = Math.max(MIN_WATER_TEMPERATURE, temperature);
        }
    }

    // ------------------------------------------------------------------------------------------- players

    /** Whether the player stands in this basin's water or shavings. */
    public boolean isInMedium(Player player) {
        if (scan == null || !scan.valid() || level == null) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        if (!scan.contains(feet)) {
            return false;
        }
        return PlungeScanner.isMedium(level.getBlockState(feet)) || PlungeScanner.isMedium(level.getBlockState(feet.above()));
    }

    /** How deep the player is in the water or shavings, measured from the feet. */
    public double mediumDepth(Player player) {
        if (level == null) {
            return 0.0;
        }
        BlockPos.MutableBlockPos cursor = player.blockPosition().mutable();
        double top = player.getY();
        for (int i = 0; i < 4; i++) {
            BlockState state = level.getBlockState(cursor);
            FluidState fluid = state.getFluidState();
            if (state.getBlock() instanceof SawdustBlock) {
                top = cursor.getY() + 1.0;
            } else if (fluid.is(FluidTags.WATER)) {
                top = cursor.getY() + fluid.getHeight(level, cursor);
            } else {
                break;
            }
            cursor.move(0, 1, 0);
        }
        return top - player.getY();
    }

    /** Head under the water or in the shavings. */
    public boolean eyeInMedium(Player player) {
        if (level == null) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        BlockPos pos = BlockPos.containing(eye);
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SawdustBlock) {
            return true;
        }
        FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) && eye.y < pos.getY() + fluid.getHeight(level, pos);
    }

    // ------------------------------------------------------------------------------------------- interaction

    public void chill(double energy) {
        if (Double.isNaN(temperature) && level != null) {
            temperature = Climate.ambientTemperature(level, worldPosition);
        }
        double capacity = scan == null ? 20.0 : scan.capacity();
        temperature -= energy / capacity;
        if (scan == null || scan.sawdust() == 0) {
            temperature = Math.max(MIN_WATER_TEMPERATURE, temperature);
        }
        setChanged();
    }

    public Component statusLine() {
        MutableComponent line = Component.translatable("block.bathhouse.plunge_thermometer").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
        if (kind == null) {
            return line.append(problem == null ? Component.literal("?") : problem.copy().withStyle(ChatFormatting.RED));
        }
        double t = temperature();
        line.append(Component.translatable(kind.translationKey()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.translatable("bathhouse.plunge.temperature", Math.round(t), kind.minTemperature(), kind.maxTemperature())
                        .withStyle(kind.inRange(t) ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
        if (herbs) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("bathhouse.plunge.herbs").withStyle(ChatFormatting.GREEN));
        }
        return line;
    }

    public void sendReport(ServerPlayer player) {
        if (scan == null) {
            rescan();
        }
        player.sendSystemMessage(Component.translatable("bathhouse.plunge.report.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        if (scan == null || !scan.valid()) {
            player.sendSystemMessage(Component.literal("✘ ").withStyle(ChatFormatting.RED)
                    .append(problem == null ? Component.literal("?") : problem.copy().withStyle(ChatFormatting.GRAY)));
            return;
        }
        player.sendSystemMessage(Component.translatable("bathhouse.plunge.report.basin", scan.volume(), scan.water(), scan.sawdust(),
                        scan.benches(), scan.stoves().size(), scan.chillers().size(),
                        BathClassifier.fmt(scan.insulatedFraction() * 100.0))
                .withStyle(ChatFormatting.GRAY));
        if (kind == null) {
            player.sendSystemMessage(Component.literal("✘ ").withStyle(ChatFormatting.RED)
                    .append(problem == null ? Component.literal("?") : problem.copy().withStyle(ChatFormatting.GRAY)));
            return;
        }
        player.sendSystemMessage(statusLine());
        player.sendSystemMessage(Component.translatable("bathhouse.plunge.report.rules." + kind.id(),
                kind.idealMin(), kind.idealMax(), BathConfig.COLD_DIP_MIN_SECONDS.get(), BathConfig.COLD_DIP_MAX_SECONDS.get(),
                BathConfig.HOT_SOAK_SECONDS.get()).withStyle(ChatFormatting.DARK_GREEN));
    }

    // ------------------------------------------------------------------------------------------- accessors

    @Nullable
    public PlungeKind kind() {
        return kind;
    }

    @Nullable
    public PlungeScan scan() {
        return scan;
    }

    @Nullable
    public Component problem() {
        return problem;
    }

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

    public boolean hasHerbs() {
        return herbs;
    }

    public void setHerbs(boolean herbs) {
        this.herbs = herbs;
        setChanged();
    }

    @Override
    public void setRemoved() {
        if (level != null) {
            PlungeRegistry.unregister(level, worldPosition);
        }
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(temperature)) {
            tag.putDouble("Temperature", temperature);
        }
        tag.putBoolean("Herbs", herbs);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        temperature = tag.contains("Temperature") ? tag.getDouble("Temperature") : Double.NaN;
        herbs = tag.getBoolean("Herbs");
    }
}
