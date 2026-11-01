package io.github.aw1y2z.sesame.hook;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.aw1y2z.sesame.util.hook.LibxposedHookBackend;

/**
 * libxposed API 101 / 102 入口（LSPosed 等现代框架）。
 *
 * <p>由 {@code META-INF/xposed/java_init.list} 指名加载。只做一件事：
 * 把生命周期回调转给框架无关的 {@link ApplicationHook}。
 *
 * <p>本类是唯一直接引用 {@code io.github.libxposed.api} 的地方（后端除外），
 * 经典 Xposed 环境下不会加载它，所以 {@code NoClassDefFoundError} 不会传染到业务代码。
 */
public class LibxposedEntry extends XposedModule {

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        ApplicationHook.onModuleLoadedCommon(new LibxposedHookBackend(this));
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        ApplicationHook.onPackageReadyCommon(param.getPackageName(), param.getClassLoader());
    }
}
