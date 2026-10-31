package io.github.aw1y2z.sesame.util.hook;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;

import io.github.aw1y2z.sesame.util.XHelpers;
import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

/**
 * libxposed API 101/102 后端。
 *
 * <p>只在 {@link io.github.aw1y2z.sesame.hook.LibxposedEntry} 里被实例化：这个类直接引用
 * {@code io.github.libxposed.api}，在经典 Xposed 环境下那些类不存在，一旦被加载就会
 * {@code NoClassDefFoundError}。所以业务代码只能通过 {@link HookBackend} 间接碰它。
 */
public class LibxposedHookBackend implements HookBackend {

    private static final String TAG = "LibxposedHookBackend";

    private final io.github.libxposed.api.XposedModule module;

    public LibxposedHookBackend(io.github.libxposed.api.XposedModule module) {
        this.module = module;
    }

    @Override
    public XC_MethodHook.Unhook hookMember(Member member, XC_MethodHook callback) {
        io.github.libxposed.api.XposedInterface.HookHandle handle = module.hook((Executable) member)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.DEFAULT)
                .intercept(chain -> {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.thisObject = chain.getThisObject();
                    param.args = chain.getArgs().toArray();
                    // 允许 hook 主动调用原方法拿真实结果（见 MethodHookParam.callOriginal）。
                    // 线程内重入保护：万一 proceed 又回到本 hook，直接给原实现，不再递归。
                    param.originalCall = () -> {
                        if (!XHelpers.IN_ORIGINAL_CALL.get()) {
                            XHelpers.IN_ORIGINAL_CALL.set(true);
                            try {
                                return chain.proceed(param.args);
                            } finally {
                                XHelpers.IN_ORIGINAL_CALL.remove();
                            }
                        }
                        return chain.proceed(param.args);
                    };
                    try {
                        callback.callBefore(param);
                    } catch (Throwable t) {
                        android.util.Log.e(TAG, "beforeHookedMethod error", t);
                        return chain.proceed(param.args);
                    }
                    if (param.hasResult) {
                        return param.result;
                    }
                    Object result = chain.proceed(param.args);
                    param.result = result;
                    param.hasResult = true;
                    try {
                        callback.callAfter(param);
                    } catch (Throwable t) {
                        android.util.Log.e(TAG, "afterHookedMethod error", t);
                        return param.result;
                    }
                    return param.result;
                });
        return handle::unhook;
    }

    @Override
    public String apiVersion() {
        return String.valueOf(module.getApiVersion());
    }

    @Override
    public String frameworkName() {
        return module.getFrameworkName();
    }

    @Override
    public String frameworkVersion() {
        return module.getFrameworkVersion();
    }

    @Override
    public void log(int priority, String tag, String msg) {
        module.log(priority, tag, msg);
    }
}
