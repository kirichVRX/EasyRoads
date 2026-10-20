package com.roadbuilder.mod.client.editmode;

import com.roadbuilder.mod.client.cache.ClientGraphCache;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Client raycaster for detecting node hovering, snapping, and ground surface target positions.
 */
public final class NodeRaycaster {

    public record RaycastResult(
            Optional<RoadNode> hoveredNode,
            Optional<Vector3d> worldPosition
    ) {}

    private NodeRaycaster() {}

    public static RaycastResult raycast() {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            return new RaycastResult(Optional.empty(), Optional.empty());
        }

        Vec3 eyeVec = player.getEyePosition(1.0f);
        Vec3 lookVec = player.getViewVector(1.0f);
        Vector3d eye = new Vector3d(eyeVec.x, eyeVec.y, eyeVec.z);
        Vector3d look = new Vector3d(lookVec.x, lookVec.y, lookVec.z).normalize();

        // 1. Check for hovered nodes in graph cache (snap radius 2.0 blocks)
        RoadNode closestNode = null;
        double closestDistToRay = 2.0;
        double maxDistance = 256.0;

        for (RoadNode node : ClientGraphCache.getInstance().getGraph().getNodes().values()) {
            Vector3d toNode = node.getPosition().subtract(eye);
            double projection = toNode.dot(look);

            if (projection > 0.5 && projection < maxDistance) {
                Vector3d rayPoint = eye.add(look.multiply(projection));
                double distToRay = node.getPosition().distanceTo(rayPoint);
                if (distToRay < closestDistToRay) {
                    closestDistToRay = distToRay;
                    closestNode = node;
                }
            }
        }

        if (closestNode != null) {
            return new RaycastResult(Optional.of(closestNode), Optional.of(closestNode.getPosition()));
        }

        // 2. Long-range raycast against terrain blocks (up to 256 blocks)
        Vec3 reachEnd = eyeVec.add(lookVec.scale(maxDistance));
        net.minecraft.world.level.ClipContext clipContext = new net.minecraft.world.level.ClipContext(
                eyeVec,
                reachEnd,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                player
        );
        BlockHitResult blockHit = mc.level.clip(clipContext);

        if (blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitBlockPos = blockHit.getBlockPos();
            // Placing road IN LEVEL with the ground (replacing the surface block instead of floating +1 above)
            Vector3d worldPos = new Vector3d(
                    hitBlockPos.getX() + 0.5,
                    hitBlockPos.getY(),
                    hitBlockPos.getZ() + 0.5
            );
            return new RaycastResult(Optional.empty(), Optional.of(worldPos));
        }

        return new RaycastResult(Optional.empty(), Optional.empty());
    }
}
