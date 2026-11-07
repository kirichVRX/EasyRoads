package com.roadbuilder.mod.network.handler;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
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

            // Construct 3D Bezier curve
            BezierCurve3D curve = new BezierCurve3D(payload.p0(), payload.p1(), payload.p2(), payload.p3());

            // Prepare nodes
            UUID startId = payload.startNodeId().orElseGet(UUID::randomUUID);
            UUID endId = payload.endNodeId().orElseGet(UUID::randomUUID);

            RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
            boolean startExisted = savedData.getGraph().getNode(startId) != null;
            boolean endExisted = savedData.getGraph().getNode(endId) != null;

            RoadNode startNode = savedData.getGraph().getNode(startId);
            if (startNode == null) {
                startNode = new RoadNode(startId, payload.p0(), curve.evaluateTangent(0.0));
            }

            RoadNode endNode = savedData.getGraph().getNode(endId);
            if (endNode == null) {
                endNode = new RoadNode(endId, payload.p3(), curve.evaluateTangent(1.0));
            }

            // Select preset
            com.roadbuilder.mod.domain.profile.ModularRoadPreset preset = com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets().stream()
                    .filter(p -> p.getId().equals(payload.profileName()))
                    .findFirst()
                    .orElse(com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getActivePreset());

            RoadProfile profile = RoadProfile.createDefault().withWidth(preset.getTotalWidth());

            // 1. Save road segment to World Graph NBT
            RoadEdge edge = savedData.addRoadSegment(startNode, endNode, curve, profile, preset.getId());
            UUID edgeId = edge.getId();

            // 2. Recalculate node types and tangents
            startNode.recalculateType(savedData.getGraph());
            endNode.recalculateType(savedData.getGraph());
            if (startExisted) {
                RevoxelizationManager.recalculateNodeTangentAndConnectedCurves(startNode, savedData.getGraph());
            }
            if (endExisted) {
                RevoxelizationManager.recalculateNodeTangentAndConnectedCurves(endNode, savedData.getGraph());
            }

            RoadEdge currentEdge = savedData.getGraph().getEdge(edgeId);
            BezierCurve3D currentCurve = currentEdge != null ? currentEdge.getCurve() : curve;
            if (currentEdge != null) {
                com.roadbuilder.mod.domain.profile.TransitionProfile t0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(savedData.getGraph(), currentEdge, preset);
                com.roadbuilder.mod.domain.profile.TransitionProfile t3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(savedData.getGraph(), currentEdge, preset);
                currentEdge.regenerateModuleCurves(preset, t0, t3);
            }

            // 3. Voxelize and place blocks in world using modular preset and graph context
            Map<BlockPos, BlockState> blocksToSet = RoadVoxelizer.calculateRoadBlocks(level, currentCurve, preset, savedData.getGraph(), edgeId);
            Set<BlockPos> otherActiveRoadBlocks = savedData.getAllOtherEdgeBlocks(edgeId);

            Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
            Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
            for (BlockPos pos : blocksToSet.keySet()) {
                BlockState existingState = level.getBlockState(pos);
                previousBlocks.put(pos, existingState);

                BlockState priorOrig = savedData.getOriginalBlockAt(pos);
                originalBlocks.put(pos, priorOrig != null ? priorOrig : existingState);
            }

            for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockState state = entry.getValue();
                // Never punch air holes into existing other roads!
                if (state.isAir() && otherActiveRoadBlocks.contains(pos)) {
                    continue;
                }
                // Do not overwrite existing other road's markings with regular asphalt
                if (otherActiveRoadBlocks.contains(pos)) {
                    BlockState existing = level.getBlockState(pos);
                    boolean existingIsMarking = existing.is(Blocks.SMOOTH_QUARTZ_STAIRS) || existing.is(Blocks.SMOOTH_QUARTZ);
                    if (existingIsMarking && !state.is(Blocks.SMOOTH_QUARTZ_STAIRS)) {
                        continue;
                    }
                }
                level.setBlock(pos, state, net.minecraft.world.level.block.Block.UPDATE_ALL);
            }

            savedData.registerEdgeBlocks(edgeId, blocksToSet.keySet());
            savedData.registerEdgeOriginalBlocks(edgeId, originalBlocks);

            // 4. If connected to existing junctions/waypoints, revoxelize adjacent edges
            Set<UUID> affectedEdges = new HashSet<>();
            affectedEdges.add(edgeId);
            if (startExisted && (startNode.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION || startNode.getType() == com.roadbuilder.mod.domain.graph.NodeType.WAYPOINT)) {
                for (UUID eId : startNode.getConnectedEdgeIds()) {
                    if (!eId.equals(edgeId)) affectedEdges.add(eId);
                }
            }
            if (endExisted && (endNode.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION || endNode.getType() == com.roadbuilder.mod.domain.graph.NodeType.WAYPOINT)) {
                for (UUID eId : endNode.getConnectedEdgeIds()) {
                    if (!eId.equals(edgeId)) affectedEdges.add(eId);
                }
            }
            if (affectedEdges.size() > 1) {
                Set<UUID> adjacentEdges = new HashSet<>(affectedEdges);
                adjacentEdges.remove(edgeId);
                Map<BlockPos, BlockState> revoxPrev = RevoxelizationManager.revoxelize(level, adjacentEdges, savedData.getGraph());
                for (Map.Entry<BlockPos, BlockState> pe : revoxPrev.entrySet()) {
                    if (!previousBlocks.containsKey(pe.getKey())) {
                        previousBlocks.put(pe.getKey(), pe.getValue());
                    }
                }
            }

            Set<UUID> addedNodes = new HashSet<>();
            if (!startExisted) addedNodes.add(startNode.getId());
            if (!endExisted) addedNodes.add(endNode.getId());

            com.roadbuilder.mod.server.undo.RoadUndoManager.recordPlacement(
                    serverPlayer.getUUID(), affectedEdges, addedNodes, Collections.singleton(edgeId), previousBlocks
            );

            // 5. Broadcast full graph sync to ensure all clients have updated node types and curve geometry
            PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

            serverPlayer.sendSystemMessage(Component.translatable(
                    "roadbuilder.message.road_built_length",
                    String.format(java.util.Locale.US, "%.1f", curve.getApproximateLength())
            ));
        });
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

            node.setRadius(payload.radius());
            node.setJunctionStyle(payload.junctionStyle());
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
            RoadEdge updatedEdge = new RoadEdge(edge.getId(), edge.getNodeAId(), edge.getNodeBId(), edge.getCurve(), updatedProfile, newPresetId, moduleCurves);
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

            // Generate roundabout blocks
            Map<BlockPos, BlockState> blocksToSet = com.roadbuilder.mod.server.voxelizer.RoundaboutGenerator.generateRoundabout(
                    level, node, savedData.getGraph().getEdgesForNode(nodeId), savedData.getGraph()
            );

            // Snapshot previous blocks for undo and capture natural original terrain
            Set<BlockPos> otherActiveRoadBlocks = savedData.getAllOtherEdgeBlocks(nodeId);
            Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
            Map<BlockPos, BlockState> originalBlocks = new HashMap<>();
            for (BlockPos pos : blocksToSet.keySet()) {
                BlockState existingState = level.getBlockState(pos);
                previousBlocks.put(pos, existingState);

                BlockState priorOrig = savedData.getOriginalBlockAt(pos);
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

            // If there are connecting edges, revoxelize them so their approach cuts into the roundabout
            Set<UUID> connectedEdges = new HashSet<>(node.getConnectedEdgeIds());
            if (!connectedEdges.isEmpty()) {
                RevoxelizationManager.revoxelize(level, connectedEdges, savedData.getGraph());
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
