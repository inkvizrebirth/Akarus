/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.class_310
 *  org.lwjgl.PointerBuffer
 *  org.lwjgl.glfw.GLFW
 *  org.lwjgl.glfw.GLFWVidMode
 */
package sweetie.leonware.client.features.modules.render.motionblur;

import net.minecraft.class_310;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

public class MonitorInfoProvider {
    private static long lastMonitorHandle = 0L;
    private static int lastRefreshRate = 60;
    private static long lastCheckTime = 0L;
    private static final long CHECK_INTERVAL_NS = 1000000000L;

    public static void updateDisplayInfo() {
        long now = System.nanoTime();
        if (now - lastCheckTime < 1000000000L) {
            return;
        }
        lastCheckTime = now;
        class_310 client = class_310.method_1551();
        if (client == null || client.method_22683() == null) {
            return;
        }
        long window = client.method_22683().method_4490();
        long monitor = GLFW.glfwGetWindowMonitor((long)window);
        if (monitor == 0L) {
            monitor = MonitorInfoProvider.getMonitorFromWindowPosition(window, client.method_22683().method_4480(), client.method_22683().method_4507());
        }
        if (monitor != lastMonitorHandle) {
            lastRefreshRate = MonitorInfoProvider.detectRefreshRateFromMonitor(monitor);
            lastMonitorHandle = monitor;
        }
    }

    public static int getRefreshRate() {
        return lastRefreshRate;
    }

    private static long getMonitorFromWindowPosition(long window, int windowWidth, int windowHeight) {
        int[] winX = new int[1];
        int[] winY = new int[1];
        GLFW.glfwGetWindowPos((long)window, (int[])winX, (int[])winY);
        int windowCenterX = winX[0] + windowWidth / 2;
        int windowCenterY = winY[0] + windowHeight / 2;
        long monitorResult = GLFW.glfwGetPrimaryMonitor();
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            for (int i = 0; i < monitors.limit(); ++i) {
                long m = monitors.get(i);
                int[] mx = new int[1];
                int[] my = new int[1];
                GLFW.glfwGetMonitorPos((long)m, (int[])mx, (int[])my);
                GLFWVidMode mode = GLFW.glfwGetVideoMode((long)m);
                if (mode == null) continue;
                int mw = mode.width();
                int mh = mode.height();
                if (windowCenterX < mx[0] || windowCenterX >= mx[0] + mw || windowCenterY < my[0] || windowCenterY >= my[0] + mh) continue;
                monitorResult = m;
                break;
            }
        }
        return monitorResult;
    }

    private static int detectRefreshRateFromMonitor(long monitor) {
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode((long)monitor);
        return vidMode != null ? vidMode.refreshRate() : 60;
    }
}

