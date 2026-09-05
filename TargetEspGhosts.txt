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
 *  net.minecraft.class_2960
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
import net.minecraft.class_2960;
import net.minecraft.class_3532;
import net.minecraft.class_4587;
import net.minecraft.class_9801;
import org.joml.Matrix4f;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.system.files.FileUtil;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;

public class TargetEspGhosts
extends TargetEspMode {
    private float animNurik = 0.0f;
    private long lastRenderTime = 0L;

    @Override
    public void onUpdate() {
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        if (currentTarget == null || !this.canDraw()) {
            return;
        }
        float anim = (float)showAnimation.getValue();
        long now = System.currentTimeMillis();
        if (this.lastRenderTime == 0L) {
            this.lastRenderTime = now;
        }
        long delta = now - this.lastRenderTime;
        this.lastRenderTime = now;
        this.animNurik += (float)(5L * delta) / 600.0f;
        double x = TargetEspGhosts.getTargetX() - TargetEspGhosts.mc.field_1773.method_19418().method_19326().field_1352;
        double y = TargetEspGhosts.getTargetY() - TargetEspGhosts.mc.field_1773.method_19418().method_19326().field_1351;
        double z = TargetEspGhosts.getTargetZ() - TargetEspGhosts.mc.field_1773.method_19418().method_19326().field_1350;
        class_4587 ms = event.matrixStack();
        RenderSystem.setShader((class_10156)class_10142.field_53880);
        RenderSystem.setShaderTexture((int)0, (class_2960)FileUtil.getImage("particles/glow"));
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE, (GlStateManager.class_4535)GlStateManager.class_4535.ZERO, (GlStateManager.class_4534)GlStateManager.class_4534.ONE);
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask((boolean)false);
        int n2 = 3;
        int n3 = 12;
        int n4 = 3 * n2;
        ms.method_22903();
        class_287 buffer = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);
        for (int i = 0; i < n4; i += n2) {
            for (int j = 0; j < n3; ++j) {
                Color color = UIColors.gradient(j * 20);
                int alpha = (int)(anim * 255.0f);
                int rgb = new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha).getRGB();
                float f2 = this.animNurik + (float)j * 0.1f;
                float f3 = 0.8f;
                float f4 = 0.5f;
                int n5 = (int)Math.pow(i, 2.0);
                ms.method_22903();
                ms.method_22904(x + (double)(f3 * class_3532.method_15374((float)(f2 + (float)n5))), y + (double)f4 + (double)(0.3f * class_3532.method_15374((float)(this.animNurik + (float)j * 0.2f))) + (double)(0.2f * (float)i), z + (double)(f3 * class_3532.method_15362((float)(f2 - (float)n5))));
                ms.method_22905(anim * (0.005f + (float)j / 2000.0f), anim * (0.005f + (float)j / 2000.0f), anim * (0.005f + (float)j / 2000.0f));
                ms.method_22907(TargetEspGhosts.mc.field_1773.method_19418().method_23767());
                Matrix4f matrix = ms.method_23760().method_23761();
                int n7 = -25;
                int n8 = 50;
                buffer.method_22918(matrix, (float)n7, (float)(n7 + n8), 0.0f).method_22913(0.0f, 1.0f).method_39415(rgb);
                buffer.method_22918(matrix, (float)(n7 + n8), (float)(n7 + n8), 0.0f).method_22913(1.0f, 1.0f).method_39415(rgb);
                buffer.method_22918(matrix, (float)(n7 + n8), (float)n7, 0.0f).method_22913(1.0f, 0.0f).method_39415(rgb);
                buffer.method_22918(matrix, (float)n7, (float)n7, 0.0f).method_22913(0.0f, 0.0f).method_39415(rgb);
                ms.method_22909();
            }
        }
        class_286.method_43433((class_9801)buffer.method_60800());
        ms.method_22909();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask((boolean)true);
        RenderSystem.disableBlend();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableCull();
    }
}

