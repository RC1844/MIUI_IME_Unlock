package com.xposed.miuiime

import android.content.Context
import android.os.Binder
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.Log
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.getObjectAs
import com.github.kyuubiran.ezxhelper.utils.getStaticObject
import com.github.kyuubiran.ezxhelper.utils.hookAfter
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import com.github.kyuubiran.ezxhelper.utils.hookReplace
import com.github.kyuubiran.ezxhelper.utils.hookReturnConstant
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAuto
import com.github.kyuubiran.ezxhelper.utils.invokeMethodAutoAs
import com.github.kyuubiran.ezxhelper.utils.invokeStaticMethodAuto
import com.github.kyuubiran.ezxhelper.utils.loadClassOrNull
import com.github.kyuubiran.ezxhelper.utils.putStaticObject
import com.github.kyuubiran.ezxhelper.utils.sameAs
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge

private const val TAG = "miuiime"

class MainHook : IXposedHookLoadPackage {
    private val miuiImeList: List<String> = listOf(
        "com.iflytek.inputmethod.miui",
        "com.sohu.inputmethod.sogou.xiaomi",
        "com.baidu.input_mi",
        "com.miui.catcherpatch",
        "com.xiaomi.type",
    )
    private val hookedImeSupportClasses = mutableSetOf<Class<*>>()
    private var navBarColor: Int? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否支持全面屏优化
        if (PropertyUtils["ro.miui.support_miui_ime_bottom", "0"] != "1") return
        EzXHelperInit.initHandleLoadPackage(lpparam)
        EzXHelperInit.setLogTag(TAG)
        Log.i("miuiime is supported")

        when (lpparam.packageName) {
            "android" -> startPermissionHook()
            "com.miui.phrase" -> startPackageValidationHook(lpparam)
            else -> startHook(lpparam)
        }
    }

    private fun startHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 检查是否为小米定制输入法
        val isNonCustomize = !miuiImeList.contains(lpparam.packageName)
        if (isNonCustomize) {
            val sInputMethodServiceInjector =
                loadClassOrNull("android.inputmethodservice.InputMethodServiceInjector")
                    ?: loadClassOrNull("android.inputmethodservice.InputMethodServiceStubImpl")

            sInputMethodServiceInjector?.also {
                hookImeSupport(it)
                hookIsXiaoAiEnable(it)
                setPhraseBgColor(it)
            } ?: Log.e("Failed:Class not found: InputMethodServiceInjector")
        }

        hookDeleteNotSupportIme(
            "android.inputmethodservice.InputMethodServiceInjector\$MiuiSwitchInputMethodListener",
            lpparam.classLoader
        )

        // 获取常用语的ClassLoader
        findMethod("android.inputmethodservice.InputMethodModuleManager") {
            name == "loadDex" && parameterTypes.sameAs(ClassLoader::class.java, String::class.java)
        }.hookBefore { param ->
            val loader = param.args[0] as ClassLoader
            val dexPath = param.args[1] as String
            // 系统原始逻辑，若已加载dex则直接返回，避免重复hook
            if (loader !is BaseDexClassLoader) throw NoSuchMethodException("addDexPath method not found.")
            val loadedBottomManager = runCatching {
                Class.forName("com.miui.inputmethod.InputMethodBottomManager", true, loader)
            }.getOrNull()
            if (loadedBottomManager != null) {
                if (isNonCustomize) hookImeSupport(loadedBottomManager)
                param.result = null
                return@hookBefore
            }
            loader.invokeMethodAuto("addDexPath", dexPath)

            hookDeleteNotSupportIme(
                "com.miui.inputmethod.InputMethodBottomManager\$MiuiSwitchInputMethodListener",
                loader
            )
            loadClassOrNull(
                "com.miui.inputmethod.InputMethodBottomManager",
                loader
            )?.also {
                if (isNonCustomize) {
                    hookImeSupport(it)
                    hookIsXiaoAiEnable(it)
                }

                // 针对A11的修复切换输入法列表
                it.getDeclaredMethod("getSupportIme").hookReplace { _ ->
                    it.getStaticObject("sBottomViewHelper")
                        .getObjectAs<InputMethodManager>("mImm").enabledInputMethodList
                }
            } ?: Log.e("Failed:Class not found: com.miui.inputmethod.InputMethodBottomManager")
            param.result = null
        }

        Log.i("Hook MIUI IME Done!")
    }

    /**
     * 跳过包名检查，直接开启输入法优化
     *
     * @param clazz 声明或继承字段的类
     */
    private fun hookSIsImeSupport(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.putStaticObject("sIsImeSupport", 1)
            Log.i("Success:Hook field sIsImeSupport")
        }.onFailure {
            Log.i("Failed:Hook field sIsImeSupport")
            Log.i(it)
        }
    }

    /**
     * 恢复可能被输入法服务 onDestroy 重置的缓存字段，并 hook 判定方法防止再次失效。
     */
    private fun hookImeSupport(clazz: Class<*>) {
        hookSIsImeSupport(clazz)
        if (hookedImeSupportClasses.contains(clazz)) return

        kotlin.runCatching {
            val methods = clazz.declaredMethods.filter {
                it.name == "isImeSupport" && it.returnType == Boolean::class.javaPrimitiveType
            }
            if (methods.isEmpty()) throw NoSuchMethodException("${clazz.name}.isImeSupport")
            methods.forEach { it.hookReturnConstant(true) }
            hookedImeSupportClasses.add(clazz)
            Log.i("Success:Hook method isImeSupport")
        }.onFailure {
            Log.i("Failed:Hook method isImeSupport")
            Log.i(it)
        }
    }

    /**
     * 小爱语音输入按钮失效修复
     *
     * @param clazz 声明或继承方法的类
     */
    private fun hookIsXiaoAiEnable(clazz: Class<*>) {
        kotlin.runCatching {
            clazz.getMethod("isXiaoAiEnable").hookReturnConstant(false)
        }.onFailure {
            Log.i("Failed:Hook method isXiaoAiEnable")
            Log.i(it)
        }
    }

    /**
     * 在适当的时机修改抬高区域背景颜色
     *
     * @param clazz 声明或继承字段的类
     */
    private fun setPhraseBgColor(clazz: Class<*>) {
        kotlin.runCatching {
            // 导航栏颜色被设置后, 将颜色存储起来并传递给常用语
            findMethod("com.android.internal.policy.PhoneWindow") {
                name == "setNavigationBarColor" && parameterTypes.sameAs(Int::class.java)
            }.hookAfter { param ->
                if (param.args[0] == 0) return@hookAfter

                navBarColor = param.args[0] as Int
                customizeBottomViewColor(clazz)
            }

            // 当常用语被创建后, 将背景颜色设置为存储的导航栏颜色
            clazz.findMethod { name == "addMiuiBottomView" }.hookAfter {
                customizeBottomViewColor(clazz)
            }
        }.onFailure {
            Log.i("Failed to set the color of the MiuiBottomView")
            Log.i(it)
        }
    }

    /**
     * 将导航栏颜色赋值给输入法优化的底图
     *
     * @param clazz 声明或继承字段的类
     */
    private fun customizeBottomViewColor(clazz: Class<*>) {
        navBarColor?.let {
            val color = -0x1 - it
            clazz.invokeStaticMethodAuto(
                "customizeBottomViewColor",
                true, navBarColor, color or -0x1000000, color or 0x66000000
            )
        }
    }

    /**
     * 针对A10的修复切换输入法列表
     *
     * @param className 声明或继承方法的类的名称
     */
    private fun hookDeleteNotSupportIme(className: String, classLoader: ClassLoader) {
        kotlin.runCatching {
            findMethod(className, classLoader) { name == "deleteNotSupportIme" }
                .hookReturnConstant(null)
        }.onFailure {
            Log.i("Failed:Hook method deleteNotSupportIme")
            Log.i(it)
        }
    }

    /**
     * Hook 获取应用列表权限，使当前输入法可见其他输入法。
     * 用于修复部分输入法（搜狗输入法小米版等）缺少获取输入法列表权限，导致切换输入法功能不能显示其他输入法的问题。
     */
    private fun startPermissionHook() {
        runCatching {
            findMethod("com.android.server.inputmethod.InputMethodManagerServiceImpl") {
                name == "isCallingBetweenCustomIME"
            }.hookAfter { param ->
                if (param.result == true) return@hookAfter
                val context = param.args[0] as Context
                val uid = param.args[1] as Int
                val currentInputMethodPackageName = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD
                )?.substringBefore('/') ?: return@hookAfter
                val packagesForUid = context.packageManager.getPackagesForUid(uid) ?: return@hookAfter
                if (packagesForUid.contains(currentInputMethodPackageName)) {
                    param.result = true
                }
            }
        }.onFailure {
            Log.i("Failed: Hook method isCallingBetweenCustomIME")
            Log.i(it)
        }
    }

    /**
     * Hook InputProvider的输入法白名单，修复当前输入法无法获得剪贴板的问题
     */
    private fun startPackageValidationHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            System.loadLibrary("dexkit")
            DexKitBridge.create(lpparam.appInfo.sourceDir).use { bridge ->
                bridge.findMethod {
                    matcher {
                        declaredClass = "com.miui.provider.InputProvider"
                        returnType = "boolean"
                        usingStrings(
                            "InputProvider",
                            "Invalid caller UID: ",
                            "No package name for UID: ",
                            "Package validation failed: ",
                            "Unexpected error during package validation"
                        )
                    }
                }.singleOrNull()?.getMethodInstance(lpparam.classLoader)?.hookBefore { param ->
                    val callingUid = Binder.getCallingUid()
                    val context = param.thisObject.invokeMethodAutoAs<Context>("getContext") ?: return@hookBefore
                    val packagesForUid = context.packageManager.getPackagesForUid(callingUid) ?: return@hookBefore
                    val currentInputMethodPackageName = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.DEFAULT_INPUT_METHOD
                    )?.substringBefore('/') ?: return@hookBefore
                    if (packagesForUid.contains(currentInputMethodPackageName)) {
                        param.result = true
                    }
                }
            }
        }.onFailure {
            Log.i("Failed: Hook package validation")
            Log.i(it)
        }
    }
}
