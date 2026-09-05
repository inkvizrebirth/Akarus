#version 330

// Motion Blur (накопление кадров): смешиваем текущий кадр с историей и
// размываем историю по соседним пикселям, чтобы движение «тянулось» за собой.
// Статичные пиксели при этом остаются резкими: вес истории зависит от того,
// насколько кадр отличается от накопленного.

uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform MotionBlurConfig {
    float BlendFactor;
};

out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / max(OutSize, vec2(1.0));
    vec4 current = texture(CurrentSampler, texCoord);
    vec4 previous = texture(HistorySampler, texCoord);

    // 9 тапов по соседним пикселям истории: именно они дают «смаз», а не
    // простое полупрозрачное эхо. Веса гауссовы, центральный — самый большой.
    vec4 history = previous * 4.0;
    float weight = 4.0;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            if (i == 0 && j == 0) {
                continue;
            }
            float w = (abs(i) + abs(j)) == 2 ? 0.5 : 1.0;
            history += texture(HistorySampler, texCoord + vec2(float(i), float(j)) * oneTexel) * w;
            weight += w;
        }
    }
    history /= weight;

    // Где кадр совпал с историей — оставляем историю (это неподвижные пиксели,
    // они и так резкие). Где разошёлся — там как раз движение: там историю
    // подмешиваем к текущему кадру.
    float diff = length(current.rgb - previous.rgb);
    float keep = clamp(BlendFactor - diff * BlendFactor * 1.5, 0.0, 0.95);
    fragColor = vec4(mix(current.rgb, history.rgb, keep), 1.0);
}
