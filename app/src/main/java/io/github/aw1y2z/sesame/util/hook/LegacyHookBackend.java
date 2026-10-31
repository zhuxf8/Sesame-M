package io.github.aw1y2z.sesame.util.hook;

import java.lang.reflect.Member;

import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

/**
 * 经典 Xposed API（82 / 89 / 93 …）后端。
 *
 * <p>直接走 {@code de.robv.android.xposed.XposedBridge.hookMethod}。这个类**刻意不用字符串反射**
 * 去调 XposedBridge：老框架下它就是宿主提供的真实类，直接调用既快又不会因为反射签名差异失效。
 * 代价是本类只能在经典框架的类加载器里被加载——由 {@link io.github.aw1y2z.sesame.hook.LegacyXposedEntry}
 * 保证（它实现了 {@code IXposedHookLoadPackage}，只有经典框架会加载它）。
 */
public class LegacyHookBackend implements HookBackend {

    private static final String TAG = "LegacyHookBackend";

    @Override
    public XC_MethodHook.Unhook hookMember(Member member, XC_MethodHook callback) {
        de.robv.android.xposed.XC_MethodHook legacyCallback = new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                p.thisObject = param.thisObject;
                p.args = param.args;
                // 老框架的 callOriginal 是 XposedBridge.invokeOriginalMethod，需要记下 method 成员
                Member hooked = param.method;
                Object thisObj = param.thisObject;
                Object[] args = param.args;
                p.originalCall = () -> {
                    try {
                        return de.robv.android.xposed.XposedBridge.invokeOriginalMethod(hooked, thisObj, args);
                    } catch (java.lang.reflect.InvocationTargetException ite) {
                        throw ite.getCause() == null ? ite : ite.getCause();
                    }
                };
                try {
                    callback.callBefore(p);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "beforeHookedMethod error", t);
                    return;
                }
                // 把改动写回老框架的 param，否则 setResult 不生效
                param.args = p.args;
                if (p.hasResult) {
                    param.setResult(p.result);
                }
                if (p.exception != null) {
                    param.setThrowable(p.exception);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                XC_MethodHook.MethodHookParam p = new XC_MethodHook.MethodHookParam();
                p.thisObject = param.thisObject;
                p.args = param.args;
                if (param.hasThrowable()) {
                    p.exception = param.getThrowable();
                } else {
                    p.result = param.getResult();
                    p.hasResult = true;
                }
                try {
                    callback.callAfter(p);
                } catch (Throwable t) {
                    android.util.Log.e(TAG, "afterHookedMethod error", t);
                    return;
                }
                if (p.exception != null) {
                    param.setThrowable(p.exception);
                } else if (p.hasResult) {
                    param.setResult(p.result);
                }
            }
        };
        de.robv.android.xposed.XC_MethodHook.Unhook unhook =
                de.robv.android.xposed.XposedBridge.hookMethod(member, legacyCallback);
        return unhook::unhook;
    }

    @Override
    public String apiVersion() {
        try {
            return String.valueOf(de.robv.android.xposed.XposedBridge.getXposedVersion());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String frameworkName() {
        return "legacy-xposed";
    }

    @Override
    public String frameworkVersion() {
        try {
            return String.valueOf(de.robv.android.xposed.XposedBridge.getXposedVersion());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public void log(int priority, String tag, String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log(tag + ": " + msg);
        } catch (Throwable ignored) {
            android.util.Log.println(priority, tag, msg);
        }
    }
}
