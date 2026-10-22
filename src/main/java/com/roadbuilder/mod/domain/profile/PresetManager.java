package com.roadbuilder.mod.domain.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Manages loading, saving, and switching between road presets.
 * Supports presets saved on disk (config/roadbuilder/presets.json).
 */
public final class PresetManager {
    private static final PresetManager INSTANCE = new PresetManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, ModularRoadPreset> presets = new LinkedHashMap<>();
    private String activePresetId = "default";

    private PresetManager() {
        initDefaults();
    }

    public static PresetManager getInstance() {
        return INSTANCE;
    }

    private void initDefaults() {
        ModularRoadPreset def = ModularRoadPreset.createDefault();
        ModularRoadPreset country = ModularRoadPreset.createCountryRoad();
        ModularRoadPreset high = ModularRoadPreset.createHighway();

        presets.put(def.getId(), def);
        presets.put(country.getId(), country);
        presets.put(high.getId(), high);
    }

    public Collection<ModularRoadPreset> getAllPresets() {
        return Collections.unmodifiableCollection(presets.values());
    }

    public ModularRoadPreset getActivePreset() {
        return presets.getOrDefault(activePresetId, presets.values().iterator().next());
    }

    public void setActivePresetId(String id) {
        if (presets.containsKey(id)) {
            this.activePresetId = id;
        }
    }

    public void savePreset(ModularRoadPreset preset) {
        presets.put(preset.getId(), preset);
        saveToFile();
    }

    public void deletePreset(String id) {
        if (presets.size() > 1 && !id.equals("default")) {
            presets.remove(id);
            if (activePresetId.equals(id)) {
                activePresetId = presets.keySet().iterator().next();
            }
            saveToFile();
        }
    }

    public ModularRoadPreset duplicatePreset(String id) {
        ModularRoadPreset orig = presets.get(id);
        if (orig == null) orig = getActivePreset();
        ModularRoadPreset copy = orig.copy();
        String newId = "preset_" + System.currentTimeMillis();
        copy.setId(newId);
        String copySuffix = net.minecraft.network.chat.Component.translatable("roadbuilder.preset.copy_suffix").getString();
        copy.setName(orig.getDisplayName() + " " + copySuffix);
        presets.put(newId, copy);
        this.activePresetId = newId;
        saveToFile();
        return copy;
    }

    public ModularRoadPreset createNewPreset() {
        String newId = "preset_" + System.currentTimeMillis();
        ModularRoadPreset newP = ModularRoadPreset.createDefault();
        newP.setId(newId);
        String newName = net.minecraft.network.chat.Component.translatable("roadbuilder.preset.new_name", (presets.size() + 1)).getString();
        newP.setName(newName);
        presets.put(newId, newP);
        this.activePresetId = newId;
        saveToFile();
        return newP;
    }

    private File getConfigFile() {
        File configDir = FMLPaths.CONFIGDIR.get().resolve("roadbuilder").toFile();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "presets.json");
    }

    public void loadFromFile() {
        File file = getConfigFile();
        if (!file.exists()) {
            saveToFile();
            return;
        }

        try (Reader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<ModularRoadPreset> list = GSON.fromJson(reader, new TypeToken<List<ModularRoadPreset>>() {}.getType());
            if (list != null && !list.isEmpty()) {
                presets.clear();
                for (ModularRoadPreset p : list) {
                    if (p.getEmbankmentMode() == null || p.getId().equals("default")) {
                        p.setEmbankmentMode(ModularRoadPreset.ToggleMode.AUTO);
                    }
                    if (p.getSlopeMode() == null || p.getId().equals("default")) {
                        p.setSlopeMode(ModularRoadPreset.ToggleMode.AUTO);
                    }
                    if (p.getViaductMode() == null) {
                        p.setViaductMode(ModularRoadPreset.Mode.AUTO);
                    }
                    if (p.getTunnelMode() == null) {
                        p.setTunnelMode(ModularRoadPreset.Mode.AUTO);
                    }
                    presets.put(p.getId(), p);
                }
                if (!presets.containsKey(activePresetId)) {
                    activePresetId = presets.keySet().iterator().next();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveToFile() {
        File file = getConfigFile();
        try (Writer writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(new ArrayList<>(presets.values()), writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
