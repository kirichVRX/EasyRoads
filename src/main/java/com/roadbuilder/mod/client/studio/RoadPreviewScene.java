package com.roadbuilder.mod.client.studio;

import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadGreeneryTemplate;
import com.roadbuilder.mod.domain.pack.RoadGuardrailTemplate;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.pack.StreetLampTemplate;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates and holds the voxel grid of blocks for the Road Preset Studio 3D preview.
 */
public final class RoadPreviewScene implements BlockGetter {

    public static final int PREVIEW_LENGTH = 28;

    private final Map<BlockPos, BlockState> blocks;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private RoadPreviewScene(Map<BlockPos, BlockState> blocks, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.blocks = blocks;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static RoadPreviewScene build(
            ModularRoadPreset preset,
            RoadPackRegistry.RoadPack pack,
            boolean viewRoad,
            boolean viewViaduct,
            boolean viewTunnel,
            boolean viewEmbankment,
            boolean viewSlope
    ) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        if (preset == null || pack == null) {
            return new RoadPreviewScene(blocks, 0, 0, 0, 0, 0, 0);
        }

        List<RoadStackElement> stack = preset.getStack();
        if (stack.isEmpty()) {
            return new RoadPreviewScene(blocks, 0, 0, 0, 0, 0, 0);
        }

        int totalWidth = preset.getTotalWidth();
        float halfW = totalWidth / 2.0f;

        if (viewRoad) {
            int currentX = 0;
            for (RoadStackElement elem : stack) {
                int elemW = elem.getWidth();
                boolean isLeftSide = (currentX < halfW);
                buildStackComponent(blocks, pack, preset, elem.getType(), currentX, elemW, PREVIEW_LENGTH, isLeftSide);
                currentX += elemW;
            }
        }

        if (viewViaduct) {
            buildSchematicViaduct(blocks, pack, totalWidth, PREVIEW_LENGTH);
        }

        if (viewTunnel) {
            buildSchematicTunnel(blocks, preset, totalWidth, PREVIEW_LENGTH);
        }

        if (viewEmbankment && preset.getEmbankmentMode() != ModularRoadPreset.ToggleMode.OFF) {
            buildPreviewEmbankment(blocks, pack, preset, totalWidth, PREVIEW_LENGTH);
        }

        if (viewSlope && preset.getSlopeMode() != ModularRoadPreset.ToggleMode.OFF) {
            buildPreviewSlope(blocks, preset, totalWidth, PREVIEW_LENGTH);
        }

        if (viewRoad && !viewTunnel) {
            buildPreviewLamps(blocks, pack, preset, totalWidth, PREVIEW_LENGTH);
        }

        if (viewRoad) {
            buildPreviewGuardrails(blocks, pack, preset, totalWidth, PREVIEW_LENGTH, viewTunnel);
        }

        if (viewRoad && !viewTunnel) {
            buildPreviewGreenery(blocks, pack, preset, totalWidth, PREVIEW_LENGTH);
        }

        RoadGuardrailTemplate.connectThinBlocksInPreview(blocks);

        if (blocks.isEmpty()) {
            return new RoadPreviewScene(blocks, 0, 0, 0, 0, 0, 0);
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : blocks.keySet()) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getY() > maxY) maxY = pos.getY();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }

        return new RoadPreviewScene(blocks, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void setBlock(Map<BlockPos, BlockState> blocks, int x, int y, int z, BlockState state) {
        if (state != null && !state.isAir()) {
            blocks.put(new BlockPos(x, y, z), state);
        }
    }

    private static void buildStackComponent(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            ModularRoadPreset preset,
            RoadElementType type,
            int startX,
            int width,
            int length,
            boolean isLeftSide
    ) {
        if (type == RoadElementType.SIDEWALK) {
            buildSidewalk(blocks, preset.getSidewalkPalette(), startX, width, length);
            return;
        }
        if (type == RoadElementType.CURB) {
            buildCurb(blocks, preset.getCurbPalette(), startX, width, length, isLeftSide);
            return;
        }
        if (type == RoadElementType.LANE) {
            buildLane(blocks, startX, width, length);
            return;
        }
        if (type == RoadElementType.SHOULDER) {
            buildShoulder(blocks, startX, width, length, isLeftSide);
            return;
        }
        if (type == RoadElementType.LAWN) {
            buildLawn(blocks, startX, width, length);
            return;
        }

        String schematicKey = switch (type) {
            case MARKING_DASHED_LONG -> "road_razmetka_prerivistaya_long";
            case MARKING_DASHED_SHORT -> "road_razmetka_prerivistaya_short";
            case MARKING_SOLID -> "road_razmetka_sploshnaya";
            case MARKING_DOUBLE_SOLID -> "road_razmetka_sploshnaya_double";
            case DIVIDER_GRASS -> "road_razdelitel_grass";
            default -> null;
        };

        LitematicParser.Schematic schematic = schematicKey != null ? pack.getSchematic(schematicKey) : null;
        if (schematic != null) {
            int elemW = schematic.getSizeZ();
            for (int z = 0; z < length; z++) {
                int sx = z % schematic.getSizeX();
                for (int dx = 0; dx < elemW; dx++) {
                    for (int y = 0; y < schematic.getSizeY(); y++) {
                        BlockState state = schematic.getBlock(sx, y, dx);
                        if (!state.isAir()) {
                            BlockState oriented = orientStairsForRoad(state);
                            setBlock(blocks, startX + dx, y, z, oriented);
                            setBlock(blocks, startX + dx, -1, z, Blocks.STONE.defaultBlockState());
                        }
                    }
                }
            }
        } else {
            buildLane(blocks, startX, width, length);
        }
    }

    private static BlockState orientStairsForRoad(BlockState state) {
        if (state.getBlock() instanceof StairBlock) {
            Direction dir = state.getValue(StairBlock.FACING);
            if (dir == Direction.NORTH) {
                return state.setValue(StairBlock.FACING, Direction.WEST);
            } else if (dir == Direction.SOUTH) {
                return state.setValue(StairBlock.FACING, Direction.EAST);
            }
        }
        return state;
    }

    private static void buildSidewalk(Map<BlockPos, BlockState> blocks, ModularRoadPreset.SidewalkPalette palette, int startX, int width, int length) {
        BlockState foundation = Blocks.STONE.defaultBlockState();

        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                int x = startX + dx;
                setBlock(blocks, x, 0, z, com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickSidewalkBlock(palette, x, 0, z));
                setBlock(blocks, x, -1, z, foundation);
            }
        }
    }

    private static void buildCurb(Map<BlockPos, BlockState> blocks, ModularRoadPreset.CurbPalette palette, int startX, int width, int length, boolean isLeftSide) {
        Direction curbFacing = isLeftSide ? Direction.WEST : Direction.EAST;
        BlockState foundation = Blocks.STONE.defaultBlockState();

        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                int x = startX + dx;
                BlockState curbBase = com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickCurbBlock(palette, x, 0, z);
                BlockState curb = (curbBase.getBlock() instanceof StairBlock)
                        ? curbBase.setValue(StairBlock.FACING, curbFacing)
                                .setValue(StairBlock.HALF, Half.BOTTOM)
                                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT)
                        : curbBase;
                setBlock(blocks, x, 0, z, curb);
                setBlock(blocks, x, -1, z, foundation);
            }
        }
    }

    private static void buildShoulder(Map<BlockPos, BlockState> blocks, int startX, int width, int length, boolean isLeftSide) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        for (int z = 0; z < length; z++) {
            if (isLeftSide) {
                setBlock(blocks, startX, 0, z, grass);
                setBlock(blocks, startX, -1, z, dirt);
                setBlock(blocks, startX + 1, 0, z, grass);
                setBlock(blocks, startX + 1, -1, z, dirt);
                setBlock(blocks, startX + 2, 0, z, stone);
                setBlock(blocks, startX + 2, -1, z, stone);
            } else {
                setBlock(blocks, startX, 0, z, stone);
                setBlock(blocks, startX, -1, z, stone);
                setBlock(blocks, startX + 1, 0, z, grass);
                setBlock(blocks, startX + 1, -1, z, dirt);
                setBlock(blocks, startX + 2, 0, z, grass);
                setBlock(blocks, startX + 2, -1, z, dirt);
            }
        }
    }

    private static void buildLawn(Map<BlockPos, BlockState> blocks, int startX, int width, int length) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                setBlock(blocks, startX + dx, 0, z, grass);
                setBlock(blocks, startX + dx, -1, z, dirt);
            }
        }
    }

    private static void buildLane(Map<BlockPos, BlockState> blocks, int startX, int width, int length) {
        BlockState asphalt = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z < length; z++) {
            for (int dx = 0; dx < width; dx++) {
                setBlock(blocks, startX + dx, 0, z, asphalt);
                setBlock(blocks, startX + dx, -1, z, stone);
            }
        }
    }

    private static void buildSchematicViaduct(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            int totalWidth,
            int length
    ) {
        LitematicParser.Schematic pillar = pack.getSchematic("road_opora");
        if (pillar == null) return;

        int pillarCrossW = 7;
        int pillarH = pillar.getSizeY();
        int pillarLen = 4;

        int pz = Math.max(0, (length - pillarLen) / 2);
        int attachY = -2 - (pillarH - 1);
        int[] pillarPositions = getPillarPositions(totalWidth);

        BlockState leftStair = pillar.getBlock(9, 6, 0);
        BlockState rightStair = pillar.getBlock(9, 6, 6);
        BlockState stone = pillar.getBlock(9, 5, 1);

        for (int px : pillarPositions) {
            for (int lz = 0; lz < length; lz++) {
                setBlock(blocks, px, attachY + 6, lz, orientStairsForRoad(leftStair));
                for (int dx = 1; dx <= 5; dx++) {
                    setBlock(blocks, px + dx, attachY + 6, lz, stone);
                }
                setBlock(blocks, px + 6, attachY + 6, lz, orientStairsForRoad(rightStair));

                for (int dx = 1; dx <= 5; dx++) {
                    setBlock(blocks, px + dx, attachY + 5, lz, stone);
                }
            }

            for (int z = 0; z < pillarCrossW; z++) {
                for (int y = 0; y <= 4; y++) {
                    for (int x = 9; x <= 12; x++) {
                        BlockState s = pillar.getBlock(x, y, z);
                        if (!s.isAir()) {
                            setBlock(blocks, px + z, attachY + y, pz + (x - 9), orientStairsForRoad(s));
                        }
                    }
                }
            }
        }
    }

    private static int[] getPillarPositions(int totalWidth) {
        if (totalWidth < 18) {
            return new int[]{(totalWidth - 7) / 2};
        } else if (totalWidth < 28) {
            int px1 = Math.max(0, Math.min((int) Math.round(totalWidth * 0.25 - 3.5), totalWidth / 2 - 7));
            int px2 = Math.max(totalWidth / 2, Math.min((int) Math.round(totalWidth * 0.75 - 3.5), totalWidth - 7));
            return new int[]{px1, px2};
        } else {
            int px1 = Math.max(0, (int) Math.round(totalWidth * (1.0 / 6.0) - 3.5));
            int px2 = (totalWidth - 7) / 2;
            int px3 = Math.min(totalWidth - 7, (int) Math.round(totalWidth * (5.0 / 6.0) - 3.5));
            return new int[]{px1, px2, px3};
        }
    }

    private static void buildSchematicTunnel(
            Map<BlockPos, BlockState> blocks,
            ModularRoadPreset preset,
            int totalWidth,
            int length
    ) {
        ModularRoadPreset.TunnelShape shape = preset.getTunnelShape();
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < totalWidth; x++) {
                com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.populateTunnelColumn(
                        blocks, null, new BlockPos(x, 0, z), shape, x, totalWidth, z, 1.0, 0.0, false, false);
            }
        }
    }

    private static void buildPreviewEmbankment(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            ModularRoadPreset preset,
            int totalWidth,
            int length
    ) {
        ModularRoadPreset.EmbankmentShape shape = preset.getEmbankmentShape();
        ModularRoadPreset.EmbankmentMaterial material = preset.getEmbankmentMaterial();
        int minY = -5;

        for (int z = 0; z < length; z++) {
            for (int x = 0; x < totalWidth; x++) {
                if (material != ModularRoadPreset.EmbankmentMaterial.EARTH) {
                    BlockState surf = blocks.get(new BlockPos(x, 0, z));
                    if (surf == null || !surf.is(Blocks.GRASS_BLOCK)) {
                        setBlock(blocks, x, -1, z, com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickEmbankmentBlock(
                                material, pack, x, -1, z, false));
                    }
                }
                for (int y = -2; y >= minY; y--) {
                    setBlock(blocks, x, y, z, com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickEmbankmentBlock(
                            material, pack, x, y, z, false));
                }
            }
        }

        if (shape == ModularRoadPreset.EmbankmentShape.WALL) {
            return;
        }

        int maxDz = (shape == ModularRoadPreset.EmbankmentShape.STEPPED_ONE) ? 5 : 6;
        for (int z = 0; z < length; z++) {
            for (int dz = 1; dz <= maxDz; dz++) {
                int topY = com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.computeEmbankmentTopY(shape, 0, dz, Double.MAX_VALUE);
                if (topY < minY) continue;

                int rx = totalWidth - 1 + dz;
                int lx = -dz;
                for (int y = topY; y >= minY; y--) {
                    boolean isTop = (y == topY);
                    setBlock(blocks, rx, y, z, com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickEmbankmentBlock(
                            material, pack, rx, y, z, isTop));
                    setBlock(blocks, lx, y, z, com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickEmbankmentBlock(
                            material, pack, lx, y, z, isTop));
                }
            }
        }
    }

    private static void buildPreviewSlope(
            Map<BlockPos, BlockState> blocks,
            ModularRoadPreset preset,
            int totalWidth,
            int length
    ) {
        ModularRoadPreset.SlopeStyle style = preset.getSlopeStyle();
        ModularRoadPreset.SlopeMaterial material = preset.getSlopeMaterial();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState stoneBase = Blocks.STONE.defaultBlockState();
        int targetTop = 10;

        for (int z = 0; z < length; z++) {
            for (int dz = 1; dz <= 10; dz++) {
                if (!com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.shouldStepSlopeOutward(style, 0, dz, targetTop)) {
                    break;
                }
                int stepTopY = com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.computeSlopeStepTopY(style, 0, dz, targetTop);
                boolean useCustom = (material != ModularRoadPreset.SlopeMaterial.WORLD)
                        && ((style == ModularRoadPreset.SlopeStyle.FLAT && dz == 1)
                        || (style == ModularRoadPreset.SlopeStyle.STAIR && dz <= 4 && stepTopY <= 7));

                int rx = totalWidth - 1 + dz;
                int lx = -dz;
                for (int y = 0; y <= stepTopY; y++) {
                    BlockState stateR = useCustom
                            ? com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickSlopeMaterialBlock(material, rx, y, z)
                            : ((y == stepTopY) ? grass : dirt);
                    BlockState stateL = useCustom
                            ? com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.pickSlopeMaterialBlock(material, lx, y, z)
                            : ((y == stepTopY) ? grass : dirt);
                    setBlock(blocks, rx, y, z, stateR);
                    setBlock(blocks, lx, y, z, stateL);
                }
                setBlock(blocks, rx, -1, z, stoneBase);
                setBlock(blocks, lx, -1, z, stoneBase);
            }
        }
    }

    private static void buildPreviewLamps(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            ModularRoadPreset preset,
            int totalWidth,
            int length
    ) {
        if (preset.getLampStyle() == ModularRoadPreset.LampStyle.OFF) {
            return;
        }
        StreetLampTemplate.LampPair lampPair = StreetLampTemplate.getForStyle(preset.getLampStyle(), pack);
        if (lampPair == null) {
            return;
        }
        List<StreetLampTemplate.StackLampSlot> slots = StreetLampTemplate.computeStackSlots(preset.getStack(), totalWidth);
        if (slots.isEmpty()) {
            return;
        }

        ModularRoadPreset.LampPlacement placement = preset.getLampPlacement();
        double minT = 4.0;
        double maxT = Math.max(minT + 1.0, length - 5.0);
        double spacing = preset.getLampSpacing();

        for (StreetLampTemplate.StackLampSlot slot : slots) {
            if (!slot.divider() && placement == ModularRoadPreset.LampPlacement.DIVIDER_ONLY) {
                continue;
            }
            boolean staggerHalf = !slot.divider()
                    && placement == ModularRoadPreset.LampPlacement.STAGGERED
                    && slot.rightSide();
            List<Double> stations = StreetLampTemplate.computeLampStations(minT, maxT, spacing, staggerHalf);
            StreetLampTemplate.LampModel model = slot.divider()
                    ? lampPair.doubleSided()
                    : lampPair.forSidewalk(preset.isSidewalkDoubleLamps());
            if (model == null) continue;

            double anchorX = totalWidth * 0.5 + slot.anchorOffset();
            for (double zDist : stations) {
                int bz = (int) Math.round(zDist);
                Map<BlockPos, BlockState> lampBlocks = model.sampleAt(anchorX, 0, bz + 0.5, slot.inwardSign(), 0.0);
                for (Map.Entry<BlockPos, BlockState> e : lampBlocks.entrySet()) {
                    setBlock(blocks, e.getKey().getX(), e.getKey().getY(), e.getKey().getZ(), e.getValue());
                }
            }
        }
    }

    private static void buildPreviewGuardrails(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            ModularRoadPreset preset,
            int totalWidth,
            int length,
            boolean viewTunnel
    ) {
        List<RoadGuardrailTemplate.GuardrailSlot> slots = RoadGuardrailTemplate.computeGuardrailSlots(preset);
        if (slots.isEmpty()) return;

        RoadGuardrailTemplate.GuardrailPattern noisePattern = preset.isNoiseWallEnabled()
                ? RoadGuardrailTemplate.getNoiseWall(pack)
                : null;

        for (RoadGuardrailTemplate.GuardrailSlot slot : slots) {
            RoadGuardrailTemplate.GuardrailPattern pattern = slot.isNoiseWall()
                    ? noisePattern
                    : RoadGuardrailTemplate.getForStyle(slot.style(), pack);
            if (pattern == null) continue;
            if (slot.isNoiseWall() && viewTunnel) continue;
            if (!slot.isNoiseWall() && viewTunnel && slot.outerBoundary()) continue;
            var rot = pattern.rotationForAlong(Direction.SOUTH);
            int baseX = (int) Math.floor(totalWidth * 0.5 + slot.anchorOffset());
            int stepInX = (slot.inwardSign() >= 0) ? 1 : -1;

            for (int z = 0; z < length; z++) {
                BlockPos lampPedestal = new BlockPos(baseX, 1, z);
                if (blocks.containsKey(lampPedestal)) {
                    continue;
                }
                for (RoadGuardrailTemplate.GuardrailVoxel v : pattern.getSlice(z)) {
                    int vx = baseX + v.relZ() * stepInX;
                    int vy = 1 + v.relY();
                    BlockPos vp = new BlockPos(vx, vy, z);
                    if (!blocks.containsKey(vp)) {
                        setBlock(blocks, vx, vy, z, v.state().rotate(rot));
                    }
                }
            }
        }
    }

    private static void buildPreviewGreenery(
            Map<BlockPos, BlockState> blocks,
            RoadPackRegistry.RoadPack pack,
            ModularRoadPreset preset,
            int totalWidth,
            int length
    ) {
        List<RoadGreeneryTemplate.GreenerySlot> slots = RoadGreeneryTemplate.computeGreenerySlots(preset);
        if (slots.isEmpty()) return;

        for (RoadGreeneryTemplate.GreenerySlot slot : slots) {
            boolean isTree = slot.style().isTree();
            int baseX = (int) Math.floor(totalWidth * 0.5 + slot.anchorOffset());
            int stepSpacing = isTree
                    ? Math.min(18, Math.max(6, preset.getTreeSpacing()))
                    : Math.max(slot.style() == ModularRoadPreset.GreeneryStyle.BUSH_WALL ? 6 : 3, preset.getBushSpacing());

            List<Double> stations = StreetLampTemplate.computeLampStations(
                    isTree ? 3.0 : 2.0,
                    length - 3.0,
                    stepSpacing,
                    isTree
            );

            for (double zDist : stations) {
                int bz = (int) Math.round(zDist);
                int shiftedZ = -1;
                for (int dzShift : (isTree ? new int[]{ 0, 2, -2 } : new int[]{ 0, 1, -1 })) {
                    int cz = bz + dzShift;
                    if (cz < 1 || cz >= length - 1) continue;
                    BlockState surf = blocks.get(new BlockPos(baseX, 0, cz));
                    if (surf == null || surf.isAir()
                            || surf.is(Blocks.GRAY_CONCRETE_POWDER)
                            || surf.is(Blocks.SMOOTH_QUARTZ)
                            || surf.getBlock() instanceof StairBlock) {
                        continue;
                    }
                    BlockState above = blocks.get(new BlockPos(baseX, 1, cz));
                    if (above == null || above.isAir()) {
                        shiftedZ = cz;
                        break;
                    }
                }
                if (shiftedZ < 0) continue;

                long seed = 31L * Double.doubleToLongBits(slot.anchorOffset())
                        ^ (31L * shiftedZ)
                        ^ ((long) baseX * 73856093L);
                ModularRoadPreset.GreeneryStyle concreteStyle = RoadGreeneryTemplate.resolveConcreteStyle(slot.style(), seed);
                RoadGreeneryTemplate.GreeneryPattern pattern = RoadGreeneryTemplate.getForStyle(concreteStyle, pack);
                if (pattern == null) continue;

                Rotation rot = isTree
                        ? RoadGreeneryTemplate.deterministicTreeRotation(seed)
                        : RoadGreeneryTemplate.rotationForAlong(Direction.SOUTH);

                BlockState baseSurf = blocks.get(new BlockPos(baseX, 0, shiftedZ));
                boolean onGrass = slot.isGrassElement() || (baseSurf != null && baseSurf.is(Blocks.GRASS_BLOCK));

                for (RoadGreeneryTemplate.GreeneryVoxel v : pattern.getVoxels()) {
                    if (isTree && onGrass && v.substratePad()) {
                        continue;
                    }
                    int rdx = RoadGreeneryTemplate.rotateDx(v.dx(), v.dz(), rot);
                    int rdz = RoadGreeneryTemplate.rotateDz(v.dx(), v.dz(), rot);
                    int wx = baseX + rdx;
                    int wy = v.dy();
                    int wz = shiftedZ + rdz;
                    if (wz < 0 || wz >= length) continue;

                    BlockPos wp = new BlockPos(wx, wy, wz);
                    BlockState existing = blocks.get(wp);
                    if (wy == 0) {
                        if (existing == null || existing.isAir()
                                || existing.is(Blocks.GRAY_CONCRETE_POWDER)
                                || existing.is(Blocks.SMOOTH_QUARTZ)
                                || existing.getBlock() instanceof StairBlock) {
                            continue;
                        }
                        if (isTree && v.substratePad() && existing.is(Blocks.GRASS_BLOCK)) {
                            continue;
                        }
                    } else if (existing != null && !existing.isAir()) {
                        if (!(existing.is(BlockTags.LEAVES) && !v.state().is(BlockTags.LEAVES))) {
                            continue;
                        }
                    }
                    setBlock(blocks, wx, wy, wz, v.state().rotate(rot));
                }
            }
        }
    }

    public Map<BlockPos, BlockState> getBlocks() {
        return blocks;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return 256;
    }

    @Override
    public int getMinBuildHeight() {
        return -64;
    }
}
