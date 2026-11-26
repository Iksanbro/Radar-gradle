package com.radar.bathhouse.plunge;

/**
 * Plunge basin walls:
 * <ul>
 *     <li>WOOD — oak staves with steel hoops: classic, keeps heat reasonably well;</li>
 *     <li>COMPOSITE — polymer liner clad in wood: the best insulation, no maintenance;</li>
 *     <li>TILE — concrete with tiles or mosaic: a stationary basin, loses heat but keeps the cold.</li>
 * </ul>
 */
public enum PlungeMaterial {
    WOOD("plunge_wall_wood", 0.008, true),
    COMPOSITE("plunge_wall_composite", 0.005, true),
    TILE("plunge_wall_tile", 0.016, false);

    private final String blockId;
    private final double conductance;
    private final boolean insulated;

    PlungeMaterial(String blockId, double conductance, boolean insulated) {
        this.blockId = blockId;
        this.conductance = conductance;
        this.insulated = insulated;
    }

    public String blockId() {
        return blockId;
    }

    /** Heat loss per face, kW/°C. */
    public double conductance() {
        return conductance;
    }

    public boolean insulated() {
        return insulated;
    }
}
