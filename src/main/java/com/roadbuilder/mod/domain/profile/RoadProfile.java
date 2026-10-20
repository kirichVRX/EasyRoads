package com.roadbuilder.mod.domain.profile;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Defines the cross-sectional profile, materials, dimensions, and viaduct/tunnel settings for a road.
 */
public final class RoadProfile {
    private final String name;
    private final double width;
    private final String surfaceBlockId;
    private final String foundationBlockId;
    private final boolean hasCurbs;
    private final String curbBlockId;
    private final double curbWidth;
    private final int clearanceHeight;

    // Advanced profile features: Sidewalks, Center Marking, Foundation
    private final boolean hasSidewalks;
    private final double sidewalkWidth;
    private final List<String> sidewalkBlockIds;
    private final boolean hasCenterMarking;
    private final String centerMarkingBlockId;
    private final int dashLength;
    private final int gapLength;

    // Bridge / Viaduct settings
    private final boolean createPillars;
    private final String pillarBlockId;
    private final double pillarSpacing;

    // Tunnel settings
    private final boolean createTunnels;
    private final String tunnelWallBlockId;
    private final int tunnelCeilingHeight;

    public RoadProfile(
            String name,
            double width,
            String surfaceBlockId,
            String foundationBlockId,
            boolean hasCurbs,
            String curbBlockId,
            double curbWidth,
            int clearanceHeight,
            boolean hasSidewalks,
            double sidewalkWidth,
            List<String> sidewalkBlockIds,
            boolean hasCenterMarking,
            String centerMarkingBlockId,
            int dashLength,
            int gapLength,
            boolean createPillars,
            String pillarBlockId,
            double pillarSpacing,
            boolean createTunnels,
            String tunnelWallBlockId,
            int tunnelCeilingHeight
    ) {
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.width = Math.max(1.0, width);
        this.surfaceBlockId = Objects.requireNonNull(surfaceBlockId, "surfaceBlockId cannot be null");
        this.foundationBlockId = Objects.requireNonNull(foundationBlockId, "foundationBlockId cannot be null");
        this.hasCurbs = hasCurbs;
        this.curbBlockId = Objects.requireNonNull(curbBlockId, "curbBlockId cannot be null");
        this.curbWidth = Math.max(0.5, curbWidth);
        this.clearanceHeight = Math.max(2, clearanceHeight);

        this.hasSidewalks = hasSidewalks;
        this.sidewalkWidth = Math.max(0.0, sidewalkWidth);
        this.sidewalkBlockIds = sidewalkBlockIds != null ? List.copyOf(sidewalkBlockIds) : Collections.emptyList();
        this.hasCenterMarking = hasCenterMarking;
        this.centerMarkingBlockId = Objects.requireNonNull(centerMarkingBlockId, "centerMarkingBlockId cannot be null");
        this.dashLength = Math.max(1, dashLength);
        this.gapLength = Math.max(1, gapLength);

        this.createPillars = createPillars;
        this.pillarBlockId = Objects.requireNonNull(pillarBlockId, "pillarBlockId cannot be null");
        this.pillarSpacing = Math.max(3.0, pillarSpacing);
        this.createTunnels = createTunnels;
        this.tunnelWallBlockId = Objects.requireNonNull(tunnelWallBlockId, "tunnelWallBlockId cannot be null");
        this.tunnelCeilingHeight = Math.max(3, tunnelCeilingHeight);
    }

    /**
     * Default City Avenue:
     * - Sidewalks: 4 blocks on each side (stone, andesite, polished_andesite, stone_bricks mix)
     * - Curbs: 1 block on each side (polished_andesite_stairs)
     * - Roadway: 2 lanes of 4 blocks each (gray_concrete_powder)
     * - Center marking: 1 block wide (smooth_quartz_stairs) with 4-block dash and 1-block gap
     * - Foundation: stone layer under concrete powder
     * Total width = 4 (sidewalk) + 1 (curb) + 4 (lane) + 1 (center) + 4 (lane) + 1 (curb) + 4 (sidewalk) = 19 blocks!
     */
    public static RoadProfile createDefault() {
        return new RoadProfile(
                "Standard City Avenue",
                19.0,
                "minecraft:gray_concrete_powder",
                "minecraft:stone",
                true,
                "minecraft:polished_andesite_stairs",
                1.0,
                4,
                true,
                4.0,
                List.of(
                        "minecraft:stone",
                        "minecraft:andesite",
                        "minecraft:polished_andesite",
                        "minecraft:stone_bricks"
                ),
                true,
                "minecraft:smooth_quartz_stairs",
                4,
                1,
                true,
                "minecraft:stone_bricks",
                12.0,
                true,
                "minecraft:stone_bricks",
                4
        );
    }

    public RoadProfile withWidth(double newWidth) {
        return new RoadProfile(
                name,
                newWidth,
                surfaceBlockId,
                foundationBlockId,
                hasCurbs,
                curbBlockId,
                curbWidth,
                clearanceHeight,
                hasSidewalks,
                sidewalkWidth,
                sidewalkBlockIds,
                hasCenterMarking,
                centerMarkingBlockId,
                dashLength,
                gapLength,
                createPillars,
                pillarBlockId,
                pillarSpacing,
                createTunnels,
                tunnelWallBlockId,
                tunnelCeilingHeight
        );
    }

    public static RoadProfile createHighway() {
        return new RoadProfile(
                "Highway",
                9.0,
                "minecraft:black_concrete",
                "minecraft:stone",
                true,
                "minecraft:polished_andesite_stairs",
                1.0,
                5,
                false,
                0.0,
                Collections.emptyList(),
                true,
                "minecraft:smooth_quartz_stairs",
                3,
                2,
                true,
                "minecraft:polished_andesite",
                12.0,
                true,
                "minecraft:polished_andesite",
                5
        );
    }

    public static RoadProfile createDirtPath() {
        return new RoadProfile(
                "Rural Dirt Path",
                3.0,
                "minecraft:dirt_path",
                "minecraft:dirt",
                false,
                "minecraft:dirt_path",
                0.0,
                3,
                false,
                0.0,
                Collections.emptyList(),
                false,
                "minecraft:dirt_path",
                1,
                1,
                false,
                "minecraft:oak_log",
                8.0,
                false,
                "minecraft:cobblestone",
                3
        );
    }

    public String getName() {
        return name;
    }

    public double getWidth() {
        return width;
    }

    public String getSurfaceBlockId() {
        return surfaceBlockId;
    }

    public String getFoundationBlockId() {
        return foundationBlockId;
    }

    public boolean hasCurbs() {
        return hasCurbs;
    }

    public String getCurbBlockId() {
        return curbBlockId;
    }

    public double getCurbWidth() {
        return curbWidth;
    }

    public int getClearanceHeight() {
        return clearanceHeight;
    }

    public boolean hasSidewalks() {
        return hasSidewalks;
    }

    public double getSidewalkWidth() {
        return sidewalkWidth;
    }

    public List<String> getSidewalkBlockIds() {
        return sidewalkBlockIds;
    }

    public boolean hasCenterMarking() {
        return hasCenterMarking;
    }

    public String getCenterMarkingBlockId() {
        return centerMarkingBlockId;
    }

    public int getDashLength() {
        return dashLength;
    }

    public int getGapLength() {
        return gapLength;
    }

    public boolean isCreatePillars() {
        return createPillars;
    }

    public String getPillarBlockId() {
        return pillarBlockId;
    }

    public double getPillarSpacing() {
        return pillarSpacing;
    }

    public boolean isCreateTunnels() {
        return createTunnels;
    }

    public String getTunnelWallBlockId() {
        return tunnelWallBlockId;
    }

    public int getTunnelCeilingHeight() {
        return tunnelCeilingHeight;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadProfile that = (RoadProfile) o;
        return Double.compare(that.width, width) == 0 &&
                hasCurbs == that.hasCurbs &&
                Double.compare(that.curbWidth, curbWidth) == 0 &&
                clearanceHeight == that.clearanceHeight &&
                hasSidewalks == that.hasSidewalks &&
                Double.compare(that.sidewalkWidth, sidewalkWidth) == 0 &&
                hasCenterMarking == that.hasCenterMarking &&
                dashLength == that.dashLength &&
                gapLength == that.gapLength &&
                createPillars == that.createPillars &&
                Double.compare(that.pillarSpacing, pillarSpacing) == 0 &&
                createTunnels == that.createTunnels &&
                tunnelCeilingHeight == that.tunnelCeilingHeight &&
                name.equals(that.name) &&
                surfaceBlockId.equals(that.surfaceBlockId) &&
                foundationBlockId.equals(that.foundationBlockId) &&
                curbBlockId.equals(that.curbBlockId) &&
                sidewalkBlockIds.equals(that.sidewalkBlockIds) &&
                centerMarkingBlockId.equals(that.centerMarkingBlockId) &&
                pillarBlockId.equals(that.pillarBlockId) &&
                tunnelWallBlockId.equals(that.tunnelWallBlockId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, width, surfaceBlockId, foundationBlockId, hasCurbs,
                curbBlockId, curbWidth, clearanceHeight, hasSidewalks, sidewalkWidth, sidewalkBlockIds,
                hasCenterMarking, centerMarkingBlockId, dashLength, gapLength,
                createPillars, pillarBlockId, pillarSpacing, createTunnels, tunnelWallBlockId, tunnelCeilingHeight);
    }

    @Override
    public String toString() {
        return "RoadProfile{" +
                "name='" + name + '\'' +
                ", width=" + width +
                '}';
    }
}
