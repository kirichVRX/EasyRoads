package com.roadbuilder.mod.domain.profile;

import java.util.Objects;

/**
 * Represents a single configured slot/component in the horizontal cross-section of a road.
 */
public class RoadStackElement {
    private RoadElementType type;
    private int customWidth; // 0 means use default width from type
    private ModularRoadPreset.GuardrailPlacement guardrail = ModularRoadPreset.GuardrailPlacement.NONE;
    private ModularRoadPreset.GuardrailStyle guardrailStyle = null; // null = inherit preset fallback (legacy saves)
    private ModularRoadPreset.GuardrailPlacement greenery = ModularRoadPreset.GuardrailPlacement.NONE;
    private ModularRoadPreset.GreeneryStyle greeneryStyle = ModularRoadPreset.GreeneryStyle.NONE;

    public RoadStackElement() {
        this(RoadElementType.LANE);
    }

    public RoadStackElement(RoadElementType type) {
        this.type = type;
        this.customWidth = type.getDefaultWidth();
        this.guardrail = ModularRoadPreset.GuardrailPlacement.NONE;
        this.guardrailStyle = null;
        this.greenery = ModularRoadPreset.GuardrailPlacement.NONE;
        this.greeneryStyle = ModularRoadPreset.GreeneryStyle.NONE;
    }

    public RoadStackElement(RoadElementType type, int customWidth) {
        this(type, customWidth, ModularRoadPreset.GuardrailPlacement.NONE, null,
                ModularRoadPreset.GuardrailPlacement.NONE, ModularRoadPreset.GreeneryStyle.NONE);
    }

    public RoadStackElement(RoadElementType type, int customWidth, ModularRoadPreset.GuardrailPlacement guardrail) {
        this(type, customWidth, guardrail, null,
                ModularRoadPreset.GuardrailPlacement.NONE, ModularRoadPreset.GreeneryStyle.NONE);
    }

    public RoadStackElement(
            RoadElementType type,
            int customWidth,
            ModularRoadPreset.GuardrailPlacement guardrail,
            ModularRoadPreset.GuardrailStyle guardrailStyle
    ) {
        this(type, customWidth, guardrail, guardrailStyle,
                ModularRoadPreset.GuardrailPlacement.NONE, ModularRoadPreset.GreeneryStyle.NONE);
    }

    public RoadStackElement(
            RoadElementType type,
            int customWidth,
            ModularRoadPreset.GuardrailPlacement guardrail,
            ModularRoadPreset.GuardrailStyle guardrailStyle,
            ModularRoadPreset.GuardrailPlacement greenery,
            ModularRoadPreset.GreeneryStyle greeneryStyle
    ) {
        this.type = type;
        this.customWidth = customWidth > 0 ? customWidth : type.getDefaultWidth();
        this.guardrail = (guardrail != null) ? guardrail : ModularRoadPreset.GuardrailPlacement.NONE;
        this.guardrailStyle = guardrailStyle;
        this.greenery = (greenery != null) ? greenery : ModularRoadPreset.GuardrailPlacement.NONE;
        this.greeneryStyle = (greeneryStyle != null) ? greeneryStyle : ModularRoadPreset.GreeneryStyle.NONE;
    }

    public RoadElementType getType() {
        return type;
    }

    public void setType(RoadElementType type) {
        this.type = type;
        this.customWidth = type.getDefaultWidth();
    }

    public int getWidth() {
        if (type == RoadElementType.CURB) {
            return customWidth > 0 ? customWidth : 1;
        }
        if (type == RoadElementType.DIVIDER_GRASS && customWidth < 5) {
            return 5;
        }
        if (type == RoadElementType.SHOULDER && customWidth < 3) {
            return 3;
        }
        if (type == RoadElementType.MARKING_DOUBLE_SOLID && customWidth < 3) {
            return 3;
        }
        return customWidth > 0 ? customWidth : type.getDefaultWidth();
    }

    public void setWidth(int width) {
        this.customWidth = Math.max(1, width);
    }

    public ModularRoadPreset.GuardrailPlacement getGuardrail() {
        return guardrail != null ? guardrail : ModularRoadPreset.GuardrailPlacement.NONE;
    }

    public void setGuardrail(ModularRoadPreset.GuardrailPlacement guardrail) {
        this.guardrail = (guardrail != null) ? guardrail : ModularRoadPreset.GuardrailPlacement.NONE;
    }

    public ModularRoadPreset.GuardrailStyle getRawGuardrailStyle() {
        return guardrailStyle;
    }

    public ModularRoadPreset.GuardrailStyle getGuardrailStyle() {
        return guardrailStyle != null ? guardrailStyle : ModularRoadPreset.GuardrailStyle.NONE;
    }

    public ModularRoadPreset.GuardrailStyle resolveGuardrailStyle(ModularRoadPreset fallbackPreset) {
        if (this.guardrailStyle != null) {
            return this.guardrailStyle;
        }
        return (fallbackPreset != null) ? fallbackPreset.getGuardrailStyle() : ModularRoadPreset.GuardrailStyle.NONE;
    }

    public void setGuardrailStyle(ModularRoadPreset.GuardrailStyle guardrailStyle) {
        this.guardrailStyle = guardrailStyle;
    }

    public ModularRoadPreset.GuardrailStyle cycleGuardrailStyle(ModularRoadPreset fallbackPreset) {
        ModularRoadPreset.GuardrailStyle cur = resolveGuardrailStyle(fallbackPreset);
        ModularRoadPreset.GuardrailStyle[] vals = ModularRoadPreset.GuardrailStyle.values();
        ModularRoadPreset.GuardrailStyle next = vals[(cur.ordinal() + 1) % vals.length];
        this.guardrailStyle = next;
        return next;
    }

    public ModularRoadPreset.GuardrailPlacement getGreenery() {
        return greenery != null ? greenery : ModularRoadPreset.GuardrailPlacement.NONE;
    }

    public void setGreenery(ModularRoadPreset.GuardrailPlacement greenery) {
        this.greenery = (greenery != null) ? greenery : ModularRoadPreset.GuardrailPlacement.NONE;
    }

    public ModularRoadPreset.GreeneryStyle getGreeneryStyle() {
        return greeneryStyle != null ? greeneryStyle : ModularRoadPreset.GreeneryStyle.NONE;
    }

    public void setGreeneryStyle(ModularRoadPreset.GreeneryStyle greeneryStyle) {
        this.greeneryStyle = (greeneryStyle != null) ? greeneryStyle : ModularRoadPreset.GreeneryStyle.NONE;
    }

    public ModularRoadPreset.GreeneryStyle cycleGreeneryStyle() {
        ModularRoadPreset.GreeneryStyle cur = getGreeneryStyle();
        ModularRoadPreset.GreeneryStyle[] vals = ModularRoadPreset.GreeneryStyle.values();
        for (int step = 1; step <= vals.length; step++) {
            ModularRoadPreset.GreeneryStyle cand = vals[(cur.ordinal() + step) % vals.length];
            if (cand.isAllowedFor(this.type)) {
                this.greeneryStyle = cand;
                return cand;
            }
        }
        this.greeneryStyle = ModularRoadPreset.GreeneryStyle.NONE;
        return this.greeneryStyle;
    }

    public RoadStackElement copy() {
        return new RoadStackElement(this.type, this.customWidth, this.getGuardrail(), this.guardrailStyle,
                this.getGreenery(), this.getGreeneryStyle());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadStackElement that = (RoadStackElement) o;
        return customWidth == that.customWidth && type == that.type
                && getGuardrail() == that.getGuardrail()
                && getGuardrailStyle() == that.getGuardrailStyle()
                && getGreenery() == that.getGreenery()
                && getGreeneryStyle() == that.getGreeneryStyle();
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, customWidth, getGuardrail(), getGuardrailStyle(), getGreenery(), getGreeneryStyle());
    }
}
