package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadModuleCurve;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadElementType;
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

    public record RoadNodeData(
            UUID id,
            Vector3d position,
            Vector3d tangent,
            String nodeType,
            String presetId,
            double radius,
            String junctionStyle
    ) {}

    public record RoadEdgeData(
            UUID id,
            UUID nodeAId,
            UUID nodeBId,
            Vector3d p0,
            Vector3d p1,
            Vector3d p2,
            Vector3d p3,
            String profileName,
            double width,
            String presetId,
            List<RoadModuleCurve> moduleCurves,
            String curbPalette,
            String sidewalkPalette,
            String lampStyle,
            int lampSpacing,
            boolean sidewalkDoubleLamps,
            String lampPlacement,
            String guardrailStyle,
            boolean noiseWallEnabled,
            String stackGuardrails,
            String stackGreenery,
            int treeSpacing,
            int bushSpacing,
            String embankmentMode,
            String embankmentShape,
            String embankmentMaterial,
            String slopeMode,
            String slopeStyle,
            String slopeMaterial,
            String tunnelShape
    ) {}

    public static S2C_SyncFullGraphPayload fromGraph(RoadNetworkGraph graph) {
        List<RoadNodeData> nodeDataList = new ArrayList<>();
        for (RoadNode node : graph.getNodes().values()) {
            nodeDataList.add(new RoadNodeData(
                    node.getId(), node.getPosition(), node.getTangent(),
                    node.getType() != null ? node.getType().name() : "ENDPOINT",
                    node.getPresetId() != null ? node.getPresetId() : "default",
                    node.getRadius(),
                    node.getJunctionStyle()
            ));
        }

        List<RoadEdgeData> edgeDataList = new ArrayList<>();
        for (RoadEdge edge : graph.getEdges().values()) {
            edgeDataList.add(new RoadEdgeData(
                    edge.getId(), edge.getNodeAId(), edge.getNodeBId(),
                    edge.getCurve().getP0(), edge.getCurve().getP1(), edge.getCurve().getP2(), edge.getCurve().getP3(),
                    edge.getProfile().getName(), edge.getProfile().getWidth(),
                    edge.getPresetId() != null ? edge.getPresetId() : "default",
                    edge.getModuleCurves(),
                    edge.getCurbPalette() != null ? edge.getCurbPalette() : "",
                    edge.getSidewalkPalette() != null ? edge.getSidewalkPalette() : "",
                    edge.getLampStyle() != null ? edge.getLampStyle() : "",
                    edge.getLampSpacing(),
                    edge.getSidewalkDoubleLamps() != null && edge.getSidewalkDoubleLamps(),
                    edge.getLampPlacement() != null ? edge.getLampPlacement() : "",
                    edge.getGuardrailStyle() != null ? edge.getGuardrailStyle() : "",
                    edge.getNoiseWallEnabled() != null && edge.getNoiseWallEnabled(),
                    edge.getStackGuardrails() != null ? edge.getStackGuardrails() : "",
                    edge.getStackGreenery() != null ? edge.getStackGreenery() : "",
                    edge.getTreeSpacing(),
                    edge.getBushSpacing(),
                    edge.getEmbankmentMode() != null ? edge.getEmbankmentMode() : "",
                    edge.getEmbankmentShape() != null ? edge.getEmbankmentShape() : "",
                    edge.getEmbankmentMaterial() != null ? edge.getEmbankmentMaterial() : "",
                    edge.getSlopeMode() != null ? edge.getSlopeMode() : "",
                    edge.getSlopeStyle() != null ? edge.getSlopeStyle() : "",
                    edge.getSlopeMaterial() != null ? edge.getSlopeMaterial() : "",
                    edge.getTunnelShape() != null ? edge.getTunnelShape() : ""
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
            buf.writeUtf(node.nodeType());
            buf.writeUtf(node.presetId());
            buf.writeDouble(node.radius());
            buf.writeUtf(node.junctionStyle() != null ? node.junctionStyle() : "ROUNDED");
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
            buf.writeUtf(edge.presetId());
            buf.writeUtf(edge.curbPalette() != null ? edge.curbPalette() : "");
            buf.writeUtf(edge.sidewalkPalette() != null ? edge.sidewalkPalette() : "");
            buf.writeUtf(edge.lampStyle() != null ? edge.lampStyle() : "");
            buf.writeVarInt(edge.lampSpacing());
            buf.writeBoolean(edge.sidewalkDoubleLamps());
            buf.writeUtf(edge.lampPlacement() != null ? edge.lampPlacement() : "");
            buf.writeUtf(edge.guardrailStyle() != null ? edge.guardrailStyle() : "");
            buf.writeBoolean(edge.noiseWallEnabled());
            buf.writeUtf(edge.stackGuardrails() != null ? edge.stackGuardrails() : "");
            buf.writeUtf(edge.stackGreenery() != null ? edge.stackGreenery() : "");
            buf.writeVarInt(edge.treeSpacing());
            buf.writeVarInt(edge.bushSpacing());
            buf.writeUtf(edge.embankmentMode() != null ? edge.embankmentMode() : "");
            buf.writeUtf(edge.embankmentShape() != null ? edge.embankmentShape() : "");
            buf.writeUtf(edge.embankmentMaterial() != null ? edge.embankmentMaterial() : "");
            buf.writeUtf(edge.slopeMode() != null ? edge.slopeMode() : "");
            buf.writeUtf(edge.slopeStyle() != null ? edge.slopeStyle() : "");
            buf.writeUtf(edge.slopeMaterial() != null ? edge.slopeMaterial() : "");
            buf.writeUtf(edge.tunnelShape() != null ? edge.tunnelShape() : "");

            List<RoadModuleCurve> mods = edge.moduleCurves() != null ? edge.moduleCurves() : List.of();
            buf.writeVarInt(mods.size());
            for (RoadModuleCurve mc : mods) {
                buf.writeUUID(mc.getId());
                buf.writeUtf(mc.getType().name());
                buf.writeDouble(mc.getWidth());
                buf.writeDouble(mc.getStartOffset());
                buf.writeDouble(mc.getEndOffset());
                buf.writeInt(mc.getStackIndex());
                buf.writeDouble(mc.getCurve().getP0().x);
                buf.writeDouble(mc.getCurve().getP0().y);
                buf.writeDouble(mc.getCurve().getP0().z);
                buf.writeDouble(mc.getCurve().getP1().x);
                buf.writeDouble(mc.getCurve().getP1().y);
                buf.writeDouble(mc.getCurve().getP1().z);
                buf.writeDouble(mc.getCurve().getP2().x);
                buf.writeDouble(mc.getCurve().getP2().y);
                buf.writeDouble(mc.getCurve().getP2().z);
                buf.writeDouble(mc.getCurve().getP3().x);
                buf.writeDouble(mc.getCurve().getP3().y);
                buf.writeDouble(mc.getCurve().getP3().z);
            }
        }
    }

    private static S2C_SyncFullGraphPayload read(FriendlyByteBuf buf) {
        int nodeCount = buf.readVarInt();
        List<RoadNodeData> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            UUID id = buf.readUUID();
            Vector3d pos = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            Vector3d tan = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
            String nodeType = buf.readUtf();
            String presetId = buf.readUtf();
            double radius = buf.readDouble();
            String junctionStyle = buf.readUtf();
            nodes.add(new RoadNodeData(id, pos, tan, nodeType, presetId, radius, junctionStyle));
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
            String presetId = buf.readUtf();
            String curbPalette = buf.readUtf();
            String sidewalkPalette = buf.readUtf();
            String lampStyle = buf.readUtf();
            int lampSpacing = buf.readVarInt();
            boolean sidewalkDoubleLamps = buf.readBoolean();
            String lampPlacement = buf.readUtf();
            String guardrailStyle = buf.readUtf();
            boolean noiseWallEnabled = buf.readBoolean();
            String stackGuardrails = buf.readUtf();
            String stackGreenery = buf.readUtf();
            int treeSpacing = buf.readVarInt();
            int bushSpacing = buf.readVarInt();
            String embankmentMode = buf.readUtf();
            String embankmentShape = buf.readUtf();
            String embankmentMaterial = buf.readUtf();
            String slopeMode = buf.readUtf();
            String slopeStyle = buf.readUtf();
            String slopeMaterial = buf.readUtf();
            String tunnelShape = buf.readUtf();

            int modCount = buf.readVarInt();
            List<RoadModuleCurve> moduleCurves = new ArrayList<>(modCount);
            for (int m = 0; m < modCount; m++) {
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
                Vector3d q0 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vector3d q1 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vector3d q2 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vector3d q3 = new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
                moduleCurves.add(new RoadModuleCurve(mcId, mcType, mcWidth, mcStart, mcEnd, mcIndex, new BezierCurve3D(q0, q1, q2, q3)));
            }

            edges.add(new RoadEdgeData(id, nodeAId, nodeBId, p0, p1, p2, p3, profileName, width, presetId, moduleCurves,
                    curbPalette, sidewalkPalette, lampStyle, lampSpacing, sidewalkDoubleLamps, lampPlacement,
                    guardrailStyle, noiseWallEnabled, stackGuardrails,
                    stackGreenery, treeSpacing, bushSpacing,
                    embankmentMode, embankmentShape, embankmentMaterial,
                    slopeMode, slopeStyle, slopeMaterial, tunnelShape));
        }

        return new S2C_SyncFullGraphPayload(nodes, edges);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
