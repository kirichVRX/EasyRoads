package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.network.payload.C2S_ConfigureJunctionPayload;
import com.roadbuilder.mod.server.voxelizer.JunctionGenerator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Objects;

/**
 * GUI screen for configuring junction radius and visual style.
 */
public class JunctionConfigScreen extends Screen {

    private final RoadNode node;
    private final List<RoadEdge> edges;
    private double currentRadius;
    private JunctionGenerator.JunctionStyle currentStyle;

    public JunctionConfigScreen(RoadNode node, List<RoadEdge> edges) {
        super(Component.translatable("roadbuilder.gui.junction_config_title"));
        this.node = Objects.requireNonNull(node);
        this.edges = edges != null ? edges : List.of();
        this.currentRadius = node.getRadius() > 0 ? node.getRadius() : JunctionGenerator.calculateJunctionRadius(node, this.edges);
        this.currentStyle = JunctionGenerator.JunctionStyle.fromString(node.getJunctionStyle());
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelW = 220;
        int panelH = 170;
        int panelX = centerX - panelW / 2;
        int panelY = centerY - panelH / 2;

        // Radius buttons (-2, -1, +1, +2)
        int btnY = panelY + 68;
        this.addRenderableWidget(Button.builder(Component.literal("-2"), b -> adjustRadius(-2.0))
                .bounds(panelX + 20, btnY, 36, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjustRadius(-1.0))
                .bounds(panelX + 60, btnY, 36, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjustRadius(1.0))
                .bounds(panelX + 124, btnY, 36, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+2"), b -> adjustRadius(2.0))
                .bounds(panelX + 164, btnY, 36, 20).build());

        // Style cycle button
        int styleY = panelY + 98;
        this.addRenderableWidget(Button.builder(getStyleComponent(), b -> {
            cycleStyle();
            b.setMessage(getStyleComponent());
        }).bounds(panelX + 20, styleY, panelW - 40, 20).build());

        // Apply and Cancel buttons
        int actionY = panelY + panelH - 30;
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.apply"), b -> {
            applyAndClose();
        }).bounds(panelX + 20, actionY, 86, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.cancel"), b -> {
            this.onClose();
        }).bounds(panelX + 114, actionY, 86, 20).build());
    }

    private void adjustRadius(double delta) {
        this.currentRadius = Math.max(3.0, Math.min(32.0, this.currentRadius + delta));
    }

    private void cycleStyle() {
        JunctionGenerator.JunctionStyle[] styles = JunctionGenerator.JunctionStyle.values();
        int nextIdx = (this.currentStyle.ordinal() + 1) % styles.length;
        this.currentStyle = styles[nextIdx];
    }

    private Component getStyleComponent() {
        return Component.literal("Style: " + this.currentStyle.name());
    }

    private void applyAndClose() {
        PacketDistributor.sendToServer(new C2S_ConfigureJunctionPayload(node.getId(), currentRadius, currentStyle.name()));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Dark translucent background scrim
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelW = 220;
        int panelH = 170;
        int panelX = centerX - panelW / 2;
        int panelY = centerY - panelH / 2;

        // Window panel
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE1E2436);
        guiGraphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF354466);

        // Header
        guiGraphics.drawString(this.font, this.title, panelX + 12, panelY + 10, 0xFFECEFF4, false);

        // Info: connected arms
        String armsInfo = "Connected roads: " + edges.size();
        guiGraphics.drawString(this.font, armsInfo, panelX + 12, panelY + 28, 0xFF88C0D0, false);

        // Radius label
        String radiusLabel = String.format(java.util.Locale.US, "Radius: %.1f blocks", this.currentRadius);
        int radiusW = this.font.width(radiusLabel);
        guiGraphics.drawString(this.font, radiusLabel, centerX - radiusW / 2, panelY + 50, 0xFFFFFFFF, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
