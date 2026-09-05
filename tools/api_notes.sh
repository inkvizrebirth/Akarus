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


echo "=== шейдер блика (для «кастомного цвета зачарования») ===" >> "$OUT"
for f in assets/minecraft/shaders/core/glint.fsh assets/minecraft/shaders/core/glint.vsh; do
  echo "--- $f ---" >> "$OUT"
  unzip -p "$JAR" "$f" >> "$OUT" 2>&1
done

echo "=== Options: glint/particle/cloud + SoundManager/LevelRenderer по небу и погоде ===" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.Options 2>/dev/null | grep -iE "glint|particle|cloud|brightness" | head -20 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.multiplayer.ClientLevel 2>/dev/null | grep -iE "rain|snow|thunder|weather|sky" | head -20 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.LevelRenderer 2>/dev/null | grep -iE "sky|weather|rain|snow|glint" | head -24 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.GameRenderer 2>/dev/null | grep -iE "sky|weather|item|hand|glint" | head -20 >> "$OUT"
javap -p -cp "$JAR" com.mojang.blaze3d.systems.RenderSystem 2>/dev/null | grep -iE "color4|shader|glin|clearColor" | head -18 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.sounds.SoundManager 2>/dev/null | grep -iE "play|stop|reload" | head -14 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.state.level.CameraRenderState 2>/dev/null | head -22 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.fog.FogRenderer 2>/dev/null | head -40 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.item.ItemRenderer 2>/dev/null | grep -iE "glint|render" | head -14 >> "$OUT"

echo "=== ресурсы шейдеров в jar ===" >> "$OUT"
unzip -l "$JAR" 2>/dev/null | grep -E "shaders/(core|post)/|\.glsl$" | awk '{print $4}' | head -60 >> "$OUT"
echo "=== ShaderManager: как строится цепочка (пост-эффекты) ===" >> "$OUT"
javap -p -c -cp "$JAR" net.minecraft.client.renderer.ShaderManager 2>&1 \
  | grep -E "Method|Field|postEffect|PostChain|shaders|post_effect" | head -120 >> "$OUT"
wc -l "$OUT"

echo "=== блик: где цвет; небо/погода; звук; скин игрока ===" >> "$OUT"
unzip -l "$JAR" 2>/dev/null | grep -iE "glint" | awk '{print "  jar: " $4}' | head -12 >> "$OUT"
javap -p -c -cp "$JAR" net.minecraft.client.renderer.item.ItemRenderer 2>/dev/null | grep -iE "glint|Color4|GLINT" -B2 -A4 | head -70 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.state.ItemRenderState 2>/dev/null | grep -iE "glint|color" | head -14 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.state.CameraRenderState 2>/dev/null | head -8 >> "$OUT"
echo "--- LevelRenderer: небо и погода ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.LevelRenderer 2>/dev/null | grep -iE "sky|weather|rain|snow|thunder|destroy" | head -22 >> "$OUT"
echo "--- ClientLevel: уровни погоды ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.multiplayer.ClientLevel 2>/dev/null | grep -iE "rain|thunder|weather|sky" | head -16 >> "$OUT"
echo "--- GameRenderer: небо/туман/эффекты ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.GameRenderer 2>/dev/null | grep -iE "sky|renderLevel|extract|entity" | head -18 >> "$OUT"
echo "--- SoundManager / LevelSoundPlayer ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.sounds.SoundManager 2>/dev/null | grep -iE "public" | head -18 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.sounds.LevelSoundPlayer 2>/dev/null | grep -iE "public|play" | head -18 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.resources.sounds.SimpleSoundInstance 2>/dev/null | head -16 >> "$OUT"
echo "--- частицы ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.particle.ParticleEngine 2>/dev/null | grep -iE "public .*(add|createEmitter)" | head -12 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.multiplayer.ClientPacketListener 2>/dev/null | grep -iE "spawnParticles|sendChat|sendCommand|getLatency" | head -10 >> "$OUT"
echo "--- скин игрока ---" >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.entity.AbstractClientPlayerRenderer 2>/dev/null | grep -iE "getTexture|shouldRender|Skin" | head -12 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.resources.DefaultPlayerSkin 2>/dev/null | head -14 >> "$OUT"
javap -p -cp "$JAR" net.minecraft.client.renderer.texture.AbstractTexture 2>/dev/null | grep -iE "public" | head -14 >> "$OUT"
