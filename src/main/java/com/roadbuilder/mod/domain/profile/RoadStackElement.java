package com.roadbuilder.mod.domain.profile;

import java.util.Objects;

/**
 * Represents a single configured slot/component in the horizontal cross-section of a road.
 */
public class RoadStackElement {
    private RoadElementType type;
    private int customWidth; // 0 means use default width from type

    public RoadStackElement() {
        this(RoadElementType.LANE);
    }

    public RoadStackElement(RoadElementType type) {
        this.type = type;
        this.customWidth = type.getDefaultWidth();
    }

    public RoadStackElement(RoadElementType type, int customWidth) {
        this.type = type;
        this.customWidth = customWidth > 0 ? customWidth : type.getDefaultWidth();
    }

    public RoadElementType getType() {
        return type;
    }

    public void setType(RoadElementType type) {
        this.type = type;
        this.customWidth = type.getDefaultWidth();
    }

    public int getWidth() {
        if (type == RoadElementType.DIVIDER_GRASS && customWidth < 5) {
            return 5;
        }
        if (type == RoadElementType.SHOULDER && customWidth < 3) {
            return 3;
        }
        if (type == RoadElementType.MARKING_DOUBLE_SOLID && customWidth < 3) {
            return 3;
        }
        if (type == RoadElementType.SIDEWALK && customWidth < 4) {
            return 4;
        }
        return customWidth > 0 ? customWidth : type.getDefaultWidth();
    }

    public void setWidth(int width) {
        this.customWidth = Math.max(1, width);
    }

    public RoadStackElement copy() {
        return new RoadStackElement(this.type, this.customWidth);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadStackElement that = (RoadStackElement) o;
        return customWidth == that.customWidth && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, customWidth);
    }
}
