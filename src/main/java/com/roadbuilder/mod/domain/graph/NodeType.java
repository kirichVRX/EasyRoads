package com.roadbuilder.mod.domain.graph;

/**
 * Categorizes the structural role of a RoadNode within the road network graph.
 */
public enum NodeType {
    ENDPOINT,      // Road terminus / dead end (1 edge)
    WAYPOINT,      // Smooth through-point between 2 continuous edges with matching preset (< 30 deg deflection)
    JUNCTION,      // Intersection where 2+ edges meet with deflection or different presets, or 3+ edges
    ROUNDABOUT;    // Circular rotary junction

    public static NodeType fromString(String name) {
        if (name == null || name.isBlank()) return ENDPOINT;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ENDPOINT;
        }
    }
}
