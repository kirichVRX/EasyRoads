package com.roadbuilder.mod.server.storage;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/**
 * Persists the entire procedural road network graph into the world NBT storage.
 * Rule 4: Server authority - world data resides on the server level.
 */
public final class RoadGraphSavedData extends SavedData {
    public static final String DATA_NAME = RoadBuilderMod.MOD_ID + "_graph";
    private final RoadNetworkGraph graph = new RoadNetworkGraph();

    public RoadGraphSavedData() {
    }

    public static RoadGraphSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(RoadGraphSavedData::new, RoadGraphSavedData::load, null),
                DATA_NAME
        );
    }

    public static RoadGraphSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RoadGraphSavedData data = new RoadGraphSavedData();
        RoadNetworkGraph graph = data.getGraph();

        // 1. Load Nodes
        ListTag nodesList = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodesList.size(); i++) {
            CompoundTag nodeTag = nodesList.getCompound(i);
            UUID id = nodeTag.getUUID("Id");
            Vector3d pos = readVector3d(nodeTag, "Pos");
            Vector3d tangent = readVector3d(nodeTag, "Tangent");

            RoadNode node = new RoadNode(id, pos, tangent);
            graph.addNode(node);
        }

        // 2. Load Edges
        ListTag edgesList = tag.getList("Edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edgesList.size(); i++) {
            CompoundTag edgeTag = edgesList.getCompound(i);
            UUID id = edgeTag.getUUID("Id");
            UUID nodeAId = edgeTag.getUUID("NodeA");
            UUID nodeBId = edgeTag.getUUID("NodeB");

            Vector3d p0 = readVector3d(edgeTag, "P0");
            Vector3d p1 = readVector3d(edgeTag, "P1");
            Vector3d p2 = readVector3d(edgeTag, "P2");
            Vector3d p3 = readVector3d(edgeTag, "P3");
            BezierCurve3D curve = new BezierCurve3D(p0, p1, p2, p3);

            CompoundTag profTag = edgeTag.getCompound("Profile");
            RoadProfile profile = readProfile(profTag);

            RoadEdge edge = new RoadEdge(id, nodeAId, nodeBId, curve, profile);
            graph.addEdge(edge);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Loaded road graph: {} nodes, {} edges",
                graph.getNodeCount(), graph.getEdgeCount());

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        // 1. Save Nodes
        ListTag nodesList = new ListTag();
        for (RoadNode node : graph.getNodes().values()) {
            CompoundTag nodeTag = new CompoundTag();
            nodeTag.putUUID("Id", node.getId());
            writeVector3d(nodeTag, "Pos", node.getPosition());
            writeVector3d(nodeTag, "Tangent", node.getTangent());
            nodesList.add(nodeTag);
        }
        tag.put("Nodes", nodesList);

        // 2. Save Edges
        ListTag edgesList = new ListTag();
        for (RoadEdge edge : graph.getEdges().values()) {
            CompoundTag edgeTag = new CompoundTag();
            edgeTag.putUUID("Id", edge.getId());
            edgeTag.putUUID("NodeA", edge.getNodeAId());
            edgeTag.putUUID("NodeB", edge.getNodeBId());

            BezierCurve3D curve = edge.getCurve();
            writeVector3d(edgeTag, "P0", curve.getP0());
            writeVector3d(edgeTag, "P1", curve.getP1());
            writeVector3d(edgeTag, "P2", curve.getP2());
            writeVector3d(edgeTag, "P3", curve.getP3());

            CompoundTag profTag = new CompoundTag();
            writeProfile(profTag, edge.getProfile());
            edgeTag.put("Profile", profTag);

            edgesList.add(edgeTag);
        }
        tag.put("Edges", edgesList);

        return tag;
    }

    public RoadNetworkGraph getGraph() {
        return graph;
    }

    public void addRoadSegment(RoadNode startNode, RoadNode endNode, BezierCurve3D curve, RoadProfile profile) {
        if (!graph.hasNode(startNode.getId())) {
            graph.addNode(startNode);
        }
        if (!graph.hasNode(endNode.getId())) {
            graph.addNode(endNode);
        }

        RoadEdge edge = new RoadEdge(startNode.getId(), endNode.getId(), curve, profile);
        graph.addEdge(edge);
        setDirty();
    }

    private static void writeVector3d(CompoundTag tag, String prefix, Vector3d vec) {
        tag.putDouble(prefix + "X", vec.x);
        tag.putDouble(prefix + "Y", vec.y);
        tag.putDouble(prefix + "Z", vec.z);
    }

    private static Vector3d readVector3d(CompoundTag tag, String prefix) {
        return new Vector3d(
                tag.getDouble(prefix + "X"),
                tag.getDouble(prefix + "Y"),
                tag.getDouble(prefix + "Z")
        );
    }

    private static void writeProfile(CompoundTag tag, RoadProfile p) {
        tag.putString("Name", p.getName());
        tag.putDouble("Width", p.getWidth());
        tag.putString("SurfaceBlock", p.getSurfaceBlockId());
        tag.putString("FoundationBlock", p.getFoundationBlockId());
        tag.putBoolean("HasCurbs", p.hasCurbs());
        tag.putString("CurbBlock", p.getCurbBlockId());
        tag.putDouble("CurbWidth", p.getCurbWidth());
        tag.putInt("ClearanceHeight", p.getClearanceHeight());

        tag.putBoolean("HasSidewalks", p.hasSidewalks());
        tag.putDouble("SidewalkWidth", p.getSidewalkWidth());
        net.minecraft.nbt.ListTag sidewalkList = new net.minecraft.nbt.ListTag();
        for (String id : p.getSidewalkBlockIds()) {
            sidewalkList.add(net.minecraft.nbt.StringTag.valueOf(id));
        }
        tag.put("SidewalkBlocks", sidewalkList);

        tag.putBoolean("HasCenterMarking", p.hasCenterMarking());
        tag.putString("CenterMarkingBlock", p.getCenterMarkingBlockId());
        tag.putInt("DashLength", p.getDashLength());
        tag.putInt("GapLength", p.getGapLength());

        tag.putBoolean("CreatePillars", p.isCreatePillars());
        tag.putString("PillarBlock", p.getPillarBlockId());
        tag.putDouble("PillarSpacing", p.getPillarSpacing());
        tag.putBoolean("CreateTunnels", p.isCreateTunnels());
        tag.putString("TunnelWallBlock", p.getTunnelWallBlockId());
        tag.putInt("TunnelCeilingHeight", p.getTunnelCeilingHeight());
    }

    private static RoadProfile readProfile(CompoundTag tag) {
        boolean hasSidewalks = tag.contains("HasSidewalks") ? tag.getBoolean("HasSidewalks") : false;
        double sidewalkWidth = tag.contains("SidewalkWidth") ? tag.getDouble("SidewalkWidth") : 0.0;
        java.util.List<String> sidewalkBlocks = new java.util.ArrayList<>();
        if (tag.contains("SidewalkBlocks")) {
            net.minecraft.nbt.ListTag list = tag.getList("SidewalkBlocks", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                sidewalkBlocks.add(list.getString(i));
            }
        }
        boolean hasCenterMarking = tag.contains("HasCenterMarking") ? tag.getBoolean("HasCenterMarking") : false;
        String centerMarkingBlock = tag.contains("CenterMarkingBlock") ? tag.getString("CenterMarkingBlock") : "minecraft:smooth_quartz_stairs";
        int dashLength = tag.contains("DashLength") ? tag.getInt("DashLength") : 4;
        int gapLength = tag.contains("GapLength") ? tag.getInt("GapLength") : 1;

        return new RoadProfile(
                tag.getString("Name"),
                tag.getDouble("Width"),
                tag.getString("SurfaceBlock"),
                tag.getString("FoundationBlock"),
                tag.getBoolean("HasCurbs"),
                tag.getString("CurbBlock"),
                tag.getDouble("CurbWidth"),
                tag.getInt("ClearanceHeight"),
                hasSidewalks,
                sidewalkWidth,
                sidewalkBlocks,
                hasCenterMarking,
                centerMarkingBlock,
                dashLength,
                gapLength,
                tag.getBoolean("CreatePillars"),
                tag.getString("PillarBlock"),
                tag.getDouble("PillarSpacing"),
                tag.getBoolean("CreateTunnels"),
                tag.getString("TunnelWallBlock"),
                tag.getInt("TunnelCeilingHeight")
        );
    }
}
