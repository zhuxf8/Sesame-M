package io.github.aw1y2z.sesame.util.hook;

import java.lang.reflect.Member;

import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;

/**
 * Hook 后端抽象：把「谁来挂 hook」从业务代码里摘出来。
 *
 * <p>模块要同时跑在新旧两套框架上：
 * <ul>
 *   <li><b>libxposed API 101/102</b>（LSPosed 新版入口）——走 {@link LibxposedHookBackend}</li>
 *   <li><b>经典 Xposed API 82/89</b>（老 Xposed、LSPosed 的兼容模式、其他兼容框架）
 *       ——走 {@link LegacyHookBackend}</li>
 * </ul>
 * 两个后端互不引用对方的类，避免在一侧缺少对应 API 时触发 {@code NoClassDefFoundError}。
 */
public interface HookBackend {

    /**
     * 给一个方法/构造器挂上回调。
     *
     * @return 卸载句柄；调用其 {@code unhook()} 即撤销
     */
    XC_MethodHook.Unhook hookMember(Member member, XC_MethodHook callback);

    /** 框架自称的 API 版本号，仅用于日志 */
    String apiVersion();

    /** 框架名称，仅用于日志 */
    String frameworkName();

    /** 框架版本号，仅用于日志 */
    String frameworkVersion();

    /** 向框架日志输出一行（优先级 4 = INFO） */
    void log(int priority, String tag, String msg);
}
