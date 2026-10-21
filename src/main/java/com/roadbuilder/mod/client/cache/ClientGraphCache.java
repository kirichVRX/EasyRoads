package com.roadbuilder.mod.client.cache;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side read-only cache of the road network graph.
 * Used for gizmo rendering, snapping, and preview visualization.
 */
public final class ClientGraphCache {
    private static final ClientGraphCache INSTANCE = new ClientGraphCache();

    private final RoadNetworkGraph graph = new RoadNetworkGraph();

    private UUID hoveredNodeId = null;
    private UUID selectedNodeId = null;

    private final java.util.List<BezierCurve3D> previewCurves = new java.util.ArrayList<>();
    private RoadProfile activeProfile = RoadProfile.createDefault();

    private Map<BlockPos, BlockState> cachedPreviewBlocks = Collections.emptyMap();
    private boolean previewDirty = true;

    private ClientGraphCache() {}

    public static ClientGraphCache getInstance() {
        return INSTANCE;
    }

    public RoadNetworkGraph getGraph() {
        return graph;
    }

    public UUID getHoveredNodeId() {
        return hoveredNodeId;
    }

    public void setHoveredNodeId(UUID hoveredNodeId) {
        this.hoveredNodeId = hoveredNodeId;
    }

    public UUID getSelectedNodeId() {
        return selectedNodeId;
    }

    public void setSelectedNodeId(UUID selectedNodeId) {
        this.selectedNodeId = selectedNodeId;
    }

    public java.util.List<BezierCurve3D> getPreviewCurves() {
        return previewCurves;
    }

    public void setPreviewCurves(java.util.List<BezierCurve3D> curves) {
        this.previewCurves.clear();
        if (curves != null) {
            this.previewCurves.addAll(curves);
        }
        markPreviewDirty();
    }

    public void clearPreview() {
        this.previewCurves.clear();
        this.cachedPreviewBlocks = Collections.emptyMap();
        markPreviewDirty();
    }

    public boolean isPreviewDirty() {
        return previewDirty;
    }

    public void markPreviewDirty() {
        this.previewDirty = true;
    }

    public Map<BlockPos, BlockState> getCachedPreviewBlocks() {
        return cachedPreviewBlocks;
    }

    public void setCachedPreviewBlocks(Map<BlockPos, BlockState> blocks) {
        this.cachedPreviewBlocks = (blocks != null) ? blocks : Collections.emptyMap();
        this.previewDirty = false;
    }

    public RoadProfile getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(RoadProfile activeProfile) {
        this.activeProfile = activeProfile;
        markPreviewDirty();
    }

    public void clear() {
        graph.clear();
        hoveredNodeId = null;
        selectedNodeId = null;
        previewCurves.clear();
        cachedPreviewBlocks = Collections.emptyMap();
        markPreviewDirty();
    }
}
