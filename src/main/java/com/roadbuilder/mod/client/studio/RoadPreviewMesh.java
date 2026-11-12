package com.roadbuilder.mod.client.studio;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Baked 3D preview mesh with neighbor face culling, biome-neutral BlockColors tinting,
 * directional face shading (1.0 / 0.8 / 0.6 / 0.5), and batched RenderType layers.
 */
public final class RoadPreviewMesh {

    private record BakedEntry(int x, int y, int z, BakedQuad quad, float r, float g, float b) {}

    private final List<BakedEntry> solidQuads;
    private final List<BakedEntry> cutoutQuads;
    private final List<BakedEntry> translucentQuads;

    public final int minX;
    public final int minY;
    public final int minZ;
    public final int maxX;
    public final int maxY;
    public final int maxZ;

    private RoadPreviewMesh(
            List<BakedEntry> solidQuads,
            List<BakedEntry> cutoutQuads,
            List<BakedEntry> translucentQuads,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ
    ) {
        this.solidQuads = solidQuads;
        this.cutoutQuads = cutoutQuads;
        this.translucentQuads = translucentQuads;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static RoadPreviewMesh bake(RoadPreviewScene scene) {
        List<BakedEntry> solid = new ArrayList<>();
        List<BakedEntry> cutout = new ArrayList<>();
        List<BakedEntry> translucent = new ArrayList<>();

        if (scene == null || scene.isEmpty()) {
            return new RoadPreviewMesh(solid, cutout, translucent, 0, 0, 0, 0, 0, 0);
        }

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        BlockColors blockColors = mc.getBlockColors();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();

        for (Map.Entry<BlockPos, BlockState> entry : scene.getBlocks().entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (state.isAir()) continue;

            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();

            BakedModel model = dispatcher.getBlockModel(state);
            long seed = state.getSeed(pos);
            random.setSeed(seed);
            ChunkRenderTypeSet renderTypes = model.getRenderTypes(state, random, ModelData.EMPTY);

            if (renderTypes.isEmpty()) {
                collectQuadsForLayer(scene, pos, neighborPos, state, model, null, RenderType.solid(), blockColors, random, seed, solid, cutout, translucent);
            } else {
                for (RenderType rt : renderTypes) {
                    collectQuadsForLayer(scene, pos, neighborPos, state, model, rt, rt, blockColors, random, seed, solid, cutout, translucent);
                }
            }
        }

        return new RoadPreviewMesh(
                solid, cutout, translucent,
                scene.getMinX(), scene.getMinY(), scene.getMinZ(),
                scene.getMaxX(), scene.getMaxY(), scene.getMaxZ()
        );
    }

    private static void collectQuadsForLayer(
            RoadPreviewScene scene,
            BlockPos pos,
            BlockPos.MutableBlockPos neighborPos,
            BlockState state,
            BakedModel model,
            RenderType queryLayer,
            RenderType targetLayer,
            BlockColors blockColors,
            RandomSource random,
            long seed,
            List<BakedEntry> solid,
            List<BakedEntry> cutout,
            List<BakedEntry> translucent
    ) {
        List<BakedEntry> bucket = selectBucket(targetLayer, solid, cutout, translucent);
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        for (Direction dir : Direction.values()) {
            neighborPos.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
            if (!Block.shouldRenderFace(state, scene, pos, dir, neighborPos)) {
                continue;
            }
            random.setSeed(seed);
            List<BakedQuad> quads = model.getQuads(state, dir, random, ModelData.EMPTY, queryLayer);
            appendQuads(x, y, z, state, quads, blockColors, bucket);
        }

        random.setSeed(seed);
        List<BakedQuad> unculled = model.getQuads(state, null, random, ModelData.EMPTY, queryLayer);
        appendQuads(x, y, z, state, unculled, blockColors, bucket);
    }

    private static List<BakedEntry> selectBucket(
            RenderType layer,
            List<BakedEntry> solid,
            List<BakedEntry> cutout,
            List<BakedEntry> translucent
    ) {
        if (layer == RenderType.translucent()) {
            return translucent;
        }
        if (layer == RenderType.cutout() || layer == RenderType.cutoutMipped()) {
            return cutout;
        }
        return solid;
    }

    private static void appendQuads(
            int x, int y, int z,
            BlockState state,
            List<BakedQuad> quads,
            BlockColors blockColors,
            List<BakedEntry> bucket
    ) {
        for (BakedQuad quad : quads) {
            int rgb = 0xFFFFFF;
            if (quad.isTinted()) {
                int tint = blockColors.getColor(state, null, null, quad.getTintIndex());
                if (tint != -1) {
                    rgb = tint & 0xFFFFFF;
                }
            }

            float shade = quad.isShade() ? faceShade(quad.getDirection()) : 1.0f;
            float r = ((rgb >> 16) & 0xFF) / 255.0f * shade;
            float g = ((rgb >> 8) & 0xFF) / 255.0f * shade;
            float b = (rgb & 0xFF) / 255.0f * shade;

            bucket.add(new BakedEntry(x, y, z, quad, r, g, b));
        }
    }

    private static float faceShade(Direction dir) {
        if (dir == null) {
            return 1.0f;
        }
        return switch (dir) {
            case DOWN -> 0.5f;
            case UP -> 1.0f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    public boolean isEmpty() {
        return solidQuads.isEmpty() && cutoutQuads.isEmpty() && translucentQuads.isEmpty();
    }

    public float centerX() {
        return (minX + maxX + 1) * 0.5f;
    }

    public float centerY() {
        return (minY + maxY + 1) * 0.5f;
    }

    public float centerZ() {
        return (minZ + maxZ + 1) * 0.5f;
    }

    public float radius() {
        float dx = maxX - minX + 1;
        float dy = maxY - minY + 1;
        float dz = maxZ - minZ + 1;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
    }

    public void emit(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource) {
        if (isEmpty()) return;

        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);

        try {
            emitBucket(poseStack, bufferSource, RenderType.solid(), solidQuads);
            emitBucket(poseStack, bufferSource, RenderType.cutoutMipped(), cutoutQuads);
            emitBucket(poseStack, bufferSource, RenderType.translucent(), translucentQuads);
        } finally {
            RenderSystem.disableDepthTest();
            RenderSystem.setShaderFogStart(prevFogStart);
            RenderSystem.setShaderFogEnd(prevFogEnd);
        }
    }

    private static void emitBucket(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            RenderType layer,
            List<BakedEntry> entries
    ) {
        if (entries.isEmpty()) return;
        VertexConsumer consumer = bufferSource.getBuffer(layer);
        for (BakedEntry e : entries) {
            poseStack.pushPose();
            poseStack.translate(e.x, e.y, e.z);
            consumer.putBulkData(
                    poseStack.last(),
                    e.quad,
                    e.r, e.g, e.b, 1.0f,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY,
                    true
            );
            poseStack.popPose();
        }
        bufferSource.endBatch(layer);
    }
}
