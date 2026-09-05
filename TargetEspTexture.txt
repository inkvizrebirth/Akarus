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
 *  net.minecraft.class_4184
 *  net.minecraft.class_4587
 *  net.minecraft.class_7833
 *  net.minecraft.class_9801
 *  org.joml.Matrix4f
 */
package sweetie.leonware.client.features.modules.render.targetesp.modes;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.class_10142;
import net.minecraft.class_10156;
import net.minecraft.class_286;
import net.minecraft.class_287;
import net.minecraft.class_289;
import net.minecraft.class_290;
import net.minecraft.class_293;
import net.minecraft.class_2960;
import net.minecraft.class_4184;
import net.minecraft.class_4587;
import net.minecraft.class_7833;
import net.minecraft.class_9801;
import org.joml.Matrix4f;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.system.files.FileUtil;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.api.utils.math.MathUtil;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;

public class TargetEspTexture
extends TargetEspMode {
    private float espValue = 1.0f;
    private float prevEspValue = 0.0f;
    private float espSpeed = 1.0f;
    private boolean flip = false;

    @Override
    public void onUpdate() {
        if (currentTarget == null || !this.canDraw()) {
            return;
        }
        this.prevEspValue = this.espValue;
        if (showAnimation.getValue() > 0.8) {
            this.espValue += this.espSpeed;
            if (this.espSpeed > 25.0f) {
                this.flip = true;
            }
            if (this.espSpeed < -25.0f) {
                this.flip = false;
            }
        }
        this.espSpeed = (float)((double)(this.flip ? this.espSpeed - 0.5f : this.espSpeed + 0.5f) * showAnimation.getValue());
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        if (currentTarget == null || !this.canDraw()) {
            return;
        }
        class_4184 camera = TargetEspTexture.mc.field_1773.method_19418();
        double x = TargetEspTexture.getTargetX() - camera.method_19326().field_1352;
        double y = TargetEspTexture.getTargetY() - camera.method_19326().field_1351;
        double z = TargetEspTexture.getTargetZ() - camera.method_19326().field_1350;
        double size = MathUtil.interpolate(this.prevSizeAnimation, sizeAnimation.getValue());
        class_4587 matrices = new class_4587();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        matrices.method_22907(class_7833.field_40714.rotationDegrees(camera.method_19329()));
        matrices.method_22907(class_7833.field_40716.rotationDegrees(camera.method_19330() + 180.0f));
        matrices.method_22904(x, y + (double)(currentTarget.method_17682() / 2.0f), z);
        matrices.method_22907(class_7833.field_40716.rotationDegrees(-camera.method_19330()));
        matrices.method_22907(class_7833.field_40714.rotationDegrees(camera.method_19329()));
        matrices.method_22907(class_7833.field_40718.rotationDegrees(MathUtil.interpolate(this.prevEspValue, this.espValue)));
        RenderSystem.enableBlend();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE);
        RenderSystem.setShaderTexture((int)0, (class_2960)FileUtil.getImage("target/rounded_target_esp"));
        matrices.method_22904(-size / 2.0, -size / 2.0, -0.01);
        Matrix4f matrix = matrices.method_23760().method_23761();
        RenderSystem.setShader((class_10156)class_10142.field_53880);
        int alpha = (int)(showAnimation.getValue() * 255.0);
        int color1 = UIColors.primary(alpha).getRGB();
        int color2 = UIColors.secondary(alpha).getRGB();
        class_287 buffer = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);
        buffer.method_22918(matrix, 0.0f, (float)size, 0.0f).method_22913(0.0f, 1.0f).method_39415(color1);
        buffer.method_22918(matrix, (float)size, (float)size, 0.0f).method_22913(1.0f, 1.0f).method_39415(color2);
        buffer.method_22918(matrix, (float)size, 0.0f, 0.0f).method_22913(1.0f, 0.0f).method_39415(color1);
        buffer.method_22918(matrix, 0.0f, 0.0f, 0.0f).method_22913(0.0f, 0.0f).method_39415(color2);
        class_286.method_43433((class_9801)buffer.method_60800());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
    }
}

