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
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§6[Инструмент]: §f" + tool.getLabel() + " §7- " + tool.getDescription()), true);
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
            String msg = editModeActive
                    ? "§a[RoadBuilder] Режим редактирования включен (F7). Инструменты [1-4]"
                    : "§c[RoadBuilder] Режим редактирования выключен";
            player.displayClientMessage(Component.literal(msg), true);
        }
    }

    public void updatePreview(NodeRaycaster.RaycastResult raycast) {
        if (!editModeActive || chainedPoints.isEmpty()) {
            ClientGraphCache.getInstance().clearPreview();
            return;
        }

        List<Vector3d> allPts = new ArrayList<>(chainedPoints);

        // Only project cursor when actively adding points
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

        List<BezierCurve3D> curves = new ArrayList<>();
        for (int i = 0; i < allPts.size() - 1; i++) {
            Vector3d p0 = allPts.get(i);
            Vector3d p3 = allPts.get(i + 1);
            double dist = p0.distanceTo(p3);

            Vector3d forward;
            if (i > 0) {
                Vector3d prevPt = allPts.get(i - 1);
                forward = p0.subtract(prevPt).normalize();
            } else {
                // If the first point is attached to an existing node, use that node's outgoing tangent!
                UUID startId = !chainedNodeIds.isEmpty() ? chainedNodeIds.get(0) : null;
                RoadNode existingStart = (startId != null) ? ClientGraphCache.getInstance().getGraph().getNode(startId) : null;
                if (existingStart != null && existingStart.getTangent() != null && existingStart.getTangent().lengthSquared() > 0.01) {
                    Vector3d nodeTang = existingStart.getTangent();
                    Vector3d dirToEnd = p3.subtract(p0).normalize();
                    // Align tangent direction with the outgoing road direction
                    if (nodeTang.dot(dirToEnd) >= 0) {
                        forward = nodeTang;
                    } else {
                        forward = nodeTang.multiply(-1.0);
                    }
                } else {
                    forward = p3.subtract(p0).normalize();
                }
            }

            Vector3d p1 = p0.add(forward.multiply(dist / 3.0));
            Vector3d toEnd = p3.subtract(p0).normalize();
            Vector3d p2 = p3.subtract(toEnd.multiply(dist / 3.0));
            p1 = new Vector3d(p1.x, p0.y + (p3.y - p0.y) / 3.0, p1.z);
            p2 = new Vector3d(p2.x, p0.y + 2.0 * (p3.y - p0.y) / 3.0, p2.z);

            curves.add(new BezierCurve3D(p0, p1, p2, p3));
        }

        ClientGraphCache.getInstance().setPreviewCurves(curves);
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
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§eТочка #1 поставлена. Кликайте дальше или нажмите ENTER для постройки!"), true);
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

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§eТочка #" + chainedPoints.size() + " добавлена."), true);
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
                mc.player.displayClientMessage(Component.literal("§aВыбрана точка #" + (closestIdx + 1) + ". Колесо мыши меняет высоту!"), true);
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
                    mc.player.displayClientMessage(Component.literal("§cУдалена точка #" + (closestIdx + 1) + ". Осталось: " + chainedPoints.size()), true);
                }
                return;
            }
        }

        // Otherwise delete last point
        removePoint(chainedPoints.size() - 1);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§cУдалена последняя точка. Осталось: " + chainedPoints.size()), true);
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
            mc.player.displayClientMessage(Component.literal(String.format("§bТочка #%d: [X: %.1f, Y: %.1f, Z: %.1f]",
                    (selectedPointIndex + 1), newPt.x, newPt.y, newPt.z)), true);
        }

        // Recompute preview immediately
        updatePreview(new NodeRaycaster.RaycastResult(Optional.empty(), Optional.empty()));
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

        for (int i = 0; i < chainedPoints.size() - 1; i++) {
            Vector3d p0 = chainedPoints.get(i);
            Vector3d p3 = chainedPoints.get(i + 1);
            UUID startNodeId = chainedNodeIds.get(i);
            UUID endNodeId = chainedNodeIds.get(i + 1);

            double dist = p0.distanceTo(p3);
            Vector3d forward;
            if (i > 0) {
                Vector3d prevPt = chainedPoints.get(i - 1);
                forward = p0.subtract(prevPt).normalize();
            } else {
                RoadNode existingStart = (startNodeId != null) ? ClientGraphCache.getInstance().getGraph().getNode(startNodeId) : null;
                if (existingStart != null && existingStart.getTangent() != null && existingStart.getTangent().lengthSquared() > 0.01) {
                    Vector3d nodeTang = existingStart.getTangent();
                    Vector3d dirToEnd = p3.subtract(p0).normalize();
                    forward = (nodeTang.dot(dirToEnd) >= 0) ? nodeTang : nodeTang.multiply(-1.0);
                } else {
                    forward = p3.subtract(p0).normalize();
                }
            }

            Vector3d p1 = p0.add(forward.multiply(dist / 3.0));
            Vector3d toEnd = p3.subtract(p0).normalize();
            Vector3d p2 = p3.subtract(toEnd.multiply(dist / 3.0));
            p1 = new Vector3d(p1.x, p0.y + (p3.y - p0.y) / 3.0, p1.z);
            p2 = new Vector3d(p2.x, p0.y + 2.0 * (p3.y - p0.y) / 3.0, p2.z);

            BezierCurve3D curve = new BezierCurve3D(p0, p1, p2, p3);
            ClientInputHandler.sendBuildRequest(curve, startNodeId, endNodeId);
        }

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§a[RoadBuilder] Дорога (" + (chainedPoints.size() - 1) + " сегментов) построена!"), true);
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
