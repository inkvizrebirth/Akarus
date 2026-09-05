/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.platform.GlStateManager$class_4534
 *  com.mojang.blaze3d.platform.GlStateManager$class_4535
 *  com.mojang.blaze3d.systems.RenderSystem
 *  net.minecraft.class_10142
 *  net.minecraft.class_10156
 *  net.minecraft.class_286
 *  net.minecraft.class_287
 *  net.minecraft.class_289
 *  net.minecraft.class_290
 *  net.minecraft.class_293$class_5596
 *  net.minecraft.class_3532
 *  net.minecraft.class_4587
 *  net.minecraft.class_9801
 *  org.joml.Matrix4f
 */
package sweetie.leonware.client.features.modules.render.targetesp.modes;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import net.minecraft.class_10142;
import net.minecraft.class_10156;
import net.minecraft.class_286;
import net.minecraft.class_287;
import net.minecraft.class_289;
import net.minecraft.class_290;
import net.minecraft.class_293;
import net.minecraft.class_3532;
import net.minecraft.class_4587;
import net.minecraft.class_9801;
import org.joml.Matrix4f;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;

public class TargetEspCircle
extends TargetEspMode {
    private float animationProgress = 0.0f;
    private long lastUpdateTime = 0L;
    private float smoothTrailHeight = 0.0f;
    private Object lastTarget = null;

    @Override
    public void onUpdate() {
        if (currentTarget != this.lastTarget) {
            this.animationProgress = 0.0f;
            this.smoothTrailHeight = 0.0f;
            this.lastTarget = currentTarget;
        }
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        float cos;
        float targetTrailHeight;
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
        }
        long delta = now - this.lastUpdateTime;
        this.lastUpdateTime = now;
        this.animationProgress += (float)delta * 0.0025f;
        double x = TargetEspCircle.getTargetX() - TargetEspCircle.mc.field_1773.method_19418().method_19326().field_1352;
        double y = TargetEspCircle.getTargetY() - TargetEspCircle.mc.field_1773.method_19418().method_19326().field_1351;
        double z = TargetEspCircle.getTargetZ() - TargetEspCircle.mc.field_1773.method_19418().method_19326().field_1350;
        float height = currentTarget.method_17682();
        float sin = class_3532.method_15374((float)this.animationProgress);
        float floatingY = (sin + 1.0f) / 2.0f * height;
        if (floatingY + (targetTrailHeight = -(cos = class_3532.method_15362((float)this.animationProgress)) * 0.5f) < 0.0f) {
            targetTrailHeight = -floatingY;
        }
        this.smoothTrailHeight = class_3532.method_16439((float)((float)delta * 0.006f), (float)this.smoothTrailHeight, (float)targetTrailHeight);
        class_4587 ms = event.matrixStack();
        Color color = UIColors.gradient(0);
        float r = (float)color.getRed() / 255.0f;
        float g = (float)color.getGreen() / 255.0f;
        float b = (float)color.getBlue() / 255.0f;
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask((boolean)false);
        RenderSystem.blendFuncSeparate((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE, (GlStateManager.class_4535)GlStateManager.class_4535.ZERO, (GlStateManager.class_4534)GlStateManager.class_4534.ONE);
        RenderSystem.setShader((class_10156)class_10142.field_53876);
        ms.method_22903();
        ms.method_22904(x, y + (double)floatingY, z);
        class_287 builder = class_289.method_1348().method_60827(class_293.class_5596.field_27380, class_290.field_1576);
        Matrix4f matrix = ms.method_23760().method_23761();
        float radius = (currentTarget.method_17681() + 0.2f) * 0.75f;
        int segments = 90;
        for (int i = 0; i <= segments; ++i) {
            float angle = (float)((double)i * Math.PI * 2.0 / (double)segments);
            float cx = class_3532.method_15362((float)angle) * radius;
            float cz = class_3532.method_15374((float)angle) * radius;
            builder.method_22918(matrix, cx, 0.0f, cz).method_22915(r, g, b, anim * 0.7f);
            builder.method_22918(matrix, cx, this.smoothTrailHeight, cz).method_22915(r, g, b, 0.0f);
        }
        class_286.method_43433((class_9801)builder.method_60800());
        class_287 lineBuilder = class_289.method_1348().method_60827(class_293.class_5596.field_29345, class_290.field_1576);
        for (int i = 0; i <= segments; ++i) {
            float angle = (float)((double)i * Math.PI * 2.0 / (double)segments);
            float cx = class_3532.method_15362((float)angle) * radius;
            float cz = class_3532.method_15374((float)angle) * radius;
            lineBuilder.method_22918(matrix, cx, 0.0f, cz).method_22915(r, g, b, anim);
        }
        class_286.method_43433((class_9801)lineBuilder.method_60800());
        ms.method_22909();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask((boolean)true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE_MINUS_SRC_ALPHA);
    }
}

