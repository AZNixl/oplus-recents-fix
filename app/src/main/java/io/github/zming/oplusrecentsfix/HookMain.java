package io.github.zming.oplusrecentsfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


/**
 * ColorOS/Oplus：当默认桌面是三方启动器（Niagara 等）时，最近任务里的
 * "浮窗"和"分屏"两个菜单项被隐藏。
 *
 * 根因（OplusLauncher_16.0.x classes5.dex，调用链经 androguard xref 确认）：
 *  - 浮窗：OplusTaskShortcutsFactory$FLOAT_WINDOW$1.getShortcut
 *      -> isUnSupportedZoomWindow -> RapidReactionUtils.isZoomWindowSupported
 *      -> 要求 !inSplitScreenMode(activity)。
 *  - 分屏：SplitScreenShortcutPolity.isSupport -> isAvailable(...) 要求
 *      !activity.getDeviceProfile().config().isMultiWindowMode()。
 *  三方桌面时 quickstep 处于 fallback 模式，ConfigParam.isMultiWindowMode()
 *  恒为 true，两个入口因此被过滤。
 *
 * 注意（v1.1 修复）：ColorOS 16 的最近任务宿主是
 *  com.android.quickstep.RecentsActivity（旧版 quickstep 的
 *  FallbackRecentsActivity 已被合并），v1.0 按类名判断 fallback 恒不成立，
 *  hook 空转。现改为直接判定"当前默认 HOME 是否为三方启动器"，
 *  系统桌面为默认时完全不干预。
 *
 * 修复（仅当默认桌面为三方启动器时生效）：
 *  A) RapidReactionUtils.inSplitScreenMode(BaseActivity) 强制返回 false；
 *  B) 重算 SplitScreenShortcutPolity.isAvailable(...)，去掉 isMultiWindowMode
 *     条件，其余按应用/系统判定与原版完全一致。
 */
public class HookMain implements IXposedHookLoadPackage {
    private static final String LAUNCHER_PKG = "com.android.launcher";
    private static final String TAG = "[OplusRecentsFix] ";

    /** 默认桌面判定缓存（3 秒），避免菜单构建期频繁 binder 调用 */
    private static long sHomeCheckTime = 0;
    private static boolean sHomeThirdParty = false;
    private static boolean sFloatFiredLogged = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!LAUNCHER_PKG.equals(lpparam.packageName)) return;
        hookFloatWindow(lpparam);
        hookSplitScreen(lpparam);
    }

    /** 当前默认 HOME 是否为三方启动器（非 com.android.launcher） */
    private static synchronized boolean isThirdPartyHome(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (now - sHomeCheckTime < 3000) return sHomeThirdParty;
        boolean third = false;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            String pkg = (ri != null && ri.activityInfo != null)
                    ? ri.activityInfo.packageName : null;
            third = pkg != null && !LAUNCHER_PKG.equals(pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "home check error: " + t);
        }
        sHomeCheckTime = now;
        sHomeThirdParty = third;
        return third;
    }

    /** A) 浮窗：inSplitScreenMode(BaseActivity) -> false（仅三方桌面时） */
    private void hookFloatWindow(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.rapidreaction.utils.RapidReactionUtils",
                    lpparam.classLoader,
                    "inSplitScreenMode",
                    "com.android.launcher3.BaseActivity",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof Context)) return;
                            if (isThirdPartyHome((Context) param.args[0])) {
                                param.setResult(false);
                                if (!sFloatFiredLogged) {
                                    sFloatFiredLogged = true;
                                    XposedBridge.log(TAG
                                            + "float-window hook FIRED (3rd-party home)");
                                }
                            }
                        }
                    });
            XposedBridge.log(TAG + "float-window hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "float-window hook FAILED: " + t);
        }
    }

    /** B) 分屏：isAvailable(BaseDraggingActivity, TaskKey) 重算（仅三方桌面时） */
    private void hookSplitScreen(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.quickstep.multiwindow.splitscreen.SplitScreenShortcutPolity",
                    lpparam.classLoader,
                    "isAvailable",
                    "com.android.launcher3.BaseDraggingActivity",
                    "com.android.systemui.shared.recents.model.Task$TaskKey",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object activity = param.args[0];
                            if (!(activity instanceof Context)) return;
                            if (!isThirdPartyHome((Context) activity)) return;
                            param.setResult(computeSplitAvailable(activity, param.args[1],
                                    lpparam.classLoader));
                        }
                    });
            XposedBridge.log(TAG + "split-screen hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "split-screen hook FAILED: " + t);
        }
    }

    /**
     * 复刻原版 isAvailable() 的判定，仅移除 config().isMultiWindowMode() 前置条件：
     *   displayId ∈ {-1, 0}
     *   && isAppSupportSplitScreen(baseIntent)   系统级按应用判定
     *   && !getSplitScreenDisable(null)          企业管控策略
     *   && !isTopWindowZoom()                    手机路径；大屏设备直接为 false
     *   && !isInSplitScreenMode(当前 displayId)
     */
    private static boolean computeSplitAvailable(Object activity, Object taskKey, ClassLoader cl) {
        try {
            Class<?> keyCls = taskKey.getClass();
            Field fDisplayId = keyCls.getField("displayId");
            Field fBaseIntent = keyCls.getField("baseIntent");
            int displayId = fDisplayId.getInt(taskKey);
            Intent baseIntent = (Intent) fBaseIntent.get(taskKey);

            Class<?> multiWindowManager =
                    cl.loadClass("com.oplus.quickstep.multiwindow.MultiWindowManager");

            boolean appSupport = true;
            try {
                Object provider = multiWindowManager
                        .getMethod("getSplitScreenInfoProvider")
                        .invoke(null);
                Object r = provider.getClass()
                        .getMethod("isAppSupportSplitScreen", Intent.class)
                        .invoke(provider, baseIntent);
                appSupport = r == null || (Boolean) r;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "isAppSupportSplitScreen error: " + t);
            }

            boolean policyDisable = false;
            try {
                Class<?> crm = Class.forName(
                        "android.os.customize.OplusCustomizeRestrictionManager");
                Object inst = crm.getMethod("getInstance", Context.class)
                        .invoke(null, (Context) activity);
                Object r = crm.getMethod("getSplitScreenDisable", ComponentName.class)
                        .invoke(inst, new Object[]{null});
                policyDisable = r != null && (Boolean) r;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "getSplitScreenDisable error: " + t);
            }

            boolean topWindowZoom = false;
            try {
                Class<?> screenUtils = cl.loadClass("com.android.common.util.ScreenUtils");
                boolean largeDisplay = (Boolean) screenUtils
                        .getMethod("isLargeDisplayDevice").invoke(null);
                if (!largeDisplay) {
                    Class<?> amw = cl.loadClass(
                            "com.android.systemui.shared.system.ActivityManagerWrapper");
                    Object amwInstance = amw.getMethod("getInstance").invoke(null);
                    Object r = amwInstance.getClass()
                            .getMethod("isTopWindowZoom")
                            .invoke(amwInstance);
                    topWindowZoom = r != null && (Boolean) r;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "isTopWindowZoom error: " + t);
            }

            boolean inSplit = false;
            try {
                int actDisplayId = displayId;
                Object r = multiWindowManager
                        .getMethod("isInSplitScreenMode", Integer.class)
                        .invoke(null, actDisplayId);
                inSplit = r != null && (Boolean) r;
            } catch (Throwable t) {
                XposedBridge.log(TAG + "isInSplitScreenMode error: " + t);
            }

            boolean ok = (displayId == -1 || displayId == 0)
                    && appSupport && !policyDisable && !topWindowZoom && !inSplit;
            XposedBridge.log(TAG + "split recompute: displayId=" + displayId
                    + " appSupport=" + appSupport
                    + " policyDisable=" + policyDisable
                    + " topWindowZoom=" + topWindowZoom
                    + " inSplit=" + inSplit + " => " + ok);
            return ok;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "split recompute FAILED: " + t);
            return false;
        }
    }
}
