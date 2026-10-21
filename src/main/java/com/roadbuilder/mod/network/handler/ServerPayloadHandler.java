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
import com.roadbuilder.mod.server.voxelizer.RoadVoxelizer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            savedData.addRoadSegment(startNode, endNode, curve, profile);

            // Get the newly added edge ID
            UUID edgeId = UUID.randomUUID();
            for (RoadEdge edge : savedData.getGraph().getEdges().values()) {
                if (edge.connectsNode(startId) && edge.connectsNode(endId)) {
                    edgeId = edge.getId();
                    break;
                }
            }

            // 2. Voxelize and place blocks in world using modular preset, snapshotting for undo
            Map<BlockPos, BlockState> blocksToSet = RoadVoxelizer.calculateRoadBlocks(level, curve, preset);
            Map<BlockPos, BlockState> previousBlocks = new HashMap<>();
            for (BlockPos pos : blocksToSet.keySet()) {
                previousBlocks.put(pos, level.getBlockState(pos));
            }
            for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
                level.setBlock(entry.getKey(), entry.getValue(), net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
            com.roadbuilder.mod.server.undo.RoadUndoManager.recordPlacement(
                    serverPlayer.getUUID(), edgeId, startNode.getId(), endNode.getId(), previousBlocks
            );

            // 3. Phase 5: Broadcast newly created segment to all players in dimension
            S2C_SyncRoadSegmentPayload syncPayload = new S2C_SyncRoadSegmentPayload(
                    startNode.getId(), startNode.getPosition(), startNode.getTangent(),
                    endNode.getId(), endNode.getPosition(), endNode.getTangent(),
                    edgeId,
                    curve.getP0(), curve.getP1(), curve.getP2(), curve.getP3(),
                    preset.getName(), preset.getTotalWidth()
            );
            PacketDistributor.sendToPlayersInDimension(level, syncPayload);

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

    public static void syncFullGraphToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);

        List<S2C_SyncFullGraphPayload.RoadNodeData> nodeDataList = new ArrayList<>();
        for (RoadNode node : savedData.getGraph().getNodes().values()) {
            nodeDataList.add(new S2C_SyncFullGraphPayload.RoadNodeData(
                    node.getId(), node.getPosition(), node.getTangent()
            ));
        }

        List<S2C_SyncFullGraphPayload.RoadEdgeData> edgeDataList = new ArrayList<>();
        for (RoadEdge edge : savedData.getGraph().getEdges().values()) {
            edgeDataList.add(new S2C_SyncFullGraphPayload.RoadEdgeData(
                    edge.getId(),
                    edge.getNodeAId(),
                    edge.getNodeBId(),
                    edge.getCurve().getP0(),
                    edge.getCurve().getP1(),
                    edge.getCurve().getP2(),
                    edge.getCurve().getP3(),
                    edge.getProfile().getName(),
                    edge.getProfile().getWidth()
            ));
        }

        S2C_SyncFullGraphPayload fullSync = new S2C_SyncFullGraphPayload(nodeDataList, edgeDataList);
        PacketDistributor.sendToPlayer(player, fullSync);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Synced {} nodes and {} edges to player {}",
                nodeDataList.size(), edgeDataList.size(), player.getName().getString());
    }
}
