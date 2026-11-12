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

    // 2. Фонари: стиль, расстояние, двусторонние на тротуаре, размещение
    public enum LampStyle {
        DEFAULT("Стандартный", "Road_light_default"),
        MODERN("Современный", "Road_light_modern"),
        DARK("Тёмный", "Road_light_darck"),
        OLD("Старинный", "Road_light_old"),
        OFF("Выкл", "");

        private final String label;
        private final String schematicKey;
        LampStyle(String label, String schematicKey) {
            this.label = label;
            this.schematicKey = schematicKey;
        }
        public String getLabel() { return label; }
        public String getSchematicKey() { return schematicKey; }

        public static LampStyle fromStringOrDefault(String raw, LampStyle fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("HIGHWAY".equals(up)) return DEFAULT;
            if ("CLASSIC".equals(up)) return OLD;
            try {
                return LampStyle.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public enum LampPlacement {
        BOTH_SIDES("С двух сторон"),
        STAGGERED("В шахматном порядке"),
        DIVIDER_ONLY("Только разделитель");

        private final String label;
        LampPlacement(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static LampPlacement fromStringOrDefault(String raw, LampPlacement fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return LampPlacement.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    // 3. Отбойники / ограждения: стили и привязка 2-го слоя на элементах
    public enum GuardrailStyle {
        NONE("Нет", ""),
        IRON("Металлическое", "Road_wall_iron"),
        ANVIL("Отбойник (наковальни)", "Road_wall_anvil"),
        BAMBOO("Бамбуковое", "Road_wall_bambook"),
        DIORITE("Диоритовое", "Road_wall_diorite"),
        GLASS("Стеклянное", "Road_wall_glass");

        private final String label;
        private final String schematicKey;
        GuardrailStyle(String label, String schematicKey) {
            this.label = label;
            this.schematicKey = schematicKey;
        }
        public String getLabel() { return label; }
        public String getSchematicKey() { return schematicKey; }

        public static GuardrailStyle fromStringOrDefault(String raw, GuardrailStyle fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("METAL".equals(up)) return IRON;
            if ("CONCRETE".equals(up)) return DIORITE;
            if ("CABLE".equals(up)) return BAMBOO;
            try {
                return GuardrailStyle.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public enum GuardrailPlacement {
        NONE("Нет"),
        INNER("К дороге"),
        OUTER("От дороги"),
        CENTER("По центру"),
        BOTH("Оба края");

        private final String label;
        GuardrailPlacement(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static GuardrailPlacement fromStringOrDefault(String raw, GuardrailPlacement fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return GuardrailPlacement.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    // 3b. Озеленение (2-й слой: деревья и кусты по элементам стека)
    public enum GreeneryStyle {
        NONE("Нет", null, false, false),
        OAK("Дуб", "Road_Greens_oak", true, false),
        PALM("Пальма", "Road_Greens_palm", true, false),
        SMALL("Деревце", "Road_Greens_smal", true, false),
        POPLAR("Тополь", "Road_Greens_topol", true, false),
        TREE_MIX("Микс деревьев", null, true, false),
        BUSH_LIGHT("Куст с фонарём", "Road_Greens_light", false, true),
        BUSH_WALL("Живая изгородь", "Road_Greens_wall", false, true);

        private final String label;
        private final String schematicKey;
        private final boolean tree;
        private final boolean bush;

        GreeneryStyle(String label, String schematicKey, boolean tree, boolean bush) {
            this.label = label;
            this.schematicKey = schematicKey;
            this.tree = tree;
            this.bush = bush;
        }

        public String getLabel() { return label; }
        public String getSchematicKey() { return schematicKey; }
        public boolean isTree() { return tree; }
        public boolean isBush() { return bush; }
        public boolean isActive() { return this != NONE; }

        public boolean isAllowedFor(RoadElementType type) {
            if (this == NONE) return true;
            if (type == null) return false;
            if (this.tree) return type.supportsTrees();
            if (this.bush) return type.supportsBushes();
            return false;
        }

        public static GreeneryStyle fromStringOrDefault(String raw, GreeneryStyle fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("SMAL".equals(up)) return SMALL;
            if ("TOPOL".equals(up)) return POPLAR;
            if ("MIX".equals(up)) return TREE_MIX;
            try {
                return GreeneryStyle.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    // 4. Насыпь: форма (лесенка 2/1 блок или прямой фундамент), материал
    public enum EmbankmentShape {
        STEPPED("Лесенка (2 бл.)"),
        STEPPED_ONE("Лесенка (1 бл.)"),
        WALL("Прямой фундамент");

        private final String label;
        EmbankmentShape(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static EmbankmentShape fromStringOrDefault(String raw, EmbankmentShape fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("STEPPED_1".equals(up)) return STEPPED_ONE;
            try {
                return EmbankmentShape.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public enum EmbankmentMaterial {
        EARTH("Земля / газон"),
        CRUSHED_STONE("Щебень"),
        STONE("Камень"),
        DEEPSLATE("Сланец (микс)"),
        DIORITE("Диорит (микс)");

        private final String label;
        EmbankmentMaterial(String label) { this.label = label; }
        public String getLabel() { return label; }
        public boolean isSteppedMaterial() { return this == EARTH || this == CRUSHED_STONE; }
        public boolean isWallMaterial() { return !isSteppedMaterial(); }

        public static EmbankmentMaterial fromStringOrDefault(String raw, EmbankmentMaterial fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("SMOOTH_STONE".equals(up)) return STONE;
            if ("STONE_BRICK".equals(up) || "COBBLESTONE".equals(up)) return DEEPSLATE;
            try {
                return EmbankmentMaterial.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    // 5. Откосы выемок: форма (лесенка 2 бл. / плоская стена до 8 блоков, затем 1 бл.) и материал (первые 8 блоков)
    public enum SlopeStyle {
        STAIR("Лесенка (2 бл.)"),
        FLAT("Плоский (стена)");

        private final String label;
        SlopeStyle(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static SlopeStyle fromStringOrDefault(String raw, SlopeStyle fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("STEPPED_WALL".equals(up) || "SMOOTH".equals(up) || "GABION".equals(up)) return STAIR;
            try {
                return SlopeStyle.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    public enum SlopeMaterial {
        WORLD("Блоки мира"),
        STONE("Камень"),
        DEEPSLATE("Сланец (микс)"),
        DIORITE("Диорит (микс)");

        private final String label;
        SlopeMaterial(String label) { this.label = label; }
        public String getLabel() { return label; }
        public boolean isStairOnly() { return this == WORLD; }

        public static SlopeMaterial fromStringOrDefault(String raw, SlopeMaterial fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            try {
                return SlopeMaterial.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    // 6. Туннели: форма
    public enum TunnelShape {
        CIRCLE("Круглый"),
        FLAT("Плоский");

        private final String label;
        TunnelShape(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static TunnelShape fromStringOrDefault(String raw, TunnelShape fallback) {
            if (raw == null || raw.isBlank()) return fallback;
            String up = raw.trim().toUpperCase();
            if ("ARCH".equals(up)) return CIRCLE;
            if ("RECTANGULAR".equals(up) || "PORTAL".equals(up)) return FLAT;
            try {
                return TunnelShape.valueOf(up);
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
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
        ANDESITE("Андезит"),
        BRICK_GRANITE("Кирпич и гранит"),
        MUD_BRICK("Саманный кирпич"),
        DIORITE("Диорит");

        private final String label;
        CurbPalette(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    // 9. Тротуары: стиль
    public enum SidewalkPalette {
        MOSAIC("Городская мозаика"),
        WARM_BRICK("Тёплая брусчатка"),
        DEEPSLATE("Тёмный сланец"),
        STONE("Сплошной камень"),
        DIORITE("Светлый диорит");

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
    private LampStyle lampStyle = LampStyle.DEFAULT;
    private int lampSpacing = 20;
    private boolean sidewalkDoubleLamps = false;
    private LampPlacement lampPlacement = LampPlacement.BOTH_SIDES;
    private GuardrailStyle guardrailStyle = GuardrailStyle.NONE;
    private boolean noiseWallEnabled = false;

    private boolean treesEnabled = true;
    private int treeSpacing = 16;
    private boolean bushesEnabled = true;
    private int bushSpacing = 6;

    private EmbankmentShape embankmentShape = EmbankmentShape.STEPPED;
    private EmbankmentMaterial embankmentMaterial = EmbankmentMaterial.EARTH;
    private SlopeStyle slopeStyle = SlopeStyle.STAIR;
    private SlopeMaterial slopeMaterial = SlopeMaterial.WORLD;
    private TunnelShape tunnelShape = TunnelShape.CIRCLE;
    private PillarStyle pillarStyle = PillarStyle.STANDARD;

    private CurbPalette curbPalette = CurbPalette.ANDESITE;
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
        preset.lampStyle = LampStyle.DEFAULT;
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
        preset.lampStyle = LampStyle.OFF;
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
        preset.lampStyle = LampStyle.DEFAULT;
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

    public LampStyle getLampStyle() { return lampStyle != null ? lampStyle : LampStyle.DEFAULT; }
    public void setLampStyle(LampStyle lampStyle) { this.lampStyle = lampStyle; }
    public void cycleLampStyle() {
        LampStyle[] vals = LampStyle.values();
        this.lampStyle = vals[(getLampStyle().ordinal() + 1) % vals.length];
    }

    public int getLampSpacing() { return lampSpacing > 0 ? lampSpacing : 20; }
    public void setLampSpacing(int lampSpacing) { this.lampSpacing = lampSpacing; }
    public void cycleLampSpacing() {
        int[] steps = { 10, 15, 20, 25, 30 };
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == this.lampSpacing) {
                this.lampSpacing = steps[(i + 1) % steps.length];
                return;
            }
        }
        this.lampSpacing = 20;
    }

    public boolean isSidewalkDoubleLamps() { return sidewalkDoubleLamps; }
    public void setSidewalkDoubleLamps(boolean sidewalkDoubleLamps) { this.sidewalkDoubleLamps = sidewalkDoubleLamps; }
    public void toggleSidewalkDoubleLamps() { this.sidewalkDoubleLamps = !this.sidewalkDoubleLamps; }

    public LampPlacement getLampPlacement() { return lampPlacement != null ? lampPlacement : LampPlacement.BOTH_SIDES; }
    public void setLampPlacement(LampPlacement lampPlacement) { this.lampPlacement = lampPlacement; }
    public void cycleLampPlacement() {
        LampPlacement[] vals = LampPlacement.values();
        this.lampPlacement = vals[(getLampPlacement().ordinal() + 1) % vals.length];
    }

    public GuardrailStyle getGuardrailStyle() { return guardrailStyle != null ? guardrailStyle : GuardrailStyle.NONE; }
    public void setGuardrailStyle(GuardrailStyle guardrailStyle) { this.guardrailStyle = guardrailStyle; }
    public void cycleGuardrailStyle() {
        GuardrailStyle[] vals = GuardrailStyle.values();
        this.guardrailStyle = vals[(getGuardrailStyle().ordinal() + 1) % vals.length];
    }

    public boolean isNoiseWallEnabled() { return noiseWallEnabled; }
    public void setNoiseWallEnabled(boolean noiseWallEnabled) { this.noiseWallEnabled = noiseWallEnabled; }
    public void toggleNoiseWall() { this.noiseWallEnabled = !this.noiseWallEnabled; }

    public boolean hasAnyElementGuardrail() {
        if (stack == null) return false;
        for (RoadStackElement el : stack) {
            if (el.getGuardrail() != GuardrailPlacement.NONE) return true;
        }
        return false;
    }

    public void applyDefaultGuardrailPlacementIfEmpty() {
        if (stack == null || stack.isEmpty() || hasAnyElementGuardrail()) return;
        boolean placedDivider = false;
        for (RoadStackElement el : stack) {
            if (el.getType() == RoadElementType.DIVIDER_GRASS) {
                el.setGuardrail(GuardrailPlacement.BOTH);
                placedDivider = true;
            }
        }
        if (placedDivider) return;

        int firstIdx = -1;
        int lastIdx = -1;
        for (int i = 0; i < stack.size(); i++) {
            RoadElementType t = stack.get(i).getType();
            if (t == RoadElementType.SIDEWALK || t == RoadElementType.SHOULDER) {
                if (firstIdx < 0) firstIdx = i;
                lastIdx = i;
            }
        }
        if (firstIdx >= 0) {
            stack.get(firstIdx).setGuardrail(GuardrailPlacement.OUTER);
            if (lastIdx != firstIdx) {
                stack.get(lastIdx).setGuardrail(GuardrailPlacement.OUTER);
            }
        }
    }

    public String getGuardrailSummaryLabel() {
        if (stack == null || stack.isEmpty()) return GuardrailStyle.NONE.getLabel();
        GuardrailStyle single = null;
        for (RoadStackElement el : stack) {
            if (el.getGuardrail() == GuardrailPlacement.NONE) continue;
            GuardrailStyle s = el.resolveGuardrailStyle(this);
            if (s == GuardrailStyle.NONE) continue;
            if (single == null) {
                single = s;
            } else if (single != s) {
                return "Раздельно";
            }
        }
        return (single != null) ? single.getLabel() : GuardrailStyle.NONE.getLabel();
    }

    public static GuardrailPlacement defaultGuardrailPlacementFor(RoadElementType type) {
        if (type == RoadElementType.DIVIDER_GRASS) return GuardrailPlacement.BOTH;
        if (type == RoadElementType.SIDEWALK || type == RoadElementType.SHOULDER) return GuardrailPlacement.OUTER;
        return GuardrailPlacement.CENTER;
    }

    public void cycleAllElementGuardrailStyles() {
        GuardrailStyle cur = null;
        boolean mixed = false;
        if (stack != null) {
            for (RoadStackElement el : stack) {
                if (el.getGuardrail() == GuardrailPlacement.NONE) continue;
                GuardrailStyle s = el.resolveGuardrailStyle(this);
                if (s == GuardrailStyle.NONE) continue;
                if (cur == null) cur = s;
                else if (cur != s) { mixed = true; break; }
            }
        }
        GuardrailStyle[] vals = GuardrailStyle.values();
        GuardrailStyle next;
        if (mixed) {
            next = GuardrailStyle.IRON;
        } else {
            GuardrailStyle base = (cur != null) ? cur : GuardrailStyle.NONE;
            next = vals[(base.ordinal() + 1) % vals.length];
        }
        this.guardrailStyle = next;
        if (stack == null) return;
        if (next != GuardrailStyle.NONE) {
            applyDefaultGuardrailPlacementIfEmpty();
            for (RoadStackElement el : stack) {
                if (el.getGuardrail() != GuardrailPlacement.NONE) {
                    el.setGuardrailStyle(next);
                }
            }
        } else {
            for (RoadStackElement el : stack) {
                el.setGuardrail(GuardrailPlacement.NONE);
                el.setGuardrailStyle(GuardrailStyle.NONE);
            }
        }
    }

    public String serializeElementGuardrails() {
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append(',');
            RoadStackElement el = stack.get(i);
            GuardrailPlacement gp = el.getGuardrail();
            GuardrailStyle gs = el.resolveGuardrailStyle(this);
            sb.append(gp.name()).append(':').append(gs.name());
        }
        return sb.toString();
    }

    public void applySerializedElementGuardrails(String encoded) {
        if (stack == null || encoded == null || encoded.isBlank()) return;
        String[] parts = encoded.split(",", -1);
        for (int i = 0; i < stack.size() && i < parts.length; i++) {
            String raw = parts[i].trim();
            int colon = raw.indexOf(':');
            if (colon >= 0) {
                GuardrailPlacement gp = GuardrailPlacement.fromStringOrDefault(raw.substring(0, colon), GuardrailPlacement.NONE);
                GuardrailStyle gs = GuardrailStyle.fromStringOrDefault(raw.substring(colon + 1), GuardrailStyle.NONE);
                stack.get(i).setGuardrail(gp);
                stack.get(i).setGuardrailStyle(gs);
            } else {
                GuardrailPlacement gp = GuardrailPlacement.fromStringOrDefault(raw, GuardrailPlacement.NONE);
                stack.get(i).setGuardrail(gp);
                stack.get(i).setGuardrailStyle(gp != GuardrailPlacement.NONE ? getGuardrailStyle() : GuardrailStyle.NONE);
            }
        }
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

    public boolean hasAnyElementGreenery() {
        if (stack == null) return false;
        for (RoadStackElement el : stack) {
            if (el.getGreenery() != GuardrailPlacement.NONE && el.getGreeneryStyle() != GreeneryStyle.NONE) {
                return true;
            }
        }
        return false;
    }

    public static GuardrailPlacement defaultGreeneryPlacementFor(RoadElementType type) {
        if (type == RoadElementType.LAWN || type == RoadElementType.DIVIDER_GRASS || type == RoadElementType.SHOULDER) {
            return GuardrailPlacement.CENTER;
        }
        if (type == RoadElementType.SIDEWALK) {
            return GuardrailPlacement.OUTER;
        }
        return GuardrailPlacement.NONE;
    }

    public String getGreenerySummaryLabel() {
        if (stack == null || stack.isEmpty()) return GreeneryStyle.NONE.getLabel();
        GreeneryStyle single = null;
        for (RoadStackElement el : stack) {
            if (el.getGreenery() == GuardrailPlacement.NONE) continue;
            GreeneryStyle s = el.getGreeneryStyle();
            if (s == GreeneryStyle.NONE || !s.isAllowedFor(el.getType())) continue;
            if (single == null) {
                single = s;
            } else if (single != s) {
                return "Раздельно";
            }
        }
        return (single != null) ? single.getLabel() : GreeneryStyle.NONE.getLabel();
    }

    public void cycleAllElementGreeneryStyles() {
        GreeneryStyle cur = null;
        boolean mixed = false;
        if (stack != null) {
            for (RoadStackElement el : stack) {
                if (el.getGreenery() == GuardrailPlacement.NONE) continue;
                GreeneryStyle s = el.getGreeneryStyle();
                if (s == GreeneryStyle.NONE || !s.isAllowedFor(el.getType())) continue;
                if (cur == null) cur = s;
                else if (cur != s) { mixed = true; break; }
            }
        }
        GreeneryStyle[] vals = GreeneryStyle.values();
        GreeneryStyle next;
        if (mixed) {
            next = GreeneryStyle.OAK;
        } else {
            GreeneryStyle base = (cur != null) ? cur : GreeneryStyle.NONE;
            next = vals[(base.ordinal() + 1) % vals.length];
        }
        if (stack == null) return;
        if (next == GreeneryStyle.NONE) {
            for (RoadStackElement el : stack) {
                el.setGreenery(GuardrailPlacement.NONE);
                el.setGreeneryStyle(GreeneryStyle.NONE);
            }
            return;
        }
        if (!hasAnyElementGreenery()) {
            // Auto-pick default compatible slots (prefer LAWN/DIVIDER_GRASS, else outer SIDEWALK)
            boolean placedGrass = false;
            for (RoadStackElement el : stack) {
                if ((el.getType() == RoadElementType.LAWN || el.getType() == RoadElementType.DIVIDER_GRASS) && next.isAllowedFor(el.getType())) {
                    el.setGreenery(GuardrailPlacement.CENTER);
                    el.setGreeneryStyle(next);
                    placedGrass = true;
                }
            }
            if (!placedGrass) {
                int firstIdx = -1;
                int lastIdx = -1;
                for (int i = 0; i < stack.size(); i++) {
                    RoadElementType t = stack.get(i).getType();
                    if (next.isAllowedFor(t)) {
                        if (firstIdx < 0) firstIdx = i;
                        lastIdx = i;
                    }
                }
                if (firstIdx >= 0) {
                    RoadStackElement first = stack.get(firstIdx);
                    first.setGreenery(defaultGreeneryPlacementFor(first.getType()));
                    first.setGreeneryStyle(next);
                    if (lastIdx != firstIdx) {
                        RoadStackElement last = stack.get(lastIdx);
                        last.setGreenery(defaultGreeneryPlacementFor(last.getType()));
                        last.setGreeneryStyle(next);
                    }
                }
            }
        } else {
            for (RoadStackElement el : stack) {
                if (el.getGreenery() != GuardrailPlacement.NONE && next.isAllowedFor(el.getType())) {
                    el.setGreeneryStyle(next);
                }
            }
        }
    }

    public String serializeElementGreenery() {
        if (stack == null || stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append(',');
            RoadStackElement el = stack.get(i);
            GuardrailPlacement gp = el.getGreenery();
            GreeneryStyle gs = el.getGreeneryStyle();
            sb.append(gp.name()).append(':').append(gs.name());
        }
        return sb.toString();
    }

    public void applySerializedElementGreenery(String encoded) {
        if (stack == null || encoded == null || encoded.isBlank()) return;
        String[] parts = encoded.split(",", -1);
        for (int i = 0; i < stack.size() && i < parts.length; i++) {
            String raw = parts[i].trim();
            int colon = raw.indexOf(':');
            if (colon >= 0) {
                GuardrailPlacement gp = GuardrailPlacement.fromStringOrDefault(raw.substring(0, colon), GuardrailPlacement.NONE);
                GreeneryStyle gs = GreeneryStyle.fromStringOrDefault(raw.substring(colon + 1), GreeneryStyle.NONE);
                RoadElementType type = stack.get(i).getType();
                if (!gs.isAllowedFor(type)) {
                    gs = GreeneryStyle.NONE;
                }
                if (gs == GreeneryStyle.NONE) {
                    gp = GuardrailPlacement.NONE;
                }
                stack.get(i).setGreenery(gp);
                stack.get(i).setGreeneryStyle(gs);
            }
        }
    }

    public EmbankmentShape getEmbankmentShape() { return embankmentShape != null ? embankmentShape : EmbankmentShape.STEPPED; }
    public void setEmbankmentShape(EmbankmentShape embankmentShape) { this.embankmentShape = embankmentShape; }
    public void cycleEmbankmentShape() {
        EmbankmentShape prev = getEmbankmentShape();
        EmbankmentShape[] vals = EmbankmentShape.values();
        EmbankmentShape next = vals[(prev.ordinal() + 1) % vals.length];
        this.embankmentShape = next;
        if (next == EmbankmentShape.WALL) {
            if (this.embankmentMaterial == null || !this.embankmentMaterial.isWallMaterial()) {
                this.embankmentMaterial = EmbankmentMaterial.STONE;
            }
        } else if (prev == EmbankmentShape.WALL || this.embankmentMaterial == null || !this.embankmentMaterial.isSteppedMaterial()) {
            this.embankmentMaterial = (next == EmbankmentShape.STEPPED_ONE)
                    ? EmbankmentMaterial.CRUSHED_STONE
                    : EmbankmentMaterial.EARTH;
        }
    }

    public EmbankmentMaterial getEmbankmentMaterial() {
        EmbankmentShape shape = getEmbankmentShape();
        EmbankmentMaterial m = (embankmentMaterial != null) ? embankmentMaterial : EmbankmentMaterial.EARTH;
        if (shape == EmbankmentShape.WALL) {
            return m.isWallMaterial() ? m : EmbankmentMaterial.STONE;
        }
        return m.isSteppedMaterial() ? m : EmbankmentMaterial.EARTH;
    }

    public void setEmbankmentMaterial(EmbankmentMaterial embankmentMaterial) {
        this.embankmentMaterial = embankmentMaterial;
    }

    public void cycleEmbankmentMaterial() {
        EmbankmentShape shape = getEmbankmentShape();
        EmbankmentMaterial cur = getEmbankmentMaterial();
        if (shape == EmbankmentShape.WALL) {
            EmbankmentMaterial[] wallMats = { EmbankmentMaterial.STONE, EmbankmentMaterial.DEEPSLATE, EmbankmentMaterial.DIORITE };
            int idx = 0;
            for (int i = 0; i < wallMats.length; i++) {
                if (wallMats[i] == cur) { idx = (i + 1) % wallMats.length; break; }
            }
            this.embankmentMaterial = wallMats[idx];
        } else {
            this.embankmentMaterial = (cur == EmbankmentMaterial.EARTH)
                    ? EmbankmentMaterial.CRUSHED_STONE
                    : EmbankmentMaterial.EARTH;
        }
    }

    public void enforceValidEmbankment() {
        this.embankmentMaterial = getEmbankmentMaterial();
    }

    public SlopeStyle getSlopeStyle() { return slopeStyle != null ? slopeStyle : SlopeStyle.STAIR; }
    public void setSlopeStyle(SlopeStyle slopeStyle) { this.slopeStyle = slopeStyle; }
    public void cycleSlopeStyle() {
        SlopeStyle[] vals = SlopeStyle.values();
        SlopeStyle next = vals[(getSlopeStyle().ordinal() + 1) % vals.length];
        this.slopeStyle = next;
        if (next == SlopeStyle.FLAT && (this.slopeMaterial == null || this.slopeMaterial.isStairOnly())) {
            this.slopeMaterial = SlopeMaterial.STONE;
        }
    }

    public SlopeMaterial getSlopeMaterial() {
        SlopeStyle style = getSlopeStyle();
        SlopeMaterial m = (slopeMaterial != null) ? slopeMaterial : SlopeMaterial.WORLD;
        if (style == SlopeStyle.FLAT && m.isStairOnly()) {
            return SlopeMaterial.STONE;
        }
        return m;
    }

    public void setSlopeMaterial(SlopeMaterial slopeMaterial) {
        this.slopeMaterial = slopeMaterial;
    }

    public void cycleSlopeMaterial() {
        SlopeStyle style = getSlopeStyle();
        SlopeMaterial cur = getSlopeMaterial();
        SlopeMaterial[] allowed = (style == SlopeStyle.FLAT)
                ? new SlopeMaterial[]{ SlopeMaterial.STONE, SlopeMaterial.DEEPSLATE, SlopeMaterial.DIORITE }
                : new SlopeMaterial[]{ SlopeMaterial.WORLD, SlopeMaterial.STONE, SlopeMaterial.DEEPSLATE, SlopeMaterial.DIORITE };
        int idx = 0;
        for (int i = 0; i < allowed.length; i++) {
            if (allowed[i] == cur) {
                idx = (i + 1) % allowed.length;
                break;
            }
        }
        this.slopeMaterial = allowed[idx];
    }

    public void enforceValidSlope() {
        this.slopeMaterial = getSlopeMaterial();
    }

    public TunnelShape getTunnelShape() { return tunnelShape != null ? tunnelShape : TunnelShape.CIRCLE; }
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

    public CurbPalette getCurbPalette() { return curbPalette != null ? curbPalette : CurbPalette.ANDESITE; }
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
        copy.sidewalkDoubleLamps = this.sidewalkDoubleLamps;
        copy.lampPlacement = this.lampPlacement;
        copy.guardrailStyle = this.guardrailStyle;
        copy.noiseWallEnabled = this.noiseWallEnabled;
        copy.treesEnabled = this.treesEnabled;
        copy.treeSpacing = this.treeSpacing;
        copy.bushesEnabled = this.bushesEnabled;
        copy.bushSpacing = this.bushSpacing;
        copy.embankmentShape = this.embankmentShape;
        copy.embankmentMaterial = this.embankmentMaterial;
        copy.slopeStyle = this.slopeStyle;
        copy.slopeMaterial = this.slopeMaterial;
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
