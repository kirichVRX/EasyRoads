package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.pack.LitematicParser;
import com.roadbuilder.mod.domain.pack.RoadPackRegistry;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.*;

/**
 * Generator for circular roundabout rotary junctions matching Road_ring.litematic 1-to-1,
 * carving and furnishing approaches strictly for the actual connected road edges (no phantom arms).
 */
public final class RoundaboutGenerator {

    private RoundaboutGenerator() {}

    public record RoundaboutArm(
            UUID edgeId,
            Vector3d direction,
            Vector3d normal,
            double halfWidth,
            double halfRoadwayWidth,
            ModularRoadPreset preset
    ) {}

    public static Map<BlockPos, BlockState> generateRoundabout(
            Level level, RoadNode node, List<RoadEdge> edges, RoadNetworkGraph graph) {

        Map<BlockPos, BlockState> surfaceMap = new HashMap<>();

        Vector3d center = node.getPosition();
        double rOut = node.getOuterRadius() > 0 ? node.getOuterRadius() : 23.0;
        double rIn = node.getInnerRadius() > 0 ? node.getInnerRadius() : 13.0;
        int roadY = (int) Math.round(center.y);
        double sidewalkWidth = 4.0;

        // Build arms strictly from actual connected edges
        List<RoundaboutArm> arms = new ArrayList<>();
        if (edges != null) {
            for (RoadEdge edge : edges) {
                Vector3d dir = edge.getNodeAId().equals(node.getId())
                        ? edge.getCurve().evaluateTangent(0.0)
                        : edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                Vector3d dirXz = new Vector3d(dir.x, 0, dir.z).normalize();
                if (dirXz.lengthSquared() < 1e-4) dirXz = new Vector3d(0, 0, 1);
                Vector3d normal = new Vector3d(-dirXz.z, 0, dirXz.x);

                String presetId = edge.getPresetId();
                ModularRoadPreset basePreset = PresetManager.getInstance().getAllPresets().stream()
                        .filter(p -> p.getId().equals(presetId))
                        .findFirst()
                        .orElse(PresetManager.getInstance().getActivePreset());
                ModularRoadPreset preset = RoadVoxelizer.resolveEffectivePreset(edge, basePreset);

                arms.add(new RoundaboutArm(
                        edge.getId(),
                        dirXz,
                        normal,
                        preset.getTotalWidth() * 0.5,
                        preset.getRoadwayWidth() * 0.5,
                        preset
                ));
            }
        }

        ModularRoadPreset primaryPreset = !arms.isEmpty() ? arms.get(0).preset() : PresetManager.getInstance().getActivePreset();
        ModularRoadPreset.CurbPalette ringCurbPalette = primaryPreset.getCurbPalette();
        ModularRoadPreset.SidewalkPalette ringSidewalkPalette = primaryPreset.getSidewalkPalette();

        BlockState asphaltState = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState markingBlock = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState markingStairBase = Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState foundationState = Blocks.STONE.defaultBlockState();
        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        BlockState airState = Blocks.AIR.defaultBlockState();

        int cX = (int) Math.floor(center.x);
        int cZ = (int) Math.floor(center.z);

        // 1. Place the circular roundabout ring (using Road_ring.litematic core when matching default radii, sealing unused cardinal gaps)
        LitematicParser.Schematic ringSchem = RoadPackRegistry.getInstance().getActivePack().getSchematic("Road_ring");
        if (ringSchem == null) {
            ringSchem = RoadPackRegistry.getInstance().getActivePack().getSchematic("road_ring");
        }

        boolean useSchematicRing = ringSchem != null
                && Math.abs(rOut - 23.0) <= 1.5
                && Math.abs(rIn - 13.0) <= 1.5;

        if (useSchematicRing) {
            int sxCenter = 42;
            int szCenter = 41;
            double maxRingRadius = 27.5;

            for (int sx = 0; sx < ringSchem.getSizeX(); sx++) {
                for (int sz = 0; sz < ringSchem.getSizeZ(); sz++) {
                    int dx = sx - sxCenter;
                    int dz = sz - szCenter;
                    double dist = Math.hypot(dx, dz);
                    // Exclude the 4 baked cardinal approach arms & corner grass squares outside the ring sidewalk
                    if (dist > maxRingRadius) {
                        continue;
                    }

                    int bx = cX + dx;
                    int bz = cZ + dz;
                    BlockPos pos = new BlockPos(bx, roadY, bz);

                    // Seal the outer ring roadway, curb & sidewalk seamlessly (replacing the 4 baked cardinal cutouts & corner flares outside dist=20.5)
                    boolean inBakedCardinalCutout = dist >= 20.5;
                    if (inBakedCardinalCutout) {
                        BlockState sealedState = generateRingBlockAtRadius(bx, roadY, bz, dx, dz, dist, rIn, rOut, sidewalkWidth,
                                2, asphaltState, markingStairBase, ringCurbPalette, ringSidewalkPalette, grassState);
                        if (sealedState != null) {
                            surfaceMap.put(pos, sealedState);
                        }
                        continue;
                    }

                    BlockState s = ringSchem.getBlock(sx, 0, sz);
                    if (!s.isAir()) {
                        if (s.getBlock() == Blocks.POLISHED_ANDESITE_STAIRS) {
                            BlockState customCurb = RoadVoxelizer.pickCurbBlock(ringCurbPalette, bx, roadY, bz);
                            if (customCurb.getBlock() instanceof StairBlock) {
                                s = customCurb
                                        .setValue(StairBlock.FACING, s.getValue(StairBlock.FACING))
                                        .setValue(StairBlock.HALF, s.getValue(StairBlock.HALF))
                                        .setValue(StairBlock.SHAPE, s.getValue(StairBlock.SHAPE));
                            }
                        }
                        surfaceMap.put(pos, s);
                    }
                }
            }
        } else {
            double maxRingR = rOut + sidewalkWidth + 0.5;
            int minR = (int) Math.ceil(maxRingR);
            int lanes = Math.max(1, node.getRingLaneCount());

            for (int dx = -minR; dx <= minR; dx++) {
                for (int dz = -minR; dz <= minR; dz++) {
                    double dist = Math.hypot(dx, dz);
                    if (dist > maxRingR) continue;
                    int bx = cX + dx;
                    int bz = cZ + dz;
                    BlockState s = generateRingBlockAtRadius(bx, roadY, bz, dx, dz, dist, rIn, rOut, sidewalkWidth,
                            lanes, asphaltState, markingStairBase, ringCurbPalette, ringSidewalkPalette, grassState);
                    if (s != null) {
                        surfaceMap.put(new BlockPos(bx, roadY, bz), s);
                    }
                }
            }
        }

        // 2. Carve & furnish ONLY the actual connected road approach arms
        double approachEnd = rOut + 8.1;
        for (RoundaboutArm arm : arms) {
            double halfArmW = arm.halfWidth() + 3.0;
            for (double along = rOut - 2.0; along <= approachEnd; along += 0.5) {
                for (double lat = -halfArmW; lat <= halfArmW; lat += 0.5) {
                    double wx = center.x + arm.direction().x * along + arm.normal().x * lat;
                    double wz = center.z + arm.direction().z * along + arm.normal().z * lat;
                    int bx = (int) Math.round(wx);
                    int bz = (int) Math.round(wz);

                    double rx = (bx + 0.5) - center.x;
                    double rz = (bz + 0.5) - center.z;
                    double dAlong = rx * arm.direction().x + rz * arm.direction().z;
                    double dLat = rx * arm.normal().x + rz * arm.normal().z;
                    double absLat = Math.abs(dLat);
                    double distCenter = Math.hypot(rx, rz);

                    if (dAlong < rOut - 1.5 || dAlong > approachEnd) continue;

                    // Flare the approach curb smoothly only at the outer ring throat (without widening zebra row 1)
                    double flare = Math.max(0.0, (rOut + 0.6 - dAlong) * 0.75);
                    double rwEdge = arm.halfRoadwayWidth() + flare;

                    BlockPos pos = new BlockPos(bx, roadY, bz);
                    if (absLat <= rwEdge + 0.2) {
                        if (dAlong >= rOut + 1.0 && dAlong <= rOut + 5.0 && absLat <= arm.halfRoadwayWidth() + 0.2) {
                            // 4m zebra crosswalk matching Road_ring.litematic
                            boolean white = JunctionGenerator.isZebraWhiteStripe(bx, bz, arm.direction(), center, rOut + 3.0, dLat);
                            surfaceMap.put(pos, white ? markingBlock : asphaltState);
                        } else if (dAlong >= rOut + 6.0 && dAlong <= rOut + 7.0 && dLat <= 0.25 && absLat <= arm.halfRoadwayWidth() + 0.2) {
                            // Incoming right-hand stop line
                            surfaceMap.put(pos, markingBlock);
                        } else if (dAlong >= rOut + 6.0 && Math.abs(dLat) < 0.5 && Math.floorMod((long) Math.floor(dAlong), 4) < 3) {
                            Direction dashFacing = RoadVoxelizer.facingFromVector(arm.normal().x, arm.normal().z);
                            surfaceMap.put(pos, RoadVoxelizer.orientStairs(markingStairBase, dashFacing, Half.BOTTOM));
                        } else {
                            surfaceMap.put(pos, asphaltState);
                        }
                    } else if (dAlong >= rOut + 0.5 || distCenter >= rOut - 0.3) {
                        if (absLat <= arm.halfRoadwayWidth() + 1.2 + flare) {
                            // Curb stair facing outward away from approach centerline
                            double outSign = (dLat >= 0.0) ? 1.0 : -1.0;
                            Direction curbFacing = RoadVoxelizer.facingFromVector(arm.normal().x * outSign, arm.normal().z * outSign);
                            BlockState curbBase = RoadVoxelizer.pickCurbBlock(arm.preset().getCurbPalette(), bx, roadY, bz);
                            surfaceMap.put(pos, RoadVoxelizer.orientStairs(curbBase, curbFacing, Half.BOTTOM));
                        } else if (absLat <= arm.halfWidth() + 0.2 + flare) {
                            // Sidewalk connecting cleanly to ring sidewalk
                            surfaceMap.put(pos, mosaicSidewalkAt(bx, roadY, bz, arm.preset().getSidewalkPalette()));
                        }
                    }
                }
            }
        }

        // Smooth curved corner fillets between each arm and the outer ring curb
        for (RoundaboutArm arm : arms) {
            addApproachCornerFillet(surfaceMap, center, roadY, rOut, arm, -1.0, arm.preset().getCurbPalette());
            addApproachCornerFillet(surfaceMap, center, roadY, rOut, arm, 1.0, arm.preset().getCurbPalette());
        }

        // Enforce strictly 1-block-wide outer curbs (thin doubled stairs into sidewalk along diagonals/throats)
        enforceSingleBlockOuterCurbs(surfaceMap, center, roadY, rIn, rOut, arms, ringCurbPalette, ringSidewalkPalette);

        // 3. Auto-compute corner StairShapes so curved curbs wrap cleanly
        RoadVoxelizer.resolveStairShapes(surfaceMap);

        // 4. Populate final result with surface blocks, air clearance, and foundation
        Map<BlockPos, BlockState> result = new HashMap<>(surfaceMap.size() * 6);
        for (Map.Entry<BlockPos, BlockState> entry : surfaceMap.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState s = entry.getValue();
            result.put(pos, s);

            for (int cy = roadY + 1; cy <= roadY + 8; cy++) {
                result.put(new BlockPos(pos.getX(), cy, pos.getZ()), airState);
            }

            BlockState subState = (s.getBlock() == Blocks.GRASS_BLOCK) ? dirtState : foundationState;
            for (int fy = roadY - 1; fy >= roadY - 2; fy--) {
                result.put(new BlockPos(pos.getX(), fy, pos.getZ()), subState);
            }
        }

        return result;
    }

    private static BlockState generateRingBlockAtRadius(
            int bx, int roadY, int bz,
            int dx, int dz, double dist,
            double rIn, double rOut, double sidewalkWidth, int lanes,
            BlockState asphaltState, BlockState markingStairBase,
            ModularRoadPreset.CurbPalette curbPalette,
            ModularRoadPreset.SidewalkPalette sidewalkPalette,
            BlockState grassState) {

        if (dist <= rIn - 0.5) {
            return grassState;
        }
        if (dist <= rIn + 0.8) {
            boolean adjToGrass = Math.hypot(dx - 1, dz) <= rIn - 0.5
                    || Math.hypot(dx + 1, dz) <= rIn - 0.5
                    || Math.hypot(dx, dz - 1) <= rIn - 0.5
                    || Math.hypot(dx, dz + 1) <= rIn - 0.5;
            if (adjToGrass) {
                // Inner island curb: stairs face INWARD toward island center
                Direction inFacing = RoadVoxelizer.facingFromVector(-dx, -dz);
                BlockState curbBase = RoadVoxelizer.pickCurbBlock(curbPalette, bx, roadY, bz);
                return RoadVoxelizer.orientStairs(curbBase, inFacing, Half.BOTTOM);
            }
        }
        if (dist <= rOut - 0.5) {
            if (lanes > 1) {
                double laneWidth = (rOut - rIn) / lanes;
                for (int lane = 1; lane < lanes; lane++) {
                    double markR = rIn + lane * laneWidth;
                    double targetOrtho = (Math.abs(dx) >= Math.abs(dz))
                            ? Math.abs(Math.abs(dx) - Math.sqrt(Math.max(0.0, markR * markR - dz * dz)))
                            : Math.abs(Math.abs(dz) - Math.sqrt(Math.max(0.0, markR * markR - dx * dx)));
                    if (Math.abs(dist - markR) <= 0.6 && targetOrtho <= 0.5) {
                        double angle = Math.atan2(dz, dx);
                        double arc = angle * markR;
                        if (Math.floorMod((long) Math.floor(arc), 4) < 3) {
                            Direction radFacing = RoadVoxelizer.facingFromVector(dx, dz);
                            return RoadVoxelizer.orientStairs(markingStairBase, radFacing, Half.BOTTOM);
                        }
                    }
                }
            }
            return asphaltState;
        }
        if (dist <= rOut + 0.8) {
            boolean adjToAsphalt = Math.hypot(dx - 1, dz) <= rOut - 0.5
                    || Math.hypot(dx + 1, dz) <= rOut - 0.5
                    || Math.hypot(dx, dz - 1) <= rOut - 0.5
                    || Math.hypot(dx, dz + 1) <= rOut - 0.5;
            if (adjToAsphalt) {
                // Outer ring curb: stairs face OUTWARD away from ring center
                Direction outFacing = RoadVoxelizer.facingFromVector(dx, dz);
                BlockState curbBase = RoadVoxelizer.pickCurbBlock(curbPalette, bx, roadY, bz);
                return RoadVoxelizer.orientStairs(curbBase, outFacing, Half.BOTTOM);
            }
            if (dist <= rOut + sidewalkWidth + 0.5) {
                return mosaicSidewalkAt(bx, roadY, bz, sidewalkPalette);
            }
        }
        if (dist <= rOut + sidewalkWidth + 0.5) {
            return mosaicSidewalkAt(bx, roadY, bz, sidewalkPalette);
        }
        return null;
    }

    private static void addApproachCornerFillet(
            Map<BlockPos, BlockState> surfaceMap, Vector3d center, int roadY,
            double rOut, RoundaboutArm arm, double sideSign, ModularRoadPreset.CurbPalette curbPalette) {

        Vector3d tangent = new Vector3d(-arm.normal().x * sideSign, 0, -arm.normal().z * sideSign);
        Vector3d dirAlong = arm.direction();
        double baseLat = arm.halfRoadwayWidth() + 0.5;

        Vector3d pRing = center.add(dirAlong.multiply(rOut - 0.5)).add(arm.normal().multiply(sideSign * (baseLat + 2.5)));
        Vector3d pArm = center.add(dirAlong.multiply(rOut + 1.0)).add(arm.normal().multiply(sideSign * baseLat));
        Vector3d cornerVertex = center.add(dirAlong.multiply(rOut - 0.5)).add(arm.normal().multiply(sideSign * baseLat));

        BezierCurve3D fillet = new BezierCurve3D(
                pRing,
                pRing.add(cornerVertex.subtract(pRing).multiply(0.55)),
                pArm.add(cornerVertex.subtract(pArm).multiply(0.55)),
                pArm
        );

        for (int s = 0; s <= 24; s++) {
            double t = (double) s / 24.0;
            Vector3d pt = fillet.evaluate(t);
            Vector3d tan = fillet.evaluateTangent(t);
            Vector3d outNormal = new Vector3d(-tan.z * sideSign, 0, tan.x * sideSign).normalize();
            if (outNormal.dot(arm.normal().multiply(sideSign).add(tangent.multiply(-1.0))) < 0) {
                outNormal = outNormal.multiply(-1.0);
            }
            int bx = (int) Math.floor(pt.x);
            int bz = (int) Math.floor(pt.z);
            double distCenter = Math.hypot((bx + 0.5) - center.x, (bz + 0.5) - center.z);
            if (distCenter >= rOut - 0.5 && distCenter <= rOut + 4.5) {
                Direction facing = RoadVoxelizer.facingFromVector(outNormal.x, outNormal.z);
                BlockPos pos = new BlockPos(bx, roadY, bz);
                BlockState existing = surfaceMap.get(pos);
                if (existing != null && existing.getBlock() != Blocks.GRAY_CONCRETE_POWDER && existing.getBlock() != Blocks.SMOOTH_QUARTZ) {
                    BlockState curbBase = RoadVoxelizer.pickCurbBlock(curbPalette, bx, roadY, bz);
                    surfaceMap.put(pos, RoadVoxelizer.orientStairs(curbBase, facing, Half.BOTTOM));
                }
            }
        }
    }

    private static boolean isRoundaboutRoadway(BlockState s) {
        if (s == null) return false;
        net.minecraft.world.level.block.Block b = s.getBlock();
        return b == Blocks.GRAY_CONCRETE_POWDER || b == Blocks.SMOOTH_QUARTZ || b == Blocks.SMOOTH_QUARTZ_STAIRS;
    }

    private static void enforceSingleBlockOuterCurbs(
            Map<BlockPos, BlockState> surfaceMap,
            Vector3d center,
            int roadY,
            double rIn,
            double rOut,
            List<RoundaboutArm> arms,
            ModularRoadPreset.CurbPalette defaultCurbPalette,
            ModularRoadPreset.SidewalkPalette defaultSidewalkPalette) {

        Map<BlockPos, BlockState> updates = new HashMap<>();
        int cX = (int) Math.floor(center.x);
        int cZ = (int) Math.floor(center.z);

        for (Map.Entry<BlockPos, BlockState> entry : surfaceMap.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            if (isRoundaboutRoadway(state) || state.getBlock() == Blocks.GRASS_BLOCK) {
                continue;
            }
            double distCenter = Math.hypot((pos.getX() + 0.5) - center.x, (pos.getZ() + 0.5) - center.z);
            if (distCenter <= rIn + 2.5 || distCenter > rOut + 7.0) {
                continue;
            }

            boolean touchesRoadway = isRoundaboutRoadway(surfaceMap.get(pos.north()))
                    || isRoundaboutRoadway(surfaceMap.get(pos.south()))
                    || isRoundaboutRoadway(surfaceMap.get(pos.east()))
                    || isRoundaboutRoadway(surfaceMap.get(pos.west()));

            boolean isCurbStair = state.getBlock() instanceof StairBlock && state.getBlock() != Blocks.SMOOTH_QUARTZ_STAIRS;
            if (isCurbStair && !touchesRoadway) {
                ModularRoadPreset.SidewalkPalette swPal = pickArmSidewalkPalette(pos, center, arms, defaultSidewalkPalette);
                updates.put(pos, mosaicSidewalkAt(pos.getX(), roadY, pos.getZ(), swPal));
            } else if (!isCurbStair && touchesRoadway) {
                // Convert sidewalk block touching roadway on the throat/arc into an outward-facing curb stair
                int outDx = 0;
                int outDz = 0;
                if (isRoundaboutRoadway(surfaceMap.get(pos.north()))) outDz += 1;
                if (isRoundaboutRoadway(surfaceMap.get(pos.south()))) outDz -= 1;
                if (isRoundaboutRoadway(surfaceMap.get(pos.west()))) outDx += 1;
                if (isRoundaboutRoadway(surfaceMap.get(pos.east()))) outDx -= 1;
                Direction outFacing = (outDx != 0 || outDz != 0)
                        ? RoadVoxelizer.facingFromVector(outDx, outDz)
                        : RoadVoxelizer.facingFromVector(pos.getX() - cX, pos.getZ() - cZ);
                ModularRoadPreset.CurbPalette cPal = pickArmCurbPalette(pos, center, arms, defaultCurbPalette);
                BlockState curbBase = RoadVoxelizer.pickCurbBlock(cPal, pos.getX(), roadY, pos.getZ());
                updates.put(pos, RoadVoxelizer.orientStairs(curbBase, outFacing, Half.BOTTOM));
            }
        }
        surfaceMap.putAll(updates);
    }

    private static ModularRoadPreset.SidewalkPalette pickArmSidewalkPalette(
            BlockPos pos, Vector3d center, List<RoundaboutArm> arms,
            ModularRoadPreset.SidewalkPalette defSw) {
        return pickClosestArm(pos, center, arms)
                .map(a -> a.preset().getSidewalkPalette())
                .orElse(defSw);
    }

    private static ModularRoadPreset.CurbPalette pickArmCurbPalette(
            BlockPos pos, Vector3d center, List<RoundaboutArm> arms,
            ModularRoadPreset.CurbPalette defCurb) {
        return pickClosestArm(pos, center, arms)
                .map(a -> a.preset().getCurbPalette())
                .orElse(defCurb);
    }

    private static Optional<RoundaboutArm> pickClosestArm(BlockPos pos, Vector3d center, List<RoundaboutArm> arms) {
        if (arms == null || arms.isEmpty()) return Optional.empty();
        double rx = (pos.getX() + 0.5) - center.x;
        double rz = (pos.getZ() + 0.5) - center.z;
        RoundaboutArm best = null;
        double bestDot = 0.70; // Within ~45 deg of arm centerline
        for (RoundaboutArm arm : arms) {
            double len = Math.hypot(rx, rz);
            if (len < 1e-4) continue;
            double dot = (rx * arm.direction().x + rz * arm.direction().z) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = arm;
            }
        }
        return Optional.ofNullable(best);
    }

    private static BlockState mosaicSidewalkAt(int bx, int y, int bz, ModularRoadPreset.SidewalkPalette palette) {
        return RoadVoxelizer.pickSidewalkBlock(palette, bx, y, bz);
    }
}
