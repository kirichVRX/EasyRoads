package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.client.editmode.EditModeManager;
import com.roadbuilder.mod.client.editmode.EditTool;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Top-center hotbar HUD rendered while in Road Builder edit mode (like Axiom's top tool bar).
 * Displays tools:
 * 1. Add Point (ЛКМ ставить точки)
 * 2. Select Point (ЛКМ выбрать точку)
 * 3. Adjust Height (Колёсико мыши вверх/вниз)
 * 4. Delete Point (ЛКМ удалить точку)
 */
public final class RoadEditorOverlay {

    private RoadEditorOverlay() {}

    public static void render(GuiGraphics guiGraphics, net.minecraft.client.DeltaTracker deltaTracker) {
        EditModeManager manager = EditModeManager.getInstance();
        if (!manager.isEditModeActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) {
            return;
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        EditModeManager.EditorPanel activePanel = manager.getActivePanel();
        EditTool[] tools = activePanel.getTools();
        int toolCount = tools.length;

        int slotWidth = 84;
        int slotHeight = 22;
        int padding = 4;
        int totalWidth = Math.max(220, toolCount * slotWidth + (toolCount - 1) * padding + 8);
        int startX = (screenWidth - totalWidth) / 2;
        int startY = 4;

        // Tab header: [Tab] BUILD | EDIT
        int tabY = startY;
        int tabH = 12;
        int contentY = tabY + tabH + 2;

        String tabHint = "[Tab] ";
        int hintW = mc.font.width(tabHint);
        guiGraphics.drawString(mc.font, tabHint, startX + 4, tabY + 2, 0xFF888888, false);

        String buildTab = " BUILD ";
        int buildW = mc.font.width(buildTab);
        boolean isBuildActive = (activePanel == EditModeManager.EditorPanel.BUILD);
        guiGraphics.fill(startX + 4 + hintW, tabY, startX + 4 + hintW + buildW, tabY + tabH, isBuildActive ? 0xDD2E3440 : 0x661E2436);
        guiGraphics.drawString(mc.font, buildTab, startX + 4 + hintW, tabY + 2, isBuildActive ? 0xFF88C0D0 : 0xFF888888, false);

        String editTab = " EDIT ";
        int editW = mc.font.width(editTab);
        boolean isEditActive = (activePanel == EditModeManager.EditorPanel.EDIT);
        guiGraphics.fill(startX + 8 + hintW + buildW, tabY, startX + 8 + hintW + buildW + editW, tabY + tabH, isEditActive ? 0xDD2E3440 : 0x661E2436);
        guiGraphics.drawString(mc.font, editTab, startX + 8 + hintW + buildW, tabY + 2, isEditActive ? 0xFF88C0D0 : 0xFF888888, false);

        // Background panel for tool slots
        guiGraphics.fill(startX - 2, contentY - 2, startX + totalWidth + 2, contentY + slotHeight + 2, 0xCC111116);
        guiGraphics.renderOutline(startX - 2, contentY - 2, totalWidth + 4, slotHeight + 4, 0xFF3B4252);

        EditTool currentTool = manager.getCurrentTool();

        for (int i = 0; i < toolCount; i++) {
            EditTool tool = tools[i];
            int slotX = startX + 4 + i * (slotWidth + padding);
            int slotY = contentY;

            boolean isSelected = tool == currentTool;

            // Slot background
            int bgColor = isSelected ? 0xDD2E3440 : 0x8820242C;
            int borderColor = isSelected ? 0xFF88C0D0 : 0xFF4C566A;

            guiGraphics.fill(slotX, slotY, slotX + slotWidth, slotY + slotHeight, bgColor);
            guiGraphics.renderOutline(slotX, slotY, slotWidth, slotHeight, borderColor);

            // Key shortcut number [1], [2], [3], [4]
            String numStr = "[" + (i + 1) + "]";
            guiGraphics.drawString(mc.font, numStr, slotX + 4, slotY + 7, isSelected ? 0xFFECEFF4 : 0xFF888888, false);

            // Render custom tool icon (16x16)
            int iconX = slotX + 20;
            int iconY = slotY + 3;
            guiGraphics.blit(tool.getIconLocation(), iconX, iconY, 0, 0, 16, 16, 16, 16);

            // Tool label
            int textX = slotX + 38;
            guiGraphics.drawString(mc.font, tool.getLabel(), textX, slotY + 7, isSelected ? 0xFF88C0D0 : 0xFFD8DEE9, false);
        }

        // Subtitle below hotbar: description of active tool + status
        int selectedPointIdx = manager.getSelectedPointIndex();
        String statusText;
        if (currentTool == EditTool.ADJUST_POINT) {
            if (selectedPointIdx >= 0) {
                statusText = net.minecraft.network.chat.Component.translatable("roadbuilder.overlay.adjust_active").getString();
            } else {
                statusText = net.minecraft.network.chat.Component.translatable("roadbuilder.overlay.adjust_hint").getString();
            }
        } else if (currentTool == EditTool.MOVE_EXISTING_NODE) {
            if (manager.getMovingNodeId() != null) {
                statusText = net.minecraft.network.chat.Component.translatable("roadbuilder.message.node_grabbed").getString();
            } else {
                statusText = currentTool.getDescription();
            }
        } else {
            statusText = currentTool.getDescription();
        }

        int subTextWidth = mc.font.width(statusText);
        int subTextX = (screenWidth - subTextWidth) / 2;
        int subY = contentY + slotHeight + 6;
        guiGraphics.fill(subTextX - 4, subY - 1, subTextX + subTextWidth + 4, subY + 11, 0xAA000000);
        guiGraphics.drawString(mc.font, statusText, subTextX, subY + 1, 0xFFE5E9F0, true);

        // Chain point counter & shortcuts guide on the side
        int count = manager.getChainedPoints().size();
        String counterText = net.minecraft.network.chat.Component.translatable("roadbuilder.overlay.status_bar", count).getString();
        int counterWidth = mc.font.width(counterText);
        int counterX = (screenWidth - counterWidth) / 2;
        guiGraphics.drawString(mc.font, counterText, counterX, subY + 15, 0xFFD8DEE9, true);

        // Hovered node / edge info tooltip
        UUID hoveredNodeId = ClientGraphCache.getInstance().getHoveredNodeId();
        UUID hoveredEdgeId = ClientGraphCache.getInstance().getHoveredEdgeId();
        String hoverInfo = null;

        if (hoveredNodeId != null) {
            RoadNode hNode = ClientGraphCache.getInstance().getGraph().getNode(hoveredNodeId);
            if (hNode != null) {
                hoverInfo = String.format(java.util.Locale.US,
                        "§bNode §f[%s] §7| §eEdges: §f%d §7| §aPos: §f(%.1f, %.1f, %.1f)",
                        hNode.getType().name(),
                        hNode.getConnectedEdgeIds().size(),
                        hNode.getPosition().x, hNode.getPosition().y, hNode.getPosition().z);
            }
        } else if (hoveredEdgeId != null) {
            RoadEdge hEdge = ClientGraphCache.getInstance().getGraph().getEdge(hoveredEdgeId);
            if (hEdge != null) {
                hoverInfo = String.format(java.util.Locale.US,
                        "§bRoad §f[%s] §7| §aLength: §f%.1fm §7| §eWidth: §f%.1fm",
                        hEdge.getProfile().getName(),
                        hEdge.getCurve().getApproximateLength(),
                        hEdge.getProfile().getWidth());
            }
        }

        if (hoverInfo != null) {
            int hW = mc.font.width(hoverInfo);
            int hX = (screenWidth - hW) / 2;
            int hY = subY + 28;
            guiGraphics.fill(hX - 4, hY - 1, hX + hW + 4, hY + 11, 0xDD111116);
            guiGraphics.renderOutline(hX - 4, hY - 1, hW + 8, 12, 0xFF354466);
            guiGraphics.drawString(mc.font, hoverInfo, hX, hY + 1, 0xFFFFFFFF, true);
        }
    }
}
