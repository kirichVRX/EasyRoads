package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * C2S payload sent by the client to request deletion of a specific road node (and all connected edges).
 */
public record C2S_DeleteNodePayload(UUID nodeId) implements CustomPacketPayload {

    public C2S_DeleteNodePayload {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
    }

    public static final Type<C2S_DeleteNodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "delete_node"));

    public static final StreamCodec<FriendlyByteBuf, C2S_DeleteNodePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> buf.writeUUID(payload.nodeId()),
                    buf -> new C2S_DeleteNodePayload(buf.readUUID())
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
