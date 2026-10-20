package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * C2S payload sent by the client editor to request building a road segment between two points/nodes.
 */
public record C2S_RequestBuildSegmentPayload(
        Vector3d p0,
        Vector3d p1,
        Vector3d p2,
        Vector3d p3,
        Optional<UUID> startNodeId,
        Optional<UUID> endNodeId,
        String profileName
) implements CustomPacketPayload {

    public static final Type<C2S_RequestBuildSegmentPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "request_build_segment"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestBuildSegmentPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    C2S_RequestBuildSegmentPayload::write,
                    C2S_RequestBuildSegmentPayload::read
            );

    private void write(FriendlyByteBuf buf) {
        writeVector3d(buf, p0);
        writeVector3d(buf, p1);
        writeVector3d(buf, p2);
        writeVector3d(buf, p3);

        buf.writeBoolean(startNodeId.isPresent());
        startNodeId.ifPresent(buf::writeUUID);

        buf.writeBoolean(endNodeId.isPresent());
        endNodeId.ifPresent(buf::writeUUID);

        buf.writeUtf(profileName);
    }

    private static C2S_RequestBuildSegmentPayload read(FriendlyByteBuf buf) {
        Vector3d p0 = readVector3d(buf);
        Vector3d p1 = readVector3d(buf);
        Vector3d p2 = readVector3d(buf);
        Vector3d p3 = readVector3d(buf);

        Optional<UUID> startNodeId = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        Optional<UUID> endNodeId = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();

        String profileName = buf.readUtf();

        return new C2S_RequestBuildSegmentPayload(p0, p1, p2, p3, startNodeId, endNodeId, profileName);
    }

    private static void writeVector3d(FriendlyByteBuf buf, Vector3d vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vector3d readVector3d(FriendlyByteBuf buf) {
        return new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
