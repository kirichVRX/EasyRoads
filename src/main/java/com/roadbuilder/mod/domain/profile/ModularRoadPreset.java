package com.roadbuilder.mod.domain.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * High-level road preset that contains the lane stack, viaduct/tunnel/slope settings, and block palettes.
 */
public class ModularRoadPreset {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Mode {
        AUTO("auto"),
        ALWAYS("always"),
        OFF("off");

        private final String keySuffix;
        Mode(String keySuffix) { this.keySuffix = keySuffix; }
        public String getLabel() {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.mode." + keySuffix).getString();
        }
    }

    public enum ToggleMode {
        AUTO("auto"),
        OFF("off");

        private final String keySuffix;
        ToggleMode(String keySuffix) { this.keySuffix = keySuffix; }
        public String getLabel() {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.mode." + keySuffix).getString();
        }
    }

    // 1. Перекрёстки: обычный, городской (с переходами), кольцо
    public enum JunctionPresetStyle {
        CITY("Городской (с переходами)"),
        REGULAR("Обычный"),
        ROUNDABOUT("Кольцевой");

        private final String label;
        JunctionPresetStyle(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 2. Фонари: стиль, расстояние
    public enum LampStyle {
        MODERN("Современный"),
        CLASSIC("Классический"),
        HIGHWAY("Шоссейный"),
        OFF("Выкл");

        private final String label;
        LampStyle(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 3. Отбойники / ограждения: стили
    public enum GuardrailStyle {
        NONE("Нет"),
        METAL("Металл"),
        CONCRETE("Бетонный парапет"),
        CABLE("Тросовое");

        private final String label;
        GuardrailStyle(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 4. Насыпь: форма (лесенкой или прямой каменный фундамент), материал
    public enum EmbankmentShape {
        STEPPED("Лесенкой"),
        WALL("Прямой фундамент");

        private final String label;
        EmbankmentShape(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public enum EmbankmentMaterial {
        EARTH("Земля / газон"),
        STONE_BRICK("Каменный кирпич"),
        COBBLESTONE("Булыжник"),
        SMOOTH_STONE("Гладкий камень");

        private final String label;
        EmbankmentMaterial(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 5. Откосы: форма и стиль
    public enum SlopeStyle {
        STEPPED_WALL("Подпорная стена"),
        SMOOTH("Плавный откос"),
        GABION("Габионы");

        private final String label;
        SlopeStyle(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 6. Туннели: форма
    public enum TunnelShape {
        ARCH("Арочный"),
        RECTANGULAR("Прямоугольный"),
        PORTAL("Портальный");

        private final String label;
        TunnelShape(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 7. Эстакады: стиль опор
    public enum PillarStyle {
        STANDARD("Стандартные пилоны"),
        DOUBLE("Двойные опоры"),
        ARCH("Арочные опоры");

        private final String label;
        PillarStyle(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 8. Бордюры: стиль
    public enum CurbPalette {
        STONE("Каменный"),
        ANDESITE("Андезит"),
        QUARTZ("Кварц"),
        BLACKSTONE("Чернит");

        private final String label;
        CurbPalette(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 9. Тротуары: стиль
    public enum SidewalkPalette {
        MOSAIC("Городская мозаика"),
        TILES("Плитка"),
        SMOOTH_STONE("Каменные плиты"),
        RED_SANDSTONE("Брусчатка");

        private final String label;
        SidewalkPalette(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    private String id;
    private String name;
    private List<RoadStackElement> stack;

    // Generation settings
    private Mode viaductMode = Mode.AUTO;
    private Mode tunnelMode = Mode.AUTO;
    private ToggleMode slopeMode = ToggleMode.AUTO;
    private ToggleMode embankmentMode = ToggleMode.AUTO;

    // Detailed road customization settings
    private JunctionPresetStyle junctionStyle = JunctionPresetStyle.CITY;
    private LampStyle lampStyle = LampStyle.MODERN;
    private int lampSpacing = 20;
    private GuardrailStyle guardrailStyle = GuardrailStyle.NONE;

    private boolean treesEnabled = true;
    private int treeSpacing = 16;
    private boolean bushesEnabled = true;
    private int bushSpacing = 6;

    private EmbankmentShape embankmentShape = EmbankmentShape.STEPPED;
    private EmbankmentMaterial embankmentMaterial = EmbankmentMaterial.EARTH;
    private SlopeStyle slopeStyle = SlopeStyle.STEPPED_WALL;
    private TunnelShape tunnelShape = TunnelShape.ARCH;
    private PillarStyle pillarStyle = PillarStyle.STANDARD;

    private CurbPalette curbPalette = CurbPalette.STONE;
    private SidewalkPalette sidewalkPalette = SidewalkPalette.MOSAIC;

    public ModularRoadPreset() {
        this("default", "default");
    }

    public ModularRoadPreset(String id, String name) {
        this.id = id;
        this.name = name;
        this.stack = new ArrayList<>();
        this.viaductMode = Mode.AUTO;
        this.tunnelMode = Mode.AUTO;
        this.slopeMode = ToggleMode.AUTO;
        this.embankmentMode = ToggleMode.AUTO;
    }

    public static ModularRoadPreset createDefault() {
        ModularRoadPreset preset = new ModularRoadPreset("default", "default");
        // Layout matching user spec:
        // [Тротуар 4] [Бордюр 1] [Полоса 4] [Разметка 1] [Полоса 4] [Бордюр 1] [Тротуар 4]
        preset.stack.add(new RoadStackElement(RoadElementType.SIDEWALK, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_DASHED_LONG, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.SIDEWALK, 4));

        preset.viaductMode = Mode.AUTO;
        preset.tunnelMode = Mode.AUTO;
        preset.slopeMode = ToggleMode.AUTO;
        preset.embankmentMode = ToggleMode.AUTO;
        return preset;
    }

    public static ModularRoadPreset createCountryRoad() {
        ModularRoadPreset preset = new ModularRoadPreset("country_road", "Сountry road");
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_SOLID, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
        preset.viaductMode = Mode.AUTO;
        preset.tunnelMode = Mode.AUTO;
        preset.slopeMode = ToggleMode.AUTO;
        preset.embankmentMode = ToggleMode.AUTO;
        return preset;
    }

    public static ModularRoadPreset createSingleLane() {
        return createCountryRoad();
    }

    public static ModularRoadPreset createHighway() {
        ModularRoadPreset preset = new ModularRoadPreset("highway", "Highway");
        preset.stack.add(new RoadStackElement(RoadElementType.SIDEWALK, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_DASHED_LONG, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.DIVIDER_GRASS, 5));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_DASHED_LONG, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.CURB, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.SIDEWALK, 4));
        preset.viaductMode = Mode.AUTO;
        preset.tunnelMode = Mode.AUTO;
        preset.slopeMode = ToggleMode.AUTO;
        preset.embankmentMode = ToggleMode.AUTO;
        return preset;
    }

    public int getTotalWidth() {
        int w = 0;
        for (RoadStackElement elem : stack) {
            w += elem.getWidth();
        }
        return Math.max(1, w);
    }

    public int getRoadwayWidth() {
        int w = 0;
        for (RoadStackElement elem : stack) {
            if (elem.getType() != RoadElementType.SIDEWALK && elem.getType() != RoadElementType.SHOULDER) {
                w += elem.getWidth();
            }
        }
        return w;
    }

    public int getLaneCount() {
        int count = 0;
        for (RoadStackElement elem : stack) {
            if (elem.getType() == RoadElementType.LANE) {
                count++;
            }
        }
        return count;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() {
        if ("default".equals(id)) {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.preset.default").getString();
        } else if ("country_road".equals(id) || "single_lane".equals(id) || "preset_1792686083438".equals(id)) {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.preset.country_road").getString();
        } else if ("highway".equals(id) || "highway_4lane".equals(id) || "preset_1792686213892".equals(id)) {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.preset.highway").getString();
        }
        return name;
    }

    public List<RoadStackElement> getStack() { return stack; }
    public void setStack(List<RoadStackElement> stack) { this.stack = stack; }

    public Mode getViaductMode() { return viaductMode; }
    public void setViaductMode(Mode viaductMode) { this.viaductMode = viaductMode; }

    public Mode getTunnelMode() { return tunnelMode; }
    public void setTunnelMode(Mode tunnelMode) { this.tunnelMode = tunnelMode; }

    public ToggleMode getSlopeMode() { return slopeMode; }
    public void setSlopeMode(ToggleMode slopeMode) { this.slopeMode = slopeMode; }

    public ToggleMode getEmbankmentMode() { return embankmentMode != null ? embankmentMode : ToggleMode.AUTO; }
    public void setEmbankmentMode(ToggleMode embankmentMode) { this.embankmentMode = embankmentMode; }

    // Customization Getters & Setters with Null Safety
    public JunctionPresetStyle getJunctionStyle() { return junctionStyle != null ? junctionStyle : JunctionPresetStyle.CITY; }
    public void setJunctionStyle(JunctionPresetStyle junctionStyle) { this.junctionStyle = junctionStyle; }
    public void cycleJunctionStyle() {
        JunctionPresetStyle[] vals = JunctionPresetStyle.values();
        this.junctionStyle = vals[(getJunctionStyle().ordinal() + 1) % vals.length];
    }

    public LampStyle getLampStyle() { return lampStyle != null ? lampStyle : LampStyle.MODERN; }
    public void setLampStyle(LampStyle lampStyle) { this.lampStyle = lampStyle; }
    public void cycleLampStyle() {
        LampStyle[] vals = LampStyle.values();
        this.lampStyle = vals[(getLampStyle().ordinal() + 1) % vals.length];
    }

    public int getLampSpacing() { return lampSpacing > 0 ? lampSpacing : 20; }
    public void setLampSpacing(int lampSpacing) { this.lampSpacing = lampSpacing; }
    public void cycleLampSpacing() {
        int[] steps = { 15, 20, 25, 30 };
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == this.lampSpacing) {
                this.lampSpacing = steps[(i + 1) % steps.length];
                return;
            }
        }
        this.lampSpacing = 20;
    }

    public GuardrailStyle getGuardrailStyle() { return guardrailStyle != null ? guardrailStyle : GuardrailStyle.NONE; }
    public void setGuardrailStyle(GuardrailStyle guardrailStyle) { this.guardrailStyle = guardrailStyle; }
    public void cycleGuardrailStyle() {
        GuardrailStyle[] vals = GuardrailStyle.values();
        this.guardrailStyle = vals[(getGuardrailStyle().ordinal() + 1) % vals.length];
    }

    public boolean isTreesEnabled() { return treesEnabled; }
    public void setTreesEnabled(boolean treesEnabled) { this.treesEnabled = treesEnabled; }
    public void toggleTrees() { this.treesEnabled = !this.treesEnabled; }

    public int getTreeSpacing() { return treeSpacing > 0 ? treeSpacing : 16; }
    public void setTreeSpacing(int treeSpacing) { this.treeSpacing = treeSpacing; }
    public void cycleTreeSpacing() {
        int[] steps = { 12, 16, 20, 24 };
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == this.treeSpacing) {
                this.treeSpacing = steps[(i + 1) % steps.length];
                return;
            }
        }
        this.treeSpacing = 16;
    }

    public boolean isBushesEnabled() { return bushesEnabled; }
    public void setBushesEnabled(boolean bushesEnabled) { this.bushesEnabled = bushesEnabled; }
    public void toggleBushes() { this.bushesEnabled = !this.bushesEnabled; }

    public int getBushSpacing() { return bushSpacing > 0 ? bushSpacing : 6; }
    public void setBushSpacing(int bushSpacing) { this.bushSpacing = bushSpacing; }
    public void cycleBushSpacing() {
        int[] steps = { 4, 6, 8, 10 };
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == this.bushSpacing) {
                this.bushSpacing = steps[(i + 1) % steps.length];
                return;
            }
        }
        this.bushSpacing = 6;
    }

    public EmbankmentShape getEmbankmentShape() { return embankmentShape != null ? embankmentShape : EmbankmentShape.STEPPED; }
    public void setEmbankmentShape(EmbankmentShape embankmentShape) { this.embankmentShape = embankmentShape; }
    public void cycleEmbankmentShape() {
        EmbankmentShape[] vals = EmbankmentShape.values();
        this.embankmentShape = vals[(getEmbankmentShape().ordinal() + 1) % vals.length];
    }

    public EmbankmentMaterial getEmbankmentMaterial() { return embankmentMaterial != null ? embankmentMaterial : EmbankmentMaterial.EARTH; }
    public void setEmbankmentMaterial(EmbankmentMaterial embankmentMaterial) { this.embankmentMaterial = embankmentMaterial; }
    public void cycleEmbankmentMaterial() {
        EmbankmentMaterial[] vals = EmbankmentMaterial.values();
        this.embankmentMaterial = vals[(getEmbankmentMaterial().ordinal() + 1) % vals.length];
    }

    public SlopeStyle getSlopeStyle() { return slopeStyle != null ? slopeStyle : SlopeStyle.STEPPED_WALL; }
    public void setSlopeStyle(SlopeStyle slopeStyle) { this.slopeStyle = slopeStyle; }
    public void cycleSlopeStyle() {
        SlopeStyle[] vals = SlopeStyle.values();
        this.slopeStyle = vals[(getSlopeStyle().ordinal() + 1) % vals.length];
    }

    public TunnelShape getTunnelShape() { return tunnelShape != null ? tunnelShape : TunnelShape.ARCH; }
    public void setTunnelShape(TunnelShape tunnelShape) { this.tunnelShape = tunnelShape; }
    public void cycleTunnelShape() {
        TunnelShape[] vals = TunnelShape.values();
        this.tunnelShape = vals[(getTunnelShape().ordinal() + 1) % vals.length];
    }

    public PillarStyle getPillarStyle() { return pillarStyle != null ? pillarStyle : PillarStyle.STANDARD; }
    public void setPillarStyle(PillarStyle pillarStyle) { this.pillarStyle = pillarStyle; }
    public void cyclePillarStyle() {
        PillarStyle[] vals = PillarStyle.values();
        this.pillarStyle = vals[(getPillarStyle().ordinal() + 1) % vals.length];
    }

    public CurbPalette getCurbPalette() { return curbPalette != null ? curbPalette : CurbPalette.STONE; }
    public void setCurbPalette(CurbPalette curbPalette) { this.curbPalette = curbPalette; }
    public void cycleCurbPalette() {
        CurbPalette[] vals = CurbPalette.values();
        this.curbPalette = vals[(getCurbPalette().ordinal() + 1) % vals.length];
    }

    public SidewalkPalette getSidewalkPalette() { return sidewalkPalette != null ? sidewalkPalette : SidewalkPalette.MOSAIC; }
    public void setSidewalkPalette(SidewalkPalette sidewalkPalette) { this.sidewalkPalette = sidewalkPalette; }
    public void cycleSidewalkPalette() {
        SidewalkPalette[] vals = SidewalkPalette.values();
        this.sidewalkPalette = vals[(getSidewalkPalette().ordinal() + 1) % vals.length];
    }

    public int[] getPillarPositions() {
        int totalWidth = getTotalWidth();
        int minPx = Math.min(2, Math.max(0, totalWidth - 5));
        int maxPx = Math.max(minPx, totalWidth - 7);
        if (totalWidth < 18) {
            return new int[]{ Math.max(0, (totalWidth - 5) / 2) };
        } else if (totalWidth < 28) {
            int px1 = Math.max(minPx, Math.min((int) Math.round(totalWidth * 0.25 - 2.5), totalWidth / 2 - 5));
            int px2 = Math.max(totalWidth / 2, Math.min((int) Math.round(totalWidth * 0.75 - 2.5), maxPx));
            return new int[]{ px1, px2 };
        } else {
            int px1 = Math.max(minPx, (int) Math.round(totalWidth * (1.0 / 6.0) - 2.5));
            int px2 = (totalWidth - 5) / 2;
            int px3 = Math.min(maxPx, (int) Math.round(totalWidth * (5.0 / 6.0) - 2.5));
            return new int[]{ px1, px2, px3 };
        }
    }

    public ModularRoadPreset copy() {
        ModularRoadPreset copy = new ModularRoadPreset(this.id, this.name);
        for (RoadStackElement elem : this.stack) {
            copy.stack.add(elem.copy());
        }
        copy.viaductMode = this.viaductMode;
        copy.tunnelMode = this.tunnelMode;
        copy.slopeMode = this.slopeMode;
        copy.embankmentMode = this.embankmentMode;

        copy.junctionStyle = this.junctionStyle;
        copy.lampStyle = this.lampStyle;
        copy.lampSpacing = this.lampSpacing;
        copy.guardrailStyle = this.guardrailStyle;
        copy.treesEnabled = this.treesEnabled;
        copy.treeSpacing = this.treeSpacing;
        copy.bushesEnabled = this.bushesEnabled;
        copy.bushSpacing = this.bushSpacing;
        copy.embankmentShape = this.embankmentShape;
        copy.embankmentMaterial = this.embankmentMaterial;
        copy.slopeStyle = this.slopeStyle;
        copy.tunnelShape = this.tunnelShape;
        copy.pillarStyle = this.pillarStyle;
        copy.curbPalette = this.curbPalette;
        copy.sidewalkPalette = this.sidewalkPalette;

        return copy;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ModularRoadPreset fromJson(String json) {
        return GSON.fromJson(json, ModularRoadPreset.class);
    }
}
