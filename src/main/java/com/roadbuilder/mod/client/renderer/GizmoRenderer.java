package com.roadbuilder.mod.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.client.editmode.EditModeManager;
import com.roadbuilder.mod.client.editmode.EditTool;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders 3D gizmos, node markers, multi-point chain markers,
 * and translucent textured phantom blocks preview for road construction (Axiom / Litematica style).
 */
public final class GizmoRenderer {

    private static final RandomSource RANDOM = RandomSource.create();

    private GizmoRenderer() {}

    public static void onRenderLevelStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        if (!EditModeManager.getInstance().isEditModeActive()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camPos = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Render all lines and wireframes
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        renderExistingGraph(poseStack, lines);

        // Render point chain markers
        List<Vector3d> chainedPts = EditModeManager.getInstance().getChainedPoints();
        int selectedIdx = EditModeManager.getInstance().getSelectedPointIndex();
        EditTool currentTool = EditModeManager.getInstance().getCurrentTool();

        double surfaceElevation = 1.02;

        for (int i = 0; i < chainedPts.size(); i++) {
            Vector3d pt = chainedPts.get(i);
            boolean isSelected = (i == selectedIdx);
            double boxSize = isSelected ? 0.35 : 0.25;
            double baseY = pt.y + surfaceElevation;
            AABB ptBox = new AABB(
                    pt.x - boxSize, baseY, pt.z - boxSize,
                    pt.x + boxSize, baseY + (isSelected ? 0.7 : 0.5), pt.z + boxSize
            );

            if (isSelected) {
                // Bright Yellow/Gold box with thick visual presence for selected point
                LevelRenderer.renderLineBox(poseStack, lines, ptBox, 1.0f, 0.95f, 0.1f, 1.0f);

                // Render 3D Axis Gizmo arrows (Axiom style) whenever a point is selected
                renderAxisGizmo(poseStack, lines, pt.add(0, surfaceElevation, 0));
            } else if (i == 0) {
                // Cyan for first point
                LevelRenderer.renderLineBox(poseStack, lines, ptBox, 0.2f, 0.8f, 1.0f, 1.0f);
            } else {
                // Orange for intermediate points
                LevelRenderer.renderLineBox(poseStack, lines, ptBox, 1.0f, 0.5f, 0.0f, 1.0f);
            }
        }

        // Render ribbon lines for preview curves
        List<BezierCurve3D> previewCurves = ClientGraphCache.getInstance().getPreviewCurves();
        if (!previewCurves.isEmpty()) {
            RoadProfile profile = ClientGraphCache.getInstance().getActiveProfile();
            for (BezierCurve3D preview : previewCurves) {
                renderRoadRibbon(poseStack, lines, preview, profile);
            }
        }
        bufferSource.endBatch(RenderType.lines());

        // 2. Render translucent textured phantom blocks in their own dedicated batch
        if (!previewCurves.isEmpty()) {
            renderTexturedPhantomBlocks(mc, poseStack, bufferSource, previewCurves);
            bufferSource.endBatch(RenderType.translucent());
        }

        poseStack.popPose();
    }

    private static void renderExistingGraph(PoseStack poseStack, VertexConsumer lines) {
        ClientGraphCache cache = ClientGraphCache.getInstance();
        UUID hoveredId = cache.getHoveredNodeId();

        // Render Nodes (elevated above road surface)
        for (RoadNode node : cache.getGraph().getNodes().values()) {
            Vector3d p = node.getPosition();
            AABB nodeBox = new AABB(p.x - 0.3, p.y + 1.02, p.z - 0.3, p.x + 0.3, p.y + 1.62, p.z + 0.3);

            boolean isHovered = node.getId().equals(hoveredId);
            if (isHovered) {
                LevelRenderer.renderLineBox(poseStack, lines, nodeBox, 0.0f, 1.0f, 0.0f, 1.0f); // Bright Green
            } else {
                LevelRenderer.renderLineBox(poseStack, lines, nodeBox, 0.0f, 0.8f, 1.0f, 0.8f); // Cyan
            }
        }

        // Render Edges
        for (RoadEdge edge : cache.getGraph().getEdges().values()) {
            renderCurveLine(poseStack, lines, edge.getCurve(), 0.0f, 0.8f, 1.0f, 0.6f);
        }
    }

    /**
     * Renders textured translucent phantom blocks like Axiom / Litematica using Minecraft's BlockRenderDispatcher.
     * Accurately displays the entire road, including embankments, viaduct pillars, and slopes in the in-world preview!
     * Cached and culled for optimal FPS (recalculated only when previewDirty == true).
     */
    private static void renderTexturedPhantomBlocks(Minecraft mc, PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                                   List<BezierCurve3D> curves) {
        ClientGraphCache cache = ClientGraphCache.getInstance();
        Map<BlockPos, BlockState> blocksToRender;

        if (cache.isPreviewDirty()) {
            blocksToRender = new HashMap<>();
            ModularRoadPreset preset = PresetManager.getInstance().getActivePreset();
            for (BezierCurve3D curve : curves) {
                Map<BlockPos, BlockState> curveBlocks = com.roadbuilder.mod.server.voxelizer.RoadVoxelizer.calculateRoadBlocks(
                        mc.level, curve, preset, cache.getGraph()
                );
                blocksToRender.putAll(curveBlocks);
            }
            cache.setCachedPreviewBlocks(blocksToRender);
        } else {
            blocksToRender = cache.getCachedPreviewBlocks();
        }

        if (blocksToRender.isEmpty()) return;

        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        VertexConsumer translucentBuffer = bufferSource.getBuffer(RenderType.translucent());

        for (Map.Entry<BlockPos, BlockState> entry : blocksToRender.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState stateToRender = entry.getValue();

            poseStack.pushPose();
            poseStack.translate(pos.getX(), pos.getY(), pos.getZ());

            BakedModel model = dispatcher.getBlockModel(stateToRender);
            renderModelTranslucent(model, stateToRender, poseStack, translucentBuffer, 0.65f, pos, blocksToRender);

            poseStack.popPose();
        }
    }

    private static void renderModelTranslucent(BakedModel model, BlockState state, PoseStack poseStack, VertexConsumer buffer, float alpha,
                                               BlockPos pos, Map<BlockPos, BlockState> blockMap) {
        PoseStack.Pose lastPose = poseStack.last();
        for (Direction direction : Direction.values()) {
            // Neighbor culling: check if neighbor in this direction is in preview blocks and opaque
            BlockState neighbor = blockMap.get(pos.relative(direction));
            if (neighbor != null && neighbor.canOcclude() && !(neighbor.getBlock() instanceof StairBlock)) {
                continue;
            }

            RANDOM.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(state, direction, RANDOM, ModelData.EMPTY, null);
            renderQuadsTranslucent(lastPose, buffer, quads, alpha);
        }

        RANDOM.setSeed(42L);
        List<BakedQuad> unculled = model.getQuads(state, null, RANDOM, ModelData.EMPTY, null);
        renderQuadsTranslucent(lastPose, buffer, unculled, alpha);
    }

    private static void renderQuadsTranslucent(PoseStack.Pose pose, VertexConsumer buffer, List<BakedQuad> quads, float alpha) {
        for (BakedQuad quad : quads) {
            buffer.putBulkData(pose, quad, 1.0f, 1.0f, 1.0f, alpha, 0xF000F0, 0, true);
        }
    }

    private static Direction getHorizontalDirection(Vector3d vec) {
        if (Math.abs(vec.x) > Math.abs(vec.z)) {
            return vec.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return vec.z > 0 ? Direction.SOUTH : Direction.NORTH;
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

    private static void renderRoadRibbon(PoseStack poseStack, VertexConsumer lines, BezierCurve3D curve, RoadProfile profile) {
        double length = curve.getApproximateLength();
        if (length < 0.5) return;

        int samples = Math.max(8, (int) (length * 2.0));
        Vector3d prevCenter = curve.evaluate(0.0).add(0, 1.02, 0);

        for (int i = 1; i <= samples; i++) {
            double distance = (double) i / samples * length;
            double t = curve.getArcLengthTable().getTForDistance(distance);
            Vector3d currCenter = curve.evaluate(t).add(0, 1.02, 0);

            // Clean, elegant centerline guide (Cyan)
            drawLine(poseStack, lines, prevCenter, currCenter, 0.2f, 1.0f, 1.0f, 1.0f);

            prevCenter = currCenter;
        }
    }

    private static void renderCurveLine(PoseStack poseStack, VertexConsumer lines, BezierCurve3D curve,
                                        float r, float g, float b, float a) {
        double length = curve.getApproximateLength();
        int samples = Math.max(6, (int) length);
        Vector3d prev = curve.evaluate(0.0).add(0, 1.02, 0);

        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            Vector3d curr = curve.evaluate(t).add(0, 1.02, 0);
            drawLine(poseStack, lines, prev, curr, r, g, b, a);
            prev = curr;
        }
    }

    private static void drawLine(PoseStack poseStack, VertexConsumer lines, Vector3d p1, Vector3d p2,
                                 float r, float g, float b, float a) {
        Vector3d dir = p2.subtract(p1).normalize();
        float nx = (float) dir.x;
        float ny = (float) dir.y;
        float nz = (float) dir.z;

        lines.addVertex(poseStack.last().pose(), (float) p1.x, (float) p1.y, (float) p1.z)
                .setColor(r, g, b, a)
                .setNormal(poseStack.last(), nx, ny, nz);

        lines.addVertex(poseStack.last().pose(), (float) p2.x, (float) p2.y, (float) p2.z)
                .setColor(r, g, b, a)
                .setNormal(poseStack.last(), nx, ny, nz);
    }

    /**
     * Renders Axiom-style 3D axis transform gizmo arrows at the selected point:
     * - Red arrow (+X East, subtle -X West)
     * - Green arrow (+Y Up, subtle -Y Down)
     * - Blue arrow (+Z South, subtle -Z North)
     */
     private static void renderAxisGizmo(PoseStack poseStack, VertexConsumer lines, Vector3d origin) {
        double axisLen = 2.5;
        double headSize = 0.4;
        double headWidth = 0.2;

        Vector3d center = origin.add(0, 0.25, 0);

        // Faint negative axis guides so user sees the full coordinate plane
        drawLine(poseStack, lines, center, center.add(-axisLen * 0.4, 0, 0), 1.0f, 0.2f, 0.2f, 0.35f);
        drawLine(poseStack, lines, center, center.add(0, -axisLen * 0.4, 0), 0.2f, 1.0f, 0.2f, 0.35f);
        drawLine(poseStack, lines, center, center.add(0, 0, -axisLen * 0.4), 0.2f, 0.5f, 1.0f, 0.35f);

        // 1. X-Axis (Red: +X)
        Vector3d xEnd = center.add(axisLen, 0, 0);
        drawLine(poseStack, lines, center, xEnd, 1.0f, 0.15f, 0.15f, 1.0f);
        // Arrow head X
        Vector3d xBase = xEnd.add(-headSize, 0, 0);
        drawLine(poseStack, lines, xEnd, xBase.add(0, headWidth, 0), 1.0f, 0.2f, 0.2f, 1.0f);
        drawLine(poseStack, lines, xEnd, xBase.add(0, -headWidth, 0), 1.0f, 0.2f, 0.2f, 1.0f);
        drawLine(poseStack, lines, xEnd, xBase.add(0, 0, headWidth), 1.0f, 0.2f, 0.2f, 1.0f);
        drawLine(poseStack, lines, xEnd, xBase.add(0, 0, -headWidth), 1.0f, 0.2f, 0.2f, 1.0f);
        // Base ring for arrow head
        drawLine(poseStack, lines, xBase.add(0, headWidth, 0), xBase.add(0, 0, headWidth), 1.0f, 0.2f, 0.2f, 0.8f);
        drawLine(poseStack, lines, xBase.add(0, 0, headWidth), xBase.add(0, -headWidth, 0), 1.0f, 0.2f, 0.2f, 0.8f);
        drawLine(poseStack, lines, xBase.add(0, -headWidth, 0), xBase.add(0, 0, -headWidth), 1.0f, 0.2f, 0.2f, 0.8f);
        drawLine(poseStack, lines, xBase.add(0, 0, -headWidth), xBase.add(0, headWidth, 0), 1.0f, 0.2f, 0.2f, 0.8f);

        // 2. Y-Axis (Green: +Y)
        Vector3d yEnd = center.add(0, axisLen, 0);
        drawLine(poseStack, lines, center, yEnd, 0.15f, 1.0f, 0.15f, 1.0f);
        // Arrow head Y
        Vector3d yBase = yEnd.add(0, -headSize, 0);
        drawLine(poseStack, lines, yEnd, yBase.add(headWidth, 0, 0), 0.2f, 1.0f, 0.2f, 1.0f);
        drawLine(poseStack, lines, yEnd, yBase.add(-headWidth, 0, 0), 0.2f, 1.0f, 0.2f, 1.0f);
        drawLine(poseStack, lines, yEnd, yBase.add(0, 0, headWidth), 0.2f, 1.0f, 0.2f, 1.0f);
        drawLine(poseStack, lines, yEnd, yBase.add(0, 0, -headWidth), 0.2f, 1.0f, 0.2f, 1.0f);
        // Base ring for arrow head
        drawLine(poseStack, lines, yBase.add(headWidth, 0, 0), yBase.add(0, 0, headWidth), 0.2f, 1.0f, 0.2f, 0.8f);
        drawLine(poseStack, lines, yBase.add(0, 0, headWidth), yBase.add(-headWidth, 0, 0), 0.2f, 1.0f, 0.2f, 0.8f);
        drawLine(poseStack, lines, yBase.add(-headWidth, 0, 0), yBase.add(0, 0, -headWidth), 0.2f, 1.0f, 0.2f, 0.8f);
        drawLine(poseStack, lines, yBase.add(0, 0, -headWidth), yBase.add(headWidth, 0, 0), 0.2f, 1.0f, 0.2f, 0.8f);

        // 3. Z-Axis (Blue: +Z)
        Vector3d zEnd = center.add(0, 0, axisLen);
        drawLine(poseStack, lines, center, zEnd, 0.2f, 0.55f, 1.0f, 1.0f);
        // Arrow head Z
        Vector3d zBase = zEnd.add(0, 0, -headSize);
        drawLine(poseStack, lines, zEnd, zBase.add(headWidth, 0, 0), 0.2f, 0.55f, 1.0f, 1.0f);
        drawLine(poseStack, lines, zEnd, zBase.add(-headWidth, 0, 0), 0.2f, 0.55f, 1.0f, 1.0f);
        drawLine(poseStack, lines, zEnd, zBase.add(0, headWidth, 0), 0.2f, 0.55f, 1.0f, 1.0f);
        drawLine(poseStack, lines, zEnd, zBase.add(0, -headWidth, 0), 0.2f, 0.55f, 1.0f, 1.0f);
        // Base ring for arrow head
        drawLine(poseStack, lines, zBase.add(headWidth, 0, 0), zBase.add(0, headWidth, 0), 0.2f, 0.55f, 1.0f, 0.8f);
        drawLine(poseStack, lines, zBase.add(0, headWidth, 0), zBase.add(-headWidth, 0, 0), 0.2f, 0.55f, 1.0f, 0.8f);
        drawLine(poseStack, lines, zBase.add(-headWidth, 0, 0), zBase.add(0, -headWidth, 0), 0.2f, 0.55f, 1.0f, 0.8f);
        drawLine(poseStack, lines, zBase.add(0, -headWidth, 0), zBase.add(headWidth, 0, 0), 0.2f, 0.55f, 1.0f, 0.8f);
    }
}
