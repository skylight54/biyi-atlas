#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec4 ColorModulator;
uniform vec4 MaskBounds;
uniform float OutsideAlpha;
uniform vec3 Tint;
in vec2 cloudUV;
out vec4 fragColor;

void main() {
    vec2 maskUV = (cloudUV - MaskBounds.xy) / max(MaskBounds.zw - MaskBounds.xy, vec2(0.000001));
    bool beyond = any(lessThan(maskUV, vec2(0.0))) || any(greaterThan(maskUV, vec2(1.0)));
    // Explicit bounds prevent a repeated/clamped mask from revealing the far exterior.
    float alpha = beyond ? OutsideAlpha : texture(Sampler1, clamp(maskUV, vec2(0.5 / 384.0), vec2(1.0 - 0.5 / 384.0))).a;
    vec2 mirroredUV = 1.0 - abs(mod(cloudUV, 2.0) - 1.0);
    vec3 ink = texture(Sampler0, mirroredUV).rgb * Tint;
    fragColor = vec4(ink, alpha) * ColorModulator;
}
