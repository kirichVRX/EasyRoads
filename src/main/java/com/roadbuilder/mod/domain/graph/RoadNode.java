package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;

import java.util.*;

/**
 * A graph node representing an endpoint, waypoint, intersection, or roundabout in 3D world space.
 * Rule 12: Node UUID is immutable (final).
 */
public final class RoadNode {
    private final UUID id;
    private Vector3d position;
    private Vector3d tangent;
    private final Set<UUID> connectedEdgeIds;

    // Structural metadata
    private NodeType type;
    private String presetId;
    private double radius;
    private String junctionStyle = null;
    private final Map<UUID, Double> edgeAngles = new HashMap<>();

    // Roundabout specific metadata
    private double outerRadius;
    private double innerRadius;
    private int ringLaneCount;
    private String ringPresetId;

    public RoadNode(UUID id, Vector3d position, Vector3d tangent, NodeType type, String presetId, double radius, String junctionStyle) {
        this.id = Objects.requireNonNull(id, "Node id cannot be null");
        this.position = Objects.requireNonNull(position, "position cannot be null");
        this.tangent = tangent != null ? tangent.normalize() : Vector3d.ZERO;
        this.connectedEdgeIds = new HashSet<>();
        this.type = type != null ? type : NodeType.ENDPOINT;
        this.presetId = presetId != null ? presetId : "default";
        this.radius = radius >= 0.0 ? radius : 0.0;
        boolean isIntersectionType = (this.type == NodeType.JUNCTION || this.type == NodeType.ROUNDABOUT);
        this.junctionStyle = (isIntersectionType && junctionStyle != null && !junctionStyle.isBlank()) ? junctionStyle : null;
        this.outerRadius = 23.0;
        this.innerRadius = 13.0;
        this.ringLaneCount = 2;
        this.ringPresetId = "default";
    }

    public RoadNode(UUID id, Vector3d position, Vector3d tangent, NodeType type, String presetId, double radius) {
        this(id, position, tangent, type, presetId, radius, null);
    }

    public RoadNode(UUID id, Vector3d position, Vector3d tangent) {
        this(id, position, tangent, NodeType.ENDPOINT, "default", 0.0);
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
        edgeAngles.remove(edgeId);
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
    }

    public String getPresetId() {
        return presetId;
    }

    public void setPresetId(String presetId) {
        this.presetId = presetId != null ? presetId : "default";
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = Math.max(0.0, radius);
    }

    public String getJunctionStyle() {
        if (this.type != NodeType.JUNCTION && this.type != NodeType.ROUNDABOUT) {
            return (junctionStyle != null && !junctionStyle.isBlank() && !"ROUNDABOUT".equalsIgnoreCase(junctionStyle))
                    ? junctionStyle
                    : "CITY";
        }
        if (junctionStyle != null && !junctionStyle.isBlank()) {
            return junctionStyle;
        }
        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
        if (active != null && active.getJunctionStyle() != null) {
            return active.getJunctionStyle().name();
        }
        return "CITY";
    }

    public void setJunctionStyle(String junctionStyle) {
        this.junctionStyle = (junctionStyle != null && !junctionStyle.isBlank()) ? junctionStyle : null;
    }

    public Map<UUID, Double> getEdgeAngles() {
        return Collections.unmodifiableMap(edgeAngles);
    }

    public void setEdgeAngle(UUID edgeId, double angle) {
        this.edgeAngles.put(edgeId, angle);
    }

    public double getOuterRadius() {
        return outerRadius;
    }

    public void setOuterRadius(double outerRadius) {
        this.outerRadius = outerRadius;
    }

    public double getInnerRadius() {
        return innerRadius;
    }

    public void setInnerRadius(double innerRadius) {
        this.innerRadius = innerRadius;
    }

    public int getRingLaneCount() {
        return ringLaneCount;
    }

    public void setRingLaneCount(int ringLaneCount) {
        this.ringLaneCount = ringLaneCount;
    }

    public String getRingPresetId() {
        return ringPresetId;
    }

    public void setRingPresetId(String ringPresetId) {
        this.ringPresetId = ringPresetId != null ? ringPresetId : "default";
    }

    /**
     * Recalculates the node type and edge entry angles based on connected edges in the graph.
     */
    public void recalculateType(RoadNetworkGraph graph) {
        if (this.type == NodeType.ROUNDABOUT) {
            recalculateEdgeAngles(graph);
            return;
        }

        List<RoadEdge> edges = graph.getEdgesForNode(this.id);
        edgeAngles.clear();
        recalculateEdgeAngles(graph);

        int count = edges.size();
        if (count <= 1) {
            this.type = NodeType.ENDPOINT;
            this.junctionStyle = null;
            if (count == 1) {
                this.presetId = edges.get(0).getPresetId();
            }
            return;
        }

        if (count == 2) {
            this.type = NodeType.WAYPOINT;
            this.junctionStyle = null;
            this.presetId = edges.get(0).getPresetId();
            return;
        }

        // 3+ edges: Intersection
        boolean wasAlreadyIntersection = (this.type == NodeType.JUNCTION || this.type == NodeType.ROUNDABOUT);
        ModularRoadPreset armPreset = null;
        for (int idx = edges.size() - 1; idx >= 0; idx--) {
            String pId = edges.get(idx).getPresetId();
            ModularRoadPreset p = PresetManager.getInstance().getAllPresets().stream()
                    .filter(pr -> pr.getId().equals(pId) || pr.getName().equals(pId))
                    .findFirst().orElse(null);
            if (p != null) {
                armPreset = p;
                break;
            }
        }
        if (armPreset == null) {
            armPreset = PresetManager.getInstance().getActivePreset();
        }

        // Calculate recommended radius: max(halfWidth) + 2.0 margin
        double maxHalfW = 0.0;
        for (RoadEdge edge : edges) {
            double w = 0.0;
            String pId = edge.getPresetId();
            ModularRoadPreset p = PresetManager.getInstance().getAllPresets().stream()
                    .filter(pr -> pr.getId().equals(pId) || pr.getName().equals(pId))
                    .findFirst().orElse(null);
            if (p != null) {
                w = p.getTotalWidth();
            } else if (edge.getProfile() != null) {
                w = edge.getProfile().getWidth();
            } else {
                w = 10.0;
            }
            maxHalfW = Math.max(maxHalfW, w / 2.0);
        }

        ModularRoadPreset.JunctionPresetStyle desiredStyle = (armPreset != null)
                ? armPreset.getJunctionStyle()
                : ModularRoadPreset.JunctionPresetStyle.CITY;

        // If junctionStyle was explicitly configured on an already-formed intersection, respect it
        if (wasAlreadyIntersection && this.junctionStyle != null) {
            if ("ROUNDABOUT".equalsIgnoreCase(this.junctionStyle)) {
                desiredStyle = ModularRoadPreset.JunctionPresetStyle.ROUNDABOUT;
            } else if ("ROUNDED".equalsIgnoreCase(this.junctionStyle) || "REGULAR".equalsIgnoreCase(this.junctionStyle)) {
                desiredStyle = ModularRoadPreset.JunctionPresetStyle.REGULAR;
            } else if ("CITY".equalsIgnoreCase(this.junctionStyle)) {
                desiredStyle = ModularRoadPreset.JunctionPresetStyle.CITY;
            }
        }

        if (desiredStyle == ModularRoadPreset.JunctionPresetStyle.ROUNDABOUT) {
            this.type = NodeType.ROUNDABOUT;
            this.junctionStyle = "ROUNDABOUT";
            this.outerRadius = 23.0;
            this.innerRadius = 13.0;
            this.ringLaneCount = 2;
        } else if (desiredStyle == ModularRoadPreset.JunctionPresetStyle.REGULAR) {
            this.type = NodeType.JUNCTION;
            this.junctionStyle = "ROUNDED";
            if (this.radius <= 0.0) {
                this.radius = maxHalfW + 2.0;
            }
        } else {
            // Default: CITY with pedestrian crosswalks and stop lines (Road_Perekrestok.litematic)
            this.type = NodeType.JUNCTION;
            this.junctionStyle = "CITY";
            if (this.radius <= 0.0) {
                this.radius = maxHalfW + 2.0;
            }
        }
    }

    private void recalculateEdgeAngles(RoadNetworkGraph graph) {
        List<RoadEdge> edges = graph.getEdgesForNode(this.id);
        for (RoadEdge edge : edges) {
            Vector3d dir = getDirectionAwayFromNode(edge);
            double angle = Math.atan2(dir.z, dir.x);
            edgeAngles.put(edge.getId(), angle);
        }
    }

    private Vector3d getDirectionAwayFromNode(RoadEdge edge) {
        if (edge.getNodeAId().equals(this.id)) {
            Vector3d tan = edge.getCurve().evaluateTangent(0.0);
            return new Vector3d(tan.x, 0, tan.z).normalize();
        } else {
            Vector3d tan = edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
            return new Vector3d(tan.x, 0, tan.z).normalize();
        }
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
                ", type=" + type +
                ", preset=" + presetId +
                ", position=" + position +
                ", connectedEdges=" + connectedEdgeIds.size() +
                '}';
    }
}
