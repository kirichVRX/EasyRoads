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
 * C2S payload sent by the client when moving an existing node to a new position.
 */
public record C2S_MoveNodePayload(UUID nodeId, Vector3d newPosition) implements CustomPacketPayload {

    public C2S_MoveNodePayload {
        Objects.requireNonNull(nodeId, "nodeId cannot be null");
        Objects.requireNonNull(newPosition, "newPosition cannot be null");
    }

    public static final Type<C2S_MoveNodePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "move_node"));

    public static final StreamCodec<FriendlyByteBuf, C2S_MoveNodePayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    C2S_MoveNodePayload::write,
                    C2S_MoveNodePayload::read
            );

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(nodeId);
        buf.writeDouble(newPosition.x);
        buf.writeDouble(newPosition.y);
        buf.writeDouble(newPosition.z);
    }

    private static C2S_MoveNodePayload read(FriendlyByteBuf buf) {
        UUID nodeId = buf.readUUID();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        return new C2S_MoveNodePayload(nodeId, new Vector3d(x, y, z));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
