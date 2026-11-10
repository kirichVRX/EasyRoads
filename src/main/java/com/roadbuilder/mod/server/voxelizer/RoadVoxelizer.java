package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.*;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Procedural road planner and voxelizer built on modular road presets:
 * 1. Discrete segment sampling with exact perpendicular distance calculation.
 * 2. Grid-aligned normalization factor m = max(|nx|, |nz|) ensuring thin elements are exactly 1 cell wide.
 * 3. Modular profile stack mapping: sidewalks, lanes, markings, dividers, shoulders.
 * 4. Automatic viaducts with continuous V-vault and dynamic pillars (1 to 3 based on width).
 * 5. Automatic arched mountain tunnels and terrain clearing.
 */
public final class RoadVoxelizer {

    public enum Kind { ASPHALT, LINE, CURB, SIDEWALK, GRASS, STONE }
    public record Cell(int x, int z, int y, Kind kind, Direction facing, BlockState state, int localX, double t, double nx, double nz) {
        public Cell(int x, int z, int y, Kind kind, Direction facing) {
            this(x, z, y, kind, facing, null, 0, 0.0, 0.0, 0.0);
        }
        public Cell(int x, int z, int y, Kind kind, Direction facing, BlockState state) {
            this(x, z, y, kind, facing, state, 0, 0.0, 0.0, 0.0);
        }
    }

    private record SideCandidate(int bx, int bz, int roadY, double dz, double t, double outX, double outZ, int side, boolean isEndCap) {}
    private record RoadIntersection(Vector3d center, Vector3d t1, Vector3d n1, double hRoadway1, Vector3d t2, Vector3d n2, double hRoadway2) {}

    private static final double EPS = 1e-9;

    public static final BlockState[] SIDEWALK_MIX = new BlockState[]{
            Blocks.STONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
            Blocks.STONE_BRICKS.defaultBlockState()
    };

    private RoadVoxelizer() {}

    public static void voxelizeAndPlace(ServerLevel level, BezierCurve3D curve, ModularRoadPreset preset) {
        Map<BlockPos, BlockState> blocksToSet = calculateRoadBlocks(level, curve, preset);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Voxelized modular road (preset '{}'): placing {} blocks in world...",
                preset.getName(), blocksToSet.size());

        for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Road placement completed.");
    }

    public static void voxelizeAndPlace(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        voxelizeAndPlace(level, curve, preset);
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, ModularRoadPreset preset) {
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        RoadNetworkGraph graph = (savedData != null) ? savedData.getGraph() : null;
        return calculateRoadBlocks((Level) level, curve, preset, graph);
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(Level level, BezierCurve3D curve, ModularRoadPreset preset, RoadNetworkGraph graph) {
        return calculateRoadBlocks(level, curve, preset, graph, null);
    }

    public static Vector3d computeMiterNormalAtNode(RoadNode node, UUID thisEdgeId, BezierCurve3D thisCurve, boolean isAtP0, RoadNetworkGraph graph) {
        if (graph == null || node == null || node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION || node.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT) {
            return null;
        }
        List<RoadEdge> edges = graph.getEdgesForNode(node.getId());
        if (edges.size() != 2) {
            return null; // Only use miter bisector when exactly 2 edges meet at this node (continuation/waypoint)
        }
        RoadEdge otherEdge = (thisEdgeId != null && edges.get(0).getId().equals(thisEdgeId)) ? edges.get(1) : edges.get(0);
        if (otherEdge == null) return null;

        // Tangent of this road at this node: pointing in flow direction
        Vector3d thisDir;
        if (isAtP0) {
            Vector3d t = thisCurve.evaluateTangent(0.0);
            thisDir = new Vector3d(t.x, 0, t.z).normalize();
        } else {
            Vector3d t = thisCurve.evaluateTangent(1.0);
            thisDir = new Vector3d(t.x, 0, t.z).normalize();
        }

        // Tangent of other road at this node: aligned in flow direction
        Vector3d otherDir;
        boolean otherStartsAtNode = otherEdge.getNodeAId().equals(node.getId())
                || (otherEdge.getCurve().getP0().distanceTo(node.getPosition()) < 0.5);

        if (isAtP0) {
            if (otherStartsAtNode) {
                Vector3d t = otherEdge.getCurve().evaluateTangent(0.0);
                otherDir = new Vector3d(-t.x, 0, -t.z).normalize();
            } else {
                Vector3d t = otherEdge.getCurve().evaluateTangent(1.0);
                otherDir = new Vector3d(t.x, 0, t.z).normalize();
            }
        } else {
            if (otherStartsAtNode) {
                Vector3d t = otherEdge.getCurve().evaluateTangent(0.0);
                otherDir = new Vector3d(t.x, 0, t.z).normalize();
            } else {
                Vector3d t = otherEdge.getCurve().evaluateTangent(1.0);
                otherDir = new Vector3d(-t.x, 0, -t.z).normalize();
            }
        }

        if (thisDir.lengthSquared() < 1e-4 || otherDir.lengthSquared() < 1e-4) {
            return null;
        }

        // Prevent extreme miter bevels on sharp turns (> 90°)
        if (thisDir.dot(otherDir) < 0.0) {
            return null;
        }

        Vector3d bisector = thisDir.add(otherDir);
        if (bisector.lengthSquared() < 0.05) {
            return null;
        }
        return bisector.normalize();
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(Level level, BezierCurve3D curve, ModularRoadPreset preset, RoadNetworkGraph graph, UUID currentEdgeId) {
        Map<BlockPos, BlockState> blockMap = new HashMap<>();

        // Ensure currentEdgeId is resolved upfront
        if (currentEdgeId == null && graph != null) {
            for (RoadEdge edge : graph.getEdges().values()) {
                if (edge.getCurve().equals(curve) ||
                    (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                    currentEdgeId = edge.getId();
                    break;
                }
            }
        }

        Vector3d startCutNormal = null;
        Vector3d endCutNormal = null;
        if (graph != null) {
            RoadNode node0 = null;
            RoadNode node3 = null;
            for (RoadNode n : graph.getNodes().values()) {
                if (n.getPosition().distanceTo(curve.getP0()) < 1.0) node0 = n;
                if (n.getPosition().distanceTo(curve.getP3()) < 1.0) node3 = n;
            }
            if (node0 != null) {
                startCutNormal = computeMiterNormalAtNode(node0, currentEdgeId, curve, true, graph);
            }
            if (node3 != null) {
                endCutNormal = computeMiterNormalAtNode(node3, currentEdgeId, curve, false, graph);
            }
        }

        TransitionProfile transitionAtP0 = null;
        TransitionProfile transitionAtP3 = null;

        if (graph != null) {
            RoadEdge currentEdge = (currentEdgeId != null) ? graph.getEdge(currentEdgeId) : null;
            if (currentEdge == null) {
                for (RoadEdge edge : graph.getEdges().values()) {
                    if (edge.getCurve().equals(curve) ||
                        (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                        currentEdge = edge;
                        break;
                    }
                }
            }

            if (currentEdge != null) {
                transitionAtP0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, currentEdge, preset);
                transitionAtP3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, currentEdge, preset);
            }
        }

        // 1. Generate cells for this road curve (with transition profiles if connecting to different presets)
        List<Cell> ownCells = planCurveCells(curve, preset, transitionAtP0, transitionAtP3, startCutNormal, endCutNormal);
        if (ownCells.isEmpty()) {
            return blockMap;
        }

        RoadNode junctionAtP0 = null;
        double radiusP0 = 0.0;
        RoadNode junctionAtP3 = null;
        double radiusP3 = 0.0;

        RoadNode roundaboutAtP0 = null;
        double rRadiusP0 = 0.0;
        RoadNode roundaboutAtP3 = null;
        double rRadiusP3 = 0.0;

        double curveLen = curve.getApproximateLength();

        if (graph != null) {
            for (RoadNode node : graph.getNodes().values()) {
                boolean isRoundabout = node.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT
                        || (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION && "ROUNDABOUT".equalsIgnoreCase(node.getJunctionStyle()));
                if (isRoundabout) {
                    double rOut = node.getOuterRadius() > 0 ? node.getOuterRadius() : 23.0;
                    double cutDist = rOut + 7.5;
                    if (node.getPosition().distanceTo(curve.getP0()) < 1.0) {
                        roundaboutAtP0 = node;
                        rRadiusP0 = cutDist;
                    } else if (node.getPosition().distanceTo(curve.getP3()) < 1.0) {
                        roundaboutAtP3 = node;
                        rRadiusP3 = cutDist;
                    }
                } else if (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION) {
                    if (node.getPosition().distanceTo(curve.getP0()) < 1.0) {
                        junctionAtP0 = node;
                        radiusP0 = JunctionGenerator.getArmEntryDistance(node, graph.getEdgesForNode(node.getId()), currentEdgeId);
                    } else if (node.getPosition().distanceTo(curve.getP3()) < 1.0) {
                        junctionAtP3 = node;
                        radiusP3 = JunctionGenerator.getArmEntryDistance(node, graph.getEdgesForNode(node.getId()), currentEdgeId);
                    }
                }
            }
        }

        if (junctionAtP0 != null || junctionAtP3 != null || roundaboutAtP0 != null || roundaboutAtP3 != null) {
            final RoadNode j0 = junctionAtP0;
            final double r0 = radiusP0;
            final RoadNode j3 = junctionAtP3;
            final double r3 = radiusP3;
            final RoadNode rb0 = roundaboutAtP0;
            final double rr0 = rRadiusP0;
            final RoadNode rb3 = roundaboutAtP3;
            final double rr3 = rRadiusP3;
            ownCells = ownCells.stream().filter(cell -> {
                if (j0 != null && cell.t() < r0) {
                    return false;
                }
                if (j3 != null && cell.t() > curveLen - r3) {
                    return false;
                }
                Vector3d cellPos = new Vector3d(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
                if (rb0 != null && cellPos.distanceTo(curve.getP0()) < rr0) {
                    return false;
                }
                if (rb3 != null && cellPos.distanceTo(curve.getP3()) < rr3) {
                    return false;
                }
                return true;
            }).toList();
        }

        // 2. Gather existing roads from saved graph to resolve intersections & grade separations
        Map<Long, List<Cell>> otherRoadCells = new HashMap<>();
        Set<Long> continuationCellKeys = new HashSet<>();
        Set<Long> otherRoadwayKeys = new HashSet<>();
        Map<Long, Integer> lowerRoadSurfaceY = new HashMap<>();
        Set<Long> lowerRoadClearanceZone = new HashSet<>();
        List<Double> lowerCrossingT = new ArrayList<>();
        Map<Long, Integer> groundYMap = new HashMap<>();
        List<RoadIntersection> intersections = new ArrayList<>();
        Set<BlockPos> otherActiveRoadBlocks = Collections.emptySet();

        if (level instanceof ServerLevel serverLevel) {
            RoadGraphSavedData savedData = RoadGraphSavedData.get(serverLevel);
            if (savedData != null) {
                if (currentEdgeId == null && graph != null) {
                    for (RoadEdge edge : graph.getEdges().values()) {
                        if (edge.getCurve().equals(curve) ||
                            (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                            currentEdgeId = edge.getId();
                            break;
                        }
                    }
                }
                otherActiveRoadBlocks = (currentEdgeId != null)
                        ? savedData.getAllOtherEdgeBlocks(currentEdgeId)
                        : savedData.getAllOtherEdgeBlocks(Collections.emptySet());
            }
        }

        double hRoadway1 = preset.getRoadwayWidth() / 2.0;
        boolean p0Connected = false;
        boolean p3Connected = false;
        boolean p0ConnectsToViaduct = false;
        boolean p3ConnectsToViaduct = false;

        if (graph != null) {
            int edgesAtP0 = 0;
            int edgesAtP3 = 0;
            for (RoadEdge edge : graph.getEdges().values()) {
                if (currentEdgeId != null && edge.getId().equals(currentEdgeId)) continue;
                if (edge.getCurve().equals(curve)) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP0()) < 0.01) continue;

                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP0()) < 1.0) {
                    edgesAtP0++;
                }
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP3()) < 1.0) {
                    edgesAtP3++;
                }
            }

            for (RoadEdge edge : graph.getEdges().values()) {
                if (currentEdgeId != null && edge.getId().equals(currentEdgeId)) continue;
                if (edge.getCurve().equals(curve)) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP0()) < 0.01) continue;

                double distP0toP0 = edge.getCurve().getP0().distanceTo(curve.getP0());
                double distP0toP3 = edge.getCurve().getP3().distanceTo(curve.getP0());
                boolean sharesP0 = distP0toP0 < 1.0 || distP0toP3 < 1.0;
                if (sharesP0) {
                    p0Connected = true;
                }

                double distP3toP0 = edge.getCurve().getP0().distanceTo(curve.getP3());
                double distP3toP3 = edge.getCurve().getP3().distanceTo(curve.getP3());
                boolean sharesP3 = distP3toP0 < 1.0 || distP3toP3 < 1.0;
                if (sharesP3) {
                    p3Connected = true;
                }

                double dist = edge.getCurve().getP0().distanceTo(curve.getP0());
                if (dist > 300.0 && !sharesP0 && !sharesP3) continue;

                ModularRoadPreset otherPreset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(edge.getPresetId()) || p.getName().equals(edge.getPresetId()) || p.getName().equals(edge.getProfile().getName()))
                        .findFirst()
                        .orElse(preset);

                double hRoadway2 = otherPreset.getRoadwayWidth() / 2.0;

                boolean isContinuationP0 = false;
                if (sharesP0) {
                    if (edgesAtP0 == 1) {
                        Vector3d vCurve = curve.evaluateTangent(0.0);
                        Vector3d vOther = (distP0toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0).multiply(-1.0) : edge.getCurve().evaluateTangent(1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : 1.0;
                        if (dot > 0.70) {
                            isContinuationP0 = true;
                        }
                    }
                    if (!isContinuationP0) {
                        Vector3d vCurve = curve.evaluateTangent(0.0);
                        Vector3d vOther = (distP0toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP0(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuationP3 = false;
                if (sharesP3) {
                    if (edgesAtP3 == 1) {
                        Vector3d vCurve = curve.evaluateTangent(1.0);
                        Vector3d vOther = (distP3toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : 1.0;
                        if (dot > 0.70) {
                            isContinuationP3 = true;
                        }
                    }
                    if (!isContinuationP3) {
                        Vector3d vCurve = curve.evaluateTangent(1.0).multiply(-1.0);
                        Vector3d vOther = (distP3toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP3(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuation = (sharesP0 && isContinuationP0) || (sharesP3 && isContinuationP3);

                if (sharesP0 && otherPreset.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                    Vector3d connPt = curve.getP0();
                    int gY = groundYMap.computeIfAbsent(coordKey((int) Math.round(connPt.x), (int) Math.round(connPt.z)),
                            k -> getTrueGroundY(level, (int) Math.round(connPt.x), (int) Math.round(connPt.z)));
                    int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, (int) Math.round(connPt.x), (int) Math.round(connPt.z));
                    boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos((int) Math.round(connPt.x), gY, (int) Math.round(connPt.z))).isEmpty();
                    boolean isElevated = (Math.round(connPt.y) - gY) >= 6;
                    if (otherPreset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS || overWater || isElevated) {
                        p0ConnectsToViaduct = true;
                    }
                }
                if (sharesP3 && otherPreset.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                    Vector3d connPt = curve.getP3();
                    int gY = groundYMap.computeIfAbsent(coordKey((int) Math.round(connPt.x), (int) Math.round(connPt.z)),
                            k -> getTrueGroundY(level, (int) Math.round(connPt.x), (int) Math.round(connPt.z)));
                    int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, (int) Math.round(connPt.x), (int) Math.round(connPt.z));
                    boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos((int) Math.round(connPt.x), gY, (int) Math.round(connPt.z))).isEmpty();
                    boolean isElevated = (Math.round(connPt.y) - gY) >= 6;
                    if (otherPreset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS || overWater || isElevated) {
                        p3ConnectsToViaduct = true;
                    }
                }

                List<Cell> theirCells = planCurveCells(edge.getCurve(), otherPreset);
                Vector3d sharedPt = sharesP0 ? curve.getP0() : curve.getP3();
                for (Cell c : theirCells) {
                    long key = coordKey(c.x(), c.z());
                    otherRoadCells.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    if (c.kind() == Kind.ASPHALT || c.kind() == Kind.LINE) {
                        otherRoadwayKeys.add(key);
                    }
                    if (isContinuation) {
                        double dToShared = Math.hypot(c.x() - sharedPt.x, c.z() - sharedPt.z);
                        if (dToShared <= 3.5) {
                            continuationCellKeys.add(key);
                        }
                    }
                }
            }
        }

        // 3. Resolve winners at intersection overlaps & track grade-separated lower roads
        List<Cell> finalCells = new ArrayList<>(ownCells.size());
        for (Cell c : ownCells) {
            long key = coordKey(c.x(), c.z());
            List<Cell> others = otherRoadCells.get(key);

            Cell intersectingOther = null;
            if (others != null) {
                for (Cell other : others) {
                    if (Math.abs(c.y() - other.y()) <= 2) {
                        intersectingOther = other;
                    } else if (other.y() < c.y() - 2) {
                        // Grade separation: another road passes UNDER this road
                        lowerCrossingT.add(c.t());
                        lowerRoadSurfaceY.put(key, other.y());
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                lowerRoadClearanceZone.add(coordKey(c.x() + dx, c.z() + dz));
                            }
                        }
                    }
                }
            }

            Cell resolved;
            if (intersectingOther == null || continuationCellKeys.contains(key)) {
                resolved = c;
            } else {
                resolved = resolveWinner(c, intersectingOther);
            }

            // Проверяем, находится ли клетка внутри зоны проезжей части перекрестка (OBB)
            boolean inIntersectionRoadway = false;
            for (RoadIntersection inter : intersections) {
                double dx = (c.x() + 0.5) - inter.center.x;
                double dz = (c.z() + 0.5) - inter.center.z;
                double u_1 = Math.abs(dx * inter.t1.x + dz * inter.t1.z);
                double v_1 = Math.abs(dx * inter.n1.x + dz * inter.n1.z);
                double u_2 = Math.abs(dx * inter.t2.x + dz * inter.t2.z);
                double v_2 = Math.abs(dx * inter.n2.x + dz * inter.n2.z);

                if (u_1 <= inter.hRoadway2 + 0.5 && v_1 <= inter.hRoadway1 + 0.2) {
                    inIntersectionRoadway = true;
                    break;
                } else if (u_2 <= inter.hRoadway1 + 0.5 && v_2 <= inter.hRoadway2 + 0.2) {
                    inIntersectionRoadway = true;
                    break;
                }
            }

            if (inIntersectionRoadway) {
                // В зоне проезжей части перекрестка:
                // Газоны-разделители, центральные бордюры, разметка и тротуары превращаются в чистый асфальт!
                if (resolved.kind() == Kind.LINE || 
                    resolved.kind() == Kind.CURB || 
                    resolved.kind() == Kind.GRASS ||
                    resolved.kind() == Kind.SIDEWALK) {
                    resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), 
                                        Kind.ASPHALT, resolved.facing(), null, 
                                        resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                }
            } else if (otherRoadwayKeys.contains(key) && !continuationCellKeys.contains(key)) {
                if (resolved.kind() == Kind.SIDEWALK || resolved.kind() == Kind.CURB || resolved.kind() == Kind.GRASS || resolved.kind() == Kind.LINE) {
                    resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), Kind.ASPHALT, resolved.facing(), null, resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                }
            } else if (intersectingOther != null && !continuationCellKeys.contains(key)) {
                if (resolved.kind() == Kind.SIDEWALK || resolved.kind() == Kind.CURB || resolved.kind() == Kind.GRASS || resolved.kind() == Kind.LINE) {
                    if (otherRoadwayKeys.contains(key) || c.kind() == Kind.ASPHALT) {
                        resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), Kind.ASPHALT, resolved.facing(), null, resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                    }
                }
            }

            finalCells.add(resolved);
        }

        // 4. Block state definitions
        BlockState roadwayState = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState foundationState = Blocks.STONE.defaultBlockState();
        BlockState curbBlock = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();
        BlockState centerMarkingBlock = Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirtState = Blocks.DIRT.defaultBlockState();

        int totalWidth = preset.getTotalWidth();
        boolean viaductEnabled = preset.getViaductMode() != ModularRoadPreset.Mode.OFF;
        boolean tunnelEnabled = preset.getTunnelMode() != ModularRoadPreset.Mode.OFF;
        boolean embankmentEnabled = preset.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF;
        LitematicParser.Schematic pillarSchematic = RoadPackRegistry.getInstance().getActivePack().getSchematic("Road_estacada_opora_new");
        if (pillarSchematic == null) {
            pillarSchematic = RoadPackRegistry.getInstance().getActivePack().getSchematic("road_opora");
        }
        LitematicParser.Schematic winosSchematic = RoadPackRegistry.getInstance().getActivePack().getSchematic("Road_estacada_opora_winos");
        int[] pillarPositions = preset.getPillarPositions();

        Set<Long> roadDeckColumns = new HashSet<>();
        for (Cell c : finalCells) {
            roadDeckColumns.add(coordKey(c.x(), c.z()));
        }
        for (List<Cell> cells : otherRoadCells.values()) {
            for (Cell c : cells) {
                roadDeckColumns.add(coordKey(c.x(), c.z()));
            }
        }

        // Pre-determine ground height and viaduct state per column with debouncing & overpass preservation
        Map<Long, Boolean> viaductActiveMap = new HashMap<>();

        double totalCurveLen = curve.getApproximateLength();
        int totalStations = (int) Math.ceil(totalCurveLen) + 1;
        boolean[] viaductStations = new boolean[totalStations];

        for (int s = 0; s < totalStations; s++) {
            double dist = Math.min(totalCurveLen, (double) s);
            double tParam = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(tParam);
            int sx = (int) Math.round(pt.x);
            int sz = (int) Math.round(pt.z);
            int sy = (int) Math.round(pt.y);

            long sKey = coordKey(sx, sz);
            int gY = groundYMap.computeIfAbsent(sKey, k -> getTrueGroundY(level, sx, sz));
            int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz);
            boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos(sx, gY, sz)).isEmpty();
            boolean isElevated = (sy - gY) >= 6;
            if (!isElevated) {
                // If centerline check hit a pillar or narrow obstacle, sample terrain outside the road deck
                Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                double sampleOffset = (totalWidth / 2.0) + 3.0;
                int offX = (int) Math.round(norm.x * sampleOffset);
                int offZ = (int) Math.round(norm.z * sampleOffset);
                int gY_left = groundYMap.computeIfAbsent(coordKey(sx + offX, sz + offZ), k -> getTrueGroundY(level, sx + offX, sz + offZ));
                int gY_right = groundYMap.computeIfAbsent(coordKey(sx - offX, sz - offZ), k -> getTrueGroundY(level, sx - offX, sz - offZ));
                int minGY = Math.min(gY, Math.min(gY_left, gY_right));
                if ((sy - minGY) >= 6) {
                    isElevated = true;
                }
            }

            boolean nearLowerRoad = false;
            for (double crossT : lowerCrossingT) {
                if (Math.abs(dist - crossT) <= 10.0) {
                    nearLowerRoad = true;
                    break;
                }
            }

            boolean active = viaductEnabled && (
                    preset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS ||
                    nearLowerRoad ||
                    (preset.getViaductMode() == ModularRoadPreset.Mode.AUTO && (overWater || isElevated))
            );
            viaductStations[s] = active;
        }

        // Viaduct Debouncing: bridge small non-viaduct gaps <= 24m between viaduct sections (eliminates micro-embankments on bridges)
        for (int i = 0; i < totalStations; i++) {
            if (viaductStations[i]) {
                for (int j = i + 2; j < Math.min(totalStations, i + 26); j++) {
                    if (viaductStations[j]) {
                        for (int k = i + 1; k < j; k++) {
                            viaductStations[k] = true;
                        }
                        break;
                    }
                }
            }
        }

        // Precalculate distance along road to the nearest viaduct station (used for smooth 45° taper approaching viaduct)
        double[] distToViaduct = new double[totalStations];
        for (int s = 0; s < totalStations; s++) {
            if (viaductStations[s]) {
                distToViaduct[s] = 0.0;
            } else {
                double minD = Double.MAX_VALUE;
                for (int v = 0; v < totalStations; v++) {
                    if (viaductStations[v]) {
                        minD = Math.min(minD, Math.abs(s - v));
                    }
                }
                distToViaduct[s] = minD;
            }
        }

        // Precalculate tunnel sections per station:
        // Tunnels generate ONLY when the road is COMPLETELY inside the mountain across its entire width (no half-tunnels)
        boolean[] tunnelStations = new boolean[totalStations];
        double halfRoadW = totalWidth / 2.0;

        if (tunnelEnabled) {
            for (int s = 0; s < totalStations; s++) {
                if (preset.getTunnelMode() == ModularRoadPreset.Mode.ALWAYS) {
                    tunnelStations[s] = true;
                    continue;
                }

                double dist = Math.min(totalCurveLen, (double) s);
                double tParam = curve.getArcLengthTable().getTForDistance(dist);
                Vector3d pt = curve.evaluate(tParam);
                Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                int sy = (int) Math.round(pt.y);

                // Sample 5 points across the entire road cross-section: Left edge, Left quarter, Center, Right quarter, Right edge
                double[] lateralOffsets = new double[]{
                        -(halfRoadW - 0.5),
                        -(halfRoadW * 0.5),
                        0.0,
                        (halfRoadW * 0.5),
                        (halfRoadW - 0.5)
                };

                boolean allPointsInsideMountain = true;

                for (double offset : lateralOffsets) {
                    int px = (int) Math.round(pt.x + norm.x * offset);
                    int pz = (int) Math.round(pt.z + norm.z * offset);

                    int gY = groundYMap.computeIfAbsent(coordKey(px, pz), k -> getTrueGroundY(level, px, pz));
                    int overhead = gY - sy;

                    // All points across the road cross-section must be covered by at least 7 blocks of mountain terrain
                    if (overhead < 7) {
                        allPointsInsideMountain = false;
                        break;
                    }

                    // Check for solid rock/mountain mass above the arch ceiling (sy + 6 to sy + 10)
                    BlockPos pPos = new BlockPos(px, sy, pz);
                    int solidCount = 0;
                    for (int dy = 6; dy <= 10; dy++) {
                        BlockState checkState = level.getBlockState(pPos.above(dy));
                        if (!checkState.isAir() && !checkState.is(BlockTags.LEAVES) && !checkState.is(BlockTags.LOGS) && checkState.isSolid()) {
                            solidCount++;
                        }
                    }

                    if (solidCount < 3) {
                        allPointsInsideMountain = false;
                        break;
                    }
                }

                tunnelStations[s] = allPointsInsideMountain;
            }

            // Debounce short tunnel segments: an auto tunnel must be at least 4 stations (meters) long
            for (int i = 0; i < totalStations; i++) {
                if (tunnelStations[i]) {
                    int len = 0;
                    while (i + len < totalStations && tunnelStations[i + len]) {
                        len++;
                    }
                    if (len < 4 && preset.getTunnelMode() != ModularRoadPreset.Mode.ALWAYS) {
                        for (int k = 0; k < len; k++) {
                            tunnelStations[i + k] = false;
                        }
                    }
                    i += len;
                }
            }

            // Bridge small 1-2 block gaps in tunnels under mountain ridges
            for (int i = 0; i < totalStations; i++) {
                if (tunnelStations[i]) {
                    for (int j = i + 2; j <= Math.min(totalStations - 1, i + 3); j++) {
                        if (tunnelStations[j]) {
                            for (int k = i + 1; k < j; k++) {
                                tunnelStations[k] = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Per-station cut depth sampling and longitudinal smoothing (eliminates EKG pulse/sawtooth spikes & flat field slopes)
        int[] cutDepthL = new int[totalStations];
        int[] cutDepthR = new int[totalStations];

        for (int s = 0; s < totalStations; s++) {
            if (viaductStations[s] || tunnelStations[s]) {
                cutDepthL[s] = 0;
                cutDepthR[s] = 0;
                continue;
            }

            double dist = Math.min(totalCurveLen, (double) s);
            double tParam = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(tParam);
            Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
            int sy = (int) Math.round(pt.y);

            // Left side (-norm): sample at road edge + 1.5 blocks
            int lx = (int) Math.round(pt.x - norm.x * (halfRoadW + 1.5));
            int lz = (int) Math.round(pt.z - norm.z * (halfRoadW + 1.5));
            int lgY = groundYMap.computeIfAbsent(coordKey(lx, lz), k -> getTrueGroundY(level, lx, lz));
            int cutL = lgY - sy;
            cutDepthL[s] = (cutL >= 3) ? cutL : 0; // Cut depth threshold: >= 3 blocks required; flat field (0-2 blocks) yields 0

            // Right side (+norm): sample at road edge + 1.5 blocks
            int rx = (int) Math.round(pt.x + norm.x * (halfRoadW + 1.5));
            int rz = (int) Math.round(pt.z + norm.z * (halfRoadW + 1.5));
            int rgY = groundYMap.computeIfAbsent(coordKey(rx, rz), k -> getTrueGroundY(level, rx, rz));
            int cutR = rgY - sy;
            cutDepthR[s] = (cutR >= 3) ? cutR : 0;
        }

        // Debounce single-station spikes (isolated trees/boulders)
        for (int s = 0; s < totalStations; s++) {
            int prevL = (s > 0) ? cutDepthL[s - 1] : 0;
            int nextL = (s < totalStations - 1) ? cutDepthL[s + 1] : 0;
            if (cutDepthL[s] > 0 && prevL == 0 && nextL == 0) {
                cutDepthL[s] = 0;
            }

            int prevR = (s > 0) ? cutDepthR[s - 1] : 0;
            int nextR = (s < totalStations - 1) ? cutDepthR[s + 1] : 0;
            if (cutDepthR[s] > 0 && prevR == 0 && nextR == 0) {
                cutDepthR[s] = 0;
            }
        }

        // 5-point moving average smoothing along the road
        int[] smoothCutL = new int[totalStations];
        int[] smoothCutR = new int[totalStations];
        for (int s = 0; s < totalStations; s++) {
            if (cutDepthL[s] == 0) {
                smoothCutL[s] = 0;
            } else {
                int sum = 0, count = 0;
                for (int d = -2; d <= 2; d++) {
                    int idx = s + d;
                    if (idx >= 0 && idx < totalStations && cutDepthL[idx] > 0) {
                        sum += cutDepthL[idx];
                        count++;
                    }
                }
                smoothCutL[s] = (count > 0) ? (int) Math.round((double) sum / count) : 0;
                if (smoothCutL[s] < 3) smoothCutL[s] = 0;
            }

            if (cutDepthR[s] == 0) {
                smoothCutR[s] = 0;
            } else {
                int sum = 0, count = 0;
                for (int d = -2; d <= 2; d++) {
                    int idx = s + d;
                    if (idx >= 0 && idx < totalStations && cutDepthR[idx] > 0) {
                        sum += cutDepthR[idx];
                        count++;
                    }
                }
                smoothCutR[s] = (count > 0) ? (int) Math.round((double) sum / count) : 0;
                if (smoothCutR[s] < 3) smoothCutR[s] = 0;
            }
        }

        // Bidirectional slope limiter: rate of change between adjacent stations cannot exceed 1 block per meter
        for (int s = 1; s < totalStations; s++) {
            if (smoothCutL[s] > 0 && smoothCutL[s - 1] > 0 && smoothCutL[s] > smoothCutL[s - 1] + 1) {
                smoothCutL[s] = smoothCutL[s - 1] + 1;
            }
            if (smoothCutR[s] > 0 && smoothCutR[s - 1] > 0 && smoothCutR[s] > smoothCutR[s - 1] + 1) {
                smoothCutR[s] = smoothCutR[s - 1] + 1;
            }
        }
        for (int s = totalStations - 2; s >= 0; s--) {
            if (smoothCutL[s] > 0 && smoothCutL[s + 1] > 0 && smoothCutL[s] > smoothCutL[s + 1] + 1) {
                smoothCutL[s] = smoothCutL[s + 1] + 1;
            }
            if (smoothCutR[s] > 0 && smoothCutR[s + 1] > 0 && smoothCutR[s] > smoothCutR[s + 1] + 1) {
                smoothCutR[s] = smoothCutR[s + 1] + 1;
            }
        }

        for (Cell cell : finalCells) {
            long key = coordKey(cell.x(), cell.z());
            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
            boolean viaductActive = viaductStations[stIdx];
            viaductActiveMap.put(key, viaductActive);
        }

        // 5. Convert cells to Minecraft blocks
        for (Cell cell : finalCells) {
            BlockPos surfacePos = new BlockPos(cell.x(), cell.y(), cell.z());
            long cellKey = coordKey(cell.x(), cell.z());

            BlockState surfaceState;
            if (cell.state() != null) {
                surfaceState = cell.state();
            } else {
                surfaceState = switch (cell.kind()) {
                    case ASPHALT -> roadwayState;
                    case LINE -> orientStairs(centerMarkingBlock, cell.facing(), Half.BOTTOM);
                    case CURB -> orientStairs(curbBlock, cell.facing(), Half.BOTTOM);
                    case GRASS -> grassState;
                    case STONE -> foundationState;
                    case SIDEWALK -> {
                        // 64-битный SplitMix64 хеш-миксер для равномерного белого шума без полос (Bug 11)
                        long h = ((long) cell.x() * 0x9E3779B97F4A7C15L) ^ ((long) cell.z() * 0xBF58476D1CE4E5B9L) ^ ((long) cell.y() * 0x94D049BB133111EBL);
                        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
                        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
                        h = h ^ (h >>> 31);
                        int idx = (int) Math.floorMod(h, (long) SIDEWALK_MIX.length);
                        yield SIDEWALK_MIX[idx];
                    }
                };
            }

            // Surface block
            blockMap.put(surfacePos, surfaceState);

            // Foundation underneath
            boolean isViaductDeck = viaductActiveMap.getOrDefault(cellKey, false);
            if (isViaductDeck) {
                blockMap.put(surfacePos.below(), foundationState);
            } else if (cell.kind() == Kind.GRASS) {
                blockMap.put(surfacePos.below(), dirtState);
            } else {
                blockMap.put(surfacePos.below(), foundationState);
            }

            // Check if another road passes above this cell
            List<Cell> existingAtCoord = otherRoadCells.get(cellKey);
            boolean roadAbove = false;
            int maxClearanceH = 5;
            if (existingAtCoord != null) {
                for (Cell otherC : existingAtCoord) {
                    if (otherC.y() > cell.y() + 2) {
                        roadAbove = true;
                        maxClearanceH = Math.min(maxClearanceH, otherC.y() - cell.y() - 1);
                    }
                }
            }

            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
            boolean isTunnelSection = !roadAbove && tunnelStations[stIdx];

            int worldSurfaceAtRoad = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.x(), cell.z());
            int terrainOverhead = worldSurfaceAtRoad - cell.y();

            // Расчет арочного свода туннеля (Bug 8)
            double halfW = Math.max(1.0, (totalWidth - 1) / 2.0);
            double uNorm = (cell.localX() - halfW) / halfW;
            uNorm = Math.max(-1.0, Math.min(1.0, uNorm));
            int archHeight = (int) Math.round(6.0 - 2.0 * (uNorm * uNorm)); // 6 в центре, 4 по краям

            if (roadAbove) {
                // Если сверху проходит другая эстакада/дорога
                for (int h = 1; h <= maxClearanceH; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            } else if (isTunnelSection) {
                // Внутри туннеля: очищаем воздух под арочным сводом
                for (int h = 1; h <= archHeight; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
                // Каменный свод туннеля толщиной в 2 блока
                blockMap.put(surfacePos.above(archHeight + 1), foundationState);
                blockMap.put(surfacePos.above(archHeight + 2), foundationState);

                // Вертикальные боковые стены туннеля
                if (cell.localX() == 0 || cell.localX() == totalWidth - 1) {
                    for (int h = 1; h <= archHeight; h++) {
                        blockMap.put(surfacePos.above(h), foundationState);
                    }
                }
            } else {
                // Открытый участок или въездная выемка в склоне (open-cut)
                // Очищаем воздух до поверхности земли + 2 блока, устраняя нависающий дерн
                int clearH = Math.max(maxClearanceH, terrainOverhead + 2);
                for (int h = 1; h <= clearH; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            }

            int terrainSurfaceY = groundYMap.computeIfAbsent(cellKey, k -> getTrueGroundY(level, cell.x(), cell.z()));
            boolean viaductActive = viaductActiveMap.getOrDefault(cellKey, false);
            boolean isOverLowerRoadCell = lowerRoadClearanceZone.contains(cellKey);

            if (viaductActive && !isTunnelSection) {
                // 1. Viaduct profile underneath (matching Road_estacada_profile.litematic: 100% solid stone trapezoid)
                // At below(1): stone across the deck
                blockMap.put(surfacePos.below(1), foundationState);
                // At below(2): stone inset by 2 blocks on left and right
                if (cell.localX() >= 2 && cell.localX() <= totalWidth - 3) {
                    blockMap.put(surfacePos.below(2), foundationState);
                }
                // At below(3): stone inset by 2 blocks on left and right
                if (cell.localX() >= 2 && cell.localX() <= totalWidth - 3) {
                    blockMap.put(surfacePos.below(3), foundationState);
                }

                // 2. Pillars: Standard (Road_estacada_opora_new) or Outrigger Bent (Road_estacada_opora_winos)
                double dToViaduct = (stIdx >= 0 && stIdx < totalStations) ? distToViaduct[stIdx] : 0.0;
                if (dToViaduct == 0.0) {
                    int pillarCycle = Math.floorMod((int) Math.round(cell.t()), 18);
                    boolean isNearLowerRoad = isOverLowerRoadCell;
                    if (!isNearLowerRoad) {
                        for (double lt : lowerCrossingT) {
                            if (Math.abs(cell.t() - lt) <= 5.0) {
                                isNearLowerRoad = true;
                                break;
                            }
                        }
                    }

                    if (isNearLowerRoad && pillarCycle < 5) {
                        // Outrigger bent (Road_estacada_opora_winos.litematic)
                        // Solid stone crossbeam 1 block below road, spanning across lower road
                        blockMap.put(surfacePos.below(1), foundationState);
                        blockMap.put(surfacePos.below(2), foundationState);
                        blockMap.put(surfacePos.below(3), foundationState);

                        // Side pillars (5x5 Half.TOP capital + 3x3 column) outside lower road clearance zone
                        int leftPx = 0;
                        int rightPx = Math.max(0, totalWidth - 5);
                        if (!isOverLowerRoadCell) {
                            placePillarSection(level, blockMap, surfacePos, cell, leftPx, pillarCycle, curbBlock, foundationState);
                            if (rightPx > leftPx + 4) {
                                placePillarSection(level, blockMap, surfacePos, cell, rightPx, pillarCycle, curbBlock, foundationState);
                            }
                        }
                    } else if (!isOverLowerRoadCell && pillarCycle < 5) {
                        // Standard viaduct pillar (Road_estacada_opora_new.litematic)
                        for (int px : pillarPositions) {
                            placePillarSection(level, blockMap, surfacePos, cell, px, pillarCycle, curbBlock, foundationState);
                        }
                    }
                }
            }

            // Embankment foundation under the road deck (strictly forbidden over lower roads, in water, or near viaducts/connected endings)
            boolean nearConnectedEnd = (cell.t() < 6.0 && (p0Connected || p0ConnectsToViaduct)) ||
                                       (cell.t() > totalCurveLen - 6.0 && (p3Connected || p3ConnectsToViaduct));
            double dToViaduct = (stIdx >= 0 && stIdx < totalStations) ? distToViaduct[stIdx] : 0.0;
            boolean nearViaduct = dToViaduct <= 6.0 || viaductActive;
            if (embankmentEnabled && !viaductActive && !nearViaduct && !isTunnelSection && !isOverLowerRoadCell && !nearConnectedEnd) {
                BlockPos tp = new BlockPos(cell.x(), terrainSurfaceY, cell.z());
                boolean isWater = !level.getFluidState(tp).isEmpty() || level.getBlockState(tp).is(Blocks.WATER);
                if (!isWater && cell.y() - 1 > terrainSurfaceY && cell.y() - terrainSurfaceY < 6) {
                    for (int ey = cell.y() - 2; ey >= terrainSurfaceY; ey--) {
                        BlockPos fp = new BlockPos(cell.x(), ey, cell.z());
                        blockMap.put(fp, dirtState);
                    }
                }
            } else if (nearViaduct && !viaductActive && !isTunnelSection && !isOverLowerRoadCell && cell.y() - 1 > terrainSurfaceY) {
                // Bridge abutment approaching viaduct: solid stone foundation, NEVER dirt!
                for (int ey = cell.y() - 2; ey >= terrainSurfaceY; ey--) {
                    BlockPos fp = new BlockPos(cell.x(), ey, cell.z());
                    blockMap.put(fp, foundationState);
                }
            }

            // Clear underpass clearance between upper bridge deck and lower road
            if (isOverLowerRoadCell) {
                int lowerY = lowerRoadSurfaceY.getOrDefault(cellKey, terrainSurfaceY);
                for (int ey = lowerY + 1; ey <= cell.y() - 2; ey++) {
                    BlockPos ap = new BlockPos(cell.x(), ey, cell.z());
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            }

            // Bridge Abutment (Устой моста) and Road Endings - 1-2 block reinforcement under deck, zero monolithic walls to ground
            boolean isAbutmentOrEnd = false;
            if (cell.t() < 1.0 && !p0Connected) {
                isAbutmentOrEnd = true;
            } else if (cell.t() > totalCurveLen - 1.0 && !p3Connected) {
                isAbutmentOrEnd = true;
            } else {
                boolean prevV = (stIdx > 0) && viaductStations[stIdx - 1];
                boolean nextV = (stIdx < totalStations - 1) && viaductStations[stIdx + 1];
                if (prevV != viaductActive || nextV != viaductActive) {
                    isAbutmentOrEnd = true;
                }
            }

            if (isAbutmentOrEnd && !isTunnelSection && !isOverLowerRoadCell && cell.y() - 1 > terrainSurfaceY) {
                int minReinforceY = Math.max(terrainSurfaceY, cell.y() - 2);
                BlockState endReinforceState = foundationState; // Solid stone/concrete foundation, NEVER dirt!
                for (int ey = cell.y() - 1; ey >= minReinforceY; ey--) {
                    BlockPos ap = new BlockPos(cell.x(), ey, cell.z());
                    blockMap.put(ap, endReinforceState);
                }
            }
        }

        // 6. Generate Continuous Side Embankments and Slopes - zero holes, zero voids at endings
        boolean slopeEnabled = preset.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF;

        if (embankmentEnabled || slopeEnabled) {
            double maxRadius = (embankmentEnabled || slopeEnabled) ? 14.0 : 3.0;

            boolean shouldRoundP0 = !p0Connected && !viaductStations[0];
            boolean shouldRoundP3 = !p3Connected && !viaductStations[totalStations - 1];

            Vector3d p0 = curve.getP0();
            Vector3d tan0 = curve.evaluateTangent(0.0);
            double t0Len = Math.hypot(tan0.x, tan0.z);
            double t0x = (t0Len > 1e-6) ? (tan0.x / t0Len) : 0.0;
            double t0z = (t0Len > 1e-6) ? (tan0.z / t0Len) : 1.0;
            int roadY0 = (int) Math.round(p0.y);

            Vector3d p3 = curve.getP3();
            Vector3d tan3 = curve.evaluateTangent(1.0);
            double t3Len = Math.hypot(tan3.x, tan3.z);
            double t3x = (t3Len > 1e-6) ? (tan3.x / t3Len) : 0.0;
            double t3z = (t3Len > 1e-6) ? (tan3.z / t3Len) : 1.0;
            int roadY3 = (int) Math.round(p3.y);

            Map<Long, Integer> roadDeckY = new HashMap<>();
            Map<Long, Double> roadDeckT = new HashMap<>();
            for (Cell c : finalCells) {
                long k = coordKey(c.x(), c.z());
                roadDeckY.put(k, c.y());
                roadDeckT.put(k, c.t());
            }

            // Multi-source BFS dilation outward from road deck blocks:
            // Expands neighbor-by-neighbor in 8 directions, guaranteeing 100% gap-free grid coverage
            // along straight roads, diagonals, sharp curves, and end-caps!
            Map<Long, Long> bestSource = new HashMap<>();
            Map<Long, Double> bestDist = new HashMap<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();

            for (Cell c : finalCells) {
                long k = coordKey(c.x(), c.z());
                bestSource.put(k, k);
                bestDist.put(k, 0.0);
                queue.add(k);
            }

            int[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
            };

            while (!queue.isEmpty()) {
                long curKey = queue.poll();
                int cx = (int) (curKey >> 32);
                int cz = (int) curKey;
                long srcKey = bestSource.get(curKey);
                int sx = (int) (srcKey >> 32);
                int sz = (int) srcKey;

                for (int[] d : dirs) {
                    int nx = cx + d[0];
                    int nz = cz + d[1];
                    long nkey = coordKey(nx, nz);

                    if (roadDeckColumns.contains(nkey)) continue;
                    if (lowerRoadClearanceZone.contains(nkey)) continue;

                    double dist = Math.hypot(nx - sx, nz - sz);
                    if (dist <= maxRadius) {
                        Double prevD = bestDist.get(nkey);
                        if (prevD == null || dist < prevD) {
                            bestDist.put(nkey, dist);
                            bestSource.put(nkey, srcKey);
                            queue.add(nkey);
                        }
                    }
                }
            }

            Map<Long, SideCandidate> sideCandidates = new HashMap<>();

            for (Map.Entry<Long, Double> entry : bestDist.entrySet()) {
                long colKey = entry.getKey();
                if (roadDeckColumns.contains(colKey)) continue;

                int bx = (int) (colKey >> 32);
                int bz = (int) colKey;
                double dz = entry.getValue();

                long srcKey = bestSource.get(colKey);
                int sx = (int) (srcKey >> 32);
                int sz = (int) srcKey;
                int roadY = roadDeckY.getOrDefault(srcKey, roadY0);
                double t = roadDeckT.getOrDefault(srcKey, 0.0);

                // Check if candidate is past P0 or P3
                double proj0 = (bx - p0.x) * (-t0x) + (bz - p0.z) * (-t0z);
                double proj3 = (bx - p3.x) * t3x + (bz - p3.z) * t3z;

                boolean isEndCap = false;
                int side = 0;
                double outX = 0;
                double outZ = 0;

                if (proj0 > 0.01) {
                    if (!shouldRoundP0) {
                        continue;
                    }
                    isEndCap = true;
                    roadY = roadY0;
                    t = 0.0;
                    outX = -t0x;
                    outZ = -t0z;
                } else if (proj3 > 0.01) {
                    if (!shouldRoundP3) {
                        continue;
                    }
                    isEndCap = true;
                    roadY = roadY3;
                    t = totalCurveLen;
                    outX = t3x;
                    outZ = t3z;
                } else {
                    double tParam = curve.getArcLengthTable().getTForDistance(Math.min(totalCurveLen, Math.max(0.0, t)));
                    Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                    double dot = (bx - sx) * norm.x + (bz - sz) * norm.z;
                    side = (dot < 0) ? -1 : 1;
                    outX = norm.x * side;
                    outZ = norm.z * side;
                }

                sideCandidates.put(colKey, new SideCandidate(bx, bz, roadY, dz, t, outX, outZ, side, isEndCap));
            }

            // Place side embankments and slopes independently per column (never broken by adjacent terrain)
            for (SideCandidate cand : sideCandidates.values()) {
                long colKey = coordKey(cand.bx(), cand.bz());
                if (roadDeckColumns.contains(colKey)) continue;
                if (lowerRoadClearanceZone.contains(colKey)) continue;

                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cand.t())));
                if (viaductStations[stIdx]) continue; // Viaduct bridge slice: no side embankment or slope!
                if (tunnelStations[stIdx]) continue; // Underground mountain tunnel: no open-cut slope!
                if (cand.t() < 3.5 && (p0Connected || p0ConnectsToViaduct)) continue; // Connected start: no side embankment into connected road/viaduct!
                if (cand.t() > totalCurveLen - 3.5 && (p3Connected || p3ConnectsToViaduct)) continue; // Connected end: no side embankment into connected road/viaduct!

                double dz = cand.dz();
                if (dz < 0.1 || dz > maxRadius) continue;

                int groundY = groundYMap.computeIfAbsent(colKey, k -> getTrueGroundY(level, cand.bx(), cand.bz()));
                int roadY = cand.roadY();

                // 1. Hillside cut -> stepped retaining wall slope. Slopes NEVER round around end-caps!
                int cutDepth = (cand.side() < 0) ? smoothCutL[stIdx] : ((cand.side() > 0) ? smoothCutR[stIdx] : 0);

                if (slopeEnabled && !cand.isEndCap() && cutDepth >= 3 && groundY > roadY) {
                    int targetGroundY = roadY + cutDepth;
                    int targetTop = Math.min(groundY, targetGroundY);

                    if (targetTop > roadY) {
                        int stepDist = (int) Math.max(1, Math.round(dz));
                        // Stop stepping outward if the previous step already reached or exceeded targetTop
                        if (stepDist > 1 && (roadY + (stepDist - 1) * 2) >= targetTop) {
                            continue;
                        }

                        int stepHeight = roadY + stepDist * 2;
                        int stepTopY = Math.min(targetTop, stepHeight);

                        if (stepTopY < targetTop) {
                            // Intermediate step: stone retaining wall with grass terrace on top
                            for (int y = roadY; y < stepTopY; y++) {
                                BlockPos bp = new BlockPos(cand.bx(), y, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    blockMap.put(bp, foundationState);
                                }
                            }
                            BlockPos topPos = new BlockPos(cand.bx(), stepTopY, cand.bz());
                            if (!otherActiveRoadBlocks.contains(topPos)) {
                                blockMap.put(topPos, grassState);
                            }
                        } else {
                            // Final step reaching the natural hill: grass on top, dirt underneath down to road level
                            BlockPos topPos = new BlockPos(cand.bx(), stepTopY, cand.bz());
                            if (!otherActiveRoadBlocks.contains(topPos)) {
                                blockMap.put(topPos, grassState);
                            }
                            for (int y = stepTopY - 1; y >= roadY; y--) {
                                BlockPos bp = new BlockPos(cand.bx(), y, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    blockMap.put(bp, dirtState);
                                }
                            }
                        }

                        // Under the road level down to terrain if there is any gap
                        if (roadY - 1 >= groundY) {
                            for (int fy = roadY - 1; fy >= groundY; fy--) {
                                BlockPos bp = new BlockPos(cand.bx(), fy, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    blockMap.put(bp, dirtState);
                                }
                            }
                        }

                        // Air clearing above the step up to the sky (removes all excavated hillside rock/dirt)
                        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, cand.bx(), cand.bz());
                        int clearTopY = Math.max(groundY + 4, worldSurfaceY + 2);
                        for (int ay = stepTopY + 1; ay <= clearTopY; ay++) {
                            BlockPos ap = new BlockPos(cand.bx(), ay, cand.bz());
                            if (!otherActiveRoadBlocks.contains(ap)) {
                                blockMap.put(ap, airState);
                            }
                        }
                        continue;
                    }
                }

                // 2. Embankment (road_nasip)
                // Height threshold: only build embankment where road is elevated between 2 and 5 blocks over ground.
                // For elevation >= 6 blocks, viaduct mode applies, strictly NEVER earth embankment!
                if (roadY - groundY < 2 || roadY - groundY >= 6) {
                    continue;
                }

                // Prohibit embankment in water (Bug 15)
                BlockPos groundPos = new BlockPos(cand.bx(), groundY, cand.bz());
                BlockState groundState = level.getBlockState(groundPos);
                boolean isWater = !groundState.getFluidState().isEmpty()
                        || groundState.is(Blocks.WATER)
                        || groundState.getFluidState().is(FluidTags.WATER)
                        || !level.getFluidState(groundPos).isEmpty()
                        || !level.getFluidState(groundPos.above()).isEmpty()
                        || level.getBlockState(groundPos.above()).is(Blocks.WATER);
                if (isWater) {
                    continue;
                }

                // Smooth 45° taper approaching viaduct: drop topY by 1 block per meter within 4 blocks of viaduct
                int topY = (dz <= 2.0) ? (roadY - 1) : (roadY - 1 - (int) Math.round(dz - 2.0));
                double dToViaduct = distToViaduct[stIdx];
                if (dToViaduct <= 4.0) {
                    int taperDrop = (int) Math.round(4.0 - dToViaduct);
                    if (taperDrop > 0) {
                        topY -= taperDrop;
                    }
                }

                // Embankment placement (pure earth: grass block on surface, dirt underneath down to ground)
                // 100% gap-free, zero voids at endings, zero stone.
                if (embankmentEnabled) {
                    if (topY >= groundY) {
                        BlockPos topPos = new BlockPos(cand.bx(), topY, cand.bz());
                        if (!otherActiveRoadBlocks.contains(topPos)) {
                            blockMap.put(topPos, grassState);
                        }
                        for (int fy = topY - 1; fy >= groundY; fy--) {
                            BlockPos fPos = new BlockPos(cand.bx(), fy, cand.bz());
                            if (!otherActiveRoadBlocks.contains(fPos)) {
                                blockMap.put(fPos, dirtState);
                            }
                        }
                    }
                }
            }
        }

        // 8. Generate junction & roundabout platforms if connecting to JUNCTION / ROUNDABOUT nodes
        if (graph != null) {
            if (junctionAtP0 != null) {
                JunctionGenerator.JunctionStyle style0 = JunctionGenerator.JunctionStyle.fromString(junctionAtP0.getJunctionStyle());
                if (style0 == JunctionGenerator.JunctionStyle.ROUNDABOUT || junctionAtP0.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT) {
                    Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                            level, junctionAtP0, graph.getEdgesForNode(junctionAtP0.getId()), graph
                    );
                    blockMap.putAll(rbBlocks);
                } else {
                    Map<BlockPos, BlockState> jBlocks = JunctionGenerator.generateJunction(
                            level, junctionAtP0, graph.getEdgesForNode(junctionAtP0.getId()), graph, style0
                    );
                    blockMap.putAll(jBlocks);
                }
            }
            if (junctionAtP3 != null) {
                JunctionGenerator.JunctionStyle style3 = JunctionGenerator.JunctionStyle.fromString(junctionAtP3.getJunctionStyle());
                if (style3 == JunctionGenerator.JunctionStyle.ROUNDABOUT || junctionAtP3.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT) {
                    Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                            level, junctionAtP3, graph.getEdgesForNode(junctionAtP3.getId()), graph
                    );
                    blockMap.putAll(rbBlocks);
                } else {
                    Map<BlockPos, BlockState> jBlocks = JunctionGenerator.generateJunction(
                            level, junctionAtP3, graph.getEdgesForNode(junctionAtP3.getId()), graph, style3
                    );
                    blockMap.putAll(jBlocks);
                }
            }
            if (roundaboutAtP0 != null && junctionAtP0 == null) {
                Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                        level, roundaboutAtP0, graph.getEdgesForNode(roundaboutAtP0.getId()), graph
                );
                blockMap.putAll(rbBlocks);
            }
            if (roundaboutAtP3 != null && junctionAtP3 == null) {
                Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                        level, roundaboutAtP3, graph.getEdgesForNode(roundaboutAtP3.getId()), graph
                );
                blockMap.putAll(rbBlocks);
            }
        }

        return blockMap;
    }

    public static boolean isRoadBlock(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            try {
                com.roadbuilder.mod.server.storage.RoadGraphSavedData savedData = com.roadbuilder.mod.server.storage.RoadGraphSavedData.get(serverLevel);
                if (savedData != null && savedData.isRoadBlock(pos)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return state.is(Blocks.GRAY_CONCRETE_POWDER)
                || state.is(Blocks.GRAY_CONCRETE)
                || state.is(Blocks.BLACK_CONCRETE)
                || state.is(Blocks.CYAN_TERRACOTTA)
                || state.is(Blocks.STONE)
                || state.is(Blocks.STONE_STAIRS)
                || state.is(Blocks.STONE_SLAB)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.COBBLESTONE_STAIRS)
                || state.is(Blocks.COBBLESTONE_SLAB)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.SMOOTH_STONE_SLAB)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.POLISHED_ANDESITE_STAIRS)
                || state.is(Blocks.POLISHED_ANDESITE_SLAB)
                || state.is(Blocks.SMOOTH_QUARTZ)
                || state.is(Blocks.SMOOTH_QUARTZ_STAIRS)
                || state.is(Blocks.SMOOTH_QUARTZ_SLAB);
    }

    public static int getTrueGroundY(Level level, int x, int z) {
        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);

        for (int y = startY; y >= level.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) continue;

            // Water surface counts as solid ground level
            if (!state.getFluidState().isEmpty() || state.is(Blocks.WATER)) {
                return y;
            }

            // Skip existing road and bridge structures so elevated decks/pillars are never treated as natural terrain
            if (isRoadBlock(level, pos, state)) {
                continue;
            }

            // Skip leaves and tree foliage
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                continue;
            }

            // Skip elevated structures, bridge decks, and pillar columns that float above terrain
            boolean isElevatedStructure = false;
            for (int dy = 1; dy <= 24; dy++) {
                if (y - dy < level.getMinBuildHeight()) break;
                if (level.getBlockState(pos.below(dy)).isAir()) {
                    isElevatedStructure = true;
                    break;
                }
            }
            if (!isElevatedStructure && (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || isRoadBlock(level, pos, state))) {
                int airNeighbors = 0;
                if (level.getBlockState(pos.offset(2, 0, 0)).isAir()) airNeighbors++;
                if (level.getBlockState(pos.offset(-2, 0, 0)).isAir()) airNeighbors++;
                if (level.getBlockState(pos.offset(0, 0, 2)).isAir()) airNeighbors++;
                if (level.getBlockState(pos.offset(0, 0, -2)).isAir()) airNeighbors++;
                if (airNeighbors >= 2 && level.getBlockState(pos.below(3).offset(2, 0, 0)).isAir()) {
                    isElevatedStructure = true;
                }
            }
            if (isElevatedStructure) {
                continue;
            }

            // Skip tree logs
            if (state.is(BlockTags.LOGS) || state.getBlock() instanceof RotatedPillarBlock) {
                continue;
            }

            // Skip flowers, tall grass, crops, mushrooms, replaceable plants, foliage, snow, carpet
            if (state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.REPLACEABLE) ||
                state.is(BlockTags.CROPS) ||
                state.is(BlockTags.SAPLINGS) ||
                state.is(BlockTags.BEE_GROWABLES) ||
                state.is(Blocks.MOSS_CARPET) ||
                state.getBlock() instanceof BushBlock ||
                state.getBlock() instanceof DoublePlantBlock ||
                state.getBlock() instanceof CarpetBlock ||
                state.getBlock() instanceof SnowLayerBlock ||
                state.getBlock() instanceof VineBlock ||
                state.getBlock() instanceof SugarCaneBlock ||
                state.getBlock() instanceof CactusBlock ||
                state.getBlock() instanceof BambooStalkBlock ||
                state.getBlock() instanceof AzaleaBlock ||
                !state.blocksMotion()) {
                continue;
            }

            // True solid ground (grass block, dirt, stone, sand, gravel, terracotta, etc.)
            return y;
        }
        return level.getMinBuildHeight();
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        return calculateRoadBlocks(level, curve, preset);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset) {
        return planCurveCells(curve, preset, null, null, null, null);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset,
                                            TransitionProfile transitionAtP0, TransitionProfile transitionAtP3) {
        return planCurveCells(curve, preset, transitionAtP0, transitionAtP3, null, null);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset,
                                            TransitionProfile transitionAtP0, TransitionProfile transitionAtP3,
                                            Vector3d startCutNormal, Vector3d endCutNormal) {
        List<Cell> out = new ArrayList<>();

        double totalLen = curve.getApproximateLength();
        if (totalLen < 0.5) return out;

        List<RoadStackElement> stack = preset.getStack();
        if (stack.isEmpty()) return out;

        int totalW = preset.getTotalWidth();
        double halfWidth = totalW / 2.0;

        int numSamples = Math.max(8, (int) Math.ceil(totalLen / 0.5));
        double[] px = new double[numSamples + 1];
        double[] py = new double[numSamples + 1];
        double[] pz = new double[numSamples + 1];
        double[] cum = new double[numSamples + 1];

        for (int i = 0; i <= numSamples; i++) {
            double dist = (double) i / numSamples * totalLen;
            double t = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(t);
            px[i] = pt.x;
            py[i] = pt.y;
            pz[i] = pt.z;
            cum[i] = dist;
        }

        int segs = numSamples;

        // Cumulative intervals for each stack element
        int numElems = stack.size();
        double[] elemStart = new double[numElems];
        double[] elemEnd = new double[numElems];
        double cur = -halfWidth;
        for (int i = 0; i < numElems; i++) {
            elemStart[i] = cur;
            cur += stack.get(i).getWidth();
            elemEnd[i] = cur;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i <= segs; i++) {
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }
        double maxHalfW = halfWidth;
        if (transitionAtP0 != null) maxHalfW = Math.max(maxHalfW, transitionAtP0.getStackAt(0.0).totalWidth() * 0.5);
        if (transitionAtP3 != null) maxHalfW = Math.max(maxHalfW, transitionAtP3.getStackAt(1.0).totalWidth() * 0.5);
        int r = (int) Math.ceil(maxHalfW * 1.5) + 3;
        int x0 = (int) Math.floor(minX) - r, x1 = (int) Math.ceil(maxX) + r;
        int z0 = (int) Math.floor(minZ) - r, z1 = (int) Math.ceil(maxZ) + r;

        Vector3d p0 = curve.getP0();
        Vector3d p3 = curve.getP3();
        Vector3d tan0 = curve.evaluateTangent(0.0);
        Vector3d tan3 = curve.evaluateTangent(1.0);

        double t0x, t0z;
        if (startCutNormal != null && (startCutNormal.x != 0 || startCutNormal.z != 0)) {
            double len = Math.hypot(startCutNormal.x, startCutNormal.z);
            t0x = startCutNormal.x / len;
            t0z = startCutNormal.z / len;
        } else {
            double t0Len = Math.hypot(tan0.x, tan0.z);
            t0x = t0Len > 1e-6 ? tan0.x / t0Len : 0.0;
            t0z = t0Len > 1e-6 ? tan0.z / t0Len : 1.0;
        }

        double t3x, t3z;
        if (endCutNormal != null && (endCutNormal.x != 0 || endCutNormal.z != 0)) {
            double len = Math.hypot(endCutNormal.x, endCutNormal.z);
            t3x = endCutNormal.x / len;
            t3z = endCutNormal.z / len;
        } else {
            double t3Len = Math.hypot(tan3.x, tan3.z);
            t3x = t3Len > 1e-6 ? tan3.x / t3Len : 0.0;
            t3z = t3Len > 1e-6 ? tan3.z / t3Len : 1.0;
        }

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // Evaluate at world block centers (+0.5, +0.5) to align identically with nodes, junctions, and roundabouts
                double cx = x + 0.5;
                double cz = z + 0.5;

                // Strict half-space bounding planes perpendicular to road centerline or joint miter at curve start and end
                double startProj = (cx - p0.x) * t0x + (cz - p0.z) * t0z;
                double endProj = (cx - p3.x) * t3x + (cz - p3.z) * t3z;
                if (startProj < -0.01 || endProj > 0.01) {
                    continue;
                }

                double bestD2 = Double.MAX_VALUE, bestRaw = 0, bestU = 0;
                int best = -1;
                for (int i = 0; i < segs; i++) {
                    double ax = px[i], az = pz[i];
                    double vx = px[i + 1] - ax, vz = pz[i + 1] - az;
                    double len2 = vx * vx + vz * vz;
                    if (len2 < 1e-12) continue;
                    double wx = cx - ax, wz = cz - az;
                    double raw = (wx * vx + wz * vz) / len2;
                    double u = Math.max(0.0, Math.min(1.0, raw));
                    double dx = wx - u * vx, dz = wz - u * vz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = i;
                        bestRaw = raw;
                        bestU = u;
                    }
                }

                if (best < 0) continue;

                double vx = px[best + 1] - px[best];
                double vz = pz[best + 1] - pz[best];
                double segLen = Math.hypot(vx, vz);
                if (segLen < 1e-6) continue;
                double ux = vx / segLen, uz = vz / segLen;
                double nx = -uz, nz = ux;

                double s;
                double t;
                int cellY;
                double latDist2;

                if (best == 0 && bestRaw < 0.0) {
                    double wx = cx - p0.x, wz = cz - p0.z;
                    double tanLen = Math.hypot(tan0.x, tan0.z);
                    double dirX = tanLen > 1e-6 ? tan0.x / tanLen : ux;
                    double dirZ = tanLen > 1e-6 ? tan0.z / tanLen : uz;
                    double nX = -dirZ, nZ = dirX;
                    double along = wx * dirX + wz * dirZ;
                    s = wx * nX + wz * nZ;
                    latDist2 = s * s;
                    t = along;
                    cellY = (int) Math.round(py[0]);
                    nx = nX;
                    nz = nZ;
                } else if (best == segs - 1 && bestRaw > 1.0) {
                    double wx = cx - p3.x, wz = cz - p3.z;
                    double tanLen = Math.hypot(tan3.x, tan3.z);
                    double dirX = tanLen > 1e-6 ? tan3.x / tanLen : ux;
                    double dirZ = tanLen > 1e-6 ? tan3.z / tanLen : uz;
                    double nX = -dirZ, nZ = dirX;
                    double along = wx * dirX + wz * dirZ;
                    s = wx * nX + wz * nZ;
                    latDist2 = s * s;
                    t = totalLen + along;
                    cellY = (int) Math.round(py[segs]);
                    nx = nX;
                    nz = nZ;
                } else {
                    double wx = cx - px[best], wz = cz - pz[best];
                    double cross = ux * wz - uz * wx;
                    s = Math.sqrt(bestD2) * (cross >= 0 ? 1 : -1);
                    latDist2 = bestD2;
                    t = cum[best] + bestU * segLen;
                    cellY = (int) Math.round(py[best] + bestU * (py[best + 1] - py[best]));
                }

                if (latDist2 > (maxHalfW + 0.5) * (maxHalfW + 0.5)) continue;

                // Physical Euclidean lateral distance from road centerline (meters)
                double sGrid = s;

                double currentHalfWidth = halfWidth;
                TransitionProfile.InterpolatedStack interpStack = null;
                if (transitionAtP0 != null) {
                    double transLen = Math.min(totalLen, transitionAtP0.getTransitionLength());
                    if (t <= transLen) {
                        double factor = Math.max(0.0, Math.min(1.0, t / Math.max(0.1, transLen)));
                        interpStack = transitionAtP0.getStackAt(factor);
                        currentHalfWidth = interpStack.totalWidth() * 0.5;
                    }
                } else if (transitionAtP3 != null) {
                    double transLen = Math.min(totalLen, transitionAtP3.getTransitionLength());
                    if (t >= totalLen - transLen) {
                        double distFromEnd = Math.max(0.0, totalLen - t);
                        double factor = Math.max(0.0, Math.min(1.0, 1.0 - (distFromEnd / Math.max(0.1, transLen))));
                        interpStack = transitionAtP3.getStackAt(factor);
                        currentHalfWidth = interpStack.totalWidth() * 0.5;
                    }
                }

                if (sGrid < -currentHalfWidth - 0.2 || sGrid >= currentHalfWidth + 0.2) continue;

                RoadElementType type;
                int elemW;
                int localX;
                if (interpStack != null) {
                    TransitionProfile.InterpolatedElement interpElem = interpStack.getElementAt(sGrid);
                    type = interpElem.type();
                    elemW = (int) Math.max(1, Math.round(interpElem.width()));
                    localX = (int) Math.floor(sGrid - interpElem.startOffset());
                    localX = Math.max(0, Math.min(elemW - 1, localX));
                } else {
                    int elemIdx = -1;
                    for (int i = 0; i < numElems; i++) {
                        if (sGrid >= elemStart[i] - EPS && sGrid < elemEnd[i] + EPS) {
                            elemIdx = i;
                            break;
                        }
                    }
                    if (elemIdx < 0) {
                        if (sGrid < elemStart[0]) elemIdx = 0;
                        else elemIdx = numElems - 1;
                    }

                    RoadStackElement elem = stack.get(elemIdx);
                    type = elem.getType();
                    elemW = elem.getWidth();
                    localX = (int) Math.floor(sGrid - elemStart[elemIdx]);
                    localX = Math.max(0, Math.min(elemW - 1, localX));
                }

                boolean isLeftSide = (sGrid < 0);
                Direction facingOutward = facingFromVector(nx * (isLeftSide ? -1 : 1), nz * (isLeftSide ? -1 : 1));

                int roadLocalX = (int) Math.floor(sGrid + currentHalfWidth);
                roadLocalX = Math.max(0, Math.min(totalW - 1, roadLocalX));

                switch (type) {
                    case SIDEWALK -> {
                        out.add(new Cell(x, z, cellY, Kind.SIDEWALK, Direction.NORTH, null, roadLocalX, t, nx, nz));
                    }
                    case CURB -> {
                        out.add(new Cell(x, z, cellY, Kind.CURB, facingOutward, null, roadLocalX, t, nx, nz));
                    }
                    case LANE -> {
                        out.add(new Cell(x, z, cellY, Kind.ASPHALT, Direction.NORTH, null, roadLocalX, t, nx, nz));
                    }
                    case SHOULDER -> {
                        int stoneDx = isLeftSide ? (elemW - 1) : 0;
                        if (localX == stoneDx) {
                            out.add(new Cell(x, z, cellY, Kind.STONE, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        }
                    }
                    case DIVIDER_GRASS -> {
                        // Detect divider nose cap at transition station (matching Road_perehod_template.litematic)
                        double trans0 = (transitionAtP0 != null) ? Math.min(totalLen, transitionAtP0.getTransitionLength()) : 0.0;
                        double trans3 = (transitionAtP3 != null) ? Math.min(totalLen, transitionAtP3.getTransitionLength()) : 0.0;
                        boolean isNoseAtP0 = transitionAtP0 != null && Math.abs(t - (0.85 * trans0)) <= 0.85;
                        boolean isNoseAtP3 = transitionAtP3 != null && Math.abs((totalLen - t) - (0.85 * trans3)) <= 0.85;
                        if (isNoseAtP0) {
                            Vector3d tan = curve.evaluateTangent(Math.max(0.0, Math.min(1.0, t / Math.max(1.0, totalLen))));
                            if (localX == 0) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx - tan.x, nz - tan.z), null, roadLocalX, t, nx, nz));
                            } else if (localX == elemW - 1) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx - tan.x, -nz - tan.z), null, roadLocalX, t, nx, nz));
                            } else {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-tan.x, -tan.z), null, roadLocalX, t, nx, nz));
                            }
                        } else if (isNoseAtP3) {
                            Vector3d tan = curve.evaluateTangent(Math.max(0.0, Math.min(1.0, t / Math.max(1.0, totalLen))));
                            if (localX == 0) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx + tan.x, nz + tan.z), null, roadLocalX, t, nx, nz));
                            } else if (localX == elemW - 1) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx + tan.x, -nz + tan.z), null, roadLocalX, t, nx, nz));
                            } else {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(tan.x, tan.z), null, roadLocalX, t, nx, nz));
                            }
                        } else if (localX == 0) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                        } else if (localX == elemW - 1) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx, -nz), null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        }
                    }
                    case MARKING_SOLID -> {
                        BlockState solidQuartz = Blocks.SMOOTH_QUARTZ.defaultBlockState();
                        out.add(new Cell(x, z, cellY, Kind.LINE, facingFromVector(nx, nz), solidQuartz, roadLocalX, t, nx, nz));
                    }
                    case MARKING_DOUBLE_SOLID -> {
                        if (localX == 1) {
                            out.add(new Cell(x, z, cellY, Kind.ASPHALT, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.LINE, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                        }
                    }
                    case MARKING_DASHED_LONG -> {
                        boolean isDash = Math.floorMod((long) Math.floor(t), 4) < 3;
                        out.add(new Cell(x, z, cellY, isDash ? Kind.LINE : Kind.ASPHALT, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                    }
                    case MARKING_DASHED_SHORT -> {
                        boolean isDash = Math.floorMod((long) Math.floor(t), 2) == 0;
                        out.add(new Cell(x, z, cellY, isDash ? Kind.LINE : Kind.ASPHALT, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                    }
                }
            }
        }

        return out;
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        return planCurveCells(curve, preset);
    }

    private static long coordKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static double getDistanceToRoadDeck(int bx, int bz, Set<Long> roadDeckColumns) {
        double minD2 = Double.MAX_VALUE;
        for (int r = 1; r <= 15; r++) {
            boolean found = false;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r || Math.abs(dz) == r) {
                        if (roadDeckColumns.contains(coordKey(bx + dx, bz + dz))) {
                            double d2 = dx * dx + dz * dz;
                            if (d2 < minD2) {
                                minD2 = d2;
                            }
                            found = true;
                        }
                    }
                }
            }
            if (found && minD2 <= r * r) {
                break;
            }
            if (found && r >= (int) Math.ceil(Math.sqrt(minD2))) {
                break;
            }
        }
        return minD2 < Double.MAX_VALUE ? Math.sqrt(minD2) : Double.MAX_VALUE;
    }

    private static int rank(Kind k) {
        return switch (k) {
            case GRASS -> 0;
            case STONE -> 1;
            case SIDEWALK -> 2;
            case CURB -> 3;
            case LINE -> 4;
            case ASPHALT -> 5;
        };
    }

    private static Cell resolveWinner(Cell a, Cell b) {
        return rank(a.kind()) >= rank(b.kind()) ? a : b;
    }

    private static void placePillarSection(Level level, Map<BlockPos, BlockState> blockMap, BlockPos surfacePos,
                                           Cell cell, int px, int pillarCycle, BlockState curbBlock, BlockState foundationState) {
        if (cell.localX() < px || cell.localX() > px + 4) {
            return;
        }
        // Capital at below(4): 5x5 with Half.TOP inward-facing stairs around 3x3 checkered core (Road_estacada_opora_new)
        if (cell.localX() == px && pillarCycle >= 1 && pillarCycle <= 3) {
            Direction stairFacing = facingFromVector(cell.nx(), cell.nz());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (cell.localX() == px + 4 && pillarCycle >= 1 && pillarCycle <= 3) {
            Direction stairFacing = facingFromVector(-cell.nx(), -cell.nz());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (pillarCycle == 0 && cell.localX() >= px + 1 && cell.localX() <= px + 3) {
            Direction stairFacing = facingFromVector(cell.nz(), -cell.nx());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (pillarCycle == 4 && cell.localX() >= px + 1 && cell.localX() <= px + 3) {
            Direction stairFacing = facingFromVector(-cell.nz(), cell.nx());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (cell.localX() >= px + 1 && cell.localX() <= px + 3 && pillarCycle >= 1 && pillarCycle <= 3) {
            int relX = cell.localX() - (px + 1);
            int relT = pillarCycle - 1;
            boolean isAndesite = ((relX + relT) % 2 == 1);
            blockMap.put(surfacePos.below(4), isAndesite ? Blocks.POLISHED_ANDESITE.defaultBlockState() : foundationState);
        }

        // 3x3 checkered pillar column extending down to ground
        if (cell.localX() >= px + 1 && cell.localX() <= px + 3 && pillarCycle >= 1 && pillarCycle <= 3) {
            int relX = cell.localX() - (px + 1);
            int relT = pillarCycle - 1;
            boolean isAndesite = ((relX + relT) % 2 == 1);
            BlockState colBlock = isAndesite ? Blocks.POLISHED_ANDESITE.defaultBlockState() : foundationState;
            int trueGroundY = getTrueGroundY(level, cell.x(), cell.z());
            int stopY = Math.max(level.getMinBuildHeight(), trueGroundY);
            for (int py = cell.y() - 5; py >= stopY; py--) {
                blockMap.put(new BlockPos(cell.x(), py, cell.z()), colBlock);
            }
        }
    }

    public static Direction facingFromVector(double ox, double oz) {
        if (Math.abs(ox) > Math.abs(oz)) {
            return ox > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return oz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    public static BlockState orientStairs(BlockState state, Direction facing, Half half) {
        if (state.getBlock() instanceof StairBlock) {
            return state.setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, half)
                    .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        }
        return state;
    }

    /**
     * Resolves vanilla StairBlock corner shapes (outer/inner left/right) across a generated block map
     * so diagonal and curved curbs wrap seamlessly without gaps.
     */
    public static void resolveStairShapes(Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, BlockState> updated = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (!(state.getBlock() instanceof StairBlock)) continue;

            Direction facing = state.getValue(StairBlock.FACING);
            Half half = state.getValue(StairBlock.HALF);
            StairsShape shape = StairsShape.STRAIGHT;

            BlockState front = blocks.get(pos.relative(facing));
            if (front != null && front.getBlock() instanceof StairBlock && front.getValue(StairBlock.HALF) == half) {
                Direction ff = front.getValue(StairBlock.FACING);
                if (ff.getAxis() != facing.getAxis() && isDifferentStair(blocks, pos.relative(ff.getOpposite()), facing, half)) {
                    shape = (ff == facing.getCounterClockWise()) ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
                }
            }
            if (shape == StairsShape.STRAIGHT) {
                BlockState back = blocks.get(pos.relative(facing.getOpposite()));
                if (back != null && back.getBlock() instanceof StairBlock && back.getValue(StairBlock.HALF) == half) {
                    Direction bf = back.getValue(StairBlock.FACING);
                    if (bf.getAxis() != facing.getAxis() && isDifferentStair(blocks, pos.relative(bf), facing, half)) {
                        shape = (bf == facing.getCounterClockWise()) ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
                    }
                }
            }
            if (state.getValue(StairBlock.SHAPE) != shape) {
                updated.put(pos, state.setValue(StairBlock.SHAPE, shape));
            }
        }
        blocks.putAll(updated);
    }

    private static boolean isDifferentStair(Map<BlockPos, BlockState> blocks, BlockPos pos, Direction facing, Half half) {
        BlockState s = blocks.get(pos);
        return s == null || !(s.getBlock() instanceof StairBlock)
                || s.getValue(StairBlock.FACING) != facing
                || s.getValue(StairBlock.HALF) != half;
    }
}
