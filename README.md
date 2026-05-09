# Mob Farmer

Fabric **1.21.11** client mod: path to mobs with **[MeteorDevelopment Baritone](https://github.com/MeteorDevelopment/baritone)**, kill them in range with your best hotbar weapon (swords preferred over axes when both are present). Baritone **block breaking is disabled** while farming; farming runs until **`/farmer stop`**.

## Requirements

- Minecraft **1.21.11**
- **Java 21**
- **Fabric Loader** ≥ **0.18.6**
- **Fabric API**
- **Baritone**

## Install

Put the built JAR in your instance `mods` folder together with Fabric API and Baritone.

## Commands (client)

| Command | Description |
|--------|-------------|
| `/farmer farm <mob>` | Start farming (e.g. `zombie`, `minecraft:creeper`). |
| `/farmer stop` | Stop farming and restore Baritone break settings. |

## License

This project is licensed under the MIT License; see [LICENSE](LICENSE).

Baritone is separate software (LGPL-3.0); use their distribution and terms for the pathfinding mod.
