package com.github.xfalcon.vhosts;

import androidx.multidex.MultiDexApplication;

import com.google.android.material.color.DynamicColors;

/**
 * 应用入口：继承 MultiDexApplication 支持 minSdk 19 的 multidex，
 * 并在 Android 12+ 启用 Material You 动态取色（跟随系统壁纸），低版本自动回退到主题品牌色。
 */
public class VhostsApp extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
