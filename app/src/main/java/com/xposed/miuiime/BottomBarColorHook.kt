package com.xposed.miuiime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.github.kyuubiran.ezxhelper.utils.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Feeds the MIUI toolbar with the colour the IME paints at the bottom of its own keyboard.
 *
 * An IME that publishes the colour through [android.view.Window.setNavigationBarColor] is already
 * served by [MainHook.setPhraseBgColor], this only covers the ones that draw the navigation bar
 * area themselves and never touch the window. It is driven by the hooks MainHook already owns, so
 * no method ends up hooked twice, and it reads framework state only, no IME is special cased.
 */
internal object BottomBarColorHook {
    private val bounds = Rect()
    private val location = IntArray(2)
    private val probeY = IntArray(3)
    private val probeX = IntArray(3)
    private var customize: Method? = null
    private var active: Bottom? = null
    private var published = 0

    /**
     * @param injector the MIUI stub that owns `customizeBottomViewColor`
     */
    fun setup(injector: Class<*>) {
        if (customize != null) return
        customize = kotlin.runCatching {
            injector.getDeclaredMethod(
                "customizeBottomViewColor",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrElse {
            Log.e("Failed:Hook method customizeBottomViewColor")
            Log.e(it)
            return
        }
        Log.i("Success:Hook method customizeBottomViewColor")
    }

    /**
     * The IME handed a colour to its own window, [MainHook.setPhraseBgColor] takes it from there.
     * Reading the window back is not an option, setNavigationBarColor stopped writing the window
     * attributes for apps in Android 15.
     */
    fun onPublished(color: Int) {
        if (color != 0) published = color
    }

    /**
     * The MIUI toolbar is up, follow the keyboard from here on.
     */
    fun onBottomView(input: ViewGroup?) {
        if (customize == null || input == null) return
        kotlin.runCatching { start(input) }.onFailure { Log.e(it) }
    }

    private fun start(input: ViewGroup) {
        val existing = active
        if (existing != null && existing.owns(input)) return
        existing?.dispose()
        val bottom = Bottom(input)
        active = bottom
        bottom.start()
    }

    private class Bottom(input: ViewGroup) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val input = WeakReference(input)
        private var source = WeakReference<View>(null)
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var applied = 0
        private var pending = true
        private var sampled = 0L
        private var disposed = false

        fun owns(input: ViewGroup): Boolean = this.input.get() === input

        fun start() {
            val input = input.get() ?: return
            input.addOnAttachStateChangeListener(this)
            observe(input.viewTreeObserver)
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            input.get()?.removeOnAttachStateChangeListener(this)
            unobserve()
        }

        private fun observe(observer: ViewTreeObserver) {
            if (this.observer.get() === observer) return
            unobserve()
            observer.addOnPreDrawListener(this)
            this.observer = WeakReference(observer)
        }

        private fun unobserve() {
            val previous = observer.get()
            val current = input.get()?.viewTreeObserver
            if (previous?.isAlive == true) previous.removeOnPreDrawListener(this)
            // A detached observer may have been merged into the attached window.
            if (current !== previous && current?.isAlive == true) {
                current.removeOnPreDrawListener(this)
            }
            observer.clear()
            // The cached view is gone, so is the colour it carried.
            source.clear()
        }

        override fun onViewAttachedToWindow(view: View) = observe(view.viewTreeObserver)

        override fun onViewDetachedFromWindow(view: View) = unobserve()

        override fun onPreDraw(): Boolean {
            if (!disposed) kotlin.runCatching { apply() }.onFailure { Log.e(it) }
            return true
        }

        private fun apply() {
            val method = customize ?: return
            val input = input.get() ?: return
            if (input.width <= 0 || input.height <= 0) return
            val now = SystemClock.uptimeMillis()
            // The keyboard can be reskinned without any layout, so keep a slow poll as a safety
            // net. It runs on the draw thread of the IME, there is no cheaper signal for it.
            if (!pending && now - sampled < SAMPLE_INTERVAL) return
            pending = false
            sampled = now
            // The window hook already applied a colour the IME published, stay out of its way.
            if (published()) return
            val color = keyboardColor(input) ?: return
            if (color == applied) return
            val icon = -0x1 - color
            kotlin.runCatching {
                method.invoke(null, true, color, icon or -0x1000000, icon or 0x66000000)
            }.onSuccess {
                applied = color
                Log.i("Set the bottom view color to #%08X".format(color))
            }.onFailure {
                Log.e("Failed to set the bottom view color")
                Log.e(it)
            }
        }

        /**
         * Whether the IME handed a colour to its own window, in which case
         * [MainHook.setPhraseBgColor] already served it. A colour this hook put there itself does
         * not count, MIUI passes it on to the window behind our back.
         */
        private fun published(): Boolean {
            return published != 0 && published != applied
        }

        /**
         * The colour the keyboard ends on, which is what the toolbar has to blend into. A
         * background colour is free to read, an IME that paints its own background only shows it
         * on screen, so the bottom row of the input view is rendered into a bitmap as the last
         * resort.
         */
        private fun keyboardColor(input: ViewGroup): Int? {
            carrier(input)?.let { return opaqueColor(it) }
            return kotlin.runCatching { renderedColor(input) }.getOrNull()
        }

        /**
         * The view that carries the keyboard background, remembered between samples so that a
         * steady keyboard costs a single field read instead of a walk over the view tree.
         */
        private fun carrier(input: ViewGroup): View? {
            val cached = source.get()
            if (cached != null && cached.width * 5 >= input.width * 4) {
                opaqueColor(cached)?.let { return cached }
            }
            return search(input)?.also { source = WeakReference(it) }
        }

        /**
         * Walks up from the bottom of the input view and takes the first view that spans the
         * keyboard and has a colour of its own. Keys and popups never do, they would be the
         * wrong colour.
         */
        private fun search(input: ViewGroup): View? {
            val width = input.width
            val height = input.height
            input.getLocationInWindow(location)
            val left = location[0]
            val top = location[1]
            // Stay above the padding an IME reserves for the navigation bar area, it may paint
            // that strip on its own, the keyboard itself is what the toolbar has to match.
            probeY[0] = height - 2 - input.paddingBottom
            probeY[1] = height - 2
            probeY[2] = height / 2
            probeX[0] = 1
            probeX[1] = width - 2
            probeX[2] = width / 2
            for (i in probeY.indices) {
                val y = probeY[i]
                if (y < 0 || y >= height) continue
                for (j in probeX.indices) {
                    val x = probeX[j]
                    if (x < 0 || x >= width) continue
                    var view = deepest(input, left + x, top + y) ?: continue
                    while (true) {
                        if (view.width * 5 >= width * 4 && opaqueColor(view) != null) return view
                        if (view === input) break
                        view = view.parent as? View ?: break
                    }
                }
            }
            return null
        }

        private fun deepest(root: View, x: Int, y: Int): View? {
            var found: View? = null
            var current: View? = root
            while (current != null) {
                found = current
                if (current !is ViewGroup) break
                var next: View? = null
                for (i in 0 until current.childCount) {
                    val child = current.getChildAt(i) ?: continue
                    if (child.visibility != View.VISIBLE) continue
                    if (child.width == 0 || child.height == 0) continue
                    if (!child.getGlobalVisibleRect(bounds)) continue
                    if (bounds.contains(x, y)) {
                        next = child
                        break
                    }
                }
                current = next
            }
            return found
        }

        private fun opaqueColor(view: View): Int? {
            val color = (view.background as? ColorDrawable)?.color
                ?: view.backgroundTintList?.defaultColor
                ?: return null
            return if (Color.alpha(color) == 255) color else null
        }

        /**
         * Renders the last rows of the input view and returns the colour most of them are made
         * of. The keyboard is not guaranteed to end on a flat colour, a gradient or a background
         * image can reach the last row, and then this settles for the tone that dominates it
         * rather than for the exact one along the edge.
         */
        private fun renderedColor(input: ViewGroup): Int? {
            val width = input.width
            val height = input.height
            val rows = if (height > 2 * ROWS) ROWS else 1
            val bitmap = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(0f, -(height - rows).toFloat())
            input.draw(canvas)
            val counts = HashMap<Int, Int>(width)
            var best = 0
            var bestCount = 0
            for (y in 0 until rows) {
                for (x in 0 until width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.alpha(pixel) != 255) continue
                    val count = (counts[pixel] ?: 0) + 1
                    counts[pixel] = count
                    if (count > bestCount) {
                        bestCount = count
                        best = pixel
                    }
                }
            }
            bitmap.recycle()
            // A row split between many colours is no background at all, leave it to MIUI.
            return if (bestCount * 2 >= width) best else null
        }

        companion object {
            private const val ROWS = 3

            /**
             * A reskin is rare and takes seconds anyway, there is nothing to gain from looking
             * sooner. One sample costs about 1.5ms on the draw thread of the IME, this keeps it
             * well under a tenth of a percent of a core.
             */
            private const val SAMPLE_INTERVAL = 3000L
        }
    }
}
