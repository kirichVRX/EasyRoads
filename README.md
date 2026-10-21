# EasyRoads (Road Builder)

<div align="center">

![EasyRoads Icon](icon.png)

**The ultimate road-building tool for Minecraft city planners and builders.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)](https://minecraft.net/)
[![NeoForge](https://img.shields.io/badge/Modloader-NeoForge-orange.svg)](https://neoforged.net/)
[![License](https://img.shields.io/badge/License-MIT%20%2F%20All%20Rights%20Reserved-blue.svg)](TEMPLATE_LICENSE.txt)

> 📖 **[Перейти на русскую версию / Switch to Russian (README_RU.md)](README_RU.md)**

[Why EasyRoads?](#-why-easyroads) • [Controls & Keybinds](#-controls--keybinds) • [Commands](#-commands) • [Preset Studio](#-road-preset-studio) • [Building from Source](#-building-from-source)

</div>

---

## 🌟 Overview

**EasyRoads** is designed specifically for city planners, builders, and map makers to supercharge road construction speed by up to **10x**. Say goodbye to placing thousands of blocks manually or struggling with sharp, unnatural steps on hills!

Plan organic road networks using smooth **3D Bézier curves**, preview cross-sections with live 3D gizmos, and generate entire roads with one click.

---

## ⚡ Why EasyRoads?

- 🏙️ **Designed for City Planners**: Connect distant cities, craft complex highway interchanges, and build natural mountain serpentines in minutes.
- 📈 **10x Faster Workflow**: Drop anchor points, tune the slope, and generate the road instantly without repetitive block clicking.
- 🛣️ **True 3D Bézier Curves**: Mathematically smooth transitions without jagged 90-degree corners or jagged elevation artifacts.
- 🎯 **Interactive In-Game Editor (`F7`)**: Visual anchor nodes, elevation adjustment with `Mouse Wheel`, and horizontal alignment with `Arrow Keys / WASD`.
- 🎨 **Road Preset Studio (`Caps Lock`)**: Configure multi-layer profiles (asphalt surface, gravel foundation, curbs, sidewalks, and markings).
- ↩️ **Terrain-Safe Undo (`/road undo`)**: Safely roll back road placement and restore original terrain cleanly.

---

## 🎮 Quick Controls & Keybinds

| Key | Default | Description |
| :--- | :---: | :--- |
| **Toggle Edit Mode** | `F7` | Enter / Exit road construction mode |
| **Open Preset Studio** | `Caps Lock` | Open GUI to choose and customize road styles |
| **Build Road (Finalize)** | `Enter` | Voxelize the active preview curve into blocks |
| **Place Anchor Node** | `Left Click` | Place a new point on the ground (in Add mode) |
| **Vertical Height (Y)** | `Mouse Wheel` | Adjust elevation of selected node (in Adjust mode) |
| **Horizontal Move (XZ)** | `Arrow Keys` / `WASD` | Fine-tune node position horizontally |

---

## ⌨️ Commands

| Command | Permission | Description |
| :--- | :---: | :--- |
| `/road undo` | OP (Level 2) | Reverts the last placed road segment and restores terrain |
| `/road clear` | OP (Level 2) | Clears active road graph and preview nodes |
| `/road stats` | OP (Level 2) | Displays statistics of the current road network graph |
| `/road test` | OP (Level 2) | Spawns a test road segment for debugging |

---

## 🛠️ Road Preset Studio

Press `Caps Lock` while in-game to open the **Road Preset Studio**. From here, you can:
- Select between road styles (e.g., Highway, City Avenue, Country Path).
- Define surface width and sidewalk width.
- Choose block palettes for the foundation, asphalt, markings, and borders.

---

## 💻 Building from Source

```bash
git clone https://github.com/kirichVRX/EasyRoads.git
cd EasyRoads

# On Windows:
.\gradlew build

# On Linux / macOS:
./gradlew build
```

Compiled JAR will be located in `build/libs/`.

---

## 📜 License & Credits

Developed by **kirichVRX**. Built for **Minecraft NeoForge**.
Contributions and bug reports are welcome via [GitHub Issues](https://github.com/kirichVRX/EasyRoads/issues)!
