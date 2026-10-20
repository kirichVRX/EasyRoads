package com.roadbuilder.mod.network;

import com.roadbuilder.mod.client.network.ClientPayloadHandler;
import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.network.handler.ServerPayloadHandler;
import com.roadbuilder.mod.network.payload.C2S_RequestBuildSegmentPayload;
import com.roadbuilder.mod.network.payload.S2C_SyncFullGraphPayload;
import com.roadbuilder.mod.network.payload.S2C_SyncRoadSegmentPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Centralized network registration pipeline.
 * Rule 2: Single source of registration for payload handlers.
 */
public final class ModNetwork {

    private ModNetwork() {}

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ModNetwork::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent event) {
        RoadBuilderMod.LOGGER.info("[RoadBuilder] Registering network payloads...");

        final PayloadRegistrar registrar = event.registrar(RoadBuilderMod.MOD_ID).versioned("1.0.0");

        // C2S
        registrar.playToServer(
                C2S_RequestBuildSegmentPayload.TYPE,
                C2S_RequestBuildSegmentPayload.STREAM_CODEC,
                ServerPayloadHandler::handleBuildSegment
        );

        // S2C
        registrar.playToClient(
                S2C_SyncRoadSegmentPayload.TYPE,
                S2C_SyncRoadSegmentPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) {
                        ClientPayloadHandler.handleSyncSegment(payload);
                    }
                })
        );

        registrar.playToClient(
                S2C_SyncFullGraphPayload.TYPE,
                S2C_SyncFullGraphPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) {
                        ClientPayloadHandler.handleSyncFullGraph(payload);
                    }
                })
        );

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Network payloads registered successfully.");
    }
}
