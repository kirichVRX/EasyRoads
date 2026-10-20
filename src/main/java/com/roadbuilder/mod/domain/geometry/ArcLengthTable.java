package com.roadbuilder.mod.domain.geometry;

import java.util.Arrays;

/**
 * Precomputed arc length table for mapping distance to parametric 't' (and vice-versa).
 * Strictly isolated: no Minecraft dependencies.
 */
public final class ArcLengthTable {
    private final double[] tValues;
    private final double[] lengths;
    private final double totalLength;

    public ArcLengthTable(BezierCurve3D curve, int sampleCount) {
        if (sampleCount < 2) {
            sampleCount = 2;
        }
        this.tValues = new double[sampleCount];
        this.lengths = new double[sampleCount];

        this.tValues[0] = 0.0;
        this.lengths[0] = 0.0;

        Vector3d prevPoint = curve.evaluate(0.0);
        double accumulatedLength = 0.0;

        for (int i = 1; i < sampleCount; i++) {
            double t = (double) i / (sampleCount - 1);
            Vector3d currentPoint = curve.evaluate(t);
            accumulatedLength += prevPoint.distanceTo(currentPoint);

            this.tValues[i] = t;
            this.lengths[i] = accumulatedLength;
            prevPoint = currentPoint;
        }

        this.totalLength = accumulatedLength;
    }

    public double getTotalLength() {
        return totalLength;
    }

    /**
     * Maps arc length distance [0, totalLength] to parametric 't' [0, 1].
     */
    public double getTForDistance(double distance) {
        if (totalLength <= 1e-9 || distance <= 0.0) {
            return 0.0;
        }
        if (distance >= totalLength) {
            return 1.0;
        }

        int index = Arrays.binarySearch(lengths, distance);
        if (index >= 0) {
            return tValues[index];
        }

        int insertionPoint = -index - 1;
        int lowerIndex = Math.max(0, insertionPoint - 1);
        int upperIndex = Math.min(lengths.length - 1, insertionPoint);

        if (lowerIndex == upperIndex) {
            return tValues[lowerIndex];
        }

        double segmentLength = lengths[upperIndex] - lengths[lowerIndex];
        if (segmentLength <= 1e-9) {
            return tValues[lowerIndex];
        }

        double alpha = (distance - lengths[lowerIndex]) / segmentLength;
        return tValues[lowerIndex] + alpha * (tValues[upperIndex] - tValues[lowerIndex]);
    }

    /**
     * Maps parameter t [0, 1] to arc length distance [0, totalLength].
     */
    public double getDistanceForT(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return totalLength;

        int index = Arrays.binarySearch(tValues, t);
        if (index >= 0) {
            return lengths[index];
        }

        int insertionPoint = -index - 1;
        int lowerIndex = Math.max(0, insertionPoint - 1);
        int upperIndex = Math.min(tValues.length - 1, insertionPoint);

        if (lowerIndex == upperIndex) {
            return lengths[lowerIndex];
        }

        double segmentT = tValues[upperIndex] - tValues[lowerIndex];
        if (segmentT <= 1e-9) {
            return lengths[lowerIndex];
        }

        double alpha = (t - tValues[lowerIndex]) / segmentT;
        return lengths[lowerIndex] + alpha * (lengths[upperIndex] - lengths[lowerIndex]);
    }
}
