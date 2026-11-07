package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * C2S payload sent by the client to change the preset of an existing road edge.
 */
public record C2S_ChangeEdgePresetPayload(UUID edgeId, String newPresetId) implements CustomPacketPayload {

    public C2S_ChangeEdgePresetPayload {
        Objects.requireNonNull(edgeId, "edgeId cannot be null");
        Objects.requireNonNull(newPresetId, "newPresetId cannot be null");
    }

    public static final Type<C2S_ChangeEdgePresetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "change_edge_preset"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ChangeEdgePresetPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeUUID(payload.edgeId());
                        buf.writeUtf(payload.newPresetId());
                    },
                    buf -> new C2S_ChangeEdgePresetPayload(buf.readUUID(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
