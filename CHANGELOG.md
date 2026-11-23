# Changelog

## 1.2.0 - 2026-11-23

This update moves atmospheric rendering to the GPU, overhauls sunsets, planets,
the Milky Way, and orbital clouds, and introduces a Minecraft Pixel vs.
Realistic Smooth style option.

### Added

- Spyglass (and zoom) planetary observation: planets appear as crisp,
  non-twinkling stars to the naked eye based on real apparent magnitude and
  expand into telescopic disks with real Sun-driven phases (such as crescent
  Venus), Mars polar caps, Jupiter's bands and Great Red Spot, and tilted
  front/back Saturn rings when zoomed in.
- Continuous, Sun-aligned lunar phases with subtle earthshine on the unlit
  side and soft daytime Moon visibility while staying compatible with custom
  `moon_phases.png` resource packs.
- Visual style toggle (`Minecraft (Pixel)` vs. `Realistic (Smooth)`) affecting
  the Moon terminator, Spyglass planets, Milky Way, auroras, and orbital planet.
- Configurable astronomical year length (`16`–`365` Minecraft days) so seasons,
  constellations, and planetary positions visibly progress during normal play.
- Atmospheric extinction on the Sun, Moon, and planets near the horizon, plus
  star scintillation (twinkling) and horizon extinction that fade out in space.
- Sunrise/sunset warm underside illumination and subtle moonlit shading on
  vanilla clouds.
- Earth's shadow rising beneath a stronger Belt of Venus opposite the Sun,
  directional moonlight sky glow, and subtle biome-aware sky haze/clarity
  transitions.
- Polar-aligned multi-layered vertical aurora curtains driven by both high
  observer latitude and cold biomes.

### Changed

- Moved the sky dome atmosphere, auroras, and shooting stars from CPU mesh
  rebuilds to per-pixel GPU fragment shaders using a single static sky sphere,
  eliminating CPU spikes during vertical movement and making meteors and
  auroras run at full frame rate.
- Rebuilt sunsets and sunrises with a cleaner spectral gradient (ozone
  transition band, warm golden-amber to rose-coral horizon sweep, and soft
  highlight roll-off) and synced distant horizon fog.
- Upgraded the high-altitude orbital planet and cloud deck with swirling
  cyclone fronts, cloud shadows projected onto oceans and continents, smoother
  mountain relief without ridge artifacts, stronger ocean sun-glint, sharper
  atmospheric limb glow, and clustered coastal city lights in Realistic mode.
- Rebuilt the Milky Way in fixed galactic coordinates with a dark Great Rift
  dust lane, Sagittarius core glow, micro-star dust, and clean horizon/planet
  clipping.
- Expanded B-V star color mapping for more recognizable warm and blue stars.
- Redesigned the in-game configuration screen into a single-page, background-free
  two-column layout with one-click presets and live sky preview.

### Fixed

- Fixed missing `VertexBuffer.bind()` calls before uploading and drawing the
  sky, Milky Way, and star buffers, restoring Milky Way rendering, preventing
  VAO corruption when toggling the mod off, and avoiding native driver crashes.
- Fixed planets appearing during bright daylight and using a fixed light angle.
- Fixed a rectangular cross artifact on the Moon's outer halo caused by
  clamping phase shading over vanilla's baked circular glow.
- Fixed star visibility at high latitudes by driving star gating from the
  astronomical Sun elevation instead of vanilla's fixed equatorial cycle.
- Removed eight redundant Sun/Moon vertex mixin hooks that modified discarded
  meshes.

## 1.1.0 - 2026-11-20

This update adds the high-altitude planet and expands the sky and celestial
effects introduced in 1.0.0.

### Added

- A procedural planet with curved horizons, continents, terrain, oceans,
  climate-based colors, day/night lighting, city lights, and an atmospheric rim.
- A moving high-altitude cloud layer with layered shapes, shading, and warm
  colors near sunrise and sunset.
- Planet surface patterns turn with horizontal travel.
- Lunar eclipses, including a shadow aligned with the Moon's phase.
- JSON star catalogs that resource packs can add to or use to replace the
  built-in catalog.
- Gradual transitions from the atmosphere to deep space, with stars visible
  during the day at high altitude.

### Changed

- Sunrise and sunset now use wavelength-dependent color shifts, with warmer
  light toward the Sun and a broader rose-violet glow across the horizon.
- The high-altitude sky gradient extends below the horizon, and fog follows
  that color.
- Clouds move to a high-altitude layer as the horizon curves; rain dimming and
  precipitation fade out above Y=1024.
- Stars fade in with altitude and are clipped at the planetary horizon.
- The sky and fog update only when their inputs change, star geometry stays
  fixed between resource reloads, and rendering buffers are released when the
  world changes.

### Fixed

- Lunar eclipse shading now applies to the Moon rather than tinting the Sun.
- The Sun and Moon fade at the altitude-adjusted horizon.
- Celestial and animation time update independently so planet lighting does
  not lag behind the Sun.

## 1.0.0 - 2026-11-18

- Add a procedural Overworld atmosphere with day, twilight, and night colors.
- Blend distant fog with a camera-stable horizon color.
- Add square stars, a procedural Milky Way, occasional auroras, and shooting
  stars.
- Add atmospheric glow around the Sun and weather-aware cloud shading.
- Extend and fade cloud rendering at long distances.
- Add Iris shaderpack detection, Sodium compatibility, and an in-game settings
  screen with presets.
- Fix sky rendering at very high altitudes.
- Suppress precipitation particles above Y=1024.
