package com.east.time.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 两根可点的"管子"：
 *
 * ```
 *   时  [0][1][2][3] … [23]        24 格，一格一小时
 *   分  [0][1][2] … [29]           前 30 分钟，一格一分钟
 *       [30][31] … [59]            后 30 分钟，拆两行是为了让每格够大
 * ```
 *
 * 交互：
 *  - 点「时」的某一格 → 选中该小时，下面的分钟管显示这一小时
 *  - 再点一次同一格 → 取消选中
 *  - 手指按在「分」的格上（可拖动）→ 回调该分钟是哪个 App，用于实时显示名称
 *
 * 每格的颜色 = 该格时间里**占时最长**的那个 App（见 [com.east.time.analyze.Bucketizer]）。
 * 灰色 = 那段时间没有 App 在前台。
 */
class TimeGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 24 格，每格一小时。null = 该小时没有前台活动 */
    var hourCells: List<String?> = List(HOURS) { null }
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 每格是否以**黑屏（息屏/锁屏）**为主，和 [hourCells] 一一对应。
     *
     * 这是和"数据没记到"的关键区分：两者在格子里都是"没有 App 前台"，
     * 但黑屏是设备正常关着，而空背景可能是采集断了。后者是故障信号，
     * 不该被伪装成前者。
     */
    var lockedHourCells: List<Boolean> = List(HOURS) { false }
        set(value) {
            field = value
            invalidate()
        }

    /** 选中小时的 60 个分钟格。未选中任何小时时为空 */
    var minuteCells: List<String?> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** 选中小时的 60 个分钟格是否黑屏 */
    var lockedMinuteCells: List<Boolean> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** -1 = 未选中，下面显示今天总体情况 */
    var selectedHour: Int = -1
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 选中/取消选中某小时。取消时传 -1 */
    var onHourSelected: ((Int) -> Unit)? = null

    /** 手指在分钟格上移动。minuteOfHour ∈ 0..59 */
    var onMinuteTouched: ((minuteOfHour: Int, pkg: String?) -> Unit)? = null

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.BLACK
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF777777.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF999999.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var topPad = 0f

    /** 布局：[时行][分两行][标签]。每次 onSizeChanged 重算 */
    private var hourTop = 0f
    private var hourBottom = 0f
    private var minTopA = 0f
    private var minBottomA = 0f
    private var minTopB = 0f
    private var minBottomB = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        topPad = 6f * density
        val labelH = 16f * density
        val gap = 6f * density

        val usable = h - topPad - labelH - gap
        // 时行比分行高：时格里要放得下两位数字
        val hourH = usable * 0.42f
        val minH = (usable - hourH) / 2f

        hourTop = topPad
        hourBottom = hourTop + hourH
        minTopA = hourBottom + gap
        minBottomA = minTopA + minH - 1f
        minTopB = minBottomA + 2f
        minBottomB = minTopB + minH - 1f

        textPaint.textSize = hourH * 0.42f
        labelPaint.textSize = 10f * density
        hintPaint.textSize = 13f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()

        // ---- 时管 ----
        val hw = w / HOURS
        for (i in 0 until HOURS) {
            val left = i * hw
            drawCell(
                canvas, left, hourTop, left + hw - 2f, hourBottom,
                hourCells.getOrNull(i), lockedHourCells.getOrElse(i) { false },
            )
            // 时格够宽（1080px 下约 45px），直接把小时数字写进去
            canvas.drawText(
                i.toString(),
                left + hw / 2f,
                hourTop + (hourBottom - hourTop) / 2f + textPaint.textSize / 3f,
                textPaint,
            )
            if (i == selectedHour) {
                rect.set(left + 2f, hourTop, left + hw - 2f, hourBottom)
                canvas.drawRoundRect(rect, 4f, 4f, selPaint)
            }
        }

        // ---- 分管 ----
        if (minuteCells.isEmpty()) {
            canvas.drawText(
                "点上面选一个小时",
                w / 2f,
                (minTopA + minBottomB) / 2f,
                hintPaint,
            )
        } else {
            val mw = w / MIN_PER_ROW
            for (m in 0 until MINUTES) {
                val row = m / MIN_PER_ROW          // 0 = 前 30 分钟, 1 = 后 30 分钟
                val col = m % MIN_PER_ROW
                val left = col * mw
                val top = if (row == 0) minTopA else minTopB
                val bottom = if (row == 0) minBottomA else minBottomB
                drawCell(
                    canvas, left, top, left + mw - 2f, bottom,
                    minuteCells.getOrNull(m), lockedMinuteCells.getOrElse(m) { false },
                )
            }
            // 每 10 分钟标一个刻度，否则 60 个格子分不清哪格是几分
            for (m in 0 until MINUTES step 10) {
                val row = m / MIN_PER_ROW
                val col = m % MIN_PER_ROW
                val y = if (row == 0) minBottomA else minBottomB
                canvas.drawText((m % 60).toString(), col * mw + mw / 2f, y + 13f, labelPaint)
            }
        }
    }

    private fun drawCell(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float,
        pkg: String?, locked: Boolean,
    ) {
        cellPaint.color = when {
            pkg != null -> AppColors.of(pkg)      // 有 App 在前台，优先显示它
            locked -> AppColors.LOCKED            // 黑屏（息屏/锁屏）
            else -> AppColors.EMPTY               // 没信息：可能是切应用的瞬间，也可能是数据断了
        }
        rect.set(l, t, r, b)
        canvas.drawRoundRect(rect, 3f, 3f, cellPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val y = event.y
                when {
                    y in hourTop..hourBottom -> {
                        // 只在按下时切换选中，拖动经过时格不反复改选
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val i = (event.x / (width / HOURS.toFloat())).toInt()
                            if (i in 0 until HOURS) {
                                selectedHour = if (selectedHour == i) -1 else i
                                onHourSelected?.invoke(selectedHour)
                            }
                        }
                    }
                    y in minTopA..minBottomA || y in minTopB..minBottomB -> {
                        if (minuteCells.isNotEmpty()) {
                            val row = if (y <= minBottomA) 0 else 1
                            val col = (event.x / (width / MIN_PER_ROW.toFloat())).toInt()
                                .coerceIn(0, MIN_PER_ROW - 1)
                            val m = row * MIN_PER_ROW + col
                            onMinuteTouched?.invoke(m, minuteCells.getOrNull(m))
                        }
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val HOURS = 24
        const val MINUTES = 60

        /** 分钟管拆两行，每行 30 格 —— 一格太细就没法点也没法看 */
        const val MIN_PER_ROW = 30
    }
}
