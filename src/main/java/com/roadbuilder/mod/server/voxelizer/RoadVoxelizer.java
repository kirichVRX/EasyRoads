package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Procedural road planner and voxelizer built directly on the proven geometry math of TUT:
 * 1. Discrete segment sampling with exact perpendicular distance calculation.
 * 2. Grid-aligned normalization factor m = max(|nx|, |nz|) ensuring curbs and lines are EXACTLY 1 block wide on any curve angle.
 * 3. Cell-chain block counting for dashed lines (avoids Euclidean distance stretching/bunching).
 * 4. Priority-based cell intersection merging: ASPHALT > LINE > CURB > SIDEWALK, smoothly connecting roads without ugly blobs.
 * 5. Stone foundation beneath concrete powder and clear air space above.
 */
public final class RoadVoxelizer {

    public enum Kind { ASPHALT, LINE, CURB, SIDEWALK }
    public record Cell(int x, int z, int y, Kind kind, Direction facing) {}
    private record LineCandidate(int x, int z, int y, double t, Direction facing) {}

    private static final double EPS = 1e-9;
    private static final RandomSource RANDOM = RandomSource.create();

    private RoadVoxelizer() {}

    public static void voxelizeAndPlace(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        Map<BlockPos, BlockState> blocksToSet = calculateRoadBlocks(level, curve, profile);

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Voxelized road: placing {} blocks in world...", blocksToSet.size());

        for (Map.Entry<BlockPos, BlockState> entry : blocksToSet.entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
        }

        RoadBuilderMod.LOGGER.info("[RoadBuilder] Road placement completed.");
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        Map<BlockPos, BlockState> blockMap = new HashMap<>();

        // 1. Generate cells for this road curve
        List<Cell> ownCells = planCurveCells(curve, profile);
        if (ownCells.isEmpty()) {
            return blockMap;
        }

        // 2. Gather existing roads from saved graph to resolve intersections
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        Map<Long, List<Cell>> otherRoadCells = new HashMap<>();
        // Record which cells belong to an end-to-end continuation vs a cross intersection
        Set<Long> continuationCellKeys = new HashSet<>();

        for (RoadEdge edge : savedData.getGraph().getEdges().values()) {
            // Skip the current curve if it was already registered
            if (edge.getCurve().equals(curve)) continue;

            // Only check nearby edges
            double dist = edge.getCurve().getP0().distanceTo(curve.getP0());
            if (dist > 300.0) continue;

            // Check if this edge shares an endpoint with the current curve (continuation)
            boolean sharesP0 = edge.getCurve().getP0().distanceTo(curve.getP0()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP0()) < 1.0;
            boolean sharesP3 = edge.getCurve().getP0().distanceTo(curve.getP3()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP3()) < 1.0;
            boolean isContinuation = sharesP0 || sharesP3;

            List<Cell> theirCells = planCurveCells(edge.getCurve(), edge.getProfile());
            for (Cell c : theirCells) {
                long key = coordKey(c.x(), c.z());
                otherRoadCells.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                if (isContinuation) {
                    continuationCellKeys.add(key);
                }
            }
        }

        // 3. Resolve winners at intersection overlaps (ONLY IF physically at the same elevation!)
        List<Cell> finalCells = new ArrayList<>(ownCells.size());
        for (Cell c : ownCells) {
            long key = coordKey(c.x(), c.z());
            List<Cell> others = otherRoadCells.get(key);

            // Find an intersecting cell only if within physical reach (e.g. |deltaY| <= 2 blocks)
            Cell intersectingOther = null;
            if (others != null) {
                for (Cell other : others) {
                    if (Math.abs(c.y() - other.y()) <= 2) {
                        intersectingOther = other;
                        break;
                    }
                }
            }

            if (intersectingOther == null) {
                // No physical intersection on this elevation! Bridge / overpass / tunnel keeps its own geometry completely intact!
                finalCells.add(c);
            } else {
                Cell resolved = resolveWinner(c, intersectingOther);
                // At TRUE level intersections/junctions (not smooth end-to-end continuations),
                // suppress center marking inside roadway
                if (!continuationCellKeys.contains(key)) {
                    if (resolved.kind() == Kind.LINE) {
                        resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), Kind.ASPHALT, resolved.facing());
                    }
                }
                finalCells.add(resolved);
            }
        }

        // 4. Resolve block states
        BlockState roadwayState = resolveBlockState(profile.getSurfaceBlockId(), Blocks.GRAY_CONCRETE_POWDER.defaultBlockState());
        BlockState foundationState = resolveBlockState(profile.getFoundationBlockId(), Blocks.STONE.defaultBlockState());
        BlockState curbBlock = resolveBlockState(profile.getCurbBlockId(), Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState());
        BlockState centerMarkingBlock = resolveBlockState(profile.getCenterMarkingBlockId(), Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState());
        BlockState pillarState = resolveBlockState(profile.getPillarBlockId(), Blocks.STONE_BRICKS.defaultBlockState());
        BlockState tunnelWallState = resolveBlockState(profile.getTunnelWallBlockId(), Blocks.STONE_BRICKS.defaultBlockState());
        BlockState airState = Blocks.AIR.defaultBlockState();

        List<BlockState> sidewalkStates = new ArrayList<>();
        if (profile.hasSidewalks() && !profile.getSidewalkBlockIds().isEmpty()) {
            for (String id : profile.getSidewalkBlockIds()) {
                sidewalkStates.add(resolveBlockState(id, Blocks.STONE.defaultBlockState()));
            }
        } else {
            sidewalkStates.add(Blocks.STONE.defaultBlockState());
        }

        // 5. Convert cells to Minecraft blocks
        for (Cell cell : finalCells) {
            BlockPos surfacePos = new BlockPos(cell.x(), cell.y(), cell.z());
            long cellKey = coordKey(cell.x(), cell.z());

            BlockState surfaceState = switch (cell.kind()) {
                case ASPHALT -> roadwayState;
                case LINE -> orientStairs(centerMarkingBlock, cell.facing(), Half.BOTTOM);
                case CURB -> orientStairs(curbBlock, cell.facing(), Half.BOTTOM);
                case SIDEWALK -> {
                    int hash = Math.abs((cell.x() * 312987 ^ cell.z() * 116129 ^ cell.y() * 8461));
                    yield sidewalkStates.get(hash % sidewalkStates.size());
                }
            };

            // Surface block
            blockMap.put(surfacePos, surfaceState);

            // Solid stone foundation beneath surface so concrete powder never falls
            blockMap.put(surfacePos.below(), foundationState);

            // Clear air space above road (Rule 13)
            int terrainSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.x(), cell.z());
            boolean isTunnel = profile.isCreateTunnels() && (terrainSurfaceY > cell.y() + profile.getClearanceHeight());
            int clearance = isTunnel ? profile.getTunnelCeilingHeight() : profile.getClearanceHeight();
            for (int h = 1; h <= clearance; h++) {
                BlockPos airPos = surfacePos.above(h);
                // Don't overwrite an existing road segment above with AIR
                List<Cell> existingAtCoord = otherRoadCells.get(cellKey);
                boolean roadAbove = false;
                if (existingAtCoord != null) {
                    for (Cell otherC : existingAtCoord) {
                        if (otherC.y() == airPos.getY()) {
                            roadAbove = true;
                            break;
                        }
                    }
                }
                if (!roadAbove) {
                    blockMap.put(airPos, airState);
                }
            }

            // Mountain tunnels
            if (isTunnel) {
                blockMap.put(surfacePos.above(profile.getTunnelCeilingHeight() + 1), tunnelWallState);
                if (cell.kind() == Kind.SIDEWALK) {
                    for (int h = 1; h <= profile.getTunnelCeilingHeight(); h++) {
                        blockMap.put(surfacePos.above(h), tunnelWallState);
                    }
                }
            }

            // Elevated viaduct / bridge pillars
            boolean isElevated = cell.y() > terrainSurfaceY + 2;
            if (isElevated && profile.isCreatePillars() && cell.kind() == Kind.LINE) {
                // Place pillar every pillarSpacing blocks
                int pSpacing = (int) profile.getPillarSpacing();
                if (Math.abs(cell.x() + cell.z()) % pSpacing == 0) {
                    // Check if there is an existing road below us to avoid punching a pillar straight into the lower road's lane!
                    int stopY = Math.max(level.getMinBuildHeight(), terrainSurfaceY);
                    List<Cell> existingAtCoord = otherRoadCells.get(cellKey);
                    if (existingAtCoord != null) {
                        for (Cell otherC : existingAtCoord) {
                            if (otherC.y() < cell.y() && otherC.y() + 4 > stopY) {
                                // Stop pillar above clearance height of the road below
                                stopY = otherC.y() + 4;
                            }
                        }
                    }

                    for (int py = cell.y() - 2; py >= stopY; py--) {
                        blockMap.put(new BlockPos(cell.x(), py, cell.z()), pillarState);
                    }
                }
            }
        }

        return blockMap;
    }

    /**
     * Discrete path sampling and area grid planning inspired by TUT's proven geometry engine.
     */
    public static List<Cell> planCurveCells(BezierCurve3D curve, RoadProfile profile) {
        List<Cell> out = new ArrayList<>();
        List<LineCandidate> line = new ArrayList<>();

        double totalLen = curve.getApproximateLength();
        if (totalLen < 0.5) return out;

        // Sample curve into dense polyline path (~0.5m step)
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

        // Dimensions:
        // Center Line: 1 block wide -> half = 0.5
        // Lanes: 4 blocks each -> laneEnd = 0.5 + 4 = 4.5
        // Curb: 1 block -> curbCenter = 4.5 + 0.5 = 5.0, curbEnd = 5.5
        // Sidewalk: 4 blocks -> halfWidth = 5.5 + 4 = 9.5
        double lineHalf = 0.5;
        double laneEnd = lineHalf + 4.0;
        double curbWidth = 1.0;
        double curbCenter = laneEnd + curbWidth / 2.0;
        double curbEnd = laneEnd + curbWidth;
        double halfWidth = profile.getWidth() / 2.0;

        // Bounding box
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i <= segs; i++) {
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }
        int r = (int) Math.ceil(halfWidth) + 1;
        int x0 = (int) Math.floor(minX) - r, x1 = (int) Math.ceil(maxX) + r;
        int z0 = (int) Math.floor(minZ) - r, z1 = (int) Math.ceil(maxZ) + r;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // 1. Find closest segment of the polyline
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

                if (best < 0 || bestD2 > halfWidth * halfWidth) continue;

                // 2. Segment direction, signed lateral offset, and longitudinal path
                double vx = px[best + 1] - px[best];
                double vz = pz[best + 1] - pz[best];
                double segLen = Math.hypot(vx, vz);
                if (segLen < 1e-6) continue;
                double ux = vx / segLen, uz = vz / segLen;
                double nx = -uz, nz = ux;

                // Boundary trimming at road ends
                double along = bestRaw * segLen;
                if (best == 0 && along < -0.5) continue;
                if (best == segs - 1 && along > segLen + 0.5) continue;

                double wx = x - px[best], wz = z - pz[best];
                double cross = ux * wz - uz * wx;
                double s = Math.sqrt(bestD2) * (cross >= 0 ? 1 : -1);
                double t = cum[best] + bestU * segLen;
                double as = Math.abs(s);

                int cellY = (int) Math.round(py[best] + bestU * (py[best + 1] - py[best]));

                // 3. TUT normalization factor m = max(|nx|, |nz|)
                // Thin elements (marking, curb) measured along grid axis:
                // on diagonals and curves this gives EXACTLY ONE cell width!
                double m = Math.max(Math.abs(nx), Math.abs(nz));
                double sm = s / m;
                double dc = (as - curbCenter) / m;

                if (sm > -lineHalf + EPS && sm <= lineHalf + EPS) {
                    // Line direction: stairs face perpendicular to road (along normal nx, nz) so the upper vertical flat step forms a thin centered white stripe!
                    line.add(new LineCandidate(x, z, cellY, t, facingFromVector(nx, nz)));
                } else if (dc > -curbWidth / 2.0 + EPS && dc <= curbWidth / 2.0 + EPS) {
                    // Curb stairs facing: exact TUT formula facing(nx * sign, nz * sign) where sign = s >= 0 ? 1 : -1
                    double sign = s >= 0 ? 1 : -1;
                    out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx * sign, nz * sign)));
                } else if (as < curbCenter) {
                    out.add(new Cell(x, z, cellY, Kind.ASPHALT, Direction.NORTH));
                } else {
                    out.add(new Cell(x, z, cellY, Kind.SIDEWALK, Direction.NORTH));
                }
            }
        }

        // 4. Center dashes counted in discrete BLOCKS along cell chain (prevents bunching on diagonals)
        line.sort(Comparator.comparingDouble(LineCandidate::t));
        int dashLength = profile.getDashLength();
        int gapLength = profile.getGapLength();
        int period = dashLength + gapLength;

        for (int i = 0; i < line.size(); i++) {
            LineCandidate c = line.get(i);
            int phase = Math.floorMod(i, period);
            boolean isDash = phase < dashLength;
            out.add(new Cell(c.x(), c.z(), c.y(), isDash ? Kind.LINE : Kind.ASPHALT, c.facing()));
        }

        return out;
    }

    private static long coordKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int rank(Kind k) {
        return switch (k) {
            case SIDEWALK -> 1;
            case CURB -> 2;
            case LINE -> 3;
            case ASPHALT -> 4;
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

    private static BlockState resolveBlockState(String blockId, BlockState fallback) {
        ResourceLocation resLoc = ResourceLocation.tryParse(blockId);
        if (resLoc == null) {
            return fallback;
        }
        return BuiltInRegistries.BLOCK.getOptional(resLoc)
                .map(Block::defaultBlockState)
                .orElse(fallback);
    }
}
