package com.roadbuilder.mod.domain.geometry;

import java.util.Objects;

/**
 * Immutable 3D vector for pure geometric calculations.
 * Strict isolation: MUST NOT import any Minecraft classes.
 */
public final class Vector3d {
    public static final Vector3d ZERO = new Vector3d(0.0, 0.0, 0.0);
    public static final Vector3d UP = new Vector3d(0.0, 1.0, 0.0);
    public static final Vector3d DOWN = new Vector3d(0.0, -1.0, 0.0);

    public final double x;
    public final double y;
    public final double z;

    public Vector3d(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3d add(Vector3d other) {
        return new Vector3d(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public Vector3d add(double dx, double dy, double dz) {
        return new Vector3d(this.x + dx, this.y + dy, this.z + dz);
    }

    public Vector3d subtract(Vector3d other) {
        return new Vector3d(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public Vector3d subtract(double dx, double dy, double dz) {
        return new Vector3d(this.x - dx, this.y - dy, this.z - dz);
    }

    public Vector3d multiply(double scalar) {
        return new Vector3d(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    public Vector3d divide(double scalar) {
        if (Math.abs(scalar) < 1e-9) {
            return ZERO;
        }
        return new Vector3d(this.x / scalar, this.y / scalar, this.z / scalar);
    }

    public double dot(Vector3d other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    public Vector3d cross(Vector3d other) {
        return new Vector3d(
                this.y * other.z - this.z * other.y,
                this.z * other.x - this.x * other.z,
                this.x * other.y - this.y * other.x
        );
    }

    public double lengthSquared() {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public Vector3d normalize() {
        double len = length();
        if (len < 1e-9) {
            return ZERO;
        }
        return new Vector3d(this.x / len, this.y / len, this.z / len);
    }

    public double distanceTo(Vector3d other) {
        return Math.sqrt(distanceToSquared(other));
    }

    public double distanceToSquared(Vector3d other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        double dz = this.z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistanceTo(Vector3d other) {
        double dx = this.x - other.x;
        double dz = this.z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public Vector3d lerp(Vector3d target, double alpha) {
        return new Vector3d(
                this.x + (target.x - this.x) * alpha,
                this.y + (target.y - this.y) * alpha,
                this.z + (target.z - this.z) * alpha
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vector3d vector3d = (Vector3d) o;
        return Double.compare(vector3d.x, x) == 0 &&
                Double.compare(vector3d.y, y) == 0 &&
                Double.compare(vector3d.z, z) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z);
    }

    @Override
    public String toString() {
        return String.format("Vector3d(%.3f, %.3f, %.3f)", x, y, z);
    }
}
