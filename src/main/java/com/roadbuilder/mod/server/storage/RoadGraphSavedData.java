package com.roadbuilder.mod.server.storage;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.NodeType;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadModuleCurve;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

/**
 * Persists the entire procedural road network graph into the world NBT storage.
 * Rule 4: Server authority - world data resides on the server level.
 */
public final class RoadGraphSavedData extends SavedData {
    public static final String DATA_NAME = RoadBuilderMod.MOD_ID + "_graph";
    private final RoadNetworkGraph graph = new RoadNetworkGraph();
    private final Map<UUID, Set<BlockPos>> edgeBlockRegistry = new HashMap<>();
    private final Map<UUID, Map<BlockPos, BlockState>> edgeOriginalBlocks = new HashMap<>();

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

            NodeType type = NodeType.ENDPOINT;
            if (nodeTag.contains("NodeType")) {
                try {
                    type = NodeType.valueOf(nodeTag.getString("NodeType"));
                } catch (IllegalArgumentException ignored) {}
            }
            String presetId = nodeTag.contains("PresetId") ? nodeTag.getString("PresetId") : "default";
            double radius = nodeTag.contains("Radius") ? nodeTag.getDouble("Radius") : 0.0;

            RoadNode node = new RoadNode(id, pos, tangent, type, presetId, radius);
            if (nodeTag.contains("JunctionStyle")) {
                node.setJunctionStyle(nodeTag.getString("JunctionStyle"));
            }
            if (nodeTag.contains("OuterRadius")) {
                node.setOuterRadius(nodeTag.getDouble("OuterRadius"));
            }
            if (nodeTag.contains("InnerRadius")) {
                node.setInnerRadius(nodeTag.getDouble("InnerRadius"));
            }
            if (nodeTag.contains("RingLaneCount")) {
                node.setRingLaneCount(nodeTag.getInt("RingLaneCount"));
            }
            if (nodeTag.contains("RingPresetId")) {
                node.setRingPresetId(nodeTag.getString("RingPresetId"));
            }

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

            String presetId = edgeTag.contains("PresetId") ? edgeTag.getString("PresetId") : profile.getName();
            if (presetId == null || presetId.isEmpty()) {
                presetId = "default";
            }

            List<RoadModuleCurve> moduleCurves = new ArrayList<>();
            if (edgeTag.contains("ModuleCurves", Tag.TAG_LIST)) {
                ListTag modulesList = edgeTag.getList("ModuleCurves", Tag.TAG_COMPOUND);
                for (int m = 0; m < modulesList.size(); m++) {
                    CompoundTag mcTag = modulesList.getCompound(m);
                    UUID mcId = mcTag.getUUID("Id");
                    RoadElementType type;
                    try {
                        type = RoadElementType.valueOf(mcTag.getString("Type"));
                    } catch (Exception e) {
                        type = RoadElementType.LANE;
                    }
                    double width = mcTag.getDouble("Width");
                    double startOffset = mcTag.getDouble("StartOffset");
                    double endOffset = mcTag.getDouble("EndOffset");
                    int stackIndex = mcTag.getInt("StackIndex");
                    Vector3d q0 = readVector3d(mcTag, "P0");
                    Vector3d q1 = readVector3d(mcTag, "P1");
                    Vector3d q2 = readVector3d(mcTag, "P2");
                    Vector3d q3 = readVector3d(mcTag, "P3");
                    BezierCurve3D mcCurve = new BezierCurve3D(q0, q1, q2, q3);
                    moduleCurves.add(new RoadModuleCurve(mcId, type, width, startOffset, endOffset, stackIndex, mcCurve));
                }
            } else {
                final String pId = presetId;
                ModularRoadPreset preset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(pId))
                        .findFirst()
                        .orElse(PresetManager.getInstance().getActivePreset());
                moduleCurves = RoadModuleCurve.generateModuleCurves(curve, preset, null, null);
            }

            RoadEdge edge = new RoadEdge(id, nodeAId, nodeBId, curve, profile, presetId, moduleCurves);
            graph.addEdge(edge);
        }

        // 3. Load Edge Block Registry
        if (tag.contains("EdgeBlockRegistry", Tag.TAG_LIST)) {
            ListTag registryList = tag.getList("EdgeBlockRegistry", Tag.TAG_COMPOUND);
            for (int i = 0; i < registryList.size(); i++) {
                CompoundTag entryTag = registryList.getCompound(i);
                UUID edgeId = entryTag.getUUID("EdgeId");
                long[] packed = entryTag.getLongArray("Positions");
                Set<BlockPos> positions = new HashSet<>(packed.length);
                for (long p : packed) {
                    positions.add(BlockPos.of(p));
                }
                data.edgeBlockRegistry.put(edgeId, positions);
            }
        }

        // 4. Load Edge Original Blocks
        if (tag.contains("EdgeOriginalBlocks", Tag.TAG_LIST)) {
            ListTag origList = tag.getList("EdgeOriginalBlocks", Tag.TAG_COMPOUND);
            HolderGetter<Block> blockGetter = registries.lookupOrThrow(Registries.BLOCK);
            for (int i = 0; i < origList.size(); i++) {
                CompoundTag entryTag = origList.getCompound(i);
                UUID edgeId = entryTag.getUUID("EdgeId");
                ListTag paletteList = entryTag.getList("Palette", Tag.TAG_COMPOUND);
                List<BlockState> palette = new ArrayList<>(paletteList.size());
                for (int p = 0; p < paletteList.size(); p++) {
                    palette.add(NbtUtils.readBlockState(blockGetter, paletteList.getCompound(p)));
                }
                long[] positions = entryTag.getLongArray("Positions");
                int[] indices = entryTag.getIntArray("Indices");
                Map<BlockPos, BlockState> blockMap = new HashMap<>(positions.length);
                for (int j = 0; j < positions.length && j < indices.length; j++) {
                    int pIdx = indices[j];
                    if (pIdx >= 0 && pIdx < palette.size()) {
                        blockMap.put(BlockPos.of(positions[j]), palette.get(pIdx));
                    }
                }
                data.edgeOriginalBlocks.put(edgeId, blockMap);
            }
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Loaded road graph: {} nodes, {} edges, {} registered block sets, {} original block maps",
                graph.getNodeCount(), graph.getEdgeCount(), data.edgeBlockRegistry.size(), data.edgeOriginalBlocks.size());

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
            nodeTag.putString("NodeType", node.getType().name());
            nodeTag.putString("PresetId", node.getPresetId());
            nodeTag.putDouble("Radius", node.getRadius());
            nodeTag.putString("JunctionStyle", node.getJunctionStyle());

            if (node.getType() == NodeType.ROUNDABOUT) {
                nodeTag.putDouble("OuterRadius", node.getOuterRadius());
                nodeTag.putDouble("InnerRadius", node.getInnerRadius());
                nodeTag.putInt("RingLaneCount", node.getRingLaneCount());
                nodeTag.putString("RingPresetId", node.getRingPresetId());
            }

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
            edgeTag.putString("PresetId", edge.getPresetId());

            BezierCurve3D curve = edge.getCurve();
            writeVector3d(edgeTag, "P0", curve.getP0());
            writeVector3d(edgeTag, "P1", curve.getP1());
            writeVector3d(edgeTag, "P2", curve.getP2());
            writeVector3d(edgeTag, "P3", curve.getP3());

            CompoundTag profTag = new CompoundTag();
            writeProfile(profTag, edge.getProfile());
            edgeTag.put("Profile", profTag);

            ListTag modulesTag = new ListTag();
            for (RoadModuleCurve mc : edge.getModuleCurves()) {
                CompoundTag mcTag = new CompoundTag();
                mcTag.putUUID("Id", mc.getId());
                mcTag.putString("Type", mc.getType().name());
                mcTag.putDouble("Width", mc.getWidth());
                mcTag.putDouble("StartOffset", mc.getStartOffset());
                mcTag.putDouble("EndOffset", mc.getEndOffset());
                mcTag.putInt("StackIndex", mc.getStackIndex());
                writeVector3d(mcTag, "P0", mc.getCurve().getP0());
                writeVector3d(mcTag, "P1", mc.getCurve().getP1());
                writeVector3d(mcTag, "P2", mc.getCurve().getP2());
                writeVector3d(mcTag, "P3", mc.getCurve().getP3());
                modulesTag.add(mcTag);
            }
            edgeTag.put("ModuleCurves", modulesTag);

            edgesList.add(edgeTag);
        }
        tag.put("Edges", edgesList);

        // 3. Save Edge Block Registry
        ListTag registryList = new ListTag();
        for (Map.Entry<UUID, Set<BlockPos>> entry : edgeBlockRegistry.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("EdgeId", entry.getKey());
            long[] packed = entry.getValue().stream().mapToLong(BlockPos::asLong).toArray();
            entryTag.putLongArray("Positions", packed);
            registryList.add(entryTag);
        }
        tag.put("EdgeBlockRegistry", registryList);

        // 4. Save Edge Original Blocks
        ListTag origList = new ListTag();
        for (Map.Entry<UUID, Map<BlockPos, BlockState>> entry : edgeOriginalBlocks.entrySet()) {
            Map<BlockPos, BlockState> origMap = entry.getValue();
            if (origMap == null || origMap.isEmpty()) continue;

            List<BlockState> palette = new ArrayList<>();
            Map<BlockState, Integer> stateToIdx = new HashMap<>();
            for (BlockState state : origMap.values()) {
                if (!stateToIdx.containsKey(state)) {
                    stateToIdx.put(state, palette.size());
                    palette.add(state);
                }
            }

            ListTag paletteList = new ListTag();
            for (BlockState state : palette) {
                paletteList.add(NbtUtils.writeBlockState(state));
            }

            long[] positions = new long[origMap.size()];
            int[] indices = new int[origMap.size()];
            int idx = 0;
            for (Map.Entry<BlockPos, BlockState> e : origMap.entrySet()) {
                positions[idx] = e.getKey().asLong();
                indices[idx] = stateToIdx.get(e.getValue());
                idx++;
            }

            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("EdgeId", entry.getKey());
            entryTag.put("Palette", paletteList);
            entryTag.putLongArray("Positions", positions);
            entryTag.putIntArray("Indices", indices);
            origList.add(entryTag);
        }
        tag.put("EdgeOriginalBlocks", origList);

        return tag;
    }

    public RoadNetworkGraph getGraph() {
        return graph;
    }

    public void registerEdgeBlocks(UUID edgeId, Set<BlockPos> positions) {
        edgeBlockRegistry.put(edgeId, new HashSet<>(positions));
        setDirty();
    }

    public void registerEdgeOriginalBlocks(UUID edgeId, Map<BlockPos, BlockState> originalBlocks) {
        if (originalBlocks != null && !originalBlocks.isEmpty()) {
            edgeOriginalBlocks.put(edgeId, new HashMap<>(originalBlocks));
            setDirty();
        }
    }

    public Map<BlockPos, BlockState> getEdgeOriginalBlocks(UUID edgeId) {
        return edgeOriginalBlocks.getOrDefault(edgeId, Collections.emptyMap());
    }

    public BlockState getOriginalBlockAt(BlockPos pos) {
        for (Map<BlockPos, BlockState> map : edgeOriginalBlocks.values()) {
            BlockState st = map.get(pos);
            if (st != null) return st;
        }
        return null;
    }

    public Set<BlockPos> getAllOtherEdgeBlocks(UUID excludeEdgeId) {
        Set<BlockPos> set = new HashSet<>();
        for (Map.Entry<UUID, Set<BlockPos>> entry : edgeBlockRegistry.entrySet()) {
            if (!entry.getKey().equals(excludeEdgeId)) {
                set.addAll(entry.getValue());
            }
        }
        return set;
    }

    public Set<BlockPos> getAllOtherEdgeBlocks(Set<UUID> excludeEdgeIds) {
        Set<BlockPos> set = new HashSet<>();
        for (Map.Entry<UUID, Set<BlockPos>> entry : edgeBlockRegistry.entrySet()) {
            if (!excludeEdgeIds.contains(entry.getKey())) {
                set.addAll(entry.getValue());
            }
        }
        return set;
    }

    public boolean isBlockUsedByOtherEdge(BlockPos pos, Set<UUID> excludeEdgeIds) {
        for (Map.Entry<UUID, Set<BlockPos>> entry : edgeBlockRegistry.entrySet()) {
            if (!excludeEdgeIds.contains(entry.getKey())) {
                if (entry.getValue().contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Set<BlockPos> getEdgeBlocks(UUID edgeId) {
        return edgeBlockRegistry.getOrDefault(edgeId, Collections.emptySet());
    }

    public void clearEdgeBlocks(UUID edgeId) {
        boolean removed1 = edgeBlockRegistry.remove(edgeId) != null;
        boolean removed2 = edgeOriginalBlocks.remove(edgeId) != null;
        if (removed1 || removed2) {
            setDirty();
        }
    }

    public Map<UUID, Set<BlockPos>> getEdgeBlockRegistry() {
        return Collections.unmodifiableMap(edgeBlockRegistry);
    }

    public RoadEdge addRoadSegment(RoadNode startNode, RoadNode endNode, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves) {
        if (!graph.hasNode(startNode.getId())) {
            graph.addNode(startNode);
        }
        if (!graph.hasNode(endNode.getId())) {
            graph.addNode(endNode);
        }

        RoadEdge edge = new RoadEdge(startNode.getId(), endNode.getId(), curve, profile, presetId, moduleCurves);
        graph.addEdge(edge);

        startNode.recalculateType(graph);
        endNode.recalculateType(graph);

        setDirty();
        return edge;
    }

    public RoadEdge addRoadSegment(RoadNode startNode, RoadNode endNode, BezierCurve3D curve, RoadProfile profile, String presetId) {
        ModularRoadPreset preset = PresetManager.getInstance().getAllPresets().stream()
                .filter(p -> p.getId().equals(presetId))
                .findFirst()
                .orElse(PresetManager.getInstance().getActivePreset());
        List<RoadModuleCurve> moduleCurves = RoadModuleCurve.generateModuleCurves(curve, preset, null, null);
        return addRoadSegment(startNode, endNode, curve, profile, presetId, moduleCurves);
    }

    public RoadEdge addRoadSegment(RoadNode startNode, RoadNode endNode, BezierCurve3D curve, RoadProfile profile) {
        return addRoadSegment(startNode, endNode, curve, profile, profile != null ? profile.getName() : "default");
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
