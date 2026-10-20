package com.roadbuilder.mod.core;

import com.mojang.logging.LogUtils;
import com.roadbuilder.mod.network.ModNetwork;
import com.roadbuilder.mod.server.command.RoadCommands;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Main entry point for Road Builder mod.
 * Single point of registration on modEventBus (Rule 2).
 * Single MOD_ID constant (Rule 11).
 */
@Mod(RoadBuilderMod.MOD_ID)
public final class RoadBuilderMod {
    public static final String MOD_ID = "roadbuilder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RoadBuilderMod(IEventBus modEventBus) {
        LOGGER.info("[RoadBuilder] Initializing Road Builder Mod...");

        // Phase 2: Network Pipeline
        ModNetwork.register(modEventBus);

        // Phase 3: Server Commands
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);

        // Phase 5: Player Login sync
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);

        // Phase 4: Client Subsystems (Rule 3: strict side separation)
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            com.roadbuilder.mod.client.RoadBuilderClient.init(modEventBus);
        }

        modEventBus.addListener(this::commonSetup);
    }

    private void onPlayerLoggedIn(final net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            com.roadbuilder.mod.network.handler.ServerPayloadHandler.syncFullGraphToPlayer(serverPlayer);
        }
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        RoadCommands.register(event.getDispatcher());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[RoadBuilder] Common setup completed.");
    }
}
