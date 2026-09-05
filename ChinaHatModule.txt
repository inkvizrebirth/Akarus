/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.mojang.blaze3d.systems.RenderSystem
 *  lombok.Generated
 *  net.minecraft.class_10142
 *  net.minecraft.class_10156
 *  net.minecraft.class_243
 *  net.minecraft.class_286
 *  net.minecraft.class_287
 *  net.minecraft.class_289
 *  net.minecraft.class_290
 *  net.minecraft.class_293$class_5596
 *  net.minecraft.class_3532
 *  net.minecraft.class_9801
 *  org.joml.Matrix4f
 */
package sweetie.leonware.client.features.modules.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Color;
import lombok.Generated;
import net.minecraft.class_10142;
import net.minecraft.class_10156;
import net.minecraft.class_243;
import net.minecraft.class_286;
import net.minecraft.class_287;
import net.minecraft.class_289;
import net.minecraft.class_290;
import net.minecraft.class_293;
import net.minecraft.class_3532;
import net.minecraft.class_9801;
import org.joml.Matrix4f;
import sweetie.leonware.api.event.EventListener;
import sweetie.leonware.api.event.Listener;
import sweetie.leonware.api.event.events.render.Render3DEvent;
import sweetie.leonware.api.module.Category;
import sweetie.leonware.api.module.Module;
import sweetie.leonware.api.module.ModuleRegister;
import sweetie.leonware.api.module.setting.BooleanSetting;
import sweetie.leonware.api.module.setting.SliderSetting;
import sweetie.leonware.api.utils.color.UIColors;

@ModuleRegister(name="China Hat", category=Category.RENDER)
public class ChinaHatModule
extends Module {
    private static final ChinaHatModule instance = new ChinaHatModule();
    private final SliderSetting height = new SliderSetting("\u0412\u044b\u0441\u043e\u0442\u0430").value(Float.valueOf(0.3f)).range(0.1f, 1.0f).step(0.01f);
    private final SliderSetting radius = new SliderSetting("\u0420\u0430\u0434\u0438\u0443\u0441").value(Float.valueOf(0.6f)).range(0.1f, 1.5f).step(0.05f);
    private final SliderSetting segments = new SliderSetting("\u0421\u0435\u0433\u043c\u0435\u043d\u0442\u044b").value(Float.valueOf(30.0f)).range(10.0f, 60.0f).step(1.0f);
    private final SliderSetting alpha = new SliderSetting("\u041f\u0440\u043e\u0437\u0440\u0430\u0447\u043d\u043e\u0441\u0442\u044c").value(Float.valueOf(120.0f)).range(0.0f, 255.0f).step(1.0f);
    private final BooleanSetting firstPerson = new BooleanSetting("\u041e\u0442 1 \u043b\u0438\u0446\u0430").value(false);
    private final SliderSetting yOffset = new SliderSetting("Y-\u0421\u043c\u0435\u0449\u0435\u043d\u0438\u0435").value(Float.valueOf(0.0f)).range(-0.5f, 0.5f).step(0.01f);

    public ChinaHatModule() {
        this.addSettings(this.height, this.radius, this.segments, this.alpha, this.firstPerson, this.yOffset);
    }

    @Override
    public void onEvent() {
        EventListener render3DEvent = Render3DEvent.getInstance().subscribe(new Listener<Render3DEvent.Render3DEventData>(event -> {
            if (ChinaHatModule.mc.field_1724 == null || ChinaHatModule.mc.field_1687 == null) {
                return;
            }
            if (!((Boolean)this.firstPerson.getValue()).booleanValue() && ChinaHatModule.mc.field_1690.method_31044().method_31034()) {
                return;
            }
            float partialTicks = event.partialTicks();
            class_243 pos = ChinaHatModule.mc.field_1724.method_30950(partialTicks);
            double viewX = ChinaHatModule.mc.field_1773.method_19418().method_19326().field_1352;
            double viewY = ChinaHatModule.mc.field_1773.method_19418().method_19326().field_1351;
            double viewZ = ChinaHatModule.mc.field_1773.method_19418().method_19326().field_1350;
            double x = pos.field_1352 - viewX;
            double y = pos.field_1351 - viewY + (ChinaHatModule.mc.field_1724.method_5715() ? 1.5 : 1.9) + (double)((Float)this.yOffset.getValue()).floatValue();
            double z = pos.field_1350 - viewZ;
            float yaw = class_3532.method_17821((float)partialTicks, (float)ChinaHatModule.mc.field_1724.field_6220, (float)ChinaHatModule.mc.field_1724.field_6283);
            Matrix4f matrix = event.matrixStack().method_23760().method_23761();
            int a = ((Float)this.alpha.getValue()).intValue();
            int timeOffset = (int)(System.currentTimeMillis() / 10L);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader((class_10156)class_10142.field_53876);
            class_289 tessellator = class_289.method_1348();
            class_287 bufferBuilder = tessellator.method_60827(class_293.class_5596.field_27381, class_290.field_1576);
            Color topCol = UIColors.gradient(timeOffset, a);
            bufferBuilder.method_22918(matrix, (float)x, (float)(y + (double)((Float)this.height.getValue()).floatValue()), (float)z).method_1336(topCol.getRed(), topCol.getGreen(), topCol.getBlue(), topCol.getAlpha());
            float r = ((Float)this.radius.getValue()).floatValue();
            int segs = ((Float)this.segments.getValue()).intValue();
            for (int i = 0; i <= segs; ++i) {
                double angle = Math.toRadians((double)i * 360.0 / (double)segs - (double)yaw);
                float dx = (float)(Math.sin(angle) * (double)r);
                float dz = (float)(Math.cos(angle) * (double)r);
                Color botCol = UIColors.gradient(timeOffset + 20, a);
                bufferBuilder.method_22918(matrix, (float)(x + (double)dx), (float)y, (float)(z + (double)dz)).method_1336(botCol.getRed(), botCol.getGreen(), botCol.getBlue(), botCol.getAlpha());
            }
            class_286.method_43433((class_9801)bufferBuilder.method_60800());
            class_287 lineBuffer = tessellator.method_60827(class_293.class_5596.field_29345, class_290.field_1576);
            for (int i = 0; i <= segs; ++i) {
                double angle = Math.toRadians((double)i * 360.0 / (double)segs - (double)yaw);
                float dx = (float)(Math.sin(angle) * (double)r);
                float dz = (float)(Math.cos(angle) * (double)r);
                Color edgeColor = UIColors.gradient(timeOffset + i * 2, 255);
                lineBuffer.method_22918(matrix, (float)(x + (double)dx), (float)y, (float)(z + (double)dz)).method_1336(edgeColor.getRed(), edgeColor.getGreen(), edgeColor.getBlue(), 255);
            }
            class_286.method_43433((class_9801)lineBuffer.method_60800());
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }));
        this.addEvents(render3DEvent);
    }

    @Generated
    public static ChinaHatModule getInstance() {
        return instance;
    }
}

