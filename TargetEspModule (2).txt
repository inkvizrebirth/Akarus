/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  lombok.Generated
 *  net.minecraft.class_1309
 */
package sweetie.leonware.client.features.modules.render.targetesp;

import lombok.Generated;
import net.minecraft.class_1309;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.system.interfaces.QuickImports;
import sweetie.leonware.api.utils.animation.AnimationUtil;
import sweetie.leonware.api.utils.animation.Easing;
import sweetie.leonware.api.utils.math.MathUtil;
import sweetie.leonware.client.features.modules.combat.AuraModule;
import sweetie.leonware.client.features.modules.render.targetesp.TargetEspModule;

public abstract class TargetEspMode
implements QuickImports {
    public static final AnimationUtil showAnimation = new AnimationUtil();
    public static final AnimationUtil sizeAnimation = new AnimationUtil();
    public static class_1309 currentTarget = null;
    public float prevShowAnimation = 0.0f;
    public float prevSizeAnimation = 0.0f;
    private static double targetX = -1.0;
    private static double targetY = -1.0;
    private static double targetZ = -1.0;
    private static double lastTargetX = -1.0;
    private static double lastTargetY = -1.0;
    private static double lastTargetZ = -1.0;

    public AuraModule aura() {
        return AuraModule.getInstance();
    }

    public void updateTarget() {
        if (this.aura().target != null) {
            currentTarget = this.aura().target;
        }
    }

    public void updateAnimation(long duration, String mode, float size, float in, float out) {
        this.prevShowAnimation = (float)showAnimation.getValue();
        this.prevSizeAnimation = (float)sizeAnimation.getValue();
        sizeAnimation.update();
        double dyingSize = switch (mode) {
            case "In" -> in;
            case "Out" -> out;
            default -> size;
        };
        sizeAnimation.run(this.reason() ? (double)size : dyingSize, duration, Easing.SINE_OUT);
        showAnimation.update();
        showAnimation.run(this.reason() ? 1.0 : 0.0, duration, Easing.SINE_OUT);
    }

    public boolean reason() {
        return this.aura().isEnabled() && this.aura().target != null;
    }

    public boolean canDraw() {
        if (TargetEspMode.mc.field_1724 == null || TargetEspMode.mc.field_1687 == null) {
            return false;
        }
        return showAnimation.getValue() > 0.0;
    }

    public static void updatePositions() {
        boolean preventUpdate;
        float animationValue = (float)showAnimation.getValue();
        float animationTarget = (float)showAnimation.getToValue();
        boolean useLastPosition = (Boolean)TargetEspModule.getInstance().lastPosition.getValue();
        boolean bl = preventUpdate = useLastPosition && (double)animationTarget == 0.0 && animationValue <= 0.9f;
        if (currentTarget != null && !preventUpdate) {
            lastTargetX = MathUtil.interpolate((float)TargetEspMode.currentTarget.field_6014, (float)currentTarget.method_23317());
            lastTargetY = MathUtil.interpolate((float)TargetEspMode.currentTarget.field_6036, (float)currentTarget.method_23318());
            lastTargetZ = MathUtil.interpolate((float)TargetEspMode.currentTarget.field_5969, (float)currentTarget.method_23321());
        }
        targetX = lastTargetX;
        targetY = lastTargetY;
        targetZ = lastTargetZ;
    }

    public abstract void onUpdate();

    public abstract void onRender3D(Render3DEvent.Render3DEventData var1);

    @Generated
    public static double getTargetX() {
        return targetX;
    }

    @Generated
    public static double getTargetY() {
        return targetY;
    }

    @Generated
    public static double getTargetZ() {
        return targetZ;
    }
}

