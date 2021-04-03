package com.xposed.miuiime;

import android.os.Build;
import android.view.inputmethod.InputMethodManager;

import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;

public class MainHook implements IXposedHookLoadPackage {
    final static List<String> miuiImeList = Arrays.asList("com.iflytek.inputmethod.miui",
            "com.sohu.inputmethod.sogou.xiaomi", "com.baidu.input_mi", "com.miui.catcherpatch");
    boolean isA10;
    boolean isA11;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        //检查是否支持全面屏优化
        if (!PropertyUtils.get("ro.miui.support_miui_ime_bottom", "0").equals("1"))
            return;
        checkVersion();
        //检查是否为小米定制输入法
        final boolean isNonCustomize = !miuiImeList.contains(lpparam.packageName);
        if (isNonCustomize) {
            Class<?> clazz = findClass("android.inputmethodservice.InputMethodServiceInjector",
                    lpparam.classLoader);
            hookSIsImeSupport(clazz);
            XposedBridge.log("Hooked ServiceInjector: " + lpparam.packageName);
            if (isA10) {
                hookIsXiaoAiEnable(clazz);
                XposedBridge.log("Hooked IsXiaoAiEnable: " + lpparam.packageName);
            } else
                //将导航栏颜色赋值给输入法优化的底图
                findAndHookMethod("com.android.internal.policy.PhoneWindow",
                        lpparam.classLoader, "setNavigationBarColor",
                        int.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
//                                      0xff747474, 0x66747474
                                int color = 0xFFFFFFFF - (int) param.args[0];
                                XposedHelpers.callStaticMethod(clazz, "customizeBottomViewColor",
                                        true, param.args[0], color | 0xFF000000, color | 0x66000000);
                            }
                        });
            XposedBridge.log("Hooked customizeBottomViewColor: " + lpparam.packageName);
        }
        if (isA10) {
            //针对A10的修复切换输入法列表
            findAndHookMethod("android.inputmethodservice.InputMethodServiceInjector$MiuiSwitchInputMethodListener",
                    lpparam.classLoader, "deleteNotSupportIme",
                    XC_MethodReplacement.returnConstant(null));
            XposedBridge.log("Hooked deleteNotSupportIme: " + lpparam.packageName);
        } else {
            //获取常用语的ClassLoader
            findAndHookMethod("android.inputmethodservice.InputMethodModuleManager",
                    lpparam.classLoader, "loadDex",
                    ClassLoader.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            final Class<?> clazz = findClass("com.miui.inputmethod.InputMethodBottomManager",
                                    (ClassLoader) param.args[0]);
                            if (isNonCustomize) {
                                hookSIsImeSupport(clazz);
                                hookIsXiaoAiEnable(clazz);
                                XposedBridge.log("Hooked MiuiBottomView: " + lpparam.packageName);
                            }
                            //针对A11的修复切换输入法列表
                            findAndHookMethod(clazz, "getSupportIme",
                                    new XC_MethodReplacement() {
                                        @Override
                                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                            return ((InputMethodManager) getObjectField(
                                                    getStaticObjectField(clazz, "sBottomViewHelper"),
                                                    "mImm")).getEnabledInputMethodList();
                                        }
                                    });
                            XposedBridge.log("Hooked getSupportIme Method: " + lpparam.packageName);
                        }
                    });
        }
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private void hookSIsImeSupport(Class<?> clazz) {
        XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1);
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private void hookIsXiaoAiEnable(Class<?> clazz) {
        findAndHookMethod(clazz, "isXiaoAiEnable",
                XC_MethodReplacement.returnConstant(false));
    }

    /**
     * 检查Android版本
     */
    public void checkVersion() {
        switch (Build.VERSION.SDK_INT) {
            case 30:
                isA10 = false;
                isA11 = true;
                break;
            case 29:
            case 28:
                isA10 = true;
                isA11 = false;
                break;
            default:
                isA10 = false;
                isA11 = false;
        }
    }
}