package com.roadbuilder.mod.domain.graph;

import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.profile.ModularRoadPreset;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.domain.profile.TransitionProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An edge connecting two nodes in the road graph, shaped by a center Bezier curve
 * and an individual set of 3D module curves for each element in the road stack (lanes, curbs, sidewalks, markings).
 * Rule 12: Edge UUID is immutable (final).
 */
public final class RoadEdge {
    private final UUID id;
    private final UUID nodeAId;
    private final UUID nodeBId;
    private final BezierCurve3D curve;
    private final RoadProfile profile;
    private String presetId;
    private String curbPalette;
    private String sidewalkPalette;
    private String lampStyle;
    private int lampSpacing;
    private Boolean sidewalkDoubleLamps;
    private String lampPlacement;
    private String guardrailStyle;
    private Boolean noiseWallEnabled;
    private String stackGuardrails;
    private String stackGreenery;
    private int treeSpacing;
    private int bushSpacing;
    private String embankmentMode;
    private String embankmentShape;
    private String embankmentMaterial;
    private String slopeMode;
    private String slopeStyle;
    private String slopeMaterial;
    private String tunnelShape;
    private List<RoadModuleCurve> moduleCurves = new ArrayList<>();

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves, String curbPalette, String sidewalkPalette) {
        this.id = Objects.requireNonNull(id, "Edge id cannot be null");
        this.nodeAId = Objects.requireNonNull(nodeAId, "nodeAId cannot be null");
        this.nodeBId = Objects.requireNonNull(nodeBId, "nodeBId cannot be null");
        this.curve = Objects.requireNonNull(curve, "curve cannot be null");
        this.profile = Objects.requireNonNull(profile, "profile cannot be null");
        this.presetId = (presetId != null && !presetId.isEmpty()) ? presetId : (profile.getName() != null ? profile.getName() : "default");
        this.moduleCurves = (moduleCurves != null) ? new ArrayList<>(moduleCurves) : new ArrayList<>();
        this.curbPalette = curbPalette;
        this.sidewalkPalette = sidewalkPalette;
    }

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves) {
        this(id, nodeAId, nodeBId, curve, profile, presetId, moduleCurves, null, null);
    }

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId) {
        this(id, nodeAId, nodeBId, curve, profile, presetId, null);
    }

    public RoadEdge(UUID id, UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this(id, nodeAId, nodeBId, curve, profile, profile != null ? profile.getName() : "default");
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId, List<RoadModuleCurve> moduleCurves) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, presetId, moduleCurves);
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile, String presetId) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, presetId);
    }

    public RoadEdge(UUID nodeAId, UUID nodeBId, BezierCurve3D curve, RoadProfile profile) {
        this(UUID.randomUUID(), nodeAId, nodeBId, curve, profile, profile != null ? profile.getName() : "default");
    }

    public UUID getId() {
        return id;
    }

    public UUID getNodeAId() {
        return nodeAId;
    }

    public UUID getNodeBId() {
        return nodeBId;
    }

    public BezierCurve3D getCurve() {
        return curve;
    }

    public RoadProfile getProfile() {
        return profile;
    }

    public String getPresetId() {
        return presetId;
    }

    public void setPresetId(String presetId) {
        this.presetId = presetId != null ? presetId : "default";
    }

    public String getCurbPalette() {
        return curbPalette;
    }

    public void setCurbPalette(String curbPalette) {
        this.curbPalette = (curbPalette != null && !curbPalette.isBlank()) ? curbPalette : null;
    }

    public String getSidewalkPalette() {
        return sidewalkPalette;
    }

    public void setSidewalkPalette(String sidewalkPalette) {
        this.sidewalkPalette = (sidewalkPalette != null && !sidewalkPalette.isBlank()) ? sidewalkPalette : null;
    }

    public String getLampStyle() { return lampStyle; }
    public void setLampStyle(String lampStyle) {
        this.lampStyle = (lampStyle != null && !lampStyle.isBlank()) ? lampStyle : null;
    }

    public int getLampSpacing() { return lampSpacing; }
    public void setLampSpacing(int lampSpacing) { this.lampSpacing = lampSpacing; }

    public Boolean getSidewalkDoubleLamps() { return sidewalkDoubleLamps; }
    public void setSidewalkDoubleLamps(Boolean sidewalkDoubleLamps) { this.sidewalkDoubleLamps = sidewalkDoubleLamps; }

    public String getLampPlacement() { return lampPlacement; }
    public void setLampPlacement(String lampPlacement) {
        this.lampPlacement = (lampPlacement != null && !lampPlacement.isBlank()) ? lampPlacement : null;
    }

    public String getGuardrailStyle() { return guardrailStyle; }
    public void setGuardrailStyle(String guardrailStyle) {
        this.guardrailStyle = (guardrailStyle != null && !guardrailStyle.isBlank()) ? guardrailStyle : null;
    }

    public Boolean getNoiseWallEnabled() { return noiseWallEnabled; }
    public void setNoiseWallEnabled(Boolean noiseWallEnabled) { this.noiseWallEnabled = noiseWallEnabled; }

    public String getStackGuardrails() { return stackGuardrails; }
    public void setStackGuardrails(String stackGuardrails) {
        this.stackGuardrails = (stackGuardrails != null && !stackGuardrails.isBlank()) ? stackGuardrails : null;
    }

    public String getStackGreenery() { return stackGreenery; }
    public void setStackGreenery(String stackGreenery) {
        this.stackGreenery = (stackGreenery != null && !stackGreenery.isBlank()) ? stackGreenery : null;
    }

    public int getTreeSpacing() { return treeSpacing; }
    public void setTreeSpacing(int treeSpacing) { this.treeSpacing = treeSpacing; }

    public int getBushSpacing() { return bushSpacing; }
    public void setBushSpacing(int bushSpacing) { this.bushSpacing = bushSpacing; }

    public String getEmbankmentMode() { return embankmentMode; }
    public void setEmbankmentMode(String embankmentMode) {
        this.embankmentMode = (embankmentMode != null && !embankmentMode.isBlank()) ? embankmentMode : null;
    }

    public String getEmbankmentShape() { return embankmentShape; }
    public void setEmbankmentShape(String embankmentShape) {
        this.embankmentShape = (embankmentShape != null && !embankmentShape.isBlank()) ? embankmentShape : null;
    }

    public String getEmbankmentMaterial() { return embankmentMaterial; }
    public void setEmbankmentMaterial(String embankmentMaterial) {
        this.embankmentMaterial = (embankmentMaterial != null && !embankmentMaterial.isBlank()) ? embankmentMaterial : null;
    }

    public String getSlopeMode() { return slopeMode; }
    public void setSlopeMode(String slopeMode) {
        this.slopeMode = (slopeMode != null && !slopeMode.isBlank()) ? slopeMode : null;
    }

    public String getSlopeStyle() { return slopeStyle; }
    public void setSlopeStyle(String slopeStyle) {
        this.slopeStyle = (slopeStyle != null && !slopeStyle.isBlank()) ? slopeStyle : null;
    }

    public String getSlopeMaterial() { return slopeMaterial; }
    public void setSlopeMaterial(String slopeMaterial) {
        this.slopeMaterial = (slopeMaterial != null && !slopeMaterial.isBlank()) ? slopeMaterial : null;
    }

    public String getTunnelShape() { return tunnelShape; }
    public void setTunnelShape(String tunnelShape) {
        this.tunnelShape = (tunnelShape != null && !tunnelShape.isBlank()) ? tunnelShape : null;
    }

    public void applyStyleFromPreset(ModularRoadPreset preset) {
        if (preset == null) return;
        setCurbPalette(preset.getCurbPalette().name());
        setSidewalkPalette(preset.getSidewalkPalette().name());
        setLampStyle(preset.getLampStyle().name());
        setLampSpacing(preset.getLampSpacing());
        setSidewalkDoubleLamps(preset.isSidewalkDoubleLamps());
        setLampPlacement(preset.getLampPlacement().name());
        setGuardrailStyle(preset.getGuardrailStyle().name());
        setNoiseWallEnabled(preset.isNoiseWallEnabled());
        setStackGuardrails(preset.serializeElementGuardrails());
        setStackGreenery(preset.serializeElementGreenery());
        setTreeSpacing(preset.getTreeSpacing());
        setBushSpacing(preset.getBushSpacing());
        setEmbankmentMode(preset.getEmbankmentMode().name());
        setEmbankmentShape(preset.getEmbankmentShape().name());
        setEmbankmentMaterial(preset.getEmbankmentMaterial().name());
        setSlopeMode(preset.getSlopeMode().name());
        setSlopeStyle(preset.getSlopeStyle().name());
        setSlopeMaterial(preset.getSlopeMaterial().name());
        setTunnelShape(preset.getTunnelShape().name());
    }

    public void copyStyleFrom(RoadEdge other) {
        if (other == null) return;
        setCurbPalette(other.getCurbPalette());
        setSidewalkPalette(other.getSidewalkPalette());
        setLampStyle(other.getLampStyle());
        setLampSpacing(other.getLampSpacing());
        setSidewalkDoubleLamps(other.getSidewalkDoubleLamps());
        setLampPlacement(other.getLampPlacement());
        setGuardrailStyle(other.getGuardrailStyle());
        setNoiseWallEnabled(other.getNoiseWallEnabled());
        setStackGuardrails(other.getStackGuardrails());
        setStackGreenery(other.getStackGreenery());
        setTreeSpacing(other.getTreeSpacing());
        setBushSpacing(other.getBushSpacing());
        setEmbankmentMode(other.getEmbankmentMode());
        setEmbankmentShape(other.getEmbankmentShape());
        setEmbankmentMaterial(other.getEmbankmentMaterial());
        setSlopeMode(other.getSlopeMode());
        setSlopeStyle(other.getSlopeStyle());
        setSlopeMaterial(other.getSlopeMaterial());
        setTunnelShape(other.getTunnelShape());
    }

    public List<RoadModuleCurve> getModuleCurves() {
        return moduleCurves != null ? Collections.unmodifiableList(moduleCurves) : Collections.emptyList();
    }

    public void setModuleCurves(List<RoadModuleCurve> moduleCurves) {
        this.moduleCurves = moduleCurves != null ? new ArrayList<>(moduleCurves) : new ArrayList<>();
    }

    public void regenerateModuleCurves(ModularRoadPreset preset, TransitionProfile transitionAtP0, TransitionProfile transitionAtP3) {
        this.moduleCurves = RoadModuleCurve.generateModuleCurves(this.curve, preset, transitionAtP0, transitionAtP3);
    }

    public boolean connectsNode(UUID nodeId) {
        return nodeAId.equals(nodeId) || nodeBId.equals(nodeId);
    }

    public UUID getOtherNodeId(UUID nodeId) {
        if (nodeAId.equals(nodeId)) {
            return nodeBId;
        } else if (nodeBId.equals(nodeId)) {
            return nodeAId;
        }
        throw new IllegalArgumentException("Node " + nodeId + " is not an endpoint of this edge " + id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RoadEdge roadEdge = (RoadEdge) o;
        return id.equals(roadEdge.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RoadEdge{" +
                "id=" + id +
                ", preset=" + presetId +
                ", nodeA=" + nodeAId +
                ", nodeB=" + nodeBId +
                ", length=" + String.format("%.2f", curve.getApproximateLength()) +
                '}';
    }
}
