package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C payload to synchronize a newly built road segment and its endpoint nodes to clients.
 */
public record S2C_SyncRoadSegmentPayload(
        UUID nodeAId,
        Vector3d nodeAPos,
        Vector3d nodeATangent,
        UUID nodeBId,
        Vector3d nodeBPos,
        Vector3d nodeBTangent,
        UUID edgeId,
        Vector3d p0,
        Vector3d p1,
        Vector3d p2,
        Vector3d p3,
        String profileName,
        double profileWidth
) implements CustomPacketPayload {

    public static final Type<S2C_SyncRoadSegmentPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "sync_road_segment"));

    public static final StreamCodec<FriendlyByteBuf, S2C_SyncRoadSegmentPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    S2C_SyncRoadSegmentPayload::write,
                    S2C_SyncRoadSegmentPayload::read
            );

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(nodeAId);
        writeVector3d(buf, nodeAPos);
        writeVector3d(buf, nodeATangent);

        buf.writeUUID(nodeBId);
        writeVector3d(buf, nodeBPos);
        writeVector3d(buf, nodeBTangent);

        buf.writeUUID(edgeId);
        writeVector3d(buf, p0);
        writeVector3d(buf, p1);
        writeVector3d(buf, p2);
        writeVector3d(buf, p3);

        buf.writeUtf(profileName);
        buf.writeDouble(profileWidth);
    }

    private static S2C_SyncRoadSegmentPayload read(FriendlyByteBuf buf) {
        UUID nodeAId = buf.readUUID();
        Vector3d nodeAPos = readVector3d(buf);
        Vector3d nodeATangent = readVector3d(buf);

        UUID nodeBId = buf.readUUID();
        Vector3d nodeBPos = readVector3d(buf);
        Vector3d nodeBTangent = readVector3d(buf);

        UUID edgeId = buf.readUUID();
        Vector3d p0 = readVector3d(buf);
        Vector3d p1 = readVector3d(buf);
        Vector3d p2 = readVector3d(buf);
        Vector3d p3 = readVector3d(buf);

        String profileName = buf.readUtf();
        double profileWidth = buf.readDouble();

        return new S2C_SyncRoadSegmentPayload(
                nodeAId, nodeAPos, nodeATangent,
                nodeBId, nodeBPos, nodeBTangent,
                edgeId, p0, p1, p2, p3,
                profileName, profileWidth
        );
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
