package dev.devmatei.lockmediamover;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class LockMediaMover implements IXposedHookLoadPackage {
    static final String TAG = "LockMediaMover: ";
    static final String VERSION = "1.2";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String STACK_PKG = "com.android.systemui.statusbar.notification.stack.";

    static final String KEY_BOTTOM_DP = "lockmediamover_bottom_dp";
    static final int DEFAULT_BOTTOM_DP = 160;
    private static final long SETTINGS_REFRESH_MS = 1500L;

    private static final int STATUS_BAR_STATE_KEYGUARD = 1;
    private static final int MAX_ERRORS = 25;

    private static Class<?> sMediaContainerClass;
    private static volatile boolean sDisabled;
    private static int sErrorCount;
    private static boolean sLoggedFirstMove;

    private static int sBottomDp = DEFAULT_BOTTOM_DP;
    private static long sLastSettingsRead;

    static final boolean DEBUG = false;
    private static final java.util.HashMap<String, Integer> BAILS = new java.util.HashMap<>();
    private static boolean sLoggedFirstCall;

    private static void bail(String reason) {
        if (!DEBUG) {
            return;
        }
        String key = reason.contains(":") ? reason.substring(0, reason.indexOf(':')) : reason;
        Integer n = BAILS.get(key);
        n = n == null ? 1 : n + 1;
        BAILS.put(key, n);
        if (n <= 3) {
            XposedBridge.log(TAG + "skip #" + n + " -> " + reason);
        }
    }

    private static Object sLastMediaState;
    private static float sLastAppliedY = Float.NaN;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            return;
        }
        if (DEBUG) {
            XposedBridge.log(TAG + "v" + VERSION + " loaded, process=" + lpparam.processName
                    + " api=" + android.os.Build.VERSION.SDK_INT);
        }
        if (lpparam.processName != null && !SYSTEMUI.equals(lpparam.processName)) {
            return;
        }
        try {
            install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not install hooks, module is inactive");
            XposedBridge.log(t);
        }
    }

    private static Class<?> sAlgorithmClass;
    private static Class<?> sAmbientClass;
    private static Class<?> sStackLayoutClass;
    private static Class<?> sAnimatorClass;

    private static void install(ClassLoader cl) throws Throwable {
        sMediaContainerClass = cl.loadClass(STACK_PKG + "MediaContainerView");
        sAlgorithmClass = cl.loadClass(STACK_PKG + "StackScrollAlgorithm");
        sAmbientClass = tryLoad(cl, STACK_PKG + "AmbientState");
        sStackLayoutClass = tryLoad(cl, STACK_PKG + "NotificationStackScrollLayout");
        sAnimatorClass = tryLoad(cl, STACK_PKG + "StackStateAnimator");
        Class<?> expandableState = tryLoad(cl, STACK_PKG + "ExpandableViewState");
        Class<?> viewState = tryLoad(cl, STACK_PKG + "ViewState");

        if (DEBUG) {
            dumpMethods(sAlgorithmClass, null);
            dumpMethods(sStackLayoutClass, "(?i).*(apply|animat|children|viewstates|predraw).*");
            dumpMethods(sAnimatorClass, null);
            dumpMethods(expandableState, null);
            dumpMethods(viewState, "(?i).*(apply|animate|translation).*");
        }

        int primary = hookAll(sAlgorithmClass, "resetViewStates", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object algorithm = pick(param, sAlgorithmClass);
                Object ambient = sAmbientClass != null ? pick(param, sAmbientClass)
                        : (param.args != null && param.args.length > 0 ? param.args[0] : null);
                safeAdjust(algorithm, ambient, null);
            }
        });
        if (primary > 0) {
            XposedBridge.log(TAG + "hooked StackScrollAlgorithm.resetViewStates");
            return;
        }

        XposedBridge.log(TAG + "resetViewStates not found (inlined?), using fallback hooks");
        int fallback = 0;

        if (sStackLayoutClass != null) {
            fallback += hookAll(sStackLayoutClass, "applyCurrentState", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    adjustFromStackLayout(pick(param, sStackLayoutClass));
                }
            });
        }
        if (sAnimatorClass != null) {
            fallback += hookAll(sAnimatorClass, "startAnimationForEvents", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        adjustFromStackLayout(Refl.get(pick(param, sAnimatorClass), "mHostLayout"));
                    } catch (Throwable t) {
                        onError(t);
                    }
                }
            });
        }

        XC_MethodHook perView = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object media = pick(param, sMediaContainerClass);
                if (media != null) {
                    adjustFromStackLayout(((View) media).getParent());
                }
            }
        };
        int perViewCount = 0;
        Class<?> stateClass = expandableState != null ? expandableState : viewState;
        if (stateClass != null) {
            perViewCount += hookAll(stateClass, "applyToView", perView);
            perViewCount += hookAll(stateClass, "animateTo", perView);
        }
        if (perViewCount == 0 && viewState != null && stateClass != viewState) {
            perViewCount += hookAll(viewState, "applyToView", perView);
            perViewCount += hookAll(viewState, "animateTo", perView);
        }
        fallback += perViewCount;

        XposedBridge.log(TAG + (fallback > 0
                ? "installed " + fallback + " fallback hook(s), " + perViewCount + " per-view"
                : "no usable hook points on this build, module is inactive"));
    }

    private static Class<?> tryLoad(ClassLoader cl, String name) {
        try {
            return cl.loadClass(name);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "class not found: " + name);
            return null;
        }
    }

    private static int hookAll(Class<?> cls, String name, XC_MethodHook hook) {
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            String n = m.getName();
            boolean match = n.equals(name) || (n.startsWith(name + "$")
                    && n.substring(name.length() + 1).matches("\\d+"));
            if (!match || Modifier.isAbstract(m.getModifiers())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, hook);
                count++;
                if (DEBUG) {
                    XposedBridge.log(TAG + "hooked " + describe(m));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + "failed to hook " + describe(m) + ": " + t);
            }
        }
        return count;
    }

    private static Object pick(XC_MethodHook.MethodHookParam param, Class<?> cls) {
        if (cls == null || param == null) {
            return null;
        }
        if (cls.isInstance(param.thisObject)) {
            return param.thisObject;
        }
        if (param.args != null) {
            for (Object arg : param.args) {
                if (cls.isInstance(arg)) {
                    return arg;
                }
            }
        }
        return null;
    }

    private static String describe(Method m) {
        StringBuilder sb = new StringBuilder();
        if (Modifier.isStatic(m.getModifiers())) {
            sb.append("static ");
        }
        sb.append(m.getDeclaringClass().getSimpleName()).append('.').append(m.getName()).append('(');
        Class<?>[] params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    private static void dumpMethods(Class<?> cls, String nameRegex) {
        if (cls == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (nameRegex != null && !m.getName().matches(nameRegex)) {
                    continue;
                }
                if (n++ >= 80) {
                    sb.append(" ...");
                    break;
                }
                sb.append(n == 1 ? "" : " | ").append(describe(m));
            }
            XposedBridge.log(TAG + "methods " + cls.getSimpleName()
                    + (nameRegex != null ? " (filtered)" : "") + ": " + sb);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "could not list methods of " + cls.getName() + ": " + t);
        }
    }

    private static void adjustFromStackLayout(Object stackLayout) {
        if (stackLayout == null
                || (sStackLayoutClass != null && !sStackLayoutClass.isInstance(stackLayout))) {
            return;
        }
        try {
            safeAdjust(Refl.get(stackLayout, "mStackScrollAlgorithm"),
                    Refl.get(stackLayout, "mAmbientState"), stackLayout);
        } catch (Throwable t) {
            onError(t);
        }
    }

    private static void safeAdjust(Object algorithm, Object ambient, Object hostHint) {
        if (sDisabled) {
            return;
        }
        try {
            adjust(algorithm, ambient, hostHint);
        } catch (Throwable t) {
            onError(t);
        }
    }

    private static void onError(Throwable t) {
        sErrorCount++;
        if (sErrorCount <= 3) {
            XposedBridge.log(TAG + "layout tweak failed (#" + sErrorCount + ")");
            XposedBridge.log(t);
        }
        if (sErrorCount >= MAX_ERRORS && !sDisabled) {
            sDisabled = true;
            XposedBridge.log(TAG + "too many errors, standing down until next reboot");
        }
    }

    private static void adjust(Object algorithm, Object ambient, Object hostHint) throws Exception {
        if (ambient == null || (algorithm == null && hostHint == null)) {
            bail("null args: algorithm=" + algorithm + " ambient=" + ambient);
            return;
        }
        if (DEBUG && !sLoggedFirstCall) {
            sLoggedFirstCall = true;
            XposedBridge.log(TAG + "hook fired: algorithm="
                    + (algorithm == null ? "null" : algorithm.getClass().getName())
                    + " ambient=" + ambient.getClass().getName());
        }
        if (!isOnKeyguard(ambient)) {
            bail("not on keyguard");
            return;
        }

        float toShade = fractionToShade(ambient);
        float progress = 1f - clamp(toShade);
        if (progress <= 0f) {
            bail("shade expanded: fractionToShade=" + toShade);
            return;
        }

        Object hostObj = Refl.get(algorithm, "mHostView");
        if (!(hostObj instanceof ViewGroup)) {
            hostObj = hostHint;
        }
        if (!(hostObj instanceof ViewGroup)) {
            bail("no host view: mHostView=" + hostObj);
            return;
        }
        ViewGroup host = (ViewGroup) hostObj;
        int hostHeight = host.getHeight();
        if (hostHeight <= 0) {
            bail("host height 0");
            return;
        }

        int bottomDp = readBottomDp(host.getContext());
        if (bottomDp < 0) {
            bail("disabled via settings: " + bottomDp);
            return;
        }

        Object shelf = callOrGet(ambient, "getShelf", "mShelf");
        if (shelf != null) {
            Object shelfState = viewStateOf(shelf);
            if (shelfState != null && !Refl.getBool(shelfState, "hidden", true)) {
                bail("shelf visible (notification overflow)");
                return;
            }
        }

        Object algoState = Refl.get(algorithm, "mTempAlgorithmState");
        Object listObj = Refl.get(algoState, "visibleChildren");
        if (!(listObj instanceof List)) {
            java.util.ArrayList<View> visible = new java.util.ArrayList<>();
            for (int i = 0; i < host.getChildCount(); i++) {
                View c = host.getChildAt(i);
                if (c != null && c != shelf && c.getVisibility() != View.GONE) {
                    visible.add(c);
                }
            }
            listObj = visible;
        }
        if (!(listObj instanceof List)) {
            bail("no visibleChildren: state=" + algoState + " list=" + listObj);
            return;
        }
        List<?> children = (List<?>) listObj;
        int count = children.size();

        int mediaIndex = -1;
        for (int i = 0; i < count; i++) {
            if (sMediaContainerClass.isInstance(children.get(i))) {
                mediaIndex = i;
                break;
            }
        }
        if (mediaIndex < 0) {
            if (DEBUG) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    Object c = children.get(i);
                    sb.append(i == 0 ? "" : ", ").append(c == null ? "null" : c.getClass().getSimpleName());
                }
                bail("no player in stack: children=[" + sb + "] hostChildren=" + describeHost(host));
            }
            return;
        }

        View media = (View) children.get(mediaIndex);
        if (media.getVisibility() != View.VISIBLE) {
            bail("player view not visible: " + media.getVisibility());
            return;
        }
        Object mediaState = viewStateOf(media);
        if (mediaState == null || !isShown(mediaState)) {
            bail("player state hidden: " + (mediaState == null ? "null" : "gone="
                    + Refl.getBool(mediaState, "gone", false) + " hidden="
                    + Refl.getBool(mediaState, "hidden", false) + " height="
                    + Refl.getInt(mediaState, "height", -1)));
            return;
        }
        int mediaHeight = Refl.getInt(mediaState, "height", 0);
        float mediaTop = Refl.getY(mediaState);

        if (mediaState == sLastMediaState && mediaTop == sLastAppliedY) {
            return;
        }

        float shift = 0f;
        for (int i = mediaIndex + 1; i < count; i++) {
            Object child = children.get(i);
            if (!isStackRow(child) || isPinned(child)) {
                continue;
            }
            Object s = viewStateOf(child);
            if (s == null || !isShown(s)) {
                continue;
            }
            shift = Refl.getY(s) - mediaTop;
            break;
        }
        if (shift < 0f || shift > hostHeight) {
            bail("weird layout: shift=" + shift + " mediaTop=" + mediaTop + " host=" + hostHeight);
            return;
        }

        float density = host.getResources().getDisplayMetrics().density;
        float gap = shift > mediaHeight ? shift - mediaHeight : 8f * density;

        float rowsBottom = Float.NaN;
        if (shift > 0f) {
            for (int i = mediaIndex + 1; i < count; i++) {
                Object child = children.get(i);
                if (!isStackRow(child) || isPinned(child)) {
                    continue;
                }
                Object s = viewStateOf(child);
                if (s == null) {
                    continue;
                }
                float y = Refl.getY(s);
                Refl.setY(s, y - shift * progress);
                if (isShown(s)) {
                    float bottom = (y - shift) + Refl.getInt(s, "height", 0);
                    if (Float.isNaN(rowsBottom) || bottom > rowsBottom) {
                        rowsBottom = bottom;
                    }
                }
            }
        }

        float desiredTop = hostHeight - bottomDp * density - mediaHeight;
        float minTop = Float.isNaN(rowsBottom) ? mediaTop : rowsBottom + gap;
        float newTop = Math.max(desiredTop, minTop);
        float appliedY = mediaTop + (newTop - mediaTop) * progress;
        if (appliedY == mediaTop) {
            bail("already in place: mediaTop=" + mediaTop + " desiredTop=" + desiredTop
                    + " host=" + hostHeight + " mediaH=" + mediaHeight + " density=" + density);
            sLastMediaState = null;
            sLastAppliedY = Float.NaN;
            return;
        }
        Refl.setY(mediaState, appliedY);

        sLastMediaState = mediaState;
        sLastAppliedY = Refl.getY(mediaState);

        if (!sLoggedFirstMove) {
            sLoggedFirstMove = true;
            XposedBridge.log(TAG + "moved lockscreen player " + Math.round(mediaTop) + "px -> "
                    + Math.round(newTop) + "px (stack height " + hostHeight + "px, bottom gap "
                    + bottomDp + "dp)");
        }
    }

    private static boolean isOnKeyguard(Object ambient) throws Exception {
        Method m = Refl.method(ambient.getClass(), "isOnKeyguard");
        if (m != null) {
            Object v = m.invoke(ambient);
            return v instanceof Boolean && (Boolean) v;
        }
        return Refl.getInt(ambient, "mStatusBarState", -1) == STATUS_BAR_STATE_KEYGUARD;
    }

    private static float fractionToShade(Object ambient) throws Exception {
        Object v = callOrGet(ambient, "getFractionToShade", "mFractionToShade");
        return v instanceof Number ? ((Number) v).floatValue() : 0f;
    }

    private static Object viewStateOf(Object view) throws Exception {
        return callOrGet(view, "getViewState", "mViewState");
    }

    private static Object callOrGet(Object target, String getter, String field) throws Exception {
        if (target == null) {
            return null;
        }
        Method m = Refl.method(target.getClass(), getter);
        if (m != null) {
            return m.invoke(target);
        }
        return Refl.get(target, field);
    }

    private static boolean isShown(Object viewState) throws Exception {
        return !Refl.getBool(viewState, "gone", false)
                && !Refl.getBool(viewState, "hidden", false)
                && Refl.getInt(viewState, "height", 0) > 0;
    }

    private static boolean isPinned(Object view) {
        try {
            return Refl.callBool(view, "isPinned", false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isStackRow(Object view) {
        if (view == null || sMediaContainerClass.isInstance(view)) {
            return false;
        }
        String name = view.getClass().getSimpleName();
        return !name.equals("FooterView")
                && !name.equals("EmptyShadeView")
                && !name.equals("EmptyShadeIconView");
    }

    private static String describeHost(ViewGroup host) {
        try {
            int media = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < host.getChildCount(); i++) {
                View c = host.getChildAt(i);
                if (sMediaContainerClass.isInstance(c)) {
                    media++;
                    sb.append(" mediaVis=").append(c.getVisibility()).append(" h=").append(c.getHeight());
                }
            }
            return host.getChildCount() + " views, " + media + " MediaContainerView" + sb;
        } catch (Throwable t) {
            return "?" + t;
        }
    }

    private static int readBottomDp(Context context) {
        long now = SystemClock.uptimeMillis();
        if (sLastSettingsRead == 0L || now - sLastSettingsRead > SETTINGS_REFRESH_MS) {
            sLastSettingsRead = now;
            try {
                sBottomDp = Settings.Secure.getInt(context.getContentResolver(),
                        KEY_BOTTOM_DP, DEFAULT_BOTTOM_DP);
            } catch (Throwable t) {
                sBottomDp = DEFAULT_BOTTOM_DP;
            }
        }
        return sBottomDp;
    }

    private static float clamp(float v) {
        if (Float.isNaN(v) || v < 0f) {
            return 0f;
        }
        return v > 1f ? 1f : v;
    }
}
