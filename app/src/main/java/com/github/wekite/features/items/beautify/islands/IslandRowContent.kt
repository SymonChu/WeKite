package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

/**
 * 「这一行到底有没有内容」判定 —— 用来避免给**空白占位行**套卡片。
 *
 * ⚠️ 为什么需要它（v3.23 真机截图像素实测）：
 * 微信的偏好页与通讯录里存在**纯占位行**（区块间距 / 页脚留白），它们照样
 * 会走 `getView` / `onBindViewHolder`，我们一视同仁地套卡片，于是页面上就出现
 * 「白色圆角块，里面什么都没有」：
 * - 发现页「朋友圈」下方一张 **81dp 高、全空**的卡（原图 y=584..861）
 * - 我页「设置」下方一张 **约 35dp 高、全空**的卡（原图 y≈2516..2544）
 * - 通讯录同类行
 *
 * 判定走「可见内容」而不是「类名含 Category」：类名判据只覆盖名字里带
 * Category 的那一族，这些占位行的类名五花八门（v3.21 实测漏网），
 * 「有没有东西可看」才是这类行的共同特征。
 *
 * ⚠️ 刻意**不看 View 自己的 background** —— 微信给几乎每一行都挂了白底
 * drawable，拿它当判据等于恒 true，什么也筛不掉。只认「文字」与「图片」。
 */
internal fun hasVisibleContent(row: View): Boolean {
    if (row.visibility != View.VISIBLE) return false
    return hasVisibleContentInner(row)
}

private fun hasVisibleContentInner(view: View): Boolean {
    if (view.visibility != View.VISIBLE) return false

    if (view is TextView && view.text?.isNotBlank() == true) return true
    if (view is ImageView && view.drawable != null) return true

    if (view is ViewGroup) {
        for (index in 0 until view.childCount) {
            if (hasVisibleContentInner(view.getChildAt(index))) return true
        }
    }
    return false
}
