/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.GlStateManager$class_4534
 *  com.mojang.blaze3d.platform.GlStateManager$class_4535
 *  com.mojang.blaze3d.systems.RenderSystem
 *  net.minecraft.class_10142
 *  net.minecraft.class_10156
 *  net.minecraft.class_243
 *  net.minecraft.class_286
 *  net.minecraft.class_287
 *  net.minecraft.class_289
 *  net.minecraft.class_290
 *  net.minecraft.class_293$class_5596
 *  net.minecraft.class_2960
 *  net.minecraft.class_4587
 *  net.minecraft.class_7833
 *  net.minecraft.class_9801
 *  org.joml.Matrix4f
 */
package sweetie.leonware.client.features.modules.render.targetesp.modes;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.class_10142;
import net.minecraft.class_10156;
import net.minecraft.class_243;
import net.minecraft.class_286;
import net.minecraft.class_287;
import net.minecraft.class_289;
import net.minecraft.class_290;
import net.minecraft.class_293;
import net.minecraft.class_2960;
import net.minecraft.class_4587;
import net.minecraft.class_7833;
import net.minecraft.class_9801;
import org.joml.Matrix4f;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.system.files.FileUtil;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;

public class TargetEspGhost2
extends TargetEspMode {
    private final List<GhostData> ghosts = new ArrayList<GhostData>();
    private long lastUpdateTime = 0L;
    private long lastTrailTime = 0L;

    @Override
    public void onUpdate() {
        if (this.ghosts.isEmpty()) {
            this.initGhosts();
        }
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        if (currentTarget == null || !this.canDraw()) {
            return;
        }
        float anim = (float)showAnimation.getValue();
        if (anim <= 0.01f) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.lastUpdateTime == 0L) {
            this.lastUpdateTime = now;
            this.lastTrailTime = now;
        }
        long delta = now - this.lastUpdateTime;
        this.lastUpdateTime = now;
        double worldX = TargetEspGhost2.getTargetX();
        double worldY = TargetEspGhost2.getTargetY() + (double)currentTarget.method_17682() / 2.0;
        double worldZ = TargetEspGhost2.getTargetZ();
        float rotSpeed = (float)delta * 0.003f;
        float radius = 0.45f;
        float vertAmp = 0.4f;
        int maxTrail = 20;
        for (int i = 0; i < this.ghosts.size(); ++i) {
            GhostData ghost = this.ghosts.get(i);
            ghost.angle += rotSpeed;
            float offset = (float)((double)i * 2.0943951023931953);
            float angle = ghost.angle + offset;
            ghost.position = new class_243(worldX + Math.sin(angle) * (double)radius, worldY + Math.sin((double)angle * 2.0) * (double)vertAmp, worldZ + Math.cos(angle) * (double)radius);
        }
        if (now - this.lastTrailTime >= 30L) {
            this.lastTrailTime = now;
            for (GhostData ghost : this.ghosts) {
                if (!ghost.history.isEmpty() && ghost.position.method_1025(ghost.history.get(0)) > 10.0) {
                    ghost.history.clear();
                }
                ghost.history.add(0, ghost.position);
                while (ghost.history.size() > maxTrail) {
                    ghost.history.remove(ghost.history.size() - 1);
                }
            }
        }
        Color color = UIColors.gradient(0);
        int rgb = color.getRGB();
        class_4587 ms = event.matrixStack();
        RenderSystem.setShader((class_10156)class_10142.field_53880);
        RenderSystem.setShaderTexture((int)0, (class_2960)FileUtil.getImage("particles/glow"));
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE, (GlStateManager.class_4535)GlStateManager.class_4535.ZERO, (GlStateManager.class_4534)GlStateManager.class_4534.ONE);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask((boolean)false);
        class_287 builder = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);
        boolean hasVertices = false;
        for (GhostData ghost : this.ghosts) {
            for (int i = ghost.history.size() - 1; i >= 0; --i) {
                if (!this.writePartVertices(builder, ms, ghost.history.get(i), i, ghost.history.size(), anim, 0.2f, rgb)) continue;
                hasVertices = true;
            }
            if (!this.writePartVertices(builder, ms, ghost.position, 0, 1, anim, 0.2f, rgb)) continue;
            hasVertices = true;
        }
        if (hasVertices) {
            class_286.method_43433((class_9801)builder.method_60800());
        }
        RenderSystem.setShaderTexture((int)0, (int)0);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask((boolean)true);
        RenderSystem.disableBlend();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableCull();
    }

    private boolean writePartVertices(class_287 builder, class_4587 ms, class_243 pos, int index, int maxHistory, float anim, float baseSize, int rgb) {
        float progress = maxHistory == 1 ? 0.0f : (float)index / (float)Math.max(1, maxHistory);
        float spriteScale = anim * baseSize * (1.0f - progress * 0.7f);
        if (spriteScale < 0.03f) {
            return false;
        }
        float alphaVal = anim * (1.0f - progress * 0.85f);
        if (alphaVal < 0.02f) {
            return false;
        }
        float r = (float)(rgb >> 16 & 0xFF) / 255.0f;
        float g = (float)(rgb >> 8 & 0xFF) / 255.0f;
        float b = (float)(rgb & 0xFF) / 255.0f;
        this.putQuad(builder, ms, pos, spriteScale * 3.4f, r, g, b, alphaVal * 0.28f);
        this.putQuad(builder, ms, pos, spriteScale * 1.9f, r, g, b, alphaVal * 0.55f);
        this.putQuad(builder, ms, pos, spriteScale, Math.min(1.0f, r * 1.2f), Math.min(1.0f, g * 1.2f), Math.min(1.0f, b * 1.2f), alphaVal);
        return true;
    }

    private void putQuad(class_287 builder, class_4587 ms, class_243 pos, float size, float r, float g, float b, float a) {
        class_243 cam = TargetEspGhost2.mc.method_1561().field_4686.method_19326();
        ms.method_22903();
        ms.method_22904(pos.field_1352 - cam.field_1352, pos.field_1351 - cam.field_1351, pos.field_1350 - cam.field_1350);
        ms.method_22907(class_7833.field_40716.rotationDegrees(-TargetEspGhost2.mc.field_1773.method_19418().method_19330()));
        ms.method_22907(class_7833.field_40714.rotationDegrees(TargetEspGhost2.mc.field_1773.method_19418().method_19329()));
        Matrix4f matrix = ms.method_23760().method_23761();
        float half = size / 2.0f;
        int ri = (int)(r * 255.0f);
        int gi = (int)(g * 255.0f);
        int bi = (int)(b * 255.0f);
        int ai = (int)(a * 255.0f);
        builder.method_22918(matrix, -half, -half, 0.0f).method_22913(0.0f, 1.0f).method_1336(ri, gi, bi, ai);
        builder.method_22918(matrix, -half, half, 0.0f).method_22913(0.0f, 0.0f).method_1336(ri, gi, bi, ai);
        builder.method_22918(matrix, half, half, 0.0f).method_22913(1.0f, 0.0f).method_1336(ri, gi, bi, ai);
        builder.method_22918(matrix, half, -half, 0.0f).method_22913(1.0f, 1.0f).method_1336(ri, gi, bi, ai);
        ms.method_22909();
    }

    private void initGhosts() {
        this.ghosts.clear();
        for (int i = 0; i < 3; ++i) {
            GhostData ghost = new GhostData();
            ghost.angle = (float)((double)i * 2.0943951023931953);
            ghost.position = class_243.field_1353;
            ghost.history = new ArrayList<class_243>();
            for (int j = 0; j < 10; ++j) {
                ghost.history.add(class_243.field_1353);
            }
            this.ghosts.add(ghost);
        }
    }

    private static class GhostData {
        float angle;
        class_243 position;
        List<class_243> history;

        private GhostData() {
        }
    }
}

