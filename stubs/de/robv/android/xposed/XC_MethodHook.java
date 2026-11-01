package de.robv.android.xposed;

import java.lang.reflect.Member;

public abstract class XC_MethodHook {
    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
    }

    public class Unhook {
        public void unhook() {
        }
    }
}
