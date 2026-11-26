package com.hannah.stepwise

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

// Compose 将 dp 圆角转换成实际像素，并传入四个角。
internal class ContinuousCornerShape(
    private val startTop: CornerSize, private val endTop: CornerSize,
    private val endBottom: CornerSize, private val startBottom: CornerSize
) : CornerBasedShape(startTop, endTop, endBottom, startBottom) {
    constructor(radius: Dp) : this(CornerSize(radius), CornerSize(radius), CornerSize(radius), CornerSize(radius))

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        ContinuousCornerShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(size: Size, topStart: Float, topEnd: Float,
        bottomEnd: Float, bottomStart: Float, layoutDirection: LayoutDirection): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        val ltr = layoutDirection == LayoutDirection.Ltr
        return Outline.Generic(continuousRect(size, if (ltr) topStart else topEnd,
            if (ltr) topEnd else topStart, if (ltr) bottomEnd else bottomStart, if (ltr) bottomStart else bottomEnd))
    }
}

// 用两段三次贝塞尔曲线画每个角。它们在对角线处对称地接合，贴着直边平滑开始和结束。
internal fun continuousRect(size: Size, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float): Path {
    val path = Path()
    val extentLimit = size.minDimension / 2
    val width = size.width
    val height = size.height

    // 将归一化的右上角模板旋转到其他三角。每个角最多使用短边的一半，避免小控件的圆角相交。
    fun corner(x: Float, y: Float, dx: Int, dy: Int, radius: Float) {
        val extent = (radius * 1.45f).coerceAtMost(extentLimit)
        fun px(u: Float, v: Float) = x + extent * ((u - 1f) * dx - v * dy)
        fun py(u: Float, v: Float) = y + extent * ((u - 1f) * dy + v * dx)
        path.lineTo(px(0f, 0f), py(0f, 0f))
        if (extent == 0f) return
        path.cubicTo(px(.36f, 0f), py(.36f, 0f), px(.68f, 0f), py(.68f, 0f), px(.84f, .16f), py(.84f, .16f))
        path.cubicTo(px(1f, .32f), py(1f, .32f), px(1f, .64f), py(1f, .64f), px(1f, 1f), py(1f, 1f))
    }

    // 从上边中点出发，顺时针连接各角并闭合，供背景、边框、裁切和绘图共用。
    path.moveTo(width / 2, 0f)
    corner(width, 0f, 1, 0, topRight)
    corner(width, height, 0, 1, bottomRight)
    corner(0f, height, -1, 0, bottomLeft)
    corner(0f, 0f, 0, -1, topLeft)
    path.close()
    return path
}
