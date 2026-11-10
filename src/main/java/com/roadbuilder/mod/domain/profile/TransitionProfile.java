package com.roadbuilder.mod.domain.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles smooth width, geometry flaring, chevron safety island, and multi-phase
 * element stack transitions between different ModularRoadPresets (e.g. 2-lane road to 4-lane avenue).
 * Matches realistic civil engineering road design and the reference layout from Road_perehod_template.litematic.
 */
public class TransitionProfile {

    private final ModularRoadPreset presetA;
    private final ModularRoadPreset presetB;
    private final double transitionLength;

    public TransitionProfile(ModularRoadPreset presetA, ModularRoadPreset presetB, double transitionLength) {
        this.presetA = presetA;
        this.presetB = presetB;
        this.transitionLength = Math.max(15.0, transitionLength);
    }

    public TransitionProfile(ModularRoadPreset presetA, ModularRoadPreset presetB) {
        this(presetA, presetB, Math.max(25.0, Math.max(presetA.getTotalWidth(), presetB.getTotalWidth()) * 1.1));
    }

    /**
     * Determines whether the given edge should host the transition between preset and otherPreset,
     * ensuring that EXACTLY ONE of two connected edges performs the transition.
     */
    public static boolean shouldEdgeHostTransition(com.roadbuilder.mod.domain.graph.RoadEdge edge,
                                                   com.roadbuilder.mod.domain.graph.RoadEdge otherEdge,
                                                   ModularRoadPreset preset,
                                                   ModularRoadPreset otherPreset) {
        if (edge == null || otherEdge == null || preset == null || otherPreset == null) return false;
        double w1 = preset.getTotalWidth();
        double w2 = otherPreset.getTotalWidth();
        if (Math.abs(w1 - w2) > 0.01) {
            double len1 = edge.getCurve().getApproximateLength();
            double len2 = otherEdge.getCurve().getApproximateLength();
            // The wider road hosts the transition unless it is extremely short (< 15m) and the other road is long (>= 15m)
            if (w1 > w2) {
                return len1 >= 15.0 || len2 < 15.0;
            } else {
                return len2 < 15.0 && len1 >= 15.0;
            }
        }
        // Same width: deterministic tie-breaker by UUID
        return edge.getId().compareTo(otherEdge.getId()) < 0;
    }

    public ModularRoadPreset getPresetA() {
        return presetA;
    }

    public ModularRoadPreset getPresetB() {
        return presetB;
    }

    public double getTransitionLength() {
        return transitionLength;
    }

    public record InterpolatedElement(RoadElementType type, double width, double startOffset, double endOffset) {}

    public record InterpolatedStack(
            double totalWidth,
            double roadwayWidth,
            List<InterpolatedElement> elements
    ) {
        public InterpolatedElement getElementAt(double sGrid) {
            if (elements.isEmpty()) {
                return new InterpolatedElement(RoadElementType.LANE, 4.0, -2.0, 2.0);
            }
            for (InterpolatedElement elem : elements) {
                if (sGrid >= elem.startOffset() - 1e-6 && sGrid < elem.endOffset() + 1e-6) {
                    return elem;
                }
            }
            if (sGrid < elements.get(0).startOffset()) return elements.get(0);
            return elements.get(elements.size() - 1);
        }
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double getLeftSidewalkWidth(ModularRoadPreset preset) {
        if (preset.getStack().isEmpty()) return 4.0;
        RoadStackElement first = preset.getStack().get(0);
        if (first.getType() == RoadElementType.SIDEWALK || first.getType() == RoadElementType.SHOULDER) {
            return first.getWidth();
        }
        return 0.0;
    }

    private static double getRightSidewalkWidth(ModularRoadPreset preset) {
        if (preset.getStack().isEmpty()) return 4.0;
        RoadStackElement last = preset.getStack().get(preset.getStack().size() - 1);
        if (last.getType() == RoadElementType.SIDEWALK || last.getType() == RoadElementType.SHOULDER) {
            return last.getWidth();
        }
        return 0.0;
    }

    private static RoadElementType getLeftOuterType(ModularRoadPreset preset) {
        if (!preset.getStack().isEmpty()) {
            RoadElementType t = preset.getStack().get(0).getType();
            if (t == RoadElementType.SHOULDER) return RoadElementType.SHOULDER;
        }
        return RoadElementType.SIDEWALK;
    }

    private static RoadElementType getRightOuterType(ModularRoadPreset preset) {
        if (!preset.getStack().isEmpty()) {
            RoadElementType t = preset.getStack().get(preset.getStack().size() - 1).getType();
            if (t == RoadElementType.SHOULDER) return RoadElementType.SHOULDER;
        }
        return RoadElementType.SIDEWALK;
    }

    /**
     * Interpolates between presetA (factor = 0.0) and presetB (factor = 1.0).
     * Follows the civil engineering road transition sequence:
     * 1. S-curve smoothstep flaring for outer boundaries.
     * 2. Outer sidewalks and curbs maintain fixed physical widths instead of stretching.
     * 3. Painted chevron safety island (outline smooth_quartz with asphalt inside).
     * 4. Physical curbed grass divider with blunt nose stair end-cap.
     * 5. Carriageway lane markings emerging/merging smoothly.
     */
    public InterpolatedStack getStackAt(double factor) {
        factor = Math.max(0.0, Math.min(1.0, factor));

        double totalWidthA = presetA.getTotalWidth();
        double totalWidthB = presetB.getTotalWidth();

        // 1. Compute total road width using continuous smoothstep S-curve flaring
        double smoothW = smoothstep(0.0, 1.0, factor);
        double currentTotalWidth = totalWidthA + (totalWidthB - totalWidthA) * smoothW;

        // 2. Outer sidewalks and curbs (smooth continuous interpolation without bulging)
        double swLeftA = getLeftSidewalkWidth(presetA);
        double swLeftB = getLeftSidewalkWidth(presetB);
        double curSwLeft = swLeftA + (swLeftB - swLeftA) * smoothW;

        double swRightA = getRightSidewalkWidth(presetA);
        double swRightB = getRightSidewalkWidth(presetB);
        double curSwRight = swRightA + (swRightB - swRightA) * smoothW;

        double curCurbLeft = 1.0;
        double curCurbRight = 1.0;

        RoadElementType leftOuterType = (factor < 0.5) ? getLeftOuterType(presetA) : getLeftOuterType(presetB);
        RoadElementType rightOuterType = (factor < 0.5) ? getRightOuterType(presetA) : getRightOuterType(presetB);

        // 3. Roadway width between outer curbs
        double currentRoadwayWidth = Math.max(2.0, currentTotalWidth - (curSwLeft + curCurbLeft + curCurbRight + curSwRight));

        // 4. Central element configuration
        boolean hasDivA = presetA.getStack().stream().anyMatch(e -> e.getType() == RoadElementType.DIVIDER_GRASS);
        boolean hasDivB = presetB.getStack().stream().anyMatch(e -> e.getType() == RoadElementType.DIVIDER_GRASS);

        double divWidthA = presetA.getStack().stream()
                .filter(e -> e.getType() == RoadElementType.DIVIDER_GRASS)
                .mapToDouble(RoadStackElement::getWidth)
                .findFirst().orElse(5.0);
        double divWidthB = presetB.getStack().stream()
                .filter(e -> e.getType() == RoadElementType.DIVIDER_GRASS)
                .mapToDouble(RoadStackElement::getWidth)
                .findFirst().orElse(5.0);

        RoadElementType centerType;
        double centerWidth;
        boolean isChevronZone = false;

        if (!hasDivA && hasDivB) {
            // Expanding: Narrow road without divider -> Wide avenue with divider
            if (factor < 0.40) {
                // Zone 1: Single dashed centerline
                centerType = RoadElementType.MARKING_DASHED_LONG;
                centerWidth = 1.0;
            } else if (factor < 0.85) {
                // Zone 2: Painted chevron safety island expanding from 1.0 to full divider width
                double chevU = smoothstep(0.40, 0.85, factor);
                centerType = RoadElementType.MARKING_SOLID;
                centerWidth = 1.0 + (divWidthB - 1.0) * chevU;
                isChevronZone = true;
            } else {
                // Zone 3: Physical curbed grass divider
                centerType = RoadElementType.DIVIDER_GRASS;
                centerWidth = divWidthB;
            }
        } else if (hasDivA && !hasDivB) {
            // Narrowing: Wide avenue with divider -> Narrow road without divider
            // The physical divider ends cleanly with a blunt nose stair end-cap (factor <= 0.15)
            if (factor <= 0.15) {
                // Zone 1: Physical curbed grass divider
                centerType = RoadElementType.DIVIDER_GRASS;
                centerWidth = divWidthA;
            } else if (factor <= 0.60) {
                // Zone 2: Painted chevron safety island tapering from full divider width down to 1.0
                double chevU = smoothstep(0.15, 0.60, factor);
                centerType = RoadElementType.MARKING_SOLID;
                centerWidth = divWidthA - (divWidthA - 1.0) * chevU;
                isChevronZone = true;
            } else {
                // Zone 3: Single dashed centerline
                centerType = RoadElementType.MARKING_DASHED_LONG;
                centerWidth = 1.0;
            }
        } else if (hasDivA && hasDivB) {
            centerType = RoadElementType.DIVIDER_GRASS;
            centerWidth = divWidthA + (divWidthB - divWidthA) * smoothW;
        } else {
            // Neither has divider: simple centerline marking
            centerType = (factor < 0.5)
                    ? presetA.getStack().stream().filter(e -> e.getType().isMarking()).map(RoadStackElement::getType).findFirst().orElse(RoadElementType.MARKING_DASHED_LONG)
                    : presetB.getStack().stream().filter(e -> e.getType().isMarking()).map(RoadStackElement::getType).findFirst().orElse(RoadElementType.MARKING_DASHED_LONG);
            centerWidth = 1.0;
        }

        // 5. Carriageway space calculations
        double remainingRoadway = Math.max(1.0, currentRoadwayWidth - centerWidth);
        double leftCarriagewayW = remainingRoadway * 0.5;
        double rightCarriagewayW = remainingRoadway * 0.5;

        boolean leftCarriagewayHasMarking = leftCarriagewayW >= 6.5;
        boolean rightCarriagewayHasMarking = rightCarriagewayW >= 6.5;

        List<InterpolatedElement> elements = new ArrayList<>();
        double curOffset = -currentTotalWidth * 0.5;

        // Element 1: Left Sidewalk / Shoulder
        if (curSwLeft > 0.05) {
            elements.add(new InterpolatedElement(leftOuterType, curSwLeft, curOffset, curOffset + curSwLeft));
            curOffset += curSwLeft;
        }

        // Element 2: Left Curb
        elements.add(new InterpolatedElement(RoadElementType.CURB, curCurbLeft, curOffset, curOffset + curCurbLeft));
        curOffset += curCurbLeft;

        // Element 3: Left Carriageway
        if (leftCarriagewayHasMarking && leftCarriagewayW >= 6.5) {
            double laneW = (leftCarriagewayW - 1.0) * 0.5;
            elements.add(new InterpolatedElement(RoadElementType.LANE, laneW, curOffset, curOffset + laneW));
            curOffset += laneW;
            elements.add(new InterpolatedElement(RoadElementType.MARKING_DASHED_LONG, 1.0, curOffset, curOffset + 1.0));
            curOffset += 1.0;
            elements.add(new InterpolatedElement(RoadElementType.LANE, laneW, curOffset, curOffset + laneW));
            curOffset += laneW;
        } else {
            elements.add(new InterpolatedElement(RoadElementType.LANE, leftCarriagewayW, curOffset, curOffset + leftCarriagewayW));
            curOffset += leftCarriagewayW;
        }

        // Element 4: Center Element (Chevron Island or Divider or Centerline)
        if (isChevronZone) {
            if (centerWidth <= 1.5) {
                // Apex of chevron: single quartz marking matching width exactly
                elements.add(new InterpolatedElement(RoadElementType.MARKING_SOLID, centerWidth, curOffset, curOffset + centerWidth));
                curOffset += centerWidth;
            } else {
                // Outline chevron matching Road_perehod_template.litematic:
                // Left border: 1.0 block smooth quartz
                // Interior: (centerWidth - 2.0) asphalt roadway
                // Right border: 1.0 block smooth quartz
                double interiorW = Math.max(0.1, centerWidth - 2.0);
                double borderW = (centerWidth - interiorW) * 0.5;
                elements.add(new InterpolatedElement(RoadElementType.MARKING_SOLID, borderW, curOffset, curOffset + borderW));
                curOffset += borderW;

                elements.add(new InterpolatedElement(RoadElementType.LANE, interiorW, curOffset, curOffset + interiorW));
                curOffset += interiorW;

                elements.add(new InterpolatedElement(RoadElementType.MARKING_SOLID, borderW, curOffset, curOffset + borderW));
                curOffset += borderW;
            }
        } else {
            elements.add(new InterpolatedElement(centerType, centerWidth, curOffset, curOffset + centerWidth));
            curOffset += centerWidth;
        }

        // Element 5: Right Carriageway
        if (rightCarriagewayHasMarking && rightCarriagewayW >= 6.5) {
            double laneW = (rightCarriagewayW - 1.0) * 0.5;
            elements.add(new InterpolatedElement(RoadElementType.LANE, laneW, curOffset, curOffset + laneW));
            curOffset += laneW;
            elements.add(new InterpolatedElement(RoadElementType.MARKING_DASHED_LONG, 1.0, curOffset, curOffset + 1.0));
            curOffset += 1.0;
            elements.add(new InterpolatedElement(RoadElementType.LANE, laneW, curOffset, curOffset + laneW));
            curOffset += laneW;
        } else {
            elements.add(new InterpolatedElement(RoadElementType.LANE, rightCarriagewayW, curOffset, curOffset + rightCarriagewayW));
            curOffset += rightCarriagewayW;
        }

        // Element 6: Right Curb
        elements.add(new InterpolatedElement(RoadElementType.CURB, curCurbRight, curOffset, curOffset + curCurbRight));
        curOffset += curCurbRight;

        // Element 7: Right Sidewalk / Shoulder
        if (curSwRight > 0.05) {
            elements.add(new InterpolatedElement(rightOuterType, curSwRight, curOffset, curOffset + curSwRight));
            curOffset += curSwRight;
        }

        return new InterpolatedStack(currentTotalWidth, currentRoadwayWidth, elements);
    }
}
