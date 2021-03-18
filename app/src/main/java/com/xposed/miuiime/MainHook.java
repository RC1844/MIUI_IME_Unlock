package com.xposed.miuiime;

import android.content.Context;
import android.view.inputmethod.InputMethodManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        findAndHookMethod("android.inputmethodservice.InputMethodService", lpparam.classLoader, "initViews", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("Hook ServiceInjector: " + lpparam.packageName);
                InputMethodManager mImm = (InputMethodManager) getObjectField(param.thisObject, "mImm");
                Class<?> clazz = findClass("android.inputmethodservice.InputMethodServiceInjector", lpparam.classLoader);
                findAndHookMethod(clazz, "isImeSupport", Context.class, new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        XposedBridge.log("Hooked ServiceInjector: " + lpparam.packageName);
                        XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
                        return true;
                    }
                });
                findAndHookMethod("android.inputmethodservice.InputMethodModuleManager", lpparam.classLoader, "loadDex", ClassLoader.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        XposedBridge.log("Hook MiuiBottomView: " + lpparam.packageName);
                        final Class<?> clazz = findClass("com.miui.inputmethod.InputMethodBottomManager", (ClassLoader) param.args[0]);
                        findAndHookMethod(clazz, "isImeSupport", Context.class, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("Hooked MiuiBottomView 1: " + lpparam.packageName);
                                XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
                                return true;
                            }
                        });
                        findAndHookMethod(clazz, "getSupportIme", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                XposedBridge.log("Hook MiuiBottomView 2: " + lpparam.packageName);
                                if (mImm != null) {
                                    param.setResult(mImm.getEnabledInputMethodList());
                                    XposedBridge.log("Hooked MiuiBottomView 2: " + lpparam.packageName);
                                }else {
                                    XposedBridge.log("Hook MiuiBottomView : List is Null");
                                    super.beforeHookedMethod(param);
                                }
                            }
                        });
                    }
                });
            }
        });
    }
}