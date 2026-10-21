package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.*;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Procedural road planner and voxelizer built on modular road presets:
 * 1. Discrete segment sampling with exact perpendicular distance calculation.
 * 2. Grid-aligned normalization factor m = max(|nx|, |nz|) ensuring thin elements are exactly 1 cell wide.
 * 3. Modular profile stack mapping: sidewalks, lanes, markings, dividers, shoulders.
 * 4. Automatic viaducts with continuous V-vault and dynamic pillars (1 to 3 based on width).
 * 5. Automatic arched mountain tunnels and terrain clearing.
 */
public final class RoadVoxelizer {

    public enum Kind { ASPHALT, LINE, CURB, SIDEWALK, GRASS, STONE }
    public record Cell(int x, int z, int y, Kind kind, Direction facing, BlockState state, int localX, double t, double nx, double nz) {
        public Cell(int x, int z, int y, Kind kind, Direction facing) {
            this(x, z, y, kind, facing, null, 0, 0.0, 0.0, 0.0);
        }
        public Cell(int x, int z, int y, Kind kind, Direction facing, BlockState state) {
            this(x, z, y, kind, facing, state, 0, 0.0, 0.0, 0.0);
        }
    }

    private record SideCandidate(int bx, int bz, int roadY, double dz, double t, double outX, double outZ, boolean isEndCap) {}
    private record RoadIntersection(Vector3d center, Vector3d t1, Vector3d n1, double hRoadway1, Vector3d t2, Vector3d n2, double hRoadway2) {}

    private static final double EPS = 1e-9;

    private static final BlockState[] SIDEWALK_MIX = new BlockState[]{
            Blocks.STONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
            Blocks.STONE_BRICKS.defaultBlockState()
    };

    private RoadVoxelizer() {}

    public static void voxelizeAndPlace(ServerLevel level, BezierCurve3D curve, ModularRoadPreset preset) {
        Map<BlockPos, BlockState> blocksToSet = calculateRoadBlocks(level, curve, preset);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Voxelized modular road (preset '{}'): placing {} blocks in world...",
                preset.getName(), blocksToSet.size());

        for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Road placement completed.");
    }

    public static void voxelizeAndPlace(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        voxelizeAndPlace(level, curve, preset);
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, ModularRoadPreset preset) {
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        RoadNetworkGraph graph = (savedData != null) ? savedData.getGraph() : null;
        return calculateRoadBlocks((Level) level, curve, preset, graph);
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(Level level, BezierCurve3D curve, ModularRoadPreset preset, RoadNetworkGraph graph) {
        Map<BlockPos, BlockState> blockMap = new HashMap<>();

        // 1. Generate cells for this road curve
        List<Cell> ownCells = planCurveCells(curve, preset);
        if (ownCells.isEmpty()) {
            return blockMap;
        }

        // 2. Gather existing roads from saved graph to resolve intersections & grade separations
        Map<Long, List<Cell>> otherRoadCells = new HashMap<>();
        Set<Long> continuationCellKeys = new HashSet<>();
        Set<Long> otherRoadwayKeys = new HashSet<>();
        Map<Long, Integer> lowerRoadSurfaceY = new HashMap<>();
        Set<Long> lowerRoadClearanceZone = new HashSet<>();
        List<Double> lowerCrossingT = new ArrayList<>();
        Map<Long, Integer> groundYMap = new HashMap<>();
        List<RoadIntersection> intersections = new ArrayList<>();
        double hRoadway1 = preset.getRoadwayWidth() / 2.0;
        boolean p0Connected = false;
        boolean p3Connected = false;
        boolean p0ConnectsToViaduct = false;
        boolean p3ConnectsToViaduct = false;

        if (graph != null) {
            int edgesAtP0 = 0;
            int edgesAtP3 = 0;
            for (RoadEdge edge : graph.getEdges().values()) {
                if (edge.getCurve().equals(curve)) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP0()) < 1.0) {
                    edgesAtP0++;
                }
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP3()) < 1.0) {
                    edgesAtP3++;
                }
            }

            for (RoadEdge edge : graph.getEdges().values()) {
                if (edge.getCurve().equals(curve)) continue;

                double distP0toP0 = edge.getCurve().getP0().distanceTo(curve.getP0());
                double distP0toP3 = edge.getCurve().getP3().distanceTo(curve.getP0());
                boolean sharesP0 = distP0toP0 < 1.0 || distP0toP3 < 1.0;
                if (sharesP0) {
                    p0Connected = true;
                }

                double distP3toP0 = edge.getCurve().getP0().distanceTo(curve.getP3());
                double distP3toP3 = edge.getCurve().getP3().distanceTo(curve.getP3());
                boolean sharesP3 = distP3toP0 < 1.0 || distP3toP3 < 1.0;
                if (sharesP3) {
                    p3Connected = true;
                }

                double dist = edge.getCurve().getP0().distanceTo(curve.getP0());
                if (dist > 300.0 && !sharesP0 && !sharesP3) continue;

                ModularRoadPreset otherPreset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(edge.getProfile().getName()) || p.getName().equals(edge.getProfile().getName()))
                        .findFirst()
                        .orElse(preset);

                double hRoadway2 = otherPreset.getRoadwayWidth() / 2.0;

                boolean isContinuationP0 = false;
                if (sharesP0) {
                    Vector3d vCurve = curve.evaluateTangent(0.0);
                    Vector3d vOther = (distP0toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                    double lC = Math.hypot(vCurve.x, vCurve.z);
                    double lO = Math.hypot(vOther.x, vOther.z);
                    double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : -1.0;
                    // Deflection angle <= 20 degrees means dot <= -cos(20 deg) ~ -0.93969; must also be 2 segments only (not 3+ way junction)
                    if (dot <= -0.93969 && edgesAtP0 == 1) {
                        isContinuationP0 = true;
                    } else {
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP0(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuationP3 = false;
                if (sharesP3) {
                    Vector3d vCurve = curve.evaluateTangent(1.0).multiply(-1.0);
                    Vector3d vOther = (distP3toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                    double lC = Math.hypot(vCurve.x, vCurve.z);
                    double lO = Math.hypot(vOther.x, vOther.z);
                    double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : -1.0;
                    if (dot <= -0.93969 && edgesAtP3 == 1) {
                        isContinuationP3 = true;
                    } else {
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP3(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuation = (sharesP0 && isContinuationP0) || (sharesP3 && isContinuationP3);

                if (sharesP0 && otherPreset.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                    Vector3d connPt = curve.getP0();
                    int gY = groundYMap.computeIfAbsent(coordKey((int) Math.round(connPt.x), (int) Math.round(connPt.z)),
                            k -> getTrueGroundY(level, (int) Math.round(connPt.x), (int) Math.round(connPt.z)));
                    int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, (int) Math.round(connPt.x), (int) Math.round(connPt.z));
                    boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos((int) Math.round(connPt.x), gY, (int) Math.round(connPt.z))).isEmpty();
                    boolean isElevated = (Math.round(connPt.y) - gY) >= 6;
                    if (otherPreset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS || overWater || isElevated) {
                        p0ConnectsToViaduct = true;
                    }
                }
                if (sharesP3 && otherPreset.getViaductMode() != ModularRoadPreset.Mode.OFF) {
                    Vector3d connPt = curve.getP3();
                    int gY = groundYMap.computeIfAbsent(coordKey((int) Math.round(connPt.x), (int) Math.round(connPt.z)),
                            k -> getTrueGroundY(level, (int) Math.round(connPt.x), (int) Math.round(connPt.z)));
                    int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, (int) Math.round(connPt.x), (int) Math.round(connPt.z));
                    boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos((int) Math.round(connPt.x), gY, (int) Math.round(connPt.z))).isEmpty();
                    boolean isElevated = (Math.round(connPt.y) - gY) >= 6;
                    if (otherPreset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS || overWater || isElevated) {
                        p3ConnectsToViaduct = true;
                    }
                }

                List<Cell> theirCells = planCurveCells(edge.getCurve(), otherPreset);
                for (Cell c : theirCells) {
                    long key = coordKey(c.x(), c.z());
                    otherRoadCells.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    if (c.kind() == Kind.ASPHALT || c.kind() == Kind.LINE) {
                        otherRoadwayKeys.add(key);
                    }
                    if (isContinuation) {
                        continuationCellKeys.add(key);
                    }
                }
            }
        }

        // 3. Resolve winners at intersection overlaps & track grade-separated lower roads
        List<Cell> finalCells = new ArrayList<>(ownCells.size());
        for (Cell c : ownCells) {
            long key = coordKey(c.x(), c.z());
            List<Cell> others = otherRoadCells.get(key);

            Cell intersectingOther = null;
            if (others != null) {
                for (Cell other : others) {
                    if (Math.abs(c.y() - other.y()) <= 2) {
                        intersectingOther = other;
                    } else if (other.y() < c.y() - 2) {
                        // Grade separation: another road passes UNDER this road
                        lowerCrossingT.add(c.t());
                        lowerRoadSurfaceY.put(key, other.y());
                        for (int dx = -3; dx <= 3; dx++) {
                            for (int dz = -3; dz <= 3; dz++) {
                                lowerRoadClearanceZone.add(coordKey(c.x() + dx, c.z() + dz));
                            }
                        }
                    }
                }
            }

            Cell resolved = (intersectingOther == null) ? c : resolveWinner(c, intersectingOther);

            // Проверяем, находится ли клетка внутри зоны проезжей части перекрестка (OBB)
            boolean inIntersectionRoadway = false;
            for (RoadIntersection inter : intersections) {
                double dx = c.x() - inter.center.x;
                double dz = c.z() - inter.center.z;
                double u_1 = Math.abs(dx * inter.t1.x + dz * inter.t1.z);
                double v_1 = Math.abs(dx * inter.n1.x + dz * inter.n1.z);
                double u_2 = Math.abs(dx * inter.t2.x + dz * inter.t2.z);
                double v_2 = Math.abs(dx * inter.n2.x + dz * inter.n2.z);

                if (u_1 <= inter.hRoadway2 + 0.5 && v_1 <= inter.hRoadway1 + 0.2) {
                    inIntersectionRoadway = true;
                    break;
                } else if (u_2 <= inter.hRoadway1 + 0.5 && v_2 <= inter.hRoadway2 + 0.2) {
                    inIntersectionRoadway = true;
                    break;
                }
            }

            if (inIntersectionRoadway) {
                // В зоне проезжей части перекрестка:
                // Газоны-разделители, центральные бордюры и линии разметки превращаются в чистый асфальт!
                if (resolved.kind() == Kind.LINE || 
                    resolved.kind() == Kind.CURB || 
                    resolved.kind() == Kind.GRASS) {
                    resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), 
                                        Kind.ASPHALT, resolved.facing(), null, 
                                        resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                }
            } else if (intersectingOther != null && !continuationCellKeys.contains(key)) {
                if (resolved.kind() == Kind.LINE || resolved.kind() == Kind.SIDEWALK || resolved.kind() == Kind.CURB || resolved.kind() == Kind.GRASS) {
                    if (otherRoadwayKeys.contains(key) || c.kind() == Kind.ASPHALT) {
                        resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), Kind.ASPHALT, resolved.facing(), null, resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                    }
                }
            }

            finalCells.add(resolved);
        }

        // 4. Block state definitions
        BlockState roadwayState = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState foundationState = Blocks.STONE.defaultBlockState();
        BlockState curbBlock = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();
        BlockState centerMarkingBlock = Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirtState = Blocks.DIRT.defaultBlockState();

        int totalWidth = preset.getTotalWidth();
        boolean viaductEnabled = preset.getViaductMode() != ModularRoadPreset.Mode.OFF;
        boolean tunnelEnabled = preset.getTunnelMode() != ModularRoadPreset.Mode.OFF;
        LitematicParser.Schematic pillarSchematic = RoadPackRegistry.getInstance().getActivePack().getSchematic("road_opora");
        int[] pillarPositions = preset.getPillarPositions();

        Set<Long> roadDeckColumns = new HashSet<>();
        for (Cell c : finalCells) {
            roadDeckColumns.add(coordKey(c.x(), c.z()));
        }
        for (List<Cell> cells : otherRoadCells.values()) {
            for (Cell c : cells) {
                roadDeckColumns.add(coordKey(c.x(), c.z()));
            }
        }

        // Pre-determine ground height and viaduct state per column with debouncing & overpass preservation
        Map<Long, Boolean> viaductActiveMap = new HashMap<>();

        double totalCurveLen = curve.getApproximateLength();
        int totalStations = (int) Math.ceil(totalCurveLen) + 1;
        boolean[] viaductStations = new boolean[totalStations];

        for (int s = 0; s < totalStations; s++) {
            double dist = Math.min(totalCurveLen, (double) s);
            double tParam = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(tParam);
            int sx = (int) Math.round(pt.x);
            int sz = (int) Math.round(pt.z);
            int sy = (int) Math.round(pt.y);

            long sKey = coordKey(sx, sz);
            int gY = groundYMap.computeIfAbsent(sKey, k -> getTrueGroundY(level, sx, sz));
            int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, sx, sz);
            boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos(sx, gY, sz)).isEmpty();
            boolean isElevated = (sy - gY) >= 6;

            boolean nearLowerRoad = false;
            for (double crossT : lowerCrossingT) {
                if (Math.abs(dist - crossT) <= 10.0) {
                    nearLowerRoad = true;
                    break;
                }
            }

            boolean active = viaductEnabled && (
                    preset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS ||
                    nearLowerRoad ||
                    (preset.getViaductMode() == ModularRoadPreset.Mode.AUTO && (overWater || isElevated))
            );
            viaductStations[s] = active;
        }

        // Viaduct Debouncing: bridge small non-viaduct gaps <= 12m between viaduct sections (eliminates micro-embankments on bridges)
        for (int i = 0; i < totalStations; i++) {
            if (viaductStations[i]) {
                for (int j = i + 2; j < Math.min(totalStations, i + 14); j++) {
                    if (viaductStations[j]) {
                        for (int k = i + 1; k < j; k++) {
                            viaductStations[k] = true;
                        }
                        break;
                    }
                }
            }
        }

        // Precalculate distance along road to the nearest viaduct station (used for smooth 45° taper approaching viaduct)
        double[] distToViaduct = new double[totalStations];
        for (int s = 0; s < totalStations; s++) {
            if (viaductStations[s]) {
                distToViaduct[s] = 0.0;
            } else {
                double minD = Double.MAX_VALUE;
                for (int v = 0; v < totalStations; v++) {
                    if (viaductStations[v]) {
                        minD = Math.min(minD, Math.abs(s - v));
                    }
                }
                distToViaduct[s] = minD;
            }
        }

        for (Cell cell : finalCells) {
            long key = coordKey(cell.x(), cell.z());
            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
            boolean viaductActive = viaductStations[stIdx];
            viaductActiveMap.put(key, viaductActive);
        }

        // 5. Convert cells to Minecraft blocks
        for (Cell cell : finalCells) {
            BlockPos surfacePos = new BlockPos(cell.x(), cell.y(), cell.z());
            long cellKey = coordKey(cell.x(), cell.z());

            BlockState surfaceState;
            if (cell.state() != null) {
                surfaceState = cell.state();
            } else {
                surfaceState = switch (cell.kind()) {
                    case ASPHALT -> roadwayState;
                    case LINE -> orientStairs(centerMarkingBlock, cell.facing(), Half.BOTTOM);
                    case CURB -> orientStairs(curbBlock, cell.facing(), Half.BOTTOM);
                    case GRASS -> grassState;
                    case STONE -> foundationState;
                    case SIDEWALK -> {
                        // 64-битный SplitMix64 хеш-миксер для равномерного белого шума без полос (Bug 11)
                        long h = ((long) cell.x() * 0x9E3779B97F4A7C15L) ^ ((long) cell.z() * 0xBF58476D1CE4E5B9L) ^ ((long) cell.y() * 0x94D049BB133111EBL);
                        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
                        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
                        h = h ^ (h >>> 31);
                        int idx = (int) Math.floorMod(h, (long) SIDEWALK_MIX.length);
                        yield SIDEWALK_MIX[idx];
                    }
                };
            }

            // Surface block
            blockMap.put(surfacePos, surfaceState);

            // Foundation underneath
            if (cell.kind() == Kind.GRASS) {
                blockMap.put(surfacePos.below(), dirtState);
            } else {
                blockMap.put(surfacePos.below(), foundationState);
            }

            // Check if another road passes above this cell
            List<Cell> existingAtCoord = otherRoadCells.get(cellKey);
            boolean roadAbove = false;
            int maxClearanceH = 5;
            if (existingAtCoord != null) {
                for (Cell otherC : existingAtCoord) {
                    if (otherC.y() > cell.y() + 2) {
                        roadAbove = true;
                        maxClearanceH = Math.min(maxClearanceH, otherC.y() - cell.y() - 1);
                    }
                }
            }

            boolean isMountain = false;
            int worldSurfaceAtRoad = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.x(), cell.z());
            int terrainOverhead = worldSurfaceAtRoad - cell.y();

            if (!roadAbove) {
                int solidCount = 0;
                for (int dy = 6; dy <= 10; dy++) {
                    BlockState checkState = level.getBlockState(surfacePos.above(dy));
                    if (!checkState.isAir() && !checkState.is(BlockTags.LEAVES) && !checkState.is(BlockTags.LOGS) && checkState.isSolid()) {
                        solidCount++;
                    }
                }
                // Туннель активируется только если над дорогой есть полноценный массив горы толщиной >= 3 блоков
                if (solidCount >= 3 && terrainOverhead >= 7) {
                    isMountain = true;
                }
            }

            boolean isTunnelSection = tunnelEnabled && (preset.getTunnelMode() == ModularRoadPreset.Mode.ALWAYS || isMountain);

            // Расчет арочного свода туннеля (Bug 8)
            double halfW = Math.max(1.0, (totalWidth - 1) / 2.0);
            double uNorm = (cell.localX() - halfW) / halfW;
            uNorm = Math.max(-1.0, Math.min(1.0, uNorm));
            int archHeight = (int) Math.round(6.0 - 2.0 * (uNorm * uNorm)); // 6 в центре, 4 по краям

            if (roadAbove) {
                // Если сверху проходит другая эстакада/дорога
                for (int h = 1; h <= maxClearanceH; h++) {
                    blockMap.put(surfacePos.above(h), airState);
                }
            } else if (isTunnelSection) {
                // Внутри туннеля: очищаем воздух под арочным сводом
                for (int h = 1; h <= archHeight; h++) {
                    blockMap.put(surfacePos.above(h), airState);
                }
                // Каменный свод туннеля толщиной в 2 блока
                blockMap.put(surfacePos.above(archHeight + 1), foundationState);
                blockMap.put(surfacePos.above(archHeight + 2), foundationState);

                // Вертикальные боковые стены туннеля
                if (cell.localX() == 0 || cell.localX() == totalWidth - 1) {
                    for (int h = 1; h <= archHeight; h++) {
                        blockMap.put(surfacePos.above(h), foundationState);
                    }
                }
            } else {
                // Открытый участок или въездная выемка в склоне (open-cut)
                // Очищаем воздух до поверхности земли + 2 блока, устраняя нависающий дерн
                int clearH = Math.max(maxClearanceH, terrainOverhead + 2);
                for (int h = 1; h <= clearH; h++) {
                    blockMap.put(surfacePos.above(h), airState);
                }
                // Подпорная стенка при глубокой врезке в склон на открытом воздухе
                if (terrainOverhead >= 2 && (cell.localX() == 0 || cell.localX() == totalWidth - 1)) {
                    for (int h = 1; h <= terrainOverhead; h++) {
                        blockMap.put(surfacePos.above(h), foundationState);
                    }
                }
            }

            int terrainSurfaceY = groundYMap.computeIfAbsent(cellKey, k -> getTrueGroundY(level, cell.x(), cell.z()));
            boolean viaductActive = viaductActiveMap.getOrDefault(cellKey, false);
            boolean isOverLowerRoadCell = lowerRoadClearanceZone.contains(cellKey);

            if (viaductActive && !isTunnelSection) {
                for (int px : pillarPositions) {
                    // 1. Continuous V-vault underneath at y - 2 and y - 3 along the entire road deck
                    if (cell.localX() == px) {
                        Direction stairFacing = facingFromVector(cell.nx(), cell.nz());
                        BlockState vaultStair = orientStairs(curbBlock, stairFacing, Half.TOP);
                        blockMap.put(surfacePos.below(2), vaultStair);
                    } else if (cell.localX() >= px + 1 && cell.localX() <= px + 5) {
                        blockMap.put(surfacePos.below(2), foundationState);
                        blockMap.put(surfacePos.below(3), foundationState);
                    } else if (cell.localX() == px + 6) {
                        Direction stairFacing = facingFromVector(-cell.nx(), -cell.nz());
                        BlockState vaultStair = orientStairs(curbBlock, stairFacing, Half.TOP);
                        blockMap.put(surfacePos.below(2), vaultStair);
                    }

                    // 2. Dynamic pillars emerging from the V-vault at regular intervals (every 18 blocks)
                    // Skip pillars that would collide with or block a lower road
                    if (!isOverLowerRoadCell) {
                        int pillarCycle = Math.floorMod((int) Math.round(cell.t()), 18);
                        if (pillarCycle < 4) {
                            int alongIdx = pillarCycle;
                            if (cell.localX() >= px && cell.localX() <= px + 6) {
                                int zSchem = cell.localX() - px;
                                if (pillarSchematic != null) {
                                    for (int sy = 0; sy <= 4; sy++) {
                                        BlockState ps = pillarSchematic.getBlock(9 + alongIdx, sy, zSchem);
                                        if (!ps.isAir()) {
                                            BlockState oriented = ps;
                                            if (ps.getBlock() instanceof StairBlock) {
                                                Direction origDir = ps.getValue(StairBlock.FACING);
                                                Half origHalf = ps.getValue(StairBlock.HALF);
                                                Direction mappedDir = (origDir == Direction.NORTH)
                                                        ? facingFromVector(-cell.nx(), -cell.nz())
                                                        : (origDir == Direction.SOUTH)
                                                            ? facingFromVector(cell.nx(), cell.nz())
                                                            : cell.facing();
                                                oriented = orientStairs(ps, mappedDir, origHalf);
                                            }
                                            blockMap.put(new BlockPos(cell.x(), cell.y() - 8 + sy, cell.z()), oriented);
                                        }
                                    }
                                } else {
                                    if (cell.localX() >= px + 1 && cell.localX() <= px + 5) {
                                        blockMap.put(surfacePos.below(4), foundationState);
                                    }
                                    if (cell.localX() >= px + 2 && cell.localX() <= px + 4) {
                                        for (int dy = 5; dy <= 8; dy++) {
                                            blockMap.put(surfacePos.below(dy), foundationState);
                                        }
                                    }
                                }

                                // Column extending down to ocean floor / terrain below y - 8 (continues under water!)
                                if (cell.localX() >= px + 2 && cell.localX() <= px + 4) {
                                    int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, cell.x(), cell.z());
                                    int stopY = Math.max(level.getMinBuildHeight(), oceanFloorY);
                                    for (int py = cell.y() - 9; py >= stopY; py--) {
                                        blockMap.put(new BlockPos(cell.x(), py, cell.z()), foundationState);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Embankment foundation under the road deck (strictly forbidden over lower roads or in water)
            if (!viaductActive && !isTunnelSection && !isOverLowerRoadCell) {
                BlockPos tp = new BlockPos(cell.x(), terrainSurfaceY, cell.z());
                boolean isWater = !level.getFluidState(tp).isEmpty() || level.getBlockState(tp).is(Blocks.WATER);
                if (!isWater && cell.y() - 1 > terrainSurfaceY) {
                    for (int ey = cell.y() - 2; ey >= terrainSurfaceY; ey--) {
                        BlockPos fp = new BlockPos(cell.x(), ey, cell.z());
                        blockMap.put(fp, dirtState);
                    }
                }
            }

            // Clear underpass clearance between upper bridge deck and lower road
            if (isOverLowerRoadCell) {
                int lowerY = lowerRoadSurfaceY.getOrDefault(cellKey, terrainSurfaceY);
                for (int ey = lowerY + 1; ey <= cell.y() - 2; ey++) {
                    blockMap.put(new BlockPos(cell.x(), ey, cell.z()), airState);
                }
            }

            // Bridge Abutment (Устой моста) and Road Endings - 1-2 block reinforcement under deck, zero monolithic walls to ground
            boolean isAbutmentOrEnd = false;
            if (cell.t() < 1.0 && !p0Connected) {
                isAbutmentOrEnd = true;
            } else if (cell.t() > totalCurveLen - 1.0 && !p3Connected) {
                isAbutmentOrEnd = true;
            } else {
                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
                boolean prevV = (stIdx > 0) && viaductStations[stIdx - 1];
                boolean nextV = (stIdx < totalStations - 1) && viaductStations[stIdx + 1];
                if (prevV != viaductActive || nextV != viaductActive) {
                    isAbutmentOrEnd = true;
                }
            }

            if (isAbutmentOrEnd && !isTunnelSection && !isOverLowerRoadCell && cell.y() - 1 > terrainSurfaceY) {
                int minReinforceY = Math.max(terrainSurfaceY, cell.y() - 2);
                for (int ey = cell.y() - 1; ey >= minReinforceY; ey--) {
                    BlockPos ap = new BlockPos(cell.x(), ey, cell.z());
                    blockMap.put(ap, foundationState);
                }
            }
        }

        // 6. Generate Continuous Side Embankments (road_nasip) and Slopes (road_otkos) - zero holes, gap-free
        LitematicParser.Schematic nasip = RoadPackRegistry.getInstance().getActivePack().getSchematic("road_nasip");
        LitematicParser.Schematic otkos = RoadPackRegistry.getInstance().getActivePack().getSchematic("road_otkos");

        boolean embankmentEnabled = preset.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF;
        boolean slopeEnabled = preset.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF;

        if (embankmentEnabled || slopeEnabled) {
            int nasipW = (nasip != null) ? nasip.getSizeZ() : 7;
            int nasipH = (nasip != null) ? nasip.getSizeY() : 5;
            int otkosW = (otkos != null) ? otkos.getSizeZ() : 2;
            int otkosH = (otkos != null) ? otkos.getSizeY() : 4;
            double maxRadius = embankmentEnabled ? 14.0 : 3.0;
            double halfW = totalWidth / 2.0;

            Map<Long, SideCandidate> sideCandidates = new HashMap<>();

            // A. Sample along curve at fine 0.5m intervals to guarantee 100% gap-free grid coverage
            double stepLen = 0.5;
            for (double s = 0; s <= totalCurveLen; s += stepLen) {
                double tParam = curve.getArcLengthTable().getTForDistance(s);
                Vector3d pt = curve.evaluate(tParam);
                Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                int roadY = (int) Math.round(pt.y);

                for (double sign : new double[]{-1.0, 1.0}) {
                    double outX = norm.x * sign;
                    double outZ = norm.z * sign;

                    for (double d = 0.5; d <= maxRadius; d += 0.5) {
                        int bx = (int) Math.round(pt.x + outX * (halfW + d));
                        int bz = (int) Math.round(pt.z + outZ * (halfW + d));
                        long k = coordKey(bx, bz);

                        SideCandidate ex = sideCandidates.get(k);
                        if (ex == null || d < ex.dz()) {
                            sideCandidates.put(k, new SideCandidate(bx, bz, roadY, d, s, outX, outZ, false));
                        }
                    }
                }
            }

            // B. Solid Grid Scan for end-cap rounding at P0 and P3 (zero holes, zero ray-stepping artifacts)
            boolean shouldRoundP0 = (!p0Connected || p0ConnectsToViaduct) && !viaductStations[0];
            boolean shouldRoundP3 = (!p3Connected || p3ConnectsToViaduct) && !viaductStations[totalStations - 1];

            Vector3d p0 = curve.getP0();
            Vector3d tan0 = curve.evaluateTangent(0.0);
            double t0Len = Math.hypot(tan0.x, tan0.z);
            double t0x = (t0Len > 1e-6) ? (tan0.x / t0Len) : 0.0;
            double t0z = (t0Len > 1e-6) ? (tan0.z / t0Len) : 1.0;
            int roadY0 = (int) Math.round(p0.y);

            if (shouldRoundP0) {
                double Rmax = halfW + maxRadius;
                int minX = (int) Math.floor(p0.x - Rmax);
                int maxX = (int) Math.ceil(p0.x + Rmax);
                int minZ = (int) Math.floor(p0.z - Rmax);
                int maxZ = (int) Math.ceil(p0.z + Rmax);

                for (int bx = minX; bx <= maxX; bx++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        double dx = bx - p0.x;
                        double dz = bz - p0.z;
                        // Half-space check: block is behind P0 (in direction of -T0)
                        double projBack = dx * (-t0x) + dz * (-t0z);
                        if (projBack < -0.01) continue;

                        double R = Math.hypot(dx, dz);
                        if (R > halfW && R <= Rmax) {
                            double d = R - halfW;
                            double dirX = (R > 1e-6) ? (dx / R) : -t0x;
                            double dirZ = (R > 1e-6) ? (dz / R) : -t0z;
                            long k = coordKey(bx, bz);
                            SideCandidate ex = sideCandidates.get(k);
                            if (ex == null || d < ex.dz()) {
                                sideCandidates.put(k, new SideCandidate(bx, bz, roadY0, d, 0.0, dirX, dirZ, true));
                            }
                        }
                    }
                }
            }

            Vector3d p3 = curve.getP3();
            Vector3d tan3 = curve.evaluateTangent(1.0);
            double t3Len = Math.hypot(tan3.x, tan3.z);
            double t3x = (t3Len > 1e-6) ? (tan3.x / t3Len) : 0.0;
            double t3z = (t3Len > 1e-6) ? (tan3.z / t3Len) : 1.0;
            int roadY3 = (int) Math.round(p3.y);

            if (shouldRoundP3) {
                double Rmax = halfW + maxRadius;
                int minX = (int) Math.floor(p3.x - Rmax);
                int maxX = (int) Math.ceil(p3.x + Rmax);
                int minZ = (int) Math.floor(p3.z - Rmax);
                int maxZ = (int) Math.ceil(p3.z + Rmax);

                for (int bx = minX; bx <= maxX; bx++) {
                    for (int bz = minZ; bz <= maxZ; bz++) {
                        double dx = bx - p3.x;
                        double dz = bz - p3.z;
                        // Half-space check: block is ahead of P3 (in direction of +T3)
                        double projFwd = dx * t3x + dz * t3z;
                        if (projFwd < -0.01) continue;

                        double R = Math.hypot(dx, dz);
                        if (R > halfW && R <= Rmax) {
                            double d = R - halfW;
                            double dirX = (R > 1e-6) ? (dx / R) : t3x;
                            double dirZ = (R > 1e-6) ? (dz / R) : t3z;
                            long k = coordKey(bx, bz);
                            SideCandidate ex = sideCandidates.get(k);
                            if (ex == null || d < ex.dz()) {
                                sideCandidates.put(k, new SideCandidate(bx, bz, roadY3, d, totalCurveLen, dirX, dirZ, true));
                            }
                        }
                    }
                }
            }

            // C. Place side embankments and slopes independently per column (never broken by adjacent terrain)
            for (SideCandidate cand : sideCandidates.values()) {
                long colKey = coordKey(cand.bx(), cand.bz());
                if (roadDeckColumns.contains(colKey)) continue;
                if (lowerRoadClearanceZone.contains(colKey)) continue;

                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cand.t())));
                if (viaductStations[stIdx]) continue; // Viaduct bridge slice: no side embankment!

                int groundY = groundYMap.computeIfAbsent(colKey, k -> getTrueGroundY(level, cand.bx(), cand.bz()));
                int roadY = cand.roadY();
                double dz = cand.dz();

                // Smooth 45° taper approaching viaduct: drop topY by 1 block per meter within 4 blocks of viaduct
                int topY = (dz <= 2.0) ? roadY : (roadY - (int) Math.round(dz - 2.0));
                double dToViaduct = distToViaduct[stIdx];
                if (dToViaduct <= 4.0) {
                    int taperDrop = (int) Math.round(4.0 - dToViaduct);
                    if (taperDrop > 0) {
                        topY -= taperDrop;
                    }
                }

                if (topY < groundY) {
                    // Hillside cut -> slope (road_otkos). Slopes NEVER round around end-caps!
                    if (!cand.isEndCap() && slopeEnabled && dz <= 2.5 && (groundY - roadY >= 2)) {
                        int sx = (otkos != null) ? Math.floorMod((int) Math.round(cand.t()), otkos.getSizeX()) : 0;
                        int schemZ = (int) Math.max(0, Math.min(otkosW - 1, Math.round(dz - 0.5)));

                        // 1. Retaining wall from roadY - 2 up to groundY + 1 (guaranteed stone replacement, no hanging dirt)
                        for (int targetY = roadY - 2; targetY <= groundY + 1; targetY++) {
                            BlockState st;
                            if (targetY <= roadY) {
                                st = foundationState;
                            } else {
                                int sy = (targetY - (roadY + 1)) % otkosH;
                                st = (otkos != null) ? otkos.getBlock(sx, sy, schemZ) : foundationState;
                                if (st == null || st.isAir()) {
                                    st = foundationState;
                                } else if (st.getBlock() instanceof StairBlock) {
                                    Direction dir = facingFromVector(-cand.outX(), -cand.outZ());
                                    st = orientStairs(st, dir, Half.BOTTOM);
                                }
                            }
                            blockMap.put(new BlockPos(cand.bx(), targetY, cand.bz()), st);
                        }

                        // 2. Air clearing above the retaining wall up to the sky
                        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, cand.bx(), cand.bz());
                        int clearTopY = Math.max(groundY + 4, worldSurfaceY + 1);
                        for (int ay = groundY + 2; ay <= clearTopY; ay++) {
                            blockMap.put(new BlockPos(cand.bx(), ay, cand.bz()), airState);
                        }
                    }
                } else {
                    // Embankment (road_nasip)
                    // 1. Height threshold: only build embankment where road is elevated by at least 2 blocks over ground
                    if (roadY - groundY < 2) {
                        continue;
                    }

                    // 2. Prohibit embankment in water (Bug 15)
                    BlockPos groundPos = new BlockPos(cand.bx(), groundY, cand.bz());
                    BlockState groundState = level.getBlockState(groundPos);
                    boolean isWater = !groundState.getFluidState().isEmpty()
                            || groundState.is(Blocks.WATER)
                            || groundState.getFluidState().is(FluidTags.WATER)
                            || !level.getFluidState(groundPos).isEmpty()
                            || !level.getFluidState(groundPos.above()).isEmpty()
                            || level.getBlockState(groundPos.above()).is(Blocks.WATER);
                    if (isWater) {
                        continue;
                    }

                    // 3. Embankment placement (seamless against sidewalk curb at roadY, zero gap)
                    if (embankmentEnabled) {
                        if (dz < nasipW && nasip != null) {
                            int sx = Math.floorMod((int) Math.round(cand.t()), nasip.getSizeX());
                            int schemZ = (int) Math.max(0, Math.min(nasipW - 1, (int) Math.floor(dz)));
                            int highestPlacedY = Integer.MIN_VALUE;
                            for (int sy = 0; sy < nasipH; sy++) {
                                int blockY = roadY - (4 - sy);
                                if (blockY >= groundY && blockY <= topY) {
                                    BlockState st = nasip.getBlock(sx, sy, schemZ);
                                    if (!st.isAir()) {
                                        if (st.getBlock() instanceof StairBlock) {
                                            Direction dir = facingFromVector(cand.outX(), cand.outZ());
                                            st = orientStairs(st, dir, Half.BOTTOM);
                                        }
                                        blockMap.put(new BlockPos(cand.bx(), blockY, cand.bz()), st);
                                        highestPlacedY = Math.max(highestPlacedY, blockY);
                                    }
                                }
                            }
                            int lowestSchemY = roadY - 4;
                            int maxDirtY = Math.min(lowestSchemY - 1, topY);
                            if (maxDirtY >= groundY) {
                                if (highestPlacedY == Integer.MIN_VALUE) {
                                    blockMap.put(new BlockPos(cand.bx(), maxDirtY, cand.bz()), grassState);
                                    maxDirtY--;
                                }
                                for (int fy = maxDirtY; fy >= groundY; fy--) {
                                    blockMap.put(new BlockPos(cand.bx(), fy, cand.bz()), dirtState);
                                }
                            }
                        } else {
                            // Extended slope down to terrain
                            if (topY >= groundY) {
                                blockMap.put(new BlockPos(cand.bx(), topY, cand.bz()), grassState);
                                for (int fy = topY - 1; fy >= groundY; fy--) {
                                    blockMap.put(new BlockPos(cand.bx(), fy, cand.bz()), dirtState);
                                }
                            }
                        }
                    }
                }
            }
        }

        return blockMap;
    }

    public static int getTrueGroundY(Level level, int x, int z) {
        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);

        for (int y = startY; y >= level.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) continue;

            // Water surface counts as solid ground level
            if (!state.getFluidState().isEmpty() || state.is(Blocks.WATER)) {
                return y;
            }

            // Skip leaves and tree foliage
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                continue;
            }

            // Skip tree logs
            if (state.is(BlockTags.LOGS) || state.getBlock() instanceof RotatedPillarBlock) {
                continue;
            }

            // Skip flowers, tall grass, crops, mushrooms, replaceable plants
            if (state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.REPLACEABLE) ||
                state.getBlock() instanceof BushBlock ||
                state.getBlock() instanceof DoublePlantBlock ||
                !state.blocksMotion()) {
                continue;
            }

            // True solid ground (grass block, dirt, stone, sand, gravel, terracotta, etc.)
            return y;
        }
        return level.getMinBuildHeight();
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        return calculateRoadBlocks(level, curve, preset);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset) {
        List<Cell> out = new ArrayList<>();

        double totalLen = curve.getApproximateLength();
        if (totalLen < 0.5) return out;

        List<RoadStackElement> stack = preset.getStack();
        if (stack.isEmpty()) return out;

        int totalW = preset.getTotalWidth();
        double halfWidth = totalW / 2.0;

        int numSamples = Math.max(8, (int) Math.ceil(totalLen / 0.5));
        double[] px = new double[numSamples + 1];
        double[] py = new double[numSamples + 1];
        double[] pz = new double[numSamples + 1];
        double[] cum = new double[numSamples + 1];

        for (int i = 0; i <= numSamples; i++) {
            double dist = (double) i / numSamples * totalLen;
            double t = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(t);
            px[i] = pt.x;
            py[i] = pt.y;
            pz[i] = pt.z;
            cum[i] = dist;
        }

        int segs = numSamples;

        // Cumulative intervals for each stack element
        int numElems = stack.size();
        double[] elemStart = new double[numElems];
        double[] elemEnd = new double[numElems];
        double cur = -halfWidth;
        for (int i = 0; i < numElems; i++) {
            elemStart[i] = cur;
            cur += stack.get(i).getWidth();
            elemEnd[i] = cur;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i <= segs; i++) {
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }
        int r = (int) Math.ceil(halfWidth) + 1;
        int x0 = (int) Math.floor(minX) - r, x1 = (int) Math.ceil(maxX) + r;
        int z0 = (int) Math.floor(minZ) - r, z1 = (int) Math.ceil(maxZ) + r;

        Vector3d p0 = curve.getP0();
        Vector3d p3 = curve.getP3();
        Vector3d tan0 = curve.evaluateTangent(0.0);
        Vector3d tan3 = curve.evaluateTangent(1.0);

        double t0Len = Math.hypot(tan0.x, tan0.z);
        double t0x = t0Len > 1e-6 ? tan0.x / t0Len : 0.0;
        double t0z = t0Len > 1e-6 ? tan0.z / t0Len : 1.0;

        double t3Len = Math.hypot(tan3.x, tan3.z);
        double t3x = t3Len > 1e-6 ? tan3.x / t3Len : 0.0;
        double t3z = t3Len > 1e-6 ? tan3.z / t3Len : 1.0;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                double bestD2 = Double.MAX_VALUE, bestRaw = 0, bestU = 0;
                int best = -1;
                for (int i = 0; i < segs; i++) {
                    double ax = px[i], az = pz[i];
                    double vx = px[i + 1] - ax, vz = pz[i + 1] - az;
                    double len2 = vx * vx + vz * vz;
                    if (len2 < 1e-12) continue;
                    double wx = x - ax, wz = z - az;
                    double raw = (wx * vx + wz * vz) / len2;
                    double u = Math.max(0.0, Math.min(1.0, raw));
                    double dx = wx - u * vx, dz = wz - u * vz;
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = i;
                        bestRaw = raw;
                        bestU = u;
                    }
                }

                if (best < 0 || bestD2 > (halfWidth + 0.5) * (halfWidth + 0.5)) continue;

                // Strict half-space bounding planes perpendicular to road centerline at curve start and end (Bug 1)
                double startProj = (x - p0.x) * t0x + (z - p0.z) * t0z;
                double endProj = (x - p3.x) * t3x + (z - p3.z) * t3z;
                if (startProj < -0.01 || endProj > 0.01) {
                    continue;
                }

                double vx = px[best + 1] - px[best];
                double vz = pz[best + 1] - pz[best];
                double segLen = Math.hypot(vx, vz);
                if (segLen < 1e-6) continue;
                double ux = vx / segLen, uz = vz / segLen;
                double nx = -uz, nz = ux;

                double wx = x - px[best], wz = z - pz[best];
                double cross = ux * wz - uz * wx;
                double s = Math.sqrt(bestD2) * (cross >= 0 ? 1 : -1);
                double t = cum[best] + bestU * segLen;

                int cellY = (int) Math.round(py[best] + bestU * (py[best + 1] - py[best]));

                // Фактор нормализации шага сетки для диагоналей (Bug 16)
                double normFactor = Math.max(Math.abs(nx), Math.abs(nz));
                if (normFactor < 1e-5) normFactor = 1.0;
                double sGrid = s / normFactor;

                if (sGrid < -halfWidth - 0.2 || sGrid >= halfWidth + 0.2) continue;

                int elemIdx = -1;
                for (int i = 0; i < numElems; i++) {
                    if (sGrid >= elemStart[i] - EPS && sGrid < elemEnd[i] + EPS) {
                        elemIdx = i;
                        break;
                    }
                }
                if (elemIdx < 0) {
                    if (sGrid < elemStart[0]) elemIdx = 0;
                    else elemIdx = numElems - 1;
                }

                RoadStackElement elem = stack.get(elemIdx);
                RoadElementType type = elem.getType();
                int elemW = elem.getWidth();
                int localX = (int) Math.floor(sGrid - elemStart[elemIdx]);
                localX = Math.max(0, Math.min(elemW - 1, localX));
                boolean isLeftSide = (sGrid < 0);

                Direction facingOutward = facingFromVector(nx * (isLeftSide ? -1 : 1), nz * (isLeftSide ? -1 : 1));

                int roadLocalX = (int) Math.floor(sGrid + halfWidth);
                roadLocalX = Math.max(0, Math.min(totalW - 1, roadLocalX));

                switch (type) {
                    case SIDEWALK -> {
                        out.add(new Cell(x, z, cellY, Kind.SIDEWALK, Direction.NORTH, null, roadLocalX, t, nx, nz));
                    }
                    case CURB -> {
                        out.add(new Cell(x, z, cellY, Kind.CURB, facingOutward, null, roadLocalX, t, nx, nz));
                    }
                    case LANE -> {
                        out.add(new Cell(x, z, cellY, Kind.ASPHALT, Direction.NORTH, null, roadLocalX, t, nx, nz));
                    }
                    case SHOULDER -> {
                        int stoneDx = isLeftSide ? (elemW - 1) : 0;
                        if (localX == stoneDx) {
                            out.add(new Cell(x, z, cellY, Kind.STONE, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        }
                    }
                    case DIVIDER_GRASS -> {
                        if (localX == 0) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                        } else if (localX == elemW - 1) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx, -nz), null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        }
                    }
                    case MARKING_SOLID -> {
                        out.add(new Cell(x, z, cellY, Kind.LINE, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                    }
                    case MARKING_DOUBLE_SOLID -> {
                        if (localX == 1) {
                            out.add(new Cell(x, z, cellY, Kind.ASPHALT, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.LINE, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                        }
                    }
                    case MARKING_DASHED_LONG -> {
                        boolean isDash = Math.floorMod((long) Math.floor(t), 4) < 3;
                        out.add(new Cell(x, z, cellY, isDash ? Kind.LINE : Kind.ASPHALT, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                    }
                    case MARKING_DASHED_SHORT -> {
                        boolean isDash = Math.floorMod((long) Math.floor(t), 2) == 0;
                        out.add(new Cell(x, z, cellY, isDash ? Kind.LINE : Kind.ASPHALT, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                    }
                }
            }
        }

        return out;
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        return planCurveCells(curve, preset);
    }

    private static long coordKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int rank(Kind k) {
        return switch (k) {
            case GRASS -> 0;
            case SIDEWALK -> 1;
            case CURB -> 2;
            case STONE -> 3;
            case LINE -> 4;
            case ASPHALT -> 5;
        };
    }

    private static Cell resolveWinner(Cell a, Cell b) {
        return rank(a.kind()) >= rank(b.kind()) ? a : b;
    }

    private static Direction facingFromVector(double ox, double oz) {
        if (Math.abs(ox) > Math.abs(oz)) {
            return ox > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return oz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private static BlockState orientStairs(BlockState state, Direction facing, Half half) {
        if (state.getBlock() instanceof StairBlock) {
            return state.setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, half)
                    .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        }
        return state;
    }
}
