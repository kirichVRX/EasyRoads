package com.roadbuilder.mod.client.cache;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.profile.RoadProfile;

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
    }

    public void clearPreview() {
        this.previewCurves.clear();
    }

    public RoadProfile getActiveProfile() {
        return activeProfile;
    }

    public void setActiveProfile(RoadProfile activeProfile) {
        this.activeProfile = activeProfile;
    }

    public void clear() {
        graph.clear();
        hoveredNodeId = null;
        selectedNodeId = null;
        previewCurves.clear();
    }
}
