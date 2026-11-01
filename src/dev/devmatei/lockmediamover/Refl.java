package dev.devmatei.lockmediamover;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class Refl {
    private static final Object MISSING = new Object();
    private static final Map<String, Object> METHODS = new HashMap<>();
    private static final Map<String, Object> FIELDS = new HashMap<>();

    private Refl() {
    }

    static Method method(Class<?> cls, String name, Class<?>... params) {
        String key = cls.getName() + "#" + name + "/" + params.length;
        synchronized (METHODS) {
            Object cached = METHODS.get(key);
            if (cached != null) {
                return cached == MISSING ? null : (Method) cached;
            }
            Method found = null;
            for (Class<?> c = cls; c != null && found == null; c = c.getSuperclass()) {
                try {
                    found = c.getDeclaredMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (found != null) {
                try {
                    found.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            METHODS.put(key, found == null ? MISSING : found);
            return found;
        }
    }

    static Field field(Class<?> cls, String name) {
        String key = cls.getName() + "." + name;
        synchronized (FIELDS) {
            Object cached = FIELDS.get(key);
            if (cached != null) {
                return cached == MISSING ? null : (Field) cached;
            }
            Field found = null;
            for (Class<?> c = cls; c != null && found == null; c = c.getSuperclass()) {
                try {
                    found = c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (found != null) {
                try {
                    found.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            FIELDS.put(key, found == null ? MISSING : found);
            return found;
        }
    }

    static Object call(Object target, String name) throws Exception {
        if (target == null) {
            return null;
        }
        Method m = method(target.getClass(), name);
        return m == null ? null : m.invoke(target);
    }

    static boolean callBool(Object target, String name, boolean def) throws Exception {
        Object v = call(target, name);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    static float callFloat(Object target, String name, float def) throws Exception {
        Object v = call(target, name);
        return v instanceof Number ? ((Number) v).floatValue() : def;
    }

    static Object get(Object target, String name) throws Exception {
        if (target == null) {
            return null;
        }
        Field f = field(target.getClass(), name);
        return f == null ? null : f.get(target);
    }

    static boolean getBool(Object target, String name, boolean def) throws Exception {
        Object v = get(target, name);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    static int getInt(Object target, String name, int def) throws Exception {
        Object v = get(target, name);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    static float getY(Object viewState) throws Exception {
        Method m = method(viewState.getClass(), "getYTranslation");
        if (m != null) {
            return ((Number) m.invoke(viewState)).floatValue();
        }
        Field f = yField(viewState.getClass());
        if (f != null) {
            return f.getFloat(viewState);
        }
        throw new NoSuchFieldException("yTranslation");
    }

    static void setY(Object viewState, float y) throws Exception {
        if (Float.isNaN(y) || Float.isInfinite(y)) {
            return;
        }
        Method m = method(viewState.getClass(), "setYTranslation", float.class);
        if (m != null) {
            m.invoke(viewState, y);
            return;
        }
        Field f = yField(viewState.getClass());
        if (f != null) {
            f.setFloat(viewState, y);
            return;
        }
        throw new NoSuchFieldException("yTranslation");
    }

    private static Field yField(Class<?> cls) {
        Field f = field(cls, "mYTranslation");
        return f != null ? f : field(cls, "yTranslation");
    }
}
