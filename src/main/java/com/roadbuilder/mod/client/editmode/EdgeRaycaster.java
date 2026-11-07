package com.roadbuilder.mod.client.editmode;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Client raycaster for detecting road edges under the player's crosshair.
 */
public final class EdgeRaycaster {

    public record EdgeHitResult(
            UUID edgeId,
            RoadEdge edge,
            double t,
            double lateralDistance,
            Vector3d hitPoint
    ) {}

    private EdgeRaycaster() {}

    /**
     * Finds the road edge nearest to the player's look ray.
     */
    public static Optional<EdgeHitResult> findEdgeUnderCursor() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return Optional.empty();
        }

        Vec3 eyeVec = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        Vector3d eye = new Vector3d(eyeVec.x, eyeVec.y, eyeVec.z);
        Vector3d look = new Vector3d(lookVec.x, lookVec.y, lookVec.z).normalize();

        double maxDistance = 128.0;

        // 1. Raycast against terrain/road blocks
        Vec3 reachEnd = eyeVec.add(lookVec.scale(maxDistance));
        net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
                eyeVec,
                reachEnd,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player
        );
        BlockHitResult blockHit = mc.level.clip(clipContext);

        Vector3d testPos = null;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            Vec3 loc = blockHit.getLocation();
            testPos = new Vector3d(loc.x, loc.y, loc.z);
        }

        EdgeHitResult bestHit = null;
        double bestDistToRay = Double.MAX_VALUE;

        for (RoadEdge edge : ClientGraphCache.getInstance().getGraph().getEdges().values()) {
            BezierCurve3D curve = edge.getCurve();
            double halfWidth = (edge.getProfile() != null ? edge.getProfile().getWidth() : 6.0) * 0.5;

            // Test 1: If we hit a block on or near the road surface
            if (testPos != null) {
                BezierCurve3D.CurveProjection2D proj = curve.projectPoint2D(testPos.x, testPos.z);
                double totalLen = curve.getApproximateLength();
                if (proj.distanceAlongCurve() >= -0.5 && proj.distanceAlongCurve() <= totalLen + 0.5) {
                    double latDist = Math.abs(proj.lateralDistance());
                    double vertDist = Math.abs(testPos.y - proj.curvePoint().y);
                    if (latDist <= halfWidth + 1.5 && vertDist <= 3.5) {
                        double camDist = eye.distanceTo(proj.curvePoint());
                        if (camDist < bestDistToRay) {
                            bestDistToRay = camDist;
                            bestHit = new EdgeHitResult(edge.getId(), edge, proj.t(), proj.lateralDistance(), proj.curvePoint());
                        }
                    }
                }
            }

            // Test 2: Fallback direct ray-to-curve proximity
            if (bestHit == null) {
                int sampleSteps = 24;
                for (int s = 0; s <= sampleSteps; s++) {
                    double t = (double) s / sampleSteps;
                    Vector3d pt = curve.evaluate(t);
                    Vector3d toPt = pt.subtract(eye);
                    double rayProj = toPt.dot(look);
                    if (rayProj > 0.5 && rayProj < maxDistance) {
                        Vector3d closestRayPt = eye.add(look.multiply(rayProj));
                        double dist = pt.distanceTo(closestRayPt);
                        if (dist <= halfWidth + 1.5 && rayProj < bestDistToRay) {
                            bestDistToRay = rayProj;
                            bestHit = new EdgeHitResult(edge.getId(), edge, t, 0.0, pt);
                        }
                    }
                }
            }
        }

        return Optional.ofNullable(bestHit);
    }
}
