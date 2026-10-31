package io.github.aw1y2z.sesame.hook;

import android.app.Application;
import android.content.Intent;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import io.github.aw1y2z.sesame.data.RunType;
import io.github.aw1y2z.sesame.data.ViewAppInfo;
import io.github.aw1y2z.sesame.util.hook.LegacyHookBackend;

/**
 * 经典 Xposed API（82 / 89 / 93 …）入口，供老 Xposed、LSPosed 兼容模式、
 * 以及其它仍走 {@code assets/xposed_init} 的兼容框架加载。
 *
 * <p>LSPosed 的模块判定逻辑是「APK 里有 {@code META-INF/xposed/java_init.list} 就算现代模块」，
 * 否则才去读 {@code xposedminversion} + {@code assets/xposed_init}。本仓库两者都提供：
 * 现代框架走 {@link LibxposedEntry}，老框架走这里，互不干扰。
 *
 * <p>经典 API 没有 "module loaded" 这种回调，所以激活标记与配置加载在
 * {@link #handleLoadPackage} 里补一次——只在支付宝主进程里做，子进程不重复。
 */
public class LegacyXposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = "LegacyXposedEntry";

    /** 模块自身包名：与 applicationId 一致，用于区分"被注入的目标"和"模块 App 自己" */
    private static final String MODULE_PACKAGE_NAME = "io.github.aw1y2z.sesame";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null) {
            return;
        }
        // 只在支付宝进程里工作：其它应用不注入
        if (!"com.eg.android.AlipayGphone".equals(lpparam.packageName)) {
            return;
        }
        // 经典框架下每个进程都会回调本方法，用静态标记保证"模块加载"只做一次
        if (LegacyInit.ensureLoaded()) {
            try {
                ApplicationHook.onModuleLoadedCommon(new LegacyHookBackend());
            } catch (Throwable t) {
                android.util.Log.e(TAG, "onModuleLoadedCommon failed", t);
            }
        }
        try {
            ApplicationHook.onPackageReadyCommon(lpparam.packageName, lpparam.classLoader);
        } catch (Throwable t) {
            android.util.Log.e(TAG, "onPackageReadyCommon failed", t);
        }
    }

    /** 经典框架下的"模块已加载"幂等标记 */
    private static final class LegacyInit {
        private static volatile boolean loaded = false;

        static synchronized boolean ensureLoaded() {
            if (loaded) {
                return false;
            }
            loaded = true;
            return true;
        }
    }
}
