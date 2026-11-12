package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Manages terrain restoration and dynamic revoxelization of road segments.
 */
public final class RevoxelizationManager {

    private RevoxelizationManager() {}

    /**
     * Restores natural terrain across the specified set of block positions that were previously occupied by a road.
     * Surface level is restored with grass/dirt/biome block, sub-surface with dirt/stone, and above surface with air.
     *
     * @param level     server level
     * @param positions set of BlockPos previously registered to the road segment
     */
    public static void restoreNaturalTerrain(ServerLevel level, Set<BlockPos> positions) {
        restoreNaturalTerrain(level, positions, Collections.emptySet(), Collections.emptyMap());
    }

    public static void restoreNaturalTerrain(ServerLevel level, Set<BlockPos> positions, Set<BlockPos> protectedPositions, Map<BlockPos, BlockState> knownOriginals) {
        if (positions == null || positions.isEmpty()) {
            return;
        }

        // 1. Separate positions that should NOT be modified because they belong to other active roads
        Set<BlockPos> toRestore = new HashSet<>();
        for (BlockPos pos : positions) {
            if (protectedPositions == null || !protectedPositions.contains(pos)) {
                toRestore.add(pos);
            }
        }

        if (toRestore.isEmpty()) {
            return;
        }

        // 2. First restore exact known original blocks (100% natural fidelity)
        Set<BlockPos> unhandled = new HashSet<>();
        int restoredExact = 0;
        for (BlockPos pos : toRestore) {
            if (knownOriginals != null && knownOriginals.containsKey(pos)) {
                BlockState orig = knownOriginals.get(pos);
                if (orig != null) {
                    level.setBlock(pos, orig, Block.UPDATE_ALL);
                    restoredExact++;
                    continue;
                }
            }
            unhandled.add(pos);
        }

        if (unhandled.isEmpty()) {
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Restored {} original blocks with exact world fidelity", restoredExact);
            return;
        }

        // 3. Fallback for positions without saved original blockstates: robust column-based reconstruction
        Map<Long, List<BlockPos>> columns = new HashMap<>();
        for (BlockPos pos : unhandled) {
            long key = (((long) pos.getX()) << 32) | (pos.getZ() & 0xFFFFFFFFL);
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(pos);
        }

        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        BlockState stoneState = Blocks.STONE.defaultBlockState();

        int restoredCount = restoredExact;

        for (Map.Entry<Long, List<BlockPos>> entry : columns.entrySet()) {
            List<BlockPos> colPositions = entry.getValue();
            int x = colPositions.get(0).getX();
            int z = colPositions.get(0).getZ();

            int lowestY = Integer.MAX_VALUE;
            int highestY = Integer.MIN_VALUE;
            int topSolidRoadY = Integer.MIN_VALUE;

            for (BlockPos p : colPositions) {
                if (p.getY() < lowestY) lowestY = p.getY();
                if (p.getY() > highestY) highestY = p.getY();
                BlockState cur = level.getBlockState(p);
                if (!cur.isAir()) {
                    if (p.getY() > topSolidRoadY) {
                        topSolidRoadY = p.getY();
                    }
                }
            }

            // Sample ground height by searching outwards radially until reaching outside the road footprint
            int neighborSurfaceSum = 0;
            int neighborCount = 0;
            BlockState sampleSurfaceState = null;

            for (int r = 1; r <= 48; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                        int checkX = x + dx;
                        int checkZ = z + dz;
                        long neighborKey = (((long) checkX) << 32) | (checkZ & 0xFFFFFFFFL);
                        if (!columns.containsKey(neighborKey)) {
                            int ny = RoadVoxelizer.getTrueGroundY(level, checkX, checkZ);
                            BlockPos neighborCheck = new BlockPos(checkX, ny, checkZ);
                            if (protectedPositions == null || !protectedPositions.contains(neighborCheck)) {
                                if (ny > level.getMinBuildHeight()) {
                                    neighborSurfaceSum += ny;
                                    neighborCount++;
                                    if (sampleSurfaceState == null) {
                                        BlockState nState = level.getBlockState(neighborCheck);
                                        if (!nState.isAir() && !RoadVoxelizer.isRoadBlock(level, neighborCheck, nState)) {
                                            sampleSurfaceState = nState;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (neighborCount >= 4) break;
            }

            int trueGroundY = RoadVoxelizer.getTrueGroundY(level, x, z);
            int surfaceY = trueGroundY;
            if (neighborCount > 0) {
                int avgNeighbor = Math.round((float) neighborSurfaceSum / neighborCount);
                if (avgNeighbor < surfaceY) {
                    surfaceY = avgNeighbor;
                }
            }
            // Safety: surfaceY must NEVER be set to an elevated road or floating structure
            surfaceY = Math.min(surfaceY, trueGroundY);

            BlockState surfaceBlock = (sampleSurfaceState != null && sampleSurfaceState.isSolid()) ? sampleSurfaceState : grassState;
            BlockState subBlock = (surfaceBlock.is(Blocks.SAND) || surfaceBlock.is(Blocks.RED_SAND)) ? Blocks.SANDSTONE.defaultBlockState() : dirtState;

            for (BlockPos p : colPositions) {
                int y = p.getY();
                if (y > surfaceY) {
                    level.setBlock(p, airState, Block.UPDATE_ALL);
                } else if (y == surfaceY) {
                    level.setBlock(p, surfaceBlock, Block.UPDATE_ALL);
                } else if (y >= surfaceY - 3) {
                    level.setBlock(p, subBlock, Block.UPDATE_ALL);
                } else {
                    level.setBlock(p, stoneState, Block.UPDATE_ALL);
                }
                restoredCount++;
            }

            // Fill solid foundation directly below restored surface to prevent sunken trenches
            for (int y = surfaceY - 1; y >= surfaceY - 2; y--) {
                BlockPos check = new BlockPos(x, y, z);
                if (level.getBlockState(check).isAir()) {
                    level.setBlock(check, subBlock, Block.UPDATE_ALL);
                }
            }
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Restored natural terrain for {} blocks across {} columns",
                restoredCount, columns.size());
    }

    public record ClearState(
            Set<UUID> edgeIds,
            Map<BlockPos, BlockState> previousBlocks,
            Map<BlockPos, BlockState> knownOriginals
    ) {}

    /**
     * Clears old world blocks and registry entries for edgeIds BEFORE new intersecting roads/roundabouts
     * are generated, preventing stale pillar/lamp blocks from surviving as half-height stubs.
     */
    public static ClearState clearForRevoxelization(ServerLevel level, Set<UUID> edgeIds, RoadNetworkGraph graph) {
        Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
        Map<BlockPos, BlockState> allKnownOriginals = new HashMap<>();
        if (edgeIds == null || edgeIds.isEmpty()) {
            return new ClearState(Collections.emptySet(), previousBlocks, allKnownOriginals);
        }

        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        Set<BlockPos> allOldPositions = new HashSet<>();

        for (UUID edgeId : edgeIds) {
            Set<BlockPos> oldBlocks = savedData.getEdgeBlocks(edgeId);
            for (BlockPos pos : oldBlocks) {
                if (!previousBlocks.containsKey(pos)) {
                    previousBlocks.put(pos, level.getBlockState(pos));
                }
            }
            allOldPositions.addAll(oldBlocks);
            allKnownOriginals.putAll(savedData.getEdgeOriginalBlocks(edgeId));
        }

        Set<BlockPos> protectedPositions = savedData.getAllOtherEdgeBlocks(edgeIds);
        restoreNaturalTerrain(level, allOldPositions, protectedPositions, allKnownOriginals);

        for (UUID edgeId : edgeIds) {
            savedData.clearEdgeBlocks(edgeId);
        }

        savedData.setDirty();
        return new ClearState(new HashSet<>(edgeIds), previousBlocks, allKnownOriginals);
    }

    /**
     * Regenerates and places blocks for edges previously cleared with clearForRevoxelization.
     */
    public static void rebuildClearedEdges(ServerLevel level, ClearState clearState, RoadNetworkGraph graph) {
        if (clearState == null || clearState.edgeIds().isEmpty()) {
            return;
        }

        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        Set<UUID> edgeIds = clearState.edgeIds();
        Map<BlockPos, BlockState> previousBlocks = clearState.previousBlocks();
        Map<BlockPos, BlockState> allKnownOriginals = clearState.knownOriginals();
        Set<BlockPos> protectedPositions = savedData.getAllOtherEdgeBlocks(edgeIds);

        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;

            String presetId = edge.getPresetId();
            ModularRoadPreset basePreset = PresetManager.getInstance().getAllPresets().stream()
                    .filter(p -> p.getId().equals(presetId) || p.getName().equals(presetId))
                    .findFirst()
                    .orElse(PresetManager.getInstance().getActivePreset());
            ModularRoadPreset preset = RoadVoxelizer.resolveEffectivePreset(edge, basePreset);

            com.roadbuilder.mod.domain.profile.TransitionProfile t0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, edge, preset);
            com.roadbuilder.mod.domain.profile.TransitionProfile t3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, edge, preset);
            edge.regenerateModuleCurves(preset, t0, t3);

            Map<BlockPos, BlockState> newBlocks = RoadVoxelizer.calculateRoadBlocks(level, edge.getCurve(), preset, graph, edgeId);

            Map<BlockPos, BlockState> newOriginalBlocks = new HashMap<>();
            for (BlockPos pos : newBlocks.keySet()) {
                BlockState existingState = level.getBlockState(pos);
                if (!previousBlocks.containsKey(pos)) {
                    previousBlocks.put(pos, existingState);
                }
                BlockState priorOrig = allKnownOriginals.get(pos);
                if (priorOrig == null) {
                    priorOrig = savedData.getOriginalBlockAt(pos);
                }
                newOriginalBlocks.put(pos, priorOrig != null ? priorOrig : existingState);
            }

            for (Map.Entry<BlockPos, BlockState> entry : newBlocks.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (state.isAir() && protectedPositions.contains(pos)) {
                    continue;
                }
                level.setBlock(pos, state, Block.UPDATE_ALL);
            }

            savedData.registerEdgeBlocks(edgeId, newBlocks.keySet());
            savedData.registerEdgeOriginalBlocks(edgeId, newOriginalBlocks);
        }

        savedData.setDirty();
    }

    /**
     * Completely revoxelizes a set of edges after their curves or profiles were changed.
     * Takes an undo snapshot, restores old terrain, calculates new road blocks, places them,
     * updates edgeBlockRegistry, and returns the snapshot map for RoadUndoManager.
     */
    public static Map<BlockPos, BlockState> revoxelize(ServerLevel level, Set<UUID> edgeIds, RoadNetworkGraph graph) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return Collections.emptyMap();
        }
        ClearState clearState = clearForRevoxelization(level, edgeIds, graph);
        rebuildClearedEdges(level, clearState, graph);
        return clearState.previousBlocks();
    }

    /**
     * Recalculates tangent vector for a node based on its connected edges,
     * and reconstructs the Bezier curves for all connected edges in the graph.
     */
    public static void recalculateNodeTangentAndConnectedCurves(RoadNode node, RoadNetworkGraph graph) {
        Set<UUID> edgeIds = node.getConnectedEdgeIds();
        if (edgeIds.isEmpty()) {
            return;
        }

        List<Vector3d> outgoingDirs = new ArrayList<>();
        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;
            UUID otherId = edge.getNodeAId().equals(node.getId()) ? edge.getNodeBId() : edge.getNodeAId();
            RoadNode otherNode = graph.getNode(otherId);
            if (otherNode != null) {
                Vector3d dir = otherNode.getPosition().subtract(node.getPosition());
                if (dir.lengthSquared() > 1e-4) {
                    outgoingDirs.add(dir.normalize());
                }
            }
        }

        if (outgoingDirs.size() == 1) {
            node.setTangent(outgoingDirs.get(0));
        } else if (outgoingDirs.size() == 2) {
            Vector3d d1 = outgoingDirs.get(0);
            Vector3d d2 = outgoingDirs.get(1);
            Vector3d tangent = d2.subtract(d1).normalize();
            node.setTangent(tangent.lengthSquared() > 1e-4 ? tangent : d2);
        } else if (outgoingDirs.size() >= 3) {
            Vector3d avg = Vector3d.ZERO;
            for (Vector3d d : outgoingDirs) {
                avg = avg.add(d);
            }
            if (avg.lengthSquared() > 1e-4) {
                node.setTangent(avg.normalize());
            }
        }

        // Reconstruct curves for all connected edges
        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge == null) continue;

            RoadNode nodeA = graph.getNode(edge.getNodeAId());
            RoadNode nodeB = graph.getNode(edge.getNodeBId());
            if (nodeA == null || nodeB == null) continue;

            Vector3d p0 = nodeA.getPosition();
            Vector3d p3 = nodeB.getPosition();
            Vector3d chord = p3.subtract(p0);
            double dist = chord.length();
            if (dist < 1e-4) continue;

            Vector3d tA = nodeA.getTangent();
            if (tA == null || tA.lengthSquared() < 1e-4) {
                tA = chord.normalize();
            } else {
                if (tA.dot(chord) < 0) tA = tA.multiply(-1.0);
                tA = tA.normalize();
            }

            Vector3d tB = nodeB.getTangent();
            if (tB == null || tB.lengthSquared() < 1e-4) {
                tB = chord.normalize();
            } else {
                if (tB.dot(chord) < 0) tB = tB.multiply(-1.0);
                tB = tB.normalize();
            }

            Vector3d c1 = p0.add(tA.multiply(dist / 3.0));
            Vector3d c2 = p3.subtract(tB.multiply(dist / 3.0));
            c1 = new Vector3d(c1.x, p0.y + (p3.y - p0.y) / 3.0, c1.z);
            c2 = new Vector3d(c2.x, p0.y + 2.0 * (p3.y - p0.y) / 3.0, c2.z);

            BezierCurve3D newCurve = new BezierCurve3D(p0, c1, c2, p3);
            ModularRoadPreset edgePreset = PresetManager.getInstance().getAllPresets().stream()
                    .filter(p -> p.getId().equals(edge.getPresetId()) || p.getName().equals(edge.getPresetId()))
                    .findFirst()
                    .orElse(PresetManager.getInstance().getActivePreset());
            com.roadbuilder.mod.domain.profile.TransitionProfile t0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, edge, edgePreset);
            com.roadbuilder.mod.domain.profile.TransitionProfile t3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, edge, edgePreset);
            List<com.roadbuilder.mod.domain.graph.RoadModuleCurve> moduleCurves = com.roadbuilder.mod.domain.graph.RoadModuleCurve.generateModuleCurves(newCurve, edgePreset, t0, t3);
            RoadEdge updatedEdge = new RoadEdge(edge.getId(), edge.getNodeAId(), edge.getNodeBId(), newCurve, edge.getProfile(), edge.getPresetId(), moduleCurves);
            updatedEdge.copyStyleFrom(edge);
            graph.addEdge(updatedEdge);
        }

        node.recalculateType(graph);
        for (UUID edgeId : edgeIds) {
            RoadEdge edge = graph.getEdge(edgeId);
            if (edge != null) {
                RoadNode other = graph.getNode(edge.getNodeAId().equals(node.getId()) ? edge.getNodeBId() : edge.getNodeAId());
                if (other != null) {
                    other.recalculateType(graph);
                }
            }
        }
    }
}
