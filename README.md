# AutoFarmer

Fabric client mod: path to mobs with **[MeteorDevelopment Baritone](https://github.com/MeteorDevelopment/baritone)**, kill them in range with your best hotbar weapon (swords preferred over axes when both are present), then **pick up the drops** Baritone **block breaking is disabled** while farming; farming runs until **`/farmer stop`**.

## Requirements

- Minecraft
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

This project is licensed under the **GNU LGPL v3.0**; see [LICENSE](LICENSE).

Baritone is separate software (also LGPL-3.0); use their distribution for the pathfinding mod.
