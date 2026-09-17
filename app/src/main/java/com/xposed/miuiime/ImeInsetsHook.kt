package com.xposed.miuiime

import android.annotation.TargetApi
import android.graphics.Insets
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** Keeps the navigation safe area owned by the MIUI toolbar out of IME content. */
@TargetApi(30)
internal object ImeInsetsHook {
    internal class CallState<T : Any> {
        private val local = ThreadLocal<IdentityHashMap<Any, T>>()
        fun put(call: Any, value: T) {
            val state = local.get() ?: IdentityHashMap<Any, T>(4).also { local.set(it) }
            state[call] = value
        }
        fun remove(call: Any): T? = local.get()?.remove(call)
    }

    internal class WindowScope {
        private val local = ThreadLocal<Frame>()
        val current: Frame? get() = local.get()
        fun enter(frame: Frame?): Frame? = current.also { restore(frame) }
        fun restore(frame: Frame?) {
            if (frame == null) local.remove() else local.set(frame)
        }
    }

    internal class Frame(input: ViewGroup, bottom: ViewGroup) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        val input = WeakReference(input)
        val bottom = WeakReference(bottom)
        private var decor = WeakReference<View>(null)
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var appliedActive: Boolean? = null
        private var appliedContent = WeakReference<View>(null)
        private var invalidated = true
        private var refreshRequested = false
        private var disposed = false
        private var cachedSource: WindowInsets? = null
        private var cachedResult: WindowInsets? = null

        fun isActive(): Boolean {
            if (disposed) return false
            val input = input.get() ?: return false
            val bottom = bottom.get() ?: return false
            val root = input.parent as? LinearLayout ?: return false
            val params = bottom.layoutParams as? LinearLayout.LayoutParams ?: return false
            // inputArea is initially hidden while the host primes its metrics cache.
            return root.orientation == LinearLayout.VERTICAL && bottom.parent === root &&
                root.indexOfChild(bottom) == root.indexOfChild(input) + 1 &&
                bottom.visibility == View.VISIBLE && bottom.childCount > 0 &&
                params.height != 0 && params.weight == 0f
        }

        fun filterInsets(source: WindowInsets): WindowInsets {
            if (cachedSource === source) return cachedResult ?: source
            return withoutBottomSafeArea(source).also {
                cachedSource = source
                cachedResult = it
            }
        }

        fun start() {
            val input = input.get() ?: return
            input.addOnAttachStateChangeListener(this)
            bindWindow(input)
        }

        private fun bindWindow(input: View) {
            val root = input.rootView
            if (decor.get() !== root) {
                unbindWindow()
                windows[root]?.takeIf { it !== this }?.dispose()
                decor = WeakReference(root)
                windows[root] = this
                invalidated = true
            }
            removeObserver()
            input.viewTreeObserver.let {
                it.addOnPreDrawListener(this)
                observer = WeakReference(it)
            }
            refreshRequested = false
            invalidate()
        }

        private fun removeObserver() {
            val previous = observer.get()
            val current = input.get()?.viewTreeObserver
            if (previous?.isAlive == true) previous.removeOnPreDrawListener(this)
            // A detached observer may have been merged into the attached window.
            if (current !== previous && current?.isAlive == true) current.removeOnPreDrawListener(this)
            observer.clear()
        }

        private fun unbindWindow() {
            removeObserver()
            decor.get()?.let { if (windows[it] === this) windows.remove(it) }
            decor.clear()
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            unbindWindow()
            input.get()?.let {
                it.removeOnAttachStateChangeListener(this)
                if (frames[it] === this) frames.remove(it)
            }
            cachedSource = null
            cachedResult = null
        }

        override fun onViewAttachedToWindow(view: View) = bindWindow(view)

        override fun onViewDetachedFromWindow(view: View) {
            unbindWindow()
            invalidated = true
            refreshRequested = false
            cachedSource = null
            cachedResult = null
        }

        override fun onPreDraw(): Boolean = !refreshInsets()

        fun invalidate() {
            if (disposed) return
            invalidated = true
            if (refreshRequested) return
            refreshRequested = true
            (decor.get() ?: input.get()?.rootView)?.let {
                it.requestApplyInsets()
                it.requestLayout()
            }
        }

        fun refreshInsets(
            duringMeasure: Boolean = false,
            sourceProvider: (() -> WindowInsets?)? = null
        ): Boolean {
            if (disposed) return false
            val input = input.get() ?: return false
            val active = isActive()
            val content = input.getChildAt(0)
            if (!invalidated && appliedActive == active && appliedContent.get() === content) return false
            val needsDispatch = active || appliedActive == true
            // Stable pre-draw/measure callbacks neither read insets nor allocate.
            val source = if (needsDispatch) {
                (if (sourceProvider != null) sourceProvider() else bottom.get()?.rootWindowInsets)
                    ?: return false
            } else null
            appliedActive = active
            appliedContent = WeakReference(content)
            invalidated = false
            refreshRequested = false
            if (source == null) return false
            val root = decor.get() ?: input.rootView
            root.dispatchApplyWindowInsets(source)
            // Measurement already guarantees layout even at unchanged bounds.
            // A late ownership change needs one traversal before the next draw.
            if (!duringMeasure) root.requestLayout()
            return true
        }
    }

    private val frames = WeakHashMap<View, Frame>()
    private val windows = WeakHashMap<View, Frame>()
    private val managers = mutableSetOf<Class<*>>()
    private val appCallbacks = mutableSetOf<Method>()
    private val windowScope = WindowScope()
    private val previousScopes = CallState<Any>()
    private val originalInsets = CallState<WindowInsets>()
    private val noScope = Any()
    private var installed = false

    private fun enterScope(param: XC_MethodHook.MethodHookParam, frame: Frame?) {
        previousScopes.put(param, windowScope.enter(frame) ?: noScope)
    }

    private fun leaveScope(param: XC_MethodHook.MethodHookParam) {
        val previous = previousScopes.remove(param) ?: return
        windowScope.restore(if (previous === noScope) null else previous as Frame)
    }

    private fun install() {
        if (installed) return
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        try {
            val dispatchHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val frame = frames[param.thisObject] ?: return
                    if (!frame.isActive()) return
                    val original = param.args[0] as WindowInsets
                    val filtered = frame.filterInsets(original)
                    if (filtered === original) return
                    originalInsets.put(param, original)
                    param.args[0] = filtered
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val original = originalInsets.remove(param) ?: return
                    // Legacy sibling dispatch must not leak consumed content insets
                    // into the MIUI toolbar which owns the safe area.
                    if (!param.hasThrowable()) param.result = original
                }
            }
            hooks += XposedHelpers.findAndHookMethod(
                ViewGroup::class.java, "dispatchApplyWindowInsets", WindowInsets::class.java, dispatchHook
            )
            hooks += XposedHelpers.findAndHookMethod(
                ViewGroup::class.java, "dispatchWindowInsetsAnimationProgress",
                WindowInsets::class.java, List::class.java, dispatchHook
            )
            hooks += XposedHelpers.findAndHookMethod(View::class.java, "getRootWindowInsets", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val source = param.result as? WindowInsets ?: return
                    var view = param.thisObject as View
                    val windowFrame = windows[view]
                    if (windowFrame != null) {
                        if (windowScope.current === windowFrame && windowFrame.isActive()) {
                            param.result = windowFrame.filterInsets(source)
                        }
                        return
                    }
                    while (true) {
                        frames[view]?.let {
                            if (it.isActive()) param.result = it.filterInsets(source)
                            return
                        }
                        view = view.parent as? View ?: return
                    }
                }
            })
            val listenerRegistration = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (windows[param.thisObject] == null) return
                    val listener = param.args[0] ?: return
                    val contract = if (param.method.name == "setOnApplyWindowInsetsListener") {
                        View.OnApplyWindowInsetsListener::class.java
                    } else View.OnLayoutChangeListener::class.java
                    val callback = contract.declaredMethods.single()
                    hookAppCallback(listener.javaClass.getMethod(callback.name, *callback.parameterTypes), false)
                }
            }
            hooks += XposedHelpers.findAndHookMethod(
                View::class.java, "addOnLayoutChangeListener", View.OnLayoutChangeListener::class.java, listenerRegistration
            )
            hooks += XposedHelpers.findAndHookMethod(
                View::class.java, "setOnApplyWindowInsetsListener", View.OnApplyWindowInsetsListener::class.java, listenerRegistration
            )
            hooks += XposedHelpers.findAndHookMethod(
                InputMethodService::class.java, "showWindow", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        serviceFrame(param.thisObject as InputMethodService)?.invalidate()
                    }
                }
            )
            hooks += XposedHelpers.findAndHookMethod(InputMethodService::class.java, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    serviceFrame(param.thisObject as InputMethodService)?.dispose()
                }
            })
            val decorClass = XposedHelpers.findClass("com.android.internal.policy.DecorView", View::class.java.classLoader)
            val frameworkCall = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Host listeners may delegate to DecorView. The framework must
                    // continue to see the original window insets during that call.
                    if (windowScope.current != null) enterScope(param, null)
                    if (param.method.name == "onMeasure") {
                        windows[param.thisObject]?.refreshInsets(duringMeasure = true)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) = leaveScope(param)
            }
            hooks += XposedHelpers.findAndHookMethod(
                decorClass, "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, frameworkCall
            )
            hooks += XposedHelpers.findAndHookMethod(
                decorClass, "onApplyWindowInsets", WindowInsets::class.java, frameworkCall
            )
            installed = true
        } catch (error: Throwable) {
            hooks.forEach { it.unhook() }
            throw error
        }
    }

    private fun serviceFrame(service: InputMethodService): Frame? = windows[service.window?.window?.decorView]

    private fun hookAppCallback(method: Method, serviceCallback: Boolean) {
        // Keep platform listeners outside the host scope. Hook the actual callback
        // once without replacing listener identities or inspecting calling stacks.
        if (method.declaringClass.classLoader === View::class.java.classLoader || method in appCallbacks) return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val frame = if (serviceCallback) serviceFrame(param.thisObject as InputMethodService)
                    else windows[param.args[0]]
                if (frame != null) enterScope(param, frame)
            }

            override fun afterHookedMethod(param: MethodHookParam) = leaveScope(param)
        })
        appCallbacks.add(method)
    }

    private fun hookServiceCallbacks(service: InputMethodService) {
        val callbacks = arrayOf(
            InputMethodService::class.java.getMethod("onCreateInputView"),
            InputMethodService::class.java.getMethod("onStartInput", EditorInfo::class.java, Boolean::class.javaPrimitiveType),
            InputMethodService::class.java.getMethod("onStartInputView", EditorInfo::class.java, Boolean::class.javaPrimitiveType),
            InputMethodService::class.java.getMethod("onWindowShown")
        )
        for (callback in callbacks) {
            hookAppCallback(service.javaClass.getMethod(callback.name, *callback.parameterTypes), true)
        }
    }

    fun registerManager(manager: Class<*>) {
        if (Build.VERSION.SDK_INT < 30 || !managers.add(manager)) return
        manager.declaredMethods.filter {
            it.name == "addMiuiBottomView" && it.parameterTypes.size >= 6 &&
                ViewGroup::class.java.isAssignableFrom(it.parameterTypes[3]) &&
                ViewGroup::class.java.isAssignableFrom(it.parameterTypes[5])
        }.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val input = param.args[3] as? ViewGroup ?: return
                    val bottom = param.args[5] as? ViewGroup ?: return
                    runCatching {
                        // No process-wide View hooks until a real MIUI input frame exists.
                        install()
                        val existing = frames[input]
                        if (existing != null && existing.bottom.get() === bottom) {
                            existing.invalidate()
                        } else {
                            existing?.dispose()
                            val frame = Frame(input, bottom)
                            frames[input] = frame
                            frame.start()
                        }
                        param.args.filterIsInstance<InputMethodService>().firstOrNull()?.let(::hookServiceCallbacks)
                    }.onFailure { XposedBridge.log(it) }
                }
            })
        }
    }

    private val safeAreaTypes by lazy {
        intArrayOf(WindowInsets.Type.navigationBars(), WindowInsets.Type.systemGestures(),
            WindowInsets.Type.mandatorySystemGestures(), WindowInsets.Type.tappableElement())
    }

    internal fun withoutBottomSafeArea(source: WindowInsets): WindowInsets {
        var builder: WindowInsets.Builder? = null
        for (type in safeAreaTypes) {
            val current = source.getInsets(type)
            val maximum = source.getInsetsIgnoringVisibility(type)
            if (current.bottom == 0 && maximum.bottom == 0) continue
            val target = builder ?: WindowInsets.Builder(source).also { builder = it }
            target.setInsets(type, Insets.of(current.left, current.top, current.right, 0))
            target.setInsetsIgnoringVisibility(type, Insets.of(maximum.left, maximum.top, maximum.right, 0))
        }
        return builder?.build() ?: source
    }
}
