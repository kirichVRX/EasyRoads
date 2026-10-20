package com.roadbuilder.mod.domain.profile;

import com.google.gson.annotations.SerializedName;

/**
 * Type of road element in the profile stack.
 */
public enum RoadElementType {
    @SerializedName("sidewalk")
    SIDEWALK("Тротуар", 4),

    @SerializedName("curb")
    CURB("Бордюр", 1),

    @SerializedName("lane")
    LANE("Полоса", 4),

    @SerializedName("marking_dashed_long")
    MARKING_DASHED_LONG("Разметка (длинная)", 1),

    @SerializedName("marking_dashed_short")
    MARKING_DASHED_SHORT("Разметка (короткая)", 1),

    @SerializedName("marking_solid")
    MARKING_SOLID("Разметка (сплошная)", 1),

    @SerializedName("marking_double_solid")
    MARKING_DOUBLE_SOLID("Разметка (двойная)", 3),

    @SerializedName("divider_grass")
    DIVIDER_GRASS("Газон/Разделитель", 5),

    @SerializedName("shoulder")
    SHOULDER("Обочина", 3);

    private final String displayName;
    private final int defaultWidth;

    RoadElementType(String displayName, int defaultWidth) {
        this.displayName = displayName;
        this.defaultWidth = defaultWidth;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultWidth() {
        return defaultWidth;
    }
}
