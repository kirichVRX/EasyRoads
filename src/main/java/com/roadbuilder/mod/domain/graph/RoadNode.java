package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.Vector3d;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A graph node representing an intersection or anchor point in 3D world space.
 * Rule 12: Node UUID is immutable (final).
 */
public final class RoadNode {
    private final UUID id;
    private Vector3d position;
    private Vector3d tangent;
    private final Set<UUID> connectedEdgeIds;

    public RoadNode(UUID id, Vector3d position, Vector3d tangent) {
        this.id = Objects.requireNonNull(id, "Node id cannot be null");
        this.position = Objects.requireNonNull(position, "position cannot be null");
        this.tangent = tangent != null ? tangent.normalize() : Vector3d.ZERO;
        this.connectedEdgeIds = new HashSet<>();
    }

    public RoadNode(Vector3d position) {
        this(UUID.randomUUID(), position, Vector3d.ZERO);
    }

    public UUID getId() {
        return id;
    }

    public Vector3d getPosition() {
        return position;
    }

    public void setPosition(Vector3d position) {
        this.position = Objects.requireNonNull(position, "position cannot be null");
    }

    public Vector3d getTangent() {
        return tangent;
    }

    public void setTangent(Vector3d tangent) {
        this.tangent = tangent != null ? tangent.normalize() : Vector3d.ZERO;
    }

    public Set<UUID> getConnectedEdgeIds() {
        return Collections.unmodifiableSet(connectedEdgeIds);
    }

    public void addEdge(UUID edgeId) {
        connectedEdgeIds.add(edgeId);
    }

    public void removeEdge(UUID edgeId) {
        connectedEdgeIds.remove(edgeId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadNode roadNode = (RoadNode) o;
        return id.equals(roadNode.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RoadNode{" +
                "id=" + id +
                ", position=" + position +
                ", connectedEdges=" + connectedEdgeIds.size() +
                '}';
    }
}
