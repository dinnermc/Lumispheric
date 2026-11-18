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

  <p>Procedural skies, atmospheric scattering, and blocky celestial beauty for the <b>Overworld</b>.</p>
</div>

> **Release status:** 1.0.0 is being prepared. The first GitHub release has not
> been published yet.

## Features

- Procedural day, twilight, and night sky colors with atmospheric scattering.
- Stable horizon fog that blends with the sky instead of changing as you turn.
- Vanilla-style square stars, a procedural Milky Way, occasional auroras, and
  rare shooting stars.
- Atmospheric glow around the Sun and weather-aware clouds.
- Extended cloud rendering with distance fading.
- Configurable settings and Vanilla+, Realistic, and Performance presets.
- Automatically yields sky rendering to active Iris shaderpacks.
- High-altitude precipitation particles are suppressed above Y=1024.

Lumispheric changes visuals on the client and is not intended to require
installation on a multiplayer server. Sodium and Iris are optional; Iris
shaderpacks take control of the sky while active.

## Requirements

- Minecraft **1.21.1**
- **NeoForge 21.1** or a compatible later 21.x version
- Java 21 (for building from source)

## Installation

1. Install Minecraft 1.21.1 with NeoForge.
2. Download the Lumispheric `.jar` from the GitHub Releases page once a release
   is available.
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

The in-game configuration screen includes atmospheric and night/cloud settings,
three presets, and a toggle to compare Lumispheric with the vanilla sky.
Settings are client-side.

## Compatibility

- **Sodium:** supported.
- **Iris:** Lumispheric disables its custom sky while a shaderpack is active and
  resumes when shaders are turned off.
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
