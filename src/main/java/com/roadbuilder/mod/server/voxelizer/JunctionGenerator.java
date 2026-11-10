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
import com.roadbuilder.mod.domain.profile.RoadStackElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.*;

/**
 * Procedural generator for road intersection platforms (junctions)
 * supporting N-way angles, arbitrary road widths, corner fillets,
 * zebra crosswalks, stop lines (matching Road_Perekrestok.litematic),
 * mosaic sidewalks, outward-oriented stair curbs, and elevated viaduct support.
 */
public final class JunctionGenerator {

    public enum JunctionStyle {
        FLUSH("Сглаженный (без бордюров)"),
        ROUNDED("Обычный"),
        SIGNALED("Светофорный"),
        CITY("Городской (с переходами)"),
        ROUNDABOUT("Кольцевой"),
        CUSTOM("Пользовательский");

        private final String label;

        JunctionStyle(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        public static JunctionStyle fromString(String name) {
            if (name == null || name.isBlank()) return CITY;
            try {
                JunctionStyle parsed = valueOf(name.toUpperCase());
                return parsed;
            } catch (Exception e) {
                if ("REGULAR".equalsIgnoreCase(name) || "ОБЫЧНЫЙ".equalsIgnoreCase(name)) return ROUNDED;
                if ("ROUNDABOUT".equalsIgnoreCase(name) || "КОЛЬЦЕВОЙ".equalsIgnoreCase(name) || name.toUpperCase().contains("КОЛЬЦ")) return ROUNDABOUT;
                if ("CITY".equalsIgnoreCase(name) || "ГОРОДСКОЙ".equalsIgnoreCase(name) || name.toUpperCase().contains("ГОРОДСКОЙ")) return CITY;
                return CITY;
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
    ) {}

    private record CornerFillet(
            int armI,
            int armJ,
            BezierCurve3D curbCurve,
            List<Vector3d> sampledPoints,
            List<Vector3d> sampledNormals,
            boolean hasCurb,
            boolean hasSidewalk,
            double curbWidth,
            double sidewalkWidth
    ) {}

    private JunctionGenerator() {}

    /**
     * Computes inner corner-fillet attachment distances along each arm (where rounded corner curbs meet straight curbs).
     */
    private static Map<UUID, Double> calculateBaseFilletDistances(RoadNode node, List<RoadEdge> edges, JunctionStyle style) {
        Map<UUID, Double> baseDistances = new HashMap<>();
        if (edges == null || edges.isEmpty()) return baseDistances;

        List<JunctionArm> arms = buildArms(node, edges);
        if (arms.isEmpty()) return baseDistances;

        if (arms.size() == 1) {
            JunctionArm arm = arms.get(0);
            double base = Math.max(arm.leftRoadwayWidth(), arm.rightRoadwayWidth());
            baseDistances.put(arm.edgeId(), Math.max(4.0, base + 2.0));
            return baseDistances;
        }

        arms.sort(Comparator.comparingDouble(JunctionArm::angle));
        int n = arms.size();

        double[] lambdaLeft = new double[n];
        double[] lambdaRight = new double[n];

        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            JunctionArm armI = arms.get(i);
            JunctionArm armJ = arms.get(j);

            double deltaX = -armJ.normal().x * armJ.leftRoadwayWidth() - armI.normal().x * armI.rightRoadwayWidth();
            double deltaZ = -armJ.normal().z * armJ.leftRoadwayWidth() - armI.normal().z * armI.rightRoadwayWidth();
            double det = armI.direction().x * (-armJ.direction().z) - armI.direction().z * (-armJ.direction().x);

            double lI;
            double lJ;
            if (Math.abs(det) > 0.05) {
                lI = (deltaX * (-armJ.direction().z) - deltaZ * (-armJ.direction().x)) / det;
                lJ = (armI.direction().x * deltaZ - armI.direction().z * deltaX) / det;
            } else {
                lI = Math.max(armI.rightRoadwayWidth(), armJ.leftRoadwayWidth());
                lJ = lI;
            }

            double minI = Math.max(armI.leftRoadwayWidth(), armI.rightRoadwayWidth());
            double minJ = Math.max(armJ.leftRoadwayWidth(), armJ.rightRoadwayWidth());
            lI = Math.max(minI, Math.min(48.0, Math.max(0.0, lI)));
            lJ = Math.max(minJ, Math.min(48.0, Math.max(0.0, lJ)));

            lambdaRight[i] = lI;
            lambdaLeft[j] = lJ;
        }

        double filletSetback = (style == JunctionStyle.FLUSH) ? 1.0 : 4.5;
        for (int i = 0; i < n; i++) {
            JunctionArm arm = arms.get(i);
            double entry = Math.max(lambdaLeft[i], lambdaRight[i]);
            double minCut = Math.max(arm.leftRoadwayWidth(), arm.rightRoadwayWidth()) + 1.0;
            baseDistances.put(arm.edgeId(), Math.max(minCut, entry + filletSetback));
        }
        return baseDistances;
    }

    /**
     * Calculates the full arm entry cutoff distances along each edge (including crosswalk + stop line approaches for CITY).
     */
    public static Map<UUID, Double> calculateArmEntryDistances(RoadNode node, List<RoadEdge> edges, JunctionStyle style) {
        Map<UUID, Double> base = calculateBaseFilletDistances(node, edges, style);
        Map<UUID, Double> entries = new HashMap<>();
        boolean hasCrosswalk = (style == JunctionStyle.CITY || style == JunctionStyle.SIGNALED);
        double nodeMinRadius = node.getRadius() > 0.0 ? node.getRadius() : 0.0;

        for (Map.Entry<UUID, Double> e : base.entrySet()) {
            double armDist = e.getValue() + (hasCrosswalk ? 8.0 : 0.0);
            if (nodeMinRadius > 0.0) {
                armDist = Math.max(armDist, nodeMinRadius);
            }
            entries.put(e.getKey(), armDist);
        }
        return entries;
    }

    public static Map<UUID, Double> calculateArmEntryDistances(RoadNode node, List<RoadEdge> edges) {
        return calculateArmEntryDistances(node, edges, JunctionStyle.fromString(node.getJunctionStyle()));
    }

    /**
     * Gets the entry distance along the specified edge curve where it enters this junction.
     */
    public static double getArmEntryDistance(RoadNode node, List<RoadEdge> edges, UUID edgeId) {
        Map<UUID, Double> entries = calculateArmEntryDistances(node, edges);
        double analytical = (edgeId != null) ? entries.getOrDefault(edgeId, 0.0) : 0.0;
        if (analytical <= 0.0) {
            analytical = calculateJunctionRadius(node, edges);
        }
        return analytical;
    }

    /**
     * Computes the outer junction radius covering all arm approaches.
     */
    public static double calculateJunctionRadius(RoadNode node, List<RoadEdge> edges) {
        Map<UUID, Double> entries = calculateArmEntryDistances(node, edges);
        double maxEntry = 6.0;
        for (double d : entries.values()) {
            if (d > maxEntry) maxEntry = d;
        }
        return maxEntry;
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
        Map<UUID, Double> armEntries = calculateArmEntryDistances(node, edges, style);
        Map<UUID, Double> filletEntries = new HashMap<>();
        boolean hasCrosswalk = (style == JunctionStyle.CITY || style == JunctionStyle.SIGNALED);
        for (Map.Entry<UUID, Double> e : armEntries.entrySet()) {
            filletEntries.put(e.getKey(), hasCrosswalk ? Math.max(4.5, e.getValue() - 8.0) : e.getValue());
        }

        double maxArmEntry = 6.0;
        for (double e : armEntries.values()) {
            if (e > maxArmEntry) maxArmEntry = e;
        }

        // 1. Build and sort arms counter-clockwise
        List<JunctionArm> arms = buildArms(node, edges);
        if (arms.isEmpty()) {
            return result;
        }
        arms.sort(Comparator.comparingDouble(JunctionArm::angle));
        int numArms = arms.size();

        ModularRoadPreset primaryPreset = arms.get(0).preset();
        BlockState defaultRoadway = Blocks.GRAY_CONCRETE_POWDER.defaultBlockState();
        BlockState defaultFoundation = Blocks.STONE.defaultBlockState();
        BlockState curbStairBase = Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();
        BlockState markingStairBase = Blocks.SMOOTH_QUARTZ_STAIRS.defaultBlockState();
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

        // 2. Build corner fillets attaching cleanly at each arm's inner fillet distance
        List<CornerFillet> cornerFillets = new ArrayList<>();
        for (int i = 0; i < numArms; i++) {
            int j = (i + 1) % numArms;
            JunctionArm armI = arms.get(i);
            JunctionArm armJ = arms.get(j);

            double L_i = filletEntries.getOrDefault(armI.edgeId(), maxArmEntry);
            double L_j = filletEntries.getOrDefault(armJ.edgeId(), maxArmEntry);

            Vector3d p_i_curb = center.add(armI.direction().multiply(L_i)).add(armI.normal().multiply(armI.rightRoadwayWidth()));
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

            int samples = 24;
            List<Vector3d> pts = new ArrayList<>(samples + 1);
            List<Vector3d> norms = new ArrayList<>(samples + 1);
            Vector3d outwardRef = armI.normal().add(armJ.normal().multiply(-1.0));
            if (outwardRef.lengthSquared() < 1e-4) {
                outwardRef = armI.direction().add(armJ.direction());
            }

            for (int s = 0; s <= samples; s++) {
                double u = (double) s / samples;
                Vector3d pt = curbCurve.evaluate(u);
                Vector3d tan = curbCurve.evaluateTangent(u);
                Vector3d nOut = new Vector3d(tan.z, 0, -tan.x).normalize();
                if (nOut.lengthSquared() < 1e-4) {
                    nOut = new Vector3d(0, 0, 1);
                }
                // Guarantee nOut points OUTWARD from intersection center into the corner sidewalk
                Vector3d radial = new Vector3d(pt.x - center.x, 0, pt.z - center.z);
                if (radial.lengthSquared() > 1e-4 ? nOut.dot(radial) < 0 : nOut.dot(outwardRef) < 0) {
                    nOut = nOut.multiply(-1.0);
                }
                pts.add(pt);
                norms.add(nOut);
            }

            boolean hasCurb = armHasCurb(armI) || armHasCurb(armJ);
            boolean hasSidewalk = armHasSidewalk(armI) || armHasSidewalk(armJ);
            double curbWidth = getCornerCurbWidth(armI, armJ);
            double sidewalkWidth = Math.max(getArmSidewalkWidth(armI), getArmSidewalkWidth(armJ));

            cornerFillets.add(new CornerFillet(i, j, curbCurve, pts, norms, hasCurb, hasSidewalk, curbWidth, sidewalkWidth));
        }

        double scanRadius = maxArmEntry + 5.5;
        int minX = (int) Math.floor(center.x - scanRadius);
        int maxX = (int) Math.ceil(center.x + scanRadius);
        int minZ = (int) Math.floor(center.z - scanRadius);
        int maxZ = (int) Math.ceil(center.z + scanRadius);

        Map<BlockPos, BlockState> surfaceMap = new HashMap<>();
        Map<BlockPos, Integer> surfaceYMap = new HashMap<>();

        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                double px = bx + 0.5;
                double pz = bz + 0.5;
                double dx = px - center.x;
                double dz = pz - center.z;
                double distCenter = Math.hypot(dx, dz);

                if (distCenter > scanRadius) {
                    continue;
                }

                boolean isStopLine = false;
                boolean isZebraStripe = false;
                boolean isCenterDash = false;
                Direction centerDashFacing = Direction.NORTH;
                boolean inCorridorRoadway = false;
                boolean isCurb = false;
                boolean isSidewalk = false;
                boolean isGrass = false;
                BlockState cornerCurb = null;

                // 1. Check arm corridors
                for (JunctionArm arm : arms) {
                    double dAlong = dx * arm.direction().x + dz * arm.direction().z;
                    double dLat = dx * arm.normal().x + dz * arm.normal().z;

                    double armEntry = armEntries.getOrDefault(arm.edgeId(), maxArmEntry);
                    double filletEnd = filletEntries.getOrDefault(arm.edgeId(), armEntry);

                    if (dAlong >= -0.5 && dAlong <= armEntry + 0.6) {
                        if (dLat >= -arm.leftRoadwayWidth() - 0.2 && dLat <= arm.rightRoadwayWidth() + 0.2) {
                            if (hasCrosswalk && dAlong >= filletEnd) {
                                // Approach section outside the corner fillet (matching Road_Perekrestok.litematic)
                                if (dAlong >= filletEnd + 0.5 && dAlong <= filletEnd + 4.5) {
                                    // 4-block wide zebra crosswalk: alternating quartz and asphalt stripes
                                    if (isZebraWhiteStripe(bx, bz, arm.direction(), center, filletEnd + 2.5, dLat)) {
                                        isZebraStripe = true;
                                    } else {
                                        inCorridorRoadway = true;
                                    }
                                } else if (dAlong >= filletEnd + 6.0 && dAlong <= filletEnd + 7.0 && dLat <= 0.25) {
                                    // Solid stop line on incoming right-hand lane
                                    isStopLine = true;
                                } else if (dAlong >= filletEnd + 6.0 && Math.abs(dLat) < 0.5 && Math.floorMod((long) Math.floor(dAlong), 4) < 3) {
                                    isCenterDash = true;
                                    centerDashFacing = RoadVoxelizer.facingFromVector(arm.normal().x, arm.normal().z);
                                } else {
                                    inCorridorRoadway = true;
                                }
                            } else {
                                inCorridorRoadway = true;
                            }
                            break;
                        } else if (dAlong >= filletEnd) {
                            // Straight approach curb & mosaic sidewalk strictly outside the corner fillet zone
                            double rw = (dLat >= 0.0) ? arm.rightRoadwayWidth() : arm.leftRoadwayWidth();
                            double totW = (dLat >= 0.0) ? arm.rightTotalWidth() : arm.leftTotalWidth();
                            double absLat = Math.abs(dLat);
                            if (absLat <= rw + 1.2) {
                                if (style != JunctionStyle.FLUSH) {
                                    isCurb = true;
                                    double outSign = (dLat >= 0.0) ? 1.0 : -1.0;
                                    cornerCurb = RoadVoxelizer.orientStairs(curbStairBase,
                                            RoadVoxelizer.facingFromVector(arm.normal().x * outSign, arm.normal().z * outSign), Half.BOTTOM);
                                }
                                break;
                            } else if (absLat <= totW + 0.2) {
                                isSidewalk = true;
                                break;
                            }
                        }
                    }
                }

                // 2. Check corner fillets (ONLY outside the straight arm roadway corridors)
                if (!isStopLine && !isZebraStripe && !isCenterDash && !inCorridorRoadway && !isCurb && !isSidewalk) {
                    double maxOutwardSignedDist = -Double.MAX_VALUE;
                    CornerFillet matchingCorner = null;
                    Vector3d matchingNormal = null;
                    boolean inCornerSpandrel = false;

                    for (CornerFillet cf : cornerFillets) {
                        double bestDist = Double.MAX_VALUE;
                        double bestSignedDist = -Double.MAX_VALUE;
                        Vector3d bestNormal = null;

                        for (int s = 0; s < cf.sampledPoints().size(); s++) {
                            Vector3d spt = cf.sampledPoints().get(s);
                            Vector3d snorm = cf.sampledNormals().get(s);

                            double cdx = px - spt.x;
                            double cdz = pz - spt.z;
                            double d = Math.hypot(cdx, cdz);
                            if (d < bestDist) {
                                bestDist = d;
                                bestSignedDist = cdx * snorm.x + cdz * snorm.z;
                                bestNormal = snorm;
                            }
                        }

                        if (bestSignedDist > 0.0 && bestDist <= scanRadius) {
                            if (bestSignedDist > maxOutwardSignedDist) {
                                maxOutwardSignedDist = bestSignedDist;
                                matchingCorner = cf;
                                matchingNormal = bestNormal;
                            }
                        } else if (!inCornerSpandrel && bestSignedDist <= 0.0 && bestSignedDist >= -4.5 && bestDist <= 6.0) {
                            JunctionArm cArmI = arms.get(cf.armI());
                            JunctionArm cArmJ = arms.get(cf.armJ());
                            double detIJ = cArmI.direction().x * cArmJ.direction().z - cArmI.direction().z * cArmJ.direction().x;
                            if (detIJ > 0.05) {
                                double dIAl = dx * cArmI.direction().x + dz * cArmI.direction().z;
                                double dJAl = dx * cArmJ.direction().x + dz * cArmJ.direction().z;
                                double dILa = dx * cArmI.normal().x + dz * cArmI.normal().z;
                                double dJLa = dx * cArmJ.normal().x + dz * cArmJ.normal().z;
                                double li = filletEntries.getOrDefault(cArmI.edgeId(), maxArmEntry);
                                double lj = filletEntries.getOrDefault(cArmJ.edgeId(), maxArmEntry);
                                if (dIAl >= 0.0 && dIAl <= li && dJAl >= 0.0 && dJAl <= lj && dILa >= 0.0 && dJLa <= 0.0) {
                                    inCornerSpandrel = true;
                                }
                            }
                        }
                    }

                    if (matchingCorner != null && maxOutwardSignedDist > 0.0) {
                        double cornerCurbW = matchingCorner.curbWidth();
                        double cornerSwW = matchingCorner.sidewalkWidth();
                        double maxCornerExtent = cornerCurbW + (matchingCorner.hasSidewalk() ? cornerSwW : 0.0) + 0.4;

                        if (maxOutwardSignedDist <= maxCornerExtent) {
                            if (style == JunctionStyle.ROUNDED || style == JunctionStyle.SIGNALED || style == JunctionStyle.CITY) {
                                if (maxOutwardSignedDist <= cornerCurbW + 0.15 && matchingCorner.hasCurb()) {
                                    isCurb = true;
                                    cornerCurb = RoadVoxelizer.orientStairs(curbStairBase,
                                            RoadVoxelizer.facingFromVector(matchingNormal.x, matchingNormal.z), Half.BOTTOM);
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
                    } else if (inCornerSpandrel) {
                        inCorridorRoadway = true;
                    }
                }

                if (!inCorridorRoadway && !isStopLine && !isZebraStripe && !isCenterDash && !isCurb && !isSidewalk && !isGrass) {
                    continue;
                }

                // Smooth Y interpolation
                double wCenter = 1.0 / (1.0 + distCenter);
                double ySum = center.y * wCenter;
                double wSum = wCenter;
                for (JunctionArm arm : arms) {
                    double dAlong = dx * arm.direction().x + dz * arm.direction().z;
                    if (dAlong > 0.0) {
                        double w = dAlong / maxArmEntry;
                        ySum += arm.entryY() * w;
                        wSum += w;
                    }
                }
                int surfaceY = (int) Math.round(ySum / wSum);

                BlockState surfaceState;
                if (isStopLine || isZebraStripe) {
                    surfaceState = stopLineState;
                } else if (isCenterDash) {
                    surfaceState = RoadVoxelizer.orientStairs(markingStairBase, centerDashFacing, Half.BOTTOM);
                } else if (isCurb) {
                    surfaceState = cornerCurb != null ? cornerCurb : Blocks.POLISHED_ANDESITE_STAIRS.defaultBlockState();
                } else if (isSidewalk) {
                    long h = ((long) bx * 0x9E3779B97F4A7C15L) ^ ((long) bz * 0xBF58476D1CE4E5B9L) ^ ((long) surfaceY * 0x94D049BB133111EBL);
                    h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
                    h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
                    h = h ^ (h >>> 31);
                    int idx = (int) Math.floorMod(h, (long) RoadVoxelizer.SIDEWALK_MIX.length);
                    surfaceState = RoadVoxelizer.SIDEWALK_MIX[idx];
                } else if (isGrass) {
                    surfaceState = grassState;
                } else {
                    surfaceState = defaultRoadway;
                }

                BlockPos surfacePos = new BlockPos(bx, surfaceY, bz);
                surfaceMap.put(surfacePos, surfaceState);
                surfaceYMap.put(surfacePos, surfaceY);
            }
        }

        // Resolve corner StairShapes cleanly for curved curbs
        RoadVoxelizer.resolveStairShapes(surfaceMap);

        for (Map.Entry<BlockPos, BlockState> entry : surfaceMap.entrySet()) {
            BlockPos surfacePos = entry.getKey();
            BlockState surfaceState = entry.getValue();
            int bx = surfacePos.getX();
            int bz = surfacePos.getZ();
            int surfaceY = surfaceYMap.get(surfacePos);

            result.put(surfacePos, surfaceState);

            for (int cy = surfaceY + 1; cy <= surfaceY + clearanceHeight; cy++) {
                result.put(new BlockPos(bx, cy, bz), airState);
            }

            BlockState subState = (surfaceState == grassState) ? Blocks.DIRT.defaultBlockState() : defaultFoundation;
            result.put(new BlockPos(bx, surfaceY - 1, bz), subState);
            result.put(new BlockPos(bx, surfaceY - 2, bz), subState);

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

    private static double getCornerCurbWidth(JunctionArm a, JunctionArm b) {
        double wA = getArmCurbWidth(a);
        double wB = getArmCurbWidth(b);
        double maxW = Math.max(wA, wB);
        return maxW > 0 ? maxW : 1.0;
    }

    private static double getArmCurbWidth(JunctionArm arm) {
        if (arm.moduleCurves() != null) {
            for (RoadModuleCurve mc : arm.moduleCurves()) {
                if (mc.isCurb()) return mc.getWidth();
            }
        }
        for (RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.CURB) return elem.getWidth();
        }
        return 0.0;
    }

    private static double getArmSidewalkWidth(JunctionArm arm) {
        double w = 0.0;
        for (RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.SIDEWALK || elem.getType() == RoadElementType.SHOULDER) {
                w = Math.max(w, elem.getWidth());
            }
        }
        return w > 0.0 ? w : 4.0;
    }

    private static boolean armHasCurb(JunctionArm arm) {
        if (arm.moduleCurves() != null) {
            for (RoadModuleCurve mc : arm.moduleCurves()) {
                if (mc.isCurb()) return true;
            }
        }
        for (RoadStackElement elem : arm.preset().getStack()) {
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
        for (RoadStackElement elem : arm.preset().getStack()) {
            if (elem.getType() == RoadElementType.SIDEWALK) return true;
        }
        return false;
    }

    /**
     * Evaluates whether block (bx, bz) belongs to a white zebra crosswalk stripe.
     * Near-cardinal arms snap stripes along the dominant world grid axis so stripes never form jagged checkerboards.
     */
    public static boolean isZebraWhiteStripe(int bx, int bz, Vector3d armDir, Vector3d center, double zebraMidDist, double dLat) {
        if (Math.abs(armDir.x) < 0.38) {
            int centerColX = (int) Math.floor(center.x + armDir.x * zebraMidDist);
            return Math.floorMod(bx - centerColX, 2) == 1;
        }
        if (Math.abs(armDir.z) < 0.38) {
            int centerRowZ = (int) Math.floor(center.z + armDir.z * zebraMidDist);
            return Math.floorMod(bz - centerRowZ, 2) == 1;
        }
        return Math.floorMod((int) Math.floor(dLat + 0.5), 2) == 1;
    }
}
