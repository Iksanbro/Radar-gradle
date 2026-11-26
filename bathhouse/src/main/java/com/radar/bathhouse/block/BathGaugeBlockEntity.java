package com.radar.bathhouse.block;

import com.radar.bathhouse.BathConfig;
import com.radar.bathhouse.climate.BathAssessment;
import com.radar.bathhouse.climate.BathClassifier;
import com.radar.bathhouse.climate.BathQuality;
import com.radar.bathhouse.climate.BathType;
import com.radar.bathhouse.climate.Climate;
import com.radar.bathhouse.climate.HeatSource;
import com.radar.bathhouse.climate.RoomScan;
import com.radar.bathhouse.climate.RoomScanner;
import com.radar.bathhouse.climate.SmokeVariant;
import com.radar.bathhouse.climate.SourceInfo;
import com.radar.bathhouse.climate.WallMaterial;
import com.radar.bathhouse.registry.ModBlockEntities;
import com.radar.bathhouse.session.BathRewards;
import com.radar.bathhouse.session.BathSessionTracker;
import com.radar.bathhouse.stove.ChamberType;
import com.radar.bathhouse.stove.StoneQuality;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * The room controller. Once a second it:
 * <ol>
 *     <li>re-scans the room every other second (flood fill from the gauge),</li>
 *     <li>collects heat, vapour and smoke from the appliances bordering the room,</li>
 *     <li>updates air temperature, absolute humidity and smoke,</li>
 *     <li>classifies the room as one of the {@link BathType}s and its {@link SmokeVariant},</li>
 *     <li>advances the bath session of every player inside.</li>
 * </ol>
 */
public class BathGaugeBlockEntity extends BlockEntity {
    private static final double BASE_AIR_EXCHANGE = 0.003;
    private static final double BASE_SMOKE_EXCHANGE = 0.002;
    private static final double LEAK_AIR_EXCHANGE = 0.05;
    private static final double LEAK_SMOKE_EXCHANGE = 0.15;

    private double temperature = Double.NaN;
    private double vapour = Double.NaN;
    private double smoke;

    @Nullable
    private RoomScan scan;
    private boolean leaky = true;
    private final Map<BlockPos, SourceInfo> sources = new LinkedHashMap<>();
    private SmokeVariant variant = SmokeVariant.WHITE;
    private BathAssessment assessment = BathAssessment.NONE;
    private int seconds;
    private int lastSignal = -1;

    public BathGaugeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BATH_GAUGE.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, BathGaugeBlockEntity gauge) {
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), 20)) % 20 == 0 && level instanceof ServerLevel serverLevel) {
            gauge.secondTick(serverLevel);
        }
    }

    private void secondTick(ServerLevel level) {
        if (scan == null || seconds % 2 == 0) {
            rescan();
        }
        seconds++;
        simulate(1.0);
        reassess();
        visitPlayers(level);
        spawnAmbientParticles(level);
        updateBlockState();
        setChanged();
    }

    // ----------------------------------------------------------------------------------------------------------
    // Room

    public void rescan() {
        if (level == null) {
            return;
        }
        RoomScan fresh = RoomScanner.scan(level, worldPosition, BathConfig.MAX_ROOM_VOLUME.get());
        if (fresh.enclosed()) {
            scan = fresh;
            leaky = false;
        } else {
            leaky = true;
        }
        refreshAppliances();
    }

    private void refreshAppliances() {
        sources.clear();
        if (scan == null || level == null) {
            variant = SmokeVariant.WHITE;
            return;
        }
        boolean smoky = false;
        for (BlockPos pos : scan.sources()) {
            if (level.getBlockEntity(pos) instanceof HeatSource source) {
                SourceInfo info = source.info();
                sources.put(pos, info);
                smoky |= info.smoky();
            }
        }
        if (!smoky) {
            variant = SmokeVariant.WHITE;
        } else {
            variant = scan.draftFlues().isEmpty() ? SmokeVariant.BLACK : SmokeVariant.GRAY;
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Climate

    public void simulate(double realSeconds) {
        if (level == null) {
            return;
        }
        double dt = realSeconds * BathConfig.SIMULATION_SPEED.get();
        double ambient = Climate.ambientTemperature(level, worldPosition);
        double ambientVapour = Climate.ambientVapour(ambient);
        if (Double.isNaN(temperature)) {
            temperature = ambient;
        }
        if (Double.isNaN(vapour)) {
            vapour = ambientVapour;
        }

        double heat = 0.0;
        double steam = 0.0;
        double smokeIn = 0.0;
        double drying = 0.0;
        if (scan != null && !scan.duplicateGauge()) { // only the room's main gauge runs the appliances
            for (BlockPos pos : scan.sources()) {
                if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof HeatSource source) {
                    source.link(worldPosition, temperature, level.getGameTime());
                    HeatSource.Output out = source.drainOutput(worldPosition);
                    heat += out.heat();
                    steam += out.vapour();
                    smokeIn += out.smoke();
                    drying += out.drying();
                }
            }
        }

        double volume = scan == null ? Climate.REFERENCE_VOLUME : Math.max(1, scan.volume());
        double conductance = scan == null ? 0.5 : scan.conductance();
        double capacity = scan == null ? 20.0 : scan.heatCapacity();
        double airExchange = BASE_AIR_EXCHANGE + drying + (scan == null ? 0.0 : scan.vapourVentilation());
        double smokeExchange = BASE_SMOKE_EXCHANGE + (scan == null ? 0.0 : scan.smokeVentilation());
        if (leaky || scan == null) {
            conductance = conductance * 3.0 + 0.3;
            airExchange += LEAK_AIR_EXCHANGE;
            smokeExchange += LEAK_SMOKE_EXCHANGE;
        }

        temperature += heat / capacity - Math.min(1.0, conductance * dt / capacity) * (temperature - ambient);
        vapour += steam / volume - Math.min(1.0, airExchange * dt) * (vapour - ambientVapour);
        vapour = Mth.clamp(vapour, 0.0, Climate.saturation(temperature)); // the rest condenses on the walls
        smoke += smokeIn * Climate.REFERENCE_VOLUME / volume - Math.min(1.0, smokeExchange * dt) * smoke;
        smoke = Mth.clamp(smoke, 0.0, 100.0);
    }

    public void reassess() {
        if (level != null && scan != null) {
            // stones, fuel and power change between scans
            for (BlockPos pos : List.copyOf(sources.keySet())) {
                if (level.getBlockEntity(pos) instanceof HeatSource source) {
                    sources.put(pos, source.info());
                }
            }
        }
        assessment = BathClassifier.assess(scan, leaky, temperature(), humidity(), smoke, variant, sources.values(),
                BathConfig.MIN_ROOM_VOLUME.get(), BathConfig.MAX_ROOM_VOLUME.get());
    }

    // ----------------------------------------------------------------------------------------------------------
    // Players

    private void visitPlayers(ServerLevel level) {
        if (scan == null || scan.bounds() == null) {
            return;
        }
        AABB area = scan.bounds().inflate(0.5);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, area)) {
            if (!player.isSpectator() && isInside(player)) {
                BathSessionTracker.visit(player, this);
            }
        }
    }

    public boolean isInside(Player player) {
        return scan != null && (scan.contains(player.blockPosition()) || scan.contains(BlockPos.containing(player.getEyePosition())));
    }

    private void spawnAmbientParticles(ServerLevel level) {
        if (scan == null || leaky) {
            return;
        }
        long[] cells = scan.interiorArray();
        if (cells.length == 0) {
            return;
        }
        int smokePuffs = (int) (smoke / 15.0);
        for (int i = 0; i < smokePuffs; i++) {
            BlockPos cell = BlockPos.of(cells[level.random.nextInt(cells.length)]);
            level.sendParticles(smoke > 50 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, 2, 0.3, 0.3, 0.3, 0.005);
        }
        if (humidity() > 75.0 && temperature > 35.0) {
            for (int i = 0; i < 2; i++) {
                BlockPos cell = BlockPos.of(cells[level.random.nextInt(cells.length)]);
                level.sendParticles(ParticleTypes.WHITE_SMOKE, cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5,
                        3, 0.35, 0.35, 0.35, 0.01);
            }
        }
    }

    private void updateBlockState() {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof BathGaugeBlock && state.getValue(BathGaugeBlock.ACTIVE) != assessment.ready()) {
            level.setBlock(worldPosition, state.setValue(BathGaugeBlock.ACTIVE, assessment.ready()), Block.UPDATE_ALL);
        }
        int signal = comparatorSignal();
        if (signal != lastSignal) {
            lastSignal = signal;
            level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    }

    public int comparatorSignal() {
        return Double.isNaN(temperature) ? 0 : Mth.clamp((int) Math.round(temperature / 8.0), 0, 15);
    }

    // ----------------------------------------------------------------------------------------------------------
    // Report

    public void sendReport(ServerPlayer player) {
        if (scan == null || seconds == 0) {
            rescan();
            simulate(0.0);
            reassess();
        }
        player.sendSystemMessage(Component.translatable("bathhouse.report.header").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        if (scan == null) {
            player.sendSystemMessage(Component.translatable("bathhouse.report.no_room", BathConfig.MAX_ROOM_VOLUME.get())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        MutableComponent room = Component.translatable("bathhouse.report.room", scan.volume()).withStyle(ChatFormatting.GRAY);
        for (WallMaterial material : WallMaterial.values()) {
            if (scan.faces(material) > 0) {
                room.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
                room.append(Component.translatable("bathhouse.report.material", Component.translatable(material.translationKey()),
                        BathClassifier.fmt(scan.fraction(material) * 100.0)).withStyle(ChatFormatting.GRAY));
            }
        }
        if (!scan.draftFlues().isEmpty()) {
            room.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            room.append(Component.translatable("bathhouse.report.flues", scan.draftFlues().size()).withStyle(ChatFormatting.GRAY));
        }
        if (!scan.openVents().isEmpty() || !scan.closedVents().isEmpty()) {
            room.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            room.append(Component.translatable("bathhouse.report.vents", scan.openVents().size(),
                    scan.openVents().size() + scan.closedVents().size()).withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(room);

        player.sendSystemMessage(Component.translatable("bathhouse.report.climate",
                        BathClassifier.fmt(temperature()), BathClassifier.fmt(humidity()), BathClassifier.fmt(smoke),
                        BathClassifier.fmt(level == null ? 0 : Climate.ambientTemperature(level, worldPosition)))
                .withStyle(ChatFormatting.WHITE));

        if (sources.isEmpty()) {
            player.sendSystemMessage(Component.translatable("bathhouse.report.no_appliances").withStyle(ChatFormatting.YELLOW));
        }
        for (Map.Entry<BlockPos, SourceInfo> entry : sources.entrySet()) {
            player.sendSystemMessage(Component.literal(" • ").withStyle(ChatFormatting.DARK_GRAY).append(describe(entry.getKey(), entry.getValue())));
        }

        player.sendSystemMessage(Component.translatable("bathhouse.report.variant",
                        Component.translatable(variant.translationKey()).withStyle(variant.color()),
                        Component.translatable(variant.translationKey() + ".hint"))
                .withStyle(ChatFormatting.GRAY));

        BathType type = assessment.type();
        if (type != null) {
            player.sendSystemMessage(Component.translatable("bathhouse.report.ready",
                            Component.translatable(type.translationKey()).withStyle(type.color()),
                            Component.translatable(variant.translationKey()).withStyle(variant.color()))
                    .withStyle(ChatFormatting.GREEN));
            List<BathQuality.Criterion> criteria = bathCriteria(type);
            player.sendSystemMessage(Component.translatable("bathhouse.report.bath_quality").withStyle(ChatFormatting.GRAY)
                    .append(BathQuality.line(criteria, variant)));
            player.sendSystemMessage(Component.translatable("bathhouse.report.session_hint." + type.id(),
                    Math.round(type.idealMinTemperature()), Math.round(type.idealMaxTemperature()),
                    Math.round(type.idealMinHumidity()), Math.round(type.idealMaxHumidity())).withStyle(ChatFormatting.DARK_GRAY));
            BathSessionTracker.Session session = BathSessionTracker.active(player);
            if (session != null && session.type() == type) {
                player.sendSystemMessage(Component.translatable("bathhouse.report.session").withStyle(ChatFormatting.GRAY)
                        .append(BathQuality.line(session.criteria(), SmokeVariant.WHITE)));
            }
            player.sendSystemMessage(Component.translatable("bathhouse.report.stay", BathConfig.SESSION_SECONDS.get(),
                    BathRewards.describeParts(type, variant)).withStyle(ChatFormatting.DARK_GREEN));
        } else {
            BathType closest = assessment.closest();
            player.sendSystemMessage(closest == null
                    ? Component.translatable("bathhouse.report.not_ready").withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("bathhouse.report.closest",
                    Component.translatable(closest.translationKey()).withStyle(closest.color())).withStyle(ChatFormatting.YELLOW));
            for (Component problem : assessment.problems()) {
                player.sendSystemMessage(Component.literal("   ✘ ").withStyle(ChatFormatting.RED).append(problem.copy().withStyle(ChatFormatting.GRAY)));
            }
            if (closest != null && BathConfig.FAILED_STEAM_DEBUFFS.get() && BathSessionTracker.isFailedAttempt(assessment, this)) {
                int parts = Math.max(1, Math.min(3, assessment.problems().size()));
                player.sendSystemMessage(Component.translatable("bathhouse.report.fail_warning",
                        BathConfig.FAILED_STEAM_SECONDS.get(), Component.translatable(closest.translationKey()), parts)
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private Component describe(BlockPos pos, SourceInfo info) {
        if (info.kind() != SourceInfo.Kind.STOVE || info.firebox() == null) {
            String key = info.kind() == SourceInfo.Kind.STEAM_GENERATOR ? "block.bathhouse.steam_generator" : "block.bathhouse.warm_lounger";
            MutableComponent line = Component.translatable(key).withStyle(ChatFormatting.GOLD);
            if (info.running()) {
                line.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY))
                        .append(Component.translatable("bathhouse.report.running").withStyle(ChatFormatting.GREEN));
            }
            return line;
        }
        MutableComponent line = Component.translatable(info.firebox().translationKey()).withStyle(ChatFormatting.GOLD);
        if (info.chamber() != ChamberType.NONE) {
            line.append(Component.literal(" + ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable(info.chamber().translationKey()).withStyle(ChatFormatting.GOLD));
        }
        line.append(Component.literal(" — ").withStyle(ChatFormatting.DARK_GRAY));
        MutableComponent parts = Component.empty();
        if (info.woodFired()) {
            parts.append(mark(info.ashPit(), "bathhouse.report.part.ash_pit")).append(" ");
            parts.append(mark(info.chimney(), "bathhouse.report.part.chimney")).append(" ");
        }
        if (info.chamber().hasStones()) {
            parts.append(Component.translatable("bathhouse.stove.stones", info.stones(), info.stoneCapacity(),
                    Component.translatable(StoneQuality.translationKey(info.stoneQuality()))).withStyle(ChatFormatting.GRAY)).append(" ");
        }
        if (info.cladding() > 0) {
            parts.append(Component.translatable("bathhouse.stove.cladding", info.cladding()).withStyle(ChatFormatting.GRAY)).append(" ");
        }
        if (level != null && level.getBlockEntity(pos) instanceof com.radar.bathhouse.stove.StoveBlockEntity stove) {
            double core = stove.coreTemperature();
            parts.append(Component.translatable("bathhouse.appliance.core", Double.isNaN(core) ? "?" : BathClassifier.fmt(core))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (info.running()) {
            parts.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("bathhouse.report.running").withStyle(ChatFormatting.GREEN));
        }
        if (info.smoky()) {
            parts.append(Component.literal(", ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatable("bathhouse.report.smoky").withStyle(ChatFormatting.DARK_GRAY));
        }
        return line.append(parts);
    }

    private static Component mark(boolean ok, String key) {
        return Component.literal(ok ? "✔" : "✘").withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY));
    }

    /** The bath criteria of the steam level for the given type in this room right now. */
    public List<BathQuality.Criterion> bathCriteria(BathType type) {
        return scan == null ? List.of() : BathQuality.bathCriteria(type, scan, sources.values());
    }

    public Collection<SourceInfo> sources() {
        return sources.values();
    }

    // ----------------------------------------------------------------------------------------------------------
    // Accessors (also used by game tests)

    public double temperature() {
        return Double.isNaN(temperature) ? 0.0 : temperature;
    }

    public double humidity() {
        if (Double.isNaN(temperature) || Double.isNaN(vapour)) {
            return 0.0;
        }
        return Climate.relativeHumidity(temperature, vapour);
    }

    public double smoke() {
        return smoke;
    }

    /** Sets the room climate directly (relative humidity in percent). */
    public void setClimate(double temperature, double humidity, double smoke) {
        this.temperature = temperature;
        this.vapour = Climate.saturation(temperature) * humidity / 100.0;
        this.smoke = smoke;
    }

    @Nullable
    public RoomScan scan() {
        return scan;
    }

    public boolean leaky() {
        return leaky;
    }

    public SmokeVariant variant() {
        return variant;
    }

    public BathAssessment assessment() {
        return assessment;
    }

    // ----------------------------------------------------------------------------------------------------------
    // Persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!Double.isNaN(temperature)) {
            tag.putDouble("Temperature", temperature);
        }
        if (!Double.isNaN(vapour)) {
            tag.putDouble("Vapour", vapour);
        }
        tag.putDouble("Smoke", smoke);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        temperature = tag.contains("Temperature") ? tag.getDouble("Temperature") : Double.NaN;
        vapour = tag.contains("Vapour") ? tag.getDouble("Vapour") : Double.NaN;
        smoke = tag.getDouble("Smoke");
    }
}
