package com.roadbuilder.mod.client.editmode;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the client-side Road Edit Mode state machine.
 * Supports:
 * - Active tool switching (ADD_POINT, SELECT_POINT, ADJUST_POINT, DELETE_POINT).
 * - Multi-point continuous road chain (P0 -> P1 -> P2 -> ...).
 * - Point selection and height adjustment via mouse wheel (Axiom style).
 */
public final class EditModeManager {
    private static final EditModeManager INSTANCE = new EditModeManager();

    private boolean editModeActive = false;
    private EditTool currentTool = EditTool.ADD_POINT;

    // Multi-point spline chain
    private final List<Vector3d> chainedPoints = new ArrayList<>();
    private final List<UUID> chainedNodeIds = new ArrayList<>();
    private int selectedPointIndex = -1;

    private EditModeManager() {}

    public static EditModeManager getInstance() {
        return INSTANCE;
    }

    public boolean isEditModeActive() {
        return editModeActive;
    }

    public EditTool getCurrentTool() {
        return currentTool;
    }

    public void setCurrentTool(EditTool tool) {
        this.currentTool = tool;
        updatePreviewCurves();
        ClientGraphCache.getInstance().markPreviewDirty();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("roadbuilder.message.tool_selected", tool.getDisplayName(), tool.getDescriptionComponent()), true);
        }
    }

    public int getSelectedPointIndex() {
        return selectedPointIndex;
    }

    public void setSelectedPointIndex(int index) {
        if (index >= 0 && index < chainedPoints.size()) {
            this.selectedPointIndex = index;
        } else {
            this.selectedPointIndex = -1;
        }
    }

    public void toggleEditMode() {
        editModeActive = !editModeActive;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (!editModeActive) {
            cancelCurrentOperation();
        }

        if (player != null) {
            Component msg = editModeActive
                    ? Component.translatable("roadbuilder.message.edit_mode_enabled")
                    : Component.translatable("roadbuilder.message.edit_mode_disabled");
            player.displayClientMessage(msg, true);
        }
    }

    public void updatePreview(NodeRaycaster.RaycastResult raycast) {
        if (!editModeActive || chainedPoints.isEmpty()) {
            ClientGraphCache.getInstance().clearPreview();
            return;
        }

        List<Vector3d> allPts = new ArrayList<>(chainedPoints);

        // Project cursor for ribbon line when actively adding points
        if (currentTool == EditTool.ADD_POINT && raycast.worldPosition().isPresent()) {
            Vector3d cursor = raycast.worldPosition().get();
            Vector3d last = chainedPoints.get(chainedPoints.size() - 1);
            if (last.distanceTo(cursor) >= 1.0) {
                allPts.add(cursor);
            }
        }

        if (allPts.size() < 2) {
            ClientGraphCache.getInstance().clearPreview();
            return;
        }

        List<BezierCurve3D> curves = generateCurvesFromChain(allPts, chainedNodeIds);
        // Update lightweight ribbon curves without marking previewDirty (avoids per-frame voxel recalculation)
        ClientGraphCache.getInstance().getPreviewCurves().clear();
        ClientGraphCache.getInstance().getPreviewCurves().addAll(curves);
    }

    public void updatePreviewCurves() {
        if (!editModeActive || chainedPoints.size() < 2) {
            ClientGraphCache.getInstance().clearPreview();
            return;
        }

        List<BezierCurve3D> curves = generateCurvesFromChain(chainedPoints, chainedNodeIds);
        ClientGraphCache.getInstance().setPreviewCurves(curves);
        ClientGraphCache.getInstance().markPreviewDirty();
    }

    /**
     * Standard Catmull-Rom to Cubic Bezier conversion for a chain of points.
     * Guarantees smooth C1-continuity without bulges or waves (Bug 7).
     * Connects collinear tangents to existing nodes at both start and end (Bug 13).
     */
    public static List<BezierCurve3D> generateCurvesFromChain(List<Vector3d> points, List<UUID> nodeIds) {
        int n = points.size();
        if (n < 2) {
            return Collections.emptyList();
        }

        Vector3d[] tangents = new Vector3d[n];

        // 1. Internal tangents: T_i = (P_{i+1} - P_{i-1}) / 2
        for (int i = 1; i < n - 1; i++) {
            tangents[i] = points.get(i + 1).subtract(points.get(i - 1)).multiply(0.5);
        }

        // 2. Start tangent: T_0 = P_1 - P_0 (or aligned tangent from attached start node)
        UUID startId = (!nodeIds.isEmpty()) ? nodeIds.get(0) : null;
        RoadNode existingStart = (startId != null) ? ClientGraphCache.getInstance().getGraph().getNode(startId) : null;
        Vector3d dirFromStart = points.get(1).subtract(points.get(0));
        double firstDist = dirFromStart.length();

        if (existingStart != null && existingStart.getTangent() != null && existingStart.getTangent().lengthSquared() > 0.01) {
            Vector3d nodeTang = existingStart.getTangent();
            if (nodeTang.dot(dirFromStart) < 0) {
                nodeTang = nodeTang.multiply(-1.0);
            }
            tangents[0] = nodeTang.normalize().multiply(firstDist);
        } else {
            tangents[0] = dirFromStart;
        }

        // 3. End tangent: T_{N-1} = P_{N-1} - P_{N-2} (or aligned tangent from attached end node) (Bug 13)
        UUID endId = (nodeIds.size() >= n) ? nodeIds.get(n - 1) : null;
        RoadNode existingEnd = (endId != null) ? ClientGraphCache.getInstance().getGraph().getNode(endId) : null;
        Vector3d dirToEnd = points.get(n - 1).subtract(points.get(n - 2));
        double lastDist = dirToEnd.length();

        if (existingEnd != null && existingEnd.getTangent() != null && existingEnd.getTangent().lengthSquared() > 0.01) {
            Vector3d nodeTang = existingEnd.getTangent();
            if (nodeTang.dot(dirToEnd) < 0) {
                nodeTang = nodeTang.multiply(-1.0);
            }
            tangents[n - 1] = nodeTang.normalize().multiply(lastDist);
        } else {
            tangents[n - 1] = dirToEnd;
        }

        // 4. Construct cubic Bezier curves for each segment
        List<BezierCurve3D> curves = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            Vector3d pStart = points.get(i);
            Vector3d pEnd = points.get(i + 1);
            double dist = pStart.distanceTo(pEnd);
            if (dist < 1e-6) continue;

            Vector3d tA = tangents[i];
            Vector3d tB = tangents[i + 1];

            // Clamp tangent lengths so they don't exceed segment distance L
            if (tA.length() > dist) {
                tA = tA.normalize().multiply(dist);
            }
            if (tB.length() > dist) {
                tB = tB.normalize().multiply(dist);
            }

            Vector3d c1 = pStart.add(tA.multiply(1.0 / 3.0));
            Vector3d c2 = pEnd.subtract(tB.multiply(1.0 / 3.0));

            // Linear Y elevation to eliminate vertical overshoots
            c1 = new Vector3d(c1.x, pStart.y + (pEnd.y - pStart.y) / 3.0, c1.z);
            c2 = new Vector3d(c2.x, pStart.y + 2.0 * (pEnd.y - pStart.y) / 3.0, c2.z);

            curves.add(new BezierCurve3D(pStart, c1, c2, pEnd));
        }

        return curves;
    }

    public void handleLeftClick(NodeRaycaster.RaycastResult raycast) {
        if (!editModeActive) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();

        switch (currentTool) {
            case ADD_POINT -> handleAddPointClick(raycast, mc);
            case SELECT_POINT, ADJUST_POINT -> handleSelectPointClick(raycast, mc);
            case DELETE_POINT -> handleDeletePointClick(raycast, mc);
        }
    }

    private void handleAddPointClick(NodeRaycaster.RaycastResult raycast, Minecraft mc) {
        if (raycast.worldPosition().isEmpty()) return;

        Vector3d clickedPos = raycast.worldPosition().get();
        Optional<RoadNode> hoveredNode = raycast.hoveredNode();
        UUID nodeId = hoveredNode.map(RoadNode::getId).orElse(null);

        if (chainedPoints.isEmpty()) {
            chainedPoints.add(clickedPos);
            chainedNodeIds.add(nodeId);
            selectedPointIndex = 0;
            updatePreviewCurves();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("roadbuilder.message.point_first_placed"), true);
            }
        } else {
            Vector3d lastPt = chainedPoints.get(chainedPoints.size() - 1);
            if (lastPt.distanceTo(clickedPos) < 1.0) {
                finishAndBuildChain();
                return;
            }

            chainedPoints.add(clickedPos);
            chainedNodeIds.add(nodeId);
            selectedPointIndex = chainedPoints.size() - 1;
            updatePreviewCurves();

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("roadbuilder.message.point_added", chainedPoints.size()), true);
            }
        }
    }

    private void handleSelectPointClick(NodeRaycaster.RaycastResult raycast, Minecraft mc) {
        if (raycast.worldPosition().isEmpty() || chainedPoints.isEmpty()) return;

        Vector3d click = raycast.worldPosition().get();
        int closestIdx = findClosestChainPoint(click, 4.0);
        if (closestIdx >= 0) {
            selectedPointIndex = closestIdx;
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("roadbuilder.message.point_selected", (closestIdx + 1)), true);
            }
        }
    }

    private void handleDeletePointClick(NodeRaycaster.RaycastResult raycast, Minecraft mc) {
        if (chainedPoints.isEmpty()) return;

        if (raycast.worldPosition().isPresent()) {
            Vector3d click = raycast.worldPosition().get();
            int closestIdx = findClosestChainPoint(click, 4.0);
            if (closestIdx >= 0) {
                removePoint(closestIdx);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("roadbuilder.message.point_deleted", (closestIdx + 1), chainedPoints.size()), true);
                }
                return;
            }
        }

        // Otherwise delete last point
        removePoint(chainedPoints.size() - 1);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("roadbuilder.message.last_point_deleted", chainedPoints.size()), true);
        }
    }

    public void adjustSelectedPointHeight(double deltaY) {
        moveSelectedPoint(0, deltaY, 0);
    }

    public void moveSelectedPoint(double dx, double dy, double dz) {
        if (selectedPointIndex < 0 || selectedPointIndex >= chainedPoints.size()) {
            return;
        }

        Vector3d pt = chainedPoints.get(selectedPointIndex);
        Vector3d newPt = new Vector3d(pt.x + dx, pt.y + dy, pt.z + dz);
        chainedPoints.set(selectedPointIndex, newPt);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("roadbuilder.message.point_coords",
                    (selectedPointIndex + 1),
                    String.format(java.util.Locale.US, "%.1f", newPt.x),
                    String.format(java.util.Locale.US, "%.1f", newPt.y),
                    String.format(java.util.Locale.US, "%.1f", newPt.z)), true);
        }

        // Recompute preview immediately
        updatePreviewCurves();
    }

    public void removePoint(int index) {
        if (index >= 0 && index < chainedPoints.size()) {
            chainedPoints.remove(index);
            chainedNodeIds.remove(index);
            if (selectedPointIndex >= chainedPoints.size()) {
                selectedPointIndex = chainedPoints.size() - 1;
            }
            if (chainedPoints.isEmpty()) {
                cancelCurrentOperation();
            } else {
                updatePreviewCurves();
            }
        }
    }

    private int findClosestChainPoint(Vector3d target, double maxDist) {
        int bestIdx = -1;
        double bestDistSq = maxDist * maxDist;
        for (int i = 0; i < chainedPoints.size(); i++) {
            double dSq = chainedPoints.get(i).distanceToSquared(target);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    public void finishAndBuildChain() {
        if (chainedPoints.size() < 2) {
            cancelCurrentOperation();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        List<BezierCurve3D> curves = generateCurvesFromChain(chainedPoints, chainedNodeIds);

        for (int i = 0; i < curves.size(); i++) {
            BezierCurve3D curve = curves.get(i);
            UUID startNodeId = chainedNodeIds.get(i);
            UUID endNodeId = (i + 1 < chainedNodeIds.size()) ? chainedNodeIds.get(i + 1) : null;
            ClientInputHandler.sendBuildRequest(curve, startNodeId, endNodeId);
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable("roadbuilder.message.road_built", curves.size()), true);
        }

        cancelCurrentOperation();
    }

    public void cancelCurrentOperation() {
        chainedPoints.clear();
        chainedNodeIds.clear();
        selectedPointIndex = -1;
        ClientGraphCache.getInstance().clearPreview();
    }

    public List<Vector3d> getChainedPoints() {
        return Collections.unmodifiableList(chainedPoints);
    }
}
