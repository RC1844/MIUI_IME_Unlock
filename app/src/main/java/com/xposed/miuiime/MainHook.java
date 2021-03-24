package com.xposed.miuiime;

import android.content.Context;
import android.view.inputmethod.InputMethodManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    boolean isMIUI12;
    boolean isMIUI125;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        findAndHookMethod("android.inputmethodservice.InputMethodService", lpparam.classLoader, "initViews", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                isMIUI();
                if (isMIUI12 || isMIUI125) {
                    final boolean isNonCustom = !miuiImeList.contains(lpparam.packageName);
                    if (isNonCustom) {
                        XposedBridge.log("Hook ServiceInjector: " + lpparam.packageName);
                        Class<?> clazz = findClass("android.inputmethodservice.InputMethodServiceInjector", lpparam.classLoader);
                        if (isMIUI12)
                            findAndHookMethod(clazz, "checkMiuiBottomSupport", setsIsImeSupport(clazz));
                        else
                            findAndHookMethod(clazz, "isCanLoadPlugin", Context.class, setsIsImeSupport(clazz));
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
                    if (isMIUI12) {
                        findAndHookMethod("android.inputmethodservice.InputMethodServiceInjector$MiuiSwitchInputMethodListener", lpparam.classLoader, "deleteNotSupportIme", new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                return null;
                            }
                        });
                    } else {
                        InputMethodManager mImm = (InputMethodManager) getObjectField(param.thisObject, "mImm");
                        findAndHookMethod("android.inputmethodservice.InputMethodModuleManager", lpparam.classLoader, "loadDex", ClassLoader.class, String.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                super.afterHookedMethod(param);
                                XposedBridge.log("Hook MiuiBottomView: " + lpparam.packageName);
                                final Class<?> clazz = findClass("com.miui.inputmethod.InputMethodBottomManager", (ClassLoader) param.args[0]);
                                if (isNonCustom) {
                                    findAndHookMethod(clazz, "checkMiuiBottomSupport", setsIsImeSupport(clazz));
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
                }
            }

            private XC_MethodHook setsIsImeSupport(Class<?> clazz) {
                return new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
                        super.beforeHookedMethod(param);
                    }
                };
            }

            public void isMIUI() {
                String line = "V0";
                BufferedReader input = null;
                try {
                    Process p = Runtime.getRuntime().exec("getprop ro.miui.ui.version.name");
                    input = new BufferedReader(new InputStreamReader(p.getInputStream()), 1024);
                    line = input.readLine();
                    input.close();

                } catch (IOException ex) {
                    XposedBridge.log("Unable to read sysprop ro.miui.ui.version.name" + ex);
                } finally {
                    if (input != null) {
                        try {
                            input.close();
                        } catch (IOException e) {
                            XposedBridge.log("Exception while closing InputStream" + e);
                        }
                    }
                }
                switch (line) {
                    case "V125":
                        isMIUI12 = false;
                        isMIUI125 = true;
                        break;
                    case "V12":
                        isMIUI12 = true;
                        isMIUI125 = false;
                        break;
                    default:
                        isMIUI12 = false;
                        isMIUI125 = false;
                }
            }
        });
    }
}