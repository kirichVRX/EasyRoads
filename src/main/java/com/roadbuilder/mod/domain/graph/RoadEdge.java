package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.profile.RoadProfile;

import java.util.Objects;
import java.util.UUID;

/**
 * An edge connecting two nodes in the road graph, shaped by a Bezier curve.
 * Rule 12: Edge UUID is immutable (final).
 */
public final class RoadEdge {
    private final UUID id;
    private final UUID nodeAId;
    private final UUID nodeBId;
    private final BezierCurve3D curve;
    private final RoadProfile profile;

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this.id = Objects.requireNonNull(id, "Edge id cannot be null");
        this.nodeAId = Objects.requireNonNull(nodeAId, "nodeAId cannot be null");
        this.nodeBId = Objects.requireNonNull(nodeBId, "nodeBId cannot be null");
        this.curve = Objects.requireNonNull(curve, "curve cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile);
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
                ", nodeA=" + nodeAId +
                ", nodeB=" + nodeBId +
                ", length=" + String.format("%.2f", curve.getApproximateLength()) +
                '}';
    }
}
