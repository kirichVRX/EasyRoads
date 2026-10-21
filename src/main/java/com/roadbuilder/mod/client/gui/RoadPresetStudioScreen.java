package com.roadbuilder.mod.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import com.roadbuilder.mod.client.cache.ClientGraphCache;

import java.util.ArrayList;
import java.util.List;

/**
 * Road Preset Studio GUI screen:
 * - 3D preview rendered at lower depth (Z = 150) behind 2D UI panels (Z = 500).
 * - Extended 3D preview length (28 blocks) without clipping.
 * - Left panel with tabs: [ Паки ] and [ Пресеты ] (with create, duplicate, delete actions).
 * - Right panel with wide buttons avoiding Russian text overflow.
 * - Bottom stack with mouse drag-and-drop reordering, horizontal scrolling, and width adjustment.
 * - Mouse wheel scrolling for menus, left panel, and bottom stack.
 * - Explicit 1-block curb stairs and pure 4-block sidewalk paving.
 */
public class RoadPresetStudioScreen extends Screen {

    private float pitch = 25.0f;
    private float yaw = -45.0f;
    private float zoom = 16.0f;
    private boolean isDragging3D = false;
    private double lastMouseX;
    private double lastMouseY;

    private boolean viewRoad = true;
    private boolean viewViaduct = false;
    private boolean viewTunnel = false;
    private boolean viewEmbankment = false;
    private boolean viewSlope = false;

    // Tabs: 0 = Road Packs, 1 = Presets
    private int leftTab = 0;
    private float leftPanelScroll = 0.0f;

    // Bottom stack controls
    private int selectedElementIdx = -1;
    private boolean isDraggingElement = false;
    private int draggedElementIdx = -1;
    private float stackScrollX = 0.0f;
    private float maxStackScroll = 0.0f;

    // Add menu dropdown
    private boolean isAddMenuOpen = false;
    private float addMenuScroll = 0.0f;

    private EditBox nameEditBox;

    public RoadPresetStudioScreen() {
        super(Component.literal("Road Preset Studio"));
    }

    @Override
    protected void init() {
        super.init();
        int panelW = 195;
        int rx = this.width - panelW - 16;
        int y1 = 20;

        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
        this.nameEditBox = new EditBox(
                this.font,
                rx + 8,
                y1 + 22,
                panelW - 16,
                18,
                Component.translatable("roadbuilder.gui.preset_name")
        );
        this.nameEditBox.setMaxLength(32);
        this.nameEditBox.setValue(active != null ? active.getName() : "Road");
        this.nameEditBox.setResponder(text -> {
            ModularRoadPreset cur = PresetManager.getInstance().getActivePreset();
            if (cur != null && !text.isBlank()) {
                cur.setName(text.trim());
                PresetManager.getInstance().saveToFile();
            }
        });
        this.addRenderableWidget(this.nameEditBox);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 1. 3D Preview at lower depth Z = 150
        render3DPreview(guiGraphics, partialTick);

        // 2. 2D UI Panels at Z = 500 (Always on top of the 3D model)
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 500.0f);

        renderLeftPanel(guiGraphics, mouseX, mouseY);
        renderRightPanels(guiGraphics, mouseX, mouseY);
        renderBottomStackPanel(guiGraphics, mouseX, mouseY);

        if (isAddMenuOpen) {
            renderAddDropdown(guiGraphics, mouseX, mouseY);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        pose.popPose();
    }

    private void renderLeftPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int panelW = 175;
        int panelH = this.height - 100;
        int x = 16;
        int y = 20;

        // Panel background & header
        guiGraphics.fill(x, y, x + panelW, y + panelH, 0xEE1E2436);
        guiGraphics.fill(x, y, x + panelW, y + 2, 0xFF354466);

        // Tabs: [ Паки ] vs [ Пресеты ]
        int tabW = (panelW - 16) / 2;
        int tabH = 20;
        int tab1X = x + 6;
        int tab2X = x + 10 + tabW;
        int tabY = y + 8;

        // Tab 0: Паки
        boolean tab0Hover = mouseX >= tab1X && mouseX <= tab1X + tabW && mouseY >= tabY && mouseY <= tabY + tabH;
        int tab0Bg = (leftTab == 0) ? 0xFF2B4D7E : (tab0Hover ? 0xFF243A5E : 0xFF16253B);
        int tab0Border = (leftTab == 0) ? 0xFF66B3FF : 0xFF335577;
        guiGraphics.fill(tab1X, tabY, tab1X + tabW, tabY + tabH, tab0Border);
        guiGraphics.fill(tab1X + 1, tabY + 1, tab1X + tabW - 1, tabY + tabH - 1, tab0Bg);
        String t0Str = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.packs").getString();
        int t0W = this.font.width(t0Str);
        guiGraphics.drawString(this.font, t0Str, tab1X + (tabW - t0W) / 2, tabY + 6, leftTab == 0 ? 0xFFFFFFFF : 0xFFAAAAAA, false);

        // Tab 1: Пресеты
        boolean tab1Hover = mouseX >= tab2X && mouseX <= tab2X + tabW && mouseY >= tabY && mouseY <= tabY + tabH;
        int tab1Bg = (leftTab == 1) ? 0xFF2B4D7E : (tab1Hover ? 0xFF243A5E : 0xFF16253B);
        int tab1Border = (leftTab == 1) ? 0xFF66B3FF : 0xFF335577;
        guiGraphics.fill(tab2X, tabY, tab2X + tabW, tabY + tabH, tab1Border);
        guiGraphics.fill(tab2X + 1, tabY + 1, tab2X + tabW - 1, tabY + tabH - 1, tab1Bg);
        String t1Str = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.presets").getString();
        int t1W = this.font.width(t1Str);
        guiGraphics.drawString(this.font, t1Str, tab2X + (tabW - t1W) / 2, tabY + 6, leftTab == 1 ? 0xFFFFFFFF : 0xFFAAAAAA, false);

        int contentY = tabY + tabH + 8;
        int bottomReserve = (leftTab == 1) ? 36 : 8;
        int contentH = panelH - (contentY - y) - bottomReserve;

        // Render scrollable content
        guiGraphics.enableScissor(x + 4, contentY, x + panelW - 4, contentY + contentH);

        if (leftTab == 0) {
            // Road Packs list
            RoadPackRegistry.RoadPack activePack = RoadPackRegistry.getInstance().getActivePack();
            int curY = contentY - (int) leftPanelScroll;

            for (RoadPackRegistry.RoadPack pack : RoadPackRegistry.getInstance().getAllPacks().values()) {
                boolean isSelected = pack.getId().equals(activePack.getId());
                boolean hover = mouseX >= x + 8 && mouseX <= x + panelW - 8 && mouseY >= curY && mouseY <= curY + 20;

                int itemBg = isSelected ? 0xFF2E4E80 : (hover ? 0xFF253B61 : 0xFF14243B);
                int itemBorder = isSelected ? 0xFF66B3FF : (hover ? 0xFF4477AA : 0xFF223855);

                guiGraphics.fill(x + 8, curY, x + panelW - 8, curY + 20, itemBorder);
                guiGraphics.fill(x + 9, curY + 1, x + panelW - 9, curY + 19, itemBg);

                String name = pack.getDisplayName();
                if (this.font.width(name) > panelW - 28) {
                    name = this.font.plainSubstrByWidth(name, panelW - 36) + "...";
                }
                guiGraphics.drawString(this.font, name, x + 14, curY + 6, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
                curY += 24;
            }
        } else {
            // Saved Presets list
            ModularRoadPreset activePreset = PresetManager.getInstance().getActivePreset();
            int curY = contentY - (int) leftPanelScroll;

            for (ModularRoadPreset preset : PresetManager.getInstance().getAllPresets()) {
                boolean isSelected = preset.getId().equals(activePreset.getId());
                boolean hover = mouseX >= x + 8 && mouseX <= x + panelW - 8 && mouseY >= curY && mouseY <= curY + 20;

                int itemBg = isSelected ? 0xFF2E4E80 : (hover ? 0xFF253B61 : 0xFF14243B);
                int itemBorder = isSelected ? 0xFF66B3FF : (hover ? 0xFF4477AA : 0xFF223855);

                guiGraphics.fill(x + 8, curY, x + panelW - 8, curY + 20, itemBorder);
                guiGraphics.fill(x + 9, curY + 1, x + panelW - 9, curY + 19, itemBg);

                String name = preset.getDisplayName();
                if (this.font.width(name) > panelW - 28) {
                    name = this.font.plainSubstrByWidth(name, panelW - 36) + "...";
                }
                guiGraphics.drawString(this.font, name, x + 14, curY + 6, isSelected ? 0xFFFFFFFF : 0xFFCCCCCC, false);
                curY += 24;
            }
        }

        guiGraphics.disableScissor();

        // Bottom action buttons for Presets tab: [+ Нов] [Копия] [Удал]
        if (leftTab == 1) {
            int btnY = y + panelH - 28;
            int btnH2 = 20;
            int gap = 4;
            int bW = (panelW - 16 - gap * 2) / 3;

            // [+ Нов]
            int b1X = x + 8;
            boolean h1 = mouseX >= b1X && mouseX <= b1X + bW && mouseY >= btnY && mouseY <= btnY + btnH2;
            guiGraphics.fill(b1X, btnY, b1X + bW, btnY + btnH2, h1 ? 0xFF66B3FF : 0xFF2A75D3);
            guiGraphics.fill(b1X + 1, btnY + 1, b1X + bW - 1, btnY + btnH2 - 1, h1 ? 0xFF244A75 : 0xFF162D4A);
            String newStr = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.new").getString();
            int nw = this.font.width(newStr);
            guiGraphics.drawString(this.font, newStr, b1X + (bW - nw) / 2, btnY + 6, 0xFFFFFFFF, false);

            // [Копия]
            int b2X = b1X + bW + gap;
            boolean h2 = mouseX >= b2X && mouseX <= b2X + bW && mouseY >= btnY && mouseY <= btnY + btnH2;
            guiGraphics.fill(b2X, btnY, b2X + bW, btnY + btnH2, h2 ? 0xFF66B3FF : 0xFF2A75D3);
            guiGraphics.fill(b2X + 1, btnY + 1, b2X + bW - 1, btnY + btnH2 - 1, h2 ? 0xFF244A75 : 0xFF162D4A);
            String copyStr = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.clone").getString();
            int cw = this.font.width(copyStr);
            guiGraphics.drawString(this.font, copyStr, b2X + (bW - cw) / 2, btnY + 6, 0xFFFFFFFF, false);

            // [Удал]
            int b3X = b2X + bW + gap;
            boolean canDelete = PresetManager.getInstance().getAllPresets().size() > 1;
            boolean h3 = canDelete && mouseX >= b3X && mouseX <= b3X + bW && mouseY >= btnY && mouseY <= btnY + btnH2;
            int delBorder = canDelete ? (h3 ? 0xFFFF6666 : 0xFFCC3333) : 0xFF555555;
            int delBg = canDelete ? (h3 ? 0xFF551818 : 0xFF351212) : 0xFF222222;
            guiGraphics.fill(b3X, btnY, b3X + bW, btnY + btnH2, delBorder);
            guiGraphics.fill(b3X + 1, btnY + 1, b3X + bW - 1, btnY + btnH2 - 1, delBg);
            String delStr = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.delete").getString();
            int dw = this.font.width(delStr);
            guiGraphics.drawString(this.font, delStr, b3X + (bW - dw) / 2, btnY + 6, canDelete ? 0xFFFFAAAA : 0xFF666666, false);
        }
    }

    private void renderRightPanels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int panelW = 195;
        int x = this.width - panelW - 16;

        // Card 0: Имя пресета
        int y0 = 20;
        int h0 = 46;
        guiGraphics.fill(x, y0, x + panelW, y0 + h0, 0xEE1E2436);
        guiGraphics.fill(x, y0, x + panelW, y0 + 2, 0xFF354466);
        guiGraphics.drawString(this.font, Component.translatable("roadbuilder.gui.preset_name", "Название пресета").getString(), x + 10, y0 + 6, 0xFFFFFFFF, false);

        // Card 1: Настройки (сдвинуты на y0 + h0 + 8)
        int y1 = y0 + h0 + 8;
        int h1 = 126;
        guiGraphics.fill(x, y1, x + panelW, y1 + h1, 0xEE1E2436);
        guiGraphics.fill(x, y1, x + panelW, y1 + 2, 0xFF354466);
        guiGraphics.drawString(this.font, Component.translatable("roadbuilder.gui.settings_title").getString(), x + 10, y1 + 8, 0xFFFFFFFF, false);

        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
        int sy = y1 + 26;
        drawSettingButton(guiGraphics, x + 8, sy, panelW - 16, 20, Component.translatable("roadbuilder.gui.viaduct", active.getViaductMode().getLabel()).getString(), mouseX, mouseY);
        drawSettingButton(guiGraphics, x + 8, sy + 24, panelW - 16, 20, Component.translatable("roadbuilder.gui.tunnel", active.getTunnelMode().getLabel()).getString(), mouseX, mouseY);
        drawSettingButton(guiGraphics, x + 8, sy + 48, panelW - 16, 20, Component.translatable("roadbuilder.gui.slope", active.getSlopeMode().getLabel()).getString(), mouseX, mouseY);
        drawSettingButton(guiGraphics, x + 8, sy + 72, panelW - 16, 20, Component.translatable("roadbuilder.gui.embankment", active.getEmbankmentMode().getLabel()).getString(), mouseX, mouseY);

        // Card 2: Отображение в предпросмотре
        int y2 = y1 + h1 + 8;
        int h2 = 150;
        guiGraphics.fill(x, y2, x + panelW, y2 + h2, 0xEE1E2436);
        guiGraphics.fill(x, y2, x + panelW, y2 + 2, 0xFF354466);
        guiGraphics.drawString(this.font, Component.translatable("roadbuilder.gui.view_title").getString(), x + 10, y2 + 8, 0xFFFFFFFF, false);

        int vy = y2 + 26;
        drawToggleButton(guiGraphics, x + 8, vy, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_road").getString(), viewRoad, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 24, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_viaduct").getString(), viewViaduct, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 48, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_tunnel").getString(), viewTunnel, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 72, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_embankment").getString(), viewEmbankment, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 96, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_slope").getString(), viewSlope, mouseX, mouseY);
    }

    private void drawSettingButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String text, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int bg = hover ? 0xFF284872 : 0xFF14243B;
        int border = hover ? 0xFF66B3FF : 0xFF2A4A72;
        guiGraphics.fill(x, y, x + w, y + h, border);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        guiGraphics.drawString(this.font, text, x + 8, y + 6, hover ? 0xFFFFFFFF : 0xFFD8E4F0, false);
    }

    private void drawToggleButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String text, boolean active, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int bg = active ? (hover ? 0xFF2E639E : 0xFF1F4B7D) : (hover ? 0xFF243247 : 0xFF141F30);
        int border = active ? 0xFF66B3FF : (hover ? 0xFF445A77 : 0xFF24354A);
        guiGraphics.fill(x, y, x + w, y + h, border);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        String stateStr = active ? net.minecraft.network.chat.Component.translatable("roadbuilder.gui.state_on").getString() : net.minecraft.network.chat.Component.translatable("roadbuilder.gui.state_off").getString();
        guiGraphics.drawString(this.font, text, x + 8, y + 6, active ? 0xFFFFFFFF : 0xFFA0B0C0, false);
        int sw = this.font.width(stateStr);
        guiGraphics.drawString(this.font, stateStr, x + w - sw - 8, y + 6, active ? 0xFF66FF88 : 0xFF888888, false);
    }

    private void renderBottomStackPanel(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
        List<RoadStackElement> stack = active.getStack();

        int itemH = 26;
        int spacing = 6;

        // Calculate dynamic button widths based on element names & widths
        int[] elemWidths = new int[stack.size()];
        int totalStackW = 0;
        for (int i = 0; i < stack.size(); i++) {
            RoadStackElement elem = stack.get(i);
            String label = getElementShortLabel(elem);
            int tw = this.font.width(label);
            elemWidths[i] = Math.max(76, tw + 16);
            totalStackW += elemWidths[i];
            if (i > 0) totalStackW += spacing;
        }

        int availableW = this.width - 40;
        maxStackScroll = Math.max(0, totalStackW - availableW);
        stackScrollX = Math.max(0, Math.min(maxStackScroll, stackScrollX));

        int startX = (totalStackW <= availableW)
                ? (this.width - totalStackW) / 2
                : 20 - (int) stackScrollX;

        int stackY = this.height - 72;

        // Enable scissor clipping for scrollable horizontal stack
        guiGraphics.enableScissor(10, stackY - 4, this.width - 10, stackY + itemH + 6);

        int curX = startX;
        for (int i = 0; i < stack.size(); i++) {
            RoadStackElement elem = stack.get(i);
            int ew = elemWidths[i];
            int bx = curX;

            boolean isHover = mouseX >= bx && mouseX <= bx + ew && mouseY >= stackY && mouseY <= stackY + itemH;
            boolean isSel = (i == selectedElementIdx);
            boolean isDragged = (isDraggingElement && i == draggedElementIdx);

            int drawY = isDragged ? stackY - 2 : stackY;

            int border = isDragged ? 0xFFFFFF55 : (isSel ? 0xFFFFFFFF : (isHover ? 0xFF66B3FF : 0xFF2A75D3));
            int bg = isDragged ? 0xFF2B5282 : (isSel ? 0xFF1E4678 : (isHover ? 0xFF19365E : 0xEE162947));

            guiGraphics.fill(bx, drawY, bx + ew, drawY + itemH, border);
            guiGraphics.fill(bx + 1, drawY + 1, bx + ew - 1, drawY + itemH - 1, bg);

            String label = getElementShortLabel(elem);
            int textW = this.font.width(label);
            int textColor = isDragged ? 0xFFFFFF88 : (isSel ? 0xFFFFFFFF : 0xFF66B3FF);
            guiGraphics.drawString(this.font, label, bx + (ew - textW) / 2, drawY + 9, textColor, false);

            curX += ew + spacing;
        }

        guiGraphics.disableScissor();

        // Actions row below stack: [+ Добавить] [- Удалить] [ ◄ ] [ ► ] [ W- ] [ W+ ]
        int actionY = stackY + itemH + 8;
        int actionH = 22;

        int btnAddW = 74;
        int btnDelW = 68;
        int btnMoveW = 24;
        int btnWidthW = 28;
        int gap = 6;

        int totalActionsW = btnAddW + gap + btnDelW + gap + btnMoveW + gap + btnMoveW + gap + btnWidthW + gap + btnWidthW;
        int actionStartX = (this.width - totalActionsW) / 2;

        // 1. [+ Добавить]
        int ax = actionStartX;
        boolean plusHover = mouseX >= ax && mouseX <= ax + btnAddW && mouseY >= actionY && mouseY <= actionY + actionH;
        guiGraphics.fill(ax, actionY, ax + btnAddW, actionY + actionH, plusHover ? 0xFF66B3FF : 0xFF2A75D3);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnAddW - 1, actionY + actionH - 1, 0xFF14243B);
        String addStr = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.add_element").getString();
        int paw = this.font.width(addStr);
        guiGraphics.drawString(this.font, addStr, ax + (btnAddW - paw) / 2, actionY + 7, 0xFF66B3FF, false);

        // 2. [- Удалить]
        ax += btnAddW + gap;
        boolean canDelete = stack.size() > 1;
        boolean minusHover = canDelete && mouseX >= ax && mouseX <= ax + btnDelW && mouseY >= actionY && mouseY <= actionY + actionH;
        int minusBorder = canDelete ? (minusHover ? 0xFFFF7777 : 0xFFD32A2A) : 0xFF555555;
        guiGraphics.fill(ax, actionY, ax + btnDelW, actionY + actionH, minusBorder);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnDelW - 1, actionY + actionH - 1, 0xFF14243B);
        String remStr = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.remove_element").getString();
        int maw = this.font.width(remStr);
        guiGraphics.drawString(this.font, remStr, ax + (btnDelW - maw) / 2, actionY + 7, canDelete ? (minusHover ? 0xFFFFFFFF : 0xFFFF7777) : 0xFF777777, false);

        // 3. [ ◄ ] Move Left
        ax += btnDelW + gap;
        boolean canMoveLeft = selectedElementIdx > 0;
        boolean mlHover = canMoveLeft && mouseX >= ax && mouseX <= ax + btnMoveW && mouseY >= actionY && mouseY <= actionY + actionH;
        guiGraphics.fill(ax, actionY, ax + btnMoveW, actionY + actionH, canMoveLeft ? (mlHover ? 0xFF66B3FF : 0xFF2A75D3) : 0xFF444444);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnMoveW - 1, actionY + actionH - 1, 0xFF14243B);
        int mlw = this.font.width("◄");
        guiGraphics.drawString(this.font, "◄", ax + (btnMoveW - mlw) / 2, actionY + 7, canMoveLeft ? 0xFF66B3FF : 0xFF666666, false);

        // 4. [ ► ] Move Right
        ax += btnMoveW + gap;
        boolean canMoveRight = selectedElementIdx >= 0 && selectedElementIdx < stack.size() - 1;
        boolean mrHover = canMoveRight && mouseX >= ax && mouseX <= ax + btnMoveW && mouseY >= actionY && mouseY <= actionY + actionH;
        guiGraphics.fill(ax, actionY, ax + btnMoveW, actionY + actionH, canMoveRight ? (mrHover ? 0xFF66B3FF : 0xFF2A75D3) : 0xFF444444);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnMoveW - 1, actionY + actionH - 1, 0xFF14243B);
        int mrw = this.font.width("►");
        guiGraphics.drawString(this.font, "►", ax + (btnMoveW - mrw) / 2, actionY + 7, canMoveRight ? 0xFF66B3FF : 0xFF666666, false);

        // 5. [ W- ] Width - 1
        ax += btnMoveW + gap;
        boolean canDecW = selectedElementIdx >= 0 && stack.get(selectedElementIdx).getWidth() > 1;
        boolean dwHover = canDecW && mouseX >= ax && mouseX <= ax + btnWidthW && mouseY >= actionY && mouseY <= actionY + actionH;
        guiGraphics.fill(ax, actionY, ax + btnWidthW, actionY + actionH, canDecW ? (dwHover ? 0xFF66B3FF : 0xFF2A75D3) : 0xFF444444);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnWidthW - 1, actionY + actionH - 1, 0xFF14243B);
        int dww = this.font.width("W-");
        guiGraphics.drawString(this.font, "W-", ax + (btnWidthW - dww) / 2, actionY + 7, canDecW ? 0xFF66B3FF : 0xFF666666, false);

        // 6. [ W+ ] Width + 1
        ax += btnWidthW + gap;
        boolean canIncW = selectedElementIdx >= 0 && stack.get(selectedElementIdx).getWidth() < 16;
        boolean iwHover = canIncW && mouseX >= ax && mouseX <= ax + btnWidthW && mouseY >= actionY && mouseY <= actionY + actionH;
        guiGraphics.fill(ax, actionY, ax + btnWidthW, actionY + actionH, canIncW ? (iwHover ? 0xFF66B3FF : 0xFF2A75D3) : 0xFF444444);
        guiGraphics.fill(ax + 1, actionY + 1, ax + btnWidthW - 1, actionY + actionH - 1, 0xFF14243B);
        int iww = this.font.width("W+");
        guiGraphics.drawString(this.font, "W+", ax + (btnWidthW - iww) / 2, actionY + 7, canIncW ? 0xFF66B3FF : 0xFF666666, false);
    }

    private String getElementShortLabel(RoadStackElement elem) {
        String name = elem.getType().getDisplayName();
        if (name.contains("(")) {
            name = name.substring(0, name.indexOf("(")).trim();
        }
        return name + " (" + elem.getWidth() + ")";
    }

    private void renderAddDropdown(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        RoadElementType[] types = RoadElementType.values();
        int menuW = 160;
        int itemH = 20;
        int maxVisible = 7;
        int visibleCount = Math.min(types.length, maxVisible);
        int menuH = visibleCount * itemH + 6;

        int x = (this.width - menuW) / 2;
        int y = this.height - 72 - menuH - 8;

        guiGraphics.fill(x, y, x + menuW, y + menuH, 0xF0182438);
        guiGraphics.fill(x, y, x + menuW, y + 2, 0xFF3366AA);

        float maxScroll = Math.max(0, types.length * itemH - (visibleCount * itemH));
        addMenuScroll = Math.max(0, Math.min(maxScroll, addMenuScroll));

        guiGraphics.enableScissor(x + 2, y + 3, x + menuW - 2, y + menuH - 3);

        int curY = y + 3 - (int) addMenuScroll;
        for (RoadElementType t : types) {
            boolean h = mouseX >= x + 2 && mouseX <= x + menuW - 2 && mouseY >= curY && mouseY <= curY + itemH;
            if (h) {
                guiGraphics.fill(x + 2, curY, x + menuW - 2, curY + itemH, 0xFF284870);
            }
            guiGraphics.drawString(this.font, "+ " + t.getDisplayName(), x + 6, curY + 6, h ? 0xFFFFFFFF : 0xFF88CCFF, false);
            curY += itemH;
        }

        guiGraphics.disableScissor();
    }

    private void render3DPreview(GuiGraphics guiGraphics, float partialTick) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        List<RoadStackElement> stack = preset.getStack();
        if (stack.isEmpty()) return;

        RoadPackRegistry.RoadPack pack = RoadPackRegistry.getInstance().getActivePack();
        int totalWidth = preset.getTotalWidth();
        float halfW = totalWidth / 2.0f;
        int previewLength = 28; // Extended render distance without edge clipping

        RenderSystem.enableDepthTest();

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        poseStack.translate(this.width / 2.0f, this.height / 2.0f - 20, 150.0f);
        poseStack.scale(zoom, -zoom, zoom);

        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
        poseStack.mulPose(rotation);

        poseStack.translate(-halfW, 0, -previewLength / 2.0f);

        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();

        // 1. Render Road Stack elements
        if (viewRoad) {
            int currentX = 0;
            for (int i = 0; i < stack.size(); i++) {
                RoadStackElement elem = stack.get(i);
                int elemW = elem.getWidth();
                boolean isLeftSide = (currentX < halfW);
                drawStackComponent(poseStack, blockRenderer, pack, elem.getType(), currentX, elemW, previewLength, isLeftSide);
                currentX += elemW;
            }
        }

        // 2. Viaduct Pillars (without crossbeam!)
        if (viewViaduct) {
            drawSchematicViaduct(poseStack, blockRenderer, pack, totalWidth, previewLength);
        }

        // 3. Tunnel
        if (viewTunnel) {
            drawSchematicTunnel(poseStack, blockRenderer, pack, totalWidth, previewLength);
        }

        // 4. Embankment
        if (viewEmbankment) {
            drawSchematicEmbankment(poseStack, blockRenderer, pack, totalWidth, previewLength);
        }

        // 5. Slope
        if (viewSlope) {
            drawSchematicSlope(poseStack, blockRenderer, pack, totalWidth, previewLength);
        }

        poseStack.popPose();
        RenderSystem.disableDepthTest();
    }

    private void drawStackComponent(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                                    RoadPackRegistry.RoadPack pack, RoadElementType type,
                                    int startX, int width, int length, boolean isLeftSide) {

        if (type == RoadElementType.SIDEWALK) {
            drawSidewalk(poseStack, blockRenderer, startX, width, length);
            return;
        }

        if (type == RoadElementType.CURB) {
            drawCurb(poseStack, blockRenderer, startX, width, length, isLeftSide);
            return;
        }

        if (type == RoadElementType.LANE) {
            drawLane(poseStack, blockRenderer, startX, width, length);
            return;
        }

        if (type == RoadElementType.SHOULDER) {
            drawShoulder(poseStack, blockRenderer, startX, width, length, isLeftSide);
            return;
        }

        String schematicKey = switch (type) {
            case MARKING_DASHED_LONG -> "road_razmetka_prerivistaya_long";
            case MARKING_DASHED_SHORT -> "road_razmetka_prerivistaya_short";
            case MARKING_SOLID -> "road_razmetka_sploshnaya";
            case MARKING_DOUBLE_SOLID -> "road_razmetka_sploshnaya_double";
            case DIVIDER_GRASS -> "road_razdelitel_grass";
            default -> null;
        };

        LitematicParser.Schematic schematic = schematicKey != null ? pack.getSchematic(schematicKey) : null;

        if (schematic != null) {
            int elemW = schematic.getSizeZ();
            for (int z = 0; z < length; z++) {
                int sx = z % schematic.getSizeX();
                for (int dx = 0; dx < elemW; dx++) {
                    for (int y = 0; y < schematic.getSizeY(); y++) {
                        BlockState state = schematic.getBlock(sx, y, dx);
                        if (!state.isAir()) {
                            BlockState oriented = orientStairsForRoad(state);
                            renderBlock(poseStack, blockRenderer, oriented, startX + dx, y, z);
                            renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), startX + dx, -1, z);
                        }
                    }
                }
            }
        } else {
            drawLane(poseStack, blockRenderer, startX, width, length);
        }
    }

    private BlockState orientStairsForRoad(BlockState state) {
        if (state.getBlock() instanceof StairBlock) {
            Direction dir = state.getValue(StairBlock.FACING);
            if (dir == Direction.NORTH) {
                return state.setValue(StairBlock.FACING, Direction.WEST);
            } else if (dir == Direction.SOUTH) {
                return state.setValue(StairBlock.FACING, Direction.EAST);
            }
        }
        return state;
    }

    /**
     * Pure Sidewalk: paving blocks (Stone, Andesite, Polished Andesite, Stone Bricks)
     * Full width without embedded curb.
     */
    private void drawSidewalk(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                              int startX, int width, int length) {
        BlockState[] mix = new BlockState[]{
                Blocks.STONE.defaultBlockState(),
                Blocks.ANDESITE.defaultBlockState(),
                Blocks.POLISHED_ANDESITE.defaultBlockState(),
                Blocks.STONE_BRICKS.defaultBlockState()
        };
        BlockState foundation = Blocks.STONE.defaultBlockState();

        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                int x = startX + dx;
                int pseudoRand = Math.abs((x * 31 + z * 17)) % mix.length;
                renderBlock(poseStack, blockRenderer, mix[pseudoRand], x, 0, z);
                renderBlock(poseStack, blockRenderer, foundation, x, -1, z);
            }
        }
    }

    /**
     * Explicit Curb: 1 block wide stairs oriented towards the sidewalk.
     */
    private void drawCurb(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                          int startX, int width, int length, boolean isLeftSide) {
        Direction curbFacing = isLeftSide ? Direction.WEST : Direction.EAST;
        BlockState curb = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, curbFacing)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        BlockState foundation = Blocks.STONE.defaultBlockState();

        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                renderBlock(poseStack, blockRenderer, curb, startX + dx, 0, z);
                renderBlock(poseStack, blockRenderer, foundation, startX + dx, -1, z);
            }
        }
    }

    /**
     * Shoulder: 1 block stone adjacent to road + 2 blocks grass/dirt.
     */
    private void drawShoulder(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                              int startX, int width, int length, boolean isLeftSide) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        for (int z = 0; z < length; z++) {
            if (isLeftSide) {
                renderBlock(poseStack, blockRenderer, grass, startX, 0, z);
                renderBlock(poseStack, blockRenderer, dirt, startX, -1, z);
                renderBlock(poseStack, blockRenderer, grass, startX + 1, 0, z);
                renderBlock(poseStack, blockRenderer, dirt, startX + 1, -1, z);
                renderBlock(poseStack, blockRenderer, stone, startX + 2, 0, z);
                renderBlock(poseStack, blockRenderer, stone, startX + 2, -1, z);
            } else {
                renderBlock(poseStack, blockRenderer, stone, startX, 0, z);
                renderBlock(poseStack, blockRenderer, stone, startX, -1, z);
                renderBlock(poseStack, blockRenderer, grass, startX + 1, 0, z);
                renderBlock(poseStack, blockRenderer, dirt, startX + 1, -1, z);
                renderBlock(poseStack, blockRenderer, grass, startX + 2, 0, z);
                renderBlock(poseStack, blockRenderer, dirt, startX + 2, -1, z);
            }
        }
    }

    private void drawLane(PoseStack poseStack, BlockRenderDispatcher blockRenderer, int startX, int width, int length) {
        BlockState asphalt = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                renderBlock(poseStack, blockRenderer, asphalt, startX + dx, 0, z);
                renderBlock(poseStack, blockRenderer, stone, startX + dx, -1, z);
            }
        }
    }

    /**
     * Viaduct:
     * - Continuous V-shaped vault along the entire road length.
     * - Single, double, or triple monolithic pillar columns emerging under the deck.
     */
    private void drawSchematicViaduct(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                                      RoadPackRegistry.RoadPack pack, int totalWidth, int length) {
        LitematicParser.Schematic pillar = pack.getSchematic("road_opora");
        if (pillar == null) return;

        int pillarCrossW = 7;
        int pillarH = pillar.getSizeY();
        int pillarLen = 4;

        int pz = Math.max(0, (length - pillarLen) / 2);
        int attachY = -2 - (pillarH - 1);

        int[] pillarPositions = getPillarPositions(totalWidth);

        BlockState leftStair = pillar.getBlock(9, 6, 0);
        BlockState rightStair = pillar.getBlock(9, 6, 6);
        BlockState stone = pillar.getBlock(9, 5, 1);

        for (int px : pillarPositions) {
            // 1. Continuous V-shaped vault along the entire road length
            for (int lz = 0; lz < length; lz++) {
                renderBlock(poseStack, blockRenderer, orientStairsForRoad(leftStair), px, attachY + 6, lz);
                for (int dx = 1; dx <= 5; dx++) {
                    renderBlock(poseStack, blockRenderer, stone, px + dx, attachY + 6, lz);
                }
                renderBlock(poseStack, blockRenderer, orientStairsForRoad(rightStair), px + 6, attachY + 6, lz);

                for (int dx = 1; dx <= 5; dx++) {
                    renderBlock(poseStack, blockRenderer, stone, px + dx, attachY + 5, lz);
                }
            }

            // 2. Vertical pillar column (Y = 0..4) emerging from the vault at pz
            for (int z = 0; z < pillarCrossW; z++) {
                for (int y = 0; y <= 4; y++) {
                    for (int x = 9; x <= 12; x++) {
                        BlockState s = pillar.getBlock(x, y, z);
                        if (!s.isAir()) {
                            BlockState oriented = orientStairsForRoad(s);
                            renderBlock(poseStack, blockRenderer, oriented, px + z, attachY + y, pz + (x - 9));
                        }
                    }
                }
            }
        }
    }

    private int[] getPillarPositions(int totalWidth) {
        if (totalWidth < 18) {
            return new int[]{(totalWidth - 7) / 2};
        } else if (totalWidth < 28) {
            int px1 = Math.max(0, Math.min((int) Math.round(totalWidth * 0.25 - 3.5), totalWidth / 2 - 7));
            int px2 = Math.max(totalWidth / 2, Math.min((int) Math.round(totalWidth * 0.75 - 3.5), totalWidth - 7));
            return new int[]{px1, px2};
        } else {
            int px1 = Math.max(0, (int) Math.round(totalWidth * (1.0 / 6.0) - 3.5));
            int px2 = (totalWidth - 7) / 2;
            int px3 = Math.min(totalWidth - 7, (int) Math.round(totalWidth * (5.0 / 6.0) - 3.5));
            return new int[]{px1, px2, px3};
        }
    }

    /**
     * Tunnel: side walls start flush with road edges at road level (y = 0), ceiling spans between arch peaks.
     */
    private void drawSchematicTunnel(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                                     RoadPackRegistry.RoadPack pack, int totalWidth, int length) {
        LitematicParser.Schematic tunnelSide = pack.getSchematic("road_tunel_side");
        if (tunnelSide == null) return;

        int sideW = tunnelSide.getSizeZ();
        int sideH = tunnelSide.getSizeY();

        for (int z = 0; z < length; z++) {
            int sx = z % tunnelSide.getSizeX();

            // Left wall
            for (int dz = 0; dz < sideW; dz++) {
                for (int y = 0; y < sideH; y++) {
                    BlockState s = tunnelSide.getBlock(sx, y, dz);
                    if (!s.isAir()) {
                        renderBlock(poseStack, blockRenderer, s, -1 + dz, y, z);
                    }
                }
            }

            // Right wall
            for (int dz = 0; dz < sideW; dz++) {
                for (int y = 0; y < sideH; y++) {
                    BlockState s = tunnelSide.getBlock(sx, y, dz);
                    if (!s.isAir()) {
                        renderBlock(poseStack, blockRenderer, s, totalWidth - dz, y, z);
                    }
                }
            }

            // Ceiling: spans between arch peaks at y = sideH - 1
            int leftPeakX = -1 + 5;
            int rightPeakX = totalWidth - 5;
            for (int x = leftPeakX; x <= rightPeakX; x++) {
                renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), x, sideH - 1, z);
            }
        }
    }

    /**
     * Embankment: attached flush at y = 0, descending downwards and outwards away from road.
     */
    private void drawSchematicEmbankment(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                                         RoadPackRegistry.RoadPack pack, int totalWidth, int length) {
        LitematicParser.Schematic nasip = pack.getSchematic("road_nasip");
        if (nasip == null) return;

        int nasipW = nasip.getSizeZ();
        int nasipH = nasip.getSizeY();

        for (int z = 0; z < length; z++) {
            int sx = z % nasip.getSizeX();

            // Under the road edge foundation (dz=0)
            for (int y = 0; y < nasipH; y++) {
                BlockState s0 = nasip.getBlock(sx, y, 0);
                if (!s0.isAir()) {
                    renderBlock(poseStack, blockRenderer, s0, 0, y - (nasipH - 1), z);
                    renderBlock(poseStack, blockRenderer, s0, totalWidth - 1, y - (nasipH - 1), z);
                }
            }

            // Right side: dz goes 1 .. nasipW - 1 (outward from road edge)
            for (int dz = 1; dz < nasipW; dz++) {
                int px = totalWidth + (dz - 1);
                for (int y = 0; y < nasipH; y++) {
                    BlockState state = nasip.getBlock(sx, y, dz);
                    if (!state.isAir()) {
                        renderBlock(poseStack, blockRenderer, state, px, y - (nasipH - 1), z);
                    }
                }
            }
            // Left side: dz goes 1 .. nasipW - 1 (outward from road edge)
            for (int dz = 1; dz < nasipW; dz++) {
                int px = -dz;
                for (int y = 0; y < nasipH; y++) {
                    BlockState state = nasip.getBlock(sx, y, dz);
                    if (!state.isAir()) {
                        renderBlock(poseStack, blockRenderer, state, px, y - (nasipH - 1), z);
                    }
                }
            }
        }
    }

    /**
     * Slope: attached flush at road level, ascending upwards and outwards away from road.
     */
    private void drawSchematicSlope(PoseStack poseStack, BlockRenderDispatcher blockRenderer,
                                    RoadPackRegistry.RoadPack pack, int totalWidth, int length) {
        LitematicParser.Schematic otkos = pack.getSchematic("road_otkos");
        if (otkos == null) return;

        int otkosW = otkos.getSizeZ();
        int otkosH = otkos.getSizeY();

        for (int z = 0; z < length; z++) {
            int sx = z % otkos.getSizeX();

            // Right side
            for (int dz = 0; dz < otkosW; dz++) {
                int schemZ = (otkosW - 1) - dz;
                for (int y = 0; y < otkosH; y++) {
                    BlockState state = otkos.getBlock(sx, y, schemZ);
                    if (!state.isAir()) {
                        renderBlock(poseStack, blockRenderer, state, totalWidth + dz, y + 1, z);
                    }
                }
                renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), totalWidth + dz, 0, z);
                renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), totalWidth + dz, -1, z);
            }

            // Left side
            for (int dz = 0; dz < otkosW; dz++) {
                int schemZ = (otkosW - 1) - dz;
                for (int y = 0; y < otkosH; y++) {
                    BlockState state = otkos.getBlock(sx, y, schemZ);
                    if (!state.isAir()) {
                        renderBlock(poseStack, blockRenderer, state, -1 - dz, y + 1, z);
                    }
                }
                renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), -1 - dz, 0, z);
                renderBlock(poseStack, blockRenderer, Blocks.STONE.defaultBlockState(), -1 - dz, -1, z);
            }
        }
    }

    private void renderBlock(PoseStack poseStack, BlockRenderDispatcher blockRenderer, BlockState state, int x, int y, int z) {
        poseStack.pushPose();
        poseStack.translate(x, y, z);

        blockRenderer.renderSingleBlock(
                state,
                poseStack,
                Minecraft.getInstance().renderBuffers().bufferSource(),
                0xF000F0,
                OverlayTexture.NO_OVERLAY
        );
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();

        poseStack.popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 1. Add Dropdown handling
        if (isAddMenuOpen) {
            RoadElementType[] types = RoadElementType.values();
            int menuW = 160;
            int itemH = 20;
            int maxVisible = 7;
            int visibleCount = Math.min(types.length, maxVisible);
            int menuH = visibleCount * itemH + 6;

            int x = (this.width - menuW) / 2;
            int y = this.height - 72 - menuH - 8;

            if (mouseX >= x && mouseX <= x + menuW && mouseY >= y && mouseY <= y + menuH) {
                int relativeY = (int) (mouseY - (y + 3) + addMenuScroll);
                int clickedIdx = relativeY / itemH;
                if (clickedIdx >= 0 && clickedIdx < types.length) {
                    ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
                    active.getStack().add(new RoadStackElement(types[clickedIdx]));
                    selectedElementIdx = active.getStack().size() - 1;
                    PresetManager.getInstance().saveToFile();
                    isAddMenuOpen = false;
                    return true;
                }
            }
            isAddMenuOpen = false;
            return true;
        }

        // 2. Left Panel handling (Tabs & lists)
        int panelW = 175;
        int panelH = this.height - 100;
        int lx = 16;
        int ly = 20;

        if (mouseX >= lx && mouseX <= lx + panelW && mouseY >= ly && mouseY <= ly + panelH) {
            int tabW = (panelW - 16) / 2;
            int tabH = 20;
            int tab1X = lx + 6;
            int tab2X = lx + 10 + tabW;
            int tabY = ly + 8;

            // Tab 0: [ Паки ]
            if (mouseX >= tab1X && mouseX <= tab1X + tabW && mouseY >= tabY && mouseY <= tabY + tabH) {
                leftTab = 0;
                leftPanelScroll = 0;
                return true;
            }
            // Tab 1: [ Пресеты ]
            if (mouseX >= tab2X && mouseX <= tab2X + tabW && mouseY >= tabY && mouseY <= tabY + tabH) {
                leftTab = 1;
                leftPanelScroll = 0;
                return true;
            }

            int contentY = tabY + tabH + 8;

            if (leftTab == 0) {
                // Click on pack
                int curY = contentY - (int) leftPanelScroll;
                for (RoadPackRegistry.RoadPack pack : RoadPackRegistry.getInstance().getAllPacks().values()) {
                    if (mouseX >= lx + 8 && mouseX <= lx + panelW - 8 && mouseY >= curY && mouseY <= curY + 20) {
                        RoadPackRegistry.getInstance().setActivePackId(pack.getId());
                        return true;
                    }
                    curY += 24;
                }
            } else {
                // Click on preset
                int curY = contentY - (int) leftPanelScroll;
                for (ModularRoadPreset preset : PresetManager.getInstance().getAllPresets()) {
                    if (mouseX >= lx + 8 && mouseX <= lx + panelW - 8 && mouseY >= curY && mouseY <= curY + 20) {
                        PresetManager.getInstance().setActivePresetId(preset.getId());
                        ClientGraphCache.getInstance().markPreviewDirty();
                        selectedElementIdx = -1;
                        if (nameEditBox != null) nameEditBox.setValue(preset.getName());
                        return true;
                    }
                    curY += 24;
                }

                // Preset actions: [+ Нов] [Копия] [Удал]
                int btnY = ly + panelH - 28;
                int btnH2 = 20;
                int gap = 4;
                int bW = (panelW - 16 - gap * 2) / 3;

                // [+ Нов]
                int b1X = lx + 8;
                if (mouseX >= b1X && mouseX <= b1X + bW && mouseY >= btnY && mouseY <= btnY + btnH2) {
                    ModularRoadPreset np = PresetManager.getInstance().createNewPreset();
                    selectedElementIdx = -1;
                    if (nameEditBox != null) nameEditBox.setValue(np.getName());
                    return true;
                }

                // [Копия]
                int b2X = b1X + bW + gap;
                if (mouseX >= b2X && mouseX <= b2X + bW && mouseY >= btnY && mouseY <= btnY + btnH2) {
                    ModularRoadPreset activeP = PresetManager.getInstance().getActivePreset();
                    ModularRoadPreset dup = PresetManager.getInstance().duplicatePreset(activeP.getId());
                    selectedElementIdx = -1;
                    if (nameEditBox != null && dup != null) nameEditBox.setValue(dup.getName());
                    return true;
                }

                // [Удал]
                int b3X = b2X + bW + gap;
                if (mouseX >= b3X && mouseX <= b3X + bW && mouseY >= btnY && mouseY <= btnY + btnH2) {
                    ModularRoadPreset activeP = PresetManager.getInstance().getActivePreset();
                    PresetManager.getInstance().deletePreset(activeP.getId());
                    selectedElementIdx = -1;
                    if (nameEditBox != null) nameEditBox.setValue(PresetManager.getInstance().getActivePreset().getName());
                    return true;
                }
            }
            return true;
        }

        // 3. Right Panel handling
        int rPanelW = 195;
        int rx = this.width - rPanelW - 16;
        int y0 = 20;
        int y1 = y0 + 46 + 8;
        int sy = y1 + 26;

        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();

        if (this.nameEditBox != null && this.nameEditBox.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(this.nameEditBox);
            return true;
        }

        if (mouseX >= rx + 8 && mouseX <= rx + rPanelW - 8) {
            // Settings card
            if (mouseY >= sy && mouseY <= sy + 20) {
                active.setViaductMode(cycleMode(active.getViaductMode()));
                PresetManager.getInstance().saveToFile();
                return true;
            } else if (mouseY >= sy + 24 && mouseY <= sy + 44) {
                active.setTunnelMode(cycleMode(active.getTunnelMode()));
                PresetManager.getInstance().saveToFile();
                return true;
            } else if (mouseY >= sy + 48 && mouseY <= sy + 68) {
                active.setSlopeMode(cycleToggle(active.getSlopeMode()));
                PresetManager.getInstance().saveToFile();
                return true;
            } else if (mouseY >= sy + 72 && mouseY <= sy + 92) {
                active.setEmbankmentMode(cycleToggle(active.getEmbankmentMode()));
                PresetManager.getInstance().saveToFile();
                return true;
            }

            // View toggles card
            int y2 = y1 + 126 + 8;
            int vy = y2 + 26;
            if (mouseY >= vy && mouseY <= vy + 20) { viewRoad = !viewRoad; return true; }
            if (mouseY >= vy + 24 && mouseY <= vy + 44) { viewViaduct = !viewViaduct; return true; }
            if (mouseY >= vy + 48 && mouseY <= vy + 68) { viewTunnel = !viewTunnel; return true; }
            if (mouseY >= vy + 72 && mouseY <= vy + 92) { viewEmbankment = !viewEmbankment; return true; }
            if (mouseY >= vy + 96 && mouseY <= vy + 116) { viewSlope = !viewSlope; return true; }
        }

        // 4. Bottom Actions row: [+ Добавить] [- Удалить] [ ◄ ] [ ► ] [ W- ] [ W+ ]
        int stackY = this.height - 72;
        int actionY = stackY + 26 + 8;
        int actionH = 22;

        int btnAddW = 74;
        int btnDelW = 68;
        int btnMoveW = 24;
        int btnWidthW = 28;
        int gap = 6;
        int totalActionsW = btnAddW + gap + btnDelW + gap + btnMoveW + gap + btnMoveW + gap + btnWidthW + gap + btnWidthW;
        int actionStartX = (this.width - totalActionsW) / 2;

        List<RoadStackElement> stack = active.getStack();

        // [+ Добавить]
        int ax = actionStartX;
        if (mouseX >= ax && mouseX <= ax + btnAddW && mouseY >= actionY && mouseY <= actionY + actionH) {
            isAddMenuOpen = true;
            return true;
        }

        // [- Удалить]
        ax += btnAddW + gap;
        if (mouseX >= ax && mouseX <= ax + btnDelW && mouseY >= actionY && mouseY <= actionY + actionH) {
            if (stack.size() > 1) {
                int toRemove = (selectedElementIdx >= 0 && selectedElementIdx < stack.size())
                        ? selectedElementIdx
                        : stack.size() - 1;
                stack.remove(toRemove);
                if (selectedElementIdx >= stack.size()) {
                    selectedElementIdx = stack.size() - 1;
                }
                PresetManager.getInstance().saveToFile();
                return true;
            }
        }

        // [ ◄ ] Move Left
        ax += btnDelW + gap;
        if (mouseX >= ax && mouseX <= ax + btnMoveW && mouseY >= actionY && mouseY <= actionY + actionH) {
            if (selectedElementIdx > 0) {
                RoadStackElement item = stack.remove(selectedElementIdx);
                stack.add(selectedElementIdx - 1, item);
                selectedElementIdx--;
                PresetManager.getInstance().saveToFile();
                return true;
            }
        }

        // [ ► ] Move Right
        ax += btnMoveW + gap;
        if (mouseX >= ax && mouseX <= ax + btnMoveW && mouseY >= actionY && mouseY <= actionY + actionH) {
            if (selectedElementIdx >= 0 && selectedElementIdx < stack.size() - 1) {
                RoadStackElement item = stack.remove(selectedElementIdx);
                stack.add(selectedElementIdx + 1, item);
                selectedElementIdx++;
                PresetManager.getInstance().saveToFile();
                return true;
            }
        }

        // [ W- ] Width - 1
        ax += btnMoveW + gap;
        if (mouseX >= ax && mouseX <= ax + btnWidthW && mouseY >= actionY && mouseY <= actionY + actionH) {
            if (selectedElementIdx >= 0 && selectedElementIdx < stack.size()) {
                RoadStackElement el = stack.get(selectedElementIdx);
                if (el.getWidth() > 1) {
                    el.setWidth(el.getWidth() - 1);
                    PresetManager.getInstance().saveToFile();
                    return true;
                }
            }
        }

        // [ W+ ] Width + 1
        ax += btnWidthW + gap;
        if (mouseX >= ax && mouseX <= ax + btnWidthW && mouseY >= actionY && mouseY <= actionY + actionH) {
            if (selectedElementIdx >= 0 && selectedElementIdx < stack.size()) {
                RoadStackElement el = stack.get(selectedElementIdx);
                if (el.getWidth() < 16) {
                    el.setWidth(el.getWidth() + 1);
                    PresetManager.getInstance().saveToFile();
                    return true;
                }
            }
        }

        // 5. Bottom Stack Elements Click & Drag start
        int itemH = 26;
        int spacing = 6;
        int[] elemWidths = new int[stack.size()];
        int totalStackW = 0;
        for (int i = 0; i < stack.size(); i++) {
            RoadStackElement elem = stack.get(i);
            String label = getElementShortLabel(elem);
            elemWidths[i] = Math.max(76, this.font.width(label) + 16);
            totalStackW += elemWidths[i];
            if (i > 0) totalStackW += spacing;
        }

        int availableW = this.width - 40;
        int curStackX = (totalStackW <= availableW)
                ? (this.width - totalStackW) / 2
                : 20 - (int) stackScrollX;

        for (int i = 0; i < stack.size(); i++) {
            int ew = elemWidths[i];
            if (mouseX >= curStackX && mouseX <= curStackX + ew && mouseY >= stackY && mouseY <= stackY + itemH) {
                selectedElementIdx = (selectedElementIdx == i) ? -1 : i;
                if (button == 0 && selectedElementIdx == i) {
                    isDraggingElement = true;
                    draggedElementIdx = i;
                }
                return true;
            }
            curStackX += ew + spacing;
        }

        // 6. Viewport 3D drag
        if (button == 0) {
            isDragging3D = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private ModularRoadPreset.Mode cycleMode(ModularRoadPreset.Mode m) {
        return switch (m) {
            case AUTO -> ModularRoadPreset.Mode.ALWAYS;
            case ALWAYS -> ModularRoadPreset.Mode.OFF;
            case OFF -> ModularRoadPreset.Mode.AUTO;
        };
    }

    private ModularRoadPreset.ToggleMode cycleToggle(ModularRoadPreset.ToggleMode m) {
        return m == ModularRoadPreset.ToggleMode.AUTO ? ModularRoadPreset.ToggleMode.OFF : ModularRoadPreset.ToggleMode.AUTO;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDragging3D = false;
            isDraggingElement = false;
            draggedElementIdx = -1;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Element Drag & Drop reordering in bottom stack
        if (isDraggingElement && draggedElementIdx >= 0) {
            ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
            List<RoadStackElement> stack = active.getStack();
            if (draggedElementIdx < stack.size()) {
                int spacing = 6;
                int[] elemWidths = new int[stack.size()];
                int totalStackW = 0;
                for (int i = 0; i < stack.size(); i++) {
                    elemWidths[i] = Math.max(76, this.font.width(getElementShortLabel(stack.get(i))) + 16);
                    totalStackW += elemWidths[i];
                    if (i > 0) totalStackW += spacing;
                }

                int availableW = this.width - 40;
                int curStackX = (totalStackW <= availableW)
                        ? (this.width - totalStackW) / 2
                        : 20 - (int) stackScrollX;

                int[] centers = new int[stack.size()];
                int cx = curStackX;
                for (int i = 0; i < stack.size(); i++) {
                    centers[i] = cx + elemWidths[i] / 2;
                    cx += elemWidths[i] + spacing;
                }

                // Check swap with left neighbor
                if (draggedElementIdx > 0 && mouseX < centers[draggedElementIdx - 1]) {
                    RoadStackElement item = stack.remove(draggedElementIdx);
                    stack.add(draggedElementIdx - 1, item);
                    draggedElementIdx--;
                    selectedElementIdx = draggedElementIdx;
                    PresetManager.getInstance().saveToFile();
                    return true;
                }
                // Check swap with right neighbor
                if (draggedElementIdx < stack.size() - 1 && mouseX > centers[draggedElementIdx + 1]) {
                    RoadStackElement item = stack.remove(draggedElementIdx);
                    stack.add(draggedElementIdx + 1, item);
                    draggedElementIdx++;
                    selectedElementIdx = draggedElementIdx;
                    PresetManager.getInstance().saveToFile();
                    return true;
                }
            }
            return true;
        }

        // 3D rotation
        if (isDragging3D) {
            yaw += (float) dragX * 0.8f;
            pitch = Math.max(-80.0f, Math.min(80.0f, pitch + (float) dragY * 0.8f));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 1. Dropdown scrolling
        if (isAddMenuOpen) {
            RoadElementType[] types = RoadElementType.values();
            int itemH = 20;
            float maxScroll = Math.max(0, types.length * itemH - (7 * itemH));
            addMenuScroll = Math.max(0, Math.min(maxScroll, addMenuScroll - (float) scrollY * 18));
            return true;
        }

        // 2. Left panel scrolling
        int panelW = 175;
        int panelH = this.height - 100;
        if (mouseX >= 16 && mouseX <= 16 + panelW && mouseY >= 20 && mouseY <= 20 + panelH) {
            int count = (leftTab == 0)
                    ? RoadPackRegistry.getInstance().getAllPacks().size()
                    : PresetManager.getInstance().getAllPresets().size();
            float maxScroll = Math.max(0, count * 24 - (panelH - 70));
            leftPanelScroll = Math.max(0, Math.min(maxScroll, leftPanelScroll - (float) scrollY * 20));
            return true;
        }

        // 3. Bottom stack horizontal scrolling
        if (mouseY >= this.height - 76) {
            stackScrollX = Math.max(0, Math.min(maxStackScroll, stackScrollX - (float) scrollY * 28));
            return true;
        }

        // 4. Default: Zoom 3D preview
        zoom = Math.max(8.0f, Math.min(60.0f, zoom + (float) scrollY * 2.5f));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.nameEditBox != null && this.nameEditBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.nameEditBox.setFocused(false);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (selectedElementIdx >= 0) {
            ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
            List<RoadStackElement> stack = active.getStack();
            if (keyCode == GLFW.GLFW_KEY_LEFT && selectedElementIdx > 0) {
                RoadStackElement item = stack.remove(selectedElementIdx);
                stack.add(selectedElementIdx - 1, item);
                selectedElementIdx--;
                PresetManager.getInstance().saveToFile();
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_RIGHT && selectedElementIdx < stack.size() - 1) {
                RoadStackElement item = stack.remove(selectedElementIdx);
                stack.add(selectedElementIdx + 1, item);
                selectedElementIdx++;
                PresetManager.getInstance().saveToFile();
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (stack.size() > 1) {
                    stack.remove(selectedElementIdx);
                    if (selectedElementIdx >= stack.size()) {
                        selectedElementIdx = stack.size() - 1;
                    }
                    PresetManager.getInstance().saveToFile();
                    return true;
                }
            }
        }

        if (keyCode == GLFW.GLFW_KEY_CAPS_LOCK || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        ClientGraphCache.getInstance().markPreviewDirty();
        super.onClose();
    }
}
