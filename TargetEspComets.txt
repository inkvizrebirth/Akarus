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
import java.util.ArrayList;
import java.util.List;
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
import sweetie.leonware.api.utils.color.ColorUtil;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.api.utils.math.MathUtil;
import sweetie.leonware.api.utils.render.RenderUtil;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;

public class TargetEspComets
extends TargetEspMode {
    private final List<double[]> previousPositions = new ArrayList<double[]>();
    private float ghostRotationAngle = 0.0f;
    private float ghostYRotationAngle = 0.0f;
    private float prevGhostRotationAngle = 0.0f;
    private float prevGhostYRotationAngle = 0.0f;

    @Override
    public void onUpdate() {
        this.updateTarget();
        this.prevGhostRotationAngle = this.ghostRotationAngle;
        this.prevGhostYRotationAngle = this.ghostYRotationAngle;
        this.ghostRotationAngle += 15.0f;
        this.ghostYRotationAngle += 15.0f;
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        int i;
        if (currentTarget == null || !this.canDraw()) {
            return;
        }
        class_4587 matrixStack = event.matrixStack();
        RenderUtil.WORLD.startRender(matrixStack);
        float size = 0.25f;
        float dony = 1.15f * MathUtil.interpolate(this.prevSizeAnimation, sizeAnimation.getValue());
        float rem = (float)(0.4 * (double)MathUtil.interpolate(this.prevSizeAnimation, sizeAnimation.getValue()));
        int trailLength = 21;
        ArrayList<double[]> currentGhostPositions = new ArrayList<double[]>();
        double centerX = TargetEspComets.getTargetX();
        double centerY = TargetEspComets.getTargetY() + (double)(currentTarget.method_17682() / 2.0f);
        double centerZ = TargetEspComets.getTargetZ();
        for (i = 0; i < 3; ++i) {
            float angle = MathUtil.interpolate(this.prevGhostRotationAngle, this.ghostRotationAngle) + (float)(i * 180);
            double radius = currentTarget.method_17681() * dony;
            double offsetX = Math.cos(Math.toRadians(angle)) * radius;
            double offsetZ = Math.sin(Math.toRadians(angle)) * radius;
            double offsetY = Math.cos(Math.toRadians(angle)) / 3.0 * radius;
            double ghostYI = MathUtil.interpolate(this.prevGhostYRotationAngle, this.ghostYRotationAngle);
            if (i == 0) {
                offsetY = Math.sin(Math.toRadians(ghostYI)) * (double)currentTarget.method_17682() * (double)rem;
            } else if (i == 2) {
                offsetY = -Math.sin(Math.toRadians(ghostYI)) * (double)currentTarget.method_17682() * (double)rem;
                offsetX = -Math.cos(Math.toRadians(angle)) * radius;
            }
            double ghostX = centerX + offsetX;
            double ghostY = centerY + offsetY;
            double ghostZ = centerZ + offsetZ;
            currentGhostPositions.add(new double[]{ghostX, ghostY, ghostZ, angle});
        }
        this.previousPositions.addAll(currentGhostPositions);
        if (this.previousPositions.size() > trailLength * 3) {
            this.previousPositions.subList(0, this.previousPositions.size() - trailLength * 3).clear();
        }
        for (i = 0; i < 3; ++i) {
            double[] currentPos = (double[])currentGhostPositions.get(i);
            float angle = (float)currentPos[3];
            double renderX = currentPos[0] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10216();
            double renderY = currentPos[1] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10214();
            double renderZ = currentPos[2] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10215();
            this.renderGhost(matrixStack, renderX, renderY, renderZ, angle, size, 1.0f);
            for (int t = 0; t < this.previousPositions.size() / 3; ++t) {
                int index = t * 3 + i;
                if (index >= this.previousPositions.size()) continue;
                double[] trailPos = this.previousPositions.get(index);
                double trailRenderX = trailPos[0] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10216();
                double trailRenderY = trailPos[1] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10214();
                double trailRenderZ = trailPos[2] - TargetEspComets.mc.method_1561().field_4686.method_19326().method_10215();
                float trailAngle = (float)trailPos[3];
                float trailAlpha = (float)(t + 1) / ((float)this.previousPositions.size() / 3.0f + 1.0f);
                float trailSize = Math.max(size * 0.4f, size * trailAlpha);
                this.renderGhost(matrixStack, trailRenderX, trailRenderY, trailRenderZ, trailAngle, trailSize, trailAlpha);
            }
        }
        RenderSystem.enableCull();
        RenderSystem.blendFunc((GlStateManager.class_4535)GlStateManager.class_4535.SRC_ALPHA, (GlStateManager.class_4534)GlStateManager.class_4534.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderColor((float)1.0f, (float)1.0f, (float)1.0f, (float)1.0f);
        RenderUtil.WORLD.endRender(matrixStack);
    }

    public void renderGhost(class_4587 stack, double x, double y, double z, float angle, float size, float alphaMultiplier) {
        stack.method_22903();
        stack.method_22904(x, y, z);
        class_4184 camera = TargetEspComets.mc.field_1773.method_19418();
        stack.method_22907(class_7833.field_40716.rotationDegrees(-camera.method_19330() + 180.0f));
        stack.method_22907(class_7833.field_40714.rotationDegrees(-camera.method_19329() + 180.0f));
        RenderSystem.setShaderTexture((int)0, (class_2960)FileUtil.getImage("particles/glow"));
        Matrix4f matrix = stack.method_23760().method_23761();
        RenderSystem.setShader((class_10156)class_10142.field_53880);
        class_287 buffer = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);
        float[] c = ColorUtil.normalize(ColorUtil.setAlpha(UIColors.gradient((int)angle), (int)(255.0 * showAnimation.getValue())));
        float alpha = c[3] * alphaMultiplier;
        buffer.method_22918(matrix, -size, size, 0.0f).method_22913(0.0f, 1.0f).method_22915(c[0], c[1], c[2], alpha);
        buffer.method_22918(matrix, size, size, 0.0f).method_22913(1.0f, 1.0f).method_22915(c[0], c[1], c[2], alpha);
        buffer.method_22918(matrix, size, -size, 0.0f).method_22913(1.0f, 0.0f).method_22915(c[0], c[1], c[2], alpha);
        buffer.method_22918(matrix, -size, -size, 0.0f).method_22913(0.0f, 0.0f).method_22915(c[0], c[1], c[2], alpha);
        class_286.method_43433((class_9801)buffer.method_60800());
        stack.method_22909();
    }
}

