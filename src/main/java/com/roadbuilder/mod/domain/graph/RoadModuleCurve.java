package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import com.roadbuilder.mod.domain.profile.TransitionProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an individual road module (lane, curb, sidewalk, marking, etc.)
 * within a road edge, containing its specific 3D Bezier curve, width, offsets, and element type.
 */
public final class RoadModuleCurve {
    private final UUID id;
    private final RoadElementType type;
    private final double width;
    private final double startOffset;
    private final double endOffset;
    private final int stackIndex;
    private final BezierCurve3D curve;

    public RoadModuleCurve(UUID id, RoadElementType type, double width, double startOffset, double endOffset, int stackIndex, BezierCurve3D curve) {
        this.id = id != null ? id : UUID.randomUUID();
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.width = Math.max(0.1, width);
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.stackIndex = stackIndex;
        this.curve = Objects.requireNonNull(curve, "curve cannot be null");
    }

    public UUID getId() {
        return id;
    }

    public RoadElementType getType() {
        return type;
    }

    public double getWidth() {
        return width;
    }

    public double getStartOffset() {
        return startOffset;
    }

    public double getEndOffset() {
        return endOffset;
    }

    public int getStackIndex() {
        return stackIndex;
    }

    public BezierCurve3D getCurve() {
        return curve;
    }

    public Vector3d getStartPoint() {
        return curve.getP0();
    }

    public Vector3d getEndPoint() {
        return curve.getP3();
    }

    public boolean isRoadway() {
        return type == RoadElementType.LANE || isMarking() || type == RoadElementType.DIVIDER_GRASS;
    }

    public boolean isMarking() {
        return type == RoadElementType.MARKING_SOLID ||
               type == RoadElementType.MARKING_DOUBLE_SOLID ||
               type == RoadElementType.MARKING_DASHED_LONG ||
               type == RoadElementType.MARKING_DASHED_SHORT;
    }

    public boolean isCurb() {
        return type == RoadElementType.CURB;
    }

    public boolean isSidewalk() {
        return type == RoadElementType.SIDEWALK;
    }

    public boolean isShoulder() {
        return type == RoadElementType.SHOULDER;
    }

    /**
     * Generates all individual module curves for a road edge given its center curve and preset,
     * taking into account optional transition profiles at P0 and P3.
     */
    public static List<RoadModuleCurve> generateModuleCurves(
            BezierCurve3D centerCurve,
            ModularRoadPreset preset,
            TransitionProfile transitionAtP0,
            TransitionProfile transitionAtP3) {

        List<RoadModuleCurve> moduleCurves = new ArrayList<>();
        if (centerCurve == null || preset == null) return moduleCurves;

        List<RoadStackElement> stack = preset.getStack();
        if (stack.isEmpty()) return moduleCurves;

        double totalW = preset.getTotalWidth();
        int numElems = stack.size();

        double[] elemStart = new double[numElems];
        double[] elemEnd = new double[numElems];
        double[] elemCenter = new double[numElems];
        double cur = -totalW * 0.5;
        for (int i = 0; i < numElems; i++) {
            elemStart[i] = cur;
            cur += stack.get(i).getWidth();
            elemEnd[i] = cur;
            elemCenter[i] = (elemStart[i] + elemEnd[i]) * 0.5;
        }

        Vector3d norm0 = centerCurve.evaluateNormal(0.0, Vector3d.UP);
        Vector3d norm3 = centerCurve.evaluateNormal(1.0, Vector3d.UP);
        Vector3d tan0 = centerCurve.evaluateTangent(0.0);
        Vector3d tan3 = centerCurve.evaluateTangent(1.0);

        Vector3d p0 = centerCurve.getP0();
        Vector3d p1 = centerCurve.getP1();
        Vector3d p2 = centerCurve.getP2();
        Vector3d p3 = centerCurve.getP3();
        double centerChordLen = p0.distanceTo(p3);

        for (int i = 0; i < numElems; i++) {
            RoadStackElement elem = stack.get(i);
            double s0 = elemCenter[i];
            double s1 = elemCenter[i];
            double elemW = elem.getWidth();

            // If transitioning at P0, get interpolated offset at factor 0.0 proportionally matching elements
            if (transitionAtP0 != null) {
                TransitionProfile.InterpolatedStack startStack = transitionAtP0.getStackAt(0.0);
                double relPos = elemCenter[i] / Math.max(1.0, totalW * 0.5);
                double targetOffset = relPos * (startStack.totalWidth() * 0.5);
                TransitionProfile.InterpolatedElement ie = startStack.getElementAt(targetOffset);
                if (ie != null) {
                    s0 = (ie.startOffset() + ie.endOffset()) * 0.5;
                    elemW = ie.width();
                }
            }

            // If transitioning at P3, get interpolated offset at factor 1.0 proportionally matching elements
            if (transitionAtP3 != null) {
                TransitionProfile.InterpolatedStack endStack = transitionAtP3.getStackAt(1.0);
                double relPos = elemCenter[i] / Math.max(1.0, totalW * 0.5);
                double targetOffset = relPos * (endStack.totalWidth() * 0.5);
                TransitionProfile.InterpolatedElement ie = endStack.getElementAt(targetOffset);
                if (ie != null) {
                    s1 = (ie.startOffset() + ie.endOffset()) * 0.5;
                }
            }

            Vector3d q0 = p0.add(norm0.multiply(s0));
            Vector3d q3 = p3.add(norm3.multiply(s1));

            double chordLen = q0.distanceTo(q3);
            double scale = centerChordLen > 1e-4 ? (chordLen / centerChordLen) : 1.0;

            Vector3d q1 = q0.add(tan0.multiply(p1.subtract(p0).length() * scale));
            Vector3d q2 = q3.subtract(tan3.multiply(p3.subtract(p2).length() * scale));

            q1 = new Vector3d(q1.x, q0.y + (q3.y - q0.y) / 3.0, q1.z);
            q2 = new Vector3d(q2.x, q0.y + 2.0 * (q3.y - q0.y) / 3.0, q2.z);

            BezierCurve3D modCurve = new BezierCurve3D(q0, q1, q2, q3);

            moduleCurves.add(new RoadModuleCurve(
                    UUID.randomUUID(),
                    elem.getType(),
                    elemW,
                    s0,
                    s1,
                    i,
                    modCurve
            ));
        }

        return moduleCurves;
    }

    /**
     * Resolves an optional transition profile at P0 (start node) if connected to another edge with a different preset.
     */
    public static TransitionProfile getTransitionAtP0(RoadNetworkGraph graph, RoadEdge edge, ModularRoadPreset preset) {
        if (graph == null || edge == null || preset == null) return null;
        RoadNode startNode = graph.getNode(edge.getNodeAId());
        if (startNode == null) return null;
        for (UUID otherEdgeId : startNode.getConnectedEdgeIds()) {
            if (otherEdgeId.equals(edge.getId())) continue;
            RoadEdge otherEdge = graph.getEdge(otherEdgeId);
            if (otherEdge == null) continue;
            String otherPresetId = otherEdge.getPresetId();
            if (otherPresetId != null && !otherPresetId.equals(preset.getId())) {
                ModularRoadPreset otherPreset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(otherPresetId))
                        .findFirst()
                        .orElse(null);
                if (otherPreset != null) {
                    return new TransitionProfile(otherPreset, preset);
                }
            }
        }
        return null;
    }

    /**
     * Resolves an optional transition profile at P3 (end node) if connected to another edge with a different preset.
     */
    public static TransitionProfile getTransitionAtP3(RoadNetworkGraph graph, RoadEdge edge, ModularRoadPreset preset) {
        if (graph == null || edge == null || preset == null) return null;
        RoadNode endNode = graph.getNode(edge.getNodeBId());
        if (endNode == null) return null;
        for (UUID otherEdgeId : endNode.getConnectedEdgeIds()) {
            if (otherEdgeId.equals(edge.getId())) continue;
            RoadEdge otherEdge = graph.getEdge(otherEdgeId);
            if (otherEdge == null) continue;
            String otherPresetId = otherEdge.getPresetId();
            if (otherPresetId != null && !otherPresetId.equals(preset.getId())) {
                ModularRoadPreset otherPreset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(otherPresetId))
                        .findFirst()
                        .orElse(null);
                if (otherPreset != null) {
                    return new TransitionProfile(preset, otherPreset);
                }
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadModuleCurve that = (RoadModuleCurve) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RoadModuleCurve{" +
                "type=" + type +
                ", width=" + width +
                ", startOffset=" + startOffset +
                ", endOffset=" + endOffset +
                '}';
    }
}
