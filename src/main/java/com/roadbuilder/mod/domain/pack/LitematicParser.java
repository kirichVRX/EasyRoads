package com.roadbuilder.mod.domain.pack;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Robust Litematica (.litematic) parser that extracts the exact 3D grid of BlockStates.
 * Note: Litematica axis mapping for road cross-sections is:
 * - Along road length: X axis (sizeX)
 * - Vertical height: Y axis (sizeY)
 * - Across road width (lateral profile): Z axis (sizeZ)
 */
public class LitematicParser {

    public static class Schematic {
        private final String name;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final BlockState[][][] grid; // [x][y][z]

        public Schematic(String name, int sizeX, int sizeY, int sizeZ, BlockState[][][] grid) {
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.grid = grid;
        }

        public String getName() { return name; }
        public int getSizeX() { return sizeX; } // length along road
        public int getSizeY() { return sizeY; } // height
        public int getSizeZ() { return sizeZ; } // width across road

        public BlockState getBlock(int x, int y, int z) {
            if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                return Blocks.AIR.defaultBlockState();
            }
            BlockState s = grid[x][y][z];
            return s != null ? s : Blocks.AIR.defaultBlockState();
        }
    }

    public static Schematic load(InputStream is, String name) {
        if (is == null) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(is, NbtAccounter.unlimitedHeap());
            return parse(name, root);
        } catch (Exception e) {
            System.err.println("[RoadBuilder] Failed to load schematic stream " + name + ": " + e.getMessage());
            return null;
        }
    }

    public static Schematic load(File file) {
        if (!file.exists()) {
            return null;
        }
        try (InputStream is = new FileInputStream(file)) {
            return load(is, file.getName().replace(".litematic", ""));
        } catch (Exception e) {
            System.err.println("[RoadBuilder] Failed to load schematic " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public static Schematic parse(String name, CompoundTag root) {
        try {
            if (!root.contains("Regions")) {
                return null;
            }

            CompoundTag regions = root.getCompound("Regions");
            if (regions.isEmpty()) {
                return null;
            }

            String regionKey = regions.getAllKeys().iterator().next();
            CompoundTag region = regions.getCompound(regionKey);

            CompoundTag sizeTag = region.getCompound("Size");
            int rawX = sizeTag.getInt("x");
            int rawY = sizeTag.getInt("y");
            int rawZ = sizeTag.getInt("z");

            int sx = Math.abs(rawX);
            int sy = Math.abs(rawY);
            int sz = Math.abs(rawZ);

            if (sx == 0 || sy == 0 || sz == 0) {
                return null;
            }

            ListTag paletteTag = region.getList("BlockStatePalette", 10);
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteTag.size(); i++) {
                palette.add(parseBlockState(paletteTag.getCompound(i)));
            }

            BlockState[][][] grid = new BlockState[sx][sy][sz];
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        grid[x][y][z] = Blocks.AIR.defaultBlockState();
                    }
                }
            }

            if (palette.isEmpty()) {
                return new Schematic(name, sx, sy, sz, grid);
            }

            if (palette.size() == 1) {
                BlockState sole = palette.get(0);
                for (int x = 0; x < sx; x++) {
                    for (int y = 0; y < sy; y++) {
                        for (int z = 0; z < sz; z++) {
                            grid[x][y][z] = sole;
                        }
                    }
                }
                return new Schematic(name, sx, sy, sz, grid);
            }

            long[] blockStates = region.getLongArray("BlockStates");
            int paletteSize = palette.size();
            int bitsPerBlock = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            long maxEntryValue = (1L << bitsPerBlock) - 1L;

            int totalVolume = sx * sy * sz;
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    for (int x = 0; x < sx; x++) {
                        int index = (y * sz + z) * sx + x;
                        if (index >= totalVolume) continue;

                        int startBit = index * bitsPerBlock;
                        int startLong = startBit / 64;
                        int startOffset = startBit % 64;
                        int endLong = ((index + 1) * bitsPerBlock - 1) / 64;

                        long value;
                        if (startLong < blockStates.length) {
                            if (startLong == endLong) {
                                value = (blockStates[startLong] >>> startOffset) & maxEntryValue;
                            } else if (endLong < blockStates.length) {
                                int endOffset = 64 - startOffset;
                                value = ((blockStates[startLong] >>> startOffset) | (blockStates[endLong] << endOffset)) & maxEntryValue;
                            } else {
                                value = (blockStates[startLong] >>> startOffset) & maxEntryValue;
                            }

                            int pIdx = (int) value;
                            if (pIdx >= 0 && pIdx < palette.size()) {
                                grid[x][y][z] = palette.get(pIdx);
                            }
                        }
                    }
                }
            }

            return new Schematic(name, sx, sy, sz, grid);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState parseBlockState(CompoundTag tag) {
        String name = tag.getString("Name");
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null) return Blocks.AIR.defaultBlockState();

        Block block = BuiltInRegistries.BLOCK.get(rl);
        if (block == null || block == Blocks.AIR) return Blocks.AIR.defaultBlockState();

        BlockState state = block.defaultBlockState();
        if (tag.contains("Properties")) {
            CompoundTag props = tag.getCompound("Properties");
            for (String key : props.getAllKeys()) {
                Property prop = block.getStateDefinition().getProperty(key);
                if (prop != null) {
                    String valStr = props.getString(key);
                    Optional opt = prop.getValue(valStr);
                    if (opt.isPresent()) {
                        state = (BlockState) state.setValue(prop, (Comparable) opt.get());
                    }
                }
            }
        }
        return state;
    }
}
