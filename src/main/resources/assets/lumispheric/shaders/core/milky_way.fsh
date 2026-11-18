#version 150

uniform vec3 GalacticNorth;
uniform vec3 GalacticCenter;
uniform float MilkyWayStrength;

in vec3 localPosition;

out vec4 fragColor;

void main() {
    vec3 viewDirection = normalize(localPosition);
    vec3 galacticNorth = normalize(GalacticNorth);
    vec3 galacticCenter = normalize(GalacticCenter);
    float planeDot = clamp(dot(viewDirection, galacticNorth), -1.0, 1.0);
    float galacticLatitude = asin(planeDot);
    float bandDistance = galacticLatitude / 0.15;
    float band = exp(-bandDistance * bandDistance);
    float centerDot = max(0.0, dot(viewDirection, galacticCenter));
    float galacticCore = 0.55 + 0.45 * centerDot;
    vec3 galacticEast = normalize(cross(galacticNorth, galacticCenter));
    float sideDot = dot(viewDirection, galacticEast);
    float dustLaneCenter = 0.02 * sin(centerDot * 2.0 + sideDot * 4.0);
    float dustLaneDistance = (galacticLatitude - dustLaneCenter) / 0.025;
    float dustLane = exp(-dustLaneDistance * dustLaneDistance);
    float dustFocus = smoothstep(-0.35, 0.7, centerDot);
    float smoothStructure = 0.92 + 0.08 * (0.5 + 0.5 * centerDot);
    float dustAttenuation = 1.0 - dustLane * (0.18 + 0.30 * dustFocus);
    float alpha = band * galacticCore * smoothStructure * dustAttenuation * MilkyWayStrength * 0.16;

    if (alpha <= 0.001) {
        discard;
    }

    vec3 milkyWayColor = mix(
        vec3(0.69, 0.73, 0.92),
        vec3(0.88, 0.80, 0.67),
        smoothstep(0.15, 0.9, centerDot)
    );
    fragColor = vec4(milkyWayColor, alpha);
}
