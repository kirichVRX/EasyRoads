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

    private String id;
    private String name;
    private List<RoadStackElement> stack;

    // Generation settings (Settings panel in GUI)
    private Mode viaductMode;
    private Mode tunnelMode;
    private ToggleMode slopeMode;
    private ToggleMode embankmentMode;

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

    public static ModularRoadPreset createSingleLane() {
        ModularRoadPreset preset = new ModularRoadPreset("single_lane", "Rural 1-lane");
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 5)); // 5 blocks for single lane!
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
        return preset;
    }

    public static ModularRoadPreset createHighway() {
        ModularRoadPreset preset = new ModularRoadPreset("highway_4lane", "Highway 4-lane");
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_DASHED_SHORT, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.DIVIDER_GRASS, 5));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.MARKING_DASHED_SHORT, 1));
        preset.stack.add(new RoadStackElement(RoadElementType.LANE, 4));
        preset.stack.add(new RoadStackElement(RoadElementType.SHOULDER, 3));
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
        } else if ("single_lane".equals(id)) {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.preset.single_lane").getString();
        } else if ("highway_4lane".equals(id)) {
            return net.minecraft.network.chat.Component.translatable("roadbuilder.preset.highway_4lane").getString();
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

    public ToggleMode getEmbankmentMode() { return embankmentMode; }
    public void setEmbankmentMode(ToggleMode embankmentMode) { this.embankmentMode = embankmentMode; }

    public int[] getPillarPositions() {
        int totalWidth = getTotalWidth();
        if (totalWidth < 18) {
            return new int[]{ (totalWidth - 7) / 2 };
        } else if (totalWidth < 28) {
            int px1 = Math.max(0, Math.min((int) Math.round(totalWidth * 0.25 - 3.5), totalWidth / 2 - 7));
            int px2 = Math.max(totalWidth / 2, Math.min((int) Math.round(totalWidth * 0.75 - 3.5), totalWidth - 7));
            return new int[]{ px1, px2 };
        } else {
            int px1 = Math.max(0, (int) Math.round(totalWidth * (1.0 / 6.0) - 3.5));
            int px2 = (totalWidth - 7) / 2;
            int px3 = Math.min(totalWidth - 7, (int) Math.round(totalWidth * (5.0 / 6.0) - 3.5));
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
        return copy;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static ModularRoadPreset fromJson(String json) {
        return GSON.fromJson(json, ModularRoadPreset.class);
    }
}
