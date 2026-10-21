package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.client.editmode.EditModeManager;
import com.roadbuilder.mod.client.editmode.EditTool;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

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
        EditTool[] tools = EditTool.values();
        int toolCount = tools.length;

        int slotWidth = 84;
        int slotHeight = 22;
        int padding = 4;
        int totalWidth = toolCount * slotWidth + (toolCount - 1) * padding + 8;
        int startX = (screenWidth - totalWidth) / 2;
        int startY = 6;

        // Background panel
        guiGraphics.fill(startX - 2, startY - 2, startX + totalWidth + 2, startY + slotHeight + 2, 0xCC111116);
        guiGraphics.renderOutline(startX - 2, startY - 2, totalWidth + 4, slotHeight + 4, 0xFF3B4252);

        EditTool currentTool = manager.getCurrentTool();

        for (int i = 0; i < toolCount; i++) {
            EditTool tool = tools[i];
            int slotX = startX + 4 + i * (slotWidth + padding);
            int slotY = startY;

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
        } else {
            statusText = currentTool.getDescription();
        }

        int subTextWidth = mc.font.width(statusText);
        int subTextX = (screenWidth - subTextWidth) / 2;
        guiGraphics.fill(subTextX - 4, startY + slotHeight + 6, subTextX + subTextWidth + 4, startY + slotHeight + 18, 0xAA000000);
        guiGraphics.drawString(mc.font, statusText, subTextX, startY + slotHeight + 8, 0xFFE5E9F0, true);

        // Chain point counter & shortcuts guide on the side
        int count = manager.getChainedPoints().size();
        String counterText = net.minecraft.network.chat.Component.translatable("roadbuilder.overlay.status_bar", count).getString();
        int counterWidth = mc.font.width(counterText);
        int counterX = (screenWidth - counterWidth) / 2;
        guiGraphics.drawString(mc.font, counterText, counterX, startY + slotHeight + 22, 0xFFD8DEE9, true);
    }
}
