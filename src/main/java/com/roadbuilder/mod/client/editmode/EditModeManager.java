package com.roadbuilder.mod.client.editmode;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
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

    // Move existing node state
    private UUID movingNodeId = null;
    private Vector3d originalNodePos = null;
    private Vector3d targetNodePos = null;
    private double movingHeightOffset = 0.0;

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

    public UUID getMovingNodeId() {
        return movingNodeId;
    }

    public Vector3d getOriginalNodePos() {
        return originalNodePos;
    }

    public Vector3d getTargetNodePos() {
        return targetNodePos;
    }

    public void adjustMovingNodeHeight(double deltaY) {
        if (movingNodeId == null || targetNodePos == null) return;
        movingHeightOffset += deltaY;
        targetNodePos = new Vector3d(targetNodePos.x, targetNodePos.y + deltaY, targetNodePos.z);
        updateMovingNodePreview();
    }

    public void cancelNodeMove() {
        movingNodeId = null;
        originalNodePos = null;
        targetNodePos = null;
        movingHeightOffset = 0.0;
        ClientGraphCache.getInstance().clearPreview();
    }

    // Active editor panel (Build / Edit)
    public enum EditorPanel {
        BUILD("build", EditTool.ADD_POINT, EditTool.SELECT_POINT, EditTool.ADJUST_POINT, EditTool.DELETE_POINT),
        EDIT("edit", EditTool.MOVE_EXISTING_NODE, EditTool.DELETE_EDGE, EditTool.EDIT_EXISTING_EDGE, EditTool.CREATE_ROUNDABOUT);

        private final String name;
        private final EditTool[] tools;

        EditorPanel(String name, EditTool... tools) {
            this.name = name;
            this.tools = tools;
        }

        public String getName() {
            return name;
        }

        public EditTool[] getTools() {
            return tools;
        }
    }

    private EditorPanel activePanel = EditorPanel.BUILD;

    public EditorPanel getActivePanel() {
        return activePanel;
    }

    public void setActivePanel(EditorPanel panel) {
        if (panel != null && this.activePanel != panel) {
            this.activePanel = panel;
            setCurrentTool(panel.getTools()[0]);
        }
    }

    public void togglePanel() {
        setActivePanel(activePanel == EditorPanel.BUILD ? EditorPanel.EDIT : EditorPanel.BUILD);
    }

    public void setCurrentTool(EditTool tool) {
        this.currentTool = tool;
        if (tool == EditTool.MOVE_EXISTING_NODE || tool == EditTool.DELETE_EDGE || tool == EditTool.EDIT_EXISTING_EDGE || tool == EditTool.CREATE_ROUNDABOUT) {
            this.activePanel = EditorPanel.EDIT;
        } else {
            this.activePanel = EditorPanel.BUILD;
        }
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
        if (!editModeActive) {
            ClientGraphCache.getInstance().clearPreview();
            return;
        }

        if (currentTool == EditTool.MOVE_EXISTING_NODE && movingNodeId != null) {
            if (raycast.worldPosition().isPresent()) {
                Vector3d cursor = raycast.worldPosition().get();
                targetNodePos = new Vector3d(cursor.x, cursor.y + movingHeightOffset, cursor.z);
                updateMovingNodePreview();
            }
            return;
        }

        if (chainedPoints.isEmpty()) {
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

    public void updateMovingNodePreview() {
        if (movingNodeId == null || targetNodePos == null) return;
        RoadNode node = ClientGraphCache.getInstance().getGraph().getNode(movingNodeId);
        if (node == null) return;

        List<BezierCurve3D> previewCurves = new ArrayList<>();
        for (UUID edgeId : node.getConnectedEdgeIds()) {
            com.roadbuilder.mod.domain.graph.RoadEdge edge = ClientGraphCache.getInstance().getGraph().getEdge(edgeId);
            if (edge == null) continue;
            UUID otherId = edge.getNodeAId().equals(movingNodeId) ? edge.getNodeBId() : edge.getNodeAId();
            RoadNode otherNode = ClientGraphCache.getInstance().getGraph().getNode(otherId);
            if (otherNode != null) {
                Vector3d pStart = otherNode.getPosition();
                Vector3d pEnd = targetNodePos;
                Vector3d dir = pEnd.subtract(pStart);
                double dist = dir.length();
                if (dist > 0.1) {
                    Vector3d tA = otherNode.getTangent();
                    if (tA == null || tA.lengthSquared() < 0.01) {
                        tA = dir.normalize();
                    } else if (tA.dot(dir) < 0) {
                        tA = tA.multiply(-1.0).normalize();
                    }
                    Vector3d c1 = pStart.add(tA.multiply(dist / 3.0));
                    Vector3d c2 = pEnd.subtract(dir.normalize().multiply(dist / 3.0));
                    c1 = new Vector3d(c1.x, pStart.y + (pEnd.y - pStart.y) / 3.0, c1.z);
                    c2 = new Vector3d(c2.x, pStart.y + 2.0 * (pEnd.y - pStart.y) / 3.0, c2.z);
                    previewCurves.add(new BezierCurve3D(pStart, c1, c2, pEnd));
                }
            }
        }
        ClientGraphCache.getInstance().getPreviewCurves().clear();
        ClientGraphCache.getInstance().getPreviewCurves().addAll(previewCurves);
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
            case DELETE_EDGE -> handleDeleteEdgeClick(mc);
            case MOVE_EXISTING_NODE -> handleMoveNodeClick(raycast, mc);
            case EDIT_EXISTING_EDGE -> handleEditEdgeClick(mc);
            case CREATE_ROUNDABOUT -> handleCreateRoundaboutClick(raycast, mc);
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
        if (chainedPoints.isEmpty()) {
            if (raycast.hoveredNode().isPresent()) {
                UUID nodeId = raycast.hoveredNode().get().getId();
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.roadbuilder.mod.network.payload.C2S_DeleteNodePayload(nodeId)
                );
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("roadbuilder.message.deleting_node"), true);
                }
            }
            return;
        }

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

    private void handleDeleteEdgeClick(Minecraft mc) {
        Optional<EdgeRaycaster.EdgeHitResult> edgeHit = EdgeRaycaster.findEdgeUnderCursor();
        if (edgeHit.isPresent()) {
            UUID edgeId = edgeHit.get().edgeId();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.roadbuilder.mod.network.payload.C2S_DeleteEdgePayload(edgeId)
            );
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("roadbuilder.message.deleting_edge"), true);
            }
        }
    }

    private void handleMoveNodeClick(NodeRaycaster.RaycastResult raycast, Minecraft mc) {
        if (movingNodeId == null) {
            // First click: select and capture existing node under cursor
            if (raycast.hoveredNode().isPresent()) {
                RoadNode node = raycast.hoveredNode().get();
                movingNodeId = node.getId();
                originalNodePos = node.getPosition();
                targetNodePos = node.getPosition();
                movingHeightOffset = 0.0;
                updateMovingNodePreview();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("roadbuilder.message.node_grabbed"), true);
                }
            }
        } else {
            // Second click: confirm new position and send payload to server
            Vector3d finalPos = (targetNodePos != null) ? targetNodePos : originalNodePos;
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.roadbuilder.mod.network.payload.C2S_MoveNodePayload(movingNodeId, finalPos)
            );
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("roadbuilder.message.node_moving"), true);
            }
            cancelNodeMove();
        }
    }

    private void handleEditEdgeClick(Minecraft mc) {
        Optional<EdgeRaycaster.EdgeHitResult> hit = EdgeRaycaster.findEdgeUnderCursor();
        if (hit.isPresent()) {
            UUID edgeId = hit.get().edgeId();
            RoadEdge edge = ClientGraphCache.getInstance().getGraph().getEdge(edgeId);
            if (edge != null) {
                List<com.roadbuilder.mod.domain.profile.ModularRoadPreset> allPresets =
                        new ArrayList<>(com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getAllPresets());
                if (!allPresets.isEmpty()) {
                    int curIdx = -1;
                    for (int i = 0; i < allPresets.size(); i++) {
                        if (allPresets.get(i).getId().equals(edge.getPresetId())) {
                            curIdx = i;
                            break;
                        }
                    }
                    int nextIdx = (curIdx + 1) % allPresets.size();
                    com.roadbuilder.mod.domain.profile.ModularRoadPreset nextPreset = allPresets.get(nextIdx);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.roadbuilder.mod.network.payload.C2S_ChangeEdgePresetPayload(edgeId, nextPreset.getId())
                    );
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.translatable("roadbuilder.message.preset_changed", nextPreset.getDisplayName()), true);
                    }
                }
            }
        }
    }

    private void handleCreateRoundaboutClick(NodeRaycaster.RaycastResult raycast, Minecraft mc) {
        if (raycast.hoveredNode().isPresent()) {
            RoadNode node = raycast.hoveredNode().get();
            mc.setScreen(new com.roadbuilder.mod.client.gui.RoundaboutConfigScreen(node));
            return;
        }

        if (raycast.worldPosition().isPresent()) {
            Vector3d pos = raycast.worldPosition().get();
            mc.setScreen(new com.roadbuilder.mod.client.gui.RoundaboutConfigScreen(
                    UUID.randomUUID(), pos, 12.0, 5.0, 2, "default"
            ));
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
        cancelNodeMove();
        ClientGraphCache.getInstance().clearPreview();
    }

    public List<Vector3d> getChainedPoints() {
        return Collections.unmodifiableList(chainedPoints);
    }
}
