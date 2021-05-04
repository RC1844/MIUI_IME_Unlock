package com.xposed.miuiime

import android.view.inputmethod.InputMethodManager
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        //检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        //检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            findClass(
                "android.inputmethodservice.InputMethodServiceInjector",
                lpparam.classLoader
            )?.let {
                hookSIsImeSupport(it)
                hookIsXiaoAiEnable(it)

                //将导航栏颜色赋值给输入法优化的底图
                findAndHookMethod("com.android.internal.policy.PhoneWindow",
                    lpparam.classLoader, "setNavigationBarColor",
                    Int::class.javaPrimitiveType, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val color = -0x1 - param.args[0] as Int
                            XposedHelpers.callStaticMethod(
                                it, "customizeBottomViewColor",
                                true, param.args[0], color or -0x1000000, color or 0x66000000
                            )
                        }
                    })
            }
        }
        //针对A10的修复切换输入法列表
        findAndHookMethod(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader, "deleteNotSupportIme",
            XC_MethodReplacement.returnConstant(null)
        )

        //获取常用语的ClassLoader
        findAndHookMethod("android.inputmethodservice.InputMethodModuleManager",
            lpparam.classLoader, "loadDex",
            ClassLoader::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    findClass(
                        "com.miui.inputmethod.InputMethodBottomManager",
                        param.args[0] as ClassLoader
                    )?.let {
                        if (isNonCustomize) {
                            hookSIsImeSupport(it)
                            hookIsXiaoAiEnable(it)
                        }

                        //针对A11的修复切换输入法列表
                        findAndHookMethod(
                            it, "getSupportIme",
                            object : XC_MethodReplacement() {
                                override fun replaceHookedMethod(param: MethodHookParam): Any {
                                    return (XposedHelpers.getObjectField(
                                        XposedHelpers.getStaticObjectField(
                                            it, "sBottomViewHelper"
                                        ),
                                        "mImm"
                                    ) as InputMethodManager).enabledInputMethodList
                                }
                            })
                    }
                }
            })
        XposedBridge.log("Hook MIUI IME Success")
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1)
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        findAndHookMethod(
            clazz, "isXiaoAiEnable",
            XC_MethodReplacement.returnConstant(false)
        )
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook? {
        return try {
            XposedBridge.log("Hook method $methodName")
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
        } catch (e: IllegalArgumentException) {
            XposedBridge.log("Hook method $methodName failed")
            XposedBridge.log(e)
            null
        }
    }

    private fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ): XC_MethodHook.Unhook? {
        return try {
            XposedBridge.log("Hook method $methodName")
            XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass(className, classLoader),
                methodName,
                *parameterTypesAndCallback
            )
        } catch (e: IllegalArgumentException) {
            XposedBridge.log("Hook method $methodName failed")
            XposedBridge.log(e)
            null
        }
    }

    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            XposedBridge.log("Find class $className")
            XposedHelpers.findClass(className, classLoader)
        } catch (e: ClassNotFoundException) {
            XposedBridge.log("Find class $className failed")
            XposedBridge.log(e)
            null
        }
    }

    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi", "com.baidu.input_mi", "com.miui.catcherpatch"
    )

}