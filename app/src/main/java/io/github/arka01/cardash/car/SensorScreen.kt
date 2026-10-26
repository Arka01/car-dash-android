package io.github.arka01.cardash.car

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.arka01.cardash.R
import io.github.arka01.cardash.Report
import io.github.arka01.cardash.obd.ObdSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

/**
 * Main car screen. As a "navigation" app we get the map area (the surface) to draw on
 * ourselves; we fill it with the same text lines as the phone, green on black.
 */
class SensorScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val ui = MainScope()
    private var refresh: Job? = null
    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var shownRunning = ObdSession.isRunning

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Color.rgb(0x33, 0xFF, 0x66)
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refresh = ui.launch {
                    while (isActive) {
                        draw()
                        if (ObdSession.isRunning != shownRunning) invalidate()   // Connect <-> Stop label
                        delay(250)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                refresh?.cancel()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                ui.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        shownRunning = ObdSession.isRunning
        val connect = Action.Builder()
            .setTitle(if (shownRunning) "Stop" else "Connect")
            .setOnClickListener { toggleConnection(carContext); invalidate() }
            .build()
        val listView = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_list)).build())
            .setOnClickListener { screenManager.push(SensorListScreen(carContext)) }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(connect).addAction(listView).build())
            .build()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    private fun draw() {
        val container = surface ?: return
        val target = container.surface ?: return
        if (!target.isValid) return
        val canvas = try {
            target.lockCanvas(null)
        } catch (e: Exception) {
            return
        }
        try {
            canvas.drawColor(Color.BLACK)
            val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, container.width, container.height)
            drawLines(canvas, area, Report.lines(ObdSession.state.value))
        } finally {
            target.unlockCanvasAndPost(canvas)
        }
    }

    /** Picks 1-3 columns and the biggest text size that fits all lines into the area. */
    private fun drawLines(canvas: Canvas, area: Rect, lines: List<String>) {
        val pad = 8f
        paint.textSize = 100f
        val charWidth = paint.measureText("M") / 100f
        val maxChars = maxOf(20, lines.maxOfOrNull { it.length } ?: 0)
        var bestSize = 0f
        var bestColumns = 1
        for (columns in 1..3) {
            val rows = ceil(lines.size / columns.toFloat())
            val byHeight = (area.height() - 2 * pad) / (rows * 1.2f)
            val byWidth = (area.width() / columns - 2 * pad) / (maxChars * charWidth)
            val size = min(byHeight, byWidth)
            if (size > bestSize) {
                bestSize = size
                bestColumns = columns
            }
        }
        paint.textSize = bestSize.coerceIn(8f, 40f)
        val lineHeight = paint.textSize * 1.2f
        val rowsPerColumn = ceil(lines.size / bestColumns.toFloat()).toInt().coerceAtLeast(1)
        val columnWidth = area.width() / bestColumns.toFloat()
        lines.forEachIndexed { i, line ->
            val x = area.left + pad + (i / rowsPerColumn) * columnWidth
            val y = area.top + pad + (i % rowsPerColumn + 1) * lineHeight
            canvas.drawText(line, x, y, paint)
        }
    }
}
