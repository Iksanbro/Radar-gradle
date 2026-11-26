package com.radar.bathhouse.climate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Decides which bath type (if any) a room is, and explains what is missing. */
public final class BathClassifier {
    /** Above this temperature no bath type is valid: it is simply dangerous. */
    public static final double DANGER_TEMPERATURE = 120.0;

    private BathClassifier() {
    }

    /**
     * @param scan        the last enclosed scan of the room, or null if it was never enclosed
     * @param leaky       true if the latest scan escaped (open door etc.)
     * @param temperature room air temperature, °C
     * @param humidity    relative humidity, %
     * @param smoke       smoke concentration, %
     * @param variant     white / gray / black
     * @param sources     heat sources bordering the room
     */
    public static BathAssessment assess(@Nullable RoomScan scan, boolean leaky, double temperature, double humidity,
                                        double smoke, SmokeVariant variant, Collection<SourceInfo> sources,
                                        int minVolume, int maxVolume) {
        if (scan == null) {
            return BathAssessment.NONE;
        }

        List<Component> general = new ArrayList<>();
        if (scan.duplicateGauge()) {
            general.add(Component.translatable("bathhouse.problem.duplicate"));
        }
        if (leaky) {
            general.add(Component.translatable("bathhouse.problem.leaky", maxVolume));
        }
        if (scan.volume() < minVolume) {
            general.add(Component.translatable("bathhouse.problem.too_small", scan.volume(), minVolume));
        }
        if (temperature > DANGER_TEMPERATURE) {
            general.add(Component.translatable("bathhouse.problem.overheat", fmt(temperature)));
        }

        BathType closest = null;
        List<Component> closestProblems = null;
        for (BathType type : BathType.values()) {
            List<Component> problems = new ArrayList<>(general);
            problems.addAll(typeProblems(type, scan, temperature, humidity, smoke, variant, sources));
            if (problems.isEmpty()) {
                return new BathAssessment(type, variant, type, List.of());
            }
            if (closestProblems == null || problems.size() < closestProblems.size()) {
                closest = type;
                closestProblems = problems;
            }
        }
        return new BathAssessment(null, variant, closest, List.copyOf(closestProblems));
    }

    private static List<Component> typeProblems(BathType type, RoomScan scan, double temperature, double humidity,
                                                double smoke, SmokeVariant variant, Collection<SourceInfo> sources) {
        List<Component> problems = new ArrayList<>();
        if (sources.stream().noneMatch(type.equipment()::matches)) {
            problems.add(Component.translatable("bathhouse.problem.equipment",
                    Component.translatable(type.equipment().translationKey())));
        }
        double wallShare = scan.fraction(type.walls());
        if (wallShare < BathType.REQUIRED_WALL_FRACTION) {
            problems.add(Component.translatable("bathhouse.problem.walls",
                    Component.translatable(type.walls().translationKey()),
                    fmt(wallShare * 100.0), fmt(BathType.REQUIRED_WALL_FRACTION * 100.0)));
        }
        if (!type.allows(variant)) {
            problems.add(Component.translatable("bathhouse.problem.variant",
                    Component.translatable(type.translationKey()), Component.translatable(variant.translationKey())));
        }
        if (temperature < type.minTemperature() || temperature > type.maxTemperature()) {
            problems.add(Component.translatable("bathhouse.problem.temperature",
                    fmt(temperature), type.minTemperature(), type.maxTemperature()));
        }
        if (humidity < type.minHumidity() || humidity > type.maxHumidity()) {
            problems.add(Component.translatable("bathhouse.problem.humidity",
                    fmt(humidity), type.minHumidity(), type.maxHumidity()));
        }
        if (smoke > variant.maxSmoke()) {
            problems.add(Component.translatable("bathhouse.problem.smoke",
                    fmt(smoke), Component.translatable(variant.translationKey()), fmt(variant.maxSmoke())));
        }
        return problems;
    }

    public static String fmt(double value) {
        return Long.toString(Math.round(value));
    }
}
