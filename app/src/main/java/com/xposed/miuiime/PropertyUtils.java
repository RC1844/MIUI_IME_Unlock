package com.xposed.miuiime;

import android.annotation.SuppressLint;

import java.lang.reflect.Method;

public class PropertyUtils {

    private static volatile Method get = null;

    public static String get(String prop, String defaultvalue) {
        String value = defaultvalue;
        try {
            if (null == get) {
                synchronized (PropertyUtils.class) {
                    if (null == get) {
                        @SuppressLint("PrivateApi") Class<?> cls = Class.forName("android.os.SystemProperties");
                        get = cls.getDeclaredMethod("get", String.class, String.class);
                    }
                }
            }
            value = (String) (get.invoke(null, new Object[]{prop, defaultvalue}));
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return value;
    }
}
