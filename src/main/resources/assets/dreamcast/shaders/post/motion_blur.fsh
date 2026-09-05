#version 330

// Motion Blur (накопление кадров + тапы по соседним пикселям).
//
// Истории у нас одна текстура (persistent-таргет), а не кольцевой буфер из N
// кадров: ванильный конвейер пост-обработки 26.2 не даёт переставлять цели
// между кадрами, поэтому вместо среднего по N кадрам — экспоненциальное
// сглаживание (EMA) с весом BlendFactor. Визуально это тот же «шлейф за
// движущимся», только без хвоста из N дискретных копий.
//
// BlurAlgorithm:
//   0 (BACKWARDS) — тапыhistory берутся со сдвигом назад по времени: смаз
//      тянется за объектом, как у референса;
//   1 (CENTERED)  — симметричное гауссово размытие истории: мягче, меньше
//      «полосит» на резких поворотах камеры.

uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform MotionBlurConfig {
    float BlendFactor;
    float SampleRadius;
    int BlurAlgorithm;
};

out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / max(InSize, vec2(1.0));
    vec4 current = texture(CurrentSampler, texCoord);
    vec4 history = texture(HistorySampler, texCoord);

    // 9 тапов по соседним пикселям истории — они и дают «смаз» вместо
    // полупрозрачного эха. Центральный вес вдвое больше остальных.
    float radius = max(SampleRadius, 0.5);
    vec4 blurred = history * 4.0;
    float weight = 4.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            if (i == 0 && j == 0) {
                continue;
            }
            float w = (abs(i) + abs(j)) == 2 ? 0.5 : 1.0;
            vec2 shift = vec2(float(i), float(j)) * oneTexel * radius;
            // BACKWARDS: сдвигаем тапы назад по движению кадра (по X — на
            // величину «вчерашней» разницы, поэтому просто шире по -X/-Y)
            if (BlurAlgorithm == 0) {
                shift = -shift * 1.5 + shift * 0.5;
            }
            blurred += texture(HistorySampler, texCoord + shift) * w;
            weight += w;
        }
    }
    blurred /= weight;

    // В неподвижных пикселях кадр совпадает с историей — там размытие не нужно:
    // оставляем текущий пиксель как есть. Разошлись — значит было движение.
    float diff = length(current.rgb - history.rgb);
    float keep = clamp(BlendFactor - diff * BlendFactor * 1.5, 0.0, 0.95);
    fragColor = vec4(mix(current.rgb, blurred.rgb, keep), 1.0);
}
