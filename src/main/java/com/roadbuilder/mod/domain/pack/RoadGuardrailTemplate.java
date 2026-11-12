package com.roadbuilder.mod.domain.pack;

import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import com.roadbuilder.mod.domain.profile.TransitionProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.WallSide;

import java.util.*;

/**
 * Loads guardrail and noise-wall .litematic schematics, resolves 2nd-layer placement slots
 * on stack elements (including width transitions), and helps tile/orient sections continuously.
 */
public final class RoadGuardrailTemplate {

    public record GuardrailVoxel(int relY, int relZ, BlockState state) {}

    public static final class GuardrailPattern {
        private final int lengthX;
        private final int height;
        private final int baseZ;
        private final List<List<GuardrailVoxel>> slices; // indexed by x in [0, lengthX)

        public GuardrailPattern(int lengthX, int height, int baseZ, List<List<GuardrailVoxel>> slices) {
            this.lengthX = Math.max(1, lengthX);
            this.height = Math.max(1, height);
            this.baseZ = baseZ;
            this.slices = slices;
        }

        public int getLengthX() { return lengthX; }
        public int getHeight() { return height; }
        public int getBaseZ() { return baseZ; }

        public List<GuardrailVoxel> getSlice(int stepIndex) {
            return slices.get(Math.floorMod(stepIndex, lengthX));
        }

        public Rotation rotationForAlong(Direction alongDir) {
            return switch (alongDir) {
                case SOUTH -> Rotation.CLOCKWISE_90;
                case WEST -> Rotation.CLOCKWISE_180;
                case NORTH -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE; // EAST matches +X schematic axis
            };
        }
    }

    public record GuardrailSlot(
            double anchorOffset,
            double inwardSign,
            boolean isNoiseWall,
            boolean isDivider,
            boolean outerBoundary,
            RoadElementType sourceType,
            double elemWidth,
            ModularRoadPreset.GuardrailStyle style
    ) {}

    private static final Map<String, GuardrailPattern> CACHE = new HashMap<>();
    private static RoadPackRegistry.RoadPack cachedPack = null;

    private RoadGuardrailTemplate() {}

    public static GuardrailPattern getForStyle(ModularRoadPreset.GuardrailStyle style, RoadPackRegistry.RoadPack pack) {
        if (style == null || style == ModularRoadPreset.GuardrailStyle.NONE) return null;
        return getByKey(style.getSchematicKey(), pack);
    }

    public static GuardrailPattern getNoiseWall(RoadPackRegistry.RoadPack pack) {
        return getByKey("Road_noizewall", pack);
    }

    private static GuardrailPattern getByKey(String key, RoadPackRegistry.RoadPack pack) {
        if (key == null || key.isEmpty() || pack == null) return null;
        if (cachedPack != pack) {
            CACHE.clear();
            cachedPack = pack;
        }
        return CACHE.computeIfAbsent(key, k -> loadPattern(k, pack));
    }

    private static GuardrailPattern loadPattern(String key, RoadPackRegistry.RoadPack pack) {
        LitematicParser.Schematic schem = pack.getSchematic(key);
        if (schem == null) {
            schem = pack.getSchematic(key.toLowerCase(Locale.ROOT));
        }
        if (schem == null) return null;
        return parsePattern(schem);
    }

    public static GuardrailPattern parsePattern(LitematicParser.Schematic schem) {
        int sx = schem.getSizeX();
        int sy = schem.getSizeY();
        int sz = schem.getSizeZ();

        // Find pedestal Z (where y=0 blocks sit) so upper overhang layers (z != baseZ) step relative to it
        int minBaseZ = Integer.MAX_VALUE;
        int maxBaseZ = Integer.MIN_VALUE;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                if (!schem.getBlock(x, 0, z).isAir()) {
                    minBaseZ = Math.min(minBaseZ, z);
                    maxBaseZ = Math.max(maxBaseZ, z);
                }
            }
        }
        int baseZ = (minBaseZ != Integer.MAX_VALUE) ? (minBaseZ + maxBaseZ) / 2 : (sz - 1);

        List<List<GuardrailVoxel>> slices = new ArrayList<>(sx);
        for (int x = 0; x < sx; x++) {
            List<GuardrailVoxel> slice = new ArrayList<>();
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    BlockState state = schem.getBlock(x, y, z);
                    if (!state.isAir()) {
                        // In schematics (like Road_noizewall), overhang toward roadway is in -Z (relInward = baseZ - z)
                        slice.add(new GuardrailVoxel(y, baseZ - z, state));
                    }
                }
            }
            slices.add(slice);
        }
        return new GuardrailPattern(sx, sy, baseZ, slices);
    }

    public static List<GuardrailSlot> computeGuardrailSlots(ModularRoadPreset preset) {
        if (preset == null || preset.getStack() == null || preset.getStack().isEmpty()) {
            return List.of();
        }
        List<RoadStackElement> stack = preset.getStack();
        int n = stack.size();
        RoadElementType[] types = new RoadElementType[n];
        double[] starts = new double[n];
        double[] ends = new double[n];
        ModularRoadPreset.GuardrailPlacement[] placements = new ModularRoadPreset.GuardrailPlacement[n];
        ModularRoadPreset.GuardrailStyle[] styles = new ModularRoadPreset.GuardrailStyle[n];

        double cur = -preset.getTotalWidth() * 0.5;
        for (int i = 0; i < n; i++) {
            RoadStackElement el = stack.get(i);
            types[i] = el.getType();
            starts[i] = cur;
            cur += el.getWidth();
            ends[i] = cur;
            placements[i] = el.getGuardrail();
            styles[i] = el.resolveGuardrailStyle(preset);
        }
        return buildSlots(preset, types, starts, ends, placements, styles, n);
    }

    public static List<GuardrailSlot> computeInterpolatedGuardrailSlots(
            ModularRoadPreset preset,
            TransitionProfile.InterpolatedStack interp
    ) {
        if (preset == null || interp == null || interp.elements().isEmpty()) {
            return List.of();
        }
        List<TransitionProfile.InterpolatedElement> elems = interp.elements();
        int n = elems.size();
        RoadElementType[] types = new RoadElementType[n];
        double[] starts = new double[n];
        double[] ends = new double[n];
        ModularRoadPreset.GuardrailPlacement[] placements = new ModularRoadPreset.GuardrailPlacement[n];
        ModularRoadPreset.GuardrailStyle[] styles = new ModularRoadPreset.GuardrailStyle[n];

        for (int i = 0; i < n; i++) {
            TransitionProfile.InterpolatedElement el = elems.get(i);
            types[i] = el.type();
            starts[i] = el.startOffset();
            ends[i] = el.endOffset();
            placements[i] = ModularRoadPreset.GuardrailPlacement.NONE;
            styles[i] = ModularRoadPreset.GuardrailStyle.NONE;
        }

        // Map outer sidewalks/shoulders and divider grass placement + style onto the interpolated stack
        List<RoadStackElement> orig = preset.getStack();
        RoadStackElement leftOuterEl = findOuterElement(orig, true);
        RoadStackElement rightOuterEl = findOuterElement(orig, false);
        RoadStackElement dividerEl = findTypeElement(orig, RoadElementType.DIVIDER_GRASS);

        int firstOuter = -1;
        int lastOuter = -1;
        for (int i = 0; i < n; i++) {
            if (types[i] == RoadElementType.SIDEWALK || types[i] == RoadElementType.SHOULDER) {
                if (firstOuter < 0) firstOuter = i;
                lastOuter = i;
            } else if (types[i] == RoadElementType.DIVIDER_GRASS && dividerEl != null && dividerEl.getGuardrail() != ModularRoadPreset.GuardrailPlacement.NONE) {
                placements[i] = dividerEl.getGuardrail();
                styles[i] = dividerEl.resolveGuardrailStyle(preset);
            }
        }
        if (firstOuter >= 0 && leftOuterEl != null && leftOuterEl.getGuardrail() != ModularRoadPreset.GuardrailPlacement.NONE) {
            placements[firstOuter] = leftOuterEl.getGuardrail();
            styles[firstOuter] = leftOuterEl.resolveGuardrailStyle(preset);
        }
        if (lastOuter >= 0 && lastOuter != firstOuter && rightOuterEl != null && rightOuterEl.getGuardrail() != ModularRoadPreset.GuardrailPlacement.NONE) {
            placements[lastOuter] = rightOuterEl.getGuardrail();
            styles[lastOuter] = rightOuterEl.resolveGuardrailStyle(preset);
        }

        return buildSlots(preset, types, starts, ends, placements, styles, n);
    }

    private static RoadStackElement findOuterElement(List<RoadStackElement> orig, boolean leftSide) {
        if (orig == null || orig.isEmpty()) return null;
        int idx = leftSide ? 0 : orig.size() - 1;
        RoadStackElement el = orig.get(idx);
        if (el.getType() == RoadElementType.SIDEWALK || el.getType() == RoadElementType.SHOULDER) {
            return el;
        }
        return null;
    }

    private static RoadStackElement findTypeElement(List<RoadStackElement> orig, RoadElementType type) {
        if (orig == null) return null;
        for (RoadStackElement el : orig) {
            if (el.getType() == type && el.getGuardrail() != ModularRoadPreset.GuardrailPlacement.NONE) {
                return el;
            }
        }
        return null;
    }

    private static List<GuardrailSlot> buildSlots(
            ModularRoadPreset preset,
            RoadElementType[] types,
            double[] starts,
            double[] ends,
            ModularRoadPreset.GuardrailPlacement[] placements,
            ModularRoadPreset.GuardrailStyle[] styles,
            int n
    ) {
        List<GuardrailSlot> slots = new ArrayList<>();
        boolean[] hasLaneLeft = new boolean[n];
        boolean[] hasLaneRight = new boolean[n];
        boolean seen = false;
        for (int i = 0; i < n; i++) {
            hasLaneLeft[i] = seen;
            if (types[i] == RoadElementType.LANE) seen = true;
        }
        seen = false;
        for (int i = n - 1; i >= 0; i--) {
            hasLaneRight[i] = seen;
            if (types[i] == RoadElementType.LANE) seen = true;
        }

        // Identify outermost SIDEWALK indices (ignore any sidewalks between roadway lanes)
        int leftOuterSwIdx = -1;
        int rightOuterSwIdx = -1;
        for (int i = 0; i < n; i++) {
            if (types[i] == RoadElementType.SIDEWALK && !hasLaneLeft[i]) {
                leftOuterSwIdx = i;
                break;
            }
        }
        for (int i = n - 1; i >= 0; i--) {
            if (types[i] == RoadElementType.SIDEWALK && !hasLaneRight[i]) {
                rightOuterSwIdx = i;
                break;
            }
        }

        double leftNoiseOff = Double.NaN;
        double rightNoiseOff = Double.NaN;
        if (preset.isNoiseWallEnabled()) {
            if (leftOuterSwIdx >= 0 && (ends[leftOuterSwIdx] - starts[leftOuterSwIdx]) >= 0.8) {
                leftNoiseOff = starts[leftOuterSwIdx] + 0.5;
                slots.add(new GuardrailSlot(leftNoiseOff, 1.0, true, false,
                        leftOuterSwIdx == 0, RoadElementType.SIDEWALK, ends[leftOuterSwIdx] - starts[leftOuterSwIdx],
                        ModularRoadPreset.GuardrailStyle.NONE));
            }
            if (rightOuterSwIdx >= 0 && rightOuterSwIdx != leftOuterSwIdx && (ends[rightOuterSwIdx] - starts[rightOuterSwIdx]) >= 0.8) {
                rightNoiseOff = ends[rightOuterSwIdx] - 0.5;
                slots.add(new GuardrailSlot(rightNoiseOff, -1.0, true, false,
                        rightOuterSwIdx == n - 1, RoadElementType.SIDEWALK, ends[rightOuterSwIdx] - starts[rightOuterSwIdx],
                        ModularRoadPreset.GuardrailStyle.NONE));
            }
        }

        for (int i = 0; i < n; i++) {
            ModularRoadPreset.GuardrailPlacement gp = placements[i];
            ModularRoadPreset.GuardrailStyle gs = styles[i];
            if (gp == null || gp == ModularRoadPreset.GuardrailPlacement.NONE) continue;
            if (gs == null || gs == ModularRoadPreset.GuardrailStyle.NONE) continue;
            double w = ends[i] - starts[i];
            if (w < 0.8) continue;

            RoadElementType type = types[i];
            boolean isDivider = (type == RoadElementType.DIVIDER_GRASS);

            // Direction toward closest LANE (falling back to centerline side)
            int bestDist = Integer.MAX_VALUE;
            double inward = ((starts[i] + ends[i]) * 0.5 <= 0.0) ? 1.0 : -1.0;
            for (int j = 0; j < n; j++) {
                if (types[j] == RoadElementType.LANE && j != i) {
                    int d = Math.abs(j - i);
                    if (d < bestDist) {
                        bestDist = d;
                        inward = (j > i) ? 1.0 : -1.0;
                    }
                }
            }

            double inOff;
            double outOff;
            if (isDivider && w >= 3.0) {
                // Step 1 block inward onto flat grass inside divider curbs (localX=0 and w-1 are stairs)
                inOff = (inward > 0) ? (ends[i] - 1.5) : (starts[i] + 1.5);
                outOff = (inward > 0) ? (starts[i] + 1.5) : (ends[i] - 1.5);
            } else {
                inOff = (inward > 0) ? (ends[i] - 0.5) : (starts[i] + 0.5);
                outOff = (inward > 0) ? (starts[i] + 0.5) : (ends[i] - 0.5);
            }
            double centerOff = (starts[i] + ends[i]) * 0.5;

            boolean leftBlockedByNoise = (!Double.isNaN(leftNoiseOff) && Math.abs(outOff - leftNoiseOff) < 0.6)
                    || (!Double.isNaN(leftNoiseOff) && w < 1.5 && Math.abs(centerOff - leftNoiseOff) < 0.6);
            boolean rightBlockedByNoise = (!Double.isNaN(rightNoiseOff) && Math.abs(outOff - rightNoiseOff) < 0.6)
                    || (!Double.isNaN(rightNoiseOff) && w < 1.5 && Math.abs(centerOff - rightNoiseOff) < 0.6);
            boolean outBlockedByNoise = (leftOuterSwIdx == i && leftBlockedByNoise) || (rightOuterSwIdx == i && rightBlockedByNoise);

            switch (gp) {
                case CENTER -> {
                    if (!(w < 1.5 && outBlockedByNoise)) {
                        slots.add(new GuardrailSlot(centerOff, inward, false, isDivider,
                                false, type, w, gs));
                    }
                }
                case INNER -> {
                    if (!(w < 1.5 && outBlockedByNoise)) {
                        slots.add(new GuardrailSlot(inOff, inward, false, isDivider,
                                false, type, w, gs));
                    }
                }
                case OUTER -> {
                    if (!outBlockedByNoise) {
                        slots.add(new GuardrailSlot(outOff, inward, false, isDivider,
                                i == 0 || i == n - 1, type, w, gs));
                    }
                }
                case BOTH -> {
                    if (w < 1.8) {
                        if (!outBlockedByNoise) {
                            slots.add(new GuardrailSlot(centerOff, inward, false, isDivider,
                                    i == 0 || i == n - 1, type, w, gs));
                        }
                    } else {
                        slots.add(new GuardrailSlot(inOff, inward, false, isDivider,
                                false, type, w, gs));
                        if (!outBlockedByNoise) {
                            slots.add(new GuardrailSlot(outOff, -inward, false, isDivider,
                                    i == 0 || i == n - 1, type, w, gs));
                        }
                    }
                }
                case NONE -> {}
            }
        }
        return slots;
    }

    public static Direction directionFromHorizontal(double x, double z) {
        if (Math.abs(x) >= Math.abs(z)) {
            return x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Populates directional fence / iron bar / glass pane / wall connection properties inside a preview map.
     */
    public static void connectThinBlocksInPreview(Map<BlockPos, BlockState> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        Map<BlockPos, BlockState> updated = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState st = entry.getValue();
            Block b = st.getBlock();
            if (b instanceof WallBlock) {
                boolean n = isConnectableWall(blocks.get(pos.north()));
                boolean s = isConnectableWall(blocks.get(pos.south()));
                boolean e = isConnectableWall(blocks.get(pos.east()));
                boolean w = isConnectableWall(blocks.get(pos.west()));
                boolean straightNS = n && s && !e && !w;
                boolean straightEW = e && w && !n && !s;
                BlockState above = blocks.get(pos.above());
                boolean hasAbove = above != null && !above.isAir();
                boolean up = hasAbove || (!straightNS && !straightEW);
                BlockState next = st
                        .setValue(BlockStateProperties.NORTH_WALL, n ? WallSide.LOW : WallSide.NONE)
                        .setValue(BlockStateProperties.SOUTH_WALL, s ? WallSide.LOW : WallSide.NONE)
                        .setValue(BlockStateProperties.EAST_WALL, e ? WallSide.LOW : WallSide.NONE)
                        .setValue(BlockStateProperties.WEST_WALL, w ? WallSide.LOW : WallSide.NONE)
                        .setValue(BlockStateProperties.UP, up);
                updated.put(pos, next);
            } else if (b instanceof IronBarsBlock || b instanceof FenceBlock) {
                boolean n = isConnectableThin(blocks.get(pos.north()));
                boolean s = isConnectableThin(blocks.get(pos.south()));
                boolean e = isConnectableThin(blocks.get(pos.east()));
                boolean w = isConnectableThin(blocks.get(pos.west()));
                BlockState next = st
                        .setValue(BlockStateProperties.NORTH, n)
                        .setValue(BlockStateProperties.SOUTH, s)
                        .setValue(BlockStateProperties.EAST, e)
                        .setValue(BlockStateProperties.WEST, w);
                updated.put(pos, next);
            }
        }
        blocks.putAll(updated);
    }

    private static boolean isConnectableWall(BlockState st) {
        if (st == null || st.isAir()) return false;
        Block b = st.getBlock();
        return b instanceof WallBlock || b instanceof IronBarsBlock || b instanceof FenceBlock;
    }

    private static boolean isConnectableThin(BlockState st) {
        if (st == null || st.isAir()) return false;
        Block b = st.getBlock();
        return b instanceof WallBlock || b instanceof IronBarsBlock || b instanceof FenceBlock;
    }
}
