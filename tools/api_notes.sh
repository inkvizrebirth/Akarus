#!/usr/bin/env bash
# Дамп сигнатур blaze3d/рендера, нужный для «своего» слоя пост-обработки.
# Запускается из CI (шаг «Заметки по API»), результат — ветка arena/api-notes.
set +e
. /tmp/paths.env
OUT=api-notes.txt
: > "$OUT"
echo "=== source: $(git log -1 --format='%h %ad' --date=iso) ===" >> "$OUT"
if [ -z "$JAR" ]; then echo "MC jar не найден" >> "$OUT"; exit 0; fi
J() { echo "=== $1 ===" >> "$OUT"; javap -p -cp "$JAR" "$1" >> "$OUT" 2>&1; }
JC() { echo "=== $1 (байткод) ===" >> "$OUT"; javap -p -c -cp "$JAR" "$1" >> "$OUT" 2>&1; }

J net.minecraft.client.renderer.ShaderManager
JC net.minecraft.client.renderer.ShaderManager
J net.minecraft.client.renderer.PostChainConfig
J 'net.minecraft.client.renderer.PostChainConfig$Pass'
J 'net.minecraft.client.renderer.PostChainConfig$InternalTarget'
J 'net.minecraft.client.renderer.PostChainConfig$Input'
J 'net.minecraft.client.renderer.PostChainConfig$TextureInput'
J 'net.minecraft.client.renderer.PostChainConfig$TargetInput'
J net.minecraft.client.renderer.UniformValue
J 'net.minecraft.client.renderer.UniformValue$FloatUniform'
J 'net.minecraft.client.renderer.UniformValue$IntUniform'
J 'net.minecraft.client.renderer.UniformValue$Vec2Uniform'
J 'net.minecraft.client.renderer.UniformValue$Type'
J net.minecraft.client.renderer.Projection
J net.minecraft.client.renderer.ProjectionMatrixBuffer
JC net.minecraft.client.renderer.ProjectionMatrixBuffer
J com.mojang.blaze3d.pipeline.RenderTarget
J com.mojang.blaze3d.resource.GraphicsResourceAllocator
J net.minecraft.client.renderer.GameRenderer
JC net.minecraft.client.renderer.GameRenderer
J com.mojang.blaze3d.systems.RenderSystem
J net.minecraft.client.Minecraft
J net.minecraft.client.renderer.ItemInHandRenderer
J net.minecraft.client.renderer.RenderPipelines
J com.mojang.blaze3d.pipeline.RenderPipeline
J com.mojang.blaze3d.shaders.GpuShader
J net.minecraft.resources.Identifier
J com.mojang.blaze3d.buffers.GpuBuffer
J com.mojang.blaze3d.buffers.BufferUsage
J net.minecraft.client.renderer.PostChain
J 'net.minecraft.client.renderer.PostChain$TargetBundle'

echo "=== классы рендера рук (куда переехал ItemInHandRenderer) ===" >> "$OUT"
for cls in $(unzip -l "$JAR" 2>/dev/null | grep -E "client/renderer/[A-Za-z/]*(Hand|FirstPerson|ItemInHand)[A-Za-z]*\.class" \
             | awk '{print $4}' | sed 's|/|.|g; s|\.class$||' | grep -v '\$' | head -10); do
  echo "=== $cls ===" >> "$OUT"
  javap -p -cp "$JAR" "$cls" >> "$OUT" 2>&1
done
echo "=== методы с hand/arm/item у GameRenderer/LevelRenderer ===" >> "$OUT"
for cls in net.minecraft.client.renderer.GameRenderer net.minecraft.client.renderer.LevelRenderer \
           net.minecraft.client.renderer.entity.AvatarRenderer; do
  echo "--- $cls ---" >> "$OUT"
  javap -p -cp "$JAR" "$cls" 2>/dev/null | grep -iE "hand|arm|item" | head -14 >> "$OUT"
done

echo "=== ресурсы шейдеров в jar ===" >> "$OUT"
unzip -l "$JAR" 2>/dev/null | grep -E "shaders/(core|post)/|\.glsl$" | awk '{print $4}' | head -60 >> "$OUT"
echo "=== ShaderManager: как строится цепочка (пост-эффекты) ===" >> "$OUT"
javap -p -c -cp "$JAR" net.minecraft.client.renderer.ShaderManager 2>&1 \
  | grep -E "Method|Field|postEffect|PostChain|shaders|post_effect" | head -120 >> "$OUT"
wc -l "$OUT"
