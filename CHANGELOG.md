# Changelog

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
