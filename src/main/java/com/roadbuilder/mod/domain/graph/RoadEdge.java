package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.domain.profile.TransitionProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An edge connecting two nodes in the road graph, shaped by a center Bezier curve
 * and an individual set of 3D module curves for each element in the road stack (lanes, curbs, sidewalks, markings).
 * Rule 12: Edge UUID is immutable (final).
 */
public final class RoadEdge {
    private final UUID id;
    private final UUID nodeAId;
    private final UUID nodeBId;
    private final BezierCurve3D curve;
    private final RoadProfile profile;
    private String presetId;
    private List<RoadModuleCurve> moduleCurves = new ArrayList<>();

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves) {
        this.id = Objects.requireNonNull(id, "Edge id cannot be null");
        this.nodeAId = Objects.requireNonNull(nodeAId, "nodeAId cannot be null");
        this.nodeBId = Objects.requireNonNull(nodeBId, "nodeBId cannot be null");
        this.curve = Objects.requireNonNull(curve, "curve cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
        this.presetId = (presetId != null && !presetId.isEmpty()) ? presetId : (profile.getName() != null ? profile.getName() : "default");
        this.moduleCurves = (moduleCurves != null) ? new ArrayList<>(moduleCurves) : new ArrayList<>();
    }

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId) {
        this(id, nodeAId, nodeBId, curve, profile, presetId, null);
    }

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this(id, nodeAId, nodeBId, curve, profile, profile != null ? profile.getName() : "default");
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, presetId, moduleCurves);
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, presetId);
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, profile != null ? profile.getName() : "default");
    }

    public UUID getId() {
        return id;
    }

    public UUID getNodeAId() {
        return nodeAId;
    }

    public UUID getNodeBId() {
        return nodeBId;
    }

    public BezierCurve3D getCurve() {
        return curve;
    }

    public RoadProfile getProfile() {
        return profile;
    }

    public String getPresetId() {
        return presetId;
    }

    public void setPresetId(String presetId) {
        this.presetId = presetId != null ? presetId : "default";
    }

    public List<RoadModuleCurve> getModuleCurves() {
        return moduleCurves != null ? Collections.unmodifiableList(moduleCurves) : Collections.emptyList();
    }

    public void setModuleCurves(List<RoadModuleCurve> moduleCurves) {
        this.moduleCurves = moduleCurves != null ? new ArrayList<>(moduleCurves) : new ArrayList<>();
    }

    public void regenerateModuleCurves(ModularRoadPreset preset, TransitionProfile transitionAtP0, TransitionProfile transitionAtP3) {
        this.moduleCurves = RoadModuleCurve.generateModuleCurves(this.curve, preset, transitionAtP0, transitionAtP3);
    }

    public boolean connectsNode(UUID nodeId) {
        return nodeAId.equals(nodeId) || nodeBId.equals(nodeId);
    }

    public UUID getOtherNodeId(UUID nodeId) {
        if (nodeAId.equals(nodeId)) {
            return nodeBId;
        } else if (nodeBId.equals(nodeId)) {
            return nodeAId;
        }
        throw new IllegalArgumentException("Node " + nodeId + " is not an endpoint of this edge " + id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadEdge roadEdge = (RoadEdge) o;
        return id.equals(roadEdge.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RoadEdge{" +
                "id=" + id +
                ", preset=" + presetId +
                ", nodeA=" + nodeAId +
                ", nodeB=" + nodeBId +
                ", length=" + String.format("%.2f", curve.getApproximateLength()) +
                '}';
    }
}
