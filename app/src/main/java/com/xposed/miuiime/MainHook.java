package com.xposed.miuiime;

import android.content.Context;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

public class MainHook implements IXposedHookLoadPackage {
    final static List<String> miuiImeList = Arrays.asList("com.iflytek.inputmethod.miui", "com.sohu.inputmethod.sogou.xiaomi", "com.baidu.input_mi", "com.miui.catcherpatch");

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        findAndHookMethod("android.inputmethodservice.InputMethodService", lpparam.classLoader, "initViews", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                InputMethodManager mImm = (InputMethodManager) getObjectField(param.thisObject, "mImm");
                final boolean contains = !miuiImeList.contains(lpparam.packageName);
                Class<?> clazz = findClass("android.inputmethodservice.InputMethodServiceInjector", lpparam.classLoader);
                if (contains) {
                    XposedBridge.log("Hook ServiceInjector: " + lpparam.packageName);

                    findAndHookMethod(clazz, "isImeSupport", Context.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // XposedBridge.log("Hooked isImeSupport Method 1: " + lpparam.packageName);
                            XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
                            return true;
                        }
                    });
                    findAndHookMethod("com.android.internal.policy.PhoneWindow", lpparam.classLoader, "setNavigationBarColor", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            super.afterHookedMethod(param);
//                            0xFF141414,0xFFA1A1A1,0x66A1A1A1
//                            0xFFE7E8EB,0x66000000,0x80000000
                            callStaticMethod(clazz, "customizeBottomViewColor", true, param.args[0], 0xff747474, 0x66747474);
                        }
                    });
                }
                // else {
                //     Class<?> clazz = findClass("android.inputmethodservice.InputMethodServiceInjector",
                //             lpparam.classLoader);
                //     findAndHookMethod(clazz, "customizeBottomViewColor", boolean.class, int.class, int.class, int.class,
                //             new XC_MethodHook() {
                //                 @Override
                //                 protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                //                     super.afterHookedMethod(param);
                //                     XposedBridge.log("Hooked customizeBottomViewColor Method: " + lpparam.packageName);
                //                     for (int i = 1; i < param.args.length; i++) {
                //                         XposedBridge.log("args: " + param.args[i]);
                //                     }
                //                 }
                //             });
                // }
                findAndHookMethod("android.inputmethodservice.InputMethodModuleManager", lpparam.classLoader, "loadDex", ClassLoader.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        XposedBridge.log("Hook MiuiBottomView: " + lpparam.packageName);
                        final Class<?> clazz = findClass("com.miui.inputmethod.InputMethodBottomManager", (ClassLoader) param.args[0]);
                        if (contains) {
                            findAndHookMethod(clazz, "isImeSupport", Context.class, new XC_MethodReplacement() {
                                @Override
                                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                    // XposedBridge.log("Hooked isImeSupport Method 2: " + lpparam.packageName);
                                    XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
                                    return true;
                                }
                            });
                        }
                        if (mImm != null) {
                            XposedBridge.log("Hook getSupportIme Method: " + lpparam.packageName);
                            findAndHookMethod(clazz, "getSupportIme", new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    param.setResult(mImm.getEnabledInputMethodList());
                                    // XposedBridge.log("Hooked getSupportIme Method: " + lpparam.packageName);
                                }
                            });
                        }
                    }
                });
            }
        });
    }
}