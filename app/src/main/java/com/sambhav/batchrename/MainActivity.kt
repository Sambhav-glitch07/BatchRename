package com.sambhav.batchrename

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK_FILES = 4102
        const val PREFS_NAME = "batchrename_prefs"
        const val KEY_BASE_NAME = "base_name"
        const val KEY_START_AT = "start_at"
        const val STATE_URIS = "selected_uris"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    // ---------------------------------------------------------------------------------------------
    // Palette: warm charcoal with a coral-orange accent
    // ---------------------------------------------------------------------------------------------

    private val cBg = Color.parseColor("#1F1E1D")
    private val cBgDeep = Color.parseColor("#181716")
    private val cSurface = Color.parseColor("#272624")
    private val cSurfaceHi = Color.parseColor("#302E2B")
    private val cTile = Color.parseColor("#363330")
    private val cHair = Color.parseColor("#3A3835")
    private val cHairStrong = Color.parseColor("#4B4843")
    private val cInk = Color.parseColor("#F3EFE8")
    private val cMuted = Color.parseColor("#A8A39A")
    private val cFaint = Color.parseColor("#76716A")
    private val cAccent = Color.parseColor("#D97757")
    private val cAccentHi = Color.parseColor("#F0936A")
    private val cAccentLo = Color.parseColor("#BC5C3B")
    private val cAccentTint = Color.parseColor("#33D97757")
    private val cAccentLine = Color.parseColor("#66D97757")
    private val cOnAccent = Color.parseColor("#1F1E1D")
    private val cError = Color.parseColor("#EE7D74")
    private val cRipple = Color.parseColor("#26FFFFFF")
    private val cRippleDark = Color.parseColor("#33000000")

    private val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)
    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    private val monoBold: Typeface = Typeface.create("monospace", Typeface.BOLD)

    // ---------------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------------

    private val ui = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val thumbWorker: ExecutorService = Executors.newFixedThreadPool(2)
    private lateinit var prefs: SharedPreferences

    private val items = ArrayList<Item>()
    private var busy = false
    private var curBase = ""
    private var curStart = 1L
    private var pulse: ObjectAnimator? = null

    // Views
    private lateinit var chipText: TextView
    private lateinit var chipDot: View
    private lateinit var pickCard: LinearLayout
    private lateinit var pickTitleView: TextView
    private lateinit var pickMetaView: TextView
    private lateinit var pickPill: TextView
    private lateinit var baseEdit: EditText
    private lateinit var startEdit: EditText
    private lateinit var previewView: TextView
    private lateinit var clearBtn: TextView
    private lateinit var orderHintView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyState: LinearLayout
    private lateinit var emptySub: TextView
    private lateinit var statusText: TextView
    private lateinit var shareBtn: TextView
    private lateinit var renameBtn: TextView
    private lateinit var adapter: FileAdapter

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        buildUi()
        configureSystemBars()

        baseEdit.setText(prefs.getString(KEY_BASE_NAME, "") ?: "")
        startEdit.setText(prefs.getString(KEY_START_AT, "1") ?: "1")
        attachWatchers()
        refreshUi(true)

        val saved = savedInstanceState?.getStringArrayList(STATE_URIS)
        if (saved != null && saved.isNotEmpty()) {
            val uris = ArrayList<Uri>()
            for (s in saved) uris.add(Uri.parse(s))
            addUris(uris)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val uris = ArrayList<String>()
        for (item in items) uris.add(item.uri.toString())
        outState.putStringArrayList(STATE_URIS, uris)
    }

    override fun onDestroy() {
        super.onDestroy()
        pulse?.cancel()
        // Graceful: lets a rename that is already running finish instead of interrupting it.
        worker.shutdown()
        thumbWorker.shutdownNow()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FILES || resultCode != RESULT_OK || data == null) return

        // Preserve exactly the order in which the picker returns the selection.
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val u = clip.getItemAt(i).uri
                if (u != null) uris.add(u)
            }
        } else {
            val single = data.data
            if (single != null) uris.add(single)
        }
        addUris(uris)
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing helpers
    // ---------------------------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(v: Float): Float = v * resources.displayMetrics.density

    private fun hair(): Int = maxOf(1, dp(1) / 2)

    private fun lp(
        width: Int,
        height: Int,
        weight: Float = 0f,
        left: Int = 0,
        top: Int = 0,
        right: Int = 0,
        bottom: Int = 0
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(width, height, weight)
        p.setMargins(left, top, right, bottom)
        return p
    }

    private fun shape(
        fill: Int,
        radiusDp: Int,
        strokeColor: Int = Color.TRANSPARENT,
        strokePx: Int = 0,
        dashed: Boolean = false
    ): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dpf(radiusDp.toFloat())
        if (strokePx > 0) {
            if (dashed) d.setStroke(strokePx, strokeColor, dpf(5f), dpf(5f)) else d.setStroke(strokePx, strokeColor)
        }
        return d
    }

    private fun gradient(
        c1: Int,
        c2: Int,
        radiusDp: Int,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR
    ): GradientDrawable {
        val d = GradientDrawable(orientation, intArrayOf(c1, c2))
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dpf(radiusDp.toFloat())
        return d
    }

    private fun oval(fill: Int, strokeColor: Int = Color.TRANSPARENT, strokePx: Int = 0, dashed: Boolean = false): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(fill)
        if (strokePx > 0) {
            if (dashed) d.setStroke(strokePx, strokeColor, dpf(4f), dpf(5f)) else d.setStroke(strokePx, strokeColor)
        }
        return d
    }

    private fun glow(centerX: Float, centerY: Float, radiusDp: Float, color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT)
        d.setGradientRadius(dpf(radiusDp))
        d.setGradientCenter(centerX, centerY)
        d.setColors(intArrayOf(color, color and 0x00FFFFFF))
        return d
    }

    private fun ripple(content: Drawable, rippleColor: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    private fun makeText(text: CharSequence, sizeSp: Float, color: Int, face: Typeface): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        t.typeface = face
        t.includeFontPadding = false
        return t
    }

    private fun eyebrow(text: String, color: Int = cFaint): TextView {
        val t = makeText(text.uppercase(Locale.ROOT), 10.5f, color, monoBold)
        t.letterSpacing = 0.14f
        return t
    }

    private fun textAction(text: String): TextView {
        val t = makeText(text, 12f, cAccent, sansMedium)
        t.setPadding(dp(10), dp(10), dp(10), dp(10))
        t.isClickable = true
        t.isFocusable = true
        t.background = ripple(shape(Color.TRANSPARENT, 10), cAccentTint)
        return t
    }

    private fun control(label: String): TextView {
        val t = makeText(label, 17f, cMuted, sans)
        t.gravity = Gravity.CENTER
        t.isClickable = true
        t.isFocusable = true
        t.background = ripple(shape(Color.TRANSPARENT, 10), cRipple)
        return t
    }

    private fun button(text: String, primary: Boolean): TextView {
        val b = makeText(text, 14f, if (primary) cOnAccent else cInk, sansMedium)
        b.gravity = Gravity.CENTER
        b.letterSpacing = 0.02f
        b.minHeight = dp(54)
        b.setPadding(dp(14), dp(14), dp(14), dp(14))
        b.isClickable = true
        b.isFocusable = true
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.background = if (primary) {
            ripple(gradient(cAccentHi, cAccentLo, 16), cRippleDark)
        } else {
            ripple(shape(cSurfaceHi, 16, cHairStrong, hair()), cRipple)
        }
        return b
    }

    private fun styleEdit(e: EditText, hint: String, sizeSp: Float, face: Typeface) {
        e.background = null
        e.setPadding(0, dp(6), 0, dp(4))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        e.setTextColor(cInk)
        e.setHintTextColor(cFaint)
        e.hint = hint
        e.typeface = face
        e.highlightColor = cAccentLine
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.includeFontPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cursor = GradientDrawable()
            cursor.setColor(cAccent)
            cursor.setSize(dp(2), dp(26))
            e.setTextCursorDrawable(cursor)
        }
        e.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                v.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun isIllegalChar(c: Char): Boolean = c < ' ' || "/\\:*?\"<>|".indexOf(c) >= 0

    // ---------------------------------------------------------------------------------------------
    // UI construction
    // ---------------------------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = LayerDrawable(
            arrayOf<Drawable>(
                ColorDrawable(cBg),
                glow(0.92f, 0.0f, 380f, Color.parseColor("#3DD97757")),
                glow(0.0f, 1.0f, 320f, Color.parseColor("#1AD97757"))
            )
        )
        root.isFocusableInTouchMode = true

        // ---- Scrolling header (lives inside the list so the whole page scrolls together) ----
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(20), dp(18), dp(20), dp(6))

        // Brand row
        val brandRow = LinearLayout(this)
        brandRow.orientation = LinearLayout.HORIZONTAL
        brandRow.gravity = Gravity.CENTER_VERTICAL

        val mark = View(this)
        mark.background = gradient(cAccentHi, cAccentLo, 2)
        mark.rotation = 45f
        brandRow.addView(mark, lp(dp(10), dp(10), left = dp(3), right = dp(13)))
        brandRow.addView(eyebrow("BatchRename", cMuted), lp(0, WRAP, 1f))

        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.setPadding(dp(12), dp(7), dp(13), dp(7))
        chip.background = shape(cSurface, 20, cHair, hair())
        chipDot = View(this)
        chipDot.background = oval(cAccent)
        chip.addView(chipDot, lp(dp(6), dp(6), right = dp(8)))
        chipText = makeText("READY", 10f, cInk, monoBold)
        chipText.letterSpacing = 0.14f
        chip.addView(chipText, lp(WRAP, WRAP))
        brandRow.addView(chip, lp(WRAP, WRAP))
        header.addView(brandRow, lp(MATCH, WRAP))

        val pulseAnim = ObjectAnimator.ofFloat(chipDot, View.ALPHA, 1f, 0.2f)
        pulseAnim.duration = 1300
        pulseAnim.repeatCount = ValueAnimator.INFINITE
        pulseAnim.repeatMode = ValueAnimator.REVERSE
        pulseAnim.start()
        pulse = pulseAnim

        // Hero
        val hero = SpannableString("Name every file,\nin sequence.")
        val heroStart = hero.toString().indexOf("in sequence.")
        hero.setSpan(ForegroundColorSpan(cAccent), heroStart, hero.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val title = makeText(hero, 38f, cInk, serif)
        title.letterSpacing = -0.02f
        title.setLineSpacing(0f, 1.02f)
        header.addView(title, lp(MATCH, WRAP, top = dp(34)))

        val subtitle = makeText(
            "Pick files with the Android Files app, set one base name, and let every file fall into numbered order.",
            14f, cMuted, sansLight
        )
        subtitle.setLineSpacing(0f, 1.25f)
        header.addView(subtitle, lp(MATCH, WRAP, top = dp(14)))

        // 01 / Source
        header.addView(eyebrow("01 / Source", cAccent), lp(WRAP, WRAP, top = dp(34)))

        pickCard = LinearLayout(this)
        pickCard.orientation = LinearLayout.HORIZONTAL
        pickCard.gravity = Gravity.CENTER_VERTICAL
        pickCard.setPadding(dp(16), dp(16), dp(14), dp(16))
        pickCard.isClickable = true
        pickCard.isFocusable = true
        pickCard.setOnClickListener { if (!busy) pickFiles() }

        val plus = makeText("+", 26f, cOnAccent, sansLight)
        plus.gravity = Gravity.CENTER
        plus.background = gradient(cAccentHi, cAccentLo, 16)
        pickCard.addView(plus, lp(dp(48), dp(48)))

        val pickCol = LinearLayout(this)
        pickCol.orientation = LinearLayout.VERTICAL
        pickTitleView = makeText("Choose your files", 19f, cInk, serif)
        pickTitleView.maxLines = 1
        pickTitleView.ellipsize = TextUtils.TruncateAt.END
        pickCol.addView(pickTitleView, lp(MATCH, WRAP))
        pickMetaView = makeText("", 12.5f, cMuted, sans)
        pickMetaView.maxLines = 2
        pickMetaView.ellipsize = TextUtils.TruncateAt.END
        pickCol.addView(pickMetaView, lp(MATCH, WRAP, top = dp(5)))
        pickCard.addView(pickCol, lp(0, WRAP, 1f, left = dp(14), right = dp(8)))

        pickPill = makeText("Browse", 12.5f, cAccent, sansMedium)
        pickPill.setPadding(dp(14), dp(9), dp(14), dp(9))
        pickPill.background = shape(cAccentTint, 20, cAccentLine, hair())
        pickCard.addView(pickPill, lp(WRAP, WRAP))

        header.addView(pickCard, lp(MATCH, WRAP, top = dp(12)))

        // 02 / Naming
        header.addView(eyebrow("02 / Naming", cAccent), lp(WRAP, WRAP, top = dp(28)))

        val nameCard = LinearLayout(this)
        nameCard.orientation = LinearLayout.VERTICAL
        nameCard.setPadding(dp(18), dp(16), dp(18), dp(16))
        nameCard.background = shape(cSurface, 22, cHair, hair())

        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.HORIZONTAL

        val baseCol = LinearLayout(this)
        baseCol.orientation = LinearLayout.VERTICAL
        baseCol.addView(eyebrow("Base name"), lp(WRAP, WRAP))
        baseEdit = EditText(this)
        styleEdit(baseEdit, "Patna Raid", 22f, serif)
        baseEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        baseEdit.filters = arrayOf<InputFilter>(
            InputFilter { source, start, end, _, _, _ ->
                val sb = StringBuilder()
                var changed = false
                for (i in start until end) {
                    val c = source[i]
                    if (isIllegalChar(c)) changed = true else sb.append(c)
                }
                if (changed) sb.toString() else null
            },
            InputFilter.LengthFilter(120)
        )
        baseCol.addView(baseEdit, lp(MATCH, WRAP, top = dp(6)))
        fields.addView(baseCol, lp(0, WRAP, 1f))

        val vDivider = View(this)
        vDivider.setBackgroundColor(cHair)
        fields.addView(vDivider, lp(hair(), MATCH, left = dp(14), right = dp(14)))

        val startCol = LinearLayout(this)
        startCol.orientation = LinearLayout.VERTICAL
        startCol.addView(eyebrow("Start at"), lp(WRAP, WRAP))
        val stepper = LinearLayout(this)
        stepper.orientation = LinearLayout.HORIZONTAL
        stepper.gravity = Gravity.CENTER_VERTICAL
        val minus = control("\u2212")
        minus.setOnClickListener { stepStart(-1) }
        startEdit = EditText(this)
        styleEdit(startEdit, "1", 19f, mono)
        startEdit.inputType = InputType.TYPE_CLASS_NUMBER
        startEdit.gravity = Gravity.CENTER
        startEdit.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        val plusStep = control("+")
        plusStep.setOnClickListener { stepStart(1) }
        stepper.addView(minus, lp(dp(30), dp(36)))
        stepper.addView(startEdit, lp(dp(44), WRAP))
        stepper.addView(plusStep, lp(dp(30), dp(36)))
        startCol.addView(stepper, lp(WRAP, WRAP, top = dp(4)))
        fields.addView(startCol, lp(WRAP, WRAP))

        nameCard.addView(fields, lp(MATCH, WRAP))

        val previewBox = LinearLayout(this)
        previewBox.orientation = LinearLayout.HORIZONTAL
        previewBox.gravity = Gravity.CENTER_VERTICAL
        previewBox.setPadding(dp(14), dp(12), dp(14), dp(12))
        previewBox.background = shape(cBgDeep, 14, cHair, hair())
        previewBox.addView(eyebrow("Preview", cFaint), lp(WRAP, WRAP))
        previewView = makeText("", 13f, cAccent, mono)
        previewView.maxLines = 1
        previewView.ellipsize = TextUtils.TruncateAt.MIDDLE
        previewBox.addView(previewView, lp(0, WRAP, 1f, left = dp(12)))
        nameCard.addView(previewBox, lp(MATCH, WRAP, top = dp(16)))

        header.addView(nameCard, lp(MATCH, WRAP, top = dp(12)))

        // 03 / Sequence
        val seqRow = LinearLayout(this)
        seqRow.orientation = LinearLayout.HORIZONTAL
        seqRow.gravity = Gravity.CENTER_VERTICAL
        seqRow.addView(eyebrow("03 / Sequence", cAccent), lp(WRAP, WRAP))
        orderHintView = makeText("", 12f, cFaint, sans)
        orderHintView.maxLines = 1
        orderHintView.ellipsize = TextUtils.TruncateAt.END
        seqRow.addView(orderHintView, lp(0, WRAP, 1f, left = dp(10)))
        clearBtn = textAction("Clear all")
        clearBtn.setOnClickListener { clearAll() }
        seqRow.addView(clearBtn, lp(WRAP, WRAP, right = dp(-10)))
        header.addView(seqRow, lp(MATCH, WRAP, top = dp(24)))

        // Empty state
        emptyState = LinearLayout(this)
        emptyState.orientation = LinearLayout.VERTICAL
        emptyState.gravity = Gravity.CENTER_HORIZONTAL
        emptyState.setPadding(dp(16), dp(30), dp(16), dp(24))

        val ring = FrameLayout(this)
        val ringOuter = View(this)
        ringOuter.background = oval(Color.TRANSPARENT, cHairStrong, maxOf(1, dp(1)), true)
        ring.addView(ringOuter, FrameLayout.LayoutParams(MATCH, MATCH))
        val ringInner = makeText("+", 24f, cAccent, sansLight)
        ringInner.gravity = Gravity.CENTER
        ringInner.background = oval(cAccentTint, cAccentLine, maxOf(1, dp(1)))
        ring.addView(ringInner, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        emptyState.addView(ring, lp(dp(96), dp(96)))

        val emptyTitle = makeText("Nothing queued yet", 22f, cInk, serif)
        emptyTitle.gravity = Gravity.CENTER
        emptyState.addView(emptyTitle, lp(MATCH, WRAP, top = dp(20)))
        emptySub = makeText("", 13.5f, cMuted, sansLight)
        emptySub.gravity = Gravity.CENTER
        emptySub.setLineSpacing(0f, 1.25f)
        emptyState.addView(emptySub, lp(MATCH, WRAP, top = dp(8)))
        header.addView(emptyState, lp(MATCH, WRAP, top = dp(6)))

        // ---- List ---------------------------------------------------------------------------
        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.TRANSPARENT)
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.isVerticalScrollBarEnabled = false
        listView.overScrollMode = View.OVER_SCROLL_NEVER
        listView.clipToPadding = false
        listView.setPadding(0, 0, 0, dp(20))
        listView.addHeaderView(header, null, false)
        adapter = FileAdapter()
        listView.adapter = adapter
        root.addView(listView, lp(MATCH, 0, 1f))

        // ---- Bottom dock --------------------------------------------------------------------
        val dock = LinearLayout(this)
        dock.orientation = LinearLayout.VERTICAL
        dock.background = gradient(cSurface, cBgDeep, 0, GradientDrawable.Orientation.TOP_BOTTOM)

        val dockLine = View(this)
        dockLine.background = gradient(Color.parseColor("#00D97757"), cAccentLine, 0, GradientDrawable.Orientation.LEFT_RIGHT)
        dock.addView(dockLine, lp(MATCH, hair()))

        statusText = makeText("", 12.5f, cMuted, sans)
        statusText.setPadding(dp(24), dp(14), dp(24), 0)
        statusText.setLineSpacing(0f, 1.2f)
        statusText.visibility = View.GONE
        dock.addView(statusText, lp(MATCH, WRAP))

        val buttonRow = LinearLayout(this)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        buttonRow.setPadding(dp(20), dp(14), dp(20), dp(16))

        shareBtn = button("Share to FTP Rush", false)
        shareBtn.setOnClickListener { shareAll() }
        buttonRow.addView(shareBtn, lp(0, WRAP, 1f, right = dp(6)))

        renameBtn = button("Rename", true)
        renameBtn.setOnClickListener { startRename() }
        buttonRow.addView(renameBtn, lp(0, WRAP, 1.1f, left = dp(6)))

        dock.addView(buttonRow, lp(MATCH, WRAP))
        root.addView(dock, lp(MATCH, WRAP))

        root.setOnApplyWindowInsetsListener { v, insets -> applyInsets(v, insets) }
        setContentView(root)
        root.requestApplyInsets()
        root.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun applyInsets(view: View, insets: WindowInsets): WindowInsets {
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            top = bars.top
            bottom = bars.bottom
        } else {
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
        }
        view.setPadding(0, top, 0, bottom)
        return insets
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.statusBarColor = cBg
        window.navigationBarColor = cBgDeep
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(0, mask)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun attachWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit()
                    .putString(KEY_BASE_NAME, baseEdit.text.toString())
                    .putString(KEY_START_AT, startEdit.text.toString())
                    .apply()
                // Light refresh only: keeps keyboard focus stable while typing.
                refreshUi(false)
            }
        }
        baseEdit.addTextChangedListener(watcher)
        startEdit.addTextChangedListener(watcher)
    }

    private fun stepStart(delta: Int) {
        val next = startNumber() + delta
        if (next < 0L) return
        startEdit.setText(next.toString())
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(baseEdit.windowToken, 0)
    }

    private fun showConfirm(title: String, message: String, positive: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(24), dp(24), dp(24), dp(20))
        card.background = shape(cSurface, 26, cHairStrong, hair())

        card.addView(eyebrow("Confirm", cAccent), lp(WRAP, WRAP))
        card.addView(makeText(title, 25f, cInk, serif), lp(MATCH, WRAP, top = dp(12)))
        val body = makeText(message, 14f, cMuted, sansLight)
        body.setLineSpacing(0f, 1.28f)
        card.addView(body, lp(MATCH, WRAP, top = dp(12)))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val cancel = button("Cancel", false)
        cancel.setOnClickListener { dialog.dismiss() }
        val ok = button(positive, true)
        ok.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        row.addView(cancel, lp(0, WRAP, 1f, right = dp(6)))
        row.addView(ok, lp(0, WRAP, 1f, left = dp(6)))
        card.addView(row, lp(MATCH, WRAP, top = dp(24)))

        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(400)), WRAP)
    }

    // ---------------------------------------------------------------------------------------------
    // Naming helpers
    // ---------------------------------------------------------------------------------------------

    private fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (c in raw) {
            if (!isIllegalChar(c)) sb.append(c)
        }
        return sb.toString().trim().trimStart('.')
    }

    private fun startNumber(): Long {
        val v = startEdit.text.toString().trim().toLongOrNull()
        return if (v == null || v < 0L) 1L else v
    }

    private fun extensionOf(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        for (compound in listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst")) {
            if (lower.endsWith(compound) && name.length > compound.length) {
                return name.substring(name.length - compound.length)
            }
        }
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot)
    }

    private fun targetNameFor(order: Int, item: Item): String =
        curBase + " " + (curStart + order) + extensionOf(item.name)

    private fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

    private fun formatSize(bytes: Long): String {
        if (bytes < 0L) return ""
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }

    // ---------------------------------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------------------------------

    private fun setStatus(message: String?, error: Boolean = false) {
        if (message.isNullOrEmpty()) {
            statusText.text = ""
            statusText.visibility = View.GONE
        } else {
            statusText.text = message
            statusText.setTextColor(if (error) cError else cMuted)
            statusText.visibility = View.VISIBLE
        }
    }

    /** Rebinds only the rows that are currently on screen; avoids a full data-changed pass. */
    private fun refreshVisibleRows() {
        val first = listView.firstVisiblePosition
        val headers = listView.headerViewsCount
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i) ?: continue
            val holder = child.tag as? RowHolder ?: continue
            val pos = first + i - headers
            if (pos >= 0 && pos < items.size) bindRow(holder, items[pos], pos)
        }
    }

    private fun refreshUi(full: Boolean) {
        curBase = sanitize(baseEdit.text.toString())
        curStart = startNumber()
        val count = items.size

        // Status chip
        chipText.text = when {
            busy -> "WORKING"
            count > 0 -> "$count QUEUED"
            else -> "READY"
        }

        // Preview
        if (curBase.isEmpty()) {
            previewView.text = "Enter a base name"
            previewView.setTextColor(cFaint)
        } else {
            val first = items.firstOrNull()
            val ext = if (first != null) extensionOf(first.name) else ""
            previewView.text = "\u2192 $curBase $curStart$ext"
            previewView.setTextColor(cAccent)
        }

        // Buttons
        val canRename = !busy && count > 0 && curBase.isNotEmpty()
        renameBtn.text = if (count > 0) "Rename " + plural(count, "file") else "Rename"
        renameBtn.isEnabled = canRename
        renameBtn.alpha = if (canRename) 1f else 0.35f
        val canShare = !busy && count > 0
        shareBtn.isEnabled = canShare
        shareBtn.alpha = if (canShare) 1f else 0.35f
        pickCard.isEnabled = !busy
        clearBtn.isEnabled = !busy

        if (!full) {
            refreshVisibleRows()
            return
        }

        // Source card
        if (count == 0) {
            pickTitleView.text = "Choose your files"
            pickMetaView.text = "Internal storage, Downloads, DCIM, Movies, WhatsApp and more."
            pickPill.text = "Browse"
            pickCard.background = ripple(shape(cAccentTint, 22, cAccentLine, maxOf(1, dp(1)), true), cAccentTint)
        } else {
            pickTitleView.text = plural(count, "file") + " queued"
            var total = 0L
            for (item in items) {
                if (item.size >= 0L) total += item.size
            }
            pickMetaView.text = if (total > 0L) formatSize(total) + " in total. Tap to add more." else "Tap to add more files."
            pickPill.text = "Add more"
            pickCard.background = ripple(shape(cSurface, 22, cHair, hair()), cRipple)
        }

        orderHintView.text = if (count > 1) "Reorder with the arrows" else ""
        clearBtn.visibility = if (count > 0) View.VISIBLE else View.GONE

        if (items.isEmpty()) {
            emptySub.text = if (busy) {
                "Reading selection\u2026"
            } else {
                "Open the Android Files app, choose as many files as you like, and they will line up here in numbered order."
            }
            emptyState.visibility = View.VISIBLE
        } else {
            emptyState.visibility = View.GONE
        }

        adapter.notifyDataSetChanged()
    }

    private fun clearAll() {
        if (busy) return
        items.clear()
        setStatus(null)
        refreshUi(true)
    }

    private fun moveItem(item: Item, delta: Int) {
        if (busy) return
        val from = items.indexOf(item)
        val to = from + delta
        if (from < 0 || to < 0 || to >= items.size) return
        Collections.swap(items, from, to)
        refreshUi(true)
    }

    private fun removeItem(item: Item) {
        if (busy) return
        items.remove(item)
        refreshUi(true)
    }

    // ---------------------------------------------------------------------------------------------
    // File selection (native Android Files picker)
    // ---------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, REQ_PICK_FILES)
        } catch (e: Exception) {
            Toast.makeText(this, "The Android Files app is not available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    /** Reads name, type, size and capability flags for a picked document. Returns null if access was denied. */
    private fun describe(uri: Uri): Item? {
        var name: String? = null
        var mime = ""
        var size = -1L
        var flags = 0
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val iFlags = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                    if (iName >= 0 && !c.isNull(iName)) name = c.getString(iName)
                    if (iMime >= 0 && !c.isNull(iMime)) mime = c.getString(iMime)
                    if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                    if (iFlags >= 0 && !c.isNull(iFlags)) flags = c.getInt(iFlags)
                }
            }
        } catch (e: SecurityException) {
            return null
        } catch (e: Exception) {
            // Keep whatever could be read; fall back to defaults below.
        }
        if (mime.isEmpty()) {
            mime = try {
                contentResolver.getType(uri) ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        val finalName = name ?: uri.lastPathSegment ?: "file"
        val isDoc = try {
            DocumentsContract.isDocumentUri(this, uri)
        } catch (e: Exception) {
            false
        }
        val renamable = isDoc && (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
        val thumbs = (flags and DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) != 0
        return Item(uri, finalName, mime, size, renamable, thumbs)
    }

    private fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val known = HashSet<String>()
        for (item in items) known.add(item.uri.toString())

        busy = true
        setStatus("Reading selection\u2026")
        refreshUi(true)

        worker.execute {
            val built = ArrayList<Item>()
            var duplicates = 0
            var denied = 0
            for (u in uris) {
                if (!known.add(u.toString())) {
                    duplicates++
                    continue
                }
                val item = describe(u)
                if (item != null) built.add(item) else denied++
            }
            ui.post {
                busy = false
                if (!isFinishing && !isDestroyed) {
                    items.addAll(built)
                    val notes = ArrayList<String>()
                    if (duplicates > 0) {
                        notes.add(
                            plural(duplicates, "file") + " already in the list " +
                                (if (duplicates == 1) "was" else "were") + " skipped"
                        )
                    }
                    if (denied > 0) notes.add(plural(denied, "file") + " could not be accessed")
                    setStatus(if (notes.isEmpty()) null else notes.joinToString(". ") + ".", denied > 0)
                    refreshUi(true)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Thumbnails
    // ---------------------------------------------------------------------------------------------

    private fun loadThumbnail(item: Item) {
        if (item.thumbRequested || !item.supportsThumb) return
        item.thumbRequested = true
        val uri = item.uri
        val side = dp(120)
        try {
            thumbWorker.execute {
                var bmp: Bitmap? = null
                try {
                    bmp = DocumentsContract.getDocumentThumbnail(contentResolver, uri, Point(side, side), null)
                } catch (e: Exception) {
                    // No thumbnail available; the extension tile stays visible.
                }
                val result = bmp
                if (result != null) {
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            item.thumb = result
                            refreshVisibleRows()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Executor already shut down; nothing to do.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Renaming
    // ---------------------------------------------------------------------------------------------

    private fun startRename() {
        if (busy) return
        refreshUi(false)
        if (curBase.isEmpty()) {
            setStatus("Enter a base name before renaming.", true)
            return
        }
        if (items.isEmpty()) {
            setStatus("Add at least one file.", true)
            return
        }

        val all = ArrayList<RenameJob>()
        for ((i, item) in items.withIndex()) {
            all.add(RenameJob(item, targetNameFor(i, item)))
        }

        for (job in all) {
            if (!job.item.renamable) {
                setStatus(
                    "\u201C${job.item.name}\u201D comes from a source that does not allow renaming. " +
                        "Remove it, then add it again by browsing Internal storage or Downloads instead of the Images or Videos shortcuts.",
                    true
                )
                return
            }
            if (job.targetName.toByteArray(Charsets.UTF_8).size > 255) {
                setStatus("The new name is too long. Shorten the base name.", true)
                return
            }
        }

        val active = ArrayList<RenameJob>()
        for (job in all) {
            if (job.targetName != job.item.name) active.add(job)
        }
        if (active.isEmpty()) {
            setStatus("The files already carry these names.", false)
            return
        }

        // If a new name equals another selected file's current name, rename through temporary names.
        val activeOriginals = HashSet<String>()
        for (job in active) activeOriginals.add(job.item.name.lowercase(Locale.ROOT))
        var needsTemp = false
        for (job in active) {
            val t = job.targetName.lowercase(Locale.ROOT)
            if (t != job.item.name.lowercase(Locale.ROOT) && activeOriginals.contains(t)) {
                needsTemp = true
                break
            }
        }
        val useTemp = needsTemp

        val first = active.first()
        val last = active.last()
        val example = if (active.size == 1) {
            "\u201C${first.item.name}\u201D becomes \u201C${first.targetName}\u201D."
        } else {
            "\u201C${first.item.name}\u201D becomes \u201C${first.targetName}\u201D, through to \u201C${last.targetName}\u201D."
        }

        showConfirm(
            "Rename " + plural(active.size, "file") + "?",
            example + "\n\nFiles are renamed in place. Nothing is copied or deleted, and any failure reverts every change.",
            "Rename"
        ) { executeRename(active, useTemp) }
    }

    private fun executeRename(jobs: List<RenameJob>, useTemp: Boolean) {
        busy = true
        setStatus("Renaming 0 of ${jobs.size}\u2026")
        hideKeyboard()
        refreshUi(true)

        worker.execute {
            val failure = performRename(jobs, useTemp) { done, total ->
                ui.post {
                    if (busy && !isFinishing && !isDestroyed) setStatus("Renaming $done of $total\u2026")
                }
            }
            ui.post {
                // Each file's address changes when it is renamed, so always adopt the latest state.
                for (job in jobs) {
                    job.item.uri = job.currentUri
                    job.item.name = job.currentName
                }
                busy = false
                if (!isFinishing && !isDestroyed) {
                    if (failure == null) {
                        setStatus("Renamed " + plural(jobs.size, "file") + " successfully.", false)
                    } else {
                        setStatus(failure, true)
                    }
                    refreshUi(true)
                }
            }
        }
    }

    /**
     * Renames every job in place using DocumentsContract.renameDocument (a metadata operation, so
     * large videos are not copied). Returns null on success, or a message if something failed, in
     * which case all completed renames have been reverted on a best-effort basis. Nothing is deleted.
     */
    private fun performRename(jobs: List<RenameJob>, useTemp: Boolean, progress: (Int, Int) -> Unit): String? {
        val stamp = System.currentTimeMillis()
        var current: RenameJob? = null
        try {
            if (useTemp) {
                for ((i, job) in jobs.withIndex()) {
                    current = job
                    renameOne(job, "brtmp_" + stamp + "_" + i + extensionOf(job.item.name))
                    job.stage = 1
                }
            }
            for ((i, job) in jobs.withIndex()) {
                current = job
                renameOne(job, job.targetName)
                job.stage = 2
                progress(i + 1, jobs.size)
            }
            return null
        } catch (e: Exception) {
            val failedAt = current?.item?.name ?: "a file"
            val unresolved = rollback(jobs, useTemp, stamp)
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return if (unresolved == 0) {
                "Renaming stopped at \u201C$failedAt\u201D: $reason. Every change was reverted and no file was deleted."
            } else {
                "Renaming stopped at \u201C$failedAt\u201D: $reason. $unresolved " +
                    (if (unresolved == 1) "file" else "files") +
                    " could not be restored automatically and may carry a temporary name. " +
                    "No file was deleted."
            }
        }
    }

    private fun renameOne(job: RenameJob, newName: String) {
        val result = DocumentsContract.renameDocument(contentResolver, job.currentUri, newName)
            ?: throw IllegalStateException("The storage provider refused to rename \u201C${job.currentName}\u201D.")
        job.currentUri = result
        job.currentName = newName
    }

    private fun rollback(jobs: List<RenameJob>, useTemp: Boolean, stamp: Long): Int {
        var unresolved = 0
        for ((i, job) in jobs.withIndex()) {
            if (job.stage == 2) {
                try {
                    if (useTemp) {
                        renameOne(job, "brtmp_" + stamp + "_rb" + i + extensionOf(job.item.name))
                        job.stage = 1
                    } else {
                        renameOne(job, job.item.name)
                        job.stage = 0
                    }
                } catch (e: Exception) {
                    unresolved++
                }
            }
        }
        for (job in jobs) {
            if (job.stage == 1) {
                try {
                    renameOne(job, job.item.name)
                    job.stage = 0
                } catch (e: Exception) {
                    unresolved++
                }
            }
        }
        return unresolved
    }

    // ---------------------------------------------------------------------------------------------
    // Sharing (FTP Rush, with chooser fallback)
    // ---------------------------------------------------------------------------------------------

    private fun commonMime(mimes: List<String>): String {
        val first = mimes[0]
        if (mimes.all { it.equals(first, ignoreCase = true) }) return first
        val top = first.substringBefore('/')
        if (mimes.all { it.substringBefore('/').equals(top, ignoreCase = true) }) return "$top/*"
        return "*/*"
    }

    @Suppress("DEPRECATION")
    private fun findFtpRush(intent: Intent): ComponentName? {
        return try {
            val pm = packageManager
            val matches: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
            for (ri in matches) {
                val pkg = ri.activityInfo.packageName.lowercase(Locale.ROOT)
                val label = ri.loadLabel(pm).toString().lowercase(Locale.ROOT)
                val byLabel = label.contains("ftp") && label.contains("rush")
                val byPackage = pkg.contains("ftprush") || (pkg.contains("ftp") && pkg.contains("rush"))
                if (byLabel || byPackage) {
                    return ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun shareAll() {
        if (busy) return
        if (items.isEmpty()) {
            setStatus("Add at least one file to share.", true)
            return
        }

        val uris = ArrayList<Uri>()
        val mimes = ArrayList<String>()
        for (item in items) {
            uris.add(item.uri)
            mimes.add(if (item.mime.isEmpty()) "application/octet-stream" else item.mime)
        }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.type = commonMime(mimes)

        val clip = ClipData.newUri(contentResolver, "Selected files", uris[0])
        for (i in 1 until uris.size) {
            clip.addItem(ClipData.Item(uris[i]))
        }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val target = findFtpRush(intent)
        if (target != null) {
            val direct = Intent(intent)
            direct.component = target
            try {
                startActivity(direct)
                return
            } catch (e: Exception) {
                // Fall through to the system chooser.
            }
        } else {
            Toast.makeText(this, "FTP Rush was not detected. Choose it from the list.", Toast.LENGTH_SHORT).show()
        }

        try {
            startActivity(Intent.createChooser(intent, "Share with FTP Rush"))
        } catch (e: Exception) {
            setStatus("No app is available to receive these files.", true)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // List adapter and rows
    // ---------------------------------------------------------------------------------------------

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: RowHolder
            val view: View
            if (convertView == null) {
                holder = createRow()
                view = holder.root
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as RowHolder
            }
            bindRow(holder, items[position], position)
            return view
        }
    }

    private fun createRow(): RowHolder {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setPadding(dp(20), dp(5), dp(20), dp(5))

        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(12), dp(12), dp(6), dp(12))
        card.background = shape(cSurface, 20, cHair, hair())

        // Thumbnail tile with order badge
        val tile = FrameLayout(this)
        tile.background = shape(cTile, 14)

        val extText = makeText("", 11f, cAccent, monoBold)
        extText.gravity = Gravity.CENTER
        extText.letterSpacing = 0.08f
        tile.addView(extText, FrameLayout.LayoutParams(MATCH, MATCH))

        val radius = dpf(14f)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        img.clipToOutline = true
        img.visibility = View.GONE
        tile.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

        val badge = makeText("", 10.5f, cOnAccent, monoBold)
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(22)
        badge.minHeight = dp(20)
        badge.setPadding(dp(6), 0, dp(6), 0)
        badge.background = gradient(cAccentHi, cAccentLo, 10)
        val badgeParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        badgeParams.setMargins(dp(5), dp(5), 0, 0)
        tile.addView(badge, badgeParams)

        card.addView(tile, lp(dp(62), dp(62)))

        // Text column
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val name = makeText("", 14.5f, cInk, sans)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name, lp(MATCH, WRAP))

        val preview = makeText("", 12f, cAccent, mono)
        preview.maxLines = 1
        preview.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(preview, lp(MATCH, WRAP, top = dp(5)))

        val info = makeText("", 10.5f, cFaint, mono)
        info.maxLines = 1
        info.ellipsize = TextUtils.TruncateAt.END
        col.addView(info, lp(MATCH, WRAP, top = dp(4)))

        card.addView(col, lp(0, WRAP, 1f, left = dp(14), right = dp(2)))

        // Reorder and remove controls
        val arrows = LinearLayout(this)
        arrows.orientation = LinearLayout.VERTICAL
        val up = control("\u2191")
        val down = control("\u2193")
        arrows.addView(up, lp(dp(34), dp(32)))
        arrows.addView(down, lp(dp(34), dp(32)))
        card.addView(arrows, lp(WRAP, WRAP))

        val remove = control("\u00D7")
        card.addView(remove, lp(dp(34), dp(40)))

        outer.addView(card, lp(MATCH, WRAP))

        return RowHolder(outer, img, extText, badge, name, preview, info, up, down, remove)
    }

    private fun bindRow(h: RowHolder, item: Item, position: Int) {
        h.badge.text = String.format(Locale.ROOT, "%02d", position + 1)
        h.name.text = item.name

        if (!item.renamable) {
            h.preview.text = "locked \u00B7 cannot rename from this source"
            h.preview.setTextColor(cError)
        } else if (curBase.isEmpty()) {
            h.preview.text = "add a base name"
            h.preview.setTextColor(cFaint)
        } else {
            h.preview.text = "\u2192 " + targetNameFor(position, item)
            h.preview.setTextColor(cAccent)
        }

        val type = extensionOf(item.name).removePrefix(".").uppercase(Locale.ROOT)
        val typeLabel = if (type.isEmpty()) "FILE" else type
        val size = formatSize(item.size)
        h.info.text = if (size.isEmpty()) typeLabel else "$typeLabel \u00B7 $size"
        h.ext.text = typeLabel.take(5)

        val bmp = item.thumb
        if (bmp != null) {
            h.image.setImageBitmap(bmp)
            h.image.visibility = View.VISIBLE
            h.ext.visibility = View.GONE
        } else {
            h.image.setImageDrawable(null)
            h.image.visibility = View.GONE
            h.ext.visibility = View.VISIBLE
            loadThumbnail(item)
        }

        h.up.alpha = if (position > 0) 1f else 0.25f
        h.down.alpha = if (position < items.size - 1) 1f else 0.25f
        h.up.setOnClickListener { moveItem(item, -1) }
        h.down.setOnClickListener { moveItem(item, 1) }
        h.remove.setOnClickListener { removeItem(item) }
    }

    // ---------------------------------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------------------------------

    private class Item(
        var uri: Uri,
        var name: String,
        val mime: String,
        val size: Long,
        val renamable: Boolean,
        val supportsThumb: Boolean
    ) {
        var thumb: Bitmap? = null
        var thumbRequested: Boolean = false
    }

    private class RenameJob(val item: Item, val targetName: String) {
        var currentUri: Uri = item.uri
        var currentName: String = item.name

        /** 0 = original name, 1 = temporary name, 2 = final name. */
        var stage: Int = 0
    }

    private class RowHolder(
        val root: LinearLayout,
        val image: ImageView,
        val ext: TextView,
        val badge: TextView,
        val name: TextView,
        val preview: TextView,
        val info: TextView,
        val up: TextView,
        val down: TextView,
        val remove: TextView
    )
}
