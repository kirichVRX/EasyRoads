package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Procedural generator for circular roundabout rotary junctions.
 */
public final class RoundaboutGenerator {

    private RoundaboutGenerator() {}

    public record RoundaboutArm(
            UUID edgeId,
            Vector3d direction,
            Vector3d normal,
            double halfWidth,
            double halfRoadwayWidth
    ) {}

    public static Map<BlockPos, BlockState> generateRoundabout(
            Level level, RoadNode node, List<RoadEdge> edges, RoadNetworkGraph graph) {

        Map<BlockPos, BlockState> result = new HashMap<>();

        Vector3d center = node.getPosition();
        double rOut = node.getOuterRadius() > 0 ? node.getOuterRadius() : 12.0;
        double rIn = node.getInnerRadius() > 0 ? node.getInnerRadius() : 5.0;
        int roadY = (int) Math.round(center.y);
        double sidewalkWidth = 3.0;
        double maxDist = rOut + sidewalkWidth;

        // Build arms (connecting road approaches)
        List<RoundaboutArm> arms = new ArrayList<>();
        if (edges != null) {
            for (RoadEdge edge : edges) {
                Vector3d dir = edge.getNodeAId().equals(node.getId())
                        ? edge.getCurve().evaluateTangent(0.0)
                        : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                Vector3d dirXz = new Vector3d(dir.x, 0, dir.z).normalize();
                if (dirXz.lengthSquared() < 1e-4) dirXz = new Vector3d(0, 0, 1);
                Vector3d normal = new Vector3d(-dirXz.z, 0, dirXz.x);

                String presetId = edge.getPresetId();
                ModularRoadPreset preset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(presetId))
                        .findFirst()
                        .orElse(PresetManager.getInstance().getActivePreset());

                arms.add(new RoundaboutArm(
                        edge.getId(),
                        dirXz,
                        normal,
                        preset.getTotalWidth() * 0.5,
                        preset.getRoadwayWidth() * 0.5
                ));
            }
        }

        BlockState asphaltState = Blocks.GRAY_CONCRETE.defaultBlockState();
        BlockState markingState = Blocks.WHITE_CONCRETE.defaultBlockState();
        BlockState curbState = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        BlockState sidewalkState = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();

        int minX = (int) Math.floor(center.x - maxDist);
        int maxX = (int) Math.ceil(center.x + maxDist);
        int minZ = (int) Math.floor(center.z - maxDist);
        int maxZ = (int) Math.ceil(center.z + maxDist);

        int lanes = Math.max(1, node.getRingLaneCount());
        double ringWidth = rOut - rIn;
        double laneWidth = ringWidth / lanes;

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                double dx = (bx + 0.5) - center.x;
                double dz = (bz + 0.5) - center.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                if (dist > maxDist) {
                    continue;
                }

                // Check if this column lies in an approach arm opening
                boolean inArmRoadway = false;
                for (RoundaboutArm arm : arms) {
                    double dAlong = dx * arm.direction().x + dz * arm.direction().z;
                    double dLat = Math.abs(dx * arm.normal().x + dz * arm.normal().z);
                    if (dAlong >= rOut - 2.0 && dLat <= arm.halfRoadwayWidth()) {
                        inArmRoadway = true;
                        break;
                    }
                }

                BlockState surfaceState;
                if (dist <= rIn - 0.5) {
                    // Central island
                    surfaceState = grassState;
                } else if (dist <= rIn + 0.5) {
                    // Inner curb border
                    surfaceState = curbState;
                } else if (dist <= rOut - 0.5 || inArmRoadway) {
                    // Ring roadway
                    surfaceState = asphaltState;

                    // Concentric lane dashed markings
                    if (lanes > 1 && !inArmRoadway) {
                        for (int lane = 1; lane < lanes; lane++) {
                            double markR = rIn + lane * laneWidth;
                            if (Math.abs(dist - markR) <= 0.5) {
                                double angle = Math.atan2(dz, dx);
                                double arc = angle * markR;
                                if (Math.floorMod((long) Math.floor(arc), 4) < 3) {
                                    surfaceState = markingState;
                                }
                            }
                        }
                    }
                } else if (dist <= rOut + 0.5) {
                    // Outer curb border
                    surfaceState = inArmRoadway ? asphaltState : curbState;
                } else {
                    // Perimeter sidewalk
                    surfaceState = inArmRoadway ? asphaltState : sidewalkState;
                }

                BlockPos surfacePos = new BlockPos(bx, roadY, bz);
                result.put(surfacePos, surfaceState);

                // Clearance zone
                for (int cy = roadY + 1; cy <= roadY + 4; cy++) {
                    result.put(new BlockPos(bx, cy, bz), airState);
                }

                // Sub-surface foundation (2 blocks down)
                BlockState subState = (surfaceState == grassState) ? dirtState : dirtState;
                for (int fy = roadY - 1; fy >= roadY - 2; fy--) {
                    result.put(new BlockPos(bx, fy, bz), subState);
                }
            }
        }

        return result;
    }
}
