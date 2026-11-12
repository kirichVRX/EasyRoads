package com.roadbuilder.mod.domain.pack;

import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import com.roadbuilder.mod.domain.profile.TransitionProfile;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Loads tree and bush .litematic schematics, resolves 2nd-layer greenery placement slots
 * on compatible stack elements (LAWN, DIVIDER_GRASS, SIDEWALK, and SHOULDER for bushes),
 * and handles trunk-centered tree rotation & grass substrate omission.
 */
public final class RoadGreeneryTemplate {

    public record GreeneryVoxel(int dx, int dy, int dz, BlockState state, boolean substratePad) {}

    public static final class GreeneryPattern {
        private final boolean tree;
        private final int trunkX;
        private final int trunkZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final List<GreeneryVoxel> voxels;

        public GreeneryPattern(
                boolean tree,
                int trunkX,
                int trunkZ,
                int sizeX,
                int sizeY,
                int sizeZ,
                List<GreeneryVoxel> voxels
        ) {
            this.tree = tree;
            this.trunkX = trunkX;
            this.trunkZ = trunkZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.voxels = voxels;
        }

        public boolean isTree() { return tree; }
        public int getTrunkX() { return trunkX; }
        public int getTrunkZ() { return trunkZ; }
        public int getSizeX() { return sizeX; }
        public int getSizeY() { return sizeY; }
        public int getSizeZ() { return sizeZ; }
        public List<GreeneryVoxel> getVoxels() { return voxels; }
    }

    public record GreenerySlot(
            double anchorOffset,
            double inwardSign,
            boolean isGrassElement,
            boolean isDivider,
            RoadElementType sourceType,
            double elemWidth,
            ModularRoadPreset.GreeneryStyle style
    ) {}

    private static final ModularRoadPreset.GreeneryStyle[] TREE_MIX_POOL = {
            ModularRoadPreset.GreeneryStyle.OAK,
            ModularRoadPreset.GreeneryStyle.PALM,
            ModularRoadPreset.GreeneryStyle.SMALL,
            ModularRoadPreset.GreeneryStyle.POPLAR
    };

    private static final Map<String, GreeneryPattern> CACHE = new HashMap<>();
    private static RoadPackRegistry.RoadPack cachedPack = null;

    private RoadGreeneryTemplate() {}

    public static GreeneryPattern getForStyle(ModularRoadPreset.GreeneryStyle style, RoadPackRegistry.RoadPack pack) {
        if (style == null || !style.isActive() || style.getSchematicKey() == null) return null;
        return getByKey(style.getSchematicKey(), style.isTree(), pack);
    }

    public static ModularRoadPreset.GreeneryStyle resolveConcreteStyle(
            ModularRoadPreset.GreeneryStyle style,
            long seed
    ) {
        if (style == null || !style.isActive()) return ModularRoadPreset.GreeneryStyle.NONE;
        if (style == ModularRoadPreset.GreeneryStyle.TREE_MIX) {
            int idx = Math.floorMod((int) (seed ^ (seed >>> 16)), TREE_MIX_POOL.length);
            return TREE_MIX_POOL[idx];
        }
        return style;
    }

    public static Rotation deterministicTreeRotation(long seed) {
        Rotation[] rots = Rotation.values();
        long h = mix64(seed);
        return rots[Math.floorMod((int) h, rots.length)];
    }

    public static Rotation rotationForAlong(Direction alongDir) {
        return switch (alongDir) {
            case SOUTH -> Rotation.CLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_180;
            case NORTH -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE; // +X axis
        };
    }

    public static int rotateDx(int dx, int dz, Rotation rot) {
        return switch (rot) {
            case CLOCKWISE_90 -> -dz;
            case CLOCKWISE_180 -> -dx;
            case COUNTERCLOCKWISE_90 -> dz;
            default -> dx;
        };
    }

    public static int rotateDz(int dx, int dz, Rotation rot) {
        return switch (rot) {
            case CLOCKWISE_90 -> dx;
            case CLOCKWISE_180 -> -dz;
            case COUNTERCLOCKWISE_90 -> -dx;
            default -> dz;
        };
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static GreeneryPattern getByKey(String key, boolean isTree, RoadPackRegistry.RoadPack pack) {
        if (key == null || key.isEmpty() || pack == null) return null;
        if (cachedPack != pack) {
            CACHE.clear();
            cachedPack = pack;
        }
        String cacheKey = (isTree ? "T:" : "B:") + key;
        return CACHE.computeIfAbsent(cacheKey, k -> loadPattern(key, isTree, pack));
    }

    private static GreeneryPattern loadPattern(String key, boolean isTree, RoadPackRegistry.RoadPack pack) {
        LitematicParser.Schematic schem = pack.getSchematic(key);
        if (schem == null) {
            schem = pack.getSchematic(key.toLowerCase(Locale.ROOT));
        }
        if (schem == null) return null;
        return parsePattern(schem, isTree);
    }

    public static GreeneryPattern parsePattern(LitematicParser.Schematic schem, boolean isTree) {
        int sx = schem.getSizeX();
        int sy = schem.getSizeY();
        int sz = schem.getSizeZ();

        int centerX;
        int centerZ;
        if (isTree && sy > 1) {
            // Every tree schematic has its single trunk column cleanly exposed at Y=1
            int sumX = 0;
            int sumZ = 0;
            int count = 0;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (!schem.getBlock(x, 1, z).isAir()) {
                        sumX += x;
                        sumZ += z;
                        count++;
                    }
                }
            }
            if (count > 0) {
                centerX = Math.round((float) sumX / count);
                centerZ = Math.round((float) sumZ / count);
            } else {
                centerX = (sx - 1) / 2;
                centerZ = (sz - 1) / 2;
            }
        } else {
            // For bushes, center using the Y=0 base footprint (3x3 -> (1,1), 6x3 -> (2,1))
            int minX = sx, maxX = -1, minZ = sz, maxZ = -1;
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (!schem.getBlock(x, 0, z).isAir()) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
            if (maxX >= minX) {
                centerX = (minX + maxX) / 2;
                centerZ = (minZ + maxZ) / 2;
            } else {
                centerX = (sx - 1) / 2;
                centerZ = (sz - 1) / 2;
            }
        }

        List<GreeneryVoxel> voxels = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState state = schem.getBlock(x, y, z);
                    if (state.isAir()) continue;
                    int dx = x - centerX;
                    int dz = z - centerZ;
                    if (isTree && y == 0) {
                        // Strip stray edge/corner marker blocks outside the 3x3 planter pad around the trunk
                        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) {
                            continue;
                        }
                    }
                    boolean substratePad = isTree && y == 0 && (dx != 0 || dz != 0);
                    voxels.add(new GreeneryVoxel(dx, y, dz, state, substratePad));
                }
            }
        }
        return new GreeneryPattern(isTree, centerX, centerZ, sx, sy, sz, voxels);
    }

    public static List<GreenerySlot> computeGreenerySlots(ModularRoadPreset preset) {
        if (preset == null || preset.getStack() == null || preset.getStack().isEmpty()) {
            return List.of();
        }
        List<RoadStackElement> stack = preset.getStack();
        int n = stack.size();
        RoadElementType[] types = new RoadElementType[n];
        double[] starts = new double[n];
        double[] ends = new double[n];
        ModularRoadPreset.GuardrailPlacement[] placements = new ModularRoadPreset.GuardrailPlacement[n];
        ModularRoadPreset.GreeneryStyle[] styles = new ModularRoadPreset.GreeneryStyle[n];

        double cur = -preset.getTotalWidth() * 0.5;
        for (int i = 0; i < n; i++) {
            RoadStackElement el = stack.get(i);
            types[i] = el.getType();
            starts[i] = cur;
            cur += el.getWidth();
            ends[i] = cur;
            placements[i] = el.getGreenery();
            styles[i] = el.getGreeneryStyle();
        }
        return buildSlots(types, starts, ends, placements, styles, n);
    }

    public static List<GreenerySlot> computeInterpolatedGreenerySlots(
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
        ModularRoadPreset.GreeneryStyle[] styles = new ModularRoadPreset.GreeneryStyle[n];

        List<RoadStackElement> orig = preset.getStack();
        for (int i = 0; i < n; i++) {
            TransitionProfile.InterpolatedElement el = elems.get(i);
            types[i] = el.type();
            starts[i] = el.startOffset();
            ends[i] = el.endOffset();
            placements[i] = ModularRoadPreset.GuardrailPlacement.NONE;
            styles[i] = ModularRoadPreset.GreeneryStyle.NONE;
        }

        // Map corresponding element placements from the preset onto the interpolated stack
        RoadStackElement leftOuterSw = findOuterCompatible(orig, true);
        RoadStackElement rightOuterSw = findOuterCompatible(orig, false);
        RoadStackElement dividerEl = findTypeGreenery(orig, RoadElementType.DIVIDER_GRASS);
        RoadStackElement lawnEl = findTypeGreenery(orig, RoadElementType.LAWN);

        int firstOuter = -1;
        int lastOuter = -1;
        for (int i = 0; i < n; i++) {
            if (types[i] == RoadElementType.SIDEWALK || types[i] == RoadElementType.SHOULDER) {
                if (firstOuter < 0) firstOuter = i;
                lastOuter = i;
            } else if (types[i] == RoadElementType.DIVIDER_GRASS && dividerEl != null) {
                placements[i] = dividerEl.getGreenery();
                styles[i] = dividerEl.getGreeneryStyle();
            } else if (types[i] == RoadElementType.LAWN && lawnEl != null) {
                placements[i] = lawnEl.getGreenery();
                styles[i] = lawnEl.getGreeneryStyle();
            }
        }
        if (firstOuter >= 0 && leftOuterSw != null && leftOuterSw.getGreeneryStyle().isAllowedFor(types[firstOuter])) {
            placements[firstOuter] = leftOuterSw.getGreenery();
            styles[firstOuter] = leftOuterSw.getGreeneryStyle();
        }
        if (lastOuter >= 0 && lastOuter != firstOuter && rightOuterSw != null
                && rightOuterSw.getGreeneryStyle().isAllowedFor(types[lastOuter])) {
            placements[lastOuter] = rightOuterSw.getGreenery();
            styles[lastOuter] = rightOuterSw.getGreeneryStyle();
        }

        return buildSlots(types, starts, ends, placements, styles, n);
    }

    private static RoadStackElement findOuterCompatible(List<RoadStackElement> orig, boolean leftSide) {
        if (orig == null || orig.isEmpty()) return null;
        int idx = leftSide ? 0 : orig.size() - 1;
        RoadStackElement el = orig.get(idx);
        if ((el.getType() == RoadElementType.SIDEWALK || el.getType() == RoadElementType.SHOULDER)
                && el.getGreenery() != ModularRoadPreset.GuardrailPlacement.NONE
                && el.getGreeneryStyle() != ModularRoadPreset.GreeneryStyle.NONE) {
            return el;
        }
        return null;
    }

    private static RoadStackElement findTypeGreenery(List<RoadStackElement> orig, RoadElementType type) {
        if (orig == null) return null;
        for (RoadStackElement el : orig) {
            if (el.getType() == type
                    && el.getGreenery() != ModularRoadPreset.GuardrailPlacement.NONE
                    && el.getGreeneryStyle() != ModularRoadPreset.GreeneryStyle.NONE) {
                return el;
            }
        }
        return null;
    }

    private static List<GreenerySlot> buildSlots(
            RoadElementType[] types,
            double[] starts,
            double[] ends,
            ModularRoadPreset.GuardrailPlacement[] placements,
            ModularRoadPreset.GreeneryStyle[] styles,
            int n
    ) {
        List<GreenerySlot> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ModularRoadPreset.GuardrailPlacement gp = placements[i];
            ModularRoadPreset.GreeneryStyle gs = styles[i];
            RoadElementType type = types[i];
            if (gp == null || gp == ModularRoadPreset.GuardrailPlacement.NONE) continue;
            if (gs == null || !gs.isActive() || !gs.isAllowedFor(type)) continue;

            double w = ends[i] - starts[i];
            if (w < 0.8) continue;

            boolean isDivider = (type == RoadElementType.DIVIDER_GRASS);
            boolean isGrass = (type == RoadElementType.LAWN || type == RoadElementType.DIVIDER_GRASS);

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
                inOff = (inward > 0) ? (ends[i] - 1.5) : (starts[i] + 1.5);
                outOff = (inward > 0) ? (starts[i] + 1.5) : (ends[i] - 1.5);
            } else if (w >= 3.0) {
                // Keep greenery at least 0.5..1.0 blocks inside wide strips so trunk/bush beds stay on the surface
                inOff = (inward > 0) ? (ends[i] - 1.0) : (starts[i] + 1.0);
                outOff = (inward > 0) ? (starts[i] + 1.0) : (ends[i] - 1.0);
            } else {
                inOff = (inward > 0) ? (ends[i] - 0.5) : (starts[i] + 0.5);
                outOff = (inward > 0) ? (starts[i] + 0.5) : (ends[i] - 0.5);
            }
            double centerOff = (starts[i] + ends[i]) * 0.5;

            switch (gp) {
                case CENTER -> slots.add(new GreenerySlot(centerOff, inward, isGrass, isDivider, type, w, gs));
                case INNER -> slots.add(new GreenerySlot(inOff, inward, isGrass, isDivider, type, w, gs));
                case OUTER -> slots.add(new GreenerySlot(outOff, inward, isGrass, isDivider, type, w, gs));
                case BOTH -> {
                    if (w < 3.0) {
                        slots.add(new GreenerySlot(centerOff, inward, isGrass, isDivider, type, w, gs));
                    } else {
                        slots.add(new GreenerySlot(inOff, inward, isGrass, isDivider, type, w, gs));
                        slots.add(new GreenerySlot(outOff, -inward, isGrass, isDivider, type, w, gs));
                    }
                }
                case NONE -> {}
            }
        }
        return slots;
    }
}
