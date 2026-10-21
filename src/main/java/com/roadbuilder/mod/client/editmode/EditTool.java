package com.roadbuilder.mod.client.editmode;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Available tools in the Road Builder editor mode.
 */
public enum EditTool {
    ADD_POINT("add"),
    SELECT_POINT("select"),
    ADJUST_POINT("adjust"),
    DELETE_POINT("delete");

    private final String iconName;
    private final ResourceLocation iconLocation;

    EditTool(String iconName) {
        this.iconName = iconName;
        this.iconLocation = ResourceLocation.fromNamespaceAndPath(
                com.roadbuilder.mod.core.RoadBuilderMod.MOD_ID,
                "textures/gui/tools/" + iconName + ".png"
        );
    }

    public String getTranslationKey() {
        return "roadbuilder.tool." + iconName;
    }

    public String getDescriptionKey() {
        return "roadbuilder.tool." + iconName + ".desc";
    }

    public Component getDisplayName() {
        return Component.translatable(getTranslationKey());
    }

    public Component getDescriptionComponent() {
        return Component.translatable(getDescriptionKey());
    }

    public String getLabel() {
        return getDisplayName().getString();
    }

    public String getDescription() {
        return getDescriptionComponent().getString();
    }

    public String getIconName() {
        return iconName;
    }

    public ResourceLocation getIconLocation() {
        return iconLocation;
    }
}
