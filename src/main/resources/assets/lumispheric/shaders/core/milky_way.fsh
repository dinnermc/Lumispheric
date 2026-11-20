#version 150

uniform vec3 GalacticNorth;
uniform vec3 GalacticCenter;
uniform float MilkyWayStrength;

in vec3 localPosition;

out vec4 fragColor;

float hashCell(vec3 cell) {
    vec3 value = fract(cell * vec3(0.1031, 0.1030, 0.0973));
    value += dot(value, value.yzx + 33.33);
    return fract((value.x + value.y) * value.z);
}

float valueNoise(vec3 position) {
    vec3 cell = floor(position);
    vec3 blend = fract(position);
    blend = blend * blend * (3.0 - 2.0 * blend);

    float z0 = mix(
        mix(hashCell(cell), hashCell(cell + vec3(1.0, 0.0, 0.0)), blend.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 0.0)), hashCell(cell + vec3(1.0, 1.0, 0.0)), blend.x),
        blend.y
    );
    float z1 = mix(
        mix(hashCell(cell + vec3(0.0, 0.0, 1.0)), hashCell(cell + vec3(1.0, 0.0, 1.0)), blend.x),
        mix(hashCell(cell + vec3(0.0, 1.0, 1.0)), hashCell(cell + vec3(1.0, 1.0, 1.0)), blend.x),
        blend.y
    );
    return mix(z0, z1, blend.z);
}

void main() {
    vec3 viewDirection = normalize(localPosition);
    vec3 galacticNorth = normalize(GalacticNorth);
    vec3 galacticCenter = normalize(GalacticCenter);
    float planeDot = clamp(dot(viewDirection, galacticNorth), -1.0, 1.0);
    float galacticLatitude = asin(planeDot);
    float bandDistance = galacticLatitude / 0.30;
    float band = exp(-bandDistance * bandDistance);
    float centerDot = max(0.0, dot(viewDirection, galacticCenter));
    float galacticCore = 0.55 + 0.45 * centerDot;
    vec3 galacticEast = normalize(cross(galacticNorth, galacticCenter));
    float sideDot = dot(viewDirection, galacticEast);
    float dustLaneCenter = 0.02 * sin(centerDot * 2.0 + sideDot * 4.0);
    float dustLaneDistance = (galacticLatitude - dustLaneCenter) / 0.025;
    float dustLane = exp(-dustLaneDistance * dustLaneDistance);
    float dustFocus = smoothstep(-0.35, 0.7, centerDot);
    float dustWarp = valueNoise(viewDirection * 12.0 + vec3(13.0, 29.0, 7.0)) - 0.5;
    vec3 dustCoordinates = normalize(
        viewDirection + galacticNorth * dustWarp * 0.12
    );
    float broadDust = valueNoise(dustCoordinates * 26.0 + vec3(31.0, 11.0, 23.0));
    float fineDust = valueNoise(dustCoordinates * 68.0 + vec3(7.0, 19.0, 37.0));
    float dustClouds = 0.64 * broadDust + 0.36 * fineDust;
    float dustFilaments = smoothstep(0.38, 0.67, dustClouds);
    float smoothStructure = 0.92 + 0.08 * (0.5 + 0.5 * centerDot);
    float dustAttenuation = 1.0 - dustLane * (0.18 + 0.30 * dustFocus);
    float dustBrightness = mix(0.14, 0.40, dustFilaments);
    float diffuseDust = exp(-galacticLatitude * galacticLatitude / 0.24);
    float diffuseDustOpacity = diffuseDust * mix(0.08, 0.32, dustFilaments);
    float galacticEmission = band * galacticCore * smoothStructure
        * dustAttenuation * dustBrightness + diffuseDustOpacity;
    float alpha = min(galacticEmission * MilkyWayStrength, 0.55);

    if (alpha <= 0.001) {
        discard;
    }

    vec3 milkyWayColor = mix(
        vec3(0.69, 0.73, 0.92),
        vec3(0.88, 0.80, 0.67),
        smoothstep(0.15, 0.9, centerDot)
    );
    vec3 dustGlow = mix(
        vec3(0.56, 0.62, 0.79),
        vec3(1.0, 0.88, 0.68),
        dustFilaments
    );
    milkyWayColor = mix(milkyWayColor, dustGlow, dustFilaments * 0.82);
    fragColor = vec4(milkyWayColor, alpha);
}
