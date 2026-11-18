#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 CelestialToLocal;

out vec4 vertexColor;
out vec3 localPosition;

void main() {
    vec4 localVertex = CelestialToLocal * vec4(Position, 1.0);
    localPosition = localVertex.xyz;
    vertexColor = Color;
    gl_Position = ProjMat * ModelViewMat * localVertex;
}
