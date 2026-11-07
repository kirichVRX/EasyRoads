package com.roadbuilder.mod.domain.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles smooth width and element stack transitions between two different ModularRoadPresets.
 */
public class TransitionProfile {

    private final ModularRoadPreset presetA;
    private final ModularRoadPreset presetB;
    private final double transitionLength;

    public TransitionProfile(ModularRoadPreset presetA, ModularRoadPreset presetB, double transitionLength) {
        this.presetA = presetA;
        this.presetB = presetB;
        this.transitionLength = Math.max(4.0, transitionLength);
    }

    public TransitionProfile(ModularRoadPreset presetA, ModularRoadPreset presetB) {
        this(presetA, presetB, Math.max(10.0, Math.max(presetA.getTotalWidth(), presetB.getTotalWidth())));
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

    /**
     * Interpolates between presetA (factor = 0.0) and presetB (factor = 1.0).
     */
    public InterpolatedStack getStackAt(double factor) {
        factor = Math.max(0.0, Math.min(1.0, factor));

        double totalWidthA = presetA.getTotalWidth();
        double totalWidthB = presetB.getTotalWidth();
        double currentTotalWidth = totalWidthA + (totalWidthB - totalWidthA) * factor;

        double roadwayA = presetA.getRoadwayWidth();
        double roadwayB = presetB.getRoadwayWidth();
        double currentRoadwayWidth = roadwayA + (roadwayB - roadwayA) * factor;

        // Base stack selection and proportional width scaling
        ModularRoadPreset basePreset = (factor < 0.5) ? presetA : presetB;
        double baseTotalWidth = basePreset.getTotalWidth();
        double scale = currentTotalWidth / Math.max(1.0, baseTotalWidth);

        List<InterpolatedElement> elements = new ArrayList<>();
        double curOffset = -currentTotalWidth * 0.5;

        for (RoadStackElement elem : basePreset.getStack()) {
            double elemW = elem.getWidth() * scale;
            double nextOffset = curOffset + elemW;
            elements.add(new InterpolatedElement(elem.getType(), elemW, curOffset, nextOffset));
            curOffset = nextOffset;
        }

        return new InterpolatedStack(currentTotalWidth, currentRoadwayWidth, elements);
    }
}
