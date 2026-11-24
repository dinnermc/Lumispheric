# Changelog

## 1.3.0 - 2026-11-24

This update transforms The End into a purple *Eye of the Universe* cosmic vista,
adds seasonal Perseids and Geminids meteor showers, introduces a subtle Spyglass
astronomy HUD, brings dynamic lunar-phase night darkness and full orbital/ground
solar eclipse effects, and optimizes CPU and GPU rendering with zero visual loss.

### Added

- **The End — Eye of the Universe:**
  - Colossal swirling purple cosmic storm vortex overhead with domain-warped
    volumetric clouds, radial quantum light shafts, internal electric-violet
    lightning flashes, and a calm dark pupil with a silver-lavender rim.
  - Deep cosmic purple atmospheric fog (`FogRendererMixin`) and luminous
    amethyst-violet terrain lighting (`EndEffectsMixin`), replacing vanilla's
    flat olive-green End lightmap while keeping torches and crystal flames crisp.
  - Rare, silent 4-stage distant supernovae (red-giant swell, diffraction flash,
    filamentary expanding shockwave nebula, and fading white-dwarf remnant) in
    the starry outer void.
- **Perseids & Geminids meteor showers:** Tied to real astronomical calendar
  dates (`solarLongitude`), with up to 4 simultaneous meteors radiating from
  their true celestial radiants in Perseus and Gemini with realistic shower
  colors.
- **Subtle Spyglass HUD (`SpyglassHud`):** Quietly displays the name and
  astronomical details of the Sun, Moon phase & illumination, Solar System
  planets (with apparent magnitude), 26 famous bright stars, active meteor
  shower radiants, the Galactic Center, and The End's Eye of the Universe when
  aiming a Spyglass at the sky.
- **Dynamic lunar nights & solar eclipse world lighting (`OverworldEffectsMixin`):**
  - Overworld nighttime ambient sky-light now darkens smoothly on moonless,
    crescent, new-moon, rainy, or lunar-eclipse nights and stays clear and
    silvery under a full moon, while keeping torches, lanterns, campfires,
    lightning flashes, and deep caves 100% untouched.
  - Daytime solar eclipses now smoothly darken Overworld terrain into deep
    twilight, enhance the pearly-white solar corona with radial streamers, and
    cast a physical sweeping **Moon umbra & penumbra shadow** across the orbital
    planet's continents, oceans, cloud deck, and atmospheric limb from space.
- **Independent feature toggles:** Added in-game toggles (and config options)
  for **End Sky**, **Lunar Nights**, and **Spyglass HUD**.

### Changed

- **Performance optimizations (CPU & GPU, zero quality loss):**
  - Cached all shader `Uniform` handles at registration, eliminating ~35 map
    lookups per frame.
  - Reduced the static sky sphere mesh by 4× (`32×24` vs. `64×48`) since all
    sky, Milky Way, planet, and End shaders normalize directions per pixel.
  - Cached equatorial planet Kepler orbits over 20-tick buckets (~95% fewer
    orbital solver iterations), cached `LunarEclipse` with a fast phase
    early-out, and cached biome lookups per 4×4×4 quart cell.
  - Batched naked-eye planets and halos into shared draw calls with a
    single-pass planet setup and a zero-allocation fast-path for celestial
    horizon clipping.
  - Added GPU early-outs across `clouds.fsh` (early distance fade discard),
    `stars.fsh` (skip scintillation/extinction in space), `end_sky.fsh` (skip
    occluded background nebula), `milky_way.fsh` (~60% sky pixels skipped via
    galactic-latitude discard), and `planet.fsh` (skip atmosphere on opaque
    orbital planet pixels, defer `atan()` until meteors are active, bound
    eclipse corona math to 16° around the Sun, skip night-side surface micro
    detail and ocean biome noise, and use screen-space cloud bump derivatives).
- **Realistic white Sun in space:** Desaturates the Sun body and halo smoothly
  to pure white as you leave the atmosphere.

### Fixed

- Fixed daytime stars popping in instantaneously around `Y ≈ 2089` (caused by
  `RenderSystem.setShaderColor(1, 1, 1, 1)` overriding `ColorModulator` inside
  `ShaderInstance.setDefaultUniforms()`); stars now fade in smoothly from
  `Y = 900` to `Y = 4800`. Also fixed color modulation on Spyglass telescopic
  planets.
- Fixed clipped triangle/square procedural stars in The End and Milky Way dust
  by replacing 3D cubic-voxel slicing with clean 2D cube-face projection
  (`cubeFaceUV`).
- Fixed subtle radial ray cutoff at the outer edge of The End vortex cone.

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
