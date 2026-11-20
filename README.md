<div align="center">
  <img src="src/main/resources/logo.png" alt="Lumispheric Icon" width="180" />

  <h1>Lumispheric</h1>

  <p>
    <a href="https://modrinth.com/project/lumispheric">
      <img src="https://img.shields.io/modrinth/dt/lumispheric?logo=modrinth&label=Downloads&style=flat&color=242629&labelColor=5CA424&logoColor=fff" alt="Modrinth Downloads" />
    </a>
    <img src="https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?logo=minecraft" alt="Minecraft Version" />
    <img src="https://img.shields.io/badge/Loader-NeoForge-orange" alt="NeoForge" />
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License" />
  </p>

  <p>A custom sky, clouds, and distant horizon for the <b>Overworld</b>.</p>
</div>

**Latest release: 1.1.0** — download it from
[GitHub Releases](https://github.com/dinnermc/Lumispheric/releases/latest).

## Features

- The sky changes through day, sunrise and sunset, and night. Horizon fog
  follows the sky color, so it stays consistent as you turn.
- The Sun, Moon, and stars keep their familiar Minecraft look. The sky also
  includes a Milky Way, auroras, shooting stars, and a soft glow around the Sun
  and Moon, with lunar eclipses.
- Clouds respond to weather and fade into the distance.
- At high altitude, the horizon opens onto a procedural planet with land,
  oceans, terrain, day/night lighting, and moving clouds.
- The sky gradually darkens toward space. Stars become visible in daylight
  once you climb high enough.
- Choose the Vanilla+, Realistic, or Performance preset, or adjust settings in
  the in-game configuration screen.
- Lumispheric works alongside Sodium. When an Iris shaderpack is active, it
  gets out of the way and restores its sky when the shaderpack is turned off.

Lumispheric is client-side; other players do not need to install it.

## Requirements

- Minecraft **1.21.1**
- **NeoForge 21.1** or a compatible later 21.x version
- Java 21 (for building from source)

## Installation

1. Install Minecraft 1.21.1 with NeoForge.
2. Download the latest Lumispheric `.jar` from
   [GitHub Releases](https://github.com/dinnermc/Lumispheric/releases/latest).
3. Place the JAR in the instance's `mods` folder and launch the game.
4. Open Lumispheric's configuration screen from the mod list to adjust the sky.

## Building from source

On Windows:

```powershell
.\gradlew.bat build
```

On macOS or Linux:

```sh
./gradlew build
```

The built mod is written to `build/libs/`.

## Configuration

Use the in-game configuration screen to change sky, night, and cloud settings,
select a preset, or switch Lumispheric off to compare it with the vanilla sky.
Settings are client-side.

### Custom star catalogs

Resource packs can add or replace stars with JSON files under
`assets/lumispheric/stars/`. Files are read in sorted path order. Set
`"replace": true` to clear the built-in catalog before loading that file.
Reloading resources updates the catalog; stars keep their celestial positions
as the sky moves.

```json
{
  "replace": false,
  "stars": [
    {
      "ra_hours": 6.7525,
      "dec_degrees": -16.7161,
      "magnitude": -1.46,
      "color_index": 0.0
    }
  ]
}
```

Right ascension is in hours (`0` to `<24`), declination in degrees (`-90` to
`90`), and magnitude and color index must be finite numbers. Custom stars use
the same color mapping as the built-in catalog.

## Compatibility

- **Sodium:** supported.
- **Iris:** Lumispheric disables its custom sky while a shaderpack is active and
  resumes when shaders are turned off.
- Mods that change the sky, fog, clouds, or Sun and Moon rendering may conflict.
- **Other dimensions:** Lumispheric's custom sky is limited to the Overworld.

## License and third-party data

The mod source code is licensed under the Apache License 2.0; see
[`LICENSE`](LICENSE). The adapted star catalog in
[`stars.csv`](src/main/resources/assets/lumispheric/stars.csv) is derived from
the HYG Database and is distributed under CC BY-SA 4.0. See
[`stars-attribution.txt`](src/main/resources/assets/lumispheric/stars-attribution.txt)
for its source and attribution.

## Bug reports and suggestions

Please open a GitHub issue with your Minecraft and NeoForge versions, whether
Sodium or Iris is installed, steps to reproduce, and a relevant log excerpt.
Avoid attaching entire game folders or worlds.
