package com.roadbuilder.mod.client.editmode;

/**
 * Available tools in the Road Builder editor mode.
 */
public enum EditTool {
    ADD_POINT("Добавить", "Ставит новые опорные точки трассы (ЛКМ)", "add"),
    SELECT_POINT("Выбрать", "Выбирает точку для перемещения или редактирования", "select"),
    ADJUST_POINT("Двигать", "Перемещает точку: колесо мыши Y, стрелочки или WASD по XZ", "adjust"),
    DELETE_POINT("Удалить", "Удаляет выбранную точку из трассы (ЛКМ)", "delete");

    private final String label;
    private final String description;
    private final String iconName;
    private final net.minecraft.resources.ResourceLocation iconLocation;

    EditTool(String label, String description, String iconName) {
        this.label = label;
        this.description = description;
        this.iconName = iconName;
        this.iconLocation = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                com.roadbuilder.mod.core.RoadBuilderMod.MOD_ID,
                "textures/gui/tools/" + iconName + ".png"
        );
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public String getIconName() {
        return iconName;
    }

    public net.minecraft.resources.ResourceLocation getIconLocation() {
        return iconLocation;
    }
}
