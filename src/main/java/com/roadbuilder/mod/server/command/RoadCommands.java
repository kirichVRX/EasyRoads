package com.roadbuilder.mod.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.roadbuilder.mod.domain.geometry.BezierCurve3D;
import com.roadbuilder.mod.domain.geometry.Vector3d;
import com.roadbuilder.mod.domain.graph.RoadNode;
import com.roadbuilder.mod.domain.profile.RoadProfile;
import com.roadbuilder.mod.server.storage.RoadGraphSavedData;
import com.roadbuilder.mod.server.voxelizer.RoadVoxelizer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * In-game test commands for server engine and graph data.
 */
public final class RoadCommands {

    private RoadCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("road")
                        .requires(cs -> cs.hasPermission(2))
                        .then(Commands.literal("test")
                                .executes(ctx -> runTestRoad(ctx.getSource()))
                        )
                        .then(Commands.literal("stats")
                                .executes(ctx -> runStats(ctx.getSource()))
                        )
                        .then(Commands.literal("clear")
                                .executes(ctx -> runClear(ctx.getSource()))
                        )
                        .then(Commands.literal("undo")
                                .executes(ctx -> runUndo(ctx.getSource()))
                        )
                        .then(Commands.literal("dumplitematic")
                                .executes(ctx -> runDumpLitematic(ctx.getSource()))
                        )
        );
    }

    private static int runDumpLitematic(CommandSourceStack source) {
        try {
            java.io.File file = new java.io.File("c:\\Users\\KarCari5\\AppData\\Roaming\\ModrinthApp\\profiles\\Bilding\\schematics\\road_templates.litematic");
            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            source.sendSuccess(() -> Component.literal("§a[Litematic] Root keys: " + tag.getAllKeys()), false);
            if (tag.contains("Metadata")) {
                source.sendSuccess(() -> Component.literal("§e[Metadata] " + tag.getCompound("Metadata")), false);
            }
            if (tag.contains("Regions")) {
                net.minecraft.nbt.CompoundTag regions = tag.getCompound("Regions");
                for (String regName : regions.getAllKeys()) {
                    net.minecraft.nbt.CompoundTag r = regions.getCompound(regName);
                    net.minecraft.nbt.CompoundTag size = r.getCompound("Size");
                    net.minecraft.nbt.CompoundTag pos = r.getCompound("Position");
                    source.sendSuccess(() -> Component.literal(String.format("§6Region: %s, Size: [%d, %d, %d], Pos: [%d, %d, %d]",
                            regName, size.getInt("x"), size.getInt("y"), size.getInt("z"),
                            pos.getInt("x"), pos.getInt("y"), pos.getInt("z"))), false);

                    net.minecraft.nbt.ListTag pal = r.getList("BlockStatePalette", 10);
                    source.sendSuccess(() -> Component.literal("§bPalette (" + pal.size() + " blocks):"), false);
                    for (int i = 0; i < pal.size(); i++) {
                        int idx = i;
                        net.minecraft.nbt.CompoundTag blockTag = pal.getCompound(i);
                        source.sendSuccess(() -> Component.literal("  " + idx + ": " + blockTag), false);
                    }
                }
            }
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError: " + e.getMessage()));
            e.printStackTrace();
        }
        return 1;
    }

    private static int runTestRoad(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can execute /road test"));
            return 0;
        }

        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();

        Vector3d p0 = new Vector3d(pos.x, pos.y, pos.z);
        Vector3d p1 = p0.add(look.x * 10 + right.x * 5, 0, look.z * 10 + right.z * 5);
        Vector3d p2 = p0.add(look.x * 20 - right.x * 5, 1, look.z * 20 - right.z * 5);
        Vector3d p3 = p0.add(look.x * 30, 2, look.z * 30);

        BezierCurve3D curve = new BezierCurve3D(p0, p1, p2, p3);
        com.roadbuilder.mod.domain.profile.ModularRoadPreset preset = com.roadbuilder.mod.domain.profile.PresetManager.getInstance().getActivePreset();
        RoadProfile profile = RoadProfile.createDefault().withWidth(preset.getTotalWidth());

        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        RoadNode startNode = new RoadNode(p0);
        RoadNode endNode = new RoadNode(p3);

        savedData.addRoadSegment(startNode, endNode, curve, profile);
        RoadVoxelizer.voxelizeAndPlace(level, curve, preset);

        source.sendSuccess(() -> Component.literal(
                String.format("§a[RoadBuilder] Generated test road! Length: %.1fm. Graph now has %d nodes, %d edges.",
                        curve.getApproximateLength(),
                        savedData.getGraph().getNodeCount(),
                        savedData.getGraph().getEdgeCount())
        ), true);

        return 1;
    }

    private static int runStats(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        source.sendSuccess(() -> Component.literal(
                String.format("§e[RoadBuilder] Graph Stats: %d nodes, %d edges.",
                        savedData.getGraph().getNodeCount(),
                        savedData.getGraph().getEdgeCount())
        ), false);
        return 1;
    }

    private static int runClear(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        RoadGraphSavedData savedData = RoadGraphSavedData.get(level);
        savedData.getGraph().clear();
        savedData.setDirty();
        source.sendSuccess(() -> Component.literal("§6[RoadBuilder] Cleared all road graph data in this world."), true);
        return 1;
    }

    private static int runUndo(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can execute /road undo"));
            return 0;
        }
        boolean ok = com.roadbuilder.mod.server.undo.RoadUndoManager.undoLast(player);
        return ok ? 1 : 0;
    }
}
