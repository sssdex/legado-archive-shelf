package io.legado.app.ui.main.bookshelf.archive

import io.legado.app.data.entities.Book
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * 档案式立体书架的几何计算。
 *
 * 移植自 AzukiReader / Wenku8 的 archive_shelf_geometry.dart：
 * 平行投影（不是透视）由 [DEPTH_X] / [DEPTH_Y] 两个方向的位移描述，
 * 书脊朝左、封面朝右上抬升，中间那本被“抽出”并上浮。
 *
 * 这里所有长度单位都是 dp，绘制时再统一换算成 px。
 */
object ArchiveShelfGeometry {

    /** 封面沿 x 方向的投影系数（封面看起来向右铺开 0.66 倍宽度） */
    const val DEPTH_X = 0.66f

    /** 封面沿 y 方向的投影系数（负值表示封面右上翘起） */
    const val DEPTH_Y = -0.30f

    /** 立体书架最多摆多少本（与 Azuki 的默认值一致） */
    const val DEFAULT_VISIBLE_COUNT = 12

    const val MIN_VISIBLE_COUNT = 1
    const val MAX_VISIBLE_COUNT = 500

    /** 书脊宽度按可用宽度缩放，限制在 12–16dp */
    fun spine(width: Float): Float = (width * 0.035f).coerceIn(12f, 16f)

    /** 封面宽度按可用宽度缩放，限制在 78–112dp */
    fun cover(width: Float): Float = (width * 0.27f).coerceIn(78f, 112f)

    /** 封面长宽比沿用 118:166，再加 54dp 留白 */
    fun height(cover: Float): Float = (cover - 14f) * 166f / 118f + 38f

    /** 台面高度：封面投影下沿 + 封面高度的 60% + 12dp */
    fun baseY(cover: Float, height: Float): Float = -cover * DEPTH_Y + height * 0.60f + 9f

    /** 左右远端轻微下坠，形成一条微微弯曲的导轨 */
    fun rail(distance: Float): Float =
        distance.coerceIn(-7f, 7f).let { it * 2.5f + it * it * 0.45f }

    /** 离焦点越近权重越高，1.48 格之外完全不受影响 */
    fun weight(distance: Float): Float =
        if (abs(distance) >= 1.48f) 0f
        else cos(distance / 1.48f * PI.toFloat() / 2).let { it * it }

    /** 焦点书被抽出的距离 */
    fun pull(cover: Float, weight: Float): Float =
        cover * 0.84f * weight * (1.24f - 0.24f * weight)

    /** 焦点书的上浮量，平滑插值 */
    fun lift(weight: Float): Float = weight * weight * (3 - 2 * weight)

    fun originX(cover: Float, weight: Float): Float = -pull(cover, weight) * DEPTH_X

    fun originY(cover: Float, height: Float, weight: Float): Float =
        baseY(cover, height) - pull(cover, weight) * DEPTH_Y - height * 0.60f * lift(weight)

    /** 相邻两本的步距：书脊宽度 + 10dp，所以整排看起来像挤在一起的书 */
    fun pitch(spine: Float): Float = spine + 10f

    /** 立体书架自身占用的高度 */
    fun rowHeight(cover: Float, height: Float): Float = baseY(cover, height) + height + 54f

    /** 单本书的占位宽度（书脊 + 封面投影） */
    fun footprint(cover: Float, spine: Float): Float = spine + cover * DEPTH_X

    /** 让第一本书居中所需的偏移 */
    fun center(width: Float, cover: Float, spine: Float): Float =
        width / 2f - footprint(cover, spine) / 2f + cover * 0.84f * DEPTH_X

    /** 两侧需要预先布置多少格才够铺满屏幕 */
    fun laneRadius(width: Float, cover: Float, spine: Float, pitch: Float): Int =
        ceil((width / 2f + footprint(cover, spine) + cover) / pitch).toInt()

    /** 把任意 lane 映射回 0..count-1，可无限循环拖动 */
    fun bookIndex(lane: Int, count: Int): Int = ((lane % count) + count) % count

    /**
     * 命中检测，坐标原点为单本书的包围盒左上角
     * （也就是 position.y 再上移 0.30 * cover 的那个点）。
     */
    fun contains(x: Float, y: Float, spine: Float, cover: Float, height: Float): Boolean {
        val dx = cover * DEPTH_X
        val dy = -cover * DEPTH_Y
        if (x < 0 || x > spine + dx) return false
        // 上沿：(0,dy) -> (dx,0) -> (dx+spine, 0.1*spine)
        val top = if (x <= dx) dy - x * dy / dx else (x - dx) * 0.1f
        // 下沿：先沿书脊下移，再沿封面底边下移
        val bottom = if (x <= spine) {
            dy + x * 0.1f + height
        } else {
            dy + spine * 0.1f - (x - spine) * dy / dx + height
        }
        return y >= top && y <= bottom
    }
}

/**
 * 两份书单在「画面」上是否等价：顺序一致，且书架/书目用到的字段都一致。
 *
 * 听书、音频书在播的时候播放器每 500ms 就往 books 表写一次进度和阅读时间，
 * 那些字段书架根本不画。原来只要表一变就重绑头部（请求布局 + 重排焦点 + 重新要封面），
 * 手还在滑的时候就被拽回去，看着就是「卡」和「两本书之间来回跳」。
 * 进度之外的字段（书名/作者/封面）变了才算真的变了。
 */
internal fun List<Book>.sameArchiveDisplay(
    other: List<Book>
): Boolean {
    if (!sameArchiveOrder(other)) return false
    for (i in indices) {
        val a = this[i]
        val b = other[i]
        if (a.name != b.name ||
            a.author != b.author ||
            a.getDisplayCover() != b.getDisplayCover()
        ) {
            return false
        }
    }
    return true
}

/** 两份书单的顺序是否一致（只比 bookUrl） */
internal fun List<Book>.sameArchiveOrder(other: List<Book>): Boolean {
    if (size != other.size) return false
    for (i in indices) {
        if (this[i].bookUrl != other[i].bookUrl) return false
    }
    return true
}

