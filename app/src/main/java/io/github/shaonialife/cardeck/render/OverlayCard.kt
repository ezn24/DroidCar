package io.github.shaonialife.cardeck.render

import android.graphics.Bitmap

/**
 * 合成器叠加卡片（当前用于「强制横屏未生效」提示）。
 *
 * 位图由调用方在后台线程画好（Android Canvas 排版文字），GL 线程只负责上传纹理与
 * 绘制；矩形使用**输出画面坐标**（与编码流同一坐标系，顶部原点），
 * 因此触摸命中判定可直接复用同一份数值，无需再做缩放/翻转换算。
 */
class OverlayCard(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** 整体不透明度（进出场淡入淡出用）。 */
    val alpha: Float = 1f,
) {
    /** 同一张位图挪位置 / 改透明度：合成器只认位图身份决定是否重传纹理，动画每帧不重传。 */
    fun moved(
        top: Float,
        alpha: Float,
    ): OverlayCard = OverlayCard(bitmap, left, top, right, top + (bottom - this.top), alpha)

    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom
}
