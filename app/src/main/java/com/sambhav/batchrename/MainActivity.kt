package com.sambhav.batchrename

import android.app.Activity
import android.app.AlertDialog
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
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
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
    // Palette and typography
    // ---------------------------------------------------------------------------------------------

    private val cBg = Color.parseColor("#F6F3EE")
    private val cCard = Color.parseColor("#FDFCFA")
    private val cInk = Color.parseColor("#25242A")
    private val cMuted = Color.parseColor("#6F6D75")
    private val cFaint = Color.parseColor("#A3A0A8")
    private val cHair = Color.parseColor("#E6E2DB")
    private val cHairStrong = Color.parseColor("#D3CEC5")
    private val cAccent = Color.parseColor("#6B4E9E")
    private val cAccentSoft = Color.parseColor("#336B4E9E")
    private val cAccentTile = Color.parseColor("#EFEAF4")
    private val cAccentRipple = Color.parseColor("#1F6B4E9E")
    private val cWhiteRipple = Color.parseColor("#33FFFFFF")
    private val cError = Color.parseColor("#9A3B3B")

    private val serif: Typeface = Typeface.create("serif", Typeface.NORMAL)
    private val sans: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

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

    // Views
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
    private lateinit var emptyText: TextView
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
        refreshUi()

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
    // UI construction
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

    private fun shape(fill: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT, strokePx: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(fill)
        d.cornerRadius = dpf(radiusDp.toFloat())
        if (strokePx > 0) d.setStroke(strokePx, strokeColor)
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

    private fun eyebrow(text: String, color: Int = cMuted): TextView {
        val t = makeText(text.uppercase(Locale.ROOT), 10.5f, color, sansMedium)
        t.letterSpacing = 0.14f
        return t
    }

    private fun textAction(text: String): TextView {
        val t = makeText(text, 12.5f, cAccent, sansMedium)
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        t.isClickable = true
        t.isFocusable = true
        t.background = ripple(shape(Color.TRANSPARENT, 8), cAccentRipple)
        return t
    }

    private fun control(label: String): TextView {
        val t = makeText(label, 17f, cMuted, sans)
        t.gravity = Gravity.CENTER
        t.isClickable = true
        t.isFocusable = true
        t.background = ripple(shape(Color.TRANSPARENT, 8), cAccentRipple)
        return t
    }

    private fun button(text: String, filled: Boolean): TextView {
        val b = makeText(text, 14f, if (filled) Color.WHITE else cInk, sansMedium)
        b.gravity = Gravity.CENTER
        b.letterSpacing = 0.03f
        b.minHeight = dp(52)
        b.setPadding(dp(14), dp(14), dp(14), dp(14))
        b.isClickable = true
        b.isFocusable = true
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.background = if (filled) {
            ripple(shape(cAccent, 14), cWhiteRipple)
        } else {
            ripple(shape(cCard, 14, cHairStrong, hair()), cAccentRipple)
        }
        return b
    }

    private fun styleEdit(e: EditText, hint: String) {
        e.background = null
        e.setPadding(0, dp(6), 0, dp(4))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        e.setTextColor(cInk)
        e.setHintTextColor(cFaint)
        e.hint = hint
        e.typeface = sans
        e.highlightColor = cAccentSoft
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.includeFontPadding = false
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

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(cBg)
        root.isFocusableInTouchMode = true

        // ---- Header panel -------------------------------------------------------------------
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(24), dp(24), dp(24), dp(4))

        panel.addView(eyebrow("Batch Rename", cAccent), lp(WRAP, WRAP))

        val title = makeText("Sequential naming", 30f, cInk, serif)
        title.letterSpacing = -0.01f
        panel.addView(title, lp(MATCH, WRAP, top = dp(10)))

        val subtitle = makeText(
            "Pick files with the Android Files app, set one base name, and number them in order.",
            13f, cMuted, sansLight
        )
        subtitle.setLineSpacing(0f, 1.2f)
        panel.addView(subtitle, lp(MATCH, WRAP, top = dp(8)))

        // ---- File picker card ---------------------------------------------------------------
        pickCard = LinearLayout(this)
        pickCard.orientation = LinearLayout.HORIZONTAL
        pickCard.gravity = Gravity.CENTER_VERTICAL
        pickCard.setPadding(dp(18), dp(14), dp(12), dp(14))
        pickCard.isClickable = true
        pickCard.isFocusable = true
        pickCard.background = ripple(shape(cCard, 16, cHair, hair()), cAccentRipple)
        pickCard.setOnClickListener { if (!busy) pickFiles() }

        val pickCol = LinearLayout(this)
        pickCol.orientation = LinearLayout.VERTICAL
        pickCol.addView(eyebrow("Files"), lp(WRAP, WRAP))
        pickTitleView = makeText("No files selected", 16f, cInk, sansMedium)
        pickTitleView.maxLines = 1
        pickTitleView.ellipsize = TextUtils.TruncateAt.END
        pickCol.addView(pickTitleView, lp(MATCH, WRAP, top = dp(6)))
        pickMetaView = makeText("", 12.5f, cMuted, sans)
        pickMetaView.maxLines = 2
        pickMetaView.ellipsize = TextUtils.TruncateAt.END
        pickCol.addView(pickMetaView, lp(MATCH, WRAP, top = dp(3)))
        pickCard.addView(pickCol, lp(0, WRAP, 1f))

        pickPill = makeText("Add files", 13f, cAccent, sansMedium)
        pickPill.setPadding(dp(14), dp(10), dp(14), dp(10))
        pickPill.background = shape(cAccentTile, 20)
        pickCard.addView(pickPill, lp(WRAP, WRAP, left = dp(10)))

        panel.addView(pickCard, lp(MATCH, WRAP, top = dp(20)))

        // ---- Name card ----------------------------------------------------------------------
        val nameCard = LinearLayout(this)
        nameCard.orientation = LinearLayout.VERTICAL
        nameCard.setPadding(dp(18), dp(14), dp(18), dp(14))
        nameCard.background = shape(cCard, 16, cHair, hair())

        val fields = LinearLayout(this)
        fields.orientation = LinearLayout.HORIZONTAL

        val baseCol = LinearLayout(this)
        baseCol.orientation = LinearLayout.VERTICAL
        baseCol.addView(eyebrow("Base name"), lp(WRAP, WRAP))
        baseEdit = EditText(this)
        styleEdit(baseEdit, "Patna Raid")
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
        baseCol.addView(baseEdit, lp(MATCH, WRAP, top = dp(4)))
        fields.addView(baseCol, lp(0, WRAP, 1f))

        val vDivider = View(this)
        vDivider.setBackgroundColor(cHair)
        fields.addView(vDivider, lp(hair(), MATCH, left = dp(14), right = dp(14)))

        val startCol = LinearLayout(this)
        startCol.orientation = LinearLayout.VERTICAL
        startCol.addView(eyebrow("Start at"), lp(WRAP, WRAP))
        startEdit = EditText(this)
        styleEdit(startEdit, "1")
        startEdit.inputType = InputType.TYPE_CLASS_NUMBER
        startEdit.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(6))
        startCol.addView(startEdit, lp(MATCH, WRAP, top = dp(4)))
        fields.addView(startCol, lp(dp(64), WRAP))

        nameCard.addView(fields, lp(MATCH, WRAP))

        val hr = View(this)
        hr.setBackgroundColor(cHair)
        nameCard.addView(hr, lp(MATCH, hair(), top = dp(12), bottom = dp(12)))

        previewView = makeText("", 13f, cAccent, sansMedium)
        previewView.maxLines = 1
        previewView.ellipsize = TextUtils.TruncateAt.MIDDLE
        nameCard.addView(previewView, lp(MATCH, WRAP))

        panel.addView(nameCard, lp(MATCH, WRAP, top = dp(12)))
        root.addView(panel, lp(MATCH, WRAP))

        // ---- List header --------------------------------------------------------------------
        val listHeader = LinearLayout(this)
        listHeader.orientation = LinearLayout.HORIZONTAL
        listHeader.gravity = Gravity.CENTER_VERTICAL
        listHeader.setPadding(dp(24), dp(14), dp(16), dp(0))
        listHeader.addView(eyebrow("Order", cInk), lp(WRAP, WRAP))
        orderHintView = makeText("", 12.5f, cMuted, sans)
        orderHintView.maxLines = 1
        orderHintView.ellipsize = TextUtils.TruncateAt.END
        listHeader.addView(orderHintView, lp(0, WRAP, 1f, left = dp(10)))
        clearBtn = textAction("Clear all")
        clearBtn.setOnClickListener { clearAll() }
        listHeader.addView(clearBtn, lp(WRAP, WRAP))
        root.addView(listHeader, lp(MATCH, WRAP))

        // ---- Ordered list -------------------------------------------------------------------
        val listFrame = FrameLayout(this)
        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.TRANSPARENT)
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.isVerticalScrollBarEnabled = true
        adapter = FileAdapter()
        listView.adapter = adapter
        listFrame.addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyText = makeText("", 14f, cMuted, sansLight)
        emptyText.gravity = Gravity.CENTER
        emptyText.setLineSpacing(0f, 1.25f)
        emptyText.setPadding(dp(40), dp(24), dp(40), dp(24))
        listFrame.addView(emptyText, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        root.addView(listFrame, lp(MATCH, 0, 1f, top = dp(4)))

        // ---- Bottom bar ---------------------------------------------------------------------
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(cCard)

        val topHair = View(this)
        topHair.setBackgroundColor(cHair)
        bottom.addView(topHair, lp(MATCH, hair()))

        statusText = makeText("", 12.5f, cMuted, sans)
        statusText.setPadding(dp(24), dp(12), dp(24), 0)
        statusText.setLineSpacing(0f, 1.15f)
        statusText.visibility = View.GONE
        bottom.addView(statusText, lp(MATCH, WRAP))

        val buttonRow = LinearLayout(this)
        buttonRow.orientation = LinearLayout.HORIZONTAL
        buttonRow.setPadding(dp(24), dp(12), dp(24), dp(16))

        shareBtn = button("Share to FTP Rush", false)
        shareBtn.setOnClickListener { shareAll() }
        buttonRow.addView(shareBtn, lp(0, WRAP, 1f, right = dp(6)))

        renameBtn = button("Rename", true)
        renameBtn.setOnClickListener { startRename() }
        buttonRow.addView(renameBtn, lp(0, WRAP, 1f, left = dp(6)))

        bottom.addView(buttonRow, lp(MATCH, WRAP))
        root.addView(bottom, lp(MATCH, WRAP))

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
        window.navigationBarColor = cBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                controller.setSystemBarsAppearance(mask, mask)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
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
                refreshUi()
            }
        }
        baseEdit.addTextChangedListener(watcher)
        startEdit.addTextChangedListener(watcher)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(baseEdit.windowToken, 0)
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

    private fun refreshUi() {
        curBase = sanitize(baseEdit.text.toString())
        curStart = startNumber()

        val count = items.size

        // File card
        if (count == 0) {
            pickTitleView.text = "No files selected"
            pickMetaView.text = "Browse internal storage, Downloads, DCIM, Movies, WhatsApp and more."
            pickPill.text = "Add files"
        } else {
            pickTitleView.text = plural(count, "file") + " selected"
            var total = 0L
            var known = true
            for (item in items) {
                if (item.size >= 0L) total += item.size else known = false
            }
            pickMetaView.text = if (known || total > 0L) {
                formatSize(total) + " in total. Tap to add more."
            } else {
                "Tap to add more files."
            }
            pickPill.text = "Add more"
        }

        orderHintView.text = if (count > 1) "Use the arrows to adjust" else ""
        clearBtn.visibility = if (count > 0) View.VISIBLE else View.GONE

        // Preview line
        if (curBase.isEmpty()) {
            previewView.text = "Enter a base name to see a preview"
            previewView.setTextColor(cFaint)
        } else {
            val first = items.firstOrNull()
            val ext = if (first != null) extensionOf(first.name) else ""
            previewView.text = "Preview   $curBase $curStart$ext"
            previewView.setTextColor(cAccent)
        }

        // Empty state
        if (items.isEmpty()) {
            emptyText.text = if (busy) {
                "Reading selection…"
            } else {
                "Your selection will appear here, numbered in order.\nTap Add files to open the Files app."
            }
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        // Buttons
        val canRename = !busy && count > 0 && curBase.isNotEmpty()
        renameBtn.text = if (count > 0) "Rename " + plural(count, "file") else "Rename"
        renameBtn.isEnabled = canRename
        renameBtn.alpha = if (canRename) 1f else 0.4f
        val canShare = !busy && count > 0
        shareBtn.isEnabled = canShare
        shareBtn.alpha = if (canShare) 1f else 0.4f
        pickCard.isEnabled = !busy
        clearBtn.isEnabled = !busy

        adapter.notifyDataSetChanged()
    }

    private fun clearAll() {
        if (busy) return
        items.clear()
        setStatus(null)
        refreshUi()
    }

    private fun moveItem(item: Item, delta: Int) {
        if (busy) return
        val from = items.indexOf(item)
        val to = from + delta
        if (from < 0 || to < 0 || to >= items.size) return
        Collections.swap(items, from, to)
        refreshUi()
    }

    private fun removeItem(item: Item) {
        if (busy) return
        items.remove(item)
        refreshUi()
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
        setStatus("Reading selection…")
        refreshUi()

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
                    if (duplicates > 0) notes.add(plural(duplicates, "file") + " already in the list " + (if (duplicates == 1) "was" else "were") + " skipped")
                    if (denied > 0) notes.add(plural(denied, "file") + " could not be accessed")
                    setStatus(if (notes.isEmpty()) null else notes.joinToString(". ") + ".", denied > 0)
                    refreshUi()
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
        val side = dp(112)
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
                            adapter.notifyDataSetChanged()
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
        refreshUi()
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
                    "“${job.item.name}” comes from a source that does not allow renaming. " +
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
            "“${first.item.name}” becomes “${first.targetName}”."
        } else {
            "“${first.item.name}” becomes “${first.targetName}”, through to “${last.targetName}”."
        }

        AlertDialog.Builder(this)
            .setTitle("Rename " + plural(active.size, "file") + "?")
            .setMessage(
                example + "\n\nFiles are renamed in place. Nothing is copied or deleted, " +
                    "and any failure reverts every change."
            )
            .setPositiveButton("Rename") { _, _ -> executeRename(active, useTemp) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeRename(jobs: List<RenameJob>, useTemp: Boolean) {
        busy = true
        setStatus("Renaming 0 of ${jobs.size}…")
        hideKeyboard()
        refreshUi()

        worker.execute {
            val failure = performRename(jobs, useTemp) { done, total ->
                ui.post {
                    if (busy && !isFinishing && !isDestroyed) setStatus("Renaming $done of $total…")
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
                    refreshUi()
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
                "Renaming stopped at “$failedAt”: $reason. Every change was reverted and no file was deleted."
            } else {
                "Renaming stopped at “$failedAt”: $reason. $unresolved " +
                    (if (unresolved == 1) "file" else "files") +
                    " could not be restored automatically and may carry a temporary name. " +
                    "No file was deleted."
            }
        }
    }

    private fun renameOne(job: RenameJob, newName: String) {
        val result = DocumentsContract.renameDocument(contentResolver, job.currentUri, newName)
            ?: throw IllegalStateException("The storage provider refused to rename “${job.currentName}”.")
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
    // List adapter
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

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(24), dp(12), dp(12), dp(12))

        // Thumbnail tile with an order badge
        val tile = FrameLayout(this)
        tile.background = shape(cAccentTile, 10)

        val extText = makeText("", 11f, cAccent, sansMedium)
        extText.gravity = Gravity.CENTER
        extText.letterSpacing = 0.06f
        tile.addView(extText, FrameLayout.LayoutParams(MATCH, MATCH))

        val radius = dpf(10f)
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

        val badge = makeText("", 10.5f, Color.WHITE, sansMedium)
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(20)
        badge.minHeight = dp(20)
        badge.setPadding(dp(5), 0, dp(5), 0)
        badge.background = shape(cAccent, 10)
        val badgeParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        badgeParams.setMargins(dp(4), dp(4), 0, 0)
        tile.addView(badge, badgeParams)

        row.addView(tile, lp(dp(56), dp(56)))

        // Text column
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val name = makeText("", 14.5f, cInk, sans)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name, lp(MATCH, WRAP))

        val preview = makeText("", 12.5f, cAccent, sansMedium)
        preview.maxLines = 1
        preview.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(preview, lp(MATCH, WRAP, top = dp(4)))

        val info = makeText("", 11.5f, cFaint, sans)
        info.maxLines = 1
        info.ellipsize = TextUtils.TruncateAt.END
        col.addView(info, lp(MATCH, WRAP, top = dp(3)))

        row.addView(col, lp(0, WRAP, 1f, left = dp(14), right = dp(4)))

        // Reorder and remove controls
        val arrows = LinearLayout(this)
        arrows.orientation = LinearLayout.VERTICAL
        val up = control("↑")
        val down = control("↓")
        arrows.addView(up, lp(dp(36), dp(32)))
        arrows.addView(down, lp(dp(36), dp(32)))
        row.addView(arrows, lp(WRAP, WRAP))

        val remove = control("×")
        row.addView(remove, lp(dp(36), dp(40)))

        outer.addView(row, lp(MATCH, WRAP))

        val divider = View(this)
        divider.setBackgroundColor(cHair)
        outer.addView(divider, lp(MATCH, hair(), left = dp(94), right = dp(24)))

        return RowHolder(outer, img, extText, badge, name, preview, info, up, down, remove)
    }

    private fun bindRow(h: RowHolder, item: Item, position: Int) {
        h.badge.text = (position + 1).toString()
        h.name.text = item.name

        if (!item.renamable) {
            h.preview.text = "Cannot be renamed from this source"
            h.preview.setTextColor(cError)
        } else if (curBase.isEmpty()) {
            h.preview.text = "Add a base name to rename"
            h.preview.setTextColor(cFaint)
        } else {
            h.preview.text = "→  " + targetNameFor(position, item)
            h.preview.setTextColor(cAccent)
        }

        val type = extensionOf(item.name).removePrefix(".").uppercase(Locale.ROOT)
        val typeLabel = if (type.isEmpty()) "FILE" else type
        val size = formatSize(item.size)
        h.info.text = if (size.isEmpty()) typeLabel else "$typeLabel   ·   $size"
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
