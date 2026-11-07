package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

/**
 * C2S payload sent by the client to create a new circular roundabout rotary junction.
 */
public record C2S_CreateRoundaboutPayload(
        UUID nodeId,
        Vector3d position,
        double outerRadius,
        double innerRadius,
        int laneCount,
        String ringPresetId
) implements CustomPacketPayload {

    public C2S_CreateRoundaboutPayload {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(position, "position cannot be null");
        ringPresetId = (ringPresetId != null && !ringPresetId.isEmpty()) ? ringPresetId : "default";
    }

    public static final Type<C2S_CreateRoundaboutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "create_roundabout"));

    public static final StreamCodec<FriendlyByteBuf, C2S_CreateRoundaboutPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    C2S_CreateRoundaboutPayload::write,
                    C2S_CreateRoundaboutPayload::read
            );

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(nodeId);
        buf.writeDouble(position.x);
        buf.writeDouble(position.y);
        buf.writeDouble(position.z);
        buf.writeDouble(outerRadius);
        buf.writeDouble(innerRadius);
        buf.writeVarInt(laneCount);
        buf.writeUtf(ringPresetId);
    }

    private static C2S_CreateRoundaboutPayload read(FriendlyByteBuf buf) {
        UUID nodeId = buf.readUUID();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double outerR = buf.readDouble();
        double innerR = buf.readDouble();
        int lanes = buf.readVarInt();
        String preset = buf.readUtf();
        return new C2S_CreateRoundaboutPayload(nodeId, new Vector3d(x, y, z), outerR, innerR, lanes, preset);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
