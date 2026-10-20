package com.roadbuilder.mod.client.key;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * Client key mappings.
 * Rule 2: Single source of registration for key bindings.
 */
public final class ModKeyMappings {

    private ModKeyMappings() {}

    public static final KeyMapping TOGGLE_EDIT_MODE = new KeyMapping(
            "key.roadbuilder.toggle_edit_mode",
            InputConstants.KEY_F7,
            "key.categories.roadbuilder"
    );

    public static final KeyMapping FINISH_ROAD = new KeyMapping(
            "key.roadbuilder.finish_road",
            InputConstants.KEY_RETURN,
            "key.categories.roadbuilder"
    );

    public static final KeyMapping OPEN_PRESET_SCREEN = new KeyMapping(
            "key.roadbuilder.open_preset_screen",
            InputConstants.KEY_CAPSLOCK,
            "key.categories.roadbuilder"
    );

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_EDIT_MODE);
        event.register(FINISH_ROAD);
        event.register(OPEN_PRESET_SCREEN);
    }
}
