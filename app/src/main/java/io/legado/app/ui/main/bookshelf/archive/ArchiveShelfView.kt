package io.legado.app.ui.main.bookshelf.archive

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.Scroller
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.storage.Restore
import io.legado.app.lib.theme.loadUiTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 档案式立体书架。
 *
 * 视觉规则来自 AzukiReader / Wenku8 的 `Wenku8ArchiveShelf`：
 * 一排书脊挤在一起，中间那本被抽出、上浮，封面用平行投影（不是透视）向右上方铺开；
 * 左右拖动可以在书目之间移动，松手后按惯性滑行并吸附到最近的一本；
 * 点击非焦点书会先把它抽出来，再点一次才真正打开。
 *
 * 所有尺寸按 dp 计算后统一换算成 px 绘制。
 */
class ArchiveShelfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 点击已抽出的那本书 */
    var onBookClick: ((Book) -> Unit)? = null

    /** 长按已抽出的那本书 */
    var onBookLongClick: ((Book) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val spToDp = scaledDensity / density

    private var books: List<Book> = emptyList()
    private var focus = 0f

    // ---- 尺寸（dp） ----
    private var contentWidthDp = 0f
    private var coverDp = 0f
    private var heightDp = 0f
    private var spineDp = 0f
    private var pitchDp = 1f
    private var baseYDp = 0f
    private var centerDp = 0f
    private var shelfHeightDp = 0f
    private var infoHeightDp = 0f
    private var counterHeightDp = 0f

    private var titleRect = RectF()
    private var prevRect = RectF()
    private var nextRect = RectF()

    // ---- 绘制工具 ----
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }

    /**
     * 书名用衬线（宋体）：纸本档案的观感，和左上角的等宽编号形成对比。
     *
     * Android 的 `Typeface.SERIF` 对中文会回落到黑体，所以内置了一份思源宋体
     * （`assets/font/archive_serif.ttf`，Source Han Serif，SIL OFL 1.1）。
     * 主题里设了「标题字体」时优先用主题字体。
     */
    private val serifBase by lazy {
        runCatching { context.loadUiTypeface(AppConfig.titleFontPath) }.getOrNull()
            ?: runCatching {
                Typeface.createFromAsset(context.assets, "font/archive_serif.ttf")
            }.getOrNull()
    }

    private fun serifTypeface(style: Int): Typeface =
        serifBase?.let { Typeface.create(it, style) } ?: Typeface.create(Typeface.SERIF, style)

    private val titleTypeface by lazy { serifTypeface(Typeface.NORMAL) }
    private val bodyTypeface by lazy { serifTypeface(Typeface.NORMAL) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val coverMatrix = Matrix()
    private val tempPath = Path()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val titleMeasurePaint = TextPaint()

    // ---- 配色 ----
    /** 纸面颜色：半透明主题背景色合成到白/黑底，保证不透明（见 [ArchivePalette.paper]） */
    private val paperColor: Int = ArchivePalette.paper(context)
    private val inkColor = context.primaryTextColor
    private val mutedColor = context.secondaryTextColor
    private val lineColor = withAlpha(inkColor, 40)

    // ---- 封面缓存 ----
    // 数量最多可设 500 本，封面按「焦点附近若干本」按需加载，并按 LRU 淘汰，避免堆内存被撑爆
    private val covers = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > COVER_CACHE_SIZE
    }
    private val coverTargets = HashMap<String, CustomTarget<Bitmap>>()

    // ---- 手势 ----
    private val scroller = Scroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressFired = false
    private var settleAnimator: ValueAnimator? = null

    // ---- 触感 ----
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var lastHapticLane = -1
    private val hapticExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val longPressRunnable = Runnable {
        if (dragging) return@Runnable
        longPressFired = true
        val lane = hitLane(downX, downY) ?: return@Runnable
        if (abs(lane - focus) < 0.5f) {
            books.getOrNull(ArchiveShelfGeometry.bookIndex(lane.roundToInt(), books.size))
                ?.let { onBookLongClick?.invoke(it) }
        }
    }

    private val focusedIndex: Int
        get() = if (books.isEmpty()) {
            0
        } else {
            ArchiveShelfGeometry.bookIndex(focus.roundToInt(), books.size)
        }

    // ------------------------------------------------------------------ 公开接口

    /**
     * 提交书架数据。
     *
     * 刷新时按 bookUrl 找回原来的焦点书，避免详情页返回或目录更新后书架跳回第一本。
     */
    fun setBooks(list: List<Book>, visibleCount: Int = ArchiveShelfGeometry.DEFAULT_VISIBLE_COUNT) {
        val limited = list.take(
            visibleCount.coerceIn(
                ArchiveShelfGeometry.MIN_VISIBLE_COUNT,
                ArchiveShelfGeometry.MAX_VISIBLE_COUNT
            )
        )
        val anchorUrl = books.getOrNull(focusedIndex)?.bookUrl
        val sameBooks = limited.size == books.size &&
            limited.indices.all { limited[it].bookUrl == books[it].bookUrl }
        books = limited
        if (!sameBooks) {
            val newIndex = anchorUrl?.let { url -> limited.indexOfFirst { it.bookUrl == url } } ?: -1
            // 循环滑动后 focus 是一个可以无限延伸的游标，落位时折回 0..count-1
            focus = if (newIndex >= 0) {
                newIndex.toFloat()
            } else {
                focus.coerceIn(0f, (limited.size - 1).coerceAtLeast(0).toFloat())
            }
        }
        if (limited.isEmpty()) {
            focus = 0f
        } else if (!dragging) {
            focus = ArchiveShelfGeometry.bookIndex(focus.roundToInt(), limited.size).toFloat()
        }
        lastHapticLane = focus.roundToInt()
        coverTargets.keys.retainAll(limited.map { it.bookUrl }.toHashSet())
        covers.keys.retainAll(limited.map { it.bookUrl }.toHashSet())
        requestCovers()
        contentDescription = if (books.isEmpty()) {
            null
        } else {
            "立体书架 ${focusedIndex + 1}/${books.size} ${books[focusedIndex].name}"
        }
        requestLayout()
        invalidate()
    }

    // ------------------------------------------------------------------ 尺寸

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        computeMetrics(width)
        val desired = if (books.isEmpty()) {
            0
        } else {
            dp(shelfHeightDp + infoHeightDp + counterHeightDp).roundToInt()
        }
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) {
            // 封面尺寸依赖宽高，尺寸变化后要按新的目标尺寸重新解码
            coverTargets.values.forEach { target ->
                runCatching { Glide.with(context).clear(target) }
            }
            coverTargets.clear()
            covers.clear()
            requestCovers()
        }
    }

    private fun computeMetrics(widthPx: Int) {
        val availableDp = (widthPx - paddingLeft - paddingRight) / density
        contentWidthDp = min(availableDp, MAX_WIDTH_DP).coerceAtLeast(1f)
        coverDp = ArchiveShelfGeometry.cover(contentWidthDp)
        heightDp = ArchiveShelfGeometry.height(coverDp)
        spineDp = ArchiveShelfGeometry.spine(contentWidthDp)
        pitchDp = ArchiveShelfGeometry.pitch(spineDp).coerceAtLeast(1f)
        baseYDp = ArchiveShelfGeometry.baseY(coverDp, heightDp)
        centerDp = (availableDp - contentWidthDp) / 2f +
            ArchiveShelfGeometry.center(contentWidthDp, coverDp, spineDp)
        shelfHeightDp = ArchiveShelfGeometry.rowHeight(coverDp, heightDp)
        // 一行标题（单行省略）+ 一行作者 + 上下留白
        infoHeightDp = TITLE_TEXT_SP * spToDp * 1.35f + AUTHOR_TEXT_SP * spToDp * 1.4f + 16f
        counterHeightDp = 32f
        updateHitRects(widthPx)
    }

    private fun updateHitRects(widthPx: Int) {
        val infoTop = dp(shelfHeightDp)
        val infoBottom = infoTop + dp(infoHeightDp)
        titleRect.set(dp(4f), infoTop, widthPx - dp(4f), infoBottom)
        val counterTop = infoBottom
        val bottom = counterTop + dp(counterHeightDp)
        prevRect.set(0f, counterTop, 0f, bottom)
        val right = widthPx - dp(6f)
        nextRect.set(right - dp(30f), counterTop, right, bottom)
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        if (books.isEmpty() || width == 0) return
        val radius = ArchiveShelfGeometry.laneRadius(contentWidthDp, coverDp, spineDp, pitchDp)
        val poolCenter = floor(focus).toInt()
        val first = poolCenter - radius
        val last = poolCenter + radius + 1
        val activeLane = focus.roundToInt()

        drawGrid(canvas)
        for (lane in first..last) {
            drawShadow(canvas, lane)
        }
        // 自左向右绘制：右侧书籍的书脊压在左侧书籍的封面上，和 Wenku8 的层叠顺序一致
        for (lane in first..last) {
            drawVolume(canvas, lane, activeLane)
        }
        drawFocusedInfo(canvas)
        drawCounter(canvas)
    }

    private fun laneOriginX(lane: Int): Float {
        val distance = lane - focus
        val weight = ArchiveShelfGeometry.weight(distance)
        return centerDp + distance * pitchDp + ArchiveShelfGeometry.originX(coverDp, weight)
    }

    private fun laneOriginY(lane: Int): Float {
        val distance = lane - focus
        val weight = ArchiveShelfGeometry.weight(distance)
        return ArchiveShelfGeometry.originY(coverDp, heightDp, weight) +
            ArchiveShelfGeometry.rail(distance)
    }

    private fun drawGrid(canvas: Canvas) {
        val step = dp(56f)
        val bottom = dp(shelfHeightDp) - dp(24f)
        strokePaint.color = withAlpha(inkColor, 12)
        strokePaint.strokeWidth = dp(0.5f)
        var x = (width % step) / 2f
        while (x < width) {
            canvas.drawLine(x, 0f, x, bottom, strokePaint)
            x += step
        }
        var y = dp(24f)
        while (y < bottom) {
            canvas.drawLine(0f, y, width.toFloat(), y, strokePaint)
            y += step
        }
    }

    private fun drawShadow(canvas: Canvas, lane: Int) {
        val x = dp(centerDp + (lane - focus) * pitchDp)
        val y = dp(baseYDp + heightDp + ArchiveShelfGeometry.rail(lane - focus))
        val spine = dp(spineDp)
        val depthX = dp(coverDp * ArchiveShelfGeometry.DEPTH_X)
        val depthY = dp(-coverDp * ArchiveShelfGeometry.DEPTH_Y)
        tempPath.reset()
        tempPath.moveTo(x, y)
        tempPath.lineTo(x + spine, y + dp(1.4f))
        tempPath.lineTo(x + spine + depthX, y - depthY)
        tempPath.lineTo(x + depthX, y - depthY)
        tempPath.lineTo(x - dp(10f), y + dp(10f))
        tempPath.close()
        fillPaint.shader = LinearGradient(
            x, y, x + depthX, y - depthY,
            withAlpha(inkColor, 22), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
    }

    private fun drawVolume(canvas: Canvas, lane: Int, activeLane: Int) {
        val index = ArchiveShelfGeometry.bookIndex(lane, books.size)
        val book = books.getOrNull(index) ?: return
        val spine = dp(spineDp)
        val cover = dp(coverDp)
        val height = dp(heightDp)
        val depthX = cover * ArchiveShelfGeometry.DEPTH_X
        val depthY = cover * -ArchiveShelfGeometry.DEPTH_Y

        canvas.save()
        canvas.translate(dp(laneOriginX(lane)), dp(laneOriginY(lane)) - depthY)

        // 封面：平行投影，右上抬升
        canvas.save()
        coverMatrix.setValues(
            floatArrayOf(
                ArchiveShelfGeometry.DEPTH_X, 0f, spine,
                ArchiveShelfGeometry.DEPTH_Y, 1f, depthY + spine * 0.10f,
                0f, 0f, 1f
            )
        )
        canvas.concat(coverMatrix)
        canvas.clipRect(0f, 0f, cover, height)
        drawCoverFace(
            canvas, book, index,
            withImage = abs(lane - activeLane) <= 1,
            // 编号只在被抽出来的那本书上显示：平放/半抽出的书要么被书脊压住，
            // 要么在邻位露出一条，都会造成「还没滑到就看到 NO.00x」
            showCode = abs(lane - focus) < 0.5f
        )
        canvas.restore()

        // 顶面
        tempPath.reset()
        tempPath.moveTo(0f, depthY)
        tempPath.lineTo(spine, depthY + spine * 0.10f)
        tempPath.lineTo(spine + depthX, spine * 0.10f)
        tempPath.lineTo(depthX, 0f)
        tempPath.close()
        // 先铺一层不透明纸色：渐变里带半透明墨色，直接填会让壁纸透出来
        fillPaint.shader = null
        fillPaint.color = paperColor
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = LinearGradient(
            0f, depthY, spine + depthX, 0f,
            lineColor, paperColor, Shader.TileMode.CLAMP
        )
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.color = lineColor
        strokePaint.strokeWidth = dp(0.4f)
        canvas.drawPath(tempPath, strokePaint)

        // 书脊侧面
        tempPath.reset()
        tempPath.moveTo(0f, depthY)
        tempPath.lineTo(spine, depthY + spine * 0.10f)
        tempPath.lineTo(spine, depthY + spine * 0.10f + height)
        tempPath.lineTo(0f, depthY + height)
        tempPath.close()
        fillPaint.shader = null
        fillPaint.color = paperColor
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = LinearGradient(
            0f, 0f, spine, 0f,
            intArrayOf(paperColor, withAlpha(inkColor, 34), paperColor),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(tempPath, fillPaint)
        fillPaint.shader = null
        strokePaint.color = withAlpha(inkColor, 46)
        canvas.drawPath(tempPath, strokePaint)
        strokePaint.color = withAlpha(inkColor, 56)
        canvas.drawLine(
            dp(3f), depthY + dp(15f),
            spine - dp(3f), depthY + dp(15f) + spine * 0.10f,
            strokePaint
        )

        // 书脊文字：向右侧微倾
        canvas.save()
        canvas.translate(0f, depthY)
        coverMatrix.setValues(
            floatArrayOf(
                1f, 0f, 0f,
                0.10f, 1f, 0f,
                0f, 0f, 1f
            )
        )
        canvas.concat(coverMatrix)
        canvas.clipRect(0f, 0f, spine, height)
        drawSpineText(canvas, book, index, spine, height)
        canvas.restore()

        canvas.restore()
    }

    private fun drawCoverFace(
        canvas: Canvas, book: Book, index: Int, withImage: Boolean, showCode: Boolean
    ) {
        val cover = dp(coverDp)
        val height = dp(heightDp)
        val padH = dp(10f)

        fillPaint.shader = null
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = paperColor
        canvas.drawRect(0f, 0f, cover, height, fillPaint)

        strokePaint.color = withAlpha(inkColor, 30)
        strokePaint.strokeWidth = dp(0.5f)
        canvas.drawRect(dp(0.25f), dp(0.25f), cover - dp(0.25f), height - dp(0.25f), strokePaint)

        monoPaint.color = inkColor
        monoPaint.textSize = 7.2f * scaledDensity
        // NO.00x 和底部的 档案 / 00x 都只画在抽出来的那本书上，并且往封面内侧收，
        // 免得「还没滑到这本就看到编号」。两者用同一个缩进，上下对齐。
        if (showCode) {
            canvas.drawText(
                "NO.%03d".format(index + 1),
                dp(LABEL_INSET_DP),
                dp(9f) + monoPaint.textSize,
                monoPaint
            )
            canvas.drawText(
                "档案 / %03d".format(index + 1),
                dp(LABEL_INSET_DP),
                height - dp(8f),
                monoPaint
            )
        }

        if (!withImage) return
        val top = dp(9f) + monoPaint.textSize * 1.2f + dp(5f)
        val bottom = height - dp(8f) - monoPaint.textSize * 1.2f - dp(4f)
        val bitmap = covers[book.bookUrl]
        if (bitmap != null && !bitmap.isRecycled && bottom > top) {
            drawCenterCrop(canvas, bitmap, padH, top, cover - padH, bottom)
        } else {
            // 封面未就绪时保持一张白纸，和远端书一致
            textPaint.color = withAlpha(inkColor, 120)
            textPaint.textSize = 9f * scaledDensity
            textPaint.typeface = titleTypeface
            val name = TextUtils.ellipsize(
                book.name,
                titleMeasurePaint.apply {
                    set(textPaint)
                    textSize = 9f * scaledDensity
                },
                (cover - padH * 2f).coerceAtLeast(1f),
                TextUtils.TruncateAt.END
            ).toString()
            val available = (bottom - top)
            if (available > textPaint.textSize * 2) {
                canvas.drawText(
                    name, padH,
                    top + available / 2f, textPaint
                )
            }
            textPaint.typeface = bodyTypeface
        }
    }

    private fun drawCenterCrop(
        canvas: Canvas, bitmap: Bitmap,
        left: Float, top: Float, right: Float, bottom: Float
    ) {
        val dstWidth = right - left
        val dstHeight = bottom - top
        if (dstWidth <= 0f || dstHeight <= 0f) return
        val scale = max(dstWidth / bitmap.width, dstHeight / bitmap.height)
        val srcWidth = dstWidth / scale
        val srcHeight = dstHeight / scale
        srcRect.set(
            ((bitmap.width - srcWidth) / 2f).roundToInt(),
            ((bitmap.height - srcHeight) / 2f).roundToInt(),
            ((bitmap.width + srcWidth) / 2f).roundToInt(),
            ((bitmap.height + srcHeight) / 2f).roundToInt()
        )
        dstRect.set(left, top, right, bottom)
        canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
    }

    private fun drawSpineText(
        canvas: Canvas, book: Book, index: Int, spine: Float, height: Float
    ) {
        monoPaint.color = inkColor
        monoPaint.textSize = 6.6f * scaledDensity
        canvas.drawText(
            "%02d".format(index + 1),
            (spine - monoPaint.measureText("%02d".format(index + 1))) / 2f,
            dp(7f) + monoPaint.textSize,
            monoPaint
        )

        textPaint.color = inkColor
        textPaint.typeface = titleTypeface
        textPaint.textSize = SPINE_TEXT_SP * scaledDensity
        textPaint.textAlign = Paint.Align.CENTER
        val glyphs = ArrayList<String>(7)
        var offset = 0
        while (offset < book.name.length && glyphs.size < 7) {
            val codePoint = book.name.codePointAt(offset)
            glyphs.add(String(Character.toChars(codePoint)))
            offset += Character.charCount(codePoint)
        }
        val lines = if (offset < book.name.length) glyphs + "…" else glyphs
        val lineHeight = SPINE_TEXT_SP * 1.12f * scaledDensity
        val blockHeight = lines.size * lineHeight
        var baseline = (height - blockHeight) / 2f + lineHeight * 0.80f
        lines.forEach { glyph ->
            canvas.drawText(glyph, spine / 2f, baseline, textPaint)
            baseline += lineHeight
        }
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = bodyTypeface

        monoPaint.textSize = 6.6f * scaledDensity
        val bullet = "○"
        canvas.drawText(
            bullet,
            (spine - monoPaint.measureText(bullet)) / 2f,
            height - dp(7f), monoPaint
        )
    }

    private fun drawFocusedInfo(canvas: Canvas) {
        val book = books.getOrNull(focusedIndex) ?: return
        textPaint.color = inkColor
        textPaint.typeface = titleTypeface
        textPaint.textSize = TITLE_TEXT_SP * scaledDensity
        val title = TextUtils.ellipsize(
            book.name,
            titleMeasurePaint.apply { set(textPaint) },
            (width - dp(40f)).coerceAtLeast(1f),
            TextUtils.TruncateAt.END
        ).toString()
        val titleBaseline = dp(shelfHeightDp) + dp(3f) + textPaint.textSize
        canvas.drawText(title, dp(8f), titleBaseline, textPaint)

        // 右侧的开卷箭头
        drawChevron(canvas, width - dp(16f), titleBaseline - textPaint.textSize * 0.35f, true)

        textPaint.typeface = bodyTypeface
        textPaint.textSize = AUTHOR_TEXT_SP * scaledDensity
        textPaint.color = mutedColor
        val author = TextUtils.ellipsize(
            book.author,
            titleMeasurePaint.apply { set(textPaint) },
            (width - dp(16f)).coerceAtLeast(1f),
            TextUtils.TruncateAt.END
        ).toString()
        val authorBaseline = titleBaseline + AUTHOR_TEXT_SP * scaledDensity * 1.45f + dp(2f)
        canvas.drawText(author, dp(8f), authorBaseline, textPaint)
    }

    private fun drawCounter(canvas: Canvas) {
        val label = "%02d / %02d".format(focusedIndex + 1, books.size)
        monoPaint.textSize = 10f * scaledDensity
        monoPaint.color = mutedColor
        val labelWidth = monoPaint.measureText(label)
        val centerY = prevRect.top + dp(counterHeightDp) / 2f
        val right = width - dp(6f)
        nextRect.set(right - dp(30f), prevRect.top, right, prevRect.bottom)
        val labelRight = nextRect.left - dp(10f)
        val labelLeft = labelRight - labelWidth
        prevRect.set(
            labelLeft - dp(12f) - dp(30f), nextRect.top,
            labelLeft - dp(12f), nextRect.bottom
        )
        canvas.drawText(
            label,
            labelLeft,
            centerY + monoPaint.textSize * 0.35f,
            monoPaint
        )
        if (books.size > 1) {
            drawChevron(canvas, (prevRect.left + prevRect.right) / 2f, centerY, false)
            drawChevron(canvas, (nextRect.left + nextRect.right) / 2f, centerY, true)
        }
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, right: Boolean) {
        val size = dp(4.4f)
        val dir = if (right) 1f else -1f
        strokePaint.color = mutedColor
        strokePaint.strokeWidth = dp(1.4f)
        tempPath.reset()
        tempPath.moveTo(cx - size * dir, cy - size)
        tempPath.lineTo(cx + size * dir, cy)
        tempPath.lineTo(cx - size * dir, cy + size)
        canvas.drawPath(tempPath, strokePaint)
    }

    // ------------------------------------------------------------------ 触感

    /**
     * 焦点每跨过一本书就轻振一次：手指拖动、惯性滑行、松手吸附都走这里，
     * 因为它们的共同点是都会改变 [focus] 的取整值。
     */
    private fun tickIfLaneChanged() {
        val lane = focus.roundToInt()
        if (lane == lastHapticLane) return
        lastHapticLane = lane
        // 滑到新的一本时，把它左右几本的封面补上（窗口加载，数量设很大也不会一次性解码几百张）
        requestCovers()
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return
        }
        // vibrate() 是同步 binder 调用，放在 UI 线程会让快速横滑掉帧，扔到单线程里排队
        hapticExecutor.execute {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(
                        VibrationEffect.createOneShot(HAPTIC_TICK_MS, HAPTIC_TICK_AMPLITUDE)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(HAPTIC_TICK_MS)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 手势

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (books.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                settleAnimator?.cancel()
                // 只挡住书架外层那个横向容器（主页的 ViewPager），
                // RecyclerView 先留着，竖滑时还要靠它滚列表
                disallowAncestors(true, includeList = false)
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                longPressFired = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                // 手指一动就不是长按了（竖滑交给 RecyclerView 去滚列表）
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    removeCallbacks(longPressRunnable)
                }
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    // 确认是横向拖动：连 RecyclerView 一起锁住，避免竖向偏移让它抢走手势
                    disallowAncestors(true, includeList = true)
                }
                if (dragging) {
                    // 不设边界：focus 可以一直往外走，绘制时按 books.size 取模，形成循环书架
                    focus -= (event.x - lastX) / dp(pitchDp)
                    lastX = event.x
                    invalidate()
                    tickIfLaneChanged()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    velocityTracker?.computeCurrentVelocity(1000, maximumVelocity.toFloat())
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    if (abs(velocityX) > minimumVelocity) {
                        fling(velocityX)
                    } else {
                        settleTo(focus.roundToInt())
                    }
                } else if (!longPressFired) {
                    handleTap(event.x, event.y)
                }
                disallowAncestors(false, includeList = true)
                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                disallowAncestors(false, includeList = true)
                velocityTracker?.recycle()
                velocityTracker = null
                if (dragging) settleTo(focus.roundToInt())
                dragging = false
            }
        }
        return true
    }

    /**
     * 请求祖先不要拦截手势。
     *
     * [includeList] = false 时只拦 RecyclerView 之上的祖先（主页 ViewPager 等），
     * 列表本身还能在竖滑时接管；一旦确认是横向拖动，就要把 RecyclerView 也算进去：
     * 否则手指带一点上下偏移，列表会按竖向滚动抢走手势，表现就是「滑几本就卡住」。
     */
    private fun disallowAncestors(disallow: Boolean, includeList: Boolean) {
        var ancestor: android.view.ViewParent? = parent
        if (!includeList) {
            while (ancestor != null && ancestor !is androidx.recyclerview.widget.RecyclerView) {
                ancestor = ancestor.parent
            }
            ancestor = ancestor?.parent
        }
        while (ancestor != null) {
            ancestor.requestDisallowInterceptTouchEvent(disallow)
            ancestor = ancestor.parent
        }
    }

    private fun handleTap(x: Float, y: Float) {
        if (prevRect.contains(x, y)) {
            settleTo(focus.roundToInt() - 1)
            return
        }
        if (nextRect.contains(x, y)) {
            settleTo(focus.roundToInt() + 1)
            return
        }
        val current = books.getOrNull(focusedIndex)
        if (titleRect.contains(x, y)) {
            current?.let { onBookClick?.invoke(it) }
            return
        }
        val lane = hitLane(x, y) ?: return
        if (abs(lane - focus) >= 0.03f) {
            settleTo(lane.roundToInt())
        } else {
            current?.let { onBookClick?.invoke(it) }
        }
    }

    private fun hitLane(x: Float, y: Float): Float? {
        val radius = ArchiveShelfGeometry.laneRadius(contentWidthDp, coverDp, spineDp, pitchDp)
        val poolCenter = floor(focus).toInt()
        val pointX = x / density
        val pointY = y / density
        for (lane in (poolCenter + radius + 1) downTo (poolCenter - radius)) {
            val localX = pointX - laneOriginX(lane)
            val localY = pointY - laneOriginY(lane) + coverDp * 0.30f
            if (ArchiveShelfGeometry.contains(localX, localY, spineDp, coverDp, heightDp)) {
                return lane.toFloat()
            }
        }
        return null
    }

    private fun fling(velocityX: Float) {
        val start = (focus * dp(pitchDp)).roundToInt()
        // 边界给一个很大的值等价于「不设边界」，滑到最后会继续滑到第一本
        scroller.fling(
            start, 0, (-velocityX).toInt(), 0,
            -FLING_BOUND, FLING_BOUND, 0, 0
        )
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            focus = scroller.currX / dp(pitchDp)
            invalidate()
            tickIfLaneChanged()
            if (scroller.isFinished) {
                settleTo(focus.roundToInt())
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    private fun settleTo(lane: Int) {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(focus, lane.toFloat()).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                focus = it.animatedValue as Float
                invalidate()
                tickIfLaneChanged()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    normalizeFocus()
                }
            })
            start()
        }
    }

    /**
     * 把游标折回 0..count-1。
     *
     * 只在停在整本上时调用：此时每个 lane 对应的书按取模不变，画面完全等价，
     * 不会出现跳动；长期滑动也不会让 focus 涨到浮点精度变差。
     */
    private fun normalizeFocus() {
        if (books.isEmpty()) {
            focus = 0f
            lastHapticLane = 0
            return
        }
        focus = ArchiveShelfGeometry.bookIndex(focus.roundToInt(), books.size).toFloat()
        lastHapticLane = focus.roundToInt()
        invalidate()
    }

    // ------------------------------------------------------------------ 封面

    private fun requestCovers() {
        if (books.isEmpty() || coverDp <= 0f || heightDp <= 0f) return
        // 只有焦点附近的书会露封面（更远的只画纸面书脊），所以按窗口请求即可
        val center = focusedIndex
        for (offset in -COVER_PRELOAD_LANES..COVER_PRELOAD_LANES) {
            val index = ArchiveShelfGeometry.bookIndex(center + offset, books.size)
            val book = books.getOrNull(index) ?: continue
            if (covers.containsKey(book.bookUrl) || coverTargets.containsKey(book.bookUrl)) continue
            loadCover(book)
        }
    }

    private fun loadCover(book: Book) {
        val width = dp(coverDp).roundToInt().coerceAtLeast(1)
        val height = dp(heightDp).roundToInt().coerceAtLeast(1)
        val target = object : CustomTarget<Bitmap>(width, height) {
            override fun onResourceReady(
                resource: Bitmap,
                transition: Transition<in Bitmap>?
            ) {
                covers[book.bookUrl] = resource
                coverTargets.remove(book.bookUrl)
                invalidate()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                covers.remove(book.bookUrl)
                invalidate()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                coverTargets.remove(book.bookUrl)
            }
        }
        coverTargets[book.bookUrl] = target
        runCatching {
            // 和 CoverImageView 保持一致：先还原本地封面路径
            val coverPath = Restore.normalizeLocalCoverPath(book.getDisplayCover())
            ImageLoader.loadBitmap(context, coverPath)
                .override(width * 2, height * 2)
                .into(target)
        }.onFailure {
            coverTargets.remove(book.bookUrl)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPressRunnable)
        settleAnimator?.cancel()
        settleAnimator = null
        scroller.forceFinished(true)
        coverTargets.values.forEach { target ->
            runCatching { Glide.with(context).clear(target) }
        }
        coverTargets.clear()
    }

    // ------------------------------------------------------------------ 工具

    private fun dp(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val MAX_WIDTH_DP = 520f

        /** 书架下方书名（衬线）/ 作者 */
        const val TITLE_TEXT_SP = 10.6f
        const val AUTHOR_TEXT_SP = 8.4f

        /** 书脊竖排书名 */
        const val SPINE_TEXT_SP = 8.2f

        /**
         * 封面 `NO.001` 标签的左侧缩进（dp）。
         *
         * 相邻书间距 pitch = spine + 10dp，封面投影系数 0.66，所以平放的书只有约 10dp 宽的封面
         * 露在外面，其余被右邻书的书脊压住。缩进必须满足 `0.66 * 缩进 > 10`，这里取 26dp
         * （屏上约 17dp），非焦点书上的编号就被彻底盖住了；抽出后的书整张封面可见，编号正常显示。
         */
        const val LABEL_INSET_DP = 26f

        /** 焦点左右各预载几本的封面（远一点的只画纸面） */
        const val COVER_PRELOAD_LANES = 4

        /** 封面位图 LRU 上限，数量设大时不至于把内存吃满 */
        const val COVER_CACHE_SIZE = 24

        /** 惯性滑行用的「无限」边界（px），够大即可，实际永远滑不到 */
        const val FLING_BOUND = 1_000_000

        const val HAPTIC_TICK_MS = 15L
        const val HAPTIC_TICK_AMPLITUDE = 150
    }
}
