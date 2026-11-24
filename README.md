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

  <p>A custom procedural sky, astronomy system, and cosmic horizon for the <b>Overworld</b> and <b>The End</b>.</p>
</div>

**Latest release: 1.3.0** — download it from
[GitHub Releases](https://github.com/dinnermc/Lumispheric/releases/latest) or [Modrinth](https://modrinth.com/mod/lumispheric).

## Features

- GPU-rendered Overworld atmosphere with rich day, sunrise, sunset, Belt of
  Venus, Earth's shadow, and night transitions. Horizon fog stays consistent
  with the sky and responds smoothly to weather and biomes.
- Familiar Minecraft Sun and Moon with atmospheric horizon reddening, pure white
  Sun in space, continuous Sun-aligned lunar phases with earthshine, soft glows,
  and solar and lunar eclipses that darken the world and cast a moving lunar
  shadow across the planet and atmosphere from orbit.
- Dynamic Overworld night darkness driven by the Moon's real phase, altitude,
  lunar eclipses, and weather — leaving torches, lanterns, and caves untouched.
- Real star positions and B-V colors from the HYG star database, with
  atmospheric twinkling near the horizon that fades smoothly into space.
- Real-orbit Solar System planets that shine like bright, non-twinkling stars
  at night and resolve into detailed disks (with real phases and Saturn's
  rings) when viewed through a Spyglass.
- Subtle Spyglass HUD indicator that softly names celestial bodies (Sun, Moon
  phases, Solar System planets, famous stars, meteor shower radiants, Galactic
  Center, and The End's Eye of the Universe) as you aim at the sky.
- Procedural Milky Way anchored in galactic coordinates, polar-aligned
  auroras, sporadic shooting stars, and seasonal **Perseids** and **Geminids**
  meteor showers radiating from their real celestial radiants.
- **The End** redesigned around a towering purple cosmic storm vortex inspired
  by the *Eye of the Universe*, complete with internal quantum lightning,
  purple atmospheric horizon fog, amethyst terrain lighting, and rare silent
  distant supernovae.
- Extended vanilla clouds tinted by sunrise, sunset, moonlight, and weather
  that fade cleanly into the distance.
- At high altitude, the horizon opens onto a procedural planet with continents,
  oceans, swirling weather clouds casting ground shadows, an atmospheric limb,
  and gradual transition into deep space.
- Switch between **Minecraft (Pixel)** and **Realistic (Smooth)** visual
  styles, toggle End sky, lunar nights, and Spyglass HUD individually, adjust
  the astronomical year length and latitude, or pick from Vanilla+, Realistic,
  and Performance presets in a live-preview configuration screen.
- Works alongside Sodium. When an Iris shaderpack is active, Lumispheric gets
  out of the way and restores its sky when the shaderpack is turned off.

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

Use the in-game configuration screen to adjust sky, night, orbit, and cloud
settings with live preview, switch between Minecraft (Pixel) and Realistic
(Smooth) styles, pick a preset, or toggle Lumispheric off to compare with the
vanilla sky. Settings are client-side.

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
- **Other dimensions:** Lumispheric customizes the Overworld and The End (which
  can be toggled independently in settings). The Nether keeps its usual
  appearance.

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
