/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.fabricmc.loader.api.FabricLoader
 *  net.minecraft.class_2960
 *  net.minecraft.class_310
 *  org.joml.Matrix4f
 *  org.joml.Matrix4fc
 *  org.joml.Vector3f
 *  org.ladysnake.satin.api.managed.ManagedShaderEffect
 *  org.ladysnake.satin.api.managed.ShaderEffectManager
 */
package sweetie.leonware.client.features.modules.render.motionblur;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.ladysnake.satin.api.managed.ManagedShaderEffect;
import org.ladysnake.satin.api.managed.ShaderEffectManager;
import sweetie.leonware.api.event.Listener;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.utils.other.TextUtil;
import sweetie.leonware.client.features.modules.render.motionblur.MonitorInfoProvider;
import sweetie.leonware.client.features.modules.render.motionblur.MotionBlurModule;

public class ShaderMotionBlur {
    private final MotionBlurModule config;
    private final ManagedShaderEffect motionBlurShader;
    private long lastNano;
    private float currentBlur = 0.0f;
    private float currentFPS = 0.0f;
    private final Matrix4f tempPrevModelView = new Matrix4f();
    private final Matrix4f tempPrevProjection = new Matrix4f();
    private final Matrix4f tempProjInverse = new Matrix4f();
    private final Matrix4f tempMvInverse = new Matrix4f();

    public ShaderMotionBlur(MotionBlurModule config) {
        this.config = config;
        this.motionBlurShader = ShaderEffectManager.getInstance().manage(class_2960.method_60655((String)"LeonWare".toLowerCase(), (String)"motion_blur"), shader -> shader.setUniformValue("BlendFactor", ((Float)config.strength.getValue()).floatValue()));
    }

    public void registerShaderCallbacks() {
        Render3DEvent.getInstance().subscribe(new Listener<Render3DEvent.Render3DEventData>(-1, event -> {
            long now = System.nanoTime();
            float deltaTime = (float)(now - this.lastNano) / 1.0E9f;
            float deltaTick = deltaTime * 20.0f;
            this.lastNano = now;
            this.currentFPS = deltaTime > 0.0f && deltaTime < 1.0f ? 1.0f / deltaTime : 0.0f;
            if (this.shouldRenderMotionBlur()) {
                this.applyMotionBlur(deltaTick);
            }
        }));
    }

    private boolean shouldRenderMotionBlur() {
        if (((Float)this.config.strength.getValue()).floatValue() == 0.0f || !this.config.isEnabled()) {
            return false;
        }
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            TextUtil.sendMessage("\u041d\u0435 \u043c\u043e\u0433\u0443 \u043d\u0430\u0445 \u0432\u043a\u043b\u044e\u0447\u0438\u0442\u044c, \u043f\u043e\u0442\u043e\u043c\u0443 \u0447\u0442\u043e Iris \u0441\u0442\u043e\u0438\u0442!");
            this.config.setEnabled(false);
            return false;
        }
        return true;
    }

    private void applyMotionBlur(float deltaTick) {
        float baseStrength;
        class_310 client = class_310.method_1551();
        MonitorInfoProvider.updateDisplayInfo();
        int displayRefreshRate = MonitorInfoProvider.getRefreshRate();
        float scaledStrength = baseStrength = ((Float)this.config.strength.getValue()).floatValue();
        if (((Boolean)this.config.useRRC.getValue()).booleanValue()) {
            float fpsOverRefresh;
            float f = fpsOverRefresh = displayRefreshRate > 0 ? this.currentFPS / (float)displayRefreshRate : 1.0f;
            if (fpsOverRefresh < 1.0f) {
                fpsOverRefresh = 1.0f;
            }
            scaledStrength = baseStrength * fpsOverRefresh;
        }
        if (this.currentBlur != scaledStrength) {
            this.motionBlurShader.setUniformValue("BlendFactor", scaledStrength);
            this.currentBlur = scaledStrength;
        }
        int sampleAmount = this.getSampleAmountForFPS(this.currentFPS);
        int halfSampleAmount = sampleAmount / 2;
        float invSamples = 1.0f / (float)sampleAmount;
        this.motionBlurShader.setUniformValue("view_res", (float)client.method_1522().field_1480, (float)client.method_1522().field_1477);
        this.motionBlurShader.setUniformValue("view_pixel_size", 1.0f / (float)client.method_1522().field_1480, 1.0f / (float)client.method_1522().field_1477);
        this.motionBlurShader.setUniformValue("motionBlurSamples", sampleAmount);
        this.motionBlurShader.setUniformValue("halfSamples", halfSampleAmount);
        this.motionBlurShader.setUniformValue("inverseSamples", invSamples);
        this.motionBlurShader.setUniformValue("blurAlgorithm", MotionBlurModule.BlurAlgorithm.BACKWARDS.ordinal());
        this.motionBlurShader.render(deltaTick);
    }

    private int getSampleAmountForFPS(float fps) {
        if (fps > 360.0f) {
            return 8;
        }
        if (fps > 120.0f) {
            return 10;
        }
        if (fps > 60.0f) {
            return 12;
        }
        return 20;
    }

    public void setFrameMotionBlur(Matrix4f modelView, Matrix4f prevModelView, Matrix4f projection, Matrix4f prevProjection, Vector3f cameraPos, Vector3f prevCameraPos) {
        this.motionBlurShader.setUniformValue("mvInverse", this.tempMvInverse.set((Matrix4fc)modelView).invert());
        this.motionBlurShader.setUniformValue("projInverse", this.tempProjInverse.set((Matrix4fc)projection).invert());
        this.motionBlurShader.setUniformValue("prevModelView", this.tempPrevModelView.set((Matrix4fc)prevModelView));
        this.motionBlurShader.setUniformValue("prevProjection", this.tempPrevProjection.set((Matrix4fc)prevProjection));
        this.motionBlurShader.setUniformValue("cameraPos", cameraPos.x, cameraPos.y, cameraPos.z);
        this.motionBlurShader.setUniformValue("prevCameraPos", prevCameraPos.x, prevCameraPos.y, prevCameraPos.z);
    }

    public void updateBlurStrength(float strength) {
        this.motionBlurShader.setUniformValue("BlendFactor", strength);
        this.currentBlur = strength;
    }
}

