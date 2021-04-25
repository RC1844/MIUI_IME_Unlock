package com.xposed.miuiime

import android.annotation.SuppressLint
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

object PropertyUtils {
    @Volatile
    private var get: Method? = null

    @SuppressLint("PrivateApi")
    operator fun get(prop: String?, defaultvalue: String?): String? {
        var value = defaultvalue
        try {
            if (null == get) {
                synchronized(PropertyUtils::class.java) {
                    if (null == get) {
                        @SuppressLint("PrivateApi") val cls =
                            Class.forName("android.os.SystemProperties")
                        get = cls.getDeclaredMethod("get", String::class.java, String::class.java)
                    }
                }
            }
            value = get!!.invoke(null, *arrayOf<Any?>(prop, defaultvalue)) as String
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return value
    }
}