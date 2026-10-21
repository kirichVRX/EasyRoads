package com.roadbuilder.mod.domain.pack;

import com.roadbuilder.mod.domain.profile.RoadElementType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry holding road addon packs (schematic sets).
 * The Left Panel in the GUI lets the player choose which Road Pack to use!
 */
public final class RoadPackRegistry {
    private static final RoadPackRegistry INSTANCE = new RoadPackRegistry();

    public static class RoadPack {
        private final String id;
        private final String displayName;
        private final Map<String, LitematicParser.Schematic> schematics = new HashMap<>();

        public RoadPack(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String getId() { return id; }
        public String getDisplayName() {
            if ("default".equals(id)) {
                return net.minecraft.network.chat.Component.translatable("roadbuilder.pack.default").getString();
            }
            return displayName;
        }

        public void putSchematic(String key, LitematicParser.Schematic schematic) {
            schematics.put(key, schematic);
        }

        public LitematicParser.Schematic getSchematic(String key) {
            return schematics.get(key);
        }

        public boolean hasSchematic(String key) {
            return schematics.containsKey(key);
        }
    }

    private final Map<String, RoadPack> packs = new HashMap<>();
    private String activePackId = "default";

    private RoadPackRegistry() {
        initDefaultPack();
    }

    public static RoadPackRegistry getInstance() {
        return INSTANCE;
    }

    private void initDefaultPack() {
        RoadPack defaultPack = new RoadPack("default", "Standard (Vanilla)");

        // 1. Load built-in bundled schematics from JAR resources
        String[] builtinKeys = {
                "road_nasip",
                "road_obochina",
                "road_opora",
                "road_opora1",
                "road_opora_perekladina",
                "road_otkos",
                "road_razdelitel_grass",
                "road_razmetka_prerivistaya_long",
                "road_razmetka_prerivistaya_short",
                "road_razmetka_sploshnaya",
                "road_razmetka_sploshnaya_double",
                "road_tunel_side"
        };
        for (String key : builtinKeys) {
            String path = "/assets/roadbuilder/schematics/" + key + ".litematic";
            java.io.InputStream stream = getClass().getResourceAsStream(path);
            if (stream != null) {
                LitematicParser.Schematic s = LitematicParser.load(stream, key);
                if (s != null) {
                    defaultPack.putSchematic(key, s);
                }
            }
        }

        // 2. Try load schematics from user profile directory or fallback to local resources
        File schematicsDir = new File("c:/Users/KarCari5/AppData/Roaming/ModrinthApp/profiles/Bilding/schematics");
        if (schematicsDir.exists()) {
            loadFromDirectory(defaultPack, schematicsDir);
        }

        packs.put(defaultPack.getId(), defaultPack);
    }

    public void loadFromDirectory(RoadPack pack, File dir) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".litematic"));
        if (files != null) {
            for (File f : files) {
                String key = f.getName().replace(".litematic", "");
                LitematicParser.Schematic schematic = LitematicParser.load(f);
                pack.putSchematic(key, schematic);
            }
        }
    }

    public Map<String, RoadPack> getAllPacks() {
        return packs;
    }

    public RoadPack getActivePack() {
        return packs.getOrDefault(activePackId, packs.values().iterator().next());
    }

    public void setActivePackId(String packId) {
        if (packs.containsKey(packId)) {
            this.activePackId = packId;
        }
    }
}
