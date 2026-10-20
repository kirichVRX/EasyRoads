package com.roadbuilder.mod.domain.profile;

import java.util.Objects;

/**
 * Represents an individual vertical layer in a road structure cross-section.
 */
public final class RoadLayer {
    private final String blockId;
    private final int depthOffset; // 0 = surface layer, -1 = sub-base, etc.

    public RoadLayer(String blockId, int depthOffset) {
        this.blockId = Objects.requireNonNull(blockId, "blockId cannot be null");
        this.depthOffset = depthOffset;
    }

    public String getBlockId() {
        return blockId;
    }

    public int getDepthOffset() {
        return depthOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadLayer roadLayer = (RoadLayer) o;
        return depthOffset == roadLayer.depthOffset && blockId.equals(roadLayer.blockId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockId, depthOffset);
    }

    @Override
    public String toString() {
        return "RoadLayer{" +
                "blockId='" + blockId + '\'' +
                ", depthOffset=" + depthOffset +
                '}';
    }
}
