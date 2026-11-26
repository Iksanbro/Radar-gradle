package com.radar.bathhouse.climate;

import com.radar.bathhouse.stove.ChamberType;
import com.radar.bathhouse.stove.FireboxType;
import org.jetbrains.annotations.Nullable;

/**
 * Description of a heat source for the gauge.
 *
 * @param firebox       the stove's firebox, null for single-block appliances
 * @param chamber       what sits on the firebox (stones, boiler) — {@link ChamberType#NONE} otherwise
 * @param stoneQuality  average quality of the stones, 1 (poor) .. 3 (excellent); 0 without stones
 * @param smoky         burns wood without its own chimney: smoke goes into the room
 */
public record SourceInfo(Kind kind, @Nullable FireboxType firebox, ChamberType chamber, boolean ashPit, boolean chimney,
                         int cladding, int stones, int stoneCapacity, double stoneQuality, boolean running, boolean smoky) {
    public enum Kind {
        STOVE, STEAM_GENERATOR, WARM_LOUNGER
    }

    public static SourceInfo appliance(Kind kind, boolean running) {
        return new SourceInfo(kind, null, ChamberType.NONE, false, false, 0, 0, 0, 0, running, false);
    }

    public boolean woodFired() {
        return firebox != null && firebox.burnsFuel();
    }

    public boolean steamSource() {
        return kind == Kind.STEAM_GENERATOR || (kind == Kind.STOVE && chamber == ChamberType.BOILER);
    }

    /** Stones at least three quarters full and good on average. */
    public boolean goodStones() {
        return stoneCapacity > 0 && stones * 4 >= stoneCapacity * 3 && stoneQuality >= 2.0;
    }
}
