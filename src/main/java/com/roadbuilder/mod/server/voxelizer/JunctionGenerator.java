package com.roadbuilder.mod.server.voxelizer;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadEdge;
import com.roadbuilder.mod.domain.graph.RoadModuleCurve;
import com.roadbuilder.mod.domain.graph.RoadNetworkGraph;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.PresetManager;
import com.roadbuilder.mod.domain.profile.RoadElementType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Procedural generator for road intersection platforms (junctions)
 * utilizing per-module curves, filleted corner curbs, and sidewalk plazas.
 */
public final class JunctionGenerator {

    public enum JunctionStyle {
        FLUSH,       // Standard flat intersection, corners filled with roadway
        ROUNDED,     // Filleted corners with rounded curbs/sidewalks
        SIGNALED,    // Intersection with traffic signal clearance markers and stop lines
        CUSTOM;

        public static JunctionStyle fromString(String name) {
            if (name == null || name.isBlank()) return ROUNDED;
            try {
                return valueOf(name.toUpperCase());
            } catch (Exception e) {
                return ROUNDED;
            }
        }
    }

    public record JunctionArm(
            UUID edgeId,
            double angle,
            Vector3d direction,
            Vector3d normal,
            double leftTotalWidth,
            double rightTotalWidth,
            double leftRoadwayWidth,
            double rightRoadwayWidth,
            ModularRoadPreset preset,
            List<RoadModuleCurve> moduleCurves,
            boolean startsAtNode,
            double entryY
    ) {
        public double halfWidth() {
            return Math.max(leftTotalWidth, rightTotalWidth);
        }

        public double halfRoadwayWidth() {
            return Math.max(leftRoadwayWidth, rightRoadwayWidth);
        }
    }

    private record CornerFillet(
            int armIIndex,
            int armJIndex,
            BezierCurve3D curbCurve,
            List<Vector3d> sampledPoints,
            List<Vector3d> sampledNormals,
            BlockState curbState,
            BlockState sidewalkState,
            boolean hasCurb,
            boolean hasSidewalk,
            double curbWidth
    ) {}

    private JunctionGenerator() {}

    /**
     * Computes the junction radius. If node radius is explicitly set (> 0), uses it;
     * otherwise computes an automatic radius based on the connected edges' widths.
     */
    public static double calculateJunctionRadius(RoadNode node, List<RoadEdge> edges) {
        if (node.getRadius() > 0.0) {
            return node.getRadius();
        }
        double maxHalfWidth = 3.0;
        for (RoadEdge edge : edges) {
            ModularRoadPreset preset = getPresetForEdge(edge);
            double hw = preset.getTotalWidth() * 0.5;
            if (hw > maxHalfWidth) {
                maxHalfWidth = hw;
            }
        }
        return Math.max(4.0, maxHalfWidth + 2.5);
    }

    /**
     * Generates all blocks for the junction zone of a node.
     */
    public static Map<BlockPos, BlockState> generateJunction(
            Level level, RoadNode node, List<RoadEdge> edges,
            RoadNetworkGraph graph, JunctionStyle style) {

        Map<BlockPos, BlockState> result = new HashMap<>();
        if (edges == null || edges.isEmpty()) {
            return result;
        }

        Vector3d center = node.getPosition();
        double radius = calculateJunctionRadius(node, edges);

        // 1. Build and sort arms counter-clockwise
        List<JunctionArm> arms = buildArms(node, edges);
        if (arms.isEmpty()) {
            return result;
        }
        arms.sort(Comparator.comparingDouble(JunctionArm::angle));
        int numArms = arms.size();

        ModularRoadPreset primaryPreset = arms.get(0).preset();
        BlockState defaultRoadway = getRoadwayBlockState(primaryPreset);
        BlockState defaultFoundation = getFoundationBlockState(primaryPreset);
        BlockState airState = Blocks.AIR.defaultBlockState();
        BlockState stopLineState = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState grassState = Blocks.GRASS_BLOCK.defaultBlockState();
        int clearanceHeight = 4;

        // Determine if this junction is an elevated viaduct/bridge junction
        int centerGroundY = RoadVoxelizer.getTrueGroundY(level, (int) Math.round(center.x), (int) Math.round(center.z));
        boolean isViaductJunction = (center.y - centerGroundY) >= 3;
        for (JunctionArm arm : arms) {
            if (arm.preset().getViaductMode() == ModularRoadPreset.Mode.ALWAYS) {
                isViaductJunction = true;
                break;
            }
        }

        // 2. Build corner fillets between adjacent arms
        List<CornerFillet> cornerFillets = new ArrayList<>();
        for (int i = 0; i < numArms; i++) {
            int j = (i + 1) % numArms;
            JunctionArm armI = arms.get(i);
            JunctionArm armJ = arms.get(j);

            double L_i = radius;
            double L_j = radius;

            // Arm i: right side faces corner
            Vector3d p_i_curb = center.add(armI.direction().multiply(L_i)).add(armI.normal().multiply(armI.rightRoadwayWidth()));
            // Arm j: left side faces corner
            Vector3d p_j_curb = center.add(armJ.direction().multiply(L_j)).subtract(armJ.normal().multiply(armJ.leftRoadwayWidth()));

            Vector3d delta = p_i_curb.subtract(p_j_curb);
            double det = armI.direction().x * armJ.direction().z - armI.direction().z * armJ.direction().x;

            Vector3d q0 = new Vector3d(p_i_curb.x, armI.entryY(), p_i_curb.z);
            Vector3d q3 = new Vector3d(p_j_curb.x, armJ.entryY(), p_j_curb.z);
            Vector3d q1;
            Vector3d q2;

            if (det > 0.05) {
                double lambda1 = (delta.x * armJ.direction().z - delta.z * armJ.direction().x) / det;
                double lambda2 = (delta.x * armI.direction().z - delta.z * armI.direction().x) / det;
                if (lambda1 > 0.1 && lambda2 > 0.1) {
                    Vector3d v = p_i_curb.add(armI.direction().multiply(-lambda1));
                    q1 = q0.add(v.subtract(q0).multiply(0.552));
                    q2 = q3.add(v.subtract(q3).multiply(0.552));
                } else {
                    double chord = q0.distanceTo(q3);
                    q1 = q0.add(armI.direction().multiply(-chord / 3.0));
                    q2 = q3.add(armJ.direction().multiply(-chord / 3.0));
                }
            } else {
                double chord = q0.distanceTo(q3);
                q1 = q0.add(armI.direction().multiply(-chord / 3.0));
                q2 = q3.add(armJ.direction().multiply(-chord / 3.0));
            }

            q1 = new Vector3d(q1.x, q0.y + (q3.y - q0.y) / 3.0, q1.z);
            q2 = new Vector3d(q2.x, q0.y + 2.0 * (q3.y - q0.y) / 3.0, q2.z);

            BezierCurve3D curbCurve = new BezierCurve3D(q0, q1, q2, q3);

            // Sample points & outward normals along the fillet
            int samples = 20;
            List<Vector3d> pts = new ArrayList<>(samples + 1);
            List<Vector3d> norms = new ArrayList<>(samples + 1);
            for (int s = 0; s <= samples; s++) {
                double u = (double) s / samples;
                Vector3d pt = curbCurve.evaluate(u);
                Vector3d tan = curbCurve.evaluateTangent(u);
                Vector3d nOut = new Vector3d(-tan.z, 0, tan.x).normalize();
                if (nOut.lengthSquared() < 1e-4) {
                    nOut = new Vector3d(0, 0, 1);
                }
                pts.add(pt);
                norms.add(nOut);
            }

            BlockState curbState = getCornerCurbState(armI, armJ);
            BlockState sidewalkState = getCornerSidewalkState(armI, armJ);
            boolean hasCurb = armHasCurb(armI) || armHasCurb(armJ);
            boolean hasSidewalk = armHasSidewalk(armI) || armHasSidewalk(armJ);
            double curbWidth = getCornerCurbWidth(armI, armJ);

            cornerFillets.add(new CornerFillet(i, j, curbCurve, pts, norms, curbState, sidewalkState, hasCurb, hasSidewalk, curbWidth));
        }

        int minX = (int) Math.floor(center.x - radius - 2);
        int maxX = (int) Math.ceil(center.x + radius + 2);
        int minZ = (int) Math.floor(center.z - radius - 2);
        int maxZ = (int) Math.ceil(center.z + radius + 2);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                double px = bx + 0.5;
                double pz = bz + 0.5;
                double dx = px - center.x;
                double dz = pz - center.z;
                double distCenter = Math.hypot(dx, dz);

                if (distCenter > radius + 2.5) {
                    continue;
                }

                boolean isStopLine = false;
                boolean inCorridorRoadway = false;

                // 1. Check arm corridors
                for (JunctionArm arm : arms) {
                    double dAlong = dx * arm.direction().x + dz * arm.direction().z;
                    double dLat = dx * arm.normal().x + dz * arm.normal().z;

                    if (dAlong >= -0.5 && dAlong <= radius) {
                        if (dLat >= -arm.leftRoadwayWidth() - 0.2 && dLat <= arm.rightRoadwayWidth() + 0.2) {
                            if (style == JunctionStyle.SIGNALED && dAlong >= radius - 1.2 && dAlong <= radius) {
                                isStopLine = true;
                            } else {
                                inCorridorRoadway = true;
                            }
                            break;
                        }
                    }
                }

                // 2. Check corner fillets
                boolean isCurb = false;
                boolean isSidewalk = false;
                boolean isGrass = false;
                BlockState cornerCurb = null;
                BlockState cornerSidewalk = null;

                if (!isStopLine && !inCorridorRoadway) {
                    double maxOutwardSignedDist = -Double.MAX_VALUE;
                    CornerFillet matchingCorner = null;

                    for (CornerFillet cf : cornerFillets) {
                        double bestDist = Double.MAX_VALUE;
                        double bestSignedDist = 0.0;

                        for (int s = 0; s < cf.sampledPoints().size(); s++) {
                            Vector3d spt = cf.sampledPoints().get(s);
                            Vector3d snorm = cf.sampledNormals().get(s);

                            double cdx = px - spt.x;
                            double cdz = pz - spt.z;
                            double d = Math.hypot(cdx, cdz);
                            if (d < bestDist) {
                                bestDist = d;
                                bestSignedDist = cdx * snorm.x + cdz * snorm.z;
                            }
                        }

                        if (bestSignedDist > 0.0 && bestDist <= radius + 2.0) {
                            if (bestSignedDist > maxOutwardSignedDist) {
                                maxOutwardSignedDist = bestSignedDist;
                                matchingCorner = cf;
                            }
                        }
                    }

                    if (matchingCorner != null && maxOutwardSignedDist > 0.0) {
                        cornerCurb = matchingCorner.curbState();
                        cornerSidewalk = matchingCorner.sidewalkState();
                        double cornerCurbW = matchingCorner.curbWidth();

                        if (style == JunctionStyle.ROUNDED || style == JunctionStyle.SIGNALED) {
                            if (maxOutwardSignedDist <= cornerCurbW && matchingCorner.hasCurb()) {
                                isCurb = true;
                            } else if (matchingCorner.hasSidewalk()) {
                                isSidewalk = true;
                            } else {
                                isGrass = true;
                            }
                        } else if (style == JunctionStyle.FLUSH) {
                            if (maxOutwardSignedDist > cornerCurbW && matchingCorner.hasSidewalk()) {
                                isSidewalk = true;
                            }
                        }
                    }
                }

                // Smooth Y interpolation
                double wCenter = 1.0 / (1.0 + distCenter);
                double ySum = center.y * wCenter;
                double wSum = wCenter;
                for (JunctionArm arm : arms) {
                    double dAlong = dx * arm.direction().x + dz * arm.direction().z;
                    if (dAlong > 0.0) {
                        double w = dAlong / radius;
                        ySum += arm.entryY() * w;
                        wSum += w;
                    }
                }
                int surfaceY = (int) Math.round(ySum / wSum);

                BlockState surfaceState;
                if (isStopLine) {
                    surfaceState = stopLineState;
                } else if (isCurb) {
                    surfaceState = cornerCurb != null ? cornerCurb : Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
                } else if (isSidewalk) {
                    surfaceState = cornerSidewalk != null ? cornerSidewalk : Blocks.STONE_BRICKS.defaultBlockState();
                } else if (isGrass) {
                    surfaceState = grassState;
                } else {
                    surfaceState = defaultRoadway;
                }

                BlockPos surfacePos = new BlockPos(bx, surfaceY, bz);
                result.put(surfacePos, surfaceState);

                // Clearance zone
                for (int cy = surfaceY + 1; cy <= surfaceY + clearanceHeight; cy++) {
                    result.put(new BlockPos(bx, cy, bz), airState);
                }

                // Foundation directly below deck (stone/concrete for roadways/curbs, dirt only under grass)
                BlockState subState = (surfaceState == grassState) ? Blocks.DIRT.defaultBlockState() : defaultFoundation;
                result.put(new BlockPos(bx, surfaceY - 1, bz), subState);
                result.put(new BlockPos(bx, surfaceY - 2, bz), subState);

                // For ground-level junctions, anchor down to natural ground if small gap (<= 2 blocks)
                // NEVER fill downward into air under viaduct/bridge junctions!
                if (!isViaductJunction) {
                    int localGroundY = RoadVoxelizer.getTrueGroundY(level, bx, bz);
                    for (int fy = surfaceY - 3; fy >= Math.max(localGroundY, surfaceY - 4); fy--) {
                        BlockPos fPos = new BlockPos(bx, fy, bz);
                        if (level.getBlockState(fPos).isAir()) {
                            result.put(fPos, subState);
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        return result;
    }

    private static List<JunctionArm> buildArms(RoadNode node, List<RoadEdge> edges) {
        List<JunctionArm> arms = new ArrayList<>();

        for (RoadEdge edge : edges) {
            boolean startsAtNode = edge.getNodeAId().equals(node.getId());
            Vector3d dir;
            double entryY;
            if (startsAtNode) {
                dir = edge.getCurve().evaluateTangent(0.0);
                entryY = edge.getCurve().getP0().y;
            } else {
                dir = edge.getCurve().evaluateTangent(1.0).multiply(-1.0);
                entryY = edge.getCurve().getP3().y;
            }

            Vector3d dirXz = new Vector3d(dir.x, 0, dir.z).normalize();
            if (dirXz.lengthSquared() < 1e-4) {
                dirXz = new Vector3d(0, 0, 1);
            }

            double angle = Math.atan2(dirXz.z, dirXz.x);
            Vector3d normal = new Vector3d(-dirXz.z, 0, dirXz.x);

            ModularRoadPreset preset = getPresetForEdge(edge);
            List<RoadModuleCurve> mcs = edge.getModuleCurves();

            double minOffset = 0.0;
            double maxOffset = 0.0;
            double minRoadOffset = 0.0;
            double maxRoadOffset = 0.0;

            if (mcs != null && !mcs.isEmpty()) {
                for (RoadModuleCurve mc : mcs) {
                    double offset = startsAtNode ? mc.getStartOffset() : mc.getEndOffset();
                    double halfW = mc.getWidth() * 0.5;
                    double leftO = offset - halfW;
                    double rightO = offset + halfW;
                    if (leftO < minOffset) minOffset = leftO;
                    if (rightO > maxOffset) maxOffset = rightO;
                    if (mc.isRoadway()) {
                        if (leftO < minRoadOffset) minRoadOffset = leftO;
                        if (rightO > maxRoadOffset) maxRoadOffset = rightO;
                    }
                }
            }

            if (minOffset >= maxOffset) {
                double hw = preset.getTotalWidth() * 0.5;
                double rhw = preset.getRoadwayWidth() * 0.5;
                minOffset = -hw;
                maxOffset = hw;
                minRoadOffset = -rhw;
                maxRoadOffset = rhw;
            }

            double leftTotalWidth = Math.abs(minOffset);
            double rightTotalWidth = Math.abs(maxOffset);
            double leftRoadwayWidth = Math.abs(minRoadOffset);
            double rightRoadwayWidth = Math.abs(maxRoadOffset);

            arms.add(new JunctionArm(
                    edge.getId(),
                    angle,
                    dirXz,
                    normal,
                    leftTotalWidth,
                    rightTotalWidth,
                    leftRoadwayWidth,
                    rightRoadwayWidth,
                    preset,
                    mcs,
                    startsAtNode,
                    entryY
            ));
        }

        return arms;
    }

    private static ModularRoadPreset getPresetForEdge(RoadEdge edge) {
        String presetId = edge.getPresetId();
        return PresetManager.getInstance().getAllPresets().stream()
                .filter(p -> p.getId().equals(presetId))
                .findFirst()
                .orElse(PresetManager.getInstance().getActivePreset());
    }

    private static BlockState getRoadwayBlockState(ModularRoadPreset preset) {
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : preset.getStack()) {
            if (elem.getType() == RoadElementType.LANE || elem.getType() == RoadElementType.SHOULDER) {
                return Blocks.GRAY_CONCRETE.defaultBlockState();
            }
        }
        return Blocks.GRAY_CONCRETE.defaultBlockState();
    }

    private static BlockState getCurbBlockState(ModularRoadPreset preset) {
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : preset.getStack()) {
            if (elem.getType() == RoadElementType.CURB) {
                return Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
            }
        }
        return Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    }

    private static BlockState getSidewalkBlockState(ModularRoadPreset preset) {
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : preset.getStack()) {
            if (elem.getType() == RoadElementType.SIDEWALK) {
                return Blocks.STONE_BRICKS.defaultBlockState();
            }
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static BlockState getFoundationBlockState(ModularRoadPreset preset) {
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static BlockState getCornerCurbState(JunctionArm a, JunctionArm b) {
        if (armHasCurb(a)) return getCurbBlockState(a.preset());
        if (armHasCurb(b)) return getCurbBlockState(b.preset());
        return Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
    }

    private static BlockState getCornerSidewalkState(JunctionArm a, JunctionArm b) {
        if (armHasSidewalk(a)) return getSidewalkBlockState(a.preset());
        if (armHasSidewalk(b)) return getSidewalkBlockState(b.preset());
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static double getCornerCurbWidth(JunctionArm a, JunctionArm b) {
        double wA = getArmCurbWidth(a);
        double wB = getArmCurbWidth(b);
        double maxW = Math.max(wA, wB);
        return maxW > 0 ? maxW : 4.0;
    }

    private static double getArmCurbWidth(JunctionArm arm) {
        if (arm.moduleCurves() != null) {
            for (RoadModuleCurve mc : arm.moduleCurves()) {
                if (mc.isCurb()) {
                    return mc.getWidth();
                }
            }
        }
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.CURB) {
                return elem.getWidth();
            }
        }
        return 0.0;
    }

    private static boolean armHasCurb(JunctionArm arm) {
        if (arm.moduleCurves() != null) {
            for (RoadModuleCurve mc : arm.moduleCurves()) {
                if (mc.isCurb()) return true;
            }
        }
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.CURB) return true;
        }
        return false;
    }

    private static boolean armHasSidewalk(JunctionArm arm) {
        if (arm.moduleCurves() != null) {
            for (RoadModuleCurve mc : arm.moduleCurves()) {
                if (mc.isSidewalk()) return true;
            }
        }
        for (com.roadbuilder.mod.domain.profile.RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.SIDEWALK) return true;
        }
        return false;
    }
}
