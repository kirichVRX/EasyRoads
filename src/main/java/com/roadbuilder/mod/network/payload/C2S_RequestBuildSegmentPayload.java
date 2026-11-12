package com.roadbuilder.mod.network.payload;

import com.roadbuilder.mod.core.RoadBuilderMod;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;
import java.util.UUID;

/**
 * C2S payload sent by the client editor to request building a road segment between two points/nodes.
 */
public record C2S_RequestBuildSegmentPayload(
        Vector3d p0,
        Vector3d p1,
        Vector3d p2,
        Vector3d p3,
        Optional<UUID> startNodeId,
        Optional<UUID> endNodeId,
        String profileName,
        String junctionStyle,
        String curbPalette,
        String sidewalkPalette,
        String lampStyle,
        int lampSpacing,
        boolean sidewalkDoubleLamps,
        String lampPlacement,
        String guardrailStyle,
        boolean noiseWallEnabled,
        String stackGuardrails,
        String stackGreenery,
        int treeSpacing,
        int bushSpacing,
        String embankmentMode,
        String embankmentShape,
        String embankmentMaterial,
        String slopeMode,
        String slopeStyle,
        String slopeMaterial,
        String tunnelShape
) implements CustomPacketPayload {

    public C2S_RequestBuildSegmentPayload(Vector3d p0, Vector3d p1, Vector3d p2, Vector3d p3,
                                          Optional<UUID> startNodeId, Optional<UUID> endNodeId, String profileName) {
        this(p0, p1, p2, p3, startNodeId, endNodeId, profileName, "", "", "", "", 0, false, "", "", false, "", "", 0, 0, "", "", "", "", "", "", "");
    }

    public static final Type<C2S_RequestBuildSegmentPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(RoadBuilderMod.MOD_ID, "request_build_segment"));

    public static final StreamCodec<FriendlyByteBuf, C2S_RequestBuildSegmentPayload> STREAM_CODEC =
            CustomPacketPayload.codec(
                    C2S_RequestBuildSegmentPayload::write,
                    C2S_RequestBuildSegmentPayload::read
            );

    private void write(FriendlyByteBuf buf) {
        writeVector3d(buf, p0);
        writeVector3d(buf, p1);
        writeVector3d(buf, p2);
        writeVector3d(buf, p3);

        buf.writeBoolean(startNodeId.isPresent());
        startNodeId.ifPresent(buf::writeUUID);

        buf.writeBoolean(endNodeId.isPresent());
        endNodeId.ifPresent(buf::writeUUID);

        buf.writeUtf(profileName);
        buf.writeUtf(junctionStyle != null ? junctionStyle : "");
        buf.writeUtf(curbPalette != null ? curbPalette : "");
        buf.writeUtf(sidewalkPalette != null ? sidewalkPalette : "");
        buf.writeUtf(lampStyle != null ? lampStyle : "");
        buf.writeVarInt(lampSpacing);
        buf.writeBoolean(sidewalkDoubleLamps);
        buf.writeUtf(lampPlacement != null ? lampPlacement : "");
        buf.writeUtf(guardrailStyle != null ? guardrailStyle : "");
        buf.writeBoolean(noiseWallEnabled);
        buf.writeUtf(stackGuardrails != null ? stackGuardrails : "");
        buf.writeUtf(stackGreenery != null ? stackGreenery : "");
        buf.writeVarInt(treeSpacing);
        buf.writeVarInt(bushSpacing);
        buf.writeUtf(embankmentMode != null ? embankmentMode : "");
        buf.writeUtf(embankmentShape != null ? embankmentShape : "");
        buf.writeUtf(embankmentMaterial != null ? embankmentMaterial : "");
        buf.writeUtf(slopeMode != null ? slopeMode : "");
        buf.writeUtf(slopeStyle != null ? slopeStyle : "");
        buf.writeUtf(slopeMaterial != null ? slopeMaterial : "");
        buf.writeUtf(tunnelShape != null ? tunnelShape : "");
    }

    private static C2S_RequestBuildSegmentPayload read(FriendlyByteBuf buf) {
        Vector3d p0 = readVector3d(buf);
        Vector3d p1 = readVector3d(buf);
        Vector3d p2 = readVector3d(buf);
        Vector3d p3 = readVector3d(buf);

        Optional<UUID> startNodeId = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();
        Optional<UUID> endNodeId = buf.readBoolean() ? Optional.of(buf.readUUID()) : Optional.empty();

        String profileName = buf.readUtf();
        String junctionStyle = buf.readUtf();
        String curbPalette = buf.readUtf();
        String sidewalkPalette = buf.readUtf();
        String lampStyle = buf.readUtf();
        int lampSpacing = buf.readVarInt();
        boolean sidewalkDoubleLamps = buf.readBoolean();
        String lampPlacement = buf.readUtf();
        String guardrailStyle = buf.readUtf();
        boolean noiseWallEnabled = buf.readBoolean();
        String stackGuardrails = buf.readUtf();
        String stackGreenery = buf.readUtf();
        int treeSpacing = buf.readVarInt();
        int bushSpacing = buf.readVarInt();
        String embankmentMode = buf.readUtf();
        String embankmentShape = buf.readUtf();
        String embankmentMaterial = buf.readUtf();
        String slopeMode = buf.readUtf();
        String slopeStyle = buf.readUtf();
        String slopeMaterial = buf.readUtf();
        String tunnelShape = buf.readUtf();

        return new C2S_RequestBuildSegmentPayload(p0, p1, p2, p3, startNodeId, endNodeId, profileName,
                junctionStyle, curbPalette, sidewalkPalette, lampStyle, lampSpacing, sidewalkDoubleLamps, lampPlacement,
                guardrailStyle, noiseWallEnabled, stackGuardrails,
                stackGreenery, treeSpacing, bushSpacing,
                embankmentMode, embankmentShape, embankmentMaterial,
                slopeMode, slopeStyle, slopeMaterial, tunnelShape);
    }

    private static void writeVector3d(FriendlyByteBuf buf, Vector3d vec) {
        buf.writeDouble(vec.x);
        buf.writeDouble(vec.y);
        buf.writeDouble(vec.z);
    }

    private static Vector3d readVector3d(FriendlyByteBuf buf) {
        return new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
