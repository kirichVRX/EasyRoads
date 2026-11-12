package com.roadbuilder.mod.client.network;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.graph.NodeType;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.network.payload.S2C_SyncFullGraphPayload;
import com.roadbuilder.mod.network.payload.S2C_SyncRoadSegmentPayload;

/**
 * Handles incoming server packets on the client side.
 * Rule 3: Client only code.
 */
public final class ClientPayloadHandler {

    private ClientPayloadHandler() {}

    public static void handleSyncSegment(final S2C_SyncRoadSegmentPayload payload) {
        RoadNode nodeA = new RoadNode(
                payload.nodeAId(), payload.nodeAPos(), payload.nodeATangent(),
                NodeType.fromString(payload.nodeAType()), payload.nodeAPresetId(), payload.nodeARadius()
        );
        RoadNode nodeB = new RoadNode(
                payload.nodeBId(), payload.nodeBPos(), payload.nodeBTangent(),
                NodeType.fromString(payload.nodeBType()), payload.nodeBPresetId(), payload.nodeBRadius()
        );

        BezierCurve3D curve = new BezierCurve3D(payload.p0(), payload.p1(), payload.p2(), payload.p3());
        RoadProfile profile = RoadProfile.createDefault().withName(payload.profileName()).withWidth(payload.profileWidth());
        RoadEdge edge = new RoadEdge(payload.edgeId(), payload.nodeAId(), payload.nodeBId(), curve, profile, payload.presetId(), payload.moduleCurves());

        ClientGraphCache cache = ClientGraphCache.getInstance();
        cache.getGraph().addNode(nodeA);
        cache.getGraph().addNode(nodeB);
        cache.getGraph().addEdge(edge);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Client received road segment sync: Edge {} (length: {:.1f}m)",
                payload.edgeId(), curve.getApproximateLength());
    }

    public static void handleSyncFullGraph(final S2C_SyncFullGraphPayload payload) {
        ClientGraphCache cache = ClientGraphCache.getInstance();
        cache.clear();

        for (S2C_SyncFullGraphPayload.RoadNodeData nodeData : payload.nodes()) {
            RoadNode node = new RoadNode(
                    nodeData.id(), nodeData.position(), nodeData.tangent(),
                    NodeType.fromString(nodeData.nodeType()), nodeData.presetId(), nodeData.radius(), nodeData.junctionStyle()
            );
            cache.getGraph().addNode(node);
        }

        for (S2C_SyncFullGraphPayload.RoadEdgeData edgeData : payload.edges()) {
            BezierCurve3D curve = new BezierCurve3D(edgeData.p0(), edgeData.p1(), edgeData.p2(), edgeData.p3());
            RoadProfile profile = RoadProfile.createDefault().withName(edgeData.profileName()).withWidth(edgeData.width());
            RoadEdge edge = new RoadEdge(
                    edgeData.id(), edgeData.nodeAId(), edgeData.nodeBId(), curve, profile, edgeData.presetId(),
                    edgeData.moduleCurves(),
                    edgeData.curbPalette() != null && !edgeData.curbPalette().isBlank() ? edgeData.curbPalette() : null,
                    edgeData.sidewalkPalette() != null && !edgeData.sidewalkPalette().isBlank() ? edgeData.sidewalkPalette() : null
            );
            if (edgeData.lampStyle() != null && !edgeData.lampStyle().isBlank()) {
                edge.setLampStyle(edgeData.lampStyle());
                edge.setLampSpacing(edgeData.lampSpacing());
                edge.setSidewalkDoubleLamps(edgeData.sidewalkDoubleLamps());
                edge.setLampPlacement(edgeData.lampPlacement());
            }
            if (edgeData.guardrailStyle() != null && !edgeData.guardrailStyle().isBlank()) {
                edge.setGuardrailStyle(edgeData.guardrailStyle());
            }
            if (edgeData.noiseWallEnabled()) {
                edge.setNoiseWallEnabled(true);
            }
            if (edgeData.stackGuardrails() != null && !edgeData.stackGuardrails().isBlank()) {
                edge.setStackGuardrails(edgeData.stackGuardrails());
            }
            if (edgeData.stackGreenery() != null && !edgeData.stackGreenery().isBlank()) {
                edge.setStackGreenery(edgeData.stackGreenery());
            }
            if (edgeData.treeSpacing() > 0) {
                edge.setTreeSpacing(edgeData.treeSpacing());
            }
            if (edgeData.bushSpacing() > 0) {
                edge.setBushSpacing(edgeData.bushSpacing());
            }
            if (edgeData.embankmentMode() != null && !edgeData.embankmentMode().isBlank()) {
                edge.setEmbankmentMode(edgeData.embankmentMode());
            }
            if (edgeData.embankmentShape() != null && !edgeData.embankmentShape().isBlank()) {
                edge.setEmbankmentShape(edgeData.embankmentShape());
            }
            if (edgeData.embankmentMaterial() != null && !edgeData.embankmentMaterial().isBlank()) {
                edge.setEmbankmentMaterial(edgeData.embankmentMaterial());
            }
            if (edgeData.slopeMode() != null && !edgeData.slopeMode().isBlank()) {
                edge.setSlopeMode(edgeData.slopeMode());
            }
            if (edgeData.slopeStyle() != null && !edgeData.slopeStyle().isBlank()) {
                edge.setSlopeStyle(edgeData.slopeStyle());
            }
            if (edgeData.slopeMaterial() != null && !edgeData.slopeMaterial().isBlank()) {
                edge.setSlopeMaterial(edgeData.slopeMaterial());
            }
            if (edgeData.tunnelShape() != null && !edgeData.tunnelShape().isBlank()) {
                edge.setTunnelShape(edgeData.tunnelShape());
            }
            cache.getGraph().addEdge(edge);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Client received full graph sync: {} nodes, {} edges",
                cache.getGraph().getNodeCount(), cache.getGraph().getEdgeCount());
    }
}
