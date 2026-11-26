package com.radar.bathhouse.stove;

/**
 * What sits directly on the firebox.
 * <ul>
 *     <li>NONE — a bare stove: radiates harshly, no steam.</li>
 *     <li>CLOSED — inner (closed) stone chamber: stones sit in the flame path and get very hot; light, fine steam;
 *     soft heat for the room. The heart of a steam lodge.</li>
 *     <li>OPEN — outer (open) stone basket on top: stones are cooler but the room heats fast. Ember rooms.</li>
 *     <li>BOILER — a boiler that turns water into steam: a wood-fired source for mist halls.</li>
 * </ul>
 */
public enum ChamberType {
    NONE(0, 1.6, 0, 1.0),
    CLOSED(6, 0.75, 12, 1.0),
    OPEN(3, 1.0, 16, 0.8),
    BOILER(10, 1.0, 0, 1.0);

    private final double mass;
    private final double transferFactor;
    private final int stoneCapacity;
    private final double stoneHeat;

    ChamberType(double mass, double transferFactor, int stoneCapacity, double stoneHeat) {
        this.mass = mass;
        this.transferFactor = transferFactor;
        this.stoneCapacity = stoneCapacity;
        this.stoneHeat = stoneHeat;
    }

    public double mass() {
        return mass;
    }

    public double transferFactor() {
        return transferFactor;
    }

    public int stoneCapacity() {
        return stoneCapacity;
    }

    public boolean hasStones() {
        return stoneCapacity > 0;
    }

    /** How hot the stones get relative to the core (1 = as hot as the fire). */
    public double stoneHeat() {
        return stoneHeat;
    }

    public String translationKey() {
        return "bathhouse.chamber." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
