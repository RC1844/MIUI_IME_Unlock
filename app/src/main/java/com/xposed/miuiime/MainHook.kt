package com.xposed.miuiime

import android.annotation.TargetApi
import android.os.Build
import android.view.inputmethod.InputMethodManager
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {
    private var isA10 = false
    private var isA11 = false
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        //检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        checkVersion()
        //检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            val clazz = XposedHelpers.findClass(
                "android.inputmethodservice.InputMethodServiceInjector",
                lpparam.classLoader
            )
            hookSIsImeSupport(clazz)
            XposedBridge.log("Hooked ServiceInjector: " + lpparam.packageName)
            if (isA10) {
                hookIsXiaoAiEnable(clazz)
                XposedBridge.log("Hooked IsXiaoAiEnable: " + lpparam.packageName)
            } else  //将导航栏颜色赋值给输入法优化的底图
                XposedHelpers.findAndHookMethod("com.android.internal.policy.PhoneWindow",
                    lpparam.classLoader, "setNavigationBarColor",
                    Int::class.javaPrimitiveType, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
//                                      0xff747474, 0x66747474
                            val color = -0x1 - param.args[0] as Int
                            XposedHelpers.callStaticMethod(
                                clazz, "customizeBottomViewColor",
                                true, param.args[0], color or -0x1000000, color or 0x66000000
                            )
                        }
                    })
            XposedBridge.log("Hooked customizeBottomViewColor: " + lpparam.packageName)
        }
        if (isA10) {
            //针对A10的修复切换输入法列表
            XposedHelpers.findAndHookMethod(
                "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
                lpparam.classLoader, "deleteNotSupportIme",
                XC_MethodReplacement.returnConstant(null)
            )
            XposedBridge.log("Hooked deleteNotSupportIme: " + lpparam.packageName)
        } else {
            //获取常用语的ClassLoader
            XposedHelpers.findAndHookMethod("android.inputmethodservice.InputMethodModuleManager",
                lpparam.classLoader, "loadDex",
                ClassLoader::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val clazz = XposedHelpers.findClass(
                            "com.miui.inputmethod.InputMethodBottomManager",
                            param.args[0] as ClassLoader
                        )
                        if (isNonCustomize) {
                            hookSIsImeSupport(clazz)
                            hookIsXiaoAiEnable(clazz)
                            XposedBridge.log("Hooked MiuiBottomView: " + lpparam.packageName)
                        }
                        //针对A11的修复切换输入法列表
                        XposedHelpers.findAndHookMethod(clazz, "getSupportIme",
                            object : XC_MethodReplacement() {
                                @TargetApi(Build.VERSION_CODES.CUPCAKE)
                                override fun replaceHookedMethod(param: MethodHookParam): Any {
                                    return (XposedHelpers.getObjectField(
                                        XposedHelpers.getStaticObjectField(
                                            clazz,
                                            "sBottomViewHelper"
                                        ),
                                        "mImm"
                                    ) as InputMethodManager).enabledInputMethodList
                                }
                            })
                        XposedBridge.log("Hooked getSupportIme Method: " + lpparam.packageName)
                    }
                })
        }
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        try {
            XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1)
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                clazz, "isXiaoAiEnable",
                XC_MethodReplacement.returnConstant(false)
            )
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    /**
     * 检查Android版本
     */
    private fun checkVersion() {
        when (Build.VERSION.SDK_INT) {
            30 -> {
                isA10 = false
                isA11 = true
            }
            29, 28 -> {
                isA10 = true
                isA11 = false
            }
            else -> {
                isA10 = false
                isA11 = false
            }
        }
    }

    companion object {
        val miuiImeList: List<String> = listOf(
            "com.iflytek.inputmethod.miui",
            "com.sohu.inputmethod.sogou.xiaomi", "com.baidu.input_mi", "com.miui.catcherpatch"
        )
    }
}