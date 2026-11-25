package com.hebi.dualtouch

import android.content.Context
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

private fun visibleApplication(context: Context, packageName: String) = try {
    context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
} catch (_: PackageManager.NameNotFoundException) {
    null
} catch (_: RuntimeException) {
    null
}

internal fun appDisplayLabel(context: Context, packageName: String): String {
    val application = visibleApplication(context, packageName) ?: return packageName
    return try {
        application.loadLabel(context.packageManager).toString()
            .replace(Regex("\\s+"), " ").trim().take(80).ifBlank { packageName }
    } catch (_: RuntimeException) {
        packageName
    }
}

internal fun appIdentityRow(context: Context, palette: UiPalette, position: String, packageName: String): LinearLayout {
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(UiKit.dp(context, 10), UiKit.dp(context, 8), UiKit.dp(context, 10), UiKit.dp(context, 8))
        background = UiKit.shape(palette.surface)
    }
    val application = visibleApplication(context, packageName)
    val drawable = try {
        application?.loadIcon(context.packageManager)
    } catch (_: RuntimeException) {
        null
    }
    if (drawable != null) {
        row.addView(ImageView(context).apply {
            setImageDrawable(drawable)
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(UiKit.dp(context, 36), UiKit.dp(context, 36)).apply {
            marginEnd = UiKit.dp(context, 10)
        })
    }
    val text = UiKit.column(context)
    UiKit.add(text, UiKit.text(context, palette, "$position · ${appDisplayLabel(context, packageName)}", 15f, true))
    UiKit.add(text, UiKit.text(context, palette, packageName, 12f, secondary = true), topDp = 3)
    row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    return row
}
