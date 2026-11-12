package com.roadbuilder.mod.network.handler;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.network.payload.C2S_RequestBuildSegmentPayload;
import com.roadbuilder.mod.network.payload.S2C_SyncFullGraphPayload;
import com.roadbuilder.mod.network.payload.S2C_SyncRoadSegmentPayload;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import com.roadbuilder.mod.server.voxelizer.RevoxelizationManager;
import com.roadbuilder.mod.server.voxelizer.RoadVoxelizer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Handles incoming client network payloads on the dedicated or integrated server.
 * Rule 4: Server authority - all mutations validated on server.
 * Rule 14: Must enqueue work onto the main server thread.
 */
public final class ServerPayloadHandler {

    private ServerPayloadHandler() {}

    public static void handleBuildSegment(final C2S_RequestBuildSegmentPayload payload, final IPayloadContext context) {
        // Rule 14: Always transfer execution to the server main thread
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            // Rule 4: Permission check (creative mode or op level 2)
            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to build roads!"));
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Unauthorized road build attempt by {}", serverPlayer.getName().getString());
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph = savedData.getGraph();

            // Select preset & apply client studio selections onto a working copy (never mutate shared preset slots)
            com.roadbuilder.mod.domain.profile.ModularRoadPreset basePreset = com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets().stream()
                    .filter(p -> p.getId().equals(payload.profileName()))
                    .findFirst()
                    .orElse(com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getActivePreset());
            com.roadbuilder.mod.domain.profile.ModularRoadPreset preset = basePreset.copy();

            if (payload.junctionStyle() != null && !payload.junctionStyle().isBlank()) {
                try {
                    preset.setJunctionStyle(com.roadbuilder.mod.domain.profile.ModularRoadPreset.JunctionPresetStyle.valueOf(payload.junctionStyle().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (payload.curbPalette() != null && !payload.curbPalette().isBlank()) {
                try {
                    preset.setCurbPalette(com.roadbuilder.mod.domain.profile.ModularRoadPreset.CurbPalette.valueOf(payload.curbPalette().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (payload.sidewalkPalette() != null && !payload.sidewalkPalette().isBlank()) {
                try {
                    preset.setSidewalkPalette(com.roadbuilder.mod.domain.profile.ModularRoadPreset.SidewalkPalette.valueOf(payload.sidewalkPalette().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (payload.lampStyle() != null && !payload.lampStyle().isBlank()) {
                preset.setLampStyle(com.roadbuilder.mod.domain.profile.ModularRoadPreset.LampStyle.fromStringOrDefault(
                        payload.lampStyle(), preset.getLampStyle()));
            }
            if (payload.lampSpacing() > 0) {
                preset.setLampSpacing(payload.lampSpacing());
            }
            if (payload.lampStyle() != null && !payload.lampStyle().isBlank()) {
                preset.setSidewalkDoubleLamps(payload.sidewalkDoubleLamps());
            }
            if (payload.lampPlacement() != null && !payload.lampPlacement().isBlank()) {
                preset.setLampPlacement(com.roadbuilder.mod.domain.profile.ModularRoadPreset.LampPlacement.fromStringOrDefault(
                        payload.lampPlacement(), preset.getLampPlacement()));
            }
            if (payload.guardrailStyle() != null && !payload.guardrailStyle().isBlank()) {
                preset.setGuardrailStyle(com.roadbuilder.mod.domain.profile.ModularRoadPreset.GuardrailStyle.fromStringOrDefault(
                        payload.guardrailStyle(), preset.getGuardrailStyle()));
            }
            preset.setNoiseWallEnabled(payload.noiseWallEnabled());
            if (payload.stackGuardrails() != null && !payload.stackGuardrails().isBlank()) {
                preset.applySerializedElementGuardrails(payload.stackGuardrails());
            }
            if (payload.stackGreenery() != null && !payload.stackGreenery().isBlank()) {
                preset.applySerializedElementGreenery(payload.stackGreenery());
            }
            if (payload.treeSpacing() > 0) {
                preset.setTreeSpacing(payload.treeSpacing());
            }
            if (payload.bushSpacing() > 0) {
                preset.setBushSpacing(payload.bushSpacing());
            }
            if (payload.embankmentMode() != null && !payload.embankmentMode().isBlank()) {
                try {
                    preset.setEmbankmentMode(com.roadbuilder.mod.domain.profile.ModularRoadPreset.ToggleMode.valueOf(payload.embankmentMode().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (payload.embankmentShape() != null && !payload.embankmentShape().isBlank()) {
                preset.setEmbankmentShape(com.roadbuilder.mod.domain.profile.ModularRoadPreset.EmbankmentShape.fromStringOrDefault(
                        payload.embankmentShape(), preset.getEmbankmentShape()));
            }
            if (payload.embankmentMaterial() != null && !payload.embankmentMaterial().isBlank()) {
                preset.setEmbankmentMaterial(com.roadbuilder.mod.domain.profile.ModularRoadPreset.EmbankmentMaterial.fromStringOrDefault(
                        payload.embankmentMaterial(), preset.getEmbankmentMaterial()));
            }
            preset.enforceValidEmbankment();
            if (payload.slopeMode() != null && !payload.slopeMode().isBlank()) {
                try {
                    preset.setSlopeMode(com.roadbuilder.mod.domain.profile.ModularRoadPreset.ToggleMode.valueOf(payload.slopeMode().toUpperCase()));
                } catch (IllegalArgumentException ignored) {}
            }
            if (payload.slopeStyle() != null && !payload.slopeStyle().isBlank()) {
                preset.setSlopeStyle(com.roadbuilder.mod.domain.profile.ModularRoadPreset.SlopeStyle.fromStringOrDefault(
                        payload.slopeStyle(), preset.getSlopeStyle()));
            }
            if (payload.slopeMaterial() != null && !payload.slopeMaterial().isBlank()) {
                preset.setSlopeMaterial(com.roadbuilder.mod.domain.profile.ModularRoadPreset.SlopeMaterial.fromStringOrDefault(
                        payload.slopeMaterial(), preset.getSlopeMaterial()));
            }
            preset.enforceValidSlope();
            if (payload.tunnelShape() != null && !payload.tunnelShape().isBlank()) {
                preset.setTunnelShape(com.roadbuilder.mod.domain.profile.ModularRoadPreset.TunnelShape.fromStringOrDefault(
                        payload.tunnelShape(), preset.getTunnelShape()));
            }

            RoadProfile profile = RoadProfile.createDefault().withWidth(preset.getTotalWidth());
            BezierCurve3D curve = new BezierCurve3D(payload.p0(), payload.p1(), payload.p2(), payload.p3());

            // Track graph mutations & previous block snapshots for complete Ctrl+Z undo support
            Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
            Set<UUID> addedNodes = new HashSet<>();
            Set<UUID> addedEdges = new HashSet<>();
            Set<UUID> edgesToRevoxelize = new HashSet<>();
            List<RoadEdge> removedEdges = new ArrayList<>();

            // 1. Resolve start/end nodes with 1.0m node snapping (prevents duplicate nodes in multi-point chains)
            UUID startId = payload.startNodeId().orElseGet(() -> findNearbyNodeId(graph, payload.p0(), 1.0));
            UUID endId = payload.endNodeId().orElseGet(() -> findNearbyNodeId(graph, payload.p3(), 1.0));

            RoadNode startNode = (startId != null) ? graph.getNode(startId) : null;
            boolean startExisted = (startNode != null);
            RoadNode endNode = (endId != null) ? graph.getNode(endId) : null;
            boolean endExisted = (endNode != null);

            // 2. T-Junction auto-split: if an unsnapped endpoint lands directly on an existing road body (same height), split that edge
            if (!startExisted) {
                SplitCandidate sc = findEdgeHitAtEndpoint(graph, curve.getP0(), endNode != null ? endNode.getId() : null, preset);
                if (sc != null) {
                    RoadNode jNode = splitExistingEdgeAt(savedData, sc.edge, sc.t, addedNodes, addedEdges, edgesToRevoxelize, removedEdges, previousBlocks, level);
                    startNode = jNode;
                    startExisted = true;
                    curve = shiftCurveEndpoint(curve, true, jNode.getPosition());
                } else {
                    startNode = new RoadNode(UUID.randomUUID(), curve.getP0(), curve.evaluateTangent(0.0));
                    addedNodes.add(startNode.getId());
                }
            }

            if (!endExisted) {
                SplitCandidate ec = findEdgeHitAtEndpoint(graph, curve.getP3(), startNode.getId(), preset);
                if (ec != null) {
                    RoadNode jNode = splitExistingEdgeAt(savedData, ec.edge, ec.t, addedNodes, addedEdges, edgesToRevoxelize, removedEdges, previousBlocks, level);
                    endNode = jNode;
                    endExisted = true;
                    curve = shiftCurveEndpoint(curve, false, jNode.getPosition());
                } else {
                    endNode = new RoadNode(UUID.randomUUID(), curve.getP3(), curve.evaluateTangent(1.0));
                    addedNodes.add(endNode.getId());
                }
            }

            if (!graph.hasNode(startNode.getId())) graph.addNode(startNode);
            if (!graph.hasNode(endNode.getId())) graph.addNode(endNode);

            // 3. X-Crossing auto-split: detect same-elevation XZ crossings with existing edges and split both at intersection node(s)
            List<CrossingHit> crossings = findAtGradeCrossings(graph, curve, startNode.getId(), endNode.getId());
            List<SubSegment> newSubSegments = new ArrayList<>();
            if (crossings.isEmpty()) {
                newSubSegments.add(new SubSegment(startNode, endNode, curve));
            } else {
                BezierCurve3D remCurve = curve;
                double prevGlobalT = 0.0;
                RoadNode curStartNode = startNode;
                for (CrossingHit hit : crossings) {
                    // Re-fetch or verify target edge still exists (previous crossing could have split an edge if crossed twice)
                    RoadEdge targetEdge = graph.getEdge(hit.edge.getId());
                    double targetT = hit.tOther;
                    if (targetEdge == null) continue;

                    RoadNode crossNode = splitExistingEdgeAt(savedData, targetEdge, targetT, addedNodes, addedEdges, edgesToRevoxelize, removedEdges, previousBlocks, level);
                    double localT = (hit.tNew - prevGlobalT) / Math.max(1e-4, 1.0 - prevGlobalT);
                    localT = Math.max(0.05, Math.min(0.95, localT));
                    BezierCurve3D[] parts = remCurve.splitAt(localT);
                    BezierCurve3D segCurve = shiftCurveEndpoint(parts[0], false, crossNode.getPosition());
                    newSubSegments.add(new SubSegment(curStartNode, crossNode, segCurve));
                    remCurve = shiftCurveEndpoint(parts[1], true, crossNode.getPosition());
                    curStartNode = crossNode;
                    prevGlobalT = hit.tNew;
                }
                newSubSegments.add(new SubSegment(curStartNode, endNode, remCurve));
            }

            // 4. Add all new sub-segments to graph and recalculate ALL touched node types BEFORE voxelization
            Set<UUID> touchedNodes = new HashSet<>();
            touchedNodes.add(startNode.getId());
            touchedNodes.add(endNode.getId());
            List<RoadEdge> newlyCreatedEdges = new ArrayList<>(newSubSegments.size());
            Set<UUID> newlyCreatedEdgeIds = new HashSet<>(newSubSegments.size());
            for (SubSegment seg : newSubSegments) {
                RoadEdge e = savedData.addRoadSegment(seg.nodeA, seg.nodeB, seg.curve, profile, preset.getId());
                e.applyStyleFromPreset(preset);
                newlyCreatedEdges.add(e);
                newlyCreatedEdgeIds.add(e.getId());
                addedEdges.add(e.getId());
                touchedNodes.add(seg.nodeA.getId());
                touchedNodes.add(seg.nodeB.getId());
            }
            for (UUID nId : touchedNodes) {
                RoadNode n = graph.getNode(nId);
                if (n != null) {
                    n.recalculateType(graph);
                }
            }

            // Backfill curb/sidewalk palettes on any pre-existing untagged edges before adjacent revoxelization
            for (RoadNode n : new RoadNode[]{startNode, endNode}) {
                if (n != null) {
                    for (UUID adjId : n.getConnectedEdgeIds()) {
                        if (!addedEdges.contains(adjId)) {
                            RoadEdge adjEdge = graph.getEdge(adjId);
                            if (adjEdge != null) {
                                RoadVoxelizer.inferPalettesIfMissing(level, savedData.getEdgeBlocks(adjId), adjEdge);
                            }
                        }
                    }
                }
            }

            // Collect existing adjacent edges needing revoxelization (junction arms, roundabouts, preset transitions)
            collectAdjacentEdgesToRevoxelize(graph, startNode, startExisted, preset.getId(), addedEdges, edgesToRevoxelize);
            collectAdjacentEdgesToRevoxelize(graph, endNode, endExisted, preset.getId(), addedEdges, edgesToRevoxelize);

            // Clear stale blocks & registry entries for split/adjacent edges BEFORE placing new roads/junctions
            edgesToRevoxelize.removeAll(newlyCreatedEdgeIds);
            RevoxelizationManager.ClearState clearState = null;
            if (!edgesToRevoxelize.isEmpty()) {
                clearState = RevoxelizationManager.clearForRevoxelization(level, edgesToRevoxelize, graph);
                for (Map.Entry<BlockPos, BlockState> pe : clearState.previousBlocks().entrySet()) {
                    if (!previousBlocks.containsKey(pe.getKey())) {
                        previousBlocks.put(pe.getKey(), pe.getValue());
                    }
                }
            }

            // 5. Regenerate module curves and place blocks for each newly created edge
            for (RoadEdge e : newlyCreatedEdges) {
                com.roadbuilder.mod.domain.profile.TransitionProfile t0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, e, preset);
                com.roadbuilder.mod.domain.profile.TransitionProfile t3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, e, preset);
                e.regenerateModuleCurves(preset, t0, t3);

                Map<BlockPos, BlockState> blocksToSet = RoadVoxelizer.calculateRoadBlocks(level, e.getCurve(), preset, graph, e.getId());
                Set<BlockPos> otherActiveRoadBlocks = savedData.getAllOtherEdgeBlocks(e.getId());

                Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
                for (BlockPos pos : blocksToSet.keySet()) {
                    BlockState existingState = level.getBlockState(pos);
                    if (!previousBlocks.containsKey(pos)) {
                        previousBlocks.put(pos, existingState);
                    }
                    BlockState priorOrig = (clearState != null) ? clearState.knownOriginals().get(pos) : null;
                    if (priorOrig == null) {
                        priorOrig = savedData.getOriginalBlockAt(pos);
                    }
                    originalBlocks.put(pos, priorOrig != null ? priorOrig : existingState);
                }

                for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
                    BlockPos pos = entry.getKey();
                    BlockState state = entry.getValue();
                    if (state.isAir() && otherActiveRoadBlocks.contains(pos)) {
                        continue;
                    }
                    level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
                }

                savedData.registerEdgeBlocks(e.getId(), blocksToSet.keySet());
                savedData.registerEdgeOriginalBlocks(e.getId(), originalBlocks);
            }

            // 6. Rebuild split halves & adjacent junction/roundabout arms on the cleared world
            if (clearState != null && !clearState.edgeIds().isEmpty()) {
                RevoxelizationManager.rebuildClearedEdges(level, clearState, graph);
                for (Map.Entry<BlockPos, BlockState> pe : clearState.previousBlocks().entrySet()) {
                    if (!previousBlocks.containsKey(pe.getKey())) {
                        previousBlocks.put(pe.getKey(), pe.getValue());
                    }
                }
            }

            Set<UUID> affectedEdges = new HashSet<>(addedEdges);
            affectedEdges.addAll(edgesToRevoxelize);

            com.roadbuilder.mod.server.undo.RoadUndoManager.recordPlacement(
                    serverPlayer.getUUID(), affectedEdges, addedNodes, addedEdges, removedEdges, previousBlocks
            );

            // 7. Broadcast full graph sync to clients
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(graph));

            serverPlayer.sendSystemMessage(Component.translatable(
                    "roadbuilder.message.road_built_length",
                    String.format(java.util.Locale.US, "%.1f", curve.getApproximateLength())
            ));
        });
    }

    private record SplitCandidate(RoadEdge edge, double t) {}
    private record CrossingHit(RoadEdge edge, double tNew, double tOther, Vector3d pos) {}
    private record SubSegment(RoadNode nodeA, RoadNode nodeB, BezierCurve3D curve) {}

    private static UUID findNearbyNodeId(com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph, Vector3d pos, double maxDist) {
        UUID bestId = null;
        double bestSq = maxDist * maxDist;
        for (RoadNode n : graph.getNodes().values()) {
            double dSq = n.getPosition().distanceToSquared(pos);
            if (dSq <= bestSq) {
                bestSq = dSq;
                bestId = n.getId();
            }
        }
        return bestId;
    }

    private static SplitCandidate findEdgeHitAtEndpoint(
            com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph,
            Vector3d pt,
            UUID excludeNodeId,
            com.roadbuilder.mod.domain.profile.ModularRoadPreset newPreset) {

        RoadEdge bestEdge = null;
        double bestT = 0.0;
        double bestDistSq = Double.MAX_VALUE;

        for (RoadEdge e : graph.getEdges().values()) {
            if (excludeNodeId != null && (e.getNodeAId().equals(excludeNodeId) || e.getNodeBId().equals(excludeNodeId))) {
                continue;
            }
            com.roadbuilder.mod.domain.profile.ModularRoadPreset otherPreset =
                    com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets().stream()
                            .filter(p -> p.getId().equals(e.getPresetId()))
                            .findFirst().orElse(newPreset);
            double maxLat = Math.max(4.5, otherPreset.getTotalWidth() * 0.45);

            BezierCurve3D ec = e.getCurve();
            if (ec.getP0().distanceTo(pt) < 5.0 || ec.getP3().distanceTo(pt) < 5.0) continue;

            BezierCurve3D.CurveProjection2D proj = ec.projectPoint2D(pt.x, pt.z);
            if (proj.t() <= 0.04 || proj.t() >= 0.96) continue;
            if (Math.abs(proj.curvePoint().y - pt.y) > 2.5) continue;

            double latSq = proj.lateralDistance() * proj.lateralDistance();
            if (Math.abs(proj.lateralDistance()) <= maxLat && latSq < bestDistSq) {
                bestDistSq = latSq;
                bestEdge = e;
                bestT = proj.t();
            }
        }
        return bestEdge != null ? new SplitCandidate(bestEdge, bestT) : null;
    }

    private static List<CrossingHit> findAtGradeCrossings(
            com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph,
            BezierCurve3D newCurve,
            UUID startNodeId,
            UUID endNodeId) {

        List<CrossingHit> rawHits = new ArrayList<>();
        double lenNew = Math.max(1.0, newCurve.getApproximateLength());
        int segNew = Math.max(12, Math.min(120, (int) Math.ceil(lenNew / 1.5)));
        Vector3d[] ptsNew = new Vector3d[segNew + 1];
        for (int i = 0; i <= segNew; i++) {
            ptsNew[i] = newCurve.evaluate((double) i / segNew);
        }

        for (RoadEdge e : new ArrayList<>(graph.getEdges().values())) {
            if (e.getNodeAId().equals(startNodeId) || e.getNodeBId().equals(startNodeId)
                    || e.getNodeAId().equals(endNodeId) || e.getNodeBId().equals(endNodeId)) {
                continue;
            }
            BezierCurve3D ec = e.getCurve();
            double lenOther = Math.max(1.0, ec.getApproximateLength());
            int segOther = Math.max(12, Math.min(120, (int) Math.ceil(lenOther / 1.5)));
            Vector3d[] ptsOther = new Vector3d[segOther + 1];
            for (int j = 0; j <= segOther; j++) {
                ptsOther[j] = ec.evaluate((double) j / segOther);
            }

            boolean edgeHitFound = false;
            for (int i = 0; i < segNew && !edgeHitFound; i++) {
                Vector3d a1 = ptsNew[i];
                Vector3d a2 = ptsNew[i + 1];
                for (int j = 0; j < segOther; j++) {
                    Vector3d b1 = ptsOther[j];
                    Vector3d b2 = ptsOther[j + 1];

                    double[] uv = intersectSegments2D(a1.x, a1.z, a2.x, a2.z, b1.x, b1.z, b2.x, b2.z);
                    if (uv == null) continue;

                    double tN = (i + uv[0]) / segNew;
                    double tO = (j + uv[1]) / segOther;
                    Vector3d ptN = newCurve.evaluate(tN);
                    Vector3d ptO = ec.evaluate(tO);

                    // Stay away from existing endpoints (avoid tiny micro-stubs) and strictly enforce at-grade elevation (|dy| <= 2.5)
                    if (ptN.distanceTo(newCurve.getP0()) < 5.0 || ptN.distanceTo(newCurve.getP3()) < 5.0) continue;
                    if (ptO.distanceTo(ec.getP0()) < 5.0 || ptO.distanceTo(ec.getP3()) < 5.0) continue;
                    if (Math.abs(ptN.y - ptO.y) > 2.5) continue;

                    rawHits.add(new CrossingHit(e, tN, tO, ptO));
                    edgeHitFound = true;
                    break;
                }
            }
        }

        rawHits.sort(Comparator.comparingDouble(CrossingHit::tNew));
        List<CrossingHit> deduped = new ArrayList<>();
        for (CrossingHit hit : rawHits) {
            boolean tooClose = false;
            for (CrossingHit prev : deduped) {
                if (hit.pos.distanceTo(prev.pos) < 6.0) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) deduped.add(hit);
        }
        return deduped;
    }

    private static double[] intersectSegments2D(
            double x1, double z1, double x2, double z2,
            double x3, double z3, double x4, double z4) {
        double dx1 = x2 - x1, dz1 = z2 - z1;
        double dx2 = x4 - x3, dz2 = z4 - z3;
        double denom = dx1 * dz2 - dz1 * dx2;
        if (Math.abs(denom) < 1e-8) return null;
        double dx3 = x3 - x1, dz3 = z3 - z1;
        double u = (dx3 * dz2 - dz3 * dx2) / denom;
        double v = (dx3 * dz1 - dz3 * dx1) / denom;
        if (u >= 0.0 && u <= 1.0 && v >= 0.0 && v <= 1.0) {
            return new double[]{u, v};
        }
        return null;
    }

    private static RoadNode splitExistingEdgeAt(
            RoadGraphSavedData savedData,
            RoadEdge existingEdge,
            double tSplit,
            Set<UUID> addedNodes,
            Set<UUID> addedEdges,
            Set<UUID> edgesToRevoxelize,
            List<RoadEdge> removedEdges,
            Map<BlockPos, BlockState> previousBlocks,
            ServerLevel level) {

        com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph = savedData.getGraph();
        RoadNode nodeA = graph.getNode(existingEdge.getNodeAId());
        RoadNode nodeB = graph.getNode(existingEdge.getNodeBId());

        BezierCurve3D[] parts = existingEdge.getCurve().splitAt(tSplit);
        Vector3d splitPt = parts[0].getP3();
        RoadNode splitNode = new RoadNode(UUID.randomUUID(), splitPt, parts[0].evaluateTangent(1.0));
        graph.addNode(splitNode);
        addedNodes.add(splitNode.getId());

        // Snapshot existing world blocks of the un-split edge before removing it
        Set<BlockPos> oldBlocks = savedData.getEdgeBlocks(existingEdge.getId());
        for (BlockPos pos : oldBlocks) {
            if (!previousBlocks.containsKey(pos)) {
                previousBlocks.put(pos, level.getBlockState(pos));
            }
        }
        Map<BlockPos, BlockState> origBlocks = savedData.getEdgeOriginalBlocks(existingEdge.getId());

        // Partition old registered blocks and original blocks cleanly between left & right sub-edges
        Set<BlockPos> leftBlocks = new HashSet<>();
        Set<BlockPos> rightBlocks = new HashSet<>();
        Map<BlockPos, BlockState> leftOrig = new HashMap<>();
        Map<BlockPos, BlockState> rightOrig = new HashMap<>();
        Vector3d chordA = nodeA != null ? nodeA.getPosition() : parts[0].getP0();
        Vector3d chordB = nodeB != null ? nodeB.getPosition() : parts[1].getP3();

        for (BlockPos p : oldBlocks) {
            double dA = Math.hypot(p.getX() + 0.5 - chordA.x, p.getZ() + 0.5 - chordA.z);
            double dB = Math.hypot(p.getX() + 0.5 - chordB.x, p.getZ() + 0.5 - chordB.z);
            if (dA <= dB) {
                leftBlocks.add(p);
                if (origBlocks.containsKey(p)) leftOrig.put(p, origBlocks.get(p));
            } else {
                rightBlocks.add(p);
                if (origBlocks.containsKey(p)) rightOrig.put(p, origBlocks.get(p));
            }
        }

        RoadVoxelizer.inferPalettesIfMissing(level, oldBlocks, existingEdge);

        removedEdges.add(existingEdge);
        graph.removeEdge(existingEdge.getId());
        savedData.clearEdgeBlocks(existingEdge.getId());

        RoadEdge leftEdge = savedData.addRoadSegment(nodeA, splitNode, parts[0], existingEdge.getProfile(), existingEdge.getPresetId());
        RoadEdge rightEdge = savedData.addRoadSegment(splitNode, nodeB, parts[1], existingEdge.getProfile(), existingEdge.getPresetId());
        leftEdge.copyStyleFrom(existingEdge);
        rightEdge.copyStyleFrom(existingEdge);

        com.roadbuilder.mod.domain.profile.ModularRoadPreset existingBasePreset = com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets().stream()
                .filter(p -> p.getId().equals(existingEdge.getPresetId()) || p.getName().equals(existingEdge.getPresetId()))
                .findFirst()
                .orElse(com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getActivePreset());
        com.roadbuilder.mod.domain.profile.ModularRoadPreset existingPreset = RoadVoxelizer.resolveEffectivePreset(existingEdge, existingBasePreset);
        leftEdge.regenerateModuleCurves(
                existingPreset,
                com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, leftEdge, existingPreset),
                com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, leftEdge, existingPreset)
        );
        rightEdge.regenerateModuleCurves(
                existingPreset,
                com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, rightEdge, existingPreset),
                com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, rightEdge, existingPreset)
        );

        savedData.registerEdgeBlocks(leftEdge.getId(), leftBlocks);
        savedData.registerEdgeOriginalBlocks(leftEdge.getId(), leftOrig);
        savedData.registerEdgeBlocks(rightEdge.getId(), rightBlocks);
        savedData.registerEdgeOriginalBlocks(rightEdge.getId(), rightOrig);

        addedEdges.add(leftEdge.getId());
        addedEdges.add(rightEdge.getId());
        edgesToRevoxelize.add(leftEdge.getId());
        edgesToRevoxelize.add(rightEdge.getId());
        return splitNode;
    }

    private static BezierCurve3D shiftCurveEndpoint(BezierCurve3D c, boolean isStart, Vector3d newPt) {
        if (isStart) {
            Vector3d d = newPt.subtract(c.getP0());
            return new BezierCurve3D(newPt, c.getP1().add(d.multiply(0.66)), c.getP2().add(d.multiply(0.33)), c.getP3());
        } else {
            Vector3d d = newPt.subtract(c.getP3());
            return new BezierCurve3D(c.getP0(), c.getP1().add(d.multiply(0.33)), c.getP2().add(d.multiply(0.66)), newPt);
        }
    }

    private static void collectAdjacentEdgesToRevoxelize(
            com.roadbuilder.mod.domain.graph.RoadNetworkGraph graph,
            RoadNode node,
            boolean existed,
            String newPresetId,
            Set<UUID> addedEdges,
            Set<UUID> outRevox) {
        if (!existed || node == null) return;
        if (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION ||
            node.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT) {
            for (UUID eId : node.getConnectedEdgeIds()) {
                if (!addedEdges.contains(eId)) outRevox.add(eId);
            }
        } else if (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.WAYPOINT) {
            for (UUID eId : node.getConnectedEdgeIds()) {
                if (!addedEdges.contains(eId)) {
                    RoadEdge otherE = graph.getEdge(eId);
                    if (otherE != null && otherE.getPresetId() != null && !otherE.getPresetId().equals(newPresetId)) {
                        outRevox.add(eId);
                    }
                }
            }
        }
    }

    public static void handleUndo(com.roadbuilder.mod.network.payload.C2S_RequestUndoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                com.roadbuilder.mod.server.undo.RoadUndoManager.undoLast(serverPlayer);
            }
        });
    }

    public static void handleDeleteEdge(com.roadbuilder.mod.network.payload.C2S_DeleteEdgePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to delete roads!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID edgeId = payload.edgeId();
            RoadEdge edge = savedData.getGraph().getEdge(edgeId);
            if (edge == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Received delete request for unknown edge {}", edgeId);
                return;
            }

            // 1. Restore natural terrain in world (preserving other active roads and using exact original blocks)
            Set<BlockPos> edgeBlocks = savedData.getEdgeBlocks(edgeId);
            Set<BlockPos> protectedPositions = savedData.getAllOtherEdgeBlocks(edgeId);
            Map<BlockPos, BlockState> knownOriginal = savedData.getEdgeOriginalBlocks(edgeId);
            RevoxelizationManager.restoreNaturalTerrain(level, edgeBlocks, protectedPositions, knownOriginal);
            savedData.clearEdgeBlocks(edgeId);

            // 2. Remove edge and update node types
            UUID nodeAId = edge.getNodeAId();
            UUID nodeBId = edge.getNodeBId();
            savedData.getGraph().removeEdge(edgeId);

            RoadNode nodeA = savedData.getGraph().getNode(nodeAId);
            if (nodeA != null) {
                if (savedData.getGraph().getEdgesForNode(nodeAId).isEmpty()) {
                    savedData.getGraph().removeNode(nodeAId);
                } else {
                    nodeA.recalculateType(savedData.getGraph());
                }
            }

            RoadNode nodeB = savedData.getGraph().getNode(nodeBId);
            if (nodeB != null) {
                if (savedData.getGraph().getEdgesForNode(nodeBId).isEmpty()) {
                    savedData.getGraph().removeNode(nodeBId);
                } else {
                    nodeB.recalculateType(savedData.getGraph());
                }
            }

            savedData.setDirty();

            // 3. Sync full graph to all players in dimension
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.edge_deleted"));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Edge {} deleted by {}", edgeId, serverPlayer.getName().getString());
        });
    }

    public static void handleDeleteNode(com.roadbuilder.mod.network.payload.C2S_DeleteNodePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to delete roads!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID nodeId = payload.nodeId();
            RoadNode node = savedData.getGraph().getNode(nodeId);
            if (node == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Received delete request for unknown node {}", nodeId);
                return;
            }

            // 1. Gather all connected edges and neighbor nodes
            java.util.Set<UUID> connectedEdges = new java.util.HashSet<>(node.getConnectedEdgeIds());
            java.util.Set<UUID> neighborNodeIds = new java.util.HashSet<>();
            Set<BlockPos> protectedPositions = savedData.getAllOtherEdgeBlocks(connectedEdges);

            for (UUID edgeId : connectedEdges) {
                RoadEdge edge = savedData.getGraph().getEdge(edgeId);
                if (edge != null) {
                    neighborNodeIds.add(edge.getNodeAId());
                    neighborNodeIds.add(edge.getNodeBId());

                    Set<BlockPos> blocks = savedData.getEdgeBlocks(edgeId);
                    Map<BlockPos, BlockState> knownOriginal = savedData.getEdgeOriginalBlocks(edgeId);
                    RevoxelizationManager.restoreNaturalTerrain(level, blocks, protectedPositions, knownOriginal);
                    savedData.clearEdgeBlocks(edgeId);
                    savedData.getGraph().removeEdge(edgeId);
                }
            }

            // 2. Remove node itself
            savedData.getGraph().removeNode(nodeId);
            neighborNodeIds.remove(nodeId);

            // 3. Recalculate remaining neighbor node types or remove orphans
            for (UUID nId : neighborNodeIds) {
                RoadNode neighbor = savedData.getGraph().getNode(nId);
                if (neighbor != null) {
                    if (savedData.getGraph().getEdgesForNode(nId).isEmpty()) {
                        savedData.getGraph().removeNode(nId);
                    } else {
                        neighbor.recalculateType(savedData.getGraph());
                    }
                }
            }

            savedData.setDirty();

            // 4. Sync full graph to all players in dimension
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.node_deleted"));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Node {} deleted by {}", nodeId, serverPlayer.getName().getString());
        });
    }

    public static void handleMoveNode(com.roadbuilder.mod.network.payload.C2S_MoveNodePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to edit roads!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID nodeId = payload.nodeId();
            RoadNode node = savedData.getGraph().getNode(nodeId);
            if (node == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Move request for non-existent node {}", nodeId);
                return;
            }

            com.roadbuilder.mod.domain.geometry.Vector3d oldPos = node.getPosition();
            com.roadbuilder.mod.domain.geometry.Vector3d newPos = payload.newPosition();
            Set<UUID> affectedEdges = new HashSet<>(node.getConnectedEdgeIds());

            // 1. Move node position
            node.setPosition(newPos);

            // 2. Recompute node tangents and connected edge Bezier curves
            RevoxelizationManager.recalculateNodeTangentAndConnectedCurves(node, savedData.getGraph());

            // 3. Revoxelize all affected edges (clears old blocks, places new blocks, takes undo snapshot)
            Map<BlockPos, BlockState> previousBlocks = RevoxelizationManager.revoxelize(level, affectedEdges, savedData.getGraph());

            // 4. Record into RoadUndoManager
            com.roadbuilder.mod.server.undo.RoadUndoManager.recordNodeMove(serverPlayer.getUUID(), nodeId, oldPos, affectedEdges, previousBlocks);

            // 5. Mark dirty and sync to clients
            savedData.setDirty();
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.node_moved"));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Node {} moved to {} by {}", nodeId, newPos, serverPlayer.getName().getString());
        });
    }

    public static void handleConfigureJunction(com.roadbuilder.mod.network.payload.C2S_ConfigureJunctionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to configure junctions!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID nodeId = payload.nodeId();
            RoadNode node = savedData.getGraph().getNode(nodeId);
            if (node == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Configure request for non-existent node {}", nodeId);
                return;
            }

            if ("ROUNDABOUT".equalsIgnoreCase(payload.junctionStyle())) {
                node.setType(com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT);
                node.setJunctionStyle("ROUNDABOUT");
                if (node.getOuterRadius() <= 0.0 || node.getOuterRadius() == 42.0) node.setOuterRadius(23.0);
                if (node.getInnerRadius() <= 0.0 || node.getInnerRadius() == 24.0) node.setInnerRadius(13.0);
                if (node.getRingLaneCount() <= 0) node.setRingLaneCount(2);
            } else {
                node.setType(com.roadbuilder.mod.domain.graph.NodeType.JUNCTION);
                node.setJunctionStyle(payload.junctionStyle());
                node.setRadius(payload.radius());
            }
            Set<UUID> affectedEdges = new HashSet<>(node.getConnectedEdgeIds());

            // Revoxelize all connected edges to rebuild the junction zone with new radius
            RevoxelizationManager.revoxelize(level, affectedEdges, savedData.getGraph());

            savedData.setDirty();
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.junction_configured"));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Node {} junction reconfigured by {}", nodeId, serverPlayer.getName().getString());
        });
    }

    public static void handleChangeEdgePreset(com.roadbuilder.mod.network.payload.C2S_ChangeEdgePresetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to edit roads!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID edgeId = payload.edgeId();
            RoadEdge edge = savedData.getGraph().getEdge(edgeId);
            if (edge == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Change preset request for non-existent edge {}", edgeId);
                return;
            }

            String newPresetId = payload.newPresetId();
            com.roadbuilder.mod.domain.profile.ModularRoadPreset preset = com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets().stream()
                    .filter(p -> p.getId().equals(newPresetId))
                    .findFirst()
                    .orElse(null);

            if (preset == null) {
                RoadBuilderMod.LOGGER.warn("[RoadBuilder] Unknown road preset: {}", newPresetId);
                return;
            }

            RoadProfile updatedProfile = edge.getProfile().withName(preset.getName()).withWidth(preset.getTotalWidth());
            com.roadbuilder.mod.domain.profile.TransitionProfile t0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(savedData.getGraph(), edge, preset);
            com.roadbuilder.mod.domain.profile.TransitionProfile t3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(savedData.getGraph(), edge, preset);
            List<com.roadbuilder.mod.domain.graph.RoadModuleCurve> moduleCurves = com.roadbuilder.mod.domain.graph.RoadModuleCurve.generateModuleCurves(edge.getCurve(), preset, t0, t3);
            RoadEdge updatedEdge = new RoadEdge(edge.getId(), edge.getNodeAId(), edge.getNodeBId(), edge.getCurve(), updatedProfile, newPresetId,
                    moduleCurves, preset.getCurbPalette().name(), preset.getSidewalkPalette().name());
            updatedEdge.applyStyleFromPreset(preset);
            savedData.getGraph().addEdge(updatedEdge);

            RoadNode nodeA = savedData.getGraph().getNode(edge.getNodeAId());
            if (nodeA != null) nodeA.recalculateType(savedData.getGraph());
            RoadNode nodeB = savedData.getGraph().getNode(edge.getNodeBId());
            if (nodeB != null) nodeB.recalculateType(savedData.getGraph());

            RevoxelizationManager.revoxelize(level, Set.of(edgeId), savedData.getGraph());

            savedData.setDirty();
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.preset_changed", preset.getDisplayName()));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Edge {} preset changed to '{}' by {}", edgeId, newPresetId, serverPlayer.getName().getString());
        });
    }

    public static void handleCreateRoundabout(com.roadbuilder.mod.network.payload.C2S_CreateRoundaboutPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            if (!serverPlayer.isCreative() && !serverPlayer.hasPermissions(2)) {
                serverPlayer.sendSystemMessage(Component.literal("§c[RoadBuilder] You don't have permission to create roundabouts!"));
                return;
            }

            ServerLevel level = serverPlayer.serverLevel();
            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            UUID nodeId = payload.nodeId();
            RoadNode node = savedData.getGraph().getNode(nodeId);
            if (node == null) {
                node = new RoadNode(nodeId, payload.position(), new com.roadbuilder.mod.domain.geometry.Vector3d(0, 0, 1),
                        com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT, payload.ringPresetId(), payload.outerRadius());
                savedData.getGraph().addNode(node);
            }

            node.setType(com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT);
            node.setPosition(payload.position());
            node.setOuterRadius(payload.outerRadius());
            node.setInnerRadius(payload.innerRadius());
            node.setRingLaneCount(payload.laneCount());
            node.setRingPresetId(payload.ringPresetId());
            node.setRadius(payload.outerRadius());

            // Clear old approach blocks & lamps BEFORE placing roundabout platform
            Set<UUID> connectedEdges = new HashSet<>(node.getConnectedEdgeIds());
            RevoxelizationManager.ClearState clearState = null;
            Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
            if (!connectedEdges.isEmpty()) {
                clearState = RevoxelizationManager.clearForRevoxelization(level, connectedEdges, savedData.getGraph());
                previousBlocks.putAll(clearState.previousBlocks());
            }

            // Generate roundabout blocks
            Map<BlockPos, BlockState> blocksToSet = com.roadbuilder.mod.server.voxelizer.RoundaboutGenerator.generateRoundabout(
                    level, node, savedData.getGraph().getEdgesForNode(nodeId), savedData.getGraph()
            );

            // Snapshot previous blocks for undo and capture natural original terrain
            Set<BlockPos> otherActiveRoadBlocks = savedData.getAllOtherEdgeBlocks(nodeId);
            Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
            for (BlockPos pos : blocksToSet.keySet()) {
                BlockState existingState = level.getBlockState(pos);
                if (!previousBlocks.containsKey(pos)) {
                    previousBlocks.put(pos, existingState);
                }

                BlockState priorOrig = (clearState != null) ? clearState.knownOriginals().get(pos) : null;
                if (priorOrig == null) {
                    priorOrig = savedData.getOriginalBlockAt(pos);
                }
                originalBlocks.put(pos, priorOrig != null ? priorOrig : existingState);
            }

            // Place blocks in world
            for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                if (state.isAir() && otherActiveRoadBlocks.contains(pos)) {
                    continue;
                }
                level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
            }

            // Register blocks and original blocks in registry using node UUID
            savedData.registerEdgeBlocks(nodeId, blocksToSet.keySet());
            savedData.registerEdgeOriginalBlocks(nodeId, originalBlocks);

            // Rebuild connecting edges so approaches trim into the roundabout cleanly
            if (clearState != null && !clearState.edgeIds().isEmpty()) {
                RevoxelizationManager.rebuildClearedEdges(level, clearState, savedData.getGraph());
                for (Map.Entry<BlockPos, BlockState> pe : clearState.previousBlocks().entrySet()) {
                    if (!previousBlocks.containsKey(pe.getKey())) {
                        previousBlocks.put(pe.getKey(), pe.getValue());
                    }
                }
            }

            // Record undo
            com.roadbuilder.mod.server.undo.RoadUndoManager.recordPlacement(
                    serverPlayer.getUUID(), nodeId, nodeId, nodeId, previousBlocks
            );

            savedData.setDirty();
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable("roadbuilder.message.roundabout_created"));
            RoadBuilderMod.LOGGER.info("[RoadBuilder] Roundabout {} created at {} by {}", nodeId, payload.position(), serverPlayer.getName().getString());
        });
    }

    public static void syncFullGraphToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);

        S2C_SyncFullGraphPayload fullSync = S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph());
        PacketDistributor.sendToPlayer(player, fullSync);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Synced {} nodes and {} edges to player {}",
                fullSync.nodes().size(), fullSync.edges().size(), player.getName().getString());
    }
}
