package com.roadbuilder.mod.domain.pack;

import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import com.roadbuilder.mod.domain.profile.TransitionProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts single-sided and double-sided street lamp models from bundled .litematic schematics
 * and maps their voxels onto arbitrary road orientations without leaving gaps on diagonals.
 */
public final class StreetLampTemplate {

    public record LampVoxel(int relY, int forwardDist, BlockState state) {}

    public static final class LampModel {
        private final List<LampVoxel> voxels;
        private final int height;
        private final int maxForward;
        private final int maxBackward;

        public LampModel(List<LampVoxel> voxels, int height, int maxForward, int maxBackward) {
            this.voxels = voxels;
            this.height = height;
            this.maxForward = maxForward;
            this.maxBackward = maxBackward;
        }

        public List<LampVoxel> getVoxels() { return voxels; }
        public int getHeight() { return height; }
        public int getMaxForward() { return maxForward; }
        public int getMaxBackward() { return maxBackward; }

        /**
         * Places all voxels anchored at surface point (anchorX, surfaceY, anchorZ),
         * where 'anchor' lies on the sidewalk/divider surface block (y = surfaceY)
         * and the lamp base sits on (surfaceY + 1).
         */
        public Map<BlockPos, BlockState> sampleAt(double anchorX, int surfaceY, double anchorZ, double inX, double inZ) {
            double len = Math.hypot(inX, inZ);
            double ux = (len > 1e-6) ? inX / len : 0.0;
            double uz = (len > 1e-6) ? inZ / len : -1.0;

            double chebyshev = Math.max(Math.abs(ux), Math.abs(uz));
            double stepX = (chebyshev > 1e-6) ? ux / chebyshev : 0.0;
            double stepZ = (chebyshev > 1e-6) ? uz / chebyshev : -1.0;

            Direction targetDir = directionFromHorizontal(ux, uz);
            Rotation rotation = switch (targetDir) {
                case EAST -> Rotation.CLOCKWISE_90;
                case SOUTH -> Rotation.CLOCKWISE_180;
                case WEST -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };

            Map<BlockPos, BlockState> out = new HashMap<>(voxels.size());
            for (LampVoxel v : voxels) {
                int bx = (int) Math.floor(anchorX + v.forwardDist() * stepX);
                int by = surfaceY + 1 + v.relY();
                int bz = (int) Math.floor(anchorZ + v.forwardDist() * stepZ);
                BlockPos pos = new BlockPos(bx, by, bz);
                BlockState rotated = v.state().rotate(rotation);
                out.put(pos, rotated);
            }
            return out;
        }
    }

    public record LampPair(LampModel singleSided, LampModel doubleSided) {
        public LampModel forSidewalk(boolean doubleSidedOnSidewalk) {
            return doubleSidedOnSidewalk ? doubleSided : singleSided;
        }
    }

    public record StackLampSlot(double anchorOffset, double inwardSign, boolean divider, boolean rightSide, double elemWidth) {}

    public static List<StackLampSlot> computeStackSlots(List<RoadStackElement> stack, double totalWidth) {
        List<StackLampSlot> slots = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return slots;
        double cur = -totalWidth * 0.5;
        int n = stack.size();
        RoadElementType[] types = new RoadElementType[n];
        double[] starts = new double[n];
        double[] ends = new double[n];
        for (int i = 0; i < n; i++) {
            RoadStackElement el = stack.get(i);
            types[i] = el.getType();
            starts[i] = cur;
            cur += el.getWidth();
            ends[i] = cur;
        }
        appendSlotsFromArrays(types, starts, ends, n, slots);
        return slots;
    }

    public static List<StackLampSlot> computeInterpolatedStackSlots(TransitionProfile.InterpolatedStack stack) {
        List<StackLampSlot> slots = new ArrayList<>();
        if (stack == null || stack.elements().isEmpty()) return slots;
        List<TransitionProfile.InterpolatedElement> elems = stack.elements();
        int n = elems.size();
        RoadElementType[] types = new RoadElementType[n];
        double[] starts = new double[n];
        double[] ends = new double[n];
        for (int i = 0; i < n; i++) {
            TransitionProfile.InterpolatedElement el = elems.get(i);
            types[i] = el.type();
            starts[i] = el.startOffset();
            ends[i] = el.endOffset();
        }
        appendSlotsFromArrays(types, starts, ends, n, slots);
        return slots;
    }

    public static List<Double> computeLampStations(double minT, double maxT, double spacing, boolean staggerHalf) {
        double avail = maxT - minT;
        if (avail < 1.0 || spacing <= 0.5) {
            return List.of();
        }
        int count = Math.max(1, (int) Math.floor(avail / spacing) + 1);
        double span = (count - 1) * spacing;
        double first = (minT + maxT - span) * 0.5;

        List<Double> res = new ArrayList<>(count + 1);
        if (!staggerHalf) {
            for (int i = 0; i < count; i++) {
                res.add(first + i * spacing);
            }
        } else {
            double start = first - spacing * 0.5;
            for (int i = 0; i <= count + 1; i++) {
                double t = start + i * spacing;
                if (t >= minT - 1e-4 && t <= maxT + 1e-4) {
                    res.add(t);
                }
            }
        }
        return res;
    }

    private static void appendSlotsFromArrays(RoadElementType[] types, double[] starts, double[] ends, int n, List<StackLampSlot> slots) {
        int swCount = 0;
        for (int i = 0; i < n; i++) {
            RoadElementType t = types[i];
            double w = ends[i] - starts[i];
            if (w < 0.8) continue;

            if (t == RoadElementType.DIVIDER_GRASS) {
                slots.add(new StackLampSlot((starts[i] + ends[i]) * 0.5, 1.0, true, false, w));
            } else if (t == RoadElementType.SIDEWALK) {
                // Determine roadway direction by locating nearest LANE/MARKING element in stack
                int bestLaneDist = Integer.MAX_VALUE;
                double inward = 1.0;
                for (int j = 0; j < n; j++) {
                    if (types[j] == RoadElementType.LANE || types[j] == RoadElementType.CURB) {
                        int d = Math.abs(j - i);
                        if (d < bestLaneDist) {
                            bestLaneDist = d;
                            inward = (j > i) ? 1.0 : -1.0;
                        }
                    }
                }
                double anchor = (inward > 0) ? (ends[i] - 0.5) : (starts[i] + 0.5);
                slots.add(new StackLampSlot(anchor, inward, false, swCount > 0 || inward < 0, w));
                swCount++;
            }
        }
    }

    private static final Map<ModularRoadPreset.LampStyle, LampPair> CACHE = new EnumMap<>(ModularRoadPreset.LampStyle.class);
    private static RoadPackRegistry.RoadPack cachedPack = null;

    private StreetLampTemplate() {}

    public static LampPair getForStyle(ModularRoadPreset.LampStyle style, RoadPackRegistry.RoadPack pack) {
        if (style == null || style == ModularRoadPreset.LampStyle.OFF || pack == null) {
            return null;
        }
        if (cachedPack != pack) {
            CACHE.clear();
            cachedPack = pack;
        }
        return CACHE.computeIfAbsent(style, s -> loadFromPack(s, pack));
    }

    private static LampPair loadFromPack(ModularRoadPreset.LampStyle style, RoadPackRegistry.RoadPack pack) {
        String key = style.getSchematicKey();
        if (key == null || key.isEmpty()) return null;

        LitematicParser.Schematic schem = pack.getSchematic(key);
        if (schem == null) {
            schem = pack.getSchematic(key.toLowerCase());
        }
        if (schem == null) {
            return null;
        }
        return parsePair(schem);
    }

    public static LampPair parsePair(LitematicParser.Schematic schem) {
        int sx = schem.getSizeX();
        int sy = schem.getSizeY();
        int sz = schem.getSizeZ();

        boolean[] zHasBlock = new boolean[sz];
        for (int z = 0; z < sz; z++) {
            outer:
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    if (!schem.getBlock(x, y, z).isAir()) {
                        zHasBlock[z] = true;
                        break outer;
                    }
                }
            }
        }

        List<int[]> islands = new ArrayList<>();
        int start = -1;
        for (int z = 0; z < sz; z++) {
            if (zHasBlock[z] && start < 0) {
                start = z;
            } else if (!zHasBlock[z] && start >= 0) {
                islands.add(new int[]{start, z - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            islands.add(new int[]{start, sz - 1});
        }
        if (islands.isEmpty()) {
            return null;
        }

        LampModel single = null;
        LampModel doubled = null;

        for (int[] range : islands) {
            LampModel model = buildIslandModel(schem, range[0], range[1]);
            if (model == null) continue;
            if (model.getMaxForward() > 0 && model.getMaxBackward() > 0) {
                if (doubled == null) doubled = model;
            } else {
                if (single == null) single = model;
            }
        }

        if (single == null && doubled != null) single = doubled;
        if (doubled == null && single != null) doubled = single;
        return (single != null) ? new LampPair(single, doubled) : null;
    }

    private static LampModel buildIslandModel(LitematicParser.Schematic schem, int zMin, int zMax) {
        int sx = schem.getSizeX();
        int sy = schem.getSizeY();

        // Find base column Z (where y=0 pedestal sits)
        int baseZ = -1;
        int baseX = 0;
        int minBaseZ = Integer.MAX_VALUE;
        int maxBaseZ = Integer.MIN_VALUE;
        for (int z = zMin; z <= zMax; z++) {
            for (int x = 0; x < sx; x++) {
                if (!schem.getBlock(x, 0, z).isAir()) {
                    minBaseZ = Math.min(minBaseZ, z);
                    maxBaseZ = Math.max(maxBaseZ, z);
                    baseX = x;
                }
            }
        }
        if (minBaseZ != Integer.MAX_VALUE) {
            baseZ = (minBaseZ + maxBaseZ) / 2;
        } else {
            baseZ = (zMin + zMax) / 2;
        }

        int fwdSpan = baseZ - zMin;
        int backSpan = zMax - baseZ;
        // If a single-sided fixture was authored facing +Z instead of -Z, flip 180 deg so forward points toward roadway
        boolean flip180 = (fwdSpan == 0 && backSpan > 0);

        List<LampVoxel> voxels = new ArrayList<>();
        int maxFwd = 0;
        int maxBack = 0;
        int maxY = 0;

        for (int y = 0; y < sy; y++) {
            for (int z = zMin; z <= zMax; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState state = schem.getBlock(x, y, z);
                    if (state.isAir()) continue;
                    int fwd = flip180 ? (z - baseZ) : (baseZ - z);
                    if (flip180) {
                        state = state.rotate(Rotation.CLOCKWISE_180);
                    }
                    // Center X around baseX if ever > 1 block wide
                    if (x == baseX || sx == 1) {
                        voxels.add(new LampVoxel(y, fwd, state));
                    }
                    if (fwd > 0) maxFwd = Math.max(maxFwd, fwd);
                    if (fwd < 0) maxBack = Math.max(maxBack, -fwd);
                    maxY = Math.max(maxY, y + 1);
                }
            }
        }
        return voxels.isEmpty() ? null : new LampModel(voxels, maxY, maxFwd, maxBack);
    }

    private static Direction directionFromHorizontal(double x, double z) {
        if (Math.abs(x) >= Math.abs(z)) {
            return x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
