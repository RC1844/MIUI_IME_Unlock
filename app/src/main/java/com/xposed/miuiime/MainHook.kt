package com.xposed.miuiime

import android.view.inputmethod.InputMethodManager
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class MainHook : IXposedHookLoadPackage {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi", "com.baidu.input_mi", "com.miui.catcherpatch"
    )

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
                    Int::class, object : XC_MethodHook() {
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
        findClass("android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader)?.let {
            hookDeleteNotSupportIme(it)
        }

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
                    findClass(
                        "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                        param.args[0] as ClassLoader
                    )?.let {
                        hookDeleteNotSupportIme(it)
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
    fun hookSIsImeSupport(clazz: Class<*>) {
        try {
//            XposedBridge.log("Hook field sIsImeSupport")
            XposedHelpers.setStaticIntField(clazz, "sIsImeSupport", 1)
            XposedBridge.log("Hook field sIsImeSupport success")
        } catch (e: Throwable) {
            XposedBridge.log("Hook field sIsImeSupport failed")
            XposedBridge.log(e)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    fun hookIsXiaoAiEnable(clazz: Class<*>) {
        findAndHookMethod(
            clazz, "isXiaoAiEnable",
            XC_MethodReplacement.returnConstant(false)
        )
    }

    /**
     * 修复切换输入法列表
     *
     * @param clazz 声明或继承方法的类
     */
    fun hookDeleteNotSupportIme(clazz: Class<*>) {
        findAndHookMethod(
            clazz, "deleteNotSupportIme",
            XC_MethodReplacement.returnConstant(null)
        )
    }

    fun findAndHookMethod(
        clazz: Class<*>,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, *parameterTypesAndCallback)
            XposedBridge.log("Hook method $methodName success")
        } catch (e: Throwable) {
            XposedBridge.log("Hook method $methodName failed")
            XposedBridge.log(e)
        }
    }

    private fun findAndHookMethod(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypesAndCallback: Any?
    ) {
        findClass(className, classLoader)?.let {
            findAndHookMethod(
                it,
                methodName,
                *parameterTypesAndCallback
            )
        }
    }

    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            val clazz= XposedHelpers.findClass(className, classLoader)
            XposedBridge.log("Find class $className success")
            clazz
        } catch (e: Throwable) {
            XposedBridge.log("Find class $className failed")
            XposedBridge.log(e)
            null
        }
    }
}