package com.roadbuilder.mod.client.studio;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Metaform-style 3D studio preview renderer adapted to EasyRoads' visual theme:
 * - Smooth dark slate-navy studio backdrop gradient
 * - Tiled 8x8 ground plane texture (1 tile = 1 block) within a 25-block radius,
 *   smoothly fading out into the backdrop via a circular radial alpha gradient
 * - Multi-layered soft contact drop shadow beneath the structure
 * - Subtle forward-direction indicator on the ground plane
 * - Baked, face-culled, directionally-shaded 3D block mesh
 */
public final class StudioPreviewRenderer {

    private static final ResourceLocation GROUND_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            RoadBuilderMod.MOD_ID,
            "textures/gui/studio_ground.png"
    );

    // EasyRoads-themed studio backdrop gradient
    private static final int BG_TOP = 0xFF1A2236;
    private static final int BG_BOTTOM = 0xFF0B0F1A;

    private static final int ARROW_RGB = 0x66B3FF;
    private static final int SHADOW_RGB = 0x00030A;

    // Ground disc radius (in blocks) and start of wide transparent fade-out ring
    private static final float GROUND_RADIUS = 46.0f;
    private static final float FADE_START = 14.0f;

    // 4 deterministic 90-degree UV rotations (0, 90, 180, 270 deg) to break tile repetition
    private static final float[][] ROT_U = {
            {0.0f, 0.0f, 1.0f, 1.0f},
            {0.0f, 1.0f, 1.0f, 0.0f},
            {1.0f, 1.0f, 0.0f, 0.0f},
            {1.0f, 0.0f, 0.0f, 1.0f}
    };
    private static final float[][] ROT_V = {
            {0.0f, 1.0f, 1.0f, 0.0f},
            {1.0f, 1.0f, 0.0f, 0.0f},
            {1.0f, 0.0f, 0.0f, 1.0f},
            {0.0f, 0.0f, 1.0f, 1.0f}
    };

    private float yaw = -36.0f;
    private float pitch = 26.0f;
    private float zoom = 14.0f;
    private float panX = 0.0f;
    private float panY = 0.0f;

    private int vpX;
    private int vpY;
    private int vpW;
    private int vpH;
    private float vpCenterX;
    private float vpCenterY;

    private boolean needsFit = true;
    private boolean dirty = true;
    private RoadPreviewMesh mesh;

    public void markDirty() {
        this.dirty = true;
    }

    public void setViewport(int x, int y, int w, int h) {
        this.vpX = x;
        this.vpY = y;
        this.vpW = Math.max(1, w);
        this.vpH = Math.max(1, h);
        this.vpCenterX = x + this.vpW * 0.5f;
        this.vpCenterY = y + this.vpH * 0.5f;
    }

    public boolean isInViewport(double mouseX, double mouseY) {
        return mouseX >= vpX && mouseX <= vpX + vpW && mouseY >= vpY && mouseY <= vpY + vpH;
    }

    public void rotate(float dragX, float dragY) {
        this.yaw = wrapAngle(this.yaw + dragX * 0.5f);
        this.pitch = Math.max(-10.0f, Math.min(89.0f, this.pitch + dragY * 0.5f));
    }

    public void pan(float dragX, float dragY) {
        this.panX += dragX;
        this.panY += dragY;
    }

    public void zoomAt(double scrollDelta, float mouseX, float mouseY) {
        if (scrollDelta == 0) return;
        float factor = (float) Math.pow(1.15, scrollDelta);
        float newZoom = Math.max(3.0f, Math.min(90.0f, this.zoom * factor));
        float ratio = newZoom / this.zoom;

        float relX = mouseX - vpCenterX;
        float relY = mouseY - vpCenterY;
        this.panX = relX - (relX - this.panX) * ratio;
        this.panY = relY - (relY - this.panY) * ratio;
        this.zoom = newZoom;
    }

    public void fit() {
        if (this.mesh == null || this.mesh.isEmpty() || this.vpW <= 0 || this.vpH <= 0) {
            return;
        }
        float radius = Math.max(1.0f, this.mesh.radius());
        this.zoom = Math.min(this.vpW, this.vpH) * 0.86f / (2.0f * radius);
        this.zoom = Math.max(4.0f, Math.min(80.0f, this.zoom));
        this.panX = 0.0f;
        this.panY = 0.0f;
        this.needsFit = false;
    }

    public void resetView() {
        this.yaw = -36.0f;
        this.pitch = 26.0f;
        fit();
    }

    public void render(
            GuiGraphics guiGraphics,
            int screenW,
            int screenH,
            ModularRoadPreset preset,
            RoadPackRegistry.RoadPack pack,
            boolean viewRoad,
            boolean viewViaduct,
            boolean viewTunnel,
            boolean viewEmbankment,
            boolean viewSlope
    ) {
        // 1. Ensure baked mesh is up-to-date
        if (this.dirty || this.mesh == null) {
            RoadPreviewScene scene = RoadPreviewScene.build(
                    preset, pack, viewRoad, viewViaduct, viewTunnel, viewEmbankment, viewSlope
            );
            this.mesh = RoadPreviewMesh.bake(scene);
            this.dirty = false;
        }

        if (this.needsFit && this.mesh != null && !this.mesh.isEmpty()) {
            fit();
        }

        // 2. Draw studio background gradient across full screen behind floating panels
        guiGraphics.fillGradient(0, 0, screenW, screenH, BG_TOP, BG_BOTTOM);

        if (this.mesh == null || this.mesh.isEmpty()) {
            return;
        }

        // Flush 2D backdrop before drawing 3D ground plane + 3D blocks
        guiGraphics.flush();

        // 3. Transform into 3D preview space (Z = 350, bounded below UI panels at Z = 800).
        // Negative Y scale cancels Minecraft GUI's Y-down ortho projection reflection so front faces stay CCW.
        float effectiveRadius = Math.max(this.mesh.radius(), GROUND_RADIUS + 2.0f);
        float zScale = Math.min(1.0f, 300.0f / (effectiveRadius * this.zoom));

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        poseStack.translate(vpCenterX + panX, vpCenterY + panY, 350.0f);
        poseStack.scale(zoom, -zoom, zoom * zScale);
        poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(-mesh.centerX(), -mesh.centerY(), -mesh.centerZ());

        // 4. Draw tiled ground plane (radius 25 blocks, 1 tile = 1 block) + contact shadow + arrow
        drawTexturedGroundAndShadow(poseStack);

        // 5. Draw baked 3D block mesh on top of ground and shadow
        mesh.emit(poseStack, guiGraphics.bufferSource());

        poseStack.popPose();
    }

    private void drawTexturedGroundAndShadow(PoseStack poseStack) {
        float groundY = (float) mesh.minY;

        float x0 = (float) mesh.minX;
        float z0 = (float) mesh.minZ;
        float x1 = (float) (mesh.maxX + 1);
        float z1 = (float) (mesh.maxZ + 1);

        float cx = (x0 + x1) * 0.5f;
        float cz = (z0 + z1) * 0.5f;
        float maxSpan = Math.max(x1 - x0, z1 - z0);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // Slightly fade the ground plane when tilting below horizon so the road underside stays readable
        float pitchVis = (pitch < 0.0f) ? Math.max(0.25f, 1.0f + pitch / 12.0f) : 1.0f;

        float prevFogStart = RenderSystem.getShaderFogStart();
        float prevFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);

        try {
            // 1. Tiled 8x8 ground plane (1 tile = 1x1 block) with radial transparent gradient.
            // Use rendertype_entity_shadow shader because it has NO alpha < 0.1 discard cutoff,
            // allowing the alpha fade to reach 0.0 seamlessly without a visible outer ring.
            RenderSystem.setShaderTexture(0, GROUND_TEXTURE);
            RenderSystem.setShader(GameRenderer::getRendertypeEntityShadowShader);

            BufferBuilder texBuf = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.NEW_ENTITY
            );

            int minTileX = (int) Math.floor(cx - GROUND_RADIUS);
            int maxTileX = (int) Math.ceil(cx + GROUND_RADIUS);
            int minTileZ = (int) Math.floor(cz - GROUND_RADIUS);
            int maxTileZ = (int) Math.ceil(cz + GROUND_RADIUS);

            for (int tx = minTileX; tx < maxTileX; tx++) {
                float xNext = tx + 1.0f;
                for (int tz = minTileZ; tz < maxTileZ; tz++) {
                    float zNext = tz + 1.0f;

                    float a00 = radialAlpha(tx - cx, tz - cz) * pitchVis;
                    float a01 = radialAlpha(tx - cx, zNext - cz) * pitchVis;
                    float a11 = radialAlpha(xNext - cx, zNext - cz) * pitchVis;
                    float a10 = radialAlpha(xNext - cx, tz - cz) * pitchVis;

                    if (a00 <= 0.002f && a01 <= 0.002f && a11 <= 0.002f && a10 <= 0.002f) {
                        continue;
                    }

                    int rot = tileRotation(tx, tz);
                    float[] u = ROT_U[rot];
                    float[] v = ROT_V[rot];

                    texBuf.addVertex(matrix, tx, groundY, tz)
                            .setColor(1.0f, 1.0f, 1.0f, a00)
                            .setUv(u[0], v[0])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(pose, 0.0f, 1.0f, 0.0f);
                    texBuf.addVertex(matrix, tx, groundY, zNext)
                            .setColor(1.0f, 1.0f, 1.0f, a01)
                            .setUv(u[1], v[1])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(pose, 0.0f, 1.0f, 0.0f);
                    texBuf.addVertex(matrix, xNext, groundY, zNext)
                            .setColor(1.0f, 1.0f, 1.0f, a11)
                            .setUv(u[2], v[2])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(pose, 0.0f, 1.0f, 0.0f);
                    texBuf.addVertex(matrix, xNext, groundY, tz)
                            .setColor(1.0f, 1.0f, 1.0f, a10)
                            .setUv(u[3], v[3])
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(LightTexture.FULL_BRIGHT)
                            .setNormal(pose, 0.0f, 1.0f, 0.0f);
                }
            }

            MeshData texMesh = texBuf.build();
            if (texMesh != null) {
                BufferUploader.drawWithShader(texMesh);
            }

            // 2. Multi-layered soft contact shadow + front direction arrow on top of the ground plane
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder colBuf = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_COLOR
            );

            int sr = (SHADOW_RGB >>> 16) & 0xFF;
            int sg = (SHADOW_RGB >>> 8) & 0xFF;
            int sb = SHADOW_RGB & 0xFF;

            for (int i = 3; i >= 1; i--) {
                float spread = i * 0.9f;
                float offset = 1.2f;
                int alpha = Math.round((140.0f / (i + 1)) * pitchVis);

                float sx0 = x0 - spread + offset;
                float sx1 = x1 + spread + offset;
                float sz0 = z0 - spread + offset;
                float sz1 = z1 + spread + offset;

                colBuf.addVertex(matrix, sx0, groundY, sz0).setColor(sr, sg, sb, alpha);
                colBuf.addVertex(matrix, sx0, groundY, sz1).setColor(sr, sg, sb, alpha);
                colBuf.addVertex(matrix, sx1, groundY, sz1).setColor(sr, sg, sb, alpha);
                colBuf.addVertex(matrix, sx1, groundY, sz0).setColor(sr, sg, sb, alpha);
            }

            // Front direction arrow
            float arrowLen = Math.max(3.5f, Math.min(6.0f, maxSpan * 0.22f));
            float arrowBaseZ = z0 - 1.8f;
            float arrowTipZ = arrowBaseZ - arrowLen;
            float arrowShoulderZ = arrowTipZ + 2.0f;

            int ar = (ARROW_RGB >>> 16) & 0xFF;
            int ag = (ARROW_RGB >>> 8) & 0xFF;
            int ab = ARROW_RGB & 0xFF;
            int aa = Math.round(160.0f * pitchVis);

            // Stem
            colBuf.addVertex(matrix, cx - 0.55f, groundY, arrowBaseZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx - 0.55f, groundY, arrowShoulderZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx + 0.55f, groundY, arrowShoulderZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx + 0.55f, groundY, arrowBaseZ).setColor(ar, ag, ab, aa);

            // Arrowhead triangle
            colBuf.addVertex(matrix, cx - 1.6f, groundY, arrowShoulderZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx, groundY, arrowTipZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx, groundY, arrowTipZ).setColor(ar, ag, ab, aa);
            colBuf.addVertex(matrix, cx + 1.6f, groundY, arrowShoulderZ).setColor(ar, ag, ab, aa);

            MeshData colMesh = colBuf.build();
            if (colMesh != null) {
                BufferUploader.drawWithShader(colMesh);
            }
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.setShaderFogStart(prevFogStart);
            RenderSystem.setShaderFogEnd(prevFogEnd);
        }
    }

    private static int tileRotation(int tx, int tz) {
        int h = tx * 374761393 + tz * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return h & 3;
    }

    private static float radialAlpha(float dx, float dz) {
        float dist = (float) Math.hypot(dx, dz);
        if (dist <= FADE_START) {
            return 1.0f;
        }
        if (dist >= GROUND_RADIUS) {
            return 0.0f;
        }
        float t = (GROUND_RADIUS - dist) / (GROUND_RADIUS - FADE_START);
        float s = t * t * (3.0f - 2.0f * t);
        // Gamma-compensated smooth tail so the outer edge dissolves invisibly into the dark backdrop
        return (float) Math.pow(s, 2.2);
    }

    private static float wrapAngle(float deg) {
        float wrapped = deg % 360.0f;
        if (wrapped > 180.0f) wrapped -= 360.0f;
        if (wrapped < -180.0f) wrapped += 360.0f;
        return wrapped;
    }
}
