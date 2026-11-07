package com.roadbuilder.mod.client.gui;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.client.editmode.EditModeManager;
import com.roadbuilder.mod.client.editmode.EditTool;
import com.roadbuilder.mod.domain.graph.NodeType;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.network.payload.C2S_DeleteNodePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

/**
 * Context menu popup displayed when right-clicking a graph node in Edit Mode.
 */
public class NodeContextMenu extends Screen {

    private final RoadNode node;

    public NodeContextMenu(RoadNode node) {
        super(Component.translatable("roadbuilder.gui.node_menu_title"));
        this.node = Objects.requireNonNull(node, "node cannot be null");
    }

    @Override
    protected void init() {
        super.init();

        int menuW = 180;
        int btnH = 20;
        int spacing = 4;
        int totalH = 5 * (btnH + spacing) + 30;

        int menuX = (this.width - menuW) / 2;
        int menuY = (this.height - totalH) / 2;

        int curY = menuY + 24;

        // 1. Move Node
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.menu_move_node"), b -> {
            EditModeManager.getInstance().setCurrentTool(EditTool.MOVE_EXISTING_NODE);
            this.onClose();
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 2. Delete Node
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.menu_delete_node"), b -> {
            PacketDistributor.sendToServer(new C2S_DeleteNodePayload(node.getId()));
            this.onClose();
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 3. Configure Junction
        boolean isJunction = node.getType() == NodeType.JUNCTION;
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.menu_config_junction"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new JunctionConfigScreen(
                        node,
                        ClientGraphCache.getInstance().getGraph().getEdgesForNode(node.getId())
                ));
            }
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build()).active = isJunction;
        curY += btnH + spacing;

        // 4. Configure Roundabout
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.menu_config_roundabout"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new RoundaboutConfigScreen(node));
            }
        }).bounds(menuX + 10, curY, menuW - 20, btnH).build());
        curY += btnH + spacing;

        // 5. Close
        this.addRenderableWidget(Button.builder(Component.translatable("roadbuilder.gui.cancel"), b -> this.onClose())
                .bounds(menuX + 10, curY, menuW - 20, btnH).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int menuW = 180;
        int btnH = 20;
        int spacing = 4;
        int totalH = 5 * (btnH + spacing) + 30;

        int menuX = (this.width - menuW) / 2;
        int menuY = (this.height - totalH) / 2;

        // Background card
        guiGraphics.fill(menuX, menuY, menuX + menuW, menuY + totalH, 0xEE1E2436);
        guiGraphics.renderOutline(menuX, menuY, menuW, totalH, 0xFF354466);

        // Header
        String titleStr = "Node: " + node.getType().name();
        guiGraphics.drawString(this.font, titleStr, menuX + 12, menuY + 8, 0xFF88C0D0, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
