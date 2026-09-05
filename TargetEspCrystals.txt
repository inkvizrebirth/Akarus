/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.systems.RenderSystem
 *  net.minecraft.class_10142
 *  net.minecraft.class_10156
 *  net.minecraft.class_1297
 *  net.minecraft.class_1309
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
 *  org.joml.Vector3f
 */
package sweetie.leonware.client.features.modules.render.targetesp.modes;

import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.class_10142;
import net.minecraft.class_10156;
import net.minecraft.class_1297;
import net.minecraft.class_1309;
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
import org.joml.Vector3f;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.system.files.FileUtil;
import sweetie.leonware.api.utils.color.ColorUtil;
import sweetie.leonware.api.utils.color.UIColors;
import sweetie.leonware.api.utils.render.RenderUtil;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspMode;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspModule;

public class TargetEspCrystals
extends TargetEspMode {
    private final List<CrystalData> crystals = new ArrayList<CrystalData>();
    private final Random random = new Random();
    private Object lastTarget = null;
    private float animationTick = 0.0f;
    private float prevAnimationTick = 0.0f;

    @Override
    public void onUpdate() {
        if (this.aura().target != null) {
            currentTarget = this.aura().target;
        } else {
            class_1297 class_12972 = TargetEspCrystals.mc.field_1692;
            if (class_12972 instanceof class_1309) {
                class_1309 living;
                currentTarget = living = (class_1309)class_12972;
            }
        }
        this.prevAnimationTick = this.animationTick;
        this.animationTick += TargetEspModule.instance.getCrystalsSpeed();
        if (currentTarget != this.lastTarget) {
            this.crystals.clear();
            if (currentTarget != null) {
                this.generateCrystals();
            }
            this.lastTarget = currentTarget;
        }
    }

    @Override
    public void onRender3D(Render3DEvent.Render3DEventData event) {
        if (currentTarget == null || !this.canDraw() || this.crystals.isEmpty()) {
            return;
        }
        class_4587 ms = event.matrixStack();
        float alphaAnim = (float)showAnimation.getValue();
        float tickDelta = mc.method_61966().method_60637(true);
        float renderTick = this.prevAnimationTick + (this.animationTick - this.prevAnimationTick) * tickDelta;
        RenderUtil.WORLD.startRender(ms);
        double tx = TargetEspCrystals.getTargetX() - TargetEspCrystals.mc.method_1561().field_4686.method_19326().method_10216();
        double ty = TargetEspCrystals.getTargetY() - TargetEspCrystals.mc.method_1561().field_4686.method_19326().method_10214();
        double tz = TargetEspCrystals.getTargetZ() - TargetEspCrystals.mc.method_1561().field_4686.method_19326().method_10215();
        float orbitSpeed = 0.02f * TargetEspModule.instance.getCrystalsSpeed();
        for (CrystalData crystal : this.crystals) {
            float floatAnim = (float)Math.sin(Math.toRadians(renderTick + (float)(crystal.index * 35))) * 0.06f;
            float orbitAngle = crystal.baseAngle + renderTick * orbitSpeed;
            double rx = tx + Math.cos(orbitAngle) * (double)crystal.baseRadius;
            double ry = ty + (double)crystal.pos.y() + (double)floatAnim;
            double rz = tz + Math.sin(orbitAngle) * (double)crystal.baseRadius;
            Color finalColor = UIColors.gradient(crystal.index * 30);
            this.renderCrystal(ms, rx, ry, rz, crystal, finalColor, alphaAnim, renderTick);
            this.renderGlow(ms, rx, ry, rz, finalColor, alphaAnim * 0.45f, crystal.scale);
        }
        RenderUtil.WORLD.endRender(ms);
    }

    private void renderCrystal(class_4587 ms, double x, double y, double z, CrystalData data, Color color, float alpha, float renderTick) {
        ms.method_22903();
        ms.method_22904(x, y, z);
        float s = data.scale * 0.12f;
        ms.method_22905(s, s, s);
        ms.method_22907(class_7833.field_40714.rotationDegrees(data.rot.x()));
        ms.method_22907(class_7833.field_40716.rotationDegrees(data.rot.y() + renderTick * 1.5f));
        ms.method_22907(class_7833.field_40718.rotationDegrees(data.rot.z() + renderTick * 0.7f));
        float[] c = ColorUtil.normalize(ColorUtil.setAlpha(color, (int)(220.0f * alpha)));
        RenderSystem.setShader((class_10156)class_10142.field_53876);
        class_287 buffer = class_289.method_1348().method_60827(class_293.class_5596.field_27379, class_290.field_1576);
        Matrix4f matrix = ms.method_23760().method_23761();
        float w = 1.0f;
        float h = 2.0f;
        this.drawFace(buffer, matrix, 0.0f, h, 0.0f, -w, 0.0f, w, w, 0.0f, w, c);
        this.drawFace(buffer, matrix, 0.0f, h, 0.0f, w, 0.0f, w, w, 0.0f, -w, c);
        this.drawFace(buffer, matrix, 0.0f, h, 0.0f, w, 0.0f, -w, -w, 0.0f, -w, c);
        this.drawFace(buffer, matrix, 0.0f, h, 0.0f, -w, 0.0f, -w, -w, 0.0f, w, c);
        this.drawFace(buffer, matrix, 0.0f, -h, 0.0f, w, 0.0f, w, -w, 0.0f, w, c);
        this.drawFace(buffer, matrix, 0.0f, -h, 0.0f, w, 0.0f, -w, w, 0.0f, w, c);
        this.drawFace(buffer, matrix, 0.0f, -h, 0.0f, -w, 0.0f, -w, w, 0.0f, -w, c);
        this.drawFace(buffer, matrix, 0.0f, -h, 0.0f, -w, 0.0f, w, -w, 0.0f, -w, c);
        class_286.method_43433((class_9801)buffer.method_60800());
        ms.method_22909();
    }

    private void drawFace(class_287 b, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float[] c) {
        b.method_22918(m, x1, y1, z1).method_22915(c[0], c[1], c[2], c[3]);
        b.method_22918(m, x2, y2, z2).method_22915(c[0], c[1], c[2], c[3]);
        b.method_22918(m, x3, y3, z3).method_22915(c[0], c[1], c[2], c[3]);
    }

    private void renderGlow(class_4587 ms, double x, double y, double z, Color color, float alpha, float scale) {
        ms.method_22903();
        ms.method_22904(x, y, z);
        ms.method_22907(class_7833.field_40716.rotationDegrees(-TargetEspCrystals.mc.field_1773.method_19418().method_19330() + 180.0f));
        ms.method_22907(class_7833.field_40714.rotationDegrees(-TargetEspCrystals.mc.field_1773.method_19418().method_19329() + 180.0f));
        float size = 0.5f * scale;
        float[] c = ColorUtil.normalize(ColorUtil.setAlpha(color, (int)(255.0f * alpha)));
        RenderSystem.setShaderTexture((int)0, (class_2960)FileUtil.getImage("particles/glow"));
        RenderSystem.setShader((class_10156)class_10142.field_53880);
        class_287 buffer = class_289.method_1348().method_60827(class_293.class_5596.field_27382, class_290.field_1575);
        Matrix4f matrix = ms.method_23760().method_23761();
        buffer.method_22918(matrix, -size, size, 0.0f).method_22913(0.0f, 1.0f).method_22915(c[0], c[1], c[2], c[3]);
        buffer.method_22918(matrix, size, size, 0.0f).method_22913(1.0f, 1.0f).method_22915(c[0], c[1], c[2], c[3]);
        buffer.method_22918(matrix, size, -size, 0.0f).method_22913(1.0f, 0.0f).method_22915(c[0], c[1], c[2], c[3]);
        buffer.method_22918(matrix, -size, -size, 0.0f).method_22913(0.0f, 0.0f).method_22915(c[0], c[1], c[2], c[3]);
        class_286.method_43433((class_9801)buffer.method_60800());
        ms.method_22909();
    }

    private void generateCrystals() {
        this.crystals.clear();
        int count = TargetEspModule.instance.getCrystalsCount();
        float height = currentTarget.method_17682();
        float width = currentTarget.method_17681();
        float baseRadius = Math.max(0.75f, width * 0.9f + 0.35f);
        float minY = 0.15f;
        float maxY = Math.max(minY + 0.2f, height - 0.15f);
        for (int i = 0; i < count; ++i) {
            float angle = (float)((double)((float)i / (float)count) * Math.PI * 2.0);
            float y = minY + (maxY - minY) * (float)(i % 3) / 2.0f;
            this.crystals.add(new CrystalData(new Vector3f(0.0f, y, 0.0f), new Vector3f(20.0f + this.random.nextFloat() * 40.0f, this.random.nextFloat() * 360.0f, this.random.nextFloat() * 360.0f), 0.75f + this.random.nextFloat() * 0.35f, i, angle, baseRadius + (this.random.nextFloat() - 0.5f) * 0.18f));
        }
    }

    private static class CrystalData {
        private final Vector3f pos;
        private final Vector3f rot;
        private final float scale;
        private final float baseAngle;
        private final float baseRadius;
        private final int index;

        public CrystalData(Vector3f pos, Vector3f rot, float scale, int index, float baseAngle, float baseRadius) {
            this.pos = pos;
            this.rot = rot;
            this.scale = scale;
            this.index = index;
            this.baseAngle = baseAngle;
            this.baseRadius = baseRadius;
        }
    }
}

