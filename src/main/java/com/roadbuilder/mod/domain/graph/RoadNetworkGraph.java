package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.Vector3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe domain representation of the entire road network graph.
 */
public final class RoadNetworkGraph {
    private final Map<UUID, RoadNode> nodes = new ConcurrentHashMap<>();
    private final Map<UUID, RoadEdge> edges = new ConcurrentHashMap<>();

    public void addNode(RoadNode node) {
        Objects.requireNonNull(node, "node cannot be null");
        nodes.put(node.getId(), node);
    }

    public RoadNode getNode(UUID id) {
        return nodes.get(id);
    }

    public boolean hasNode(UUID id) {
        return nodes.containsKey(id);
    }

    public RoadNode removeNode(UUID id) {
        RoadNode removed = nodes.remove(id);
        if (removed != null) {
            // Remove all edges connected to this node
            for (UUID edgeId : new HashSet<>(removed.getConnectedEdgeIds())) {
                removeEdge(edgeId);
            }
        }
        return removed;
    }

    public void addEdge(RoadEdge edge) {
        Objects.requireNonNull(edge, "edge cannot be null");
        edges.put(edge.getId(), edge);

        RoadNode nodeA = nodes.get(edge.getNodeAId());
        if (nodeA != null) {
            nodeA.addEdge(edge.getId());
        }

        RoadNode nodeB = nodes.get(edge.getNodeBId());
        if (nodeB != null) {
            nodeB.addEdge(edge.getId());
        }
    }

    public RoadEdge getEdge(UUID id) {
        return edges.get(id);
    }

    public boolean hasEdge(UUID id) {
        return edges.containsKey(id);
    }

    public RoadEdge removeEdge(UUID id) {
        RoadEdge edge = edges.remove(id);
        if (edge != null) {
            RoadNode nodeA = nodes.get(edge.getNodeAId());
            if (nodeA != null) {
                nodeA.removeEdge(id);
            }
            RoadNode nodeB = nodes.get(edge.getNodeBId());
            if (nodeB != null) {
                nodeB.removeEdge(id);
            }
        }
        return edge;
    }

    public Map<UUID, RoadNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public Map<UUID, RoadEdge> getEdges() {
        return Collections.unmodifiableMap(edges);
    }

    public List<RoadEdge> getEdgesForNode(UUID nodeId) {
        RoadNode node = nodes.get(nodeId);
        if (node == null) {
            return Collections.emptyList();
        }
        List<RoadEdge> result = new ArrayList<>();
        for (UUID edgeId : node.getConnectedEdgeIds()) {
            RoadEdge edge = edges.get(edgeId);
            if (edge != null) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * Finds the closest node to the specified position within maxDistance, or null if none found.
     * Useful for node snapping in editor mode.
     */
    public RoadNode findNearbyNode(Vector3d position, double maxDistance) {
        RoadNode closestNode = null;
        double closestDistSq = maxDistance * maxDistance;

        for (RoadNode node : nodes.values()) {
            double distSq = node.getPosition().distanceToSquared(position);
            if (distSq <= closestDistSq) {
                closestDistSq = distSq;
                closestNode = node;
            }
        }
        return closestNode;
    }

    public void clear() {
        nodes.clear();
        edges.clear();
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public int getEdgeCount() {
        return edges.size();
    }
}
