package com.roadbuilder.mod.server.undo;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.network.payload.S2C_SyncFullGraphPayload;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Server-side manager for undoing road construction actions.
 */
public final class RoadUndoManager {

    public record UndoEntry(
            UUID playerId,
            UUID edgeId,
            UUID startNodeId,
            UUID endNodeId,
            Map<BlockPos, BlockState> previousBlocks
    ) {}

    private static final Deque<UndoEntry> UNDO_STACK = new ArrayDeque<>();
    private static final int MAX_UNDO_ENTRIES = 20;

    private RoadUndoManager() {}

    public static synchronized void recordPlacement(UUID playerId, UUID edgeId, UUID startNodeId, UUID endNodeId, Map<BlockPos, BlockState> previousBlocks) {
        if (UNDO_STACK.size() >= MAX_UNDO_ENTRIES) {
            UNDO_STACK.removeFirst();
        }
        UNDO_STACK.addLast(new UndoEntry(playerId, edgeId, startNodeId, endNodeId, new HashMap<>(previousBlocks)));
        RoadBuilderMod.LOGGER.info("[RoadBuilder] Recorded road undo entry for edge {}, snapshot of {} blocks", edgeId, previousBlocks.size());
    }

    public static synchronized boolean undoLast(ServerPlayer player) {
        if (UNDO_STACK.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[RoadBuilder] Нет дорог для отмены!"));
            return false;
        }

        UndoEntry entry = UNDO_STACK.removeLast();
        ServerLevel level = player.serverLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);

        // 1. Restore all replaced blocks
        for (Map.Entry<BlockPos, BlockState> e : entry.previousBlocks().entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
        }

        // 2. Remove edge from graph
        savedData.getGraph().removeEdge(entry.edgeId());

        // 3. Remove orphaned nodes
        RoadNode startNode = savedData.getGraph().getNode(entry.startNodeId());
        if (startNode != null && savedData.getGraph().getEdgesForNode(entry.startNodeId()).isEmpty()) {
            savedData.getGraph().removeNode(entry.startNodeId());
        }
        RoadNode endNode = savedData.getGraph().getNode(entry.endNodeId());
        if (endNode != null && savedData.getGraph().getEdgesForNode(entry.endNodeId()).isEmpty()) {
            savedData.getGraph().removeNode(entry.endNodeId());
        }

        savedData.setDirty();

        // 4. Sync full graph to all players in dimension
        PacketDistributor.sendToPlayersInDimension(level, S2C_SyncFullGraphPayload.fromGraph(savedData.getGraph()));

        player.sendSystemMessage(Component.literal("§a[RoadBuilder] Успешно отменено строительство дороги! Восстановлено " + entry.previousBlocks().size() + " блоков."));
        return true;
    }
}
