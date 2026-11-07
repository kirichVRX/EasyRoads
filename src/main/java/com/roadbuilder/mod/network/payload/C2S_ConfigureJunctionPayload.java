package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * C2S payload to configure junction radius and visual style.
 */
public record C2S_ConfigureJunctionPayload(UUID nodeId, double radius, String junctionStyle) implements CustomPacketPayload {

    public C2S_ConfigureJunctionPayload {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(junctionStyle, "junctionStyle cannot be null");
    }

    public static final Type<C2S_ConfigureJunctionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "configure_junction"));

    public static final StreamCodec<FriendlyByteBuf, C2S_ConfigureJunctionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {
                        buf.writeUUID(payload.nodeId());
                        buf.writeDouble(payload.radius());
                        buf.writeUtf(payload.junctionStyle());
                    },
                    buf -> new C2S_ConfigureJunctionPayload(buf.readUUID(), buf.readDouble(), buf.readUtf())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
