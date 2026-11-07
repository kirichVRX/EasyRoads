package com.roadbuilder.mod.server.undo;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.network.payload.S2C_SyncFullGraphPayload;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import com.roadbuilder.mod.server.voxelizer.RevoxelizationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Server-side manager for undoing road construction and modification actions.
 */
public final class RoadUndoManager {

    public record UndoEntry(
            UUID playerId,
            Set<UUID> affectedEdgeIds,
            Set<UUID> addedNodeIds,
            Set<UUID> addedEdgeIds,
            Map<UUID, Vector3d> movedNodes,
            Map<BlockPos, BlockState> previousBlocks
    ) {}

    private static final Deque<UndoEntry> UNDO_STACK = new ArrayDeque<>();
    private static final int MAX_UNDO_ENTRIES = 20;

    private RoadUndoManager() {}

    public static synchronized void recordPlacement(UUID playerId, UUID edgeId, UUID startNodeId, UUID endNodeId, Map<BlockPos, BlockState> previousBlocks) {
        recordPlacement(playerId, Collections.singleton(edgeId), new HashSet<>(Arrays.asList(startNodeId, endNodeId)), Collections.singleton(edgeId), previousBlocks);
    }

    public static synchronized void recordPlacement(UUID playerId, Set<UUID> affectedEdges, Set<UUID> addedNodes, Set<UUID> addedEdges, Map<BlockPos, BlockState> previousBlocks) {
        if (UNDO_STACK.size() >= MAX_UNDO_ENTRIES) {
            UNDO_STACK.removeFirst();
        }
        UNDO_STACK.addLast(new UndoEntry(playerId, new HashSet<>(affectedEdges), new HashSet<>(addedNodes), new HashSet<>(addedEdges), Collections.emptyMap(), new HashMap<>(previousBlocks)));
        RoadBuilderMod.LOGGER.info("[RoadBuilder] Recorded road undo entry for edges {}, snapshot of {} blocks", affectedEdges, previousBlocks.size());
    }

    public static synchronized void recordNodeMove(UUID playerId, UUID nodeId, Vector3d oldPos, Set<UUID> affectedEdges, Map<BlockPos, BlockState> previousBlocks) {
        if (UNDO_STACK.size() >= MAX_UNDO_ENTRIES) {
            UNDO_STACK.removeFirst();
        }
        Map<UUID, Vector3d> moved = Collections.singletonMap(nodeId, oldPos);
        UNDO_STACK.addLast(new UndoEntry(playerId, new HashSet<>(affectedEdges), Collections.emptySet(), Collections.emptySet(), moved, new HashMap<>(previousBlocks)));
        RoadBuilderMod.LOGGER.info("[RoadBuilder] Recorded node move undo entry for node {}, snapshot of {} blocks", nodeId, previousBlocks.size());
    }

    public static synchronized boolean undoLast(ServerPlayer player) {
        if (UNDO_STACK.isEmpty()) {
            player.sendSystemMessage(Component.translatable("roadbuilder.message.undo_empty"));
            return false;
        }

        UndoEntry entry = UNDO_STACK.removeLast();
        ServerLevel level = player.serverLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);

        // 1. Restore all replaced blocks in world
        for (Map.Entry<BlockPos, BlockState> e : entry.previousBlocks().entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
        }

        // 2. Undo added edges
        for (UUID edgeId : entry.addedEdgeIds()) {
            savedData.getGraph().removeEdge(edgeId);
            savedData.clearEdgeBlocks(edgeId);
        }

        // 3. Undo added nodes (remove if orphaned)
        for (UUID nodeId : entry.addedNodeIds()) {
            RoadNode node = savedData.getGraph().getNode(nodeId);
            if (node != null) {
                if (savedData.getGraph().getEdgesForNode(nodeId).isEmpty()) {
                    savedData.getGraph().removeNode(nodeId);
                } else {
                    node.recalculateType(savedData.getGraph());
                }
            }
        }

        // 4. Undo moved nodes: restore old position and recompute curves
        for (Map.Entry<UUID, Vector3d> moved : entry.movedNodes().entrySet()) {
            RoadNode node = savedData.getGraph().getNode(moved.getKey());
            if (node != null) {
                node.setPosition(moved.getValue());
                RevoxelizationManager.recalculateNodeTangentAndConnectedCurves(node, savedData.getGraph());
            }
        }

        // 5. Revoxelize any remaining affected edges whose junctions/curves were affected
        Set<UUID> remainingEdges = new HashSet<>(entry.affectedEdgeIds());
        remainingEdges.removeAll(entry.addedEdgeIds());
        if (!remainingEdges.isEmpty()) {
            for (UUID eId : remainingEdges) {
                RoadEdge e = savedData.getGraph().getEdge(eId);
                if (e != null) {
                    RoadNode nA = savedData.getGraph().getNode(e.getNodeAId());
                    if (nA != null) nA.recalculateType(savedData.getGraph());
                    RoadNode nB = savedData.getGraph().getNode(e.getNodeBId());
                    if (nB != null) nB.recalculateType(savedData.getGraph());
                }
            }
            RevoxelizationManager.revoxelize(level, remainingEdges, savedData.getGraph());
        }

        savedData.setDirty();

        // 5. Sync full graph to all players in dimension
        PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

        player.sendSystemMessage(Component.translatable(
                "roadbuilder.message.undo_success",
                entry.previousBlocks().size()
        ));
        return true;
    }
}
