package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadModuleCurve;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C payload to synchronize a newly built road segment and its endpoint nodes to clients.
 */
public record S2C_SyncRoadSegmentPayload(
        UUID nodeAId,
        Vector3d nodeAPos,
        Vector3d nodeATangent,
        String nodeAType,
        String nodeAPresetId,
        double nodeARadius,
        UUID nodeBId,
        Vector3d nodeBPos,
        Vector3d nodeBTangent,
        String nodeBType,
        String nodeBPresetId,
        double nodeBRadius,
        UUID edgeId,
        Vector3d p0,
        Vector3d p1,
        Vector3d p2,
        Vector3d p3,
        String profileName,
        double profileWidth,
        String presetId,
        List<RoadModuleCurve> moduleCurves
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
        buf.writeUtf(nodeAType);
        buf.writeUtf(nodeAPresetId);
        buf.writeDouble(nodeARadius);

        buf.writeUUID(nodeBId);
        writeVector3d(buf, nodeBPos);
        writeVector3d(buf, nodeBTangent);
        buf.writeUtf(nodeBType);
        buf.writeUtf(nodeBPresetId);
        buf.writeDouble(nodeBRadius);

        buf.writeUUID(edgeId);
        writeVector3d(buf, p0);
        writeVector3d(buf, p1);
        writeVector3d(buf, p2);
        writeVector3d(buf, p3);

        buf.writeUtf(profileName);
        buf.writeDouble(profileWidth);
        buf.writeUtf(presetId);

        List<RoadModuleCurve> mods = moduleCurves != null ? moduleCurves : List.of();
        buf.writeVarInt(mods.size());
        for (RoadModuleCurve mc : mods) {
            buf.writeUUID(mc.getId());
            buf.writeUtf(mc.getType().name());
            buf.writeDouble(mc.getWidth());
            buf.writeDouble(mc.getStartOffset());
            buf.writeDouble(mc.getEndOffset());
            buf.writeInt(mc.getStackIndex());
            writeVector3d(buf, mc.getCurve().getP0());
            writeVector3d(buf, mc.getCurve().getP1());
            writeVector3d(buf, mc.getCurve().getP2());
            writeVector3d(buf, mc.getCurve().getP3());
        }
    }

    private static S2C_SyncRoadSegmentPayload read(FriendlyByteBuf buf) {
        UUID nodeAId = buf.readUUID();
        Vector3d nodeAPos = readVector3d(buf);
        Vector3d nodeATangent = readVector3d(buf);
        String nodeAType = buf.readUtf();
        String nodeAPresetId = buf.readUtf();
        double nodeARadius = buf.readDouble();

        UUID nodeBId = buf.readUUID();
        Vector3d nodeBPos = readVector3d(buf);
        Vector3d nodeBTangent = readVector3d(buf);
        String nodeBType = buf.readUtf();
        String nodeBPresetId = buf.readUtf();
        double nodeBRadius = buf.readDouble();

        UUID edgeId = buf.readUUID();
        Vector3d p0 = readVector3d(buf);
        Vector3d p1 = readVector3d(buf);
        Vector3d p2 = readVector3d(buf);
        Vector3d p3 = readVector3d(buf);

        String profileName = buf.readUtf();
        double profileWidth = buf.readDouble();
        String presetId = buf.readUtf();

        int modCount = buf.readVarInt();
        List<RoadModuleCurve> moduleCurves = new ArrayList<>(modCount);
        for (int i = 0; i < modCount; i++) {
            UUID mcId = buf.readUUID();
            RoadElementType mcType;
            try {
                mcType = RoadElementType.valueOf(buf.readUtf());
            } catch (Exception e) {
                mcType = RoadElementType.LANE;
            }
            double mcWidth = buf.readDouble();
            double mcStart = buf.readDouble();
            double mcEnd = buf.readDouble();
            int mcIndex = buf.readInt();
            Vector3d q0 = readVector3d(buf);
            Vector3d q1 = readVector3d(buf);
            Vector3d q2 = readVector3d(buf);
            Vector3d q3 = readVector3d(buf);
            moduleCurves.add(new RoadModuleCurve(mcId, mcType, mcWidth, mcStart, mcEnd, mcIndex, new BezierCurve3D(q0, q1, q2, q3)));
        }

        return new S2C_SyncRoadSegmentPayload(
                nodeAId, nodeAPos, nodeATangent, nodeAType, nodeAPresetId, nodeARadius,
                nodeBId, nodeBPos, nodeBTangent, nodeBType, nodeBPresetId, nodeBRadius,
                edgeId, p0, p1, p2, p3,
                profileName, profileWidth, presetId,
                moduleCurves
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
