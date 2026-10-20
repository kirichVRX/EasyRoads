package com.roadbuilder.mod.client;

import com.roadbuilder.mod.client.editmode.ClientInputHandler;
import com.roadbuilder.mod.client.gui.RoadEditorOverlay;
import com.roadbuilder.mod.client.key.ModKeyMappings;
import com.roadbuilder.mod.client.renderer.GizmoRenderer;
import com.roadbuilder.mod.core.RoadBuilderMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side setup and event registration.
 * Rule 2: Single registration point for client events.
 * Rule 3: Strict side separation - NEVER called on a dedicated server.
 */
public final class RoadBuilderClient {

    public static final ResourceLocation OVERLAY_ID = ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "editor_overlay");

    private RoadBuilderClient() {}

    public static void init(IEventBus modEventBus) {
        RoadBuilderMod.LOGGER.info("[RoadBuilder] Initializing Client Subsystems (Phase 4: Client Editor)...");

        // Register key bindings and GUI layers on modEventBus (Rule 2)
        modEventBus.addListener(RoadBuilderClient::onRegisterKeyMappings);
        modEventBus.addListener(RoadBuilderClient::onRegisterGuiLayers);

        // Register gameplay client events on NeoForge.EVENT_BUS
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onKeyInput);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(ClientInputHandler::onMouseButton);
        NeoForge.EVENT_BUS.addListener(GizmoRenderer::onRenderLevelStage);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Client Subsystems initialized.");
    }

    private static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        ModKeyMappings.register(event);
    }

    private static void onRegisterGuiLayers(final RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, OVERLAY_ID, (guiGraphics, partialTick) -> {
            RoadEditorOverlay.render(guiGraphics, partialTick);
        });
    }
}
