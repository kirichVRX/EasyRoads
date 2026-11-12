package com.roadbuilder.mod.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.client.studio.StudioPreviewRenderer;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Road Preset Studio GUI screen:
 * - Metaform-inspired 3D studio preview (backdrop gradient, ground grid, contact shadow, baked mesh)
 *   rendered behind 2D UI panels (Z = 800).
 * - Extended 3D preview length (28 blocks) with orbit, pan, cursor zoom, and double-click fit.
 * - Left panel with tabs: [ Настройка ] and [ Пресеты ] (with create, duplicate, delete actions).
 * - Right panel with wide buttons avoiding Russian text overflow.
 * - Bottom stack with mouse drag-and-drop reordering, horizontal scrolling, and width adjustment.
 * - Mouse wheel scrolling for menus, left panel, and bottom stack.
 */
public class RoadPresetStudioScreen extends Screen {

    private final StudioPreviewRenderer studioPreview = new StudioPreviewRenderer();
    private boolean isDraggingOrbit = false;
    private boolean isDraggingPan = false;
    private long lastViewportClickMs = 0L;
    private double lastViewportClickX = 0.0;
    private double lastViewportClickY = 0.0;

    private boolean viewRoad = true;
    private boolean viewViaduct = false;
    private boolean viewTunnel = false;
    private boolean viewEmbankment = false;
    private boolean viewSlope = false;

    // Tabs: 0 = Road Settings, 1 = Presets
    private int leftTab = 0;
    private float leftPanelScroll = 0.0f;
    private int leftSettingsContentHeight = 0;
    private static final boolean[] SECTION_COLLAPSED = new boolean[9];

    // Bottom stack controls
    private int selectedElementIdx = -1;
    private int rightLayerTab = 0; // 0 = Барьеры, 1 = Зелень
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
        // Backdrop gradient is rendered by StudioPreviewRenderer before the 3D scene,
        // so Screen.super.render() at Z=800 does not dim the 3D preview or UI panels.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Open viewport region between Left Panel (x=16..216), Right Panel (x=width-211..width-16),
        // and Bottom Stack (y=height-76..height).
        int vpX = 220;
        int vpY = 16;
        int vpW = Math.max(100, this.width - 220 - 215);
        int vpH = Math.max(100, this.height - 96);
        studioPreview.setViewport(vpX, vpY, vpW, vpH);

        // 1. Studio backdrop gradient, ground grid, contact shadow, and baked 3D preview (Z = 0..650)
        studioPreview.render(
                guiGraphics,
                this.width,
                this.height,
                PresetManager.getInstance().getActivePreset(),
                RoadPackRegistry.getInstance().getActivePack(),
                viewRoad,
                viewViaduct,
                viewTunnel,
                viewEmbankment,
                viewSlope
        );

        // 2. 2D UI Panels at Z = 800 (Always on top of the 3D model)
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(0, 0, 800.0f);

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
        int panelW = 200;
        int panelH = this.height - 100;
        int x = 16;
        int y = 20;

        // Panel background & header
        guiGraphics.fill(x, y, x + panelW, y + panelH, 0xEE1E2436);
        guiGraphics.fill(x, y, x + panelW, y + 2, 0xFF354466);

        // Tabs: [ Настройки ] vs [ Пресеты ]
        int tabW = (panelW - 16) / 2;
        int tabH = 20;
        int tab1X = x + 6;
        int tab2X = x + 10 + tabW;
        int tabY = y + 8;

        // Tab 0: Настройка дороги
        boolean tab0Hover = mouseX >= tab1X && mouseX <= tab1X + tabW && mouseY >= tabY && mouseY <= tabY + tabH;
        int tab0Bg = (leftTab == 0) ? 0xFF2B4D7E : (tab0Hover ? 0xFF243A5E : 0xFF16253B);
        int tab0Border = (leftTab == 0) ? 0xFF66B3FF : 0xFF335577;
        guiGraphics.fill(tab1X, tabY, tab1X + tabW, tabY + tabH, tab0Border);
        guiGraphics.fill(tab1X + 1, tabY + 1, tab1X + tabW - 1, tabY + tabH - 1, tab0Bg);
        String t0Str = net.minecraft.network.chat.Component.translatable("roadbuilder.gui.road_settings").getString();
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
            // Detailed Road Configuration List with collapsible sections
            ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
            int startCurY = contentY - (int) leftPanelScroll;
            int curY = startCurY;
            int btnW = panelW - 16;
            int btnH = 20;
            boolean inScrollArea = mouseX >= x + 4 && mouseX <= x + panelW - 4 && mouseY >= contentY && mouseY <= contentY + contentH;

            // 1. ПЕРЕКРЕСТКИ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ПЕРЕКРЁСТКИ", SECTION_COLLAPSED[0], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[0]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Стиль: " + active.getJunctionStyle().getLabel(), mouseX, mouseY);
                curY += 24;
            }

            // 2. ФОНАРИ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ОСВЕЩЕНИЕ (ФОНАРИ)", SECTION_COLLAPSED[1], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[1]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Фонари: " + active.getLampStyle().getLabel(), mouseX, mouseY);
                curY += 24;
                if (active.getLampStyle() != ModularRoadPreset.LampStyle.OFF) {
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Шаг фонарей: " + active.getLampSpacing() + "м", mouseX, mouseY);
                    curY += 24;
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH,
                            "Тротуар: " + (active.isSidewalkDoubleLamps() ? "Двусторонние" : "Односторонние"), mouseX, mouseY);
                    curY += 24;
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Схема: " + active.getLampPlacement().getLabel(), mouseX, mouseY);
                    curY += 24;
                }
            }

            // 3. ОГРАЖДЕНИЯ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ОГРАЖДЕНИЯ", SECTION_COLLAPSED[2], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[2]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Все барьеры: " + active.getGuardrailSummaryLabel(), mouseX, mouseY);
                curY += 24;
                drawToggleButton(guiGraphics, x + 8, curY, btnW, btnH, "Шумовой барьер", active.isNoiseWallEnabled(), mouseX, mouseY);
                curY += 24;
            }

            // 4. ОЗЕЛЕНЕНИЕ (2-й слой: деревья и кусты)
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ОЗЕЛЕНЕНИЕ", SECTION_COLLAPSED[3], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[3]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Все: " + active.getGreenerySummaryLabel(), mouseX, mouseY);
                curY += 24;
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Шаг деревьев: " + active.getTreeSpacing() + "м", mouseX, mouseY);
                curY += 24;
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Шаг кустов: " + active.getBushSpacing() + "м", mouseX, mouseY);
                curY += 24;
            }

            // 5. НАСЫПЬ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "НАСЫПЬ", SECTION_COLLAPSED[4], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[4]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Режим: " + active.getEmbankmentMode().getLabel(), mouseX, mouseY);
                curY += 24;
                if (active.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF) {
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Форма: " + active.getEmbankmentShape().getLabel(), mouseX, mouseY);
                    curY += 24;
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Материал: " + active.getEmbankmentMaterial().getLabel(), mouseX, mouseY);
                    curY += 24;
                }
            }

            // 6. ОТКОСЫ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ОТКОСЫ ВЫЕМОК", SECTION_COLLAPSED[5], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[5]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Режим: " + active.getSlopeMode().getLabel(), mouseX, mouseY);
                curY += 24;
                if (active.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF) {
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Форма: " + active.getSlopeStyle().getLabel(), mouseX, mouseY);
                    curY += 24;
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Материал: " + active.getSlopeMaterial().getLabel(), mouseX, mouseY);
                    curY += 24;
                }
            }

            // 7. ТОННЕЛИ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ТОННЕЛИ", SECTION_COLLAPSED[6], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[6]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Режим: " + active.getTunnelMode().getLabel(), mouseX, mouseY);
                curY += 24;
                if (active.getTunnelMode() != ModularRoadPreset.Mode.OFF) {
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Форма свода: " + active.getTunnelShape().getLabel(), mouseX, mouseY);
                    curY += 24;
                }
            }

            // 8. ЭСТАКАДЫ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "ЭСТАКАДЫ (МОСТЫ)", SECTION_COLLAPSED[7], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[7]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Режим: " + active.getViaductMode().getLabel(), mouseX, mouseY);
                curY += 24;
                if (active.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                    drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Опоры: " + active.getPillarStyle().getLabel(), mouseX, mouseY);
                    curY += 24;
                }
            }

            // 9. БОРДЮРЫ И ТРОТУАРЫ
            renderCategoryHeader(guiGraphics, x + 8, curY, btnW, "БОРДЮРЫ И ТРОТУАРЫ", SECTION_COLLAPSED[8], inScrollArea, mouseX, mouseY);
            curY += 18;
            if (!SECTION_COLLAPSED[8]) {
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Бордюры: " + active.getCurbPalette().getLabel(), mouseX, mouseY);
                curY += 24;
                drawSettingButton(guiGraphics, x + 8, curY, btnW, btnH, "Тротуары: " + active.getSidewalkPalette().getLabel(), mouseX, mouseY);
                curY += 24;
            }

            this.leftSettingsContentHeight = curY - startCurY;
            float maxScroll = Math.max(0, this.leftSettingsContentHeight - contentH);
            if (leftPanelScroll > maxScroll) {
                leftPanelScroll = maxScroll;
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

    private void renderCategoryHeader(GuiGraphics guiGraphics, int x, int y, int w, String title, boolean collapsed, boolean inScrollArea, int mouseX, int mouseY) {
        boolean hover = inScrollArea && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 16;
        if (hover) {
            guiGraphics.fill(x, y, x + w, y + 15, 0xFF1B304F);
        }
        int textColor = hover ? 0xFF99D1FF : 0xFF66B3FF;
        guiGraphics.drawString(this.font, title, x + 3, y + 4, textColor, false);

        // Crisp 5px chevron on the right: '>' when collapsed, 'v' when expanded
        int cx = x + w - 9;
        int cy = y + 6;
        if (collapsed) {
            guiGraphics.fill(cx + 1, cy, cx + 2, cy + 5, textColor);
            guiGraphics.fill(cx + 2, cy + 1, cx + 3, cy + 4, textColor);
            guiGraphics.fill(cx + 3, cy + 2, cx + 4, cy + 3, textColor);
        } else {
            guiGraphics.fill(cx, cy + 1, cx + 5, cy + 2, textColor);
            guiGraphics.fill(cx + 1, cy + 2, cx + 4, cy + 3, textColor);
            guiGraphics.fill(cx + 2, cy + 3, cx + 3, cy + 4, textColor);
        }

        guiGraphics.fill(x, y + 15, x + w, y + 16, hover ? 0xFF4A7BB8 : 0xFF2A4A72);
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

        // Card 1: Отображение в предпросмотре (все настройки профиля переехали в левую панель)
        int y1 = y0 + h0 + 8;
        int h1 = 150;
        guiGraphics.fill(x, y1, x + panelW, y1 + h1, 0xEE1E2436);
        guiGraphics.fill(x, y1, x + panelW, y1 + 2, 0xFF354466);
        guiGraphics.drawString(this.font, Component.translatable("roadbuilder.gui.view_title").getString(), x + 10, y1 + 8, 0xFFFFFFFF, false);

        int vy = y1 + 26;
        drawToggleButton(guiGraphics, x + 8, vy, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_road").getString(), viewRoad, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 24, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_viaduct").getString(), viewViaduct, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 48, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_tunnel").getString(), viewTunnel, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 72, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_embankment").getString(), viewEmbankment, mouseX, mouseY);
        drawToggleButton(guiGraphics, x + 8, vy + 96, panelW - 16, 20, Component.translatable("roadbuilder.gui.view_slope").getString(), viewSlope, mouseX, mouseY);

        // Card 2: Настройка 2-го слоя (барьеры / озеленение) для выбранного элемента стека
        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
        List<RoadStackElement> stack = active.getStack();
        int y2 = y1 + h1 + 8;
        if (selectedElementIdx >= 0 && selectedElementIdx < stack.size()) {
            RoadStackElement sel = stack.get(selectedElementIdx);
            int h2 = 100;

            guiGraphics.fill(x, y2, x + panelW, y2 + h2, 0xEE1E2436);
            guiGraphics.fill(x, y2, x + panelW, y2 + 2, 0xFF354466);
            int tab1X = x + panelW - 102;
            int tab2X = x + panelW - 52;
            int tabY = y2 + 5;
            drawPillButton(guiGraphics, tab1X, tabY, 48, 14, "Барьеры", rightLayerTab == 0, mouseX, mouseY);
            drawPillButton(guiGraphics, tab2X, tabY, 44, 14, "Зелень", rightLayerTab == 1, mouseX, mouseY);

            String title = getElementShortLabel(sel);
            int maxTitleW = tab1X - (x + 12);
            if (this.font.width(title) > maxTitleW) {
                title = this.font.plainSubstrByWidth(title, Math.max(20, maxTitleW - 6)) + "...";
            }
            guiGraphics.drawString(this.font, title, x + 8, y2 + 8, 0xFFFFFFFF, false);

            int innerW = panelW - 16;
            int gap = 4;
            int wRow1 = (innerW - gap) / 2;
            int r1Y = y2 + 24;
            int wRow2 = (innerW - gap * 2) / 3;
            int r2Y = r1Y + 24;
            int r3Y = r2Y + 24;

            if (rightLayerTab == 0) {
                drawPillButton(guiGraphics, x + 8, r1Y, wRow1, 20, "Нет",
                        sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.NONE, mouseX, mouseY);
                drawPillButton(guiGraphics, x + 8 + wRow1 + gap, r1Y, innerW - wRow1 - gap, 20, "По центру",
                        sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.CENTER, mouseX, mouseY);

                drawPillButton(guiGraphics, x + 8, r2Y, wRow2, 20, "К дороге",
                        sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.INNER, mouseX, mouseY);
                drawPillButton(guiGraphics, x + 8 + wRow2 + gap, r2Y, wRow2, 20, "От дороги",
                        sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.OUTER, mouseX, mouseY);
                drawPillButton(guiGraphics, x + 8 + (wRow2 + gap) * 2, r2Y, innerW - (wRow2 + gap) * 2, 20, "Оба края",
                        sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.BOTH, mouseX, mouseY);

                ModularRoadPreset.GuardrailStyle elemStyle = sel.resolveGuardrailStyle(active);
                String styleLabel = "Стиль: " + elemStyle.getLabel();
                if (this.font.width(styleLabel) > innerW - 12) {
                    styleLabel = this.font.plainSubstrByWidth(styleLabel, innerW - 18) + "...";
                }
                drawSettingButton(guiGraphics, x + 8, r3Y, innerW, 20, styleLabel, mouseX, mouseY);
            } else {
                if (!sel.getType().supportsGreenery()) {
                    guiGraphics.drawString(this.font, "§7Деревья: газон, тротуар", x + 10, r1Y + 8, 0xFF99AABB, false);
                    guiGraphics.drawString(this.font, "§7Кусты: + обочина", x + 10, r1Y + 22, 0xFF99AABB, false);
                } else {
                    drawPillButton(guiGraphics, x + 8, r1Y, wRow1, 20, "Нет",
                            sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.NONE, mouseX, mouseY);
                    drawPillButton(guiGraphics, x + 8 + wRow1 + gap, r1Y, innerW - wRow1 - gap, 20, "По центру",
                            sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.CENTER, mouseX, mouseY);

                    drawPillButton(guiGraphics, x + 8, r2Y, wRow2, 20, "К дороге",
                            sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.INNER, mouseX, mouseY);
                    drawPillButton(guiGraphics, x + 8 + wRow2 + gap, r2Y, wRow2, 20, "От дороги",
                            sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.OUTER, mouseX, mouseY);
                    drawPillButton(guiGraphics, x + 8 + (wRow2 + gap) * 2, r2Y, innerW - (wRow2 + gap) * 2, 20, "Оба края",
                            sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.BOTH, mouseX, mouseY);

                    String gLabel = "Растение: " + sel.getGreeneryStyle().getLabel();
                    if (this.font.width(gLabel) > innerW - 12) {
                        gLabel = this.font.plainSubstrByWidth(gLabel, innerW - 18) + "...";
                    }
                    drawSettingButton(guiGraphics, x + 8, r3Y, innerW, 20, gLabel, mouseX, mouseY);
                }
            }
        } else {
            int h2 = 38;
            guiGraphics.fill(x, y2, x + panelW, y2 + h2, 0xEE1E2436);
            guiGraphics.fill(x, y2, x + panelW, y2 + 2, 0xFF354466);
            guiGraphics.drawString(this.font, "2-й слой (барьеры / зелень)", x + 10, y2 + 7, 0xFFFFFFFF, false);
            guiGraphics.drawString(this.font, "§7Кликните элемент снизу", x + 10, y2 + 21, 0xFF99AABB, false);
        }
    }

    private void drawPillButton(GuiGraphics guiGraphics, int x, int y, int w, int h, String text, boolean active, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        int border = active ? 0xFF55E0FF : (hover ? 0xFF66B3FF : 0xFF2A4A72);
        int bg = active ? 0xFF1F4B7D : (hover ? 0xFF284872 : 0xFF14243B);
        guiGraphics.fill(x, y, x + w, y + h, border);
        guiGraphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        int tw = this.font.width(text);
        guiGraphics.drawString(this.font, text, x + Math.max(2, (w - tw) / 2), y + 6, active ? 0xFFFFFFFF : 0xFFD8E4F0, false);
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

        // Enable scissor clipping for scrollable horizontal stack + 2nd-layer indicators
        guiGraphics.enableScissor(10, stackY - 9, this.width - 10, stackY + itemH + 6);

        int leftOuterSw = -1;
        int rightOuterSw = -1;
        if (active.isNoiseWallEnabled()) {
            boolean laneSeen = false;
            for (int i = 0; i < stack.size(); i++) {
                RoadElementType t = stack.get(i).getType();
                if (t == RoadElementType.SIDEWALK && !laneSeen) { leftOuterSw = i; break; }
                if (t == RoadElementType.LANE) laneSeen = true;
            }
            laneSeen = false;
            for (int i = stack.size() - 1; i >= 0; i--) {
                RoadElementType t = stack.get(i).getType();
                if (t == RoadElementType.SIDEWALK && !laneSeen) { rightOuterSw = i; break; }
                if (t == RoadElementType.LANE) laneSeen = true;
            }
        }

        int curX = startX;
        for (int i = 0; i < stack.size(); i++) {
            RoadStackElement elem = stack.get(i);
            int ew = elemWidths[i];
            int bx = curX;

            boolean isHover = mouseX >= bx && mouseX <= bx + ew && mouseY >= stackY && mouseY <= stackY + itemH;
            boolean isSel = (i == selectedElementIdx);
            boolean isDragged = (isDraggingElement && i == draggedElementIdx);

            int drawY = isDragged ? stackY - 2 : stackY;

            // Render 2nd-layer guardrail / noise-wall markers directly above element box
            int barY0 = drawY - 6;
            int barY1 = drawY - 2;
            int markerW = Math.max(10, ew / 4);
            ModularRoadPreset.GuardrailStyle elemStyle = elem.resolveGuardrailStyle(active);
            if (elemStyle != ModularRoadPreset.GuardrailStyle.NONE
                    && elem.getGuardrail() != ModularRoadPreset.GuardrailPlacement.NONE) {
                boolean roadIsRight = true;
                int bestDist = Integer.MAX_VALUE;
                for (int j = 0; j < stack.size(); j++) {
                    if (stack.get(j).getType() == RoadElementType.LANE && j != i) {
                        int d = Math.abs(j - i);
                        if (d < bestDist) {
                            bestDist = d;
                            roadIsRight = (j > i);
                        }
                    }
                }
                int col = switch (elemStyle) {
                    case IRON -> 0xFF55E0FF;
                    case ANVIL -> 0xFFAAB4C2;
                    case BAMBOO -> 0xFF7BE04E;
                    case DIORITE -> 0xFFF0EDE5;
                    case GLASS -> 0xFFC29BFF;
                    case NONE -> 0xFF55E0FF;
                };
                switch (elem.getGuardrail()) {
                    case CENTER -> guiGraphics.fill(bx + (ew - markerW) / 2, barY0, bx + (ew + markerW) / 2, barY1, col);
                    case INNER -> {
                        int mx = roadIsRight ? (bx + ew - markerW - 2) : (bx + 2);
                        guiGraphics.fill(mx, barY0, mx + markerW, barY1, col);
                    }
                    case OUTER -> {
                        int mx = roadIsRight ? (bx + 2) : (bx + ew - markerW - 2);
                        guiGraphics.fill(mx, barY0, mx + markerW, barY1, col);
                    }
                    case BOTH -> {
                        guiGraphics.fill(bx + 2, barY0, bx + 2 + markerW, barY1, col);
                        guiGraphics.fill(bx + ew - markerW - 2, barY0, bx + ew - 2, barY1, col);
                    }
                    case NONE -> {}
                }
            }
            if (i == leftOuterSw) {
                guiGraphics.fill(bx + 2, barY0 - 2, bx + 2 + markerW, barY1, 0xFFFFAA33);
            }
            if (i == rightOuterSw && rightOuterSw != leftOuterSw) {
                guiGraphics.fill(bx + ew - markerW - 2, barY0 - 2, bx + ew - 2, barY1, 0xFFFFAA33);
            }

            int border = isDragged ? 0xFFFFFF55 : (isSel ? 0xFFFFFFFF : (isHover ? 0xFF66B3FF : 0xFF2A75D3));
            int bg = isDragged ? 0xFF2B5282 : (isSel ? 0xFF1E4678 : (isHover ? 0xFF19365E : 0xEE162947));

            guiGraphics.fill(bx, drawY, bx + ew, drawY + itemH, border);
            guiGraphics.fill(bx + 1, drawY + 1, bx + ew - 1, drawY + itemH - 1, bg);

            // Render 2nd-layer greenery slot markers inside the top lip of the element box
            ModularRoadPreset.GreeneryStyle gStyle = elem.getGreeneryStyle();
            if (gStyle.isActive() && elem.getGreenery() != ModularRoadPreset.GuardrailPlacement.NONE && gStyle.isAllowedFor(elem.getType())) {
                boolean roadIsRightG = true;
                int bestDistG = Integer.MAX_VALUE;
                for (int j = 0; j < stack.size(); j++) {
                    if (stack.get(j).getType() == RoadElementType.LANE && j != i) {
                        int d = Math.abs(j - i);
                        if (d < bestDistG) {
                            bestDistG = d;
                            roadIsRightG = (j > i);
                        }
                    }
                }
                int gCol = switch (gStyle) {
                    case OAK -> 0xFF4CD964;
                    case PALM -> 0xFFA8E04E;
                    case SMALL -> 0xFF55E080;
                    case POPLAR -> 0xFF2EB85C;
                    case TREE_MIX -> 0xFF88F048;
                    case BUSH_LIGHT -> 0xFFFFE066;
                    case BUSH_WALL -> 0xFF70C948;
                    case NONE -> 0xFF4CD964;
                };
                int gY0 = drawY + 2;
                int gY1 = drawY + 5;
                switch (elem.getGreenery()) {
                    case CENTER -> guiGraphics.fill(bx + (ew - markerW) / 2, gY0, bx + (ew + markerW) / 2, gY1, gCol);
                    case INNER -> {
                        int mx = roadIsRightG ? (bx + ew - markerW - 2) : (bx + 2);
                        guiGraphics.fill(mx, gY0, mx + markerW, gY1, gCol);
                    }
                    case OUTER -> {
                        int mx = roadIsRightG ? (bx + 2) : (bx + ew - markerW - 2);
                        guiGraphics.fill(mx, gY0, mx + markerW, gY1, gCol);
                    }
                    case BOTH -> {
                        guiGraphics.fill(bx + 2, gY0, bx + 2 + markerW, gY1, gCol);
                        guiGraphics.fill(bx + ew - markerW - 2, gY0, bx + ew - 2, gY1, gCol);
                    }
                    case NONE -> {}
                }
            }

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
                    saveAndRefreshStudio();
                    isAddMenuOpen = false;
                    return true;
                }
            }
            isAddMenuOpen = false;
            return true;
        }

        // 2. Left Panel handling (Tabs & lists)
        int panelW = 200;
        int panelH = this.height - 100;
        int lx = 16;
        int ly = 20;

        if (mouseX >= lx && mouseX <= lx + panelW && mouseY >= ly && mouseY <= ly + panelH) {
            int tabW = (panelW - 16) / 2;
            int tabH = 20;
            int tab1X = lx + 6;
            int tab2X = lx + 10 + tabW;
            int tabY = ly + 8;

            // Tab 0: [ Настройки ]
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
                int contentH = panelH - (contentY - ly) - 8;
                if (mouseY < contentY || mouseY > contentY + contentH) {
                    return true;
                }

                // Interactive Road Settings with collapsible sections
                ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
                int curY = contentY - (int) leftPanelScroll;
                int btnW = panelW - 16;
                int btnH = 20;

                // 1. ПЕРЕКРЁСТКИ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[0] = !SECTION_COLLAPSED[0];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[0]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleJunctionStyle();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                }

                // 2. ФОНАРИ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[1] = !SECTION_COLLAPSED[1];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[1]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleLampStyle();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (active.getLampStyle() != ModularRoadPreset.LampStyle.OFF) {
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleLampSpacing();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.toggleSidewalkDoubleLamps();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleLampPlacement();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                    }
                }

                // 3. ОГРАЖДЕНИЯ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[2] = !SECTION_COLLAPSED[2];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[2]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleAllElementGuardrailStyles();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.toggleNoiseWall();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                }

                // 4. ОЗЕЛЕНЕНИЕ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[3] = !SECTION_COLLAPSED[3];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[3]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleAllElementGreeneryStyles();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleTreeSpacing();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleBushSpacing();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                }

                // 5. НАСЫПЬ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[4] = !SECTION_COLLAPSED[4];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[4]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.setEmbankmentMode(cycleToggle(active.getEmbankmentMode()));
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (active.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF) {
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleEmbankmentShape();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleEmbankmentMaterial();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                    }
                }

                // 6. ОТКОСЫ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[5] = !SECTION_COLLAPSED[5];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[5]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.setSlopeMode(cycleToggle(active.getSlopeMode()));
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (active.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF) {
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleSlopeStyle();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleSlopeMaterial();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                    }
                }

                // 7. ТУННЕЛИ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[6] = !SECTION_COLLAPSED[6];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[6]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.setTunnelMode(cycleMode(active.getTunnelMode()));
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (active.getTunnelMode() != ModularRoadPreset.Mode.OFF) {
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cycleTunnelShape();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                    }
                }

                // 8. ЭСТАКАДЫ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[7] = !SECTION_COLLAPSED[7];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[7]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.setViaductMode(cycleMode(active.getViaductMode()));
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (active.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                        if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                            active.cyclePillarStyle();
                            saveAndRefreshStudio();
                            return true;
                        }
                        curY += 24;
                    }
                }

                // 9. БОРДЮРЫ И ТРОТУАРЫ
                if (isHeaderClicked(mouseX, mouseY, lx + 8, curY, btnW)) {
                    SECTION_COLLAPSED[8] = !SECTION_COLLAPSED[8];
                    return true;
                }
                curY += 18;
                if (!SECTION_COLLAPSED[8]) {
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleCurbPalette();
                        saveAndRefreshStudio();
                        return true;
                    }
                    curY += 24;
                    if (mouseX >= lx + 8 && mouseX <= lx + 8 + btnW && mouseY >= curY && mouseY <= curY + btnH) {
                        active.cycleSidewalkPalette();
                        saveAndRefreshStudio();
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
                        studioPreview.markDirty();
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
                    studioPreview.markDirty();
                    selectedElementIdx = -1;
                    if (nameEditBox != null) nameEditBox.setValue(np.getName());
                    return true;
                }

                // [Копия]
                int b2X = b1X + bW + gap;
                if (mouseX >= b2X && mouseX <= b2X + bW && mouseY >= btnY && mouseY <= btnY + btnH2) {
                    ModularRoadPreset activeP = PresetManager.getInstance().getActivePreset();
                    ModularRoadPreset dup = PresetManager.getInstance().duplicatePreset(activeP.getId());
                    studioPreview.markDirty();
                    selectedElementIdx = -1;
                    if (nameEditBox != null && dup != null) nameEditBox.setValue(dup.getName());
                    return true;
                }

                // [Удал]
                int b3X = b2X + bW + gap;
                if (mouseX >= b3X && mouseX <= b3X + bW && mouseY >= btnY && mouseY <= btnY + btnH2) {
                    ModularRoadPreset activeP = PresetManager.getInstance().getActivePreset();
                    PresetManager.getInstance().deletePreset(activeP.getId());
                    studioPreview.markDirty();
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
        int vy = y1 + 26;

        if (this.nameEditBox != null && this.nameEditBox.mouseClicked(mouseX, mouseY, button)) {
            this.setFocused(this.nameEditBox);
            return true;
        }

        if (mouseX >= rx + 8 && mouseX <= rx + rPanelW - 8) {
            // View toggles card
            if (mouseY >= vy && mouseY <= vy + 20) { viewRoad = !viewRoad; studioPreview.markDirty(); return true; }
            if (mouseY >= vy + 24 && mouseY <= vy + 44) { viewViaduct = !viewViaduct; studioPreview.markDirty(); return true; }
            if (mouseY >= vy + 48 && mouseY <= vy + 68) { viewTunnel = !viewTunnel; studioPreview.markDirty(); return true; }
            if (mouseY >= vy + 72 && mouseY <= vy + 92) { viewEmbankment = !viewEmbankment; studioPreview.markDirty(); return true; }
            if (mouseY >= vy + 96 && mouseY <= vy + 116) { viewSlope = !viewSlope; studioPreview.markDirty(); return true; }
        }
        if (mouseX >= rx && mouseX <= rx + rPanelW && ((mouseY >= y0 && mouseY <= y0 + 46) || (mouseY >= y1 && mouseY <= y1 + 150))) {
            return true;
        }

        // Card 2: Selected element 2nd-layer (guardrails / greenery) placement and per-element style clicks
        ModularRoadPreset activeForCard = PresetManager.getInstance().getActivePreset();
        List<RoadStackElement> cardStack = activeForCard.getStack();
        int y2 = y1 + 150 + 8;
        if (selectedElementIdx >= 0 && selectedElementIdx < cardStack.size()) {
            RoadStackElement sel = cardStack.get(selectedElementIdx);
            int tab1X = rx + rPanelW - 102;
            int tab2X = rx + rPanelW - 52;
            int tabY = y2 + 5;
            if (mouseY >= tabY && mouseY <= tabY + 14) {
                if (mouseX >= tab1X && mouseX <= tab1X + 48) {
                    rightLayerTab = 0;
                    return true;
                }
                if (mouseX >= tab2X && mouseX <= tab2X + 44) {
                    rightLayerTab = 1;
                    return true;
                }
            }

            int innerW = rPanelW - 16;
            int gap2 = 4;
            int wRow1 = (innerW - gap2) / 2;
            int r1Y = y2 + 24;
            int wRow2 = (innerW - gap2 * 2) / 3;
            int r2Y = r1Y + 24;
            int r3Y = r2Y + 24;

            if (rightLayerTab == 0 || sel.getType().supportsGreenery()) {
                ModularRoadPreset.GuardrailPlacement chosen = null;
                if (mouseY >= r1Y && mouseY <= r1Y + 20) {
                    if (mouseX >= rx + 8 && mouseX <= rx + 8 + wRow1) chosen = ModularRoadPreset.GuardrailPlacement.NONE;
                    else if (mouseX >= rx + 8 + wRow1 + gap2 && mouseX <= rx + rPanelW - 8) chosen = ModularRoadPreset.GuardrailPlacement.CENTER;
                } else if (mouseY >= r2Y && mouseY <= r2Y + 20) {
                    if (mouseX >= rx + 8 && mouseX <= rx + 8 + wRow2) chosen = ModularRoadPreset.GuardrailPlacement.INNER;
                    else if (mouseX >= rx + 8 + wRow2 + gap2 && mouseX <= rx + 8 + wRow2 * 2 + gap2) chosen = ModularRoadPreset.GuardrailPlacement.OUTER;
                    else if (mouseX >= rx + 8 + (wRow2 + gap2) * 2 && mouseX <= rx + rPanelW - 8) chosen = ModularRoadPreset.GuardrailPlacement.BOTH;
                } else if (mouseY >= r3Y && mouseY <= r3Y + 20 && mouseX >= rx + 8 && mouseX <= rx + rPanelW - 8) {
                    if (rightLayerTab == 0) {
                        ModularRoadPreset.GuardrailStyle nextStyle = sel.cycleGuardrailStyle(activeForCard);
                        if (nextStyle == ModularRoadPreset.GuardrailStyle.NONE) {
                            sel.setGuardrail(ModularRoadPreset.GuardrailPlacement.NONE);
                        } else if (sel.getGuardrail() == ModularRoadPreset.GuardrailPlacement.NONE) {
                            sel.setGuardrail(ModularRoadPreset.defaultGuardrailPlacementFor(sel.getType()));
                        }
                    } else {
                        ModularRoadPreset.GreeneryStyle nextStyle = sel.cycleGreeneryStyle();
                        if (nextStyle == ModularRoadPreset.GreeneryStyle.NONE) {
                            sel.setGreenery(ModularRoadPreset.GuardrailPlacement.NONE);
                        } else if (sel.getGreenery() == ModularRoadPreset.GuardrailPlacement.NONE) {
                            sel.setGreenery(ModularRoadPreset.defaultGreeneryPlacementFor(sel.getType()));
                        }
                    }
                    saveAndRefreshStudio();
                    return true;
                }
                if (chosen != null) {
                    if (rightLayerTab == 0) {
                        sel.setGuardrail(chosen);
                        if (chosen != ModularRoadPreset.GuardrailPlacement.NONE) {
                            ModularRoadPreset.GuardrailStyle resolved = sel.resolveGuardrailStyle(activeForCard);
                            if (resolved == ModularRoadPreset.GuardrailStyle.NONE) {
                                sel.setGuardrailStyle(ModularRoadPreset.GuardrailStyle.IRON);
                            } else if (sel.getRawGuardrailStyle() == null) {
                                sel.setGuardrailStyle(resolved);
                            }
                        }
                    } else {
                        sel.setGreenery(chosen);
                        if (chosen != ModularRoadPreset.GuardrailPlacement.NONE
                                && (sel.getGreeneryStyle() == ModularRoadPreset.GreeneryStyle.NONE
                                        || !sel.getGreeneryStyle().isAllowedFor(sel.getType()))) {
                            sel.setGreeneryStyle(sel.getType().supportsTrees()
                                    ? ModularRoadPreset.GreeneryStyle.OAK
                                    : ModularRoadPreset.GreeneryStyle.BUSH_LIGHT);
                        }
                    }
                    saveAndRefreshStudio();
                    return true;
                }
            }
            int h2 = 100;
            if (mouseX >= rx && mouseX <= rx + rPanelW && mouseY >= y2 && mouseY <= y2 + h2) {
                return true;
            }
        } else if (mouseX >= rx && mouseX <= rx + rPanelW && mouseY >= y2 && mouseY <= y2 + 38) {
            return true;
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

        ModularRoadPreset active = PresetManager.getInstance().getActivePreset();
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
                saveAndRefreshStudio();
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
                saveAndRefreshStudio();
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
                saveAndRefreshStudio();
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
                    saveAndRefreshStudio();
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
                    saveAndRefreshStudio();
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

        // 6. Viewport 3D orbit (LMB), pan (RMB / MMB), and double-click fit
        if (this.nameEditBox != null) {
            this.nameEditBox.setFocused(false);
        }
        if (button == 0) {
            long now = System.currentTimeMillis();
            double dist = Math.hypot(mouseX - lastViewportClickX, mouseY - lastViewportClickY);
            if (now - lastViewportClickMs <= 280L && dist <= 5.0) {
                studioPreview.fit();
                lastViewportClickMs = 0L;
            } else {
                lastViewportClickMs = now;
                lastViewportClickX = mouseX;
                lastViewportClickY = mouseY;
            }
            isDraggingOrbit = true;
            return true;
        }
        if (button == 1 || button == 2) {
            isDraggingPan = true;
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
            isDraggingOrbit = false;
            isDraggingElement = false;
            draggedElementIdx = -1;
        }
        if (button == 1 || button == 2) {
            isDraggingPan = false;
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
                    saveAndRefreshStudio();
                    return true;
                }
                // Check swap with right neighbor
                if (draggedElementIdx < stack.size() - 1 && mouseX > centers[draggedElementIdx + 1]) {
                    RoadStackElement item = stack.remove(draggedElementIdx);
                    stack.add(draggedElementIdx + 1, item);
                    draggedElementIdx++;
                    selectedElementIdx = draggedElementIdx;
                    saveAndRefreshStudio();
                    return true;
                }
            }
            return true;
        }

        // 3D pan & orbit
        if (isDraggingPan) {
            studioPreview.pan((float) dragX, (float) dragY);
            return true;
        }
        if (isDraggingOrbit) {
            studioPreview.rotate((float) dragX, (float) dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private boolean isHeaderClicked(double mouseX, double mouseY, int x, int y, int w) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 16;
    }

    private void saveAndRefreshStudio() {
        PresetManager.getInstance().saveToFile();
        ClientGraphCache.getInstance().markPreviewDirty();
        studioPreview.markDirty();
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
        int panelW = 200;
        int panelH = this.height - 100;
        if (mouseX >= 16 && mouseX <= 16 + panelW && mouseY >= 20 && mouseY <= 20 + panelH) {
            int contentH = Math.max(1, panelH - 36 - ((leftTab == 1) ? 36 : 8));
            float maxScroll = (leftTab == 0)
                    ? Math.max(0, leftSettingsContentHeight - contentH)
                    : Math.max(0, PresetManager.getInstance().getAllPresets().size() * 24 - contentH);
            leftPanelScroll = Math.max(0, Math.min(maxScroll, leftPanelScroll - (float) scrollY * 20));
            return true;
        }

        // 3. Right panel area (ignore scroll inside cards)
        int rPanelW = 195;
        int rx = this.width - rPanelW - 16;
        if (mouseX >= rx && mouseX <= rx + rPanelW && mouseY >= 20 && mouseY <= 224) {
            return true;
        }

        // 4. Bottom stack horizontal scrolling
        if (mouseY >= this.height - 76) {
            stackScrollX = Math.max(0, Math.min(maxStackScroll, stackScrollX - (float) scrollY * 28));
            return true;
        }

        // 5. Default: Cursor-centered exponential zoom in 3D preview
        studioPreview.zoomAt(scrollY, (float) mouseX, (float) mouseY);
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
                saveAndRefreshStudio();
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_RIGHT && selectedElementIdx < stack.size() - 1) {
                RoadStackElement item = stack.remove(selectedElementIdx);
                stack.add(selectedElementIdx + 1, item);
                selectedElementIdx++;
                saveAndRefreshStudio();
                return true;
            } else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (stack.size() > 1) {
                    stack.remove(selectedElementIdx);
                    if (selectedElementIdx >= stack.size()) {
                        selectedElementIdx = stack.size() - 1;
                    }
                    saveAndRefreshStudio();
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
