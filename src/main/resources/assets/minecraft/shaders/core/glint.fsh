#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;

out vec4 fragColor;

// Dreamcast. Свой цвет блика зачарования.
//
// Игра красит блик одним скаляром — GlintAlpha, который Sheyder берёт из
// ванильной настройки «Сила блика». Мы используем тот же канал как передачу
// тона (0..1 по кругу HSV): модуль «Цвет блика» пишет туда нужный тон каждый
// тик, поэтому цвет меняется мгновенно и без перезагрузки ресурсов.
//
// Оттенок дополнительно «размазывается» по маске блика, чтобы полоса не была
// плоской: там, где маска ярче, тон чуть светлее и теплее — как у настоящего
// переливающегося зачарования.
vec3 dreamcast_hsv(float hue, float saturation, float value) {
	vec3 p = abs(fract(vec3(hue) + vec3(1.0, 0.6666667, 0.3333333)) * 6.0 - vec3(3.0));
	return value * mix(vec3(1.0), clamp(p - vec3(1.0), 0.0, 1.0), saturation);
}

void main() {
	vec4 mask = texture(Sampler0, texCoord0) * ColorModulator;
	if (mask.a < 0.1) {
		discard;
	}
	float fade = 1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
			FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
	float hue = fract(GlintAlpha + mask.r * 0.12 + texCoord0.x * 0.04);
	vec3 tint = dreamcast_hsv(hue, 0.58, 1.05);
	float body = mix(0.34, 1.0, clamp(mask.r * 0.55 + mask.g * 0.65, 0.0, 1.0));
	fragColor = vec4(tint * body * max(fade, 0.0), mask.a * 0.92);
}
