package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.network.payload.C2S_ChangeEdgePresetPayload;
import com.roadbuilder.mod.network.payload.C2S_DeleteEdgePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Context menu popup displayed when right-clicking a road edge/segment in Edit Mode.
 */
public class EdgeContextMenu extends Screen {

    private final RoadEdge edge;
    private final List<ModularRoadPreset> presets;
    private int selectedPresetIndex = 0;

    public EdgeContextMenu(RoadEdge edge) {
        super(Component.translatable("roadbuilder.gui.edge_menu_title"));
        this.edge = Objects.requireNonNull(edge, "edge cannot be null");
        this.presets = new ArrayList<>(PresetManager.getInstance().getAllPresets());
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).getId().equals(edge.getPresetId())) {
                this.selectedPresetIndex = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        int menuW = 200;
        int btnH = 20;
        int spacing = 4;
        int totalH = 4 * (btnH + spacing) + 50;

        int menuX = (this.width - menuW) / 2;
        int menuY = (this.height - totalH) / 2;

        int curY = menuY + 44;

        // 1. Cycle / Change Preset button
        this.addRenderableWidget(Button.builder(getPresetButtonLabel(), b -> {
            if (!presets.isEmpty()) {
                selectedPresetIndex = (selectedPresetIndex + 1) % presets.size();
                b.setMessage(getPresetButtonLabel());
            }
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 2. Apply Preset Change
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.apply_preset"), b -> {
            if (!presets.isEmpty()) {
                ModularRoadPreset chosen = presets.get(selectedPresetIndex);
                PacketDistributor.sendToServer(new C2S_ChangeEdgePresetPayload(edge.getId(), chosen.getId()));
            }
            this.onClose();
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 3. Delete Edge
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.menu_delete_edge"), b -> {
            PacketDistributor.sendToServer(new C2S_DeleteEdgePayload(edge.getId()));
            this.onClose();
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 4. Close
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.cancel"), b -> this.onClose())
                .bounds(menuX + 10, curY, menuW - 20, btnH).build());
    }

    private Component getPresetButtonLabel() {
        if (presets.isEmpty()) return Component.literal("Preset: none");
        return Component.literal("Preset: " + presets.get(selectedPresetIndex).getDisplayName());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int menuW = 200;
        int btnH = 20;
        int spacing = 4;
        int totalH = 4 * (btnH + spacing) + 50;

        int menuX = (this.width - menuW) / 2;
        int menuY = (this.height - totalH) / 2;

        // Background card
        guiGraphics.fill(menuX, menuY, menuX + menuW, menuY + totalH, 0xEE1E2436);
        guiGraphics.renderOutline(menuX, menuY, menuW, totalH, 0xFF354466);

        // Header
        guiGraphics.drawString(this.font, this.title, menuX + 12, menuY + 8, 0xFF88C0D0, false);

        // Edge info
        String infoStr = String.format(java.util.Locale.US, "Length: %.1f m | Width: %.1f m",
                edge.getCurve().getApproximateLength(),
                edge.getProfile().getWidth());
        guiGraphics.drawString(this.font, infoStr, menuX + 12, menuY + 24, 0xFFECEFF4, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
