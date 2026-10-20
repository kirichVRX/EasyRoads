package com.roadbuilder.mod.client.network;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
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
        RoadNode nodeA = new RoadNode(payload.nodeAId(), payload.nodeAPos(), payload.nodeATangent());
        RoadNode nodeB = new RoadNode(payload.nodeBId(), payload.nodeBPos(), payload.nodeBTangent());

        BezierCurve3D curve = new BezierCurve3D(payload.p0(), payload.p1(), payload.p2(), payload.p3());
        RoadProfile profile = RoadProfile.createDefault();
        RoadEdge edge = new RoadEdge(payload.edgeId(), payload.nodeAId(), payload.nodeBId(), curve, profile);

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
            RoadNode node = new RoadNode(nodeData.id(), nodeData.position(), nodeData.tangent());
            cache.getGraph().addNode(node);
        }

        for (S2C_SyncFullGraphPayload.RoadEdgeData edgeData : payload.edges()) {
            BezierCurve3D curve = new BezierCurve3D(edgeData.p0(), edgeData.p1(), edgeData.p2(), edgeData.p3());
            RoadProfile profile = RoadProfile.createDefault();
            RoadEdge edge = new RoadEdge(edgeData.id(), edgeData.nodeAId(), edgeData.nodeBId(), curve, profile);
            cache.getGraph().addEdge(edge);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Client received full graph sync: {} nodes, {} edges",
                cache.getGraph().getNodeCount(), cache.getGraph().getEdgeCount());
    }
}
