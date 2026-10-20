package com.roadbuilder.mod.client.editmode;

import com.roadbuilder.mod.client.key.ModKeyMappings;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.network.payload.C2S_RequestBuildSegmentPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles client input events (key presses, mouse clicks, mouse scroll for height adjustment).
 */
public final class ClientInputHandler {

    private ClientInputHandler() {}

    public static void onClientTick(final ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Handle F7 Toggle Edit Mode
        while (ModKeyMappings.TOGGLE_EDIT_MODE.consumeClick()) {
            EditModeManager.getInstance().toggleEditMode();
        }

        // Handle ENTER to finish multi-point road chain
        while (ModKeyMappings.FINISH_ROAD.consumeClick()) {
            if (EditModeManager.getInstance().isEditModeActive()) {
                EditModeManager.getInstance().finishAndBuildChain();
            }
        }

        // When in edit mode, continuously raycast and update curve preview
        if (EditModeManager.getInstance().isEditModeActive()) {
            NodeRaycaster.RaycastResult raycast = NodeRaycaster.raycast();
            EditModeManager.getInstance().updatePreview(raycast);
        }
    }

    public static void onKeyInput(final InputEvent.Key event) {
        if (!EditModeManager.getInstance().isEditModeActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }

        // Only handle key press (action == GLFW.GLFW_PRESS)
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_1) {
            EditModeManager.getInstance().setCurrentTool(EditTool.ADD_POINT);
        } else if (key == GLFW.GLFW_KEY_2) {
            EditModeManager.getInstance().setCurrentTool(EditTool.SELECT_POINT);
        } else if (key == GLFW.GLFW_KEY_3) {
            EditModeManager.getInstance().setCurrentTool(EditTool.ADJUST_POINT);
        } else if (key == GLFW.GLFW_KEY_4) {
            EditModeManager.getInstance().setCurrentTool(EditTool.DELETE_POINT);
        } else if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
            EditModeManager.getInstance().removePoint(EditModeManager.getInstance().getSelectedPointIndex());
        }

        // Arrow keys movement in ADJUST_POINT mode
        EditTool currentTool = EditModeManager.getInstance().getCurrentTool();
        if (currentTool == EditTool.ADJUST_POINT && EditModeManager.getInstance().getSelectedPointIndex() >= 0) {
            double step = (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 5.0 : 1.0;
            if (key == GLFW.GLFW_KEY_UP) {
                // North / -Z
                EditModeManager.getInstance().moveSelectedPoint(0, 0, -step);
            } else if (key == GLFW.GLFW_KEY_DOWN) {
                // South / +Z
                EditModeManager.getInstance().moveSelectedPoint(0, 0, step);
            } else if (key == GLFW.GLFW_KEY_LEFT) {
                // West / -X
                EditModeManager.getInstance().moveSelectedPoint(-step, 0, 0);
            } else if (key == GLFW.GLFW_KEY_RIGHT) {
                // East / +X
                EditModeManager.getInstance().moveSelectedPoint(step, 0, 0);
            } else if (key == GLFW.GLFW_KEY_PAGE_UP) {
                // Up / +Y
                EditModeManager.getInstance().moveSelectedPoint(0, step, 0);
            } else if (key == GLFW.GLFW_KEY_PAGE_DOWN) {
                // Down / -Y
                EditModeManager.getInstance().moveSelectedPoint(0, -step, 0);
            }
        }
    }

    public static void onMouseScroll(final InputEvent.MouseScrollingEvent event) {
        if (!EditModeManager.getInstance().isEditModeActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }

        // In ADJUST_POINT or SELECT_POINT mode, mouse wheel moves the selected point
        // along the dominant axis player is looking towards (Axiom style)
        EditTool tool = EditModeManager.getInstance().getCurrentTool();
        if (tool == EditTool.ADJUST_POINT || tool == EditTool.SELECT_POINT) {
            int selectedIdx = EditModeManager.getInstance().getSelectedPointIndex();
            if (selectedIdx >= 0) {
                double scroll = event.getScrollDeltaY();
                if (Math.abs(scroll) > 0.1 && mc.player != null) {
                    double sign = scroll > 0 ? 1.0 : -1.0;
                    net.minecraft.world.phys.Vec3 look = mc.player.getViewVector(1.0f);

                    double dx = 0;
                    double dy = 0;
                    double dz = 0;

                    // If looking sharply up or down (|look.y| > 0.707 or ~45 degrees), scroll moves vertically
                    if (Math.abs(look.y) > 0.707) {
                        dy = (look.y > 0 ? 1.0 : -1.0) * sign;
                    } else {
                        // Looking horizontally: find dominant horizontal axis (X or Z)
                        if (Math.abs(look.x) > Math.abs(look.z)) {
                            dx = (look.x > 0 ? 1.0 : -1.0) * sign;
                        } else {
                            dz = (look.z > 0 ? 1.0 : -1.0) * sign;
                        }
                    }

                    EditModeManager.getInstance().moveSelectedPoint(dx, dy, dz);
                    event.setCanceled(true);
                }
            }
        }
    }

    public static void onMouseButton(final InputEvent.MouseButton.Pre event) {
        if (!EditModeManager.getInstance().isEditModeActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return; // Don't intercept clicks inside GUI screens
        }

        // Left Click = Tool action (Add / Select / Delete)
        if (event.getButton() == 0 && event.getAction() == 1) { // 0 = Left click, 1 = Press
            NodeRaycaster.RaycastResult raycast = NodeRaycaster.raycast();
            EditModeManager.getInstance().handleLeftClick(raycast);
            event.setCanceled(true); // Prevent breaking blocks while editing roads!
        }
        // Right Click = Cancel chain or current operation
        else if (event.getButton() == 1 && event.getAction() == 1) { // 1 = Right click, 1 = Press
            if (!EditModeManager.getInstance().getChainedPoints().isEmpty()) {
                EditModeManager.getInstance().cancelCurrentOperation();
                if (mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c[RoadBuilder] Строительство отменено."), true);
                }
                event.setCanceled(true);
            }
        }
    }

    public static void sendBuildRequest(BezierCurve3D curve, UUID startNodeId, UUID endNodeId) {
        C2S_RequestBuildSegmentPayload payload = new C2S_RequestBuildSegmentPayload(
                curve.getP0(),
                curve.getP1(),
                curve.getP2(),
                curve.getP3(),
                Optional.ofNullable(startNodeId),
                Optional.ofNullable(endNodeId),
                "Standard City Avenue"
        );

        PacketDistributor.sendToServer(payload);
    }
}
