package com.roadbuilder.mod.domain.geometry;

import java.util.Objects;

/**
 * Cubic 3D Bezier curve defined by 4 control points: p0, p1, p2, p3.
 * Strictly isolated: MUST NOT import any Minecraft classes.
 */
public final class BezierCurve3D {
    private final Vector3d p0;
    private final Vector3d p1;
    private final Vector3d p2;
    private final Vector3d p3;

    private volatile ArcLengthTable arcLengthTable;

    public BezierCurve3D(Vector3d p0, Vector3d p1, Vector3d p2, Vector3d p3) {
        this.p0 = Objects.requireNonNull(p0, "p0 cannot be null");
        this.p1 = Objects.requireNonNull(p1, "p1 cannot be null");
        this.p2 = Objects.requireNonNull(p2, "p2 cannot be null");
        this.p3 = Objects.requireNonNull(p3, "p3 cannot be null");
    }

    public Vector3d getP0() {
        return p0;
    }

    public Vector3d getP1() {
        return p1;
    }

    public Vector3d getP2() {
        return p2;
    }

    public Vector3d getP3() {
        return p3;
    }

    /**
     * Evaluates position on the curve at parameter t [0, 1].
     */
    public Vector3d evaluate(double t) {
        double u = 1.0 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;

        return p0.multiply(uuu)
                .add(p1.multiply(3.0 * uu * t))
                .add(p2.multiply(3.0 * u * tt))
                .add(p3.multiply(ttt));
    }

    /**
     * Evaluates first derivative (velocity vector) at parameter t [0, 1].
     */
    public Vector3d evaluateDerivative(double t) {
        double u = 1.0 - t;
        Vector3d d0 = p1.subtract(p0).multiply(3.0 * u * u);
        Vector3d d1 = p2.subtract(p1).multiply(6.0 * u * t);
        Vector3d d2 = p3.subtract(p2).multiply(3.0 * t * t);
        return d0.add(d1).add(d2);
    }

    /**
     * Evaluates unit tangent vector at parameter t [0, 1].
     */
    public Vector3d evaluateTangent(double t) {
        Vector3d derivative = evaluateDerivative(t);
        Vector3d norm = derivative.normalize();
        if (norm.lengthSquared() < 1e-6) {
            // Fallback if derivative is degenerate: chord between endpoints
            Vector3d chord = p3.subtract(p0).normalize();
            return chord.lengthSquared() > 1e-6 ? chord : new Vector3d(0, 0, 1);
        }
        return norm;
    }

    /**
     * Computes the lateral normal vector across the road width at parameter t [0, 1].
     * (tangent x upGuide)
     */
    public Vector3d evaluateNormal(double t, Vector3d upGuide) {
        Vector3d tangent = evaluateTangent(t);
        Vector3d normal = tangent.cross(upGuide).normalize();
        if (normal.lengthSquared() < 1e-6) {
            Vector3d altUp = Math.abs(upGuide.y) > 0.9 ? new Vector3d(1, 0, 0) : Vector3d.UP;
            normal = tangent.cross(altUp).normalize();
        }
        return normal;
    }

    /**
     * Lazily creates or returns the arc length table with 64 samples.
     */
    public ArcLengthTable getArcLengthTable() {
        if (arcLengthTable == null) {
            synchronized (this) {
                if (arcLengthTable == null) {
                    arcLengthTable = new ArcLengthTable(this, 64);
                }
            }
        }
        return arcLengthTable;
    }

    public double getApproximateLength() {
        return getArcLengthTable().getTotalLength();
    }

    /**
     * Evaluates position at arc length distance along the curve.
     */
    public Vector3d evaluateAtDistance(double distance) {
        double t = getArcLengthTable().getTForDistance(distance);
        return evaluate(t);
    }

    /**
     * Evaluates tangent at arc length distance along the curve.
     */
    public Vector3d evaluateTangentAtDistance(double distance) {
        double t = getArcLengthTable().getTForDistance(distance);
        return evaluateTangent(t);
    }

    /**
     * Result of projecting a 2D (x, z) point onto the curve.
     */
    public record CurveProjection2D(double t, double distanceAlongCurve, double lateralDistance, Vector3d curvePoint, Vector3d tangent) {}

    /**
     * Projects a 2D point (x, z) onto this 3D Bezier curve (projected to XZ plane).
     * Computes the exact parameter t, arc length distance, lateral signed offset (+right, -left),
     * and the 3D position on the curve centerline.
     */
    public CurveProjection2D projectPoint2D(double qx, double qz) {
        ArcLengthTable table = getArcLengthTable();
        double totalLen = table.getTotalLength();
        if (totalLen < 1e-4) {
            Vector3d p = evaluate(0.0);
            return new CurveProjection2D(0.0, 0.0, 0.0, p, evaluateTangent(0.0));
        }

        // Coarse search along arc length samples (~0.5m intervals)
        int numSamples = Math.max(16, (int) Math.ceil(totalLen * 2.0));
        double bestDistSq = Double.MAX_VALUE;
        double bestDistAlong = 0.0;

        for (int i = 0; i <= numSamples; i++) {
            double s = (double) i / numSamples * totalLen;
            double t = table.getTForDistance(s);
            Vector3d p = evaluate(t);
            double dx = qx - p.x;
            double dz = qz - p.z;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestDistAlong = s;
            }
        }

        // Fine refinement: ternary / golden search around bestDistAlong
        double searchRadius = Math.max(0.5, totalLen / numSamples);
        double leftS = Math.max(0.0, bestDistAlong - searchRadius);
        double rightS = Math.min(totalLen, bestDistAlong + searchRadius);

        for (int iter = 0; iter < 12; iter++) {
            double m1 = leftS + (rightS - leftS) / 3.0;
            double m2 = rightS - (rightS - leftS) / 3.0;

            Vector3d p1 = evaluate(table.getTForDistance(m1));
            Vector3d p2 = evaluate(table.getTForDistance(m2));

            double d1 = (qx - p1.x) * (qx - p1.x) + (qz - p1.z) * (qz - p1.z);
            double d2 = (qx - p2.x) * (qx - p2.x) + (qz - p2.z) * (qz - p2.z);

            if (d1 < d2) {
                rightS = m2;
            } else {
                leftS = m1;
            }
        }

        double finalDistAlong = (leftS + rightS) * 0.5;
        double finalT = table.getTForDistance(finalDistAlong);
        Vector3d curvePoint = evaluate(finalT);
        Vector3d tangent = evaluateTangent(finalT);

        // Vector from curve point to query point in XZ plane
        double dx = qx - curvePoint.x;
        double dz = qz - curvePoint.z;

        // Tangent in XZ plane
        double tx = tangent.x;
        double tz = tangent.z;
        double tLen = Math.sqrt(tx * tx + tz * tz);
        if (tLen > 1e-6) {
            tx /= tLen;
            tz /= tLen;
        } else {
            tx = 0;
            tz = 1;
        }

        // 2D Normal vector (facing right: (tz, -tx))
        // Cross product / 2D determinant gives signed lateral distance:
        // lateral > 0 = right of curve, lateral < 0 = left of curve
        double lateralDist = dx * tz - dz * tx;

        return new CurveProjection2D(finalT, finalDistAlong, lateralDist, curvePoint, tangent);
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BezierCurve3D that = (BezierCurve3D) o;
        return p0.equals(that.p0) && p1.equals(that.p1) && p2.equals(that.p2) && p3.equals(that.p3);
    }

    @Override
    public int hashCode() {
        return Objects.hash(p0, p1, p2, p3);
    }

    @Override
    public String toString() {
        return "BezierCurve3D{" +
                "p0=" + p0 +
                ", p1=" + p1 +
                ", p2=" + p2 +
                ", p3=" + p3 +
                '}';
    }
}
