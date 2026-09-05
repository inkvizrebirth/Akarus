/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 */
package sweetie.leonware.client.features.modules.render.motionblur;

import lombok.Generated;
import sweetie.leonware.api.module.Category;
import sweetie.leonware.api.module.Module;
import sweetie.leonware.api.module.ModuleRegister;
import sweetie.leonware.api.module.setting.BooleanSetting;
import sweetie.leonware.api.module.setting.SliderSetting;
import sweetie.leonware.client.features.modules.render.motionblur.ShaderMotionBlur;

@ModuleRegister(name="Motion Blur", category=Category.RENDER)
public class MotionBlurModule
extends Module {
    private static final MotionBlurModule instance = new MotionBlurModule();
    public final ShaderMotionBlur shader;
    public final SliderSetting strength = new SliderSetting("Strength").value(Float.valueOf(-0.8f)).range(-2.0f, 2.0f).step(0.1f).onAction(() -> this.setMotionBlurStrength(((Float)this.getStrength().getValue()).floatValue()));
    public final BooleanSetting useRRC = new BooleanSetting("Use refresh rate scaling").value(true);
    public static BlurAlgorithm blurAlgorithm = BlurAlgorithm.CENTERED;

    public MotionBlurModule() {
        this.shader = new ShaderMotionBlur(this);
        this.shader.registerShaderCallbacks();
        this.addSettings(this.strength, this.useRRC);
    }

    @Override
    public void onEvent() {
    }

    private void setMotionBlurStrength(float strength) {
        this.shader.updateBlurStrength(strength);
    }

    @Generated
    public static MotionBlurModule getInstance() {
        return instance;
    }

    @Generated
    public SliderSetting getStrength() {
        return this.strength;
    }

    public static enum BlurAlgorithm {
        BACKWARDS,
        CENTERED;

    }
}

