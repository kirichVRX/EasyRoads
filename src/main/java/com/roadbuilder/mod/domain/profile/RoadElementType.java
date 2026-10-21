package com.roadbuilder.mod.domain.profile;

import com.google.gson.annotations.SerializedName;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Type of road element in the profile stack.
 */
public enum RoadElementType {
    @SerializedName("sidewalk")
    SIDEWALK(4),

    @SerializedName("curb")
    CURB(1),

    @SerializedName("lane")
    LANE(4),

    @SerializedName("marking_dashed_long")
    MARKING_DASHED_LONG(1),

    @SerializedName("marking_dashed_short")
    MARKING_DASHED_SHORT(1),

    @SerializedName("marking_solid")
    MARKING_SOLID(1),

    @SerializedName("marking_double_solid")
    MARKING_DOUBLE_SOLID(3),

    @SerializedName("divider_grass")
    DIVIDER_GRASS(5),

    @SerializedName("shoulder")
    SHOULDER(3);

    private final int defaultWidth;

    RoadElementType(int defaultWidth) {
        this.defaultWidth = defaultWidth;
    }

    public String getTranslationKey() {
        return "roadbuilder.element." + name().toLowerCase(Locale.ROOT);
    }

    public Component getDisplayNameComponent() {
        return Component.translatable(getTranslationKey());
    }

    public String getDisplayName() {
        return getDisplayNameComponent().getString();
    }

    public int getDefaultWidth() {
        return defaultWidth;
    }
}
