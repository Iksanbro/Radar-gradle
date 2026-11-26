package com.radar.bathhouse.stove;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.Nullable;

/** The parts a stove is assembled from, besides the firebox and the chimney. */
public enum StovePartType implements StringRepresentable {
    /** Under the firebox: grate and ash drawer. Gives the fire its draft; empty it now and then. */
    ASH_PIT("ash_pit", Slot.BELOW, ChamberType.NONE, false),
    /** On the firebox: closed stone chamber ("inner" heater). */
    CLOSED_HEATER("closed_heater", Slot.CHAMBER, ChamberType.CLOSED, true),
    /** On the firebox: open stone basket ("outer" heater). */
    OPEN_HEATER("open_heater", Slot.CHAMBER, ChamberType.OPEN, true),
    /** On the firebox: steam boiler for a mist hall. */
    BOILER("boiler", Slot.CHAMBER, ChamberType.BOILER, true),
    /** In the flue: water tank heated by the hot gases. Hot water makes lighter steam. */
    WATER_TANK("water_tank", Slot.FLUE, ChamberType.NONE, true),
    /** In the flue: gate valve. Closed after the fire is out it keeps the heat in; closed while burning — smoke! */
    DAMPER("damper", Slot.FLUE, ChamberType.NONE, false),
    /** In front of the firebox door: feeds the fire through the wall from the changing room. */
    FUEL_TUNNEL("fuel_tunnel", Slot.FRONT, ChamberType.NONE, false),
    /** Beside the firebox or chamber: soapstone cladding. More heat capacity, softer heat. */
    CLADDING("stove_cladding", Slot.SIDE, ChamberType.NONE, false);

    private final String id;
    private final Slot slot;
    private final ChamberType chamber;
    private final boolean blockEntity;

    StovePartType(String id, Slot slot, ChamberType chamber, boolean blockEntity) {
        this.id = id;
        this.slot = slot;
        this.chamber = chamber;
        this.blockEntity = blockEntity;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String translationKey() {
        return "block.bathhouse." + id;
    }

    public Slot slot() {
        return slot;
    }

    public ChamberType chamber() {
        return chamber;
    }

    /** Stores something (stones, water, ash) in a block entity. */
    public boolean hasBlockEntity() {
        return blockEntity || this == ASH_PIT;
    }

    public int waterCapacity() {
        return switch (this) {
            case BOILER -> 8000;
            case WATER_TANK -> 4000;
            default -> 0;
        };
    }

    @Nullable
    public static StovePartType chamberOf(ChamberType type) {
        for (StovePartType part : values()) {
            if (part.slot == Slot.CHAMBER && part.chamber == type) {
                return part;
            }
        }
        return null;
    }

    public enum Slot {
        BELOW, CHAMBER, FLUE, FRONT, SIDE
    }
}
