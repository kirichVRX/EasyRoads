package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadGreeneryTemplate;
import com.roadbuilder.mod.domain.pack.RoadGuardrailTemplate;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.pack.StreetLampTemplate;
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
import net.minecraft.world.level.block.AzaleaBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
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

    private record SideCandidate(int bx, int bz, int roadY, double dz, double t, double outX, double outZ, int side, boolean isEndCap) {}
    private record RoadIntersection(Vector3d center, Vector3d t1, Vector3d n1, double hRoadway1, Vector3d t2, Vector3d n2, double hRoadway2) {}

    private static final double EPS = 1e-9;

    public static final BlockState[] SIDEWALK_MIX = new BlockState[]{
            Blocks.POLISHED_ANDESITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.STONE_BRICKS.defaultBlockState()
    };

    private static final BlockState[] SIDEWALK_WARM_BRICK = new BlockState[]{
            Blocks.BRICKS.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.POLISHED_GRANITE.defaultBlockState(),
            Blocks.MUD_BRICKS.defaultBlockState()
    };

    private static final BlockState[] SIDEWALK_DEEPSLATE = new BlockState[]{
            Blocks.DEEPSLATE_BRICKS.defaultBlockState(),
            Blocks.DEEPSLATE_TILES.defaultBlockState(),
            Blocks.POLISHED_DEEPSLATE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState()
    };

    private static final BlockState[] SIDEWALK_STONE = new BlockState[]{
            Blocks.STONE.defaultBlockState()
    };

    private static final BlockState[] SIDEWALK_DIORITE = new BlockState[]{
            Blocks.DIORITE.defaultBlockState(),
            Blocks.POLISHED_DIORITE.defaultBlockState(),
            Blocks.SMOOTH_STONE.defaultBlockState()
    };

    private static final BlockState[] CURB_ANDESITE = new BlockState[]{
            Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState()
    };

    private static final BlockState[] CURB_BRICK_GRANITE = new BlockState[]{
            Blocks.BRICK_STAIRS.defaultBlockState(),
            Blocks.GRANITE_STAIRS.defaultBlockState()
    };

    private static final BlockState[] CURB_MUD_BRICK = new BlockState[]{
            Blocks.MUD_BRICK_STAIRS.defaultBlockState()
    };

    private static final BlockState[] CURB_DIORITE = new BlockState[]{
            Blocks.DIORITE_STAIRS.defaultBlockState(),
            Blocks.POLISHED_DIORITE_STAIRS.defaultBlockState()
    };

    public static BlockState pickPaletteBlock(BlockState[] palette, int bx, int y, int bz) {
        if (palette.length == 1) {
            return palette[0];
        }
        // 64-bit SplitMix64 noise for uniform block mixing without repeating diagonal bands
        long h = ((long) bx * 0x9E3779B97F4A7C15L) ^ ((long) bz * 0xBF58476D1CE4E5B9L) ^ ((long) y * 0x94D049BB133111EBL);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        int idx = (int) Math.floorMod(h, (long) palette.length);
        return palette[idx];
    }

    public static BlockState pickCurbBlock(ModularRoadPreset.CurbPalette palette, int bx, int y, int bz) {
        ModularRoadPreset.CurbPalette p = (palette != null) ? palette : ModularRoadPreset.CurbPalette.ANDESITE;
        BlockState[] pool = switch (p) {
            case ANDESITE -> CURB_ANDESITE;
            case BRICK_GRANITE -> CURB_BRICK_GRANITE;
            case MUD_BRICK -> CURB_MUD_BRICK;
            case DIORITE -> CURB_DIORITE;
        };
        return pickPaletteBlock(pool, bx, y, bz);
    }

    public static BlockState pickSidewalkBlock(ModularRoadPreset.SidewalkPalette palette, int bx, int y, int bz) {
        ModularRoadPreset.SidewalkPalette p = (palette != null) ? palette : ModularRoadPreset.SidewalkPalette.MOSAIC;
        BlockState[] pool = switch (p) {
            case MOSAIC -> SIDEWALK_MIX;
            case WARM_BRICK -> SIDEWALK_WARM_BRICK;
            case DEEPSLATE -> SIDEWALK_DEEPSLATE;
            case STONE -> SIDEWALK_STONE;
            case DIORITE -> SIDEWALK_DIORITE;
        };
        return pickPaletteBlock(pool, bx, y, bz);
    }

    private static final BlockState[] EMBANKMENT_STONE = new BlockState[]{
            Blocks.STONE.defaultBlockState()
    };

    private static final BlockState[] EMBANKMENT_DEEPSLATE = new BlockState[]{
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.COBBLED_DEEPSLATE.defaultBlockState(),
            Blocks.POLISHED_DEEPSLATE.defaultBlockState()
    };

    private static final BlockState[] EMBANKMENT_DIORITE = new BlockState[]{
            Blocks.DIORITE.defaultBlockState(),
            Blocks.POLISHED_DIORITE.defaultBlockState()
    };

    private static final BlockState[] EMBANKMENT_SHEBEN_FALLBACK = new BlockState[]{
            Blocks.SMOOTH_BASALT.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.MUD.defaultBlockState()
    };

    private static final Map<LitematicParser.Schematic, BlockState[]> SHEBEN_PALETTE_CACHE = new java.util.WeakHashMap<>();

    private static BlockState[] getCrushedStonePalette(RoadPackRegistry.RoadPack pack) {
        if (pack == null) {
            return EMBANKMENT_SHEBEN_FALLBACK;
        }
        LitematicParser.Schematic sheben = pack.getSchematic("Road_nasip_sheben");
        if (sheben == null) {
            return EMBANKMENT_SHEBEN_FALLBACK;
        }
        synchronized (SHEBEN_PALETTE_CACHE) {
            BlockState[] cached = SHEBEN_PALETTE_CACHE.get(sheben);
            if (cached != null) {
                return cached;
            }
            List<BlockState> collected = new ArrayList<>();
            for (int y = 0; y < sheben.getSizeY(); y++) {
                for (int z = 0; z < sheben.getSizeZ(); z++) {
                    for (int x = 0; x < sheben.getSizeX(); x++) {
                        BlockState st = sheben.getBlock(x, y, z);
                        if (st != null && !st.isAir()) {
                            collected.add(st);
                        }
                    }
                }
            }
            BlockState[] pool = collected.isEmpty() ? EMBANKMENT_SHEBEN_FALLBACK : collected.toArray(new BlockState[0]);
            SHEBEN_PALETTE_CACHE.put(sheben, pool);
            return pool;
        }
    }

    public static BlockState pickEmbankmentBlock(
            ModularRoadPreset.EmbankmentMaterial material,
            RoadPackRegistry.RoadPack pack,
            int bx,
            int y,
            int bz,
            boolean isTopSurface
    ) {
        ModularRoadPreset.EmbankmentMaterial m = (material != null) ? material : ModularRoadPreset.EmbankmentMaterial.EARTH;
        return switch (m) {
            case EARTH -> isTopSurface ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            case CRUSHED_STONE -> pickPaletteBlock(getCrushedStonePalette(pack), bx, y, bz);
            case STONE -> pickPaletteBlock(EMBANKMENT_STONE, bx, y, bz);
            case DEEPSLATE -> pickPaletteBlock(EMBANKMENT_DEEPSLATE, bx, y, bz);
            case DIORITE -> pickPaletteBlock(EMBANKMENT_DIORITE, bx, y, bz);
        };
    }

    public static int computeEmbankmentTopY(ModularRoadPreset.EmbankmentShape shape, int roadY, double dz, double dToViaduct) {
        int topY;
        if (shape == ModularRoadPreset.EmbankmentShape.STEPPED_ONE) {
            topY = (dz <= 1.0) ? (roadY - 1) : (roadY - (int) Math.round(dz));
        } else {
            topY = (dz <= 2.0) ? (roadY - 1) : (roadY - 1 - (int) Math.round(dz - 2.0));
        }
        if (dToViaduct <= 4.0) {
            int taperDrop = (int) Math.round(4.0 - dToViaduct);
            if (taperDrop > 0) {
                topY -= taperDrop;
            }
        }
        return topY;
    }

    public static BlockState pickSlopeMaterialBlock(
            ModularRoadPreset.SlopeMaterial material,
            int bx,
            int y,
            int bz
    ) {
        ModularRoadPreset.SlopeMaterial m = (material != null) ? material : ModularRoadPreset.SlopeMaterial.WORLD;
        return switch (m) {
            case STONE -> pickPaletteBlock(EMBANKMENT_STONE, bx, y, bz);
            case DEEPSLATE -> pickPaletteBlock(EMBANKMENT_DEEPSLATE, bx, y, bz);
            case DIORITE -> pickPaletteBlock(EMBANKMENT_DIORITE, bx, y, bz);
            case WORLD -> Blocks.DIRT.defaultBlockState();
        };
    }

    public static boolean shouldStepSlopeOutward(
            ModularRoadPreset.SlopeStyle style,
            int roadY,
            int stepDist,
            int targetTop
    ) {
        if (stepDist <= 1) return true;
        if (style == ModularRoadPreset.SlopeStyle.FLAT) {
            if (targetTop <= roadY + 7) {
                return false;
            }
            int prevStep = (stepDist == 2) ? (roadY + 7) : (roadY + 7 + (stepDist - 3));
            return prevStep < targetTop;
        }
        int prevHeight = (stepDist - 1 <= 4)
                ? (roadY + (stepDist - 1) * 2 - 1)
                : (roadY + 7 + (stepDist - 1 - 4));
        return prevHeight < targetTop;
    }

    public static int computeSlopeStepTopY(
            ModularRoadPreset.SlopeStyle style,
            int roadY,
            int stepDist,
            int targetTop
    ) {
        if (style == ModularRoadPreset.SlopeStyle.FLAT) {
            if (stepDist <= 1) {
                return Math.min(targetTop, roadY + 7);
            }
            return Math.min(targetTop, roadY + 7 + (stepDist - 2));
        }
        int stepHeight = (stepDist <= 4)
                ? (roadY + stepDist * 2 - 1)
                : (roadY + 7 + (stepDist - 4));
        return Math.min(targetTop, stepHeight);
    }

    private static boolean isNaturalTerrainSolid(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.isSolid()
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.LOGS)
                && !(state.getBlock() instanceof BushBlock)
                && !(state.getBlock() instanceof DoublePlantBlock)
                && !(state.getBlock() instanceof SnowLayerBlock);
    }

    private static BlockState resolveWorldSlopeSurface(BlockState worldGroundTop, BlockState grassFallback) {
        if (worldGroundTop == null) return grassFallback;
        Block b = worldGroundTop.getBlock();
        if (b == Blocks.GRASS_BLOCK || b == Blocks.DIRT || b == Blocks.PODZOL || b == Blocks.MYCELIUM
                || b == Blocks.COARSE_DIRT || b == Blocks.ROOTED_DIRT || b == Blocks.MOSS_BLOCK
                || b == Blocks.SAND || b == Blocks.RED_SAND || b == Blocks.SNOW_BLOCK
                || b == Blocks.MUD || b == Blocks.GRAVEL) {
            return worldGroundTop;
        }
        return grassFallback;
    }

    /**
     * Returns a view of basePreset with edge's saved curb/sidewalk/lamp styles applied,
     * ensuring each road segment preserves the style it was built with.
     */
    public static ModularRoadPreset resolveEffectivePreset(RoadEdge edge, ModularRoadPreset basePreset) {
        if (edge == null || basePreset == null) {
            return basePreset;
        }
        String cp = edge.getCurbPalette();
        String sp = edge.getSidewalkPalette();
        String ls = edge.getLampStyle();
        int lsp = edge.getLampSpacing();
        Boolean sdl = edge.getSidewalkDoubleLamps();
        String lp = edge.getLampPlacement();
        String gs = edge.getGuardrailStyle();
        Boolean nw = edge.getNoiseWallEnabled();
        String sg = edge.getStackGuardrails();
        String sgr = edge.getStackGreenery();
        int trsp = edge.getTreeSpacing();
        int bsp = edge.getBushSpacing();
        String em = edge.getEmbankmentMode();
        String es = edge.getEmbankmentShape();
        String emat = edge.getEmbankmentMaterial();
        String sm = edge.getSlopeMode();
        String ss = edge.getSlopeStyle();
        String smat = edge.getSlopeMaterial();
        String ts = edge.getTunnelShape();

        boolean hasCp = (cp != null && !cp.isBlank());
        boolean hasSp = (sp != null && !sp.isBlank());
        boolean hasLs = (ls != null && !ls.isBlank());
        boolean hasLsp = (lsp > 0);
        boolean hasSdl = (sdl != null);
        boolean hasLp = (lp != null && !lp.isBlank());
        boolean hasGs = (gs != null && !gs.isBlank());
        boolean hasNw = (nw != null);
        boolean hasSg = (sg != null && !sg.isBlank());
        boolean hasSgr = (sgr != null && !sgr.isBlank());
        boolean hasTrsp = (trsp > 0);
        boolean hasBsp = (bsp > 0);
        boolean hasEm = (em != null && !em.isBlank());
        boolean hasEs = (es != null && !es.isBlank());
        boolean hasEmat = (emat != null && !emat.isBlank());
        boolean hasSm = (sm != null && !sm.isBlank());
        boolean hasSs = (ss != null && !ss.isBlank());
        boolean hasSmat = (smat != null && !smat.isBlank());
        boolean hasTs = (ts != null && !ts.isBlank());

        if (!hasCp && !hasSp && !hasLs && !hasLsp && !hasSdl && !hasLp && !hasGs && !hasNw && !hasSg
                && !hasSgr && !hasTrsp && !hasBsp
                && !hasEm && !hasEs && !hasEmat && !hasSm && !hasSs && !hasSmat && !hasTs) {
            return basePreset;
        }
        ModularRoadPreset effective = basePreset.copy();
        if (hasCp) {
            try {
                effective.setCurbPalette(ModularRoadPreset.CurbPalette.valueOf(cp.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (hasSp) {
            try {
                effective.setSidewalkPalette(ModularRoadPreset.SidewalkPalette.valueOf(sp.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (hasLs) {
            effective.setLampStyle(ModularRoadPreset.LampStyle.fromStringOrDefault(ls, effective.getLampStyle()));
        }
        if (hasLsp) {
            effective.setLampSpacing(lsp);
        }
        if (hasSdl) {
            effective.setSidewalkDoubleLamps(sdl);
        }
        if (hasLp) {
            effective.setLampPlacement(ModularRoadPreset.LampPlacement.fromStringOrDefault(lp, effective.getLampPlacement()));
        }
        if (hasGs) {
            effective.setGuardrailStyle(ModularRoadPreset.GuardrailStyle.fromStringOrDefault(gs, effective.getGuardrailStyle()));
        }
        if (hasNw) {
            effective.setNoiseWallEnabled(nw);
        }
        if (hasSg) {
            effective.applySerializedElementGuardrails(sg);
        }
        if (hasSgr) {
            effective.applySerializedElementGreenery(sgr);
        }
        if (hasTrsp) {
            effective.setTreeSpacing(trsp);
        }
        if (hasBsp) {
            effective.setBushSpacing(bsp);
        }
        if (hasEm) {
            try {
                effective.setEmbankmentMode(ModularRoadPreset.ToggleMode.valueOf(em.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (hasEs) {
            effective.setEmbankmentShape(ModularRoadPreset.EmbankmentShape.fromStringOrDefault(es, effective.getEmbankmentShape()));
        }
        if (hasEmat) {
            effective.setEmbankmentMaterial(ModularRoadPreset.EmbankmentMaterial.fromStringOrDefault(emat, effective.getEmbankmentMaterial()));
        }
        effective.enforceValidEmbankment();
        if (hasSm) {
            try {
                effective.setSlopeMode(ModularRoadPreset.ToggleMode.valueOf(sm.toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (hasSs) {
            effective.setSlopeStyle(ModularRoadPreset.SlopeStyle.fromStringOrDefault(ss, effective.getSlopeStyle()));
        }
        if (hasSmat) {
            effective.setSlopeMaterial(ModularRoadPreset.SlopeMaterial.fromStringOrDefault(smat, effective.getSlopeMaterial()));
        }
        effective.enforceValidSlope();
        if (hasTs) {
            effective.setTunnelShape(ModularRoadPreset.TunnelShape.fromStringOrDefault(ts, effective.getTunnelShape()));
        }
        return effective;
    }

    /**
     * Backfills curb/sidewalk palette onto pre-existing untagged edges by inspecting their placed surface blocks.
     */
    public static void inferPalettesIfMissing(Level level, Collection<BlockPos> positions, RoadEdge edge) {
        if (level == null || positions == null || edge == null) return;
        boolean needCurb = edge.getCurbPalette() == null || edge.getCurbPalette().isBlank();
        boolean needSw = edge.getSidewalkPalette() == null || edge.getSidewalkPalette().isBlank();
        if (!needCurb && !needSw) return;

        String foundCurb = null;
        String foundSw = null;
        boolean sawStone = false;
        for (BlockPos pos : positions) {
            net.minecraft.world.level.block.Block b = level.getBlockState(pos).getBlock();
            if (needCurb && foundCurb == null) {
                if (b == Blocks.POLISHED_ANDESITE_STAIRS) foundCurb = ModularRoadPreset.CurbPalette.ANDESITE.name();
                else if (b == Blocks.BRICK_STAIRS || b == Blocks.GRANITE_STAIRS) foundCurb = ModularRoadPreset.CurbPalette.BRICK_GRANITE.name();
                else if (b == Blocks.MUD_BRICK_STAIRS) foundCurb = ModularRoadPreset.CurbPalette.MUD_BRICK.name();
                else if (b == Blocks.DIORITE_STAIRS || b == Blocks.POLISHED_DIORITE_STAIRS) foundCurb = ModularRoadPreset.CurbPalette.DIORITE.name();
            }
            if (needSw && foundSw == null) {
                if (b == Blocks.ANDESITE || b == Blocks.POLISHED_ANDESITE || b == Blocks.STONE_BRICKS) foundSw = ModularRoadPreset.SidewalkPalette.MOSAIC.name();
                else if (b == Blocks.BRICKS || b == Blocks.GRANITE || b == Blocks.POLISHED_GRANITE || b == Blocks.MUD_BRICKS) foundSw = ModularRoadPreset.SidewalkPalette.WARM_BRICK.name();
                else if (b == Blocks.DEEPSLATE_BRICKS || b == Blocks.DEEPSLATE_TILES || b == Blocks.POLISHED_DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE) foundSw = ModularRoadPreset.SidewalkPalette.DEEPSLATE.name();
                else if (b == Blocks.DIORITE || b == Blocks.POLISHED_DIORITE || b == Blocks.SMOOTH_STONE) foundSw = ModularRoadPreset.SidewalkPalette.DIORITE.name();
                else if (b == Blocks.STONE) sawStone = true;
            }
            if ((!needCurb || foundCurb != null) && (!needSw || foundSw != null)) break;
        }
        if (needCurb && foundCurb != null) edge.setCurbPalette(foundCurb);
        if (needSw) {
            if (foundSw != null) edge.setSidewalkPalette(foundSw);
            else if (sawStone) edge.setSidewalkPalette(ModularRoadPreset.SidewalkPalette.STONE.name());
        }
    }

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
        return calculateRoadBlocks(level, curve, preset, graph, null);
    }

    public static Vector3d computeMiterNormalAtNode(RoadNode node, UUID thisEdgeId, BezierCurve3D thisCurve, boolean isAtP0, RoadNetworkGraph graph) {
        if (graph == null || node == null || node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION || node.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT) {
            return null;
        }
        List<RoadEdge> edges = graph.getEdgesForNode(node.getId());
        if (edges.size() != 2) {
            return null; // Only use miter bisector when exactly 2 edges meet at this node (continuation/waypoint)
        }
        RoadEdge otherEdge = (thisEdgeId != null && edges.get(0).getId().equals(thisEdgeId)) ? edges.get(1) : edges.get(0);
        if (otherEdge == null) return null;

        // Tangent of this road at this node: pointing in flow direction
        Vector3d thisDir;
        if (isAtP0) {
            Vector3d t = thisCurve.evaluateTangent(0.0);
            thisDir = new Vector3d(t.x, 0, t.z).normalize();
        } else {
            Vector3d t = thisCurve.evaluateTangent(1.0);
            thisDir = new Vector3d(t.x, 0, t.z).normalize();
        }

        // Tangent of other road at this node: aligned in flow direction
        Vector3d otherDir;
        boolean otherStartsAtNode = otherEdge.getNodeAId().equals(node.getId())
                || (otherEdge.getCurve().getP0().distanceTo(node.getPosition()) < 0.5);

        if (isAtP0) {
            if (otherStartsAtNode) {
                Vector3d t = otherEdge.getCurve().evaluateTangent(0.0);
                otherDir = new Vector3d(-t.x, 0, -t.z).normalize();
            } else {
                Vector3d t = otherEdge.getCurve().evaluateTangent(1.0);
                otherDir = new Vector3d(t.x, 0, t.z).normalize();
            }
        } else {
            if (otherStartsAtNode) {
                Vector3d t = otherEdge.getCurve().evaluateTangent(0.0);
                otherDir = new Vector3d(t.x, 0, t.z).normalize();
            } else {
                Vector3d t = otherEdge.getCurve().evaluateTangent(1.0);
                otherDir = new Vector3d(-t.x, 0, -t.z).normalize();
            }
        }

        if (thisDir.lengthSquared() < 1e-4 || otherDir.lengthSquared() < 1e-4) {
            return null;
        }

        // Prevent extreme miter bevels on sharp turns (> 90°)
        if (thisDir.dot(otherDir) < 0.0) {
            return null;
        }

        Vector3d bisector = thisDir.add(otherDir);
        if (bisector.lengthSquared() < 0.05) {
            return null;
        }
        return bisector.normalize();
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(Level level, BezierCurve3D curve, ModularRoadPreset preset, RoadNetworkGraph graph, UUID currentEdgeId) {
        Map<BlockPos, BlockState> blockMap = new HashMap<>();

        // Ensure currentEdgeId is resolved upfront
        if (currentEdgeId == null && graph != null) {
            for (RoadEdge edge : graph.getEdges().values()) {
                if (edge.getCurve().equals(curve) ||
                    (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                    currentEdgeId = edge.getId();
                    break;
                }
            }
        }

        Vector3d startCutNormal = null;
        Vector3d endCutNormal = null;
        if (graph != null) {
            RoadNode node0 = null;
            RoadNode node3 = null;
            for (RoadNode n : graph.getNodes().values()) {
                if (n.getPosition().distanceTo(curve.getP0()) < 1.0) node0 = n;
                if (n.getPosition().distanceTo(curve.getP3()) < 1.0) node3 = n;
            }
            if (node0 != null) {
                startCutNormal = computeMiterNormalAtNode(node0, currentEdgeId, curve, true, graph);
            }
            if (node3 != null) {
                endCutNormal = computeMiterNormalAtNode(node3, currentEdgeId, curve, false, graph);
            }
        }

        TransitionProfile transitionAtP0 = null;
        TransitionProfile transitionAtP3 = null;

        if (graph != null) {
            RoadEdge currentEdge = (currentEdgeId != null) ? graph.getEdge(currentEdgeId) : null;
            if (currentEdge == null) {
                for (RoadEdge edge : graph.getEdges().values()) {
                    if (edge.getCurve().equals(curve) ||
                        (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                        currentEdge = edge;
                        break;
                    }
                }
            }

            if (currentEdge != null) {
                preset = resolveEffectivePreset(currentEdge, preset);
                transitionAtP0 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP0(graph, currentEdge, preset);
                transitionAtP3 = com.roadbuilder.mod.domain.graph.RoadModuleCurve.getTransitionAtP3(graph, currentEdge, preset);
            }
        }

        // 1. Generate cells for this road curve (with transition profiles if connecting to different presets)
        List<Cell> ownCells = planCurveCells(curve, preset, transitionAtP0, transitionAtP3, startCutNormal, endCutNormal);
        if (ownCells.isEmpty()) {
            return blockMap;
        }

        RoadNode junctionAtP0 = null;
        double radiusP0 = 0.0;
        RoadNode junctionAtP3 = null;
        double radiusP3 = 0.0;

        RoadNode roundaboutAtP0 = null;
        double rRadiusP0 = 0.0;
        RoadNode roundaboutAtP3 = null;
        double rRadiusP3 = 0.0;

        double curveLen = curve.getApproximateLength();

        if (graph != null) {
            for (RoadNode node : graph.getNodes().values()) {
                boolean isRoundabout = node.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT
                        || (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION && "ROUNDABOUT".equalsIgnoreCase(node.getJunctionStyle()));
                if (isRoundabout) {
                    double rOut = node.getOuterRadius() > 0 ? node.getOuterRadius() : 23.0;
                    double cutDist = rOut + 7.5;
                    if (node.getPosition().distanceTo(curve.getP0()) < 1.0) {
                        roundaboutAtP0 = node;
                        rRadiusP0 = cutDist;
                    } else if (node.getPosition().distanceTo(curve.getP3()) < 1.0) {
                        roundaboutAtP3 = node;
                        rRadiusP3 = cutDist;
                    }
                } else if (node.getType() == com.roadbuilder.mod.domain.graph.NodeType.JUNCTION) {
                    if (node.getPosition().distanceTo(curve.getP0()) < 1.0) {
                        junctionAtP0 = node;
                        radiusP0 = JunctionGenerator.getArmEntryDistance(node, graph.getEdgesForNode(node.getId()), currentEdgeId);
                    } else if (node.getPosition().distanceTo(curve.getP3()) < 1.0) {
                        junctionAtP3 = node;
                        radiusP3 = JunctionGenerator.getArmEntryDistance(node, graph.getEdgesForNode(node.getId()), currentEdgeId);
                    }
                }
            }
        }

        if (junctionAtP0 != null || junctionAtP3 != null || roundaboutAtP0 != null || roundaboutAtP3 != null) {
            final RoadNode j0 = junctionAtP0;
            final double r0 = radiusP0;
            final RoadNode j3 = junctionAtP3;
            final double r3 = radiusP3;
            final RoadNode rb0 = roundaboutAtP0;
            final double rr0 = rRadiusP0;
            final RoadNode rb3 = roundaboutAtP3;
            final double rr3 = rRadiusP3;
            ownCells = ownCells.stream().filter(cell -> {
                if (j0 != null && cell.t() < r0) {
                    return false;
                }
                if (j3 != null && cell.t() > curveLen - r3) {
                    return false;
                }
                Vector3d cellPos = new Vector3d(cell.x() + 0.5, cell.y(), cell.z() + 0.5);
                if (rb0 != null && cellPos.distanceTo(curve.getP0()) < rr0) {
                    return false;
                }
                if (rb3 != null && cellPos.distanceTo(curve.getP3()) < rr3) {
                    return false;
                }
                return true;
            }).toList();
        }

        // 2. Gather existing roads from saved graph to resolve intersections & grade separations
        Map<Long, List<Cell>> otherRoadCells = new HashMap<>();
        Set<Long> continuationCellKeys = new HashSet<>();
        Set<Long> otherRoadwayKeys = new HashSet<>();
        Map<Long, Integer> lowerRoadSurfaceY = new HashMap<>();
        Set<Long> lowerRoadClearanceZone = new HashSet<>();
        List<Double> lowerCrossingT = new ArrayList<>();
        Map<Long, Integer> groundYMap = new HashMap<>();
        Map<Long, Integer> solidBedYMap = new HashMap<>();
        List<RoadIntersection> intersections = new ArrayList<>();
        Set<BlockPos> otherActiveRoadBlocks = Collections.emptySet();

        if (level instanceof ServerLevel serverLevel) {
            RoadGraphSavedData savedData = RoadGraphSavedData.get(serverLevel);
            if (savedData != null) {
                if (currentEdgeId == null && graph != null) {
                    for (RoadEdge edge : graph.getEdges().values()) {
                        if (edge.getCurve().equals(curve) ||
                            (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01)) {
                            currentEdgeId = edge.getId();
                            break;
                        }
                    }
                }
                otherActiveRoadBlocks = (currentEdgeId != null)
                        ? savedData.getAllOtherEdgeBlocks(currentEdgeId)
                        : savedData.getAllOtherEdgeBlocks(Collections.emptySet());
            }
        }

        double hRoadway1 = preset.getRoadwayWidth() / 2.0;
        boolean p0Connected = false;
        boolean p3Connected = false;
        boolean p0IsContinuation = false;
        boolean p3IsContinuation = false;
        boolean p0ConnectsToViaduct = false;
        boolean p3ConnectsToViaduct = false;
        int edgesAtP0 = 0;
        int edgesAtP3 = 0;

        if (graph != null) {
            for (RoadEdge edge : graph.getEdges().values()) {
                if (currentEdgeId != null && edge.getId().equals(currentEdgeId)) continue;
                if (edge.getCurve().equals(curve)) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP0()) < 0.01) continue;

                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP0()) < 1.0) {
                    edgesAtP0++;
                }
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 1.0 || edge.getCurve().getP3().distanceTo(curve.getP3()) < 1.0) {
                    edgesAtP3++;
                }
            }

            for (RoadEdge edge : graph.getEdges().values()) {
                if (currentEdgeId != null && edge.getId().equals(currentEdgeId)) continue;
                if (edge.getCurve().equals(curve)) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP0()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP3()) < 0.01) continue;
                if (edge.getCurve().getP0().distanceTo(curve.getP3()) < 0.01 && edge.getCurve().getP3().distanceTo(curve.getP0()) < 0.01) continue;

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

                ModularRoadPreset otherPresetBase = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(edge.getPresetId()) || p.getName().equals(edge.getPresetId()) || p.getName().equals(edge.getProfile().getName()))
                        .findFirst()
                        .orElse(preset);
                ModularRoadPreset otherPreset = resolveEffectivePreset(edge, otherPresetBase);

                double hRoadway2 = otherPreset.getRoadwayWidth() / 2.0;

                boolean isContinuationP0 = false;
                if (sharesP0) {
                    if (edgesAtP0 == 1) {
                        Vector3d vCurve = curve.evaluateTangent(0.0);
                        Vector3d vOther = (distP0toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0).multiply(-1.0) : edge.getCurve().evaluateTangent(1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : 1.0;
                        if (dot > 0.70) {
                            isContinuationP0 = true;
                            p0IsContinuation = true;
                        }
                    }
                    if (!isContinuationP0) {
                        Vector3d vCurve = curve.evaluateTangent(0.0);
                        Vector3d vOther = (distP0toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP0(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuationP3 = false;
                if (sharesP3) {
                    if (edgesAtP3 == 1) {
                        Vector3d vCurve = curve.evaluateTangent(1.0);
                        Vector3d vOther = (distP3toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        double dot = (lC > 1e-6 && lO > 1e-6) ? (vCurve.x * vOther.x + vCurve.z * vOther.z) / (lC * lO) : 1.0;
                        if (dot > 0.70) {
                            isContinuationP3 = true;
                            p3IsContinuation = true;
                        }
                    }
                    if (!isContinuationP3) {
                        Vector3d vCurve = curve.evaluateTangent(1.0).multiply(-1.0);
                        Vector3d vOther = (distP3toP0 < 1.0) ? edge.getCurve().evaluateTangent(0.0) : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                        double lC = Math.hypot(vCurve.x, vCurve.z);
                        double lO = Math.hypot(vOther.x, vOther.z);
                        Vector3d t1 = (lC > 1e-6) ? new Vector3d(vCurve.x / lC, 0, vCurve.z / lC) : new Vector3d(0, 0, 1);
                        Vector3d n1 = new Vector3d(-t1.z, 0, t1.x);
                        Vector3d t2 = (lO > 1e-6) ? new Vector3d(vOther.x / lO, 0, vOther.z / lO) : new Vector3d(1, 0, 0);
                        Vector3d n2 = new Vector3d(-t2.z, 0, t2.x);
                        intersections.add(new RoadIntersection(curve.getP3(), t1, n1, hRoadway1, t2, n2, hRoadway2));
                    }
                }

                boolean isContinuation = (sharesP0 && isContinuationP0) || (sharesP3 && isContinuationP3);

                if (sharesP0 && doesConnectedEndpointTriggerViaduct(level, groundYMap, otherPreset, curve.getP0())) {
                    p0ConnectsToViaduct = true;
                }
                if (sharesP3 && doesConnectedEndpointTriggerViaduct(level, groundYMap, otherPreset, curve.getP3())) {
                    p3ConnectsToViaduct = true;
                }

                List<Cell> theirCells = planCurveCells(edge.getCurve(), otherPreset);
                Vector3d sharedPt = sharesP0 ? curve.getP0() : curve.getP3();
                for (Cell c : theirCells) {
                    long key = coordKey(c.x(), c.z());
                    otherRoadCells.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    if (c.kind() == Kind.ASPHALT || c.kind() == Kind.LINE) {
                        otherRoadwayKeys.add(key);
                    }
                    if (isContinuation) {
                        double dToShared = Math.hypot(c.x() - sharedPt.x, c.z() - sharedPt.z);
                        if (dToShared <= 3.5) {
                            continuationCellKeys.add(key);
                        }
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

            Cell resolved;
            if (intersectingOther == null || continuationCellKeys.contains(key)) {
                resolved = c;
            } else {
                resolved = resolveWinner(c, intersectingOther);
            }

            // Проверяем, находится ли клетка внутри зоны проезжей части перекрестка (OBB)
            boolean inIntersectionRoadway = false;
            for (RoadIntersection inter : intersections) {
                double dx = (c.x() + 0.5) - inter.center.x;
                double dz = (c.z() + 0.5) - inter.center.z;
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
                // Газоны-разделители, центральные бордюры, разметка и тротуары превращаются в чистый асфальт!
                if (resolved.kind() == Kind.LINE || 
                    resolved.kind() == Kind.CURB || 
                    resolved.kind() == Kind.GRASS ||
                    resolved.kind() == Kind.SIDEWALK) {
                    resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), 
                                        Kind.ASPHALT, resolved.facing(), null, 
                                        resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                }
            } else if (otherRoadwayKeys.contains(key) && !continuationCellKeys.contains(key)) {
                if (resolved.kind() == Kind.SIDEWALK || resolved.kind() == Kind.CURB || resolved.kind() == Kind.GRASS || resolved.kind() == Kind.LINE) {
                    resolved = new Cell(resolved.x(), resolved.z(), resolved.y(), Kind.ASPHALT, resolved.facing(), null, resolved.localX(), resolved.t(), resolved.nx(), resolved.nz());
                }
            } else if (intersectingOther != null && !continuationCellKeys.contains(key)) {
                if (resolved.kind() == Kind.SIDEWALK || resolved.kind() == Kind.CURB || resolved.kind() == Kind.GRASS || resolved.kind() == Kind.LINE) {
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
        boolean embankmentEnabled = preset.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF;
        ModularRoadPreset.EmbankmentShape embankmentShape = preset.getEmbankmentShape();
        ModularRoadPreset.EmbankmentMaterial embankmentMaterial = preset.getEmbankmentMaterial();
        ModularRoadPreset.SlopeStyle slopeStyle = preset.getSlopeStyle();
        ModularRoadPreset.SlopeMaterial slopeMaterial = preset.getSlopeMaterial();
        RoadPackRegistry.RoadPack activePack = RoadPackRegistry.getInstance().getActivePack();
        LitematicParser.Schematic pillarSchematic = activePack.getSchematic("Road_estacada_opora_new");
        if (pillarSchematic == null) {
            pillarSchematic = activePack.getSchematic("road_opora");
        }
        LitematicParser.Schematic winosSchematic = activePack.getSchematic("Road_estacada_opora_winos");
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
            boolean autoOverWaterViaduct = overWater && (!embankmentEnabled || (sy - gY) >= 4);
            boolean isElevated = (sy - gY) >= 6;
            if (!isElevated) {
                // If centerline check hit a pillar or narrow obstacle, sample terrain outside the road deck
                Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                double sampleOffset = (totalWidth / 2.0) + 3.0;
                int offX = (int) Math.round(norm.x * sampleOffset);
                int offZ = (int) Math.round(norm.z * sampleOffset);
                int gY_left = groundYMap.computeIfAbsent(coordKey(sx + offX, sz + offZ), k -> getTrueGroundY(level, sx + offX, sz + offZ));
                int gY_right = groundYMap.computeIfAbsent(coordKey(sx - offX, sz - offZ), k -> getTrueGroundY(level, sx - offX, sz - offZ));
                int minGY = Math.min(gY, Math.min(gY_left, gY_right));
                if ((sy - minGY) >= 6) {
                    isElevated = true;
                }
            }

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
                    (preset.getViaductMode() == ModularRoadPreset.Mode.AUTO && (autoOverWaterViaduct || isElevated))
            );
            viaductStations[s] = active;
        }

        // Viaduct Debouncing: bridge small non-viaduct gaps <= 24m between viaduct sections (eliminates micro-embankments on bridges)
        for (int i = 0; i < totalStations; i++) {
            if (viaductStations[i]) {
                for (int j = i + 2; j < Math.min(totalStations, i + 26); j++) {
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

        // Precalculate tunnel sections per station:
        // Tunnels generate ONLY when the road is COMPLETELY inside the mountain across its entire width (no half-tunnels)
        boolean[] tunnelStations = new boolean[totalStations];
        double halfRoadW = totalWidth / 2.0;

        if (tunnelEnabled) {
            for (int s = 0; s < totalStations; s++) {
                if (preset.getTunnelMode() == ModularRoadPreset.Mode.ALWAYS) {
                    tunnelStations[s] = true;
                    continue;
                }

                double dist = Math.min(totalCurveLen, (double) s);
                double tParam = curve.getArcLengthTable().getTForDistance(dist);
                Vector3d pt = curve.evaluate(tParam);
                Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                int sy = (int) Math.round(pt.y);

                // Sample 5 points across the entire road cross-section: Left edge, Left quarter, Center, Right quarter, Right edge
                double[] lateralOffsets = new double[]{
                        -(halfRoadW - 0.5),
                        -(halfRoadW * 0.5),
                        0.0,
                        (halfRoadW * 0.5),
                        (halfRoadW - 0.5)
                };

                boolean allPointsInsideMountain = true;

                for (double offset : lateralOffsets) {
                    int px = (int) Math.round(pt.x + norm.x * offset);
                    int pz = (int) Math.round(pt.z + norm.z * offset);

                    int gY = groundYMap.computeIfAbsent(coordKey(px, pz), k -> getTrueGroundY(level, px, pz));
                    int overhead = gY - sy;

                    // All points across the road cross-section must be covered by at least 7 blocks of mountain terrain
                    if (overhead < 7) {
                        allPointsInsideMountain = false;
                        break;
                    }

                    // Check for solid rock/mountain mass above the arch ceiling (sy + 6 to sy + 10)
                    BlockPos pPos = new BlockPos(px, sy, pz);
                    int solidCount = 0;
                    for (int dy = 6; dy <= 10; dy++) {
                        BlockState checkState = level.getBlockState(pPos.above(dy));
                        if (!checkState.isAir() && !checkState.is(BlockTags.LEAVES) && !checkState.is(BlockTags.LOGS) && checkState.isSolid()) {
                            solidCount++;
                        }
                    }

                    if (solidCount < 3) {
                        allPointsInsideMountain = false;
                        break;
                    }
                }

                tunnelStations[s] = allPointsInsideMountain;
            }

            // Debounce short tunnel segments: an auto tunnel must be at least 4 stations (meters) long
            for (int i = 0; i < totalStations; i++) {
                if (tunnelStations[i]) {
                    int len = 0;
                    while (i + len < totalStations && tunnelStations[i + len]) {
                        len++;
                    }
                    if (len < 4 && preset.getTunnelMode() != ModularRoadPreset.Mode.ALWAYS) {
                        for (int k = 0; k < len; k++) {
                            tunnelStations[i + k] = false;
                        }
                    }
                    i += len;
                }
            }

            // Bridge small 1-2 block gaps in tunnels under mountain ridges
            for (int i = 0; i < totalStations; i++) {
                if (tunnelStations[i]) {
                    for (int j = i + 2; j <= Math.min(totalStations - 1, i + 3); j++) {
                        if (tunnelStations[j]) {
                            for (int k = i + 1; k < j; k++) {
                                tunnelStations[k] = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        // Per-station cut depth sampling and longitudinal smoothing (eliminates isolated spikes & missed setback ridges)
        int[] cutDepthL = new int[totalStations];
        int[] cutDepthR = new int[totalStations];

        for (int s = 0; s < totalStations; s++) {
            if (viaductStations[s] || tunnelStations[s]) {
                cutDepthL[s] = 0;
                cutDepthR[s] = 0;
                continue;
            }

            double dist = Math.min(totalCurveLen, (double) s);
            double tParam = curve.getArcLengthTable().getTForDistance(dist);
            Vector3d pt = curve.evaluate(tParam);
            Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
            int sy = (int) Math.round(pt.y);

            cutDepthL[s] = sampleStationCutDepth(level, groundYMap, pt, norm, halfRoadW, -1, sy);
            cutDepthR[s] = sampleStationCutDepth(level, groundYMap, pt, norm, halfRoadW, 1, sy);
        }

        int[] smoothCutL = smoothStationCutDepths(cutDepthL);
        int[] smoothCutR = smoothStationCutDepths(cutDepthR);

        for (Cell cell : finalCells) {
            long key = coordKey(cell.x(), cell.z());
            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
            boolean viaductActive = viaductStations[stIdx];
            viaductActiveMap.put(key, viaductActive);
        }

        // 5. Convert cells to Minecraft blocks
        ModularRoadPreset.CurbPalette curbPalette = preset.getCurbPalette();
        ModularRoadPreset.SidewalkPalette sidewalkPalette = preset.getSidewalkPalette();

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
                    case CURB -> orientStairs(pickCurbBlock(curbPalette, cell.x(), cell.y(), cell.z()), cell.facing(), Half.BOTTOM);
                    case GRASS -> grassState;
                    case STONE -> foundationState;
                    case SIDEWALK -> pickSidewalkBlock(sidewalkPalette, cell.x(), cell.y(), cell.z());
                };
            }

            // Surface block
            blockMap.put(surfacePos, surfaceState);

            // Foundation underneath
            boolean isViaductDeck = viaductActiveMap.getOrDefault(cellKey, false);
            if (isViaductDeck) {
                blockMap.put(surfacePos.below(), foundationState);
            } else if (cell.kind() == Kind.GRASS) {
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

            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cell.t())));
            boolean isTunnelSection = !roadAbove && tunnelStations[stIdx];

            int worldSurfaceAtRoad = level.getHeight(Heightmap.Types.WORLD_SURFACE, cell.x(), cell.z());
            int terrainOverhead = worldSurfaceAtRoad - cell.y();

            if (roadAbove) {
                // Если сверху проходит другая эстакада/дорога
                for (int h = 1; h <= maxClearanceH; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            } else if (isTunnelSection) {
                int tunnelT = Math.max(0, (int) Math.floor(cell.t()));
                populateTunnelColumn(
                        blockMap,
                        otherActiveRoadBlocks,
                        surfacePos,
                        preset.getTunnelShape(),
                        cell.localX(),
                        totalWidth,
                        tunnelT,
                        cell.nx(),
                        cell.nz(),
                        terrainOverhead > 5,
                        true
                );
            } else {
                // Открытый участок или въездная выемка в склоне (open-cut)
                // Очищаем воздух до поверхности земли + 2 блока, устраняя нависающий дерн
                int clearH = Math.max(maxClearanceH, terrainOverhead + 2);
                for (int h = 1; h <= clearH; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            }

            int terrainSurfaceY = groundYMap.computeIfAbsent(cellKey, k -> getTrueGroundY(level, cell.x(), cell.z()));
            boolean viaductActive = viaductActiveMap.getOrDefault(cellKey, false);
            boolean isOverLowerRoadCell = lowerRoadClearanceZone.contains(cellKey);

            if (viaductActive && !isTunnelSection) {
                // 1. Viaduct profile underneath (matching Road_estacada_profile.litematic: 100% solid stone trapezoid)
                // At below(1): stone across the deck
                blockMap.put(surfacePos.below(1), foundationState);
                // At below(2): stone inset by 2 blocks on left and right
                if (cell.localX() >= 2 && cell.localX() <= totalWidth - 3) {
                    blockMap.put(surfacePos.below(2), foundationState);
                }
                // At below(3): stone inset by 2 blocks on left and right
                if (cell.localX() >= 2 && cell.localX() <= totalWidth - 3) {
                    blockMap.put(surfacePos.below(3), foundationState);
                }

                // 2. Pillars: Standard (Road_estacada_opora_new) or Outrigger Bent (Road_estacada_opora_winos)
                double dToViaduct = (stIdx >= 0 && stIdx < totalStations) ? distToViaduct[stIdx] : 0.0;
                if (dToViaduct == 0.0) {
                    int pillarCycle = Math.floorMod((int) Math.round(cell.t()), 18);
                    boolean isNearLowerRoad = isOverLowerRoadCell;
                    if (!isNearLowerRoad) {
                        for (double lt : lowerCrossingT) {
                            if (Math.abs(cell.t() - lt) <= 5.0) {
                                isNearLowerRoad = true;
                                break;
                            }
                        }
                    }

                    if (isNearLowerRoad && pillarCycle < 5) {
                        // Outrigger bent (Road_estacada_opora_winos.litematic)
                        // Solid stone crossbeam 1 block below road, spanning across lower road
                        blockMap.put(surfacePos.below(1), foundationState);
                        blockMap.put(surfacePos.below(2), foundationState);
                        blockMap.put(surfacePos.below(3), foundationState);

                        // Side pillars (5x5 Half.TOP capital + 3x3 column) outside lower road clearance zone
                        int leftPx = 0;
                        int rightPx = Math.max(0, totalWidth - 5);
                        if (!isOverLowerRoadCell) {
                            placePillarSection(level, blockMap, surfacePos, cell, leftPx, pillarCycle, curbBlock, foundationState);
                            if (rightPx > leftPx + 4) {
                                placePillarSection(level, blockMap, surfacePos, cell, rightPx, pillarCycle, curbBlock, foundationState);
                            }
                        }
                    } else if (!isOverLowerRoadCell && pillarCycle < 5) {
                        // Standard viaduct pillar (Road_estacada_opora_new.litematic)
                        for (int px : pillarPositions) {
                            placePillarSection(level, blockMap, surfacePos, cell, px, pillarCycle, curbBlock, foundationState);
                        }
                    }
                }
            }

            // Embankment foundation under the road deck (extends down through water to riverbed/seabed; blocked at junctions/viaducts/tunnels/overpasses)
            boolean isJunctionAtP0 = (edgesAtP0 >= 2) || junctionAtP0 != null || roundaboutAtP0 != null;
            boolean isJunctionAtP3 = (edgesAtP3 >= 2) || junctionAtP3 != null || roundaboutAtP3 != null;
            boolean p0BlocksEmb = isJunctionAtP0 || p0ConnectsToViaduct;
            boolean p3BlocksEmb = isJunctionAtP3 || p3ConnectsToViaduct;
            boolean nearConnectedEnd = (cell.t() < 6.0 && p0BlocksEmb) ||
                                       (cell.t() > totalCurveLen - 6.0 && p3BlocksEmb);
            double dToViaduct = (stIdx >= 0 && stIdx < totalStations) ? distToViaduct[stIdx] : 0.0;
            boolean nearViaduct = dToViaduct <= 6.0 || viaductActive;

            boolean isWaterColumn = isWaterAt(level, new BlockPos(cell.x(), terrainSurfaceY, cell.z()))
                    || isWaterAt(level, new BlockPos(cell.x(), terrainSurfaceY + 1, cell.z()));
            int solidBedY = isWaterColumn
                    ? solidBedYMap.computeIfAbsent(cellKey, k -> getSolidBedY(level, cell.x(), cell.z()))
                    : terrainSurfaceY;

            if (embankmentEnabled && !viaductActive && !nearViaduct && !isTunnelSection && !isOverLowerRoadCell && !nearConnectedEnd) {
                boolean heightOk = cell.y() - 1 > solidBedY && (!viaductEnabled || (cell.y() - terrainSurfaceY) < 6);
                if (heightOk) {
                    if (embankmentMaterial != ModularRoadPreset.EmbankmentMaterial.EARTH && cell.kind() != Kind.GRASS) {
                        blockMap.put(surfacePos.below(), pickEmbankmentBlock(embankmentMaterial, activePack, cell.x(), cell.y() - 1, cell.z(), false));
                    }
                    for (int ey = cell.y() - 2; ey >= solidBedY; ey--) {
                        BlockPos fp = new BlockPos(cell.x(), ey, cell.z());
                        blockMap.put(fp, pickEmbankmentBlock(embankmentMaterial, activePack, cell.x(), ey, cell.z(), false));
                    }
                }
            } else if (nearViaduct && !viaductActive && !isTunnelSection && !isOverLowerRoadCell && cell.y() - 1 > solidBedY) {
                // Bridge abutment approaching viaduct: solid stone foundation down to ground/seabed, NEVER dirt!
                for (int ey = cell.y() - 2; ey >= solidBedY; ey--) {
                    BlockPos fp = new BlockPos(cell.x(), ey, cell.z());
                    blockMap.put(fp, foundationState);
                }
            }

            // Clear underpass clearance between upper bridge deck and lower road
            if (isOverLowerRoadCell) {
                int lowerY = lowerRoadSurfaceY.getOrDefault(cellKey, terrainSurfaceY);
                for (int ey = lowerY + 1; ey <= cell.y() - 2; ey++) {
                    BlockPos ap = new BlockPos(cell.x(), ey, cell.z());
                    if (!otherActiveRoadBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            }

            // Bridge Abutment (Устой моста) and Road Endings - 1-2 block reinforcement under deck, zero monolithic walls to ground
            boolean isAbutmentOrEnd = false;
            if (cell.t() < 1.0 && !p0Connected) {
                isAbutmentOrEnd = true;
            } else if (cell.t() > totalCurveLen - 1.0 && !p3Connected) {
                isAbutmentOrEnd = true;
            } else {
                boolean prevV = (stIdx > 0) && viaductStations[stIdx - 1];
                boolean nextV = (stIdx < totalStations - 1) && viaductStations[stIdx + 1];
                if (prevV != viaductActive || nextV != viaductActive) {
                    isAbutmentOrEnd = true;
                }
            }

            if (isAbutmentOrEnd && !isTunnelSection && !isOverLowerRoadCell && cell.y() - 1 > solidBedY) {
                int minReinforceY = Math.max(solidBedY, cell.y() - 2);
                boolean useEmbMat = embankmentEnabled && !nearViaduct
                        && embankmentMaterial != ModularRoadPreset.EmbankmentMaterial.EARTH;
                for (int ey = cell.y() - 1; ey >= minReinforceY; ey--) {
                    BlockPos ap = new BlockPos(cell.x(), ey, cell.z());
                    BlockState endReinforceState = useEmbMat
                            ? pickEmbankmentBlock(embankmentMaterial, activePack, cell.x(), ey, cell.z(), false)
                            : foundationState;
                    blockMap.put(ap, endReinforceState);
                }
            }
        }

        // 6. Generate Continuous Side Embankments and Slopes - zero holes, zero voids at endings
        boolean slopeEnabled = preset.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF;

        if (embankmentEnabled || slopeEnabled) {
            double maxRadius = slopeEnabled ? 18.0 : 14.0;

            boolean isJunctionAtP0 = (edgesAtP0 >= 2) || junctionAtP0 != null || roundaboutAtP0 != null;
            boolean isJunctionAtP3 = (edgesAtP3 >= 2) || junctionAtP3 != null || roundaboutAtP3 != null;
            boolean shouldRoundP0 = !p0Connected && !viaductStations[0];
            boolean shouldRoundP3 = !p3Connected && !viaductStations[totalStations - 1];

            Vector3d p0 = curve.getP0();
            Vector3d tan0 = curve.evaluateTangent(0.0);
            double t0Len = Math.hypot(tan0.x, tan0.z);
            double t0x = (t0Len > 1e-6) ? (tan0.x / t0Len) : 0.0;
            double t0z = (t0Len > 1e-6) ? (tan0.z / t0Len) : 1.0;
            int roadY0 = (int) Math.round(p0.y);

            Vector3d p3 = curve.getP3();
            Vector3d tan3 = curve.evaluateTangent(1.0);
            double t3Len = Math.hypot(tan3.x, tan3.z);
            double t3x = (t3Len > 1e-6) ? (tan3.x / t3Len) : 0.0;
            double t3z = (t3Len > 1e-6) ? (tan3.z / t3Len) : 1.0;
            int roadY3 = (int) Math.round(p3.y);

            Map<Long, Integer> roadDeckY = new HashMap<>();
            Map<Long, Double> roadDeckT = new HashMap<>();
            for (Cell c : finalCells) {
                long k = coordKey(c.x(), c.z());
                roadDeckY.put(k, c.y());
                roadDeckT.put(k, c.t());
            }

            // Multi-source BFS dilation outward from road deck blocks:
            // Expands neighbor-by-neighbor in 8 directions, guaranteeing 100% gap-free grid coverage
            // along straight roads, diagonals, sharp curves, and end-caps!
            Map<Long, Long> bestSource = new HashMap<>();
            Map<Long, Double> bestDist = new HashMap<>();
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();

            for (Cell c : finalCells) {
                long k = coordKey(c.x(), c.z());
                bestSource.put(k, k);
                bestDist.put(k, 0.0);
                queue.add(k);
            }

            int[][] dirs = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
            };

            while (!queue.isEmpty()) {
                long curKey = queue.poll();
                int cx = (int) (curKey >> 32);
                int cz = (int) curKey;
                long srcKey = bestSource.get(curKey);
                int sx = (int) (srcKey >> 32);
                int sz = (int) srcKey;

                for (int[] d : dirs) {
                    int nx = cx + d[0];
                    int nz = cz + d[1];
                    long nkey = coordKey(nx, nz);

                    if (roadDeckColumns.contains(nkey)) continue;
                    if (lowerRoadClearanceZone.contains(nkey)) continue;

                    double dist = Math.hypot(nx - sx, nz - sz);
                    if (dist <= maxRadius) {
                        Double prevD = bestDist.get(nkey);
                        if (prevD == null || dist < prevD) {
                            bestDist.put(nkey, dist);
                            bestSource.put(nkey, srcKey);
                            queue.add(nkey);
                        }
                    }
                }
            }

            Map<Long, SideCandidate> sideCandidates = new HashMap<>();

            for (Map.Entry<Long, Double> entry : bestDist.entrySet()) {
                long colKey = entry.getKey();
                if (roadDeckColumns.contains(colKey)) continue;

                int bx = (int) (colKey >> 32);
                int bz = (int) colKey;
                double dz = entry.getValue();

                long srcKey = bestSource.get(colKey);
                int sx = (int) (srcKey >> 32);
                int sz = (int) srcKey;
                int roadY = roadDeckY.getOrDefault(srcKey, roadY0);
                double t = roadDeckT.getOrDefault(srcKey, 0.0);

                // Check if candidate is past P0 or P3
                double proj0 = (bx - p0.x) * (-t0x) + (bz - p0.z) * (-t0z);
                double proj3 = (bx - p3.x) * t3x + (bz - p3.z) * t3z;

                boolean isEndCap = false;
                int side = 0;
                double outX = 0;
                double outZ = 0;

                if (proj0 > 0.01) {
                    if (shouldRoundP0) {
                        isEndCap = true;
                        roadY = roadY0;
                        t = 0.0;
                        outX = -t0x;
                        outZ = -t0z;
                    } else if (p0Connected && !isJunctionAtP0 && !p0ConnectsToViaduct && proj0 <= dz + 0.5
                            && !isColumnOnOtherRoadDeck(otherRoadCells, colKey, roadY0)) {
                        // Outer wedge around a 2-segment bend: keep slope/embankment continuous across the joint
                        roadY = roadY0;
                        t = 0.0;
                        Vector3d norm0 = curve.evaluateNormal(0.0, Vector3d.UP);
                        double signedLat = (bx + 0.5 - p0.x) * norm0.x + (bz + 0.5 - p0.z) * norm0.z;
                        side = (signedLat < 0) ? -1 : 1;
                        outX = norm0.x * side;
                        outZ = norm0.z * side;
                    } else {
                        continue;
                    }
                } else if (proj3 > 0.01) {
                    if (shouldRoundP3) {
                        isEndCap = true;
                        roadY = roadY3;
                        t = totalCurveLen;
                        outX = t3x;
                        outZ = t3z;
                    } else if (p3Connected && !isJunctionAtP3 && !p3ConnectsToViaduct && proj3 <= dz + 0.5
                            && !isColumnOnOtherRoadDeck(otherRoadCells, colKey, roadY3)) {
                        // Outer wedge around a 2-segment bend: keep slope/embankment continuous across the joint
                        roadY = roadY3;
                        t = totalCurveLen;
                        Vector3d norm3 = curve.evaluateNormal(1.0, Vector3d.UP);
                        double signedLat = (bx + 0.5 - p3.x) * norm3.x + (bz + 0.5 - p3.z) * norm3.z;
                        side = (signedLat < 0) ? -1 : 1;
                        outX = norm3.x * side;
                        outZ = norm3.z * side;
                    } else {
                        continue;
                    }
                } else {
                    double tParam = curve.getArcLengthTable().getTForDistance(Math.min(totalCurveLen, Math.max(0.0, t)));
                    Vector3d pt = curve.evaluate(tParam);
                    Vector3d norm = curve.evaluateNormal(tParam, Vector3d.UP);
                    double signedLat = (bx + 0.5 - pt.x) * norm.x + (bz + 0.5 - pt.z) * norm.z;
                    side = (signedLat < 0) ? -1 : 1;
                    outX = norm.x * side;
                    outZ = norm.z * side;
                }

                sideCandidates.put(colKey, new SideCandidate(bx, bz, roadY, dz, t, outX, outZ, side, isEndCap));
            }

            // Place side embankments and slopes independently per column (never broken by adjacent terrain)
            for (SideCandidate cand : sideCandidates.values()) {
                long colKey = coordKey(cand.bx(), cand.bz());
                if (roadDeckColumns.contains(colKey)) continue;
                if (lowerRoadClearanceZone.contains(colKey)) continue;
                if (isColumnOnOtherRoadDeck(otherRoadCells, colKey, cand.roadY())) continue;

                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(cand.t())));
                if (viaductStations[stIdx]) continue; // Viaduct bridge slice: no side embankment or slope!
                if (tunnelStations[stIdx]) continue; // Underground mountain tunnel: no open-cut slope!
                if (cand.t() < 3.5 && (isJunctionAtP0 || p0ConnectsToViaduct)) continue; // Junction/viaduct start: no side embankment into junction/viaduct!
                if (cand.t() > totalCurveLen - 3.5 && (isJunctionAtP3 || p3ConnectsToViaduct)) continue; // Junction/viaduct end: no side embankment into junction/viaduct!

                double dz = cand.dz();
                if (dz < 0.1 || dz > maxRadius) continue;

                int groundY = groundYMap.computeIfAbsent(colKey, k -> getTrueGroundY(level, cand.bx(), cand.bz()));
                int roadY = cand.roadY();

                // 1. Hillside cut -> 2-block STAIR or FLAT retaining wall up to 8 blocks, then 1-block natural slope above.
                int cutDepth = (cand.side() < 0) ? smoothCutL[stIdx] : ((cand.side() > 0) ? smoothCutR[stIdx] : 0);

                if (slopeEnabled && !cand.isEndCap() && cutDepth >= 3 && groundY > roadY) {
                    int targetTop = groundY;

                    if (targetTop >= roadY) {
                        int stepDist = (int) Math.max(1, Math.round(dz));
                        if (!shouldStepSlopeOutward(slopeStyle, roadY, stepDist, targetTop)) {
                            continue;
                        }

                        int stepTopY = computeSlopeStepTopY(slopeStyle, roadY, stepDist, targetTop);
                        int slopeMaterialCapY = roadY + 7; // First 8 vertical blocks (roadY .. roadY + 7)
                        boolean useCustomMaterial = (slopeMaterial != ModularRoadPreset.SlopeMaterial.WORLD)
                                && ((slopeStyle == ModularRoadPreset.SlopeStyle.FLAT && stepDist == 1)
                                || (slopeStyle == ModularRoadPreset.SlopeStyle.STAIR && stepDist <= 4 && stepTopY <= slopeMaterialCapY));

                        if (useCustomMaterial) {
                            for (int y = roadY; y <= stepTopY; y++) {
                                BlockPos bp = new BlockPos(cand.bx(), y, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    blockMap.put(bp, pickSlopeMaterialBlock(slopeMaterial, cand.bx(), y, cand.bz()));
                                }
                            }
                        } else {
                            BlockPos groundPos = new BlockPos(cand.bx(), groundY, cand.bz());
                            BlockState worldGroundTop = level.getBlockState(groundPos);
                            BlockState surfaceTop = resolveWorldSlopeSurface(worldGroundTop, grassState);

                            if (stepTopY < groundY || !isNaturalTerrainSolid(worldGroundTop)) {
                                BlockPos topPos = new BlockPos(cand.bx(), stepTopY, cand.bz());
                                if (!otherActiveRoadBlocks.contains(topPos)) {
                                    blockMap.put(topPos, surfaceTop);
                                }
                            }
                            for (int y = stepTopY - 1; y >= roadY; y--) {
                                BlockPos bp = new BlockPos(cand.bx(), y, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    BlockState existing = level.getBlockState(bp);
                                    if (!isNaturalTerrainSolid(existing)) {
                                        blockMap.put(bp, dirtState);
                                    }
                                }
                            }
                        }

                        // Under the road level down to terrain if there is any gap
                        if (roadY - 1 >= groundY) {
                            for (int fy = roadY - 1; fy >= groundY; fy--) {
                                BlockPos bp = new BlockPos(cand.bx(), fy, cand.bz());
                                if (!otherActiveRoadBlocks.contains(bp)) {
                                    blockMap.put(bp, dirtState);
                                }
                            }
                        }

                        // Air clearing above the step up to the sky (removes all excavated hillside rock/dirt)
                        int worldSurfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, cand.bx(), cand.bz());
                        int clearTopY = Math.max(groundY + 4, worldSurfaceY + 2);
                        for (int ay = stepTopY + 1; ay <= clearTopY; ay++) {
                            BlockPos ap = new BlockPos(cand.bx(), ay, cand.bz());
                            if (!otherActiveRoadBlocks.contains(ap)) {
                                blockMap.put(ap, airState);
                            }
                        }
                        continue;
                    }
                }

                // 2. Side Embankment (stepped 2-block earth, stepped 1-block crushed stone, etc.; extends underwater down to seabed)
                if (!embankmentEnabled || embankmentShape == ModularRoadPreset.EmbankmentShape.WALL) {
                    continue;
                }
                BlockPos groundPos = new BlockPos(cand.bx(), groundY, cand.bz());
                boolean isWater = isWaterAt(level, groundPos) || isWaterAt(level, groundPos.above());
                int solidBedY = isWater
                        ? solidBedYMap.computeIfAbsent(colKey, k -> getSolidBedY(level, cand.bx(), cand.bz()))
                        : groundY;

                if (roadY - solidBedY < 2 || (viaductEnabled && roadY - groundY >= 6)) {
                    continue;
                }

                // Profile top height (STEPPED 2-block shelf vs STEPPED_ONE 1-block shelf) with smooth viaduct taper
                int topY = computeEmbankmentTopY(embankmentShape, roadY, dz, distToViaduct[stIdx]);

                if (topY >= solidBedY) {
                    boolean topIsDry = !isWater || topY > groundY;
                    BlockPos topPos = new BlockPos(cand.bx(), topY, cand.bz());
                    if (!otherActiveRoadBlocks.contains(topPos)) {
                        blockMap.put(topPos, pickEmbankmentBlock(embankmentMaterial, activePack, cand.bx(), topY, cand.bz(), topIsDry));
                    }
                    for (int fy = topY - 1; fy >= solidBedY; fy--) {
                        BlockPos fPos = new BlockPos(cand.bx(), fy, cand.bz());
                        if (!otherActiveRoadBlocks.contains(fPos)) {
                            blockMap.put(fPos, pickEmbankmentBlock(embankmentMaterial, activePack, cand.bx(), fy, cand.bz(), false));
                        }
                    }
                }
            }
        }

        // 8. Generate junction & roundabout platforms if connecting to JUNCTION / ROUNDABOUT nodes
        Set<Long> junctionColumnKeys = new HashSet<>();
        if (graph != null) {
            if (junctionAtP0 != null) {
                JunctionGenerator.JunctionStyle style0 = JunctionGenerator.JunctionStyle.fromString(junctionAtP0.getJunctionStyle());
                Map<BlockPos, BlockState> j0Blocks = (style0 == JunctionGenerator.JunctionStyle.ROUNDABOUT || junctionAtP0.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT)
                        ? RoundaboutGenerator.generateRoundabout(level, junctionAtP0, graph.getEdgesForNode(junctionAtP0.getId()), graph)
                        : JunctionGenerator.generateJunction(level, junctionAtP0, graph.getEdgesForNode(junctionAtP0.getId()), graph, style0);
                blockMap.putAll(j0Blocks);
                collectNonAirColumns(j0Blocks, junctionColumnKeys);
            }
            if (junctionAtP3 != null) {
                JunctionGenerator.JunctionStyle style3 = JunctionGenerator.JunctionStyle.fromString(junctionAtP3.getJunctionStyle());
                Map<BlockPos, BlockState> j3Blocks = (style3 == JunctionGenerator.JunctionStyle.ROUNDABOUT || junctionAtP3.getType() == com.roadbuilder.mod.domain.graph.NodeType.ROUNDABOUT)
                        ? RoundaboutGenerator.generateRoundabout(level, junctionAtP3, graph.getEdgesForNode(junctionAtP3.getId()), graph)
                        : JunctionGenerator.generateJunction(level, junctionAtP3, graph.getEdgesForNode(junctionAtP3.getId()), graph, style3);
                blockMap.putAll(j3Blocks);
                collectNonAirColumns(j3Blocks, junctionColumnKeys);
            }
            if (roundaboutAtP0 != null && junctionAtP0 == null) {
                Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                        level, roundaboutAtP0, graph.getEdgesForNode(roundaboutAtP0.getId()), graph
                );
                blockMap.putAll(rbBlocks);
                collectNonAirColumns(rbBlocks, junctionColumnKeys);
            }
            if (roundaboutAtP3 != null && junctionAtP3 == null) {
                Map<BlockPos, BlockState> rbBlocks = RoundaboutGenerator.generateRoundabout(
                        level, roundaboutAtP3, graph.getEdgesForNode(roundaboutAtP3.getId()), graph
                );
                blockMap.putAll(rbBlocks);
                collectNonAirColumns(rbBlocks, junctionColumnKeys);
            }
        }

        // 9. Generate street lamps along sidewalks and grass dividers (skipping tunnels, junctions, and overhead conflicts)
        placeStreetLamps(
                blockMap, curve, preset, finalCells,
                totalCurveLen, totalStations, tunnelStations,
                transitionAtP0, transitionAtP3,
                junctionAtP0, radiusP0, junctionAtP3, radiusP3,
                roundaboutAtP0, rRadiusP0, roundaboutAtP3, rRadiusP3,
                p0Connected, p3Connected,
                otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
        );

        // 10. Generate 2nd-layer guardrails & optional outer sidewalk noise barriers
        placeGuardrailsAndNoiseWalls(
                blockMap, curve, preset, finalCells,
                totalCurveLen, totalStations, tunnelStations,
                transitionAtP0, transitionAtP3,
                junctionAtP0, radiusP0, junctionAtP3, radiusP3,
                roundaboutAtP0, rRadiusP0, roundaboutAtP3, rRadiusP3,
                otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
        );

        // 11. Generate 2nd-layer greenery (trees & bushes) along grass dividers, lawns, sidewalks, and shoulders
        placeGreenery(
                blockMap, curve, preset, finalCells,
                totalCurveLen, totalStations, tunnelStations,
                transitionAtP0, transitionAtP3,
                junctionAtP0, radiusP0, junctionAtP3, radiusP3,
                roundaboutAtP0, rRadiusP0, roundaboutAtP3, rRadiusP3,
                otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
        );

        return blockMap;
    }

    private static boolean isColumnOnOtherRoadDeck(Map<Long, List<Cell>> otherRoadCells, long colKey, int roadY) {
        if (otherRoadCells == null || otherRoadCells.isEmpty()) return false;
        List<Cell> others = otherRoadCells.get(colKey);
        if (others == null || others.isEmpty()) return false;
        for (Cell c : others) {
            if (Math.abs(c.y() - roadY) <= 4) {
                return true;
            }
        }
        return false;
    }

    private static int sampleStationCutDepth(
            Level level,
            Map<Long, Integer> groundYMap,
            Vector3d pt,
            Vector3d norm,
            double halfRoadW,
            int sideSign,
            int sy
    ) {
        double[] offsets = {1.5, 3.0, 5.0, 7.5, 10.0, 13.0};
        int[] thresholds = {3, 3, 4, 6, 8, 10};
        int maxCut = 0;
        for (int i = 0; i < offsets.length; i++) {
            double dist = halfRoadW + offsets[i];
            int sx = (int) Math.round(pt.x + norm.x * sideSign * dist);
            int sz = (int) Math.round(pt.z + norm.z * sideSign * dist);
            int gY = groundYMap.computeIfAbsent(coordKey(sx, sz), k -> getTrueGroundY(level, sx, sz));
            int rise = gY - sy;
            if (rise >= thresholds[i] && rise > maxCut) {
                maxCut = rise;
            }
        }
        return (maxCut >= 3) ? maxCut : 0;
    }

    private static int[] smoothStationCutDepths(int[] rawCut) {
        int n = rawCut.length;
        int[] cleaned = new int[n];
        for (int s = 0; s < n; s++) {
            int prev = (s > 0) ? rawCut[s - 1] : 0;
            int next = (s < n - 1) ? rawCut[s + 1] : 0;
            cleaned[s] = (rawCut[s] > 0 && prev == 0 && next == 0) ? 0 : rawCut[s];
        }

        int[] smooth = new int[n];
        for (int s = 0; s < n; s++) {
            boolean hasLeft = (s >= 1 && cleaned[s - 1] > 0) || (s >= 2 && cleaned[s - 2] > 0);
            boolean hasRight = (s + 1 < n && cleaned[s + 1] > 0) || (s + 2 < n && cleaned[s + 2] > 0);
            if (cleaned[s] == 0 && !(hasLeft && hasRight)) {
                smooth[s] = 0;
                continue;
            }
            int sum = 0;
            int count = 0;
            for (int d = -2; d <= 2; d++) {
                int idx = s + d;
                if (idx >= 0 && idx < n && cleaned[idx] > 0) {
                    sum += cleaned[idx];
                    count++;
                }
            }
            int val = (count > 0) ? (int) Math.round((double) sum / count) : 0;
            smooth[s] = (val >= 3) ? val : 0;
        }

        for (int s = 1; s < n; s++) {
            if (smooth[s] > 0 && smooth[s - 1] > 0 && smooth[s] > smooth[s - 1] + 1) {
                smooth[s] = smooth[s - 1] + 1;
            }
        }
        for (int s = n - 2; s >= 0; s--) {
            if (smooth[s] > 0 && smooth[s + 1] > 0 && smooth[s] > smooth[s + 1] + 1) {
                smooth[s] = smooth[s + 1] + 1;
            }
        }
        return smooth;
    }

    private static boolean isWaterAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || state.is(Blocks.WATER) || !level.getFluidState(pos).isEmpty()) {
            return true;
        }
        if (state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE)) {
            BlockPos below = pos.below();
            return !level.getFluidState(below).isEmpty() || level.getBlockState(below).is(Blocks.WATER);
        }
        return false;
    }

    private static boolean doesConnectedEndpointTriggerViaduct(
            Level level,
            Map<Long, Integer> groundYMap,
            ModularRoadPreset otherPreset,
            Vector3d connPt
    ) {
        if (otherPreset.getViaductMode() == ModularRoadPreset.Mode.OFF) {
            return false;
        }
        int cx = (int) Math.round(connPt.x);
        int cz = (int) Math.round(connPt.z);
        int gY = groundYMap.computeIfAbsent(coordKey(cx, cz), k -> getTrueGroundY(level, cx, cz));
        int oceanFloorY = level.getHeight(Heightmap.Types.OCEAN_FLOOR, cx, cz);
        boolean overWater = gY > oceanFloorY || !level.getFluidState(new BlockPos(cx, gY, cz)).isEmpty();
        int sy = (int) Math.round(connPt.y);
        boolean otherEmbEnabled = otherPreset.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF;
        boolean autoOverWaterViaduct = overWater && (!otherEmbEnabled || (sy - gY) >= 4);
        boolean isElevated = (sy - gY) >= 6;
        return otherPreset.getViaductMode() == ModularRoadPreset.Mode.ALWAYS || autoOverWaterViaduct || isElevated;
    }

    private static boolean isThinSuspendedDeck(Level level, BlockPos pos) {
        BlockState top = level.getBlockState(pos);
        if (top.is(Blocks.STONE) || top.is(Blocks.DEEPSLATE) || top.is(Blocks.GRANITE)
                || top.is(Blocks.DIORITE) || top.is(Blocks.ANDESITE) || top.is(Blocks.TERRACOTTA)) {
            return false;
        }
        int y = pos.getY();
        int minY = level.getMinBuildHeight();
        for (int dy = 1; dy <= 3; dy++) {
            if (y - dy - 1 < minY) {
                return false;
            }
            BlockState below = level.getBlockState(pos.below(dy));
            if (below.isAir() || !below.blocksMotion()) {
                BlockState belowNext = level.getBlockState(pos.below(dy + 1));
                return belowNext.isAir() || !belowNext.blocksMotion();
            }
        }
        return false;
    }

    public static boolean isRoadBlock(Level level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            try {
                com.roadbuilder.mod.server.storage.RoadGraphSavedData savedData = com.roadbuilder.mod.server.storage.RoadGraphSavedData.get(serverLevel);
                if (savedData != null && savedData.isRoadBlock(pos)) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return state.is(Blocks.GRAY_CONCRETE_POWDER)
                || state.is(Blocks.GRAY_CONCRETE)
                || state.is(Blocks.BLACK_CONCRETE)
                || state.is(Blocks.CYAN_TERRACOTTA)
                || state.is(Blocks.SMOOTH_QUARTZ)
                || state.is(Blocks.SMOOTH_QUARTZ_STAIRS)
                || state.is(Blocks.SMOOTH_QUARTZ_SLAB)
                || state.is(Blocks.POLISHED_ANDESITE_STAIRS)
                || state.is(Blocks.POLISHED_ANDESITE_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.BRICK_STAIRS)
                || state.is(Blocks.GRANITE_STAIRS)
                || state.is(Blocks.MUD_BRICK_STAIRS)
                || state.is(Blocks.DIORITE_STAIRS)
                || state.is(Blocks.POLISHED_DIORITE_STAIRS);
    }

    private static boolean isNonGroundFoliageOrPlant(BlockState state) {
        return state.is(BlockTags.FLOWERS) ||
                state.is(BlockTags.REPLACEABLE) ||
                state.is(BlockTags.CROPS) ||
                state.is(BlockTags.SAPLINGS) ||
                state.is(BlockTags.BEE_GROWABLES) ||
                state.is(Blocks.MOSS_CARPET) ||
                state.getBlock() instanceof BushBlock ||
                state.getBlock() instanceof DoublePlantBlock ||
                state.getBlock() instanceof CarpetBlock ||
                state.getBlock() instanceof SnowLayerBlock ||
                state.getBlock() instanceof VineBlock ||
                state.getBlock() instanceof SugarCaneBlock ||
                state.getBlock() instanceof CactusBlock ||
                state.getBlock() instanceof BambooStalkBlock ||
                state.getBlock() instanceof AzaleaBlock ||
                !state.blocksMotion();
    }

    public static int getTrueGroundY(Level level, int x, int z) {
        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);

        for (int y = startY; y >= level.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) continue;

            // Water surface counts as surface ground level
            if (!state.getFluidState().isEmpty() || state.is(Blocks.WATER)) {
                return y;
            }

            // Skip existing road and bridge structures so elevated decks/pillars are never treated as natural terrain
            if (isRoadBlock(level, pos, state)) {
                continue;
            }

            // Skip leaves and tree foliage
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                continue;
            }

            // Skip only genuine thin suspended decks/canopies (<= 4 blocks thick over >= 2 air blocks), never real hillside terrain
            if (isThinSuspendedDeck(level, pos)) {
                continue;
            }

            // Skip tree logs
            if (state.is(BlockTags.LOGS) || state.getBlock() instanceof RotatedPillarBlock) {
                continue;
            }

            if (isNonGroundFoliageOrPlant(state)) {
                continue;
            }

            // True solid ground (grass block, dirt, stone, sand, gravel, terracotta, etc.)
            return y;
        }
        return level.getMinBuildHeight();
    }

    public static int getSolidBedY(Level level, int x, int z) {
        int startY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);

        for (int y = startY; y >= level.getMinBuildHeight(); y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);

            if (state.isAir()) continue;

            // Skip water, waterlogged plants (kelp, seagrass, sea pickle), and surface ice down to the solid bed
            if (!state.getFluidState().isEmpty()
                    || state.is(Blocks.WATER)
                    || !level.getFluidState(pos).isEmpty()
                    || state.is(Blocks.ICE)
                    || state.is(Blocks.FROSTED_ICE)) {
                continue;
            }

            if (isRoadBlock(level, pos, state)) {
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock) {
                continue;
            }
            if (isThinSuspendedDeck(level, pos)) {
                continue;
            }
            if (state.is(BlockTags.LOGS) || state.getBlock() instanceof RotatedPillarBlock) {
                continue;
            }
            if (isNonGroundFoliageOrPlant(state)) {
                continue;
            }

            return y;
        }
        return level.getMinBuildHeight();
    }

    public static Map<BlockPos, BlockState> calculateRoadBlocks(ServerLevel level, BezierCurve3D curve, RoadProfile profile) {
        ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
        return calculateRoadBlocks(level, curve, preset);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset) {
        return planCurveCells(curve, preset, null, null, null, null);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset,
                                            TransitionProfile transitionAtP0, TransitionProfile transitionAtP3) {
        return planCurveCells(curve, preset, transitionAtP0, transitionAtP3, null, null);
    }

    public static List<Cell> planCurveCells(BezierCurve3D curve, ModularRoadPreset preset,
                                            TransitionProfile transitionAtP0, TransitionProfile transitionAtP3,
                                            Vector3d startCutNormal, Vector3d endCutNormal) {
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
        double maxHalfW = halfWidth;
        if (transitionAtP0 != null) maxHalfW = Math.max(maxHalfW, transitionAtP0.getStackAt(0.0).totalWidth() * 0.5);
        if (transitionAtP3 != null) maxHalfW = Math.max(maxHalfW, transitionAtP3.getStackAt(1.0).totalWidth() * 0.5);
        int r = (int) Math.ceil(maxHalfW * 1.5) + 3;
        int x0 = (int) Math.floor(minX) - r, x1 = (int) Math.ceil(maxX) + r;
        int z0 = (int) Math.floor(minZ) - r, z1 = (int) Math.ceil(maxZ) + r;

        Vector3d p0 = curve.getP0();
        Vector3d p3 = curve.getP3();
        Vector3d tan0 = curve.evaluateTangent(0.0);
        Vector3d tan3 = curve.evaluateTangent(1.0);

        double t0x, t0z;
        if (startCutNormal != null && (startCutNormal.x != 0 || startCutNormal.z != 0)) {
            double len = Math.hypot(startCutNormal.x, startCutNormal.z);
            t0x = startCutNormal.x / len;
            t0z = startCutNormal.z / len;
        } else {
            double t0Len = Math.hypot(tan0.x, tan0.z);
            t0x = t0Len > 1e-6 ? tan0.x / t0Len : 0.0;
            t0z = t0Len > 1e-6 ? tan0.z / t0Len : 1.0;
        }

        double t3x, t3z;
        if (endCutNormal != null && (endCutNormal.x != 0 || endCutNormal.z != 0)) {
            double len = Math.hypot(endCutNormal.x, endCutNormal.z);
            t3x = endCutNormal.x / len;
            t3z = endCutNormal.z / len;
        } else {
            double t3Len = Math.hypot(tan3.x, tan3.z);
            t3x = t3Len > 1e-6 ? tan3.x / t3Len : 0.0;
            t3z = t3Len > 1e-6 ? tan3.z / t3Len : 1.0;
        }

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                // Evaluate at world block centers (+0.5, +0.5) to align identically with nodes, junctions, and roundabouts
                double cx = x + 0.5;
                double cz = z + 0.5;

                // Strict half-space bounding planes perpendicular to road centerline or joint miter at curve start and end
                double startProj = (cx - p0.x) * t0x + (cz - p0.z) * t0z;
                double endProj = (cx - p3.x) * t3x + (cz - p3.z) * t3z;
                if (startProj < -0.01 || endProj > 0.01) {
                    continue;
                }

                double bestD2 = Double.MAX_VALUE, bestRaw = 0, bestU = 0;
                int best = -1;
                for (int i = 0; i < segs; i++) {
                    double ax = px[i], az = pz[i];
                    double vx = px[i + 1] - ax, vz = pz[i + 1] - az;
                    double len2 = vx * vx + vz * vz;
                    if (len2 < 1e-12) continue;
                    double wx = cx - ax, wz = cz - az;
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

                if (best < 0) continue;

                double vx = px[best + 1] - px[best];
                double vz = pz[best + 1] - pz[best];
                double segLen = Math.hypot(vx, vz);
                if (segLen < 1e-6) continue;
                double ux = vx / segLen, uz = vz / segLen;
                double nx = -uz, nz = ux;

                double s;
                double t;
                int cellY;
                double latDist2;

                if (best == 0 && bestRaw < 0.0) {
                    double wx = cx - p0.x, wz = cz - p0.z;
                    double tanLen = Math.hypot(tan0.x, tan0.z);
                    double dirX = tanLen > 1e-6 ? tan0.x / tanLen : ux;
                    double dirZ = tanLen > 1e-6 ? tan0.z / tanLen : uz;
                    double nX = -dirZ, nZ = dirX;
                    double along = wx * dirX + wz * dirZ;
                    s = wx * nX + wz * nZ;
                    latDist2 = s * s;
                    t = along;
                    cellY = (int) Math.round(py[0]);
                    nx = nX;
                    nz = nZ;
                } else if (best == segs - 1 && bestRaw > 1.0) {
                    double wx = cx - p3.x, wz = cz - p3.z;
                    double tanLen = Math.hypot(tan3.x, tan3.z);
                    double dirX = tanLen > 1e-6 ? tan3.x / tanLen : ux;
                    double dirZ = tanLen > 1e-6 ? tan3.z / tanLen : uz;
                    double nX = -dirZ, nZ = dirX;
                    double along = wx * dirX + wz * dirZ;
                    s = wx * nX + wz * nZ;
                    latDist2 = s * s;
                    t = totalLen + along;
                    cellY = (int) Math.round(py[segs]);
                    nx = nX;
                    nz = nZ;
                } else {
                    double wx = cx - px[best], wz = cz - pz[best];
                    double cross = ux * wz - uz * wx;
                    s = Math.sqrt(bestD2) * (cross >= 0 ? 1 : -1);
                    latDist2 = bestD2;
                    t = cum[best] + bestU * segLen;
                    cellY = (int) Math.round(py[best] + bestU * (py[best + 1] - py[best]));
                }

                if (latDist2 > (maxHalfW + 0.5) * (maxHalfW + 0.5)) continue;

                // Physical Euclidean lateral distance from road centerline (meters)
                double sGrid = s;

                double currentHalfWidth = halfWidth;
                TransitionProfile.InterpolatedStack interpStack = null;
                if (transitionAtP0 != null) {
                    double transLen = Math.min(totalLen, transitionAtP0.getTransitionLength());
                    if (t <= transLen) {
                        double factor = Math.max(0.0, Math.min(1.0, t / Math.max(0.1, transLen)));
                        interpStack = transitionAtP0.getStackAt(factor);
                        currentHalfWidth = interpStack.totalWidth() * 0.5;
                    }
                } else if (transitionAtP3 != null) {
                    double transLen = Math.min(totalLen, transitionAtP3.getTransitionLength());
                    if (t >= totalLen - transLen) {
                        double distFromEnd = Math.max(0.0, totalLen - t);
                        double factor = Math.max(0.0, Math.min(1.0, 1.0 - (distFromEnd / Math.max(0.1, transLen))));
                        interpStack = transitionAtP3.getStackAt(factor);
                        currentHalfWidth = interpStack.totalWidth() * 0.5;
                    }
                }

                if (sGrid < -currentHalfWidth - 0.2 || sGrid >= currentHalfWidth + 0.2) continue;

                RoadElementType type;
                int elemW;
                int localX;
                if (interpStack != null) {
                    TransitionProfile.InterpolatedElement interpElem = interpStack.getElementAt(sGrid);
                    type = interpElem.type();
                    elemW = (int) Math.max(1, Math.round(interpElem.width()));
                    localX = (int) Math.floor(sGrid - interpElem.startOffset());
                    localX = Math.max(0, Math.min(elemW - 1, localX));
                } else {
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
                    type = elem.getType();
                    elemW = elem.getWidth();
                    localX = (int) Math.floor(sGrid - elemStart[elemIdx] + 1e-4);
                    localX = Math.max(0, Math.min(elemW - 1, localX));
                }

                boolean isLeftSide = (sGrid < 0);
                Direction facingOutward = facingFromVector(nx * (isLeftSide ? -1 : 1), nz * (isLeftSide ? -1 : 1));

                int roadLocalX = (int) Math.floor(sGrid + currentHalfWidth + 1e-4);
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
                        // Detect divider nose cap at transition station (matching Road_perehod_template.litematic)
                        double trans0 = (transitionAtP0 != null) ? Math.min(totalLen, transitionAtP0.getTransitionLength()) : 0.0;
                        double trans3 = (transitionAtP3 != null) ? Math.min(totalLen, transitionAtP3.getTransitionLength()) : 0.0;
                        boolean isNoseAtP0 = transitionAtP0 != null && Math.abs(t - (0.85 * trans0)) <= 0.85;
                        boolean isNoseAtP3 = transitionAtP3 != null && Math.abs((totalLen - t) - (0.85 * trans3)) <= 0.85;
                        if (isNoseAtP0) {
                            Vector3d tan = curve.evaluateTangent(Math.max(0.0, Math.min(1.0, t / Math.max(1.0, totalLen))));
                            if (localX == 0) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx - tan.x, nz - tan.z), null, roadLocalX, t, nx, nz));
                            } else if (localX == elemW - 1) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx - tan.x, -nz - tan.z), null, roadLocalX, t, nx, nz));
                            } else {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-tan.x, -tan.z), null, roadLocalX, t, nx, nz));
                            }
                        } else if (isNoseAtP3) {
                            Vector3d tan = curve.evaluateTangent(Math.max(0.0, Math.min(1.0, t / Math.max(1.0, totalLen))));
                            if (localX == 0) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx + tan.x, nz + tan.z), null, roadLocalX, t, nx, nz));
                            } else if (localX == elemW - 1) {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx + tan.x, -nz + tan.z), null, roadLocalX, t, nx, nz));
                            } else {
                                out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(tan.x, tan.z), null, roadLocalX, t, nx, nz));
                            }
                        } else if (localX == 0) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(nx, nz), null, roadLocalX, t, nx, nz));
                        } else if (localX == elemW - 1) {
                            out.add(new Cell(x, z, cellY, Kind.CURB, facingFromVector(-nx, -nz), null, roadLocalX, t, nx, nz));
                        } else {
                            out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                        }
                    }
                    case LAWN -> {
                        out.add(new Cell(x, z, cellY, Kind.GRASS, Direction.NORTH, null, roadLocalX, t, nx, nz));
                    }
                    case MARKING_SOLID -> {
                        BlockState solidQuartz = Blocks.SMOOTH_QUARTZ.defaultBlockState();
                        out.add(new Cell(x, z, cellY, Kind.LINE, facingFromVector(nx, nz), solidQuartz, roadLocalX, t, nx, nz));
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

    private static double getDistanceToRoadDeck(int bx, int bz, Set<Long> roadDeckColumns) {
        double minD2 = Double.MAX_VALUE;
        for (int r = 1; r <= 15; r++) {
            boolean found = false;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r || Math.abs(dz) == r) {
                        if (roadDeckColumns.contains(coordKey(bx + dx, bz + dz))) {
                            double d2 = dx * dx + dz * dz;
                            if (d2 < minD2) {
                                minD2 = d2;
                            }
                            found = true;
                        }
                    }
                }
            }
            if (found && minD2 <= r * r) {
                break;
            }
            if (found && r >= (int) Math.ceil(Math.sqrt(minD2))) {
                break;
            }
        }
        return minD2 < Double.MAX_VALUE ? Math.sqrt(minD2) : Double.MAX_VALUE;
    }

    private static int rank(Kind k) {
        return switch (k) {
            case GRASS -> 0;
            case STONE -> 1;
            case SIDEWALK -> 2;
            case CURB -> 3;
            case LINE -> 4;
            case ASPHALT -> 5;
        };
    }

    private static Cell resolveWinner(Cell a, Cell b) {
        return rank(a.kind()) >= rank(b.kind()) ? a : b;
    }

    private static void placePillarSection(Level level, Map<BlockPos, BlockState> blockMap, BlockPos surfacePos,
                                           Cell cell, int px, int pillarCycle, BlockState curbBlock, BlockState foundationState) {
        if (cell.localX() < px || cell.localX() > px + 4) {
            return;
        }
        // Capital at below(4): 5x5 with Half.TOP inward-facing stairs around 3x3 checkered core (Road_estacada_opora_new)
        if (cell.localX() == px && pillarCycle >= 1 && pillarCycle <= 3) {
            Direction stairFacing = facingFromVector(cell.nx(), cell.nz());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (cell.localX() == px + 4 && pillarCycle >= 1 && pillarCycle <= 3) {
            Direction stairFacing = facingFromVector(-cell.nx(), -cell.nz());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (pillarCycle == 0 && cell.localX() >= px + 1 && cell.localX() <= px + 3) {
            Direction stairFacing = facingFromVector(cell.nz(), -cell.nx());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (pillarCycle == 4 && cell.localX() >= px + 1 && cell.localX() <= px + 3) {
            Direction stairFacing = facingFromVector(-cell.nz(), cell.nx());
            blockMap.put(surfacePos.below(4), orientStairs(curbBlock, stairFacing, Half.TOP));
        } else if (cell.localX() >= px + 1 && cell.localX() <= px + 3 && pillarCycle >= 1 && pillarCycle <= 3) {
            int relX = cell.localX() - (px + 1);
            int relT = pillarCycle - 1;
            boolean isAndesite = ((relX + relT) % 2 == 1);
            blockMap.put(surfacePos.below(4), isAndesite ? Blocks.POLISHED_ANDESITE.defaultBlockState() : foundationState);
        }

        // 3x3 checkered pillar column extending down to ground
        if (cell.localX() >= px + 1 && cell.localX() <= px + 3 && pillarCycle >= 1 && pillarCycle <= 3) {
            int relX = cell.localX() - (px + 1);
            int relT = pillarCycle - 1;
            boolean isAndesite = ((relX + relT) % 2 == 1);
            BlockState colBlock = isAndesite ? Blocks.POLISHED_ANDESITE.defaultBlockState() : foundationState;
            int trueGroundY = getTrueGroundY(level, cell.x(), cell.z());
            int stopY = Math.max(level.getMinBuildHeight(), trueGroundY);
            for (int py = cell.y() - 5; py >= stopY; py--) {
                blockMap.put(new BlockPos(cell.x(), py, cell.z()), colBlock);
            }
        }
    }

    /**
     * Populates tunnel walls, ceiling, ribs, and embedded lighting for a single road column
     * matching Road_tunel_profile_circle.litematic (CIRCLE) or Road_tunel_profile_flat.litematic (FLAT).
     */
    public static void populateTunnelColumn(
            Map<BlockPos, BlockState> blockMap,
            Set<BlockPos> protectedBlocks,
            BlockPos surfacePos,
            ModularRoadPreset.TunnelShape shape,
            int localX,
            int totalWidth,
            int stationT,
            double nx,
            double nz,
            boolean addOuterSeal,
            boolean clearInteriorAir
    ) {
        BlockState stoneState = Blocks.STONE.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();
        boolean isSideWall = (localX == 0 || localX == totalWidth - 1);

        if (shape == ModularRoadPreset.TunnelShape.FLAT) {
            if (isSideWall) {
                for (int h = 1; h <= 7; h++) {
                    blockMap.put(surfacePos.above(h), stoneState);
                }
                if (addOuterSeal) {
                    blockMap.put(surfacePos.above(8), stoneState);
                }
                return;
            }

            int cycle = Math.floorMod(stationT, 8);
            boolean isRib = (cycle == 4 || cycle == 6);
            boolean isLightBay = (cycle == 5);
            int clearH = (isRib || isLightBay) ? 5 : 6;

            if (clearInteriorAir) {
                for (int h = 1; h <= clearH; h++) {
                    BlockPos ap = surfacePos.above(h);
                    if (protectedBlocks == null || !protectedBlocks.contains(ap)) {
                        blockMap.put(ap, airState);
                    }
                }
            }

            if (isRib) {
                blockMap.put(surfacePos.above(6), stoneState);
            } else if (isLightBay) {
                blockMap.put(surfacePos.above(6), computeFlatCeilingFeature(localX, totalWidth, nx, nz));
            }
            blockMap.put(surfacePos.above(7), stoneState);
            if (addOuterSeal) {
                blockMap.put(surfacePos.above(8), stoneState);
            }
            return;
        }

        // TunnelShape.CIRCLE (4-block cycle matching Road_tunel_profile_circle.litematic)
        if (isSideWall) {
            for (int h = 1; h <= 4; h++) {
                blockMap.put(surfacePos.above(h), stoneState);
            }
            if (addOuterSeal) {
                blockMap.put(surfacePos.above(5), stoneState);
            }
            return;
        }

        double halfW = Math.max(1.0, (totalWidth - 1) / 2.0);
        double u = Math.min(1.0, Math.abs(localX - halfW) / halfW);

        int clearH;
        int roofTopH;
        if (u <= 0.35) {
            clearH = 8;
            roofTopH = 9;
        } else if (u <= 0.65) {
            clearH = 7;
            roofTopH = 8;
        } else if (u <= 0.78) {
            clearH = 6;
            roofTopH = 7;
        } else {
            clearH = 4;
            roofTopH = 6; // 2-block side curve thickness (h=5..6) matching litematic
        }

        if (clearInteriorAir) {
            for (int h = 1; h <= clearH; h++) {
                BlockPos ap = surfacePos.above(h);
                if (protectedBlocks == null || !protectedBlocks.contains(ap)) {
                    blockMap.put(ap, airState);
                }
            }
        }

        for (int h = clearH + 1; h <= roofTopH; h++) {
            BlockState ceilingBlock = stoneState;
            if (h == 9 && clearH == 8 && Math.floorMod(stationT, 4) == 0 && Math.abs(localX - halfW) <= 0.51) {
                ceilingBlock = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
            }
            blockMap.put(surfacePos.above(h), ceilingBlock);
        }

        if (addOuterSeal) {
            blockMap.put(surfacePos.above(roofTopH + 1), stoneState);
        }
    }

    private static BlockState computeFlatCeilingFeature(int localX, int totalWidth, double nx, double nz) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState light = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
        BlockState stairBase = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();

        if (totalWidth >= 13) {
            int leftBayStart = (totalWidth - 1) / 2 - 4;
            int rightBayStart = totalWidth - 1 - leftBayStart - 3;
            BlockState inLeft = matchLightBaySlot(localX, leftBayStart, stairBase, light, nx, nz);
            if (inLeft != null) return inLeft;
            BlockState inRight = matchLightBaySlot(localX, rightBayStart, stairBase, light, nx, nz);
            if (inRight != null) return inRight;
            return stone;
        }

        if (totalWidth >= 7) {
            int bayStart = (totalWidth - 4) / 2;
            BlockState inBay = matchLightBaySlot(localX, bayStart, stairBase, light, nx, nz);
            return inBay != null ? inBay : stone;
        }

        double halfW = (totalWidth - 1) / 2.0;
        return Math.abs(localX - halfW) <= 0.6 ? light : stone;
    }

    private static BlockState matchLightBaySlot(
            int localX, int bayStart, BlockState stairBase, BlockState light, double nx, double nz) {
        if (localX == bayStart) {
            return orientStairs(stairBase, facingFromVector(-nx, -nz), Half.TOP);
        }
        if (localX == bayStart + 1 || localX == bayStart + 2) {
            return light;
        }
        if (localX == bayStart + 3) {
            return orientStairs(stairBase, facingFromVector(nx, nz), Half.TOP);
        }
        return null;
    }

    public static Direction facingFromVector(double ox, double oz) {
        if (Math.abs(ox) > Math.abs(oz)) {
            return ox > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return oz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    public static BlockState orientStairs(BlockState state, Direction facing, Half half) {
        if (state.getBlock() instanceof StairBlock) {
            return state.setValue(StairBlock.FACING, facing)
                    .setValue(StairBlock.HALF, half)
                    .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        }
        return state;
    }

    /**
     * Resolves vanilla StairBlock corner shapes (outer/inner left/right) across a generated block map
     * so diagonal and curved curbs wrap seamlessly without gaps.
     */
    public static void resolveStairShapes(Map<BlockPos, BlockState> blocks) {
        Map<BlockPos, BlockState> updated = new HashMap<>();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (!(state.getBlock() instanceof StairBlock)) continue;

            Direction facing = state.getValue(StairBlock.FACING);
            Half half = state.getValue(StairBlock.HALF);
            StairsShape shape = StairsShape.STRAIGHT;

            BlockState front = blocks.get(pos.relative(facing));
            if (front != null && front.getBlock() instanceof StairBlock && front.getValue(StairBlock.HALF) == half) {
                Direction ff = front.getValue(StairBlock.FACING);
                if (ff.getAxis() != facing.getAxis() && isDifferentStair(blocks, pos.relative(ff.getOpposite()), facing, half)) {
                    shape = (ff == facing.getCounterClockWise()) ? StairsShape.OUTER_LEFT : StairsShape.OUTER_RIGHT;
                }
            }
            if (shape == StairsShape.STRAIGHT) {
                BlockState back = blocks.get(pos.relative(facing.getOpposite()));
                if (back != null && back.getBlock() instanceof StairBlock && back.getValue(StairBlock.HALF) == half) {
                    Direction bf = back.getValue(StairBlock.FACING);
                    if (bf.getAxis() != facing.getAxis() && isDifferentStair(blocks, pos.relative(bf), facing, half)) {
                        shape = (bf == facing.getCounterClockWise()) ? StairsShape.INNER_LEFT : StairsShape.INNER_RIGHT;
                    }
                }
            }
            if (state.getValue(StairBlock.SHAPE) != shape) {
                updated.put(pos, state.setValue(StairBlock.SHAPE, shape));
            }
        }
        blocks.putAll(updated);
    }

    private static void collectNonAirColumns(Map<BlockPos, BlockState> blocks, Set<Long> outKeys) {
        if (blocks == null) return;
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (!entry.getValue().isAir()) {
                BlockPos p = entry.getKey();
                outKeys.add(coordKey(p.getX(), p.getZ()));
            }
        }
    }

    private static void placeStreetLamps(
            Map<BlockPos, BlockState> blockMap,
            BezierCurve3D curve,
            ModularRoadPreset preset,
            List<Cell> finalCells,
            double totalLen,
            int totalStations,
            boolean[] tunnelStations,
            TransitionProfile transitionAtP0,
            TransitionProfile transitionAtP3,
            RoadNode junctionAtP0,
            double radiusP0,
            RoadNode junctionAtP3,
            double radiusP3,
            RoadNode roundaboutAtP0,
            double rRadiusP0,
            RoadNode roundaboutAtP3,
            double rRadiusP3,
            boolean p0Connected,
            boolean p3Connected,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        if (preset == null || preset.getLampStyle() == ModularRoadPreset.LampStyle.OFF || totalLen < 4.0) {
            return;
        }
        StreetLampTemplate.LampPair lampPair = StreetLampTemplate.getForStyle(
                preset.getLampStyle(),
                RoadPackRegistry.getInstance().getActivePack()
        );
        if (lampPair == null) {
            return;
        }

        double spacing = Math.max(8, preset.getLampSpacing());
        double minT;
        if (roundaboutAtP0 != null) {
            minT = rRadiusP0 + 3.0;
        } else if (junctionAtP0 != null) {
            minT = radiusP0 + 3.0;
        } else if (p0Connected) {
            minT = Math.min(totalLen * 0.45, Math.max(4.0, spacing * 0.4));
        } else {
            minT = Math.min(totalLen * 0.45, 3.0);
        }

        double maxT;
        if (roundaboutAtP3 != null) {
            maxT = totalLen - (rRadiusP3 + 3.0);
        } else if (junctionAtP3 != null) {
            maxT = totalLen - (radiusP3 + 3.0);
        } else if (p3Connected) {
            maxT = totalLen - Math.min(totalLen * 0.45, Math.max(4.0, spacing * 0.4));
        } else {
            maxT = totalLen - Math.min(totalLen * 0.45, 3.0);
        }

        if (maxT - minT < 1.0) {
            return;
        }

        Map<Long, Cell> ownCellsMap = new HashMap<>(finalCells.size());
        for (Cell c : finalCells) {
            ownCellsMap.put(coordKey(c.x(), c.z()), c);
        }

        List<StreetLampTemplate.StackLampSlot> baseSlots = StreetLampTemplate.computeStackSlots(
                preset.getStack(), preset.getTotalWidth()
        );
        if (baseSlots.isEmpty()) {
            return;
        }

        ModularRoadPreset.LampPlacement placement = preset.getLampPlacement();
        boolean doubleOnSidewalk = preset.isSidewalkDoubleLamps();
        double transLen0 = (transitionAtP0 != null) ? Math.min(totalLen, transitionAtP0.getTransitionLength()) : 0.0;
        double transLen3 = (transitionAtP3 != null) ? Math.min(totalLen, transitionAtP3.getTransitionLength()) : 0.0;

        for (int pass = 0; pass < 2; pass++) {
            boolean isStaggeredPass = (pass == 1);
            if (isStaggeredPass && placement != ModularRoadPreset.LampPlacement.STAGGERED) {
                continue;
            }
            List<Double> stations = StreetLampTemplate.computeLampStations(minT, maxT, spacing, isStaggeredPass);
            for (double tDist : stations) {
                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(tDist)));
                boolean nearTunnel = false;
                for (int ds = -3; ds <= 3; ds++) {
                    int checkIdx = Math.max(0, Math.min(totalStations - 1, stIdx + ds));
                    if (tunnelStations[checkIdx]) {
                        nearTunnel = true;
                        break;
                    }
                }
                if (nearTunnel) {
                    continue;
                }

                List<StreetLampTemplate.StackLampSlot> slots = baseSlots;
                if (transitionAtP0 != null && tDist <= transLen0) {
                    double factor = Math.max(0.0, Math.min(1.0, tDist / Math.max(0.1, transLen0)));
                    slots = StreetLampTemplate.computeInterpolatedStackSlots(transitionAtP0.getStackAt(factor));
                } else if (transitionAtP3 != null && tDist >= totalLen - transLen3) {
                    double distFromEnd = Math.max(0.0, totalLen - tDist);
                    double factor = Math.max(0.0, Math.min(1.0, 1.0 - (distFromEnd / Math.max(0.1, transLen3))));
                    slots = StreetLampTemplate.computeInterpolatedStackSlots(transitionAtP3.getStackAt(factor));
                }

                double u = curve.getArcLengthTable().getTForDistance(tDist);
                Vector3d pt = curve.evaluate(u);
                Vector3d tan = curve.evaluateTangent(u);
                double tanLen = Math.hypot(tan.x, tan.z);
                if (tanLen < 1e-6) continue;
                double tx = tan.x / tanLen;
                double tz = tan.z / tanLen;
                double nx = -tz;
                double nz = tx;

                for (StreetLampTemplate.StackLampSlot slot : slots) {
                    if (slot.divider()) {
                        if (isStaggeredPass) continue;
                    } else {
                        if (placement == ModularRoadPreset.LampPlacement.DIVIDER_ONLY) continue;
                        if (placement == ModularRoadPreset.LampPlacement.STAGGERED) {
                            if (slot.rightSide() != isStaggeredPass) continue;
                        } else if (isStaggeredPass) {
                            continue;
                        }
                    }

                    double inX = nx * slot.inwardSign();
                    double inZ = nz * slot.inwardSign();
                    double wx = pt.x + nx * slot.anchorOffset();
                    double wz = pt.z + nz * slot.anchorOffset();
                    Cell anchorCell = resolveLampAnchorCell(ownCellsMap, wx, wz, inX, inZ, slot.divider(), slot.elemWidth());
                    if (anchorCell == null) {
                        continue;
                    }

                    BlockPos surfacePos = new BlockPos(anchorCell.x(), anchorCell.y(), anchorCell.z());
                    if (junctionColumnKeys != null && junctionColumnKeys.contains(coordKey(anchorCell.x(), anchorCell.z()))) {
                        continue;
                    }
                    BlockState placedSurface = blockMap.get(surfacePos);
                    if (placedSurface == null || placedSurface.isAir()) {
                        continue;
                    }
                    // Ensure the anchor wasn't converted into roadway asphalt/markings by an intersection/junction
                    if (placedSurface.is(Blocks.GRAY_CONCRETE_POWDER) || placedSurface.is(Blocks.SMOOTH_QUARTZ)
                            || placedSurface.is(Blocks.SMOOTH_QUARTZ_STAIRS) || otherActiveRoadBlocks.contains(surfacePos)) {
                        continue;
                    }

                    StreetLampTemplate.LampModel model = slot.divider()
                            ? lampPair.doubleSided()
                            : lampPair.forSidewalk(doubleOnSidewalk);
                    if (model == null) continue;

                    Map<BlockPos, BlockState> lampBlocks = model.sampleAt(
                            anchorCell.x() + 0.5, anchorCell.y(), anchorCell.z() + 0.5, inX, inZ
                    );

                    boolean conflict = false;
                    for (BlockPos lp : lampBlocks.keySet()) {
                        if (otherActiveRoadBlocks.contains(lp)) {
                            conflict = true;
                            break;
                        }
                        List<Cell> others = otherRoadCells.get(coordKey(lp.getX(), lp.getZ()));
                        if (others != null) {
                            boolean isPedestal = lp.getX() == anchorCell.x() && lp.getZ() == anchorCell.z();
                            for (Cell otherC : others) {
                                if (isPedestal) {
                                    if (otherC.y() >= anchorCell.y() - 1 && otherC.y() <= lp.getY() + 2) {
                                        conflict = true;
                                        break;
                                    }
                                } else if (otherC.y() >= anchorCell.y() + 1 && otherC.y() - 4 <= lp.getY()) {
                                    conflict = true;
                                    break;
                                }
                            }
                            if (conflict) break;
                        }
                    }
                    if (!conflict) {
                        blockMap.putAll(lampBlocks);
                    }
                }
            }
        }
    }

    private static Cell resolveLampAnchorCell(
            Map<Long, Cell> ownCellsMap,
            double wx,
            double wz,
            double inX,
            double inZ,
            boolean isDivider,
            double elemWidth
    ) {
        int bx = (int) Math.floor(wx);
        int bz = (int) Math.floor(wz);
        int stepOutX = (int) Math.round(-inX);
        int stepOutZ = (int) Math.round(-inZ);

        int[][] candidates = isDivider
                ? new int[][]{{0, 0}, {stepOutX, stepOutZ}, {-stepOutX, -stepOutZ}}
                : new int[][]{{0, 0}, {stepOutX, stepOutZ}};

        for (int[] off : candidates) {
            Cell c = ownCellsMap.get(coordKey(bx + off[0], bz + off[1]));
            if (c == null) continue;
            if (isDivider) {
                if (c.kind() == Kind.GRASS || (elemWidth <= 2.2 && c.kind() == Kind.CURB)) {
                    return c;
                }
            } else if (c.kind() == Kind.SIDEWALK) {
                return c;
            }
        }
        return null;
    }

    private record GuardrailTrackPoint(int bx, int by, int bz, double wx, double wz, double tx, double tz, double inX, double inZ) {}

    private static void placeGuardrailsAndNoiseWalls(
            Map<BlockPos, BlockState> blockMap,
            BezierCurve3D curve,
            ModularRoadPreset preset,
            List<Cell> finalCells,
            double totalLen,
            int totalStations,
            boolean[] tunnelStations,
            TransitionProfile transitionAtP0,
            TransitionProfile transitionAtP3,
            RoadNode junctionAtP0,
            double radiusP0,
            RoadNode junctionAtP3,
            double radiusP3,
            RoadNode roundaboutAtP0,
            double rRadiusP0,
            RoadNode roundaboutAtP3,
            double rRadiusP3,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        if (preset == null || totalLen < 1.0) return;

        RoadPackRegistry.RoadPack pack = RoadPackRegistry.getInstance().getActivePack();
        RoadGuardrailTemplate.GuardrailPattern noisePattern = preset.isNoiseWallEnabled()
                ? RoadGuardrailTemplate.getNoiseWall(pack)
                : null;
        List<RoadGuardrailTemplate.GuardrailSlot> baseSlots = RoadGuardrailTemplate.computeGuardrailSlots(preset);
        boolean hasAnyGuardrailPattern = (noisePattern != null);
        if (!hasAnyGuardrailPattern) {
            for (RoadGuardrailTemplate.GuardrailSlot s : baseSlots) {
                if (!s.isNoiseWall() && RoadGuardrailTemplate.getForStyle(s.style(), pack) != null) {
                    hasAnyGuardrailPattern = true;
                    break;
                }
            }
        }
        if (!hasAnyGuardrailPattern) {
            return;
        }

        double minT = (roundaboutAtP0 != null) ? (rRadiusP0 + 1.0)
                : (junctionAtP0 != null ? (radiusP0 + 1.0) : 0.0);
        double maxT = (roundaboutAtP3 != null) ? (totalLen - (rRadiusP3 + 1.0))
                : (junctionAtP3 != null ? (totalLen - (radiusP3 + 1.0)) : totalLen);
        if (maxT - minT < 0.8) {
            return;
        }

        Map<Long, Cell> ownCellsMap = new HashMap<>(finalCells.size());
        for (Cell c : finalCells) {
            ownCellsMap.put(coordKey(c.x(), c.z()), c);
        }

        double transLen0 = (transitionAtP0 != null) ? Math.min(totalLen, transitionAtP0.getTransitionLength()) : 0.0;
        double transLen3 = (transitionAtP3 != null) ? Math.min(totalLen, transitionAtP3.getTransitionLength()) : 0.0;

        // Track chains keyed by (isNoiseWall, trackIndex, style)
        Map<String, List<List<GuardrailTrackPoint>>> chainsByTrack = new LinkedHashMap<>();
        Map<String, Boolean> trackNoiseFlag = new HashMap<>();
        Map<String, RoadGuardrailTemplate.GuardrailPattern> trackPatterns = new HashMap<>();

        double stepT = 0.25;
        int numSteps = Math.max(1, (int) Math.ceil((maxT - minT) / stepT));
        for (int step = 0; step <= numSteps; step++) {
            double tDist = (step == numSteps) ? maxT : Math.min(maxT, minT + step * stepT);
            int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(tDist)));

            boolean nearTunnel = false;
            for (int ds = -2; ds <= 2; ds++) {
                int checkIdx = Math.max(0, Math.min(totalStations - 1, stIdx + ds));
                if (tunnelStations[checkIdx]) {
                    nearTunnel = true;
                    break;
                }
            }

            List<RoadGuardrailTemplate.GuardrailSlot> slots = baseSlots;
            if (transitionAtP0 != null && tDist <= transLen0) {
                double factor = Math.max(0.0, Math.min(1.0, tDist / Math.max(0.1, transLen0)));
                slots = RoadGuardrailTemplate.computeInterpolatedGuardrailSlots(preset, transitionAtP0.getStackAt(factor));
            } else if (transitionAtP3 != null && tDist >= totalLen - transLen3) {
                double distFromEnd = Math.max(0.0, totalLen - tDist);
                double factor = Math.max(0.0, Math.min(1.0, 1.0 - (distFromEnd / Math.max(0.1, transLen3))));
                slots = RoadGuardrailTemplate.computeInterpolatedGuardrailSlots(preset, transitionAtP3.getStackAt(factor));
            }
            if (slots.isEmpty()) continue;

            double u = curve.getArcLengthTable().getTForDistance(tDist);
            Vector3d pt = curve.evaluate(u);
            Vector3d tan = curve.evaluateTangent(u);
            double tanLen = Math.hypot(tan.x, tan.z);
            if (tanLen < 1e-6) continue;
            double tx = tan.x / tanLen;
            double tz = tan.z / tanLen;
            double nx = -tz;
            double nz = tx;

            int nwIdx = 0;
            int grIdx = 0;
            for (RoadGuardrailTemplate.GuardrailSlot slot : slots) {
                RoadGuardrailTemplate.GuardrailPattern slotPattern = slot.isNoiseWall()
                        ? noisePattern
                        : RoadGuardrailTemplate.getForStyle(slot.style(), pack);
                String trackKey = slot.isNoiseWall()
                        ? ("NW_" + (slot.inwardSign() > 0 ? "L" : "R") + "_" + (nwIdx++))
                        : ("GR_" + (grIdx++) + "_" + (slot.style() != null ? slot.style().name() : "NONE"));
                trackNoiseFlag.put(trackKey, slot.isNoiseWall());
                if (slotPattern != null) {
                    trackPatterns.put(trackKey, slotPattern);
                }

                boolean skipSample = (slotPattern == null)
                        || (slot.isNoiseWall() && nearTunnel)
                        || (!slot.isNoiseWall() && tunnelStations[stIdx] && slot.outerBoundary());

                if (skipSample) {
                    closeOpenTrackChain(chainsByTrack, trackKey);
                    continue;
                }

                double inX = nx * slot.inwardSign();
                double inZ = nz * slot.inwardSign();
                double wx = pt.x + nx * slot.anchorOffset();
                double wz = pt.z + nz * slot.anchorOffset();
                Cell anchorCell = resolveGuardrailAnchorCell(ownCellsMap, wx, wz, inX, inZ, slot.sourceType(), slot.elemWidth());
                if (anchorCell == null
                        || (junctionColumnKeys != null && junctionColumnKeys.contains(coordKey(anchorCell.x(), anchorCell.z())))) {
                    closeOpenTrackChain(chainsByTrack, trackKey);
                    continue;
                }

                BlockPos surfPos = new BlockPos(anchorCell.x(), anchorCell.y(), anchorCell.z());
                BlockState surfState = blockMap.get(surfPos);
                if (surfState == null || surfState.isAir() || otherActiveRoadBlocks.contains(surfPos)) {
                    closeOpenTrackChain(chainsByTrack, trackKey);
                    continue;
                }

                // If non-lane barrier surface was overwritten into asphalt by another intersecting road, break cleanly
                boolean isRoadwaySlot = slot.sourceType() == RoadElementType.LANE || slot.sourceType().isMarking();
                if (!isRoadwaySlot && (surfState.is(Blocks.GRAY_CONCRETE_POWDER)
                        || surfState.is(Blocks.SMOOTH_QUARTZ)
                        || surfState.is(Blocks.SMOOTH_QUARTZ_STAIRS))) {
                    closeOpenTrackChain(chainsByTrack, trackKey);
                    continue;
                }

                GuardrailTrackPoint sample = new GuardrailTrackPoint(
                        anchorCell.x(), anchorCell.y(), anchorCell.z(), wx, wz, tx, tz, inX, inZ
                );
                appendTrackSample4Connected(chainsByTrack, trackKey, sample, ownCellsMap);
            }
        }

        // Render all 4-connected barrier & noise-wall chains
        for (Map.Entry<String, List<List<GuardrailTrackPoint>>> trackEntry : chainsByTrack.entrySet()) {
            boolean isNoise = trackNoiseFlag.getOrDefault(trackEntry.getKey(), false);
            RoadGuardrailTemplate.GuardrailPattern pattern = trackPatterns.get(trackEntry.getKey());
            if (pattern == null) continue;

            for (List<GuardrailTrackPoint> chain : trackEntry.getValue()) {
                if (chain.isEmpty()) continue;
                renderGuardrailChain(chain, pattern, isNoise, blockMap, otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys);
            }
        }
    }

    private static void closeOpenTrackChain(Map<String, List<List<GuardrailTrackPoint>>> chainsByTrack, String trackKey) {
        List<List<GuardrailTrackPoint>> list = chainsByTrack.get(trackKey);
        if (list != null && !list.isEmpty() && !list.get(list.size() - 1).isEmpty()) {
            list.add(new ArrayList<>());
        }
    }

    private static void appendTrackSample4Connected(
            Map<String, List<List<GuardrailTrackPoint>>> chainsByTrack,
            String trackKey,
            GuardrailTrackPoint sample,
            Map<Long, Cell> ownCellsMap
    ) {
        List<List<GuardrailTrackPoint>> chains = chainsByTrack.computeIfAbsent(trackKey, k -> {
            List<List<GuardrailTrackPoint>> l = new ArrayList<>();
            l.add(new ArrayList<>());
            return l;
        });
        List<GuardrailTrackPoint> cur = chains.get(chains.size() - 1);
        if (cur.isEmpty()) {
            cur.add(sample);
            return;
        }
        GuardrailTrackPoint prev = cur.get(cur.size() - 1);
        int dx = sample.bx - prev.bx;
        int dz = sample.bz - prev.bz;
        if (dx == 0 && dz == 0) {
            return;
        }
        int manhattan = Math.abs(dx) + Math.abs(dz);
        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            // Insert 1 orthogonal bridge cell so fences/bars/glass panes never disconnect along diagonals
            Cell cA = ownCellsMap.get(coordKey(sample.bx, prev.bz));
            Cell cB = ownCellsMap.get(coordKey(prev.bx, sample.bz));
            int bridgeX;
            int bridgeZ;
            int bridgeY;
            if (cA != null && cB == null) {
                bridgeX = sample.bx;
                bridgeZ = prev.bz;
                bridgeY = cA.y();
            } else if (cB != null && cA == null) {
                bridgeX = prev.bx;
                bridgeZ = sample.bz;
                bridgeY = cB.y();
            } else {
                double dA = Math.hypot((sample.bx + 0.5) - sample.wx, (prev.bz + 0.5) - sample.wz);
                double dB = Math.hypot((prev.bx + 0.5) - sample.wx, (sample.bz + 0.5) - sample.wz);
                if (dA <= dB) {
                    bridgeX = sample.bx;
                    bridgeZ = prev.bz;
                    bridgeY = (cA != null) ? cA.y() : prev.by;
                } else {
                    bridgeX = prev.bx;
                    bridgeZ = sample.bz;
                    bridgeY = (cB != null) ? cB.y() : prev.by;
                }
            }
            cur.add(new GuardrailTrackPoint(bridgeX, bridgeY, bridgeZ, sample.wx, sample.wz, sample.tx, sample.tz, sample.inX, sample.inZ));
        } else if (manhattan > 2) {
            cur = new ArrayList<>();
            chains.add(cur);
        }
        cur.add(sample);
    }

    private static Cell resolveGuardrailAnchorCell(
            Map<Long, Cell> ownCellsMap,
            double wx,
            double wz,
            double inX,
            double inZ,
            RoadElementType expectedType,
            double elemWidth
    ) {
        int bx = (int) Math.floor(wx);
        int bz = (int) Math.floor(wz);
        int stepOutX = (int) Math.round(-inX);
        int stepOutZ = (int) Math.round(-inZ);
        int stepInX = (int) Math.round(inX);
        int stepInZ = (int) Math.round(inZ);

        int[][] candidates = new int[][]{{0, 0}, {stepOutX, stepOutZ}, {stepInX, stepInZ}};
        for (int[] off : candidates) {
            Cell c = ownCellsMap.get(coordKey(bx + off[0], bz + off[1]));
            if (c == null) continue;
            if (matchesGuardrailElement(c.kind(), expectedType, elemWidth)) {
                return c;
            }
        }
        // Fallback to exact floored cell if type check slightly shifted on curves
        return ownCellsMap.get(coordKey(bx, bz));
    }

    private static boolean matchesGuardrailElement(Kind kind, RoadElementType type, double elemWidth) {
        return switch (type) {
            case SIDEWALK, SHOULDER -> kind == Kind.SIDEWALK;
            case DIVIDER_GRASS -> kind == Kind.GRASS || (elemWidth <= 2.2 && kind == Kind.CURB);
            case LAWN -> kind == Kind.GRASS;
            case CURB -> kind == Kind.CURB;
            case LANE -> kind == Kind.ASPHALT;
            case MARKING_DASHED_LONG, MARKING_DASHED_SHORT, MARKING_SOLID, MARKING_DOUBLE_SOLID ->
                    kind == Kind.LINE || kind == Kind.ASPHALT;
        };
    }

    private static void renderGuardrailChain(
            List<GuardrailTrackPoint> chain,
            RoadGuardrailTemplate.GuardrailPattern pattern,
            boolean isNoiseWall,
            Map<BlockPos, BlockState> blockMap,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        int n = chain.size();
        BlockPos prevUpperBase = null;

        for (int i = 0; i < n; i++) {
            GuardrailTrackPoint p = chain.get(i);
            double dirX = p.tx;
            double dirZ = p.tz;
            if (i + 1 < n) {
                dirX = chain.get(i + 1).bx - p.bx;
                dirZ = chain.get(i + 1).bz - p.bz;
            } else if (i > 0) {
                dirX = p.bx - chain.get(i - 1).bx;
                dirZ = p.bz - chain.get(i - 1).bz;
            }
            Direction alongDir = RoadGuardrailTemplate.directionFromHorizontal(dirX, dirZ);
            Rotation rot = pattern.rotationForAlong(alongDir);

            double cheb = Math.max(Math.abs(p.inX), Math.abs(p.inZ));
            int stepInX = (cheb > 1e-6) ? (int) Math.round(p.inX / cheb) : 0;
            int stepInZ = (cheb > 1e-6) ? (int) Math.round(p.inZ / cheb) : 0;

            List<RoadGuardrailTemplate.GuardrailVoxel> slice = pattern.getSlice(i);

            // Check overpass height clearance & lamp pedestal preservation for the whole vertical column
            boolean blockedByOverpassOrLamp = false;
            BlockPos lampCheck = new BlockPos(p.bx, p.by + 1, p.bz);
            BlockState existingBase = blockMap.get(lampCheck);
            if (existingBase != null && !existingBase.isAir()) {
                // Skip if a street lamp pedestal or tunnel wall already occupies this column
                prevUpperBase = null;
                continue;
            }

            BlockPos upperBaseThisStep = null;
            Map<BlockPos, BlockState> toPlace = new HashMap<>(slice.size());
            for (RoadGuardrailTemplate.GuardrailVoxel v : slice) {
                int vx = p.bx + v.relZ() * stepInX;
                int vz = p.bz + v.relZ() * stepInZ;
                int vy = p.by + 1 + v.relY();
                BlockPos vp = new BlockPos(vx, vy, vz);

                if (v.relZ() != 0 && upperBaseThisStep == null) {
                    upperBaseThisStep = new BlockPos(vx, p.by, vz);
                }

                if (otherActiveRoadBlocks.contains(vp)) {
                    blockedByOverpassOrLamp = true;
                    break;
                }
                if (junctionColumnKeys != null && v.relZ() == 0 && junctionColumnKeys.contains(coordKey(vx, vz))) {
                    blockedByOverpassOrLamp = true;
                    break;
                }

                List<Cell> others = otherRoadCells.get(coordKey(vx, vz));
                if (others != null) {
                    for (Cell otherC : others) {
                        if (isNoiseWall) {
                            if (otherC.y() >= p.by + 1 && otherC.y() - 4 <= vy) {
                                blockedByOverpassOrLamp = true;
                                break;
                            }
                        } else if (otherC.y() >= p.by + 1 && otherC.y() - 4 <= vy) {
                            blockedByOverpassOrLamp = true;
                            break;
                        }
                    }
                    if (blockedByOverpassOrLamp) break;
                }

                BlockState curAtVp = blockMap.get(vp);
                if (curAtVp != null && !curAtVp.isAir()) {
                    // Preserve already placed solid blocks (lamps, tunnel vault, crosswalk curbs)
                    continue;
                }
                toPlace.put(vp, v.state().rotate(rot));
            }

            if (blockedByOverpassOrLamp) {
                prevUpperBase = null;
                continue;
            }

            // Ensure diagonal bends of the shifted upper glass tier on Road_noizewall stay sealed
            if (isNoiseWall && upperBaseThisStep != null && prevUpperBase != null) {
                int ux = upperBaseThisStep.getX() - prevUpperBase.getX();
                int uz = upperBaseThisStep.getZ() - prevUpperBase.getZ();
                if (Math.abs(ux) == 1 && Math.abs(uz) == 1) {
                    int bx = upperBaseThisStep.getX();
                    int bz = prevUpperBase.getZ();
                    for (RoadGuardrailTemplate.GuardrailVoxel v : slice) {
                        if (v.relZ() != 0) {
                            BlockPos bp = new BlockPos(bx, p.by + 1 + v.relY(), bz);
                            if (!otherActiveRoadBlocks.contains(bp)) {
                                BlockState cur = blockMap.get(bp);
                                if (cur == null || cur.isAir()) {
                                    toPlace.put(bp, v.state().rotate(rot));
                                }
                            }
                        }
                    }
                }
            }
            prevUpperBase = upperBaseThisStep;

            blockMap.putAll(toPlace);
        }
    }

    private static boolean isDifferentStair(Map<BlockPos, BlockState> blocks, BlockPos pos, Direction facing, Half half) {
        BlockState s = blocks.get(pos);
        return s == null || !(s.getBlock() instanceof StairBlock)
                || s.getValue(StairBlock.FACING) != facing
                || s.getValue(StairBlock.HALF) != half;
    }

    private static void placeGreenery(
            Map<BlockPos, BlockState> blockMap,
            BezierCurve3D curve,
            ModularRoadPreset preset,
            List<Cell> finalCells,
            double totalLen,
            int totalStations,
            boolean[] tunnelStations,
            TransitionProfile transitionAtP0,
            TransitionProfile transitionAtP3,
            RoadNode junctionAtP0,
            double radiusP0,
            RoadNode junctionAtP3,
            double radiusP3,
            RoadNode roundaboutAtP0,
            double rRadiusP0,
            RoadNode roundaboutAtP3,
            double rRadiusP3,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        if (preset == null || totalLen < 2.0) return;
        List<RoadGreeneryTemplate.GreenerySlot> baseSlots = RoadGreeneryTemplate.computeGreenerySlots(preset);
        if (baseSlots.isEmpty()) return;

        RoadPackRegistry.RoadPack pack = RoadPackRegistry.getInstance().getActivePack();
        double minT = (roundaboutAtP0 != null) ? (rRadiusP0 + 2.0)
                : (junctionAtP0 != null ? (radiusP0 + 2.0) : 1.0);
        double maxT = (roundaboutAtP3 != null) ? (totalLen - (rRadiusP3 + 2.0))
                : (junctionAtP3 != null ? (totalLen - (radiusP3 + 2.0)) : totalLen - 1.0);
        if (maxT - minT < 1.0) return;

        Map<Long, Cell> ownCellsMap = new HashMap<>(finalCells.size());
        for (Cell c : finalCells) {
            ownCellsMap.put(coordKey(c.x(), c.z()), c);
        }

        double transLen0 = (transitionAtP0 != null) ? Math.min(totalLen, transitionAtP0.getTransitionLength()) : 0.0;
        double transLen3 = (transitionAtP3 != null) ? Math.min(totalLen, transitionAtP3.getTransitionLength()) : 0.0;

        boolean hasTrees = baseSlots.stream().anyMatch(s -> s.style().isTree());
        boolean hasBushes = baseSlots.stream().anyMatch(s -> s.style().isBush());

        if (hasTrees) {
            int treeSpacing = Math.max(4, preset.getTreeSpacing());
            // Phase-offset trees halfway between lamp stations so trunks naturally alternate with lamps
            List<Double> treeStations = StreetLampTemplate.computeLampStations(minT, maxT, treeSpacing, true);
            for (double tDist : treeStations) {
                placeGreeneryAtStationGroup(
                        tDist, true, minT, maxT, totalLen, totalStations, tunnelStations,
                        curve, preset, baseSlots, transitionAtP0, transitionAtP3, transLen0, transLen3,
                        pack, ownCellsMap, blockMap, otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
                );
            }
        }

        if (hasBushes) {
            int baseBushSpacing = Math.max(3, preset.getBushSpacing());
            int minPatternLen = baseSlots.stream()
                    .filter(s -> s.style().isBush())
                    .mapToInt(s -> s.style() == ModularRoadPreset.GreeneryStyle.BUSH_WALL ? 6 : 3)
                    .max()
                    .orElse(3);
            int bushStep = Math.max(baseBushSpacing, minPatternLen);
            List<Double> bushStations = StreetLampTemplate.computeLampStations(minT, maxT, bushStep, false);
            for (double tDist : bushStations) {
                placeGreeneryAtStationGroup(
                        tDist, false, minT, maxT, totalLen, totalStations, tunnelStations,
                        curve, preset, baseSlots, transitionAtP0, transitionAtP3, transLen0, transLen3,
                        pack, ownCellsMap, blockMap, otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
                );
            }
        }
    }

    private static void placeGreeneryAtStationGroup(
            double baseTDist,
            boolean treesPass,
            double minT,
            double maxT,
            double totalLen,
            int totalStations,
            boolean[] tunnelStations,
            BezierCurve3D curve,
            ModularRoadPreset preset,
            List<RoadGreeneryTemplate.GreenerySlot> baseSlots,
            TransitionProfile transitionAtP0,
            TransitionProfile transitionAtP3,
            double transLen0,
            double transLen3,
            RoadPackRegistry.RoadPack pack,
            Map<Long, Cell> ownCellsMap,
            Map<BlockPos, BlockState> blockMap,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        double[] offsets = treesPass ? new double[]{ 0.0, 2.0, -2.0 } : new double[]{ 0.0, 1.0, -1.0 };

        List<RoadGreeneryTemplate.GreenerySlot> baseFiltered = baseSlots.stream()
                .filter(s -> s.style().isTree() == treesPass)
                .toList();

        for (int slotIdx = 0; slotIdx < baseFiltered.size(); slotIdx++) {
            for (double shift : offsets) {
                double tDist = baseTDist + shift;
                if (tDist < minT || tDist > maxT) continue;

                int stIdx = (int) Math.max(0, Math.min(totalStations - 1, Math.round(tDist)));
                boolean nearTunnel = false;
                int tunnelPad = treesPass ? 3 : 1;
                for (int ds = -tunnelPad; ds <= tunnelPad; ds++) {
                    int checkIdx = Math.max(0, Math.min(totalStations - 1, stIdx + ds));
                    if (tunnelStations[checkIdx]) {
                        nearTunnel = true;
                        break;
                    }
                }
                if (nearTunnel) continue;

                List<RoadGreeneryTemplate.GreenerySlot> slots = baseFiltered;
                if (transitionAtP0 != null && tDist <= transLen0) {
                    double factor = Math.max(0.0, Math.min(1.0, tDist / Math.max(0.1, transLen0)));
                    slots = RoadGreeneryTemplate.computeInterpolatedGreenerySlots(preset, transitionAtP0.getStackAt(factor))
                            .stream().filter(s -> s.style().isTree() == treesPass).toList();
                } else if (transitionAtP3 != null && tDist >= totalLen - transLen3) {
                    double distFromEnd = Math.max(0.0, totalLen - tDist);
                    double factor = Math.max(0.0, Math.min(1.0, 1.0 - (distFromEnd / Math.max(0.1, transLen3))));
                    slots = RoadGreeneryTemplate.computeInterpolatedGreenerySlots(preset, transitionAtP3.getStackAt(factor))
                            .stream().filter(s -> s.style().isTree() == treesPass).toList();
                }
                if (slotIdx >= slots.size()) break;

                RoadGreeneryTemplate.GreenerySlot slot = slots.get(slotIdx);
                double u = curve.getArcLengthTable().getTForDistance(tDist);
                Vector3d pt = curve.evaluate(u);
                Vector3d tan = curve.evaluateTangent(u);
                double tanLen = Math.hypot(tan.x, tan.z);
                if (tanLen < 1e-6) continue;
                double tx = tan.x / tanLen;
                double tz = tan.z / tanLen;
                double nx = -tz;
                double nz = tx;

                double inX = nx * slot.inwardSign();
                double inZ = nz * slot.inwardSign();
                double wx = pt.x + nx * slot.anchorOffset();
                double wz = pt.z + nz * slot.anchorOffset();

                Cell anchorCell = resolveGreeneryAnchorCell(ownCellsMap, wx, wz, inX, inZ, slot.sourceType());
                if (anchorCell == null) continue;
                if (junctionColumnKeys != null && junctionColumnKeys.contains(coordKey(anchorCell.x(), anchorCell.z()))) {
                    continue;
                }

                // Trees can strictly only spawn on sidewalks or grass elements
                if (treesPass) {
                    if (anchorCell.kind() != Kind.GRASS && anchorCell.kind() != Kind.SIDEWALK) {
                        continue;
                    }
                    if (!slot.sourceType().supportsTrees()) {
                        continue;
                    }
                } else {
                    if (anchorCell.kind() != Kind.GRASS && anchorCell.kind() != Kind.SIDEWALK && anchorCell.kind() != Kind.STONE) {
                        continue;
                    }
                    if (!slot.sourceType().supportsBushes()) {
                        continue;
                    }
                }

                BlockPos surfPos = new BlockPos(anchorCell.x(), anchorCell.y(), anchorCell.z());
                BlockState surfState = blockMap.get(surfPos);
                if (surfState == null || surfState.isAir() || otherActiveRoadBlocks.contains(surfPos)) {
                    continue;
                }
                if (surfState.is(Blocks.GRAY_CONCRETE_POWDER)
                        || surfState.is(Blocks.SMOOTH_QUARTZ)
                        || surfState.getBlock() instanceof StairBlock) {
                    continue;
                }

                // Abort if an existing lamp pedestal or guardrail occupies the anchor column above road surface
                BlockPos aboveAnchor = new BlockPos(anchorCell.x(), anchorCell.y() + 1, anchorCell.z());
                BlockState existingAbove = blockMap.get(aboveAnchor);
                if (existingAbove != null && !existingAbove.isAir()) {
                    continue;
                }

                long seed = 31L * Double.doubleToLongBits(slot.anchorOffset())
                        ^ (31L * stIdx)
                        ^ ((long) anchorCell.x() * 73856093L)
                        ^ ((long) anchorCell.z() * 19349663L);
                ModularRoadPreset.GreeneryStyle concreteStyle = RoadGreeneryTemplate.resolveConcreteStyle(slot.style(), seed);
                RoadGreeneryTemplate.GreeneryPattern pattern = RoadGreeneryTemplate.getForStyle(concreteStyle, pack);
                if (pattern == null) break;

                Rotation rot = treesPass
                        ? RoadGreeneryTemplate.deterministicTreeRotation(seed)
                        : RoadGreeneryTemplate.rotationForAlong(RoadGuardrailTemplate.directionFromHorizontal(tx, tz));

                boolean onGrassSurface = slot.isGrassElement()
                        || anchorCell.kind() == Kind.GRASS
                        || surfState.is(Blocks.GRASS_BLOCK);

                if (tryPlaceSingleGreenery(
                        pattern, anchorCell.x(), anchorCell.y(), anchorCell.z(), rot,
                        onGrassSurface, treesPass, ownCellsMap, blockMap,
                        otherRoadCells, otherActiveRoadBlocks, junctionColumnKeys
                )) {
                    break;
                }
            }
        }
    }

    private static boolean tryPlaceSingleGreenery(
            RoadGreeneryTemplate.GreeneryPattern pattern,
            int ax,
            int ay,
            int az,
            Rotation rot,
            boolean onGrassSurface,
            boolean isTree,
            Map<Long, Cell> ownCellsMap,
            Map<BlockPos, BlockState> blockMap,
            Map<Long, List<Cell>> otherRoadCells,
            Set<BlockPos> otherActiveRoadBlocks,
            Set<Long> junctionColumnKeys
    ) {
        Map<BlockPos, BlockState> staged = new HashMap<>(pattern.getVoxels().size());
        for (RoadGreeneryTemplate.GreeneryVoxel v : pattern.getVoxels()) {
            // Trees on grass/lawn must NOT place the copper-slab substrate pad around the trunk base
            if (isTree && onGrassSurface && v.substratePad()) {
                continue;
            }

            int rdx = RoadGreeneryTemplate.rotateDx(v.dx(), v.dz(), rot);
            int rdz = RoadGreeneryTemplate.rotateDz(v.dx(), v.dz(), rot);
            int vx = ax + rdx;
            int vy = ay + v.dy();
            int vz = az + rdz;
            BlockPos vp = new BlockPos(vx, vy, vz);

            if (otherActiveRoadBlocks.contains(vp)) {
                if (v.dy() == 0) continue;
                return false;
            }
            if (junctionColumnKeys != null && v.dy() <= 2 && junctionColumnKeys.contains(coordKey(vx, vz))) {
                if (v.dy() == 0) continue;
                return false;
            }

            // Never clip canopy or trunk into another elevated road/overpass deck above
            List<Cell> others = otherRoadCells.get(coordKey(vx, vz));
            if (others != null) {
                for (Cell otherC : others) {
                    if (otherC.y() >= ay + 1 && otherC.y() - 4 <= vy) {
                        return false;
                    }
                }
            }

            if (v.dy() == 0) {
                // Y=0 replaces only ground/sidewalk/grass surface blocks, never curbs, asphalt, or road markings
                Cell targetCell = ownCellsMap.get(coordKey(vx, vz));
                if (targetCell != null && (targetCell.kind() == Kind.ASPHALT
                        || targetCell.kind() == Kind.LINE
                        || targetCell.kind() == Kind.CURB)) {
                    continue;
                }
                if (isTree && targetCell != null && targetCell.kind() == Kind.GRASS && v.substratePad()) {
                    continue;
                }
                BlockState cur = blockMap.get(vp);
                if (cur == null || cur.isAir()
                        || cur.is(Blocks.GRAY_CONCRETE_POWDER)
                        || cur.is(Blocks.SMOOTH_QUARTZ)
                        || cur.getBlock() instanceof StairBlock) {
                    continue;
                }
            } else {
                BlockState cur = blockMap.get(vp);
                if (cur != null && !cur.isAir()) {
                    // Do not overwrite street lamps, guardrails, tunnel vaults, or existing non-leaf trunks
                    if (!(cur.is(BlockTags.LEAVES) && !v.state().is(BlockTags.LEAVES))) {
                        continue;
                    }
                }
            }

            staged.put(vp, v.state().rotate(rot));
        }

        if (staged.isEmpty()) {
            return false;
        }
        blockMap.putAll(staged);
        return true;
    }

    private static Cell resolveGreeneryAnchorCell(
            Map<Long, Cell> ownCellsMap,
            double wx,
            double wz,
            double inX,
            double inZ,
            RoadElementType expectedType
    ) {
        int bx = (int) Math.floor(wx + 1e-4);
        int bz = (int) Math.floor(wz + 1e-4);
        Cell direct = ownCellsMap.get(coordKey(bx, bz));
        if (direct != null && matchesGreeneryElement(direct.kind(), expectedType)) {
            return direct;
        }
        int stepInX = (int) Math.round(inX);
        int stepInZ = (int) Math.round(inZ);
        int[][] search = {
                { stepInX, stepInZ },
                { -stepInX, -stepInZ },
                { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }
        };
        for (int[] off : search) {
            Cell c = ownCellsMap.get(coordKey(bx + off[0], bz + off[1]));
            if (c != null && matchesGreeneryElement(c.kind(), expectedType)) {
                return c;
            }
        }
        return null;
    }

    private static boolean matchesGreeneryElement(Kind kind, RoadElementType type) {
        return switch (type) {
            case SIDEWALK -> kind == Kind.SIDEWALK;
            case LAWN, DIVIDER_GRASS -> kind == Kind.GRASS;
            case SHOULDER -> kind == Kind.GRASS || kind == Kind.STONE;
            default -> false;
        };
    }
}
