#version 150

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 colour = vertexColor * ColorModulator;
    float haze = smoothstep(FogStart, FogEnd, vertexDistance);
    fragColor = vec4(mix(colour.rgb, FogColor.rgb, haze), colour.a);
}
