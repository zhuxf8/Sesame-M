package io.github.aw1y2z.sesame.util;

import android.util.Log;

import io.github.aw1y2z.sesame.util.compat.XC_MethodHook;
import io.github.aw1y2z.sesame.util.hook.HookBackend;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 反射工具 + Hook 挂载的统一入口（框架无关）。
 *
 * <p>hook 的"谁来挂"由 {@link HookBackend} 决定：libxposed API 101/102 走
 * {@code LibxposedHookBackend}，经典 Xposed API 82/89 走 {@code LegacyHookBackend}。
 * 业务代码只认本类，换框架不需要改一行 hook 逻辑。
 */
public class XHelpers {

    private static final String TAG = "XHelpers";
    private static volatile HookBackend sBackend;
    /** 标记"当前线程正在调用原方法"，避免 hook 里 callOriginal 又回到本 hook 造成递归 */
    public static final ThreadLocal<Boolean> IN_ORIGINAL_CALL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 由入口类在模块加载时注入 Hook 后端。
     *
     * <p>刻意不再直接吃 {@code XposedModule}：同一个模块要能跑在 libxposed 101/102 与经典 API 82/89
     * 两套框架上，后端由各自的入口类决定（见 {@code hook/LibxposedEntry} 与 {@code hook/LegacyXposedEntry}）。
     */
    public static void init(HookBackend backend) {
        sBackend = backend;
    }

    /** 当前后端；未初始化返回 null（用于"模块是否已在框架内加载"的探测） */
    public static HookBackend backend() {
        return sBackend;
    }

    private static HookBackend requireBackend() {
        HookBackend backend = sBackend;
        if (backend == null) {
            throw new IllegalStateException("XHelpers 未初始化，入口类需在模块加载时调用 XHelpers.init(backend)");
        }
        return backend;
    }

    // ----------------------------------------------------------------
    // 类 / 方法 / 字段查找
    // ----------------------------------------------------------------

    public static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return cl.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + name, e);
        }
    }

    public static Class<?> findClassIfExists(String name, ClassLoader cl) {
        try {
            return cl.loadClass(name);
        } catch (Throwable t) {
            // 探测型调用：找不到类属正常情况（用于兼容不同版本/机型），静默返回 null，不打日志
            return null;
        }
    }

    public static Method findMethodExact(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method not found: " + clazz.getName() + "#" + name, e);
        }
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredConstructor(paramTypes);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Constructor not found: " + clazz.getName(), e);
        }
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        Class<?> c = clazz;
        while (c != null) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + clazz.getName() + "#" + fieldName);
    }

    // ----------------------------------------------------------------
    // 调用 / 字段读写
    // ----------------------------------------------------------------

    public static Object callMethod(Object obj, String name, Object... args) {
        Method m = findMethodBestMatch(obj.getClass(), name, args, false);
        try {
            m.setAccessible(true);
            return m.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException("callMethod failed: " + name, e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String name, Object... args) {
        Method m = findMethodBestMatch(clazz, name, args, true);
        try {
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException("callStaticMethod failed: " + name, e);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            throw new RuntimeException("getObjectField failed: " + fieldName, e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException("setObjectField failed: " + fieldName, e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field f = findField(clazz, fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new RuntimeException("getStaticObjectField failed: " + fieldName, e);
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field f = findField(clazz, fieldName);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException("setStaticObjectField failed: " + fieldName, e);
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        Class<?>[] argTypes = getArgTypes(args);
        Constructor<?> c = findConstructorBestMatch(clazz, argTypes);
        try {
            c.setAccessible(true);
            return c.newInstance(args);
        } catch (Exception e) {
            throw new RuntimeException("newInstance failed: " + clazz.getName(), e);
        }
    }

    // ----------------------------------------------------------------
    // Hook 桥接
    // ----------------------------------------------------------------

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String name, Object... argsAndCallback) {
        int last = argsAndCallback.length - 1;
        XC_MethodHook callback = (XC_MethodHook) argsAndCallback[last];
        Class<?>[] paramTypes = new Class<?>[last];
        for (int i = 0; i < last; i++) {
            paramTypes[i] = (Class<?>) argsAndCallback[i];
        }
        Method m = findMethodExact(clazz, name, paramTypes);
        return hookMember(m, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader cl, String name, Object... argsAndCallback) {
        Class<?> clazz = findClass(className, cl);
        return findAndHookMethod(clazz, name, argsAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... argsAndCallback) {
        int last = argsAndCallback.length - 1;
        XC_MethodHook callback = (XC_MethodHook) argsAndCallback[last];
        Class<?>[] paramTypes = new Class<?>[last];
        for (int i = 0; i < last; i++) {
            paramTypes[i] = (Class<?>) argsAndCallback[i];
        }
        Constructor<?> c = findConstructorExact(clazz, paramTypes);
        return hookMember(c, callback);
    }

    public static XC_MethodHook.Unhook hookMember(java.lang.reflect.Member member, XC_MethodHook callback) {
        return requireBackend().hookMember(member, callback);
    }

    // ----------------------------------------------------------------
    // 内部辅助
    // ----------------------------------------------------------------

    private static Class<?>[] getArgTypes(Object... args) {
        if (args == null) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = (args[i] != null) ? args[i].getClass() : null;
        }
        return types;
    }

    private static Method findMethodBestMatch(Class<?> clazz, String name, Object[] args, boolean staticOnly) {
        Class<?>[] argTypes = getArgTypes(args);
        Method m = searchMethods(clazz, name, argTypes, staticOnly, true);
        if (m != null) {
            return m;
        }
        m = searchMethods(clazz, name, argTypes, staticOnly, false);
        if (m != null) {
            return m;
        }
        throw new RuntimeException("No method " + name + " matching args in " + clazz.getName());
    }

    private static Method searchMethods(Class<?> clazz, String name, Class<?>[] argTypes, boolean staticOnly, boolean declaredOnly) {
        Method[] methods = declaredOnly ? clazz.getDeclaredMethods() : clazz.getMethods();
        for (Method m : methods) {
            if (!m.getName().equals(name)) {
                continue;
            }
            if (staticOnly && !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != argTypes.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (argTypes[i] != null && !isAssignable(pts[i], argTypes[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return m;
            }
        }
        return null;
    }

    private static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>[] argTypes) {
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            Class<?>[] pts = c.getParameterTypes();
            if (pts.length != argTypes.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < pts.length; i++) {
                if (argTypes[i] != null && !isAssignable(pts[i], argTypes[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return c;
            }
        }
        throw new RuntimeException("No constructor matching args in " + clazz.getName());
    }

    private static boolean isAssignable(Class<?> paramType, Class<?> argType) {
        if (paramType.isPrimitive()) {
            Class<?> boxed = PRIMITIVE_BOX.get(paramType);
            return boxed != null && boxed.equals(argType);
        }
        return paramType.isAssignableFrom(argType);
    }

    private static final Map<Class<?>, Class<?>> PRIMITIVE_BOX = new HashMap<>();

    static {
        PRIMITIVE_BOX.put(int.class, Integer.class);
        PRIMITIVE_BOX.put(long.class, Long.class);
        PRIMITIVE_BOX.put(boolean.class, Boolean.class);
        PRIMITIVE_BOX.put(double.class, Double.class);
        PRIMITIVE_BOX.put(float.class, Float.class);
        PRIMITIVE_BOX.put(short.class, Short.class);
        PRIMITIVE_BOX.put(byte.class, Byte.class);
        PRIMITIVE_BOX.put(char.class, Character.class);
    }
}
