package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S payload sent by the client to request undoing the last built road segment.
 */
public record C2S_RequestUndoPayload() implements CustomPacketPayload {

    public static final Type<C2S_RequestUndoPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "request_undo"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestUndoPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    (payload, buf) -> {},
                    buf -> new C2S_RequestUndoPayload()
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
