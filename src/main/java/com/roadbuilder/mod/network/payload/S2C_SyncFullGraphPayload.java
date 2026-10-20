package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S2C payload to synchronize the full road network graph on player login / level load.
 */
public record S2C_SyncFullGraphPayload(
        List<RoadNodeData> nodes,
        List<RoadEdgeData> edges
) implements CustomPacketPayload {

    public record RoadNodeData(UUID id, Vector3d position, Vector3d tangent) {}
    public record RoadEdgeData(UUID id, UUID nodeAId, UUID nodeBId, Vector3d p0, Vector3d p1, Vector3d p2, Vector3d p3, String profileName, double width) {}

    public static S2C_SyncFullGraphPayload fromGraph(RoadNetworkGraph graph) {
        List<RoadNodeData> nodeDataList = new ArrayList<>();
        for (RoadNode node : graph.getNodes().values()) {
            nodeDataList.add(new RoadNodeData(
                    node.getId(), node.getPosition(), node.getTangent()
            ));
        }

        List<RoadEdgeData> edgeDataList = new ArrayList<>();
        for (RoadEdge edge : graph.getEdges().values()) {
            edgeDataList.add(new RoadEdgeData(
                    edge.getId(), edge.getNodeAId(), edge.getNodeBId(),
                    edge.getCurve().getP0(), edge.getCurve().getP1(), edge.getCurve().getP2(), edge.getCurve().getP3(),
                    edge.getProfile().getName(), edge.getProfile().getWidth()
            ));
        }

        return new S2C_SyncFullGraphPayload(nodeDataList, edgeDataList);
    }

    public static final Type<S2C_SyncFullGraphPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "sync_full_graph"));

    public static final StreamCodec<FriendlyByteBuf, S2C_SyncFullGraphPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    S2C_SyncFullGraphPayload::write,
                    S2C_SyncFullGraphPayload::read
            );

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(nodes.size());
        for (RoadNodeData node : nodes) {
            buf.writeUUID(node.id());
            buf.writeDouble(node.position().x);
            buf.writeDouble(node.position().y);
            buf.writeDouble(node.position().z);
            buf.writeDouble(node.tangent().x);
            buf.writeDouble(node.tangent().y);
            buf.writeDouble(node.tangent().z);
        }

        buf.writeVarInt(edges.size());
        for (RoadEdgeData edge : edges) {
            buf.writeUUID(edge.id());
            buf.writeUUID(edge.nodeAId());
            buf.writeUUID(edge.nodeBId());
            buf.writeDouble(edge.p0().x);
            buf.writeDouble(edge.p0().y);
            buf.writeDouble(edge.p0().z);
            buf.writeDouble(edge.p1().x);
            buf.writeDouble(edge.p1().y);
            buf.writeDouble(edge.p1().z);
            buf.writeDouble(edge.p2().x);
            buf.writeDouble(edge.p2().y);
            buf.writeDouble(edge.p2().z);
            buf.writeDouble(edge.p3().x);
            buf.writeDouble(edge.p3().y);
            buf.writeDouble(edge.p3().z);
            buf.writeUtf(edge.profileName());
            buf.writeDouble(edge.width());
        }
    }

    private static S2C_SyncFullGraphPayload read(FriendlyByteBuf buf) {
        int nodeCount = buf.readVarInt();
        List<RoadNodeData> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            UUID id = buf.readUUID();
            Vector3d pos = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vector3d tan = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            nodes.add(new RoadNodeData(id, pos, tan));
        }

        int edgeCount = buf.readVarInt();
        List<RoadEdgeData> edges = new ArrayList<>(edgeCount);
        for (int i = 0; i < edgeCount; i++) {
            UUID id = buf.readUUID();
            UUID nodeAId = buf.readUUID();
            UUID nodeBId = buf.readUUID();
            Vector3d p0 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vector3d p1 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vector3d p2 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vector3d p3 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            String profileName = buf.readUtf();
            double width = buf.readDouble();
            edges.add(new RoadEdgeData(id, nodeAId, nodeBId, p0, p1, p2, p3, profileName, width));
        }

        return new S2C_SyncFullGraphPayload(nodes, edges);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
