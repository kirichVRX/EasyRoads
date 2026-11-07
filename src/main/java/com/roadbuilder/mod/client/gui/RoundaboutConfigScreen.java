package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.network.payload.C2S_CreateRoundaboutPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;
import java.util.UUID;

/**
 * Screen for configuring circular roundabout parameters (outer radius, inner island radius, lane count).
 */
public class RoundaboutConfigScreen extends Screen {

    private final UUID nodeId;
    private final Vector3d position;
    private double outerRadius;
    private double innerRadius;
    private int laneCount;
    private String ringPresetId;

    public RoundaboutConfigScreen(UUID nodeId, Vector3d position, double outerRadius, double innerRadius, int laneCount, String ringPresetId) {
        super(Component.translatable("roadbuilder.gui.roundabout_config_title"));
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId cannot be null");
        this.position = Objects.requireNonNull(position, "position cannot be null");
        this.outerRadius = (outerRadius > 0) ? outerRadius : 12.0;
        this.innerRadius = (innerRadius > 0) ? innerRadius : 5.0;
        this.laneCount = Math.max(1, Math.min(3, laneCount));
        this.ringPresetId = (ringPresetId != null && !ringPresetId.isEmpty()) ? ringPresetId : "default";
    }

    public RoundaboutConfigScreen(RoadNode node) {
        this(
                node.getId(),
                node.getPosition(),
                node.getOuterRadius() > 0 ? node.getOuterRadius() : 12.0,
                node.getInnerRadius() > 0 ? node.getInnerRadius() : 5.0,
                node.getRingLaneCount() > 0 ? node.getRingLaneCount() : 2,
                node.getRingPresetId()
        );
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelW = 240;
        int panelH = 200;
        int panelX = centerX - panelW / 2;
        int panelY = centerY - panelH / 2;

        // Outer radius controls
        int rOutY = panelY + 44;
        this.addRenderableWidget(Button.builder(Component.literal("-2"), b -> adjustOuterRadius(-2.0))
                .bounds(panelX + 20, rOutY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjustOuterRadius(-1.0))
                .bounds(panelX + 60, rOutY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjustOuterRadius(1.0))
                .bounds(panelX + 144, rOutY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+2"), b -> adjustOuterRadius(2.0))
                .bounds(panelX + 184, rOutY, 36, 18).build());

        // Inner radius controls
        int rInY = panelY + 84;
        this.addRenderableWidget(Button.builder(Component.literal("-2"), b -> adjustInnerRadius(-2.0))
                .bounds(panelX + 20, rInY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjustInnerRadius(-1.0))
                .bounds(panelX + 60, rInY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjustInnerRadius(1.0))
                .bounds(panelX + 144, rInY, 36, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+2"), b -> adjustInnerRadius(2.0))
                .bounds(panelX + 184, rInY, 36, 18).build());

        // Lane count controls
        int laneY = panelY + 124;
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> adjustLanes(-1))
                .bounds(panelX + 50, laneY, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> adjustLanes(1))
                .bounds(panelX + 150, laneY, 40, 18).build());

        // Apply and Cancel buttons
        int actionY = panelY + panelH - 28;
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.apply"), b -> applyAndClose())
                .bounds(panelX + 20, actionY, 96, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.cancel"), b -> this.onClose())
                .bounds(panelX + 124, actionY, 96, 20).build());
    }

    private void adjustOuterRadius(double delta) {
        this.outerRadius = Math.max(8.0, Math.min(30.0, this.outerRadius + delta));
        if (this.innerRadius >= this.outerRadius - 3.0) {
            this.innerRadius = Math.max(2.0, this.outerRadius - 4.0);
        }
    }

    private void adjustInnerRadius(double delta) {
        this.innerRadius = Math.max(2.0, Math.min(this.outerRadius - 4.0, this.innerRadius + delta));
    }

    private void adjustLanes(int delta) {
        this.laneCount = Math.max(1, Math.min(3, this.laneCount + delta));
    }

    private void applyAndClose() {
        PacketDistributor.sendToServer(new C2S_CreateRoundaboutPayload(
                this.nodeId,
                this.position,
                this.outerRadius,
                this.innerRadius,
                this.laneCount,
                this.ringPresetId
        ));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelW = 240;
        int panelH = 200;
        int panelX = centerX - panelW / 2;
        int panelY = centerY - panelH / 2;

        // Window background
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE1E2436);
        guiGraphics.renderOutline(panelX, panelY, panelW, panelH, 0xFF354466);

        // Title
        guiGraphics.drawString(this.font, this.title, panelX + 12, panelY + 10, 0xFFECEFF4, false);

        // Outer radius label
        String rOutStr = String.format(java.util.Locale.US, "Outer Radius: %.1f m", this.outerRadius);
        int rOutW = this.font.width(rOutStr);
        guiGraphics.drawString(this.font, rOutStr, centerX - rOutW / 2, panelY + 32, 0xFF88C0D0, false);

        // Inner radius label
        String rInStr = String.format(java.util.Locale.US, "Inner Island: %.1f m", this.innerRadius);
        int rInW = this.font.width(rInStr);
        guiGraphics.drawString(this.font, rInStr, centerX - rInW / 2, panelY + 72, 0xFFA3BE8C, false);

        // Lanes label
        String laneStr = "Lanes: " + this.laneCount;
        int laneW = this.font.width(laneStr);
        guiGraphics.drawString(this.font, laneStr, centerX - laneW / 2, panelY + 112, 0xFFEBCB8B, false);

        // Position coordinates info
        String posInfo = String.format(java.util.Locale.US, "Pos: %.1f, %.1f, %.1f", position.x, position.y, position.z);
        guiGraphics.drawString(this.font, posInfo, panelX + 12, panelY + 152, 0xFF888888, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
