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
import android.graphics.Color
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val REQ_PICK_FOLDER = 4101
        const val PREFS_NAME = "batchrename_prefs"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_BASE_NAME = "base_name"
        const val KEY_START_AT = "start_at"
        const val STATE_SELECTION = "selection_order"
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
    private val cAccentSoft = Color.parseColor("#33" + "6B4E9E")
    private val cAccentRow = Color.parseColor("#F1ECF8")
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
    private lateinit var prefs: SharedPreferences

    private var treeUri: Uri? = null
    private var folderName: String = ""
    private val files = ArrayList<DocEntry>()
    private val allNamesLower = HashSet<String>()
    private val selectedIds = ArrayList<String>()
    private val orderMap = HashMap<String, Int>()
    private var busy = false
    private var loading = false
    private var loadToken = 0
    private var curBase = ""
    private var curStart = 1L

    // Views
    private lateinit var folderCard: LinearLayout
    private lateinit var folderNameView: TextView
    private lateinit var folderMetaView: TextView
    private lateinit var chooseBtn: TextView
    private lateinit var baseEdit: EditText
    private lateinit var startEdit: EditText
    private lateinit var previewView: TextView
    private lateinit var selectionCountView: TextView
    private lateinit var selectAllBtn: TextView
    private lateinit var clearBtn: TextView
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

        val restored = savedInstanceState?.getStringArrayList(STATE_SELECTION)
        refreshUi()
        restoreLastFolder(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTION, ArrayList(selectedIds))
    }

    override fun onDestroy() {
        super.onDestroy()
        // Graceful: lets a rename that is already running finish instead of interrupting it.
        worker.shutdown()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Permission could not be persisted; it remains valid for this session.
        }
        treeUri = uri
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        selectedIds.clear()
        setStatus(null)
        loadFolder(uri, null)
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
            "Choose a folder, select files in the order you want, and apply one consistent name.",
            13f, cMuted, sansLight
        )
        subtitle.setLineSpacing(0f, 1.2f)
        panel.addView(subtitle, lp(MATCH, WRAP, top = dp(8)))

        // ---- Folder card --------------------------------------------------------------------
        folderCard = LinearLayout(this)
        folderCard.orientation = LinearLayout.HORIZONTAL
        folderCard.gravity = Gravity.CENTER_VERTICAL
        folderCard.setPadding(dp(18), dp(14), dp(12), dp(14))
        folderCard.isClickable = true
        folderCard.isFocusable = true
        folderCard.background = ripple(shape(cCard, 16, cHair, hair()), cAccentRipple)
        folderCard.setOnClickListener { if (!busy) pickFolder() }

        val folderCol = LinearLayout(this)
        folderCol.orientation = LinearLayout.VERTICAL
        folderCol.addView(eyebrow("Folder"), lp(WRAP, WRAP))
        folderNameView = makeText("No folder selected", 16f, cInk, sansMedium)
        folderNameView.maxLines = 1
        folderNameView.ellipsize = TextUtils.TruncateAt.END
        folderCol.addView(folderNameView, lp(MATCH, WRAP, top = dp(6)))
        folderMetaView = makeText("Choose a folder to begin", 12.5f, cMuted, sans)
        folderMetaView.maxLines = 1
        folderMetaView.ellipsize = TextUtils.TruncateAt.END
        folderCol.addView(folderMetaView, lp(MATCH, WRAP, top = dp(3)))
        folderCard.addView(folderCol, lp(0, WRAP, 1f))

        chooseBtn = makeText("Choose", 13f, cAccent, sansMedium)
        chooseBtn.setPadding(dp(14), dp(10), dp(14), dp(10))
        chooseBtn.background = shape(Color.parseColor("#F0EBF7"), 20)
        folderCard.addView(chooseBtn, lp(WRAP, WRAP, left = dp(10)))

        panel.addView(folderCard, lp(MATCH, WRAP, top = dp(20)))

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
        listHeader.addView(eyebrow("Files", cInk), lp(WRAP, WRAP))
        selectionCountView = makeText("0 of 0 selected", 12.5f, cMuted, sans)
        selectionCountView.maxLines = 1
        listHeader.addView(selectionCountView, lp(0, WRAP, 1f, left = dp(10)))
        selectAllBtn = textAction("Select all")
        selectAllBtn.setOnClickListener { selectAll() }
        listHeader.addView(selectAllBtn, lp(WRAP, WRAP))
        clearBtn = textAction("Clear")
        clearBtn.setOnClickListener { clearSelection() }
        listHeader.addView(clearBtn, lp(WRAP, WRAP))
        root.addView(listHeader, lp(MATCH, WRAP))

        // ---- File list ----------------------------------------------------------------------
        val listFrame = FrameLayout(this)
        listView = ListView(this)
        listView.divider = null
        listView.dividerHeight = 0
        listView.selector = ColorDrawable(Color.parseColor("#12" + "6B4E9E"))
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.isVerticalScrollBarEnabled = true
        adapter = FileAdapter()
        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            toggleSelection(position)
        }
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
        shareBtn.setOnClickListener { shareSelected() }
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

    private fun targetNameFor(order: Int, entry: DocEntry): String =
        curBase + " " + (curStart + order) + extensionOf(entry.name)

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

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
            } else {
                val la = Character.toLowerCase(ca)
                val lb = Character.toLowerCase(cb)
                if (la != lb) return la.compareTo(lb)
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
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

    private fun rebuildOrderMap() {
        orderMap.clear()
        for ((i, id) in selectedIds.withIndex()) {
            orderMap[id] = i
        }
    }

    private fun selectedEntries(): List<DocEntry> {
        val byId = HashMap<String, DocEntry>()
        for (f in files) byId[f.docId] = f
        val out = ArrayList<DocEntry>()
        for (id in selectedIds) {
            val e = byId[id]
            if (e != null) out.add(e)
        }
        return out
    }

    private fun refreshUi() {
        rebuildOrderMap()
        curBase = sanitize(baseEdit.text.toString())
        curStart = startNumber()

        val hasFolder = treeUri != null
        folderNameView.text = if (hasFolder) {
            if (folderName.isEmpty()) "Selected folder" else folderName
        } else {
            "No folder selected"
        }
        folderMetaView.text = when {
            loading -> "Reading folder…"
            !hasFolder -> "Choose a folder to begin"
            else -> plural(files.size, "file")
        }
        chooseBtn.text = if (hasFolder) "Change" else "Choose"
        selectionCountView.text = "${selectedIds.size} of ${files.size} selected"

        // Preview line
        if (curBase.isEmpty()) {
            previewView.text = "Enter a base name to see a preview"
            previewView.setTextColor(cFaint)
        } else {
            val sample = selectedEntries().firstOrNull()
            val ext = if (sample != null) extensionOf(sample.name) else ""
            previewView.text = "Preview   $curBase $curStart$ext"
            previewView.setTextColor(cAccent)
        }

        // Empty state
        if (files.isEmpty()) {
            emptyText.text = when {
                loading -> "Reading folder…"
                !hasFolder -> "No folder yet.\nChoose one to see its files."
                else -> "This folder contains no files."
            }
            emptyText.visibility = View.VISIBLE
        } else {
            emptyText.visibility = View.GONE
        }

        // Buttons
        val count = selectedIds.size
        val canRename = !busy && !loading && count > 0 && curBase.isNotEmpty()
        renameBtn.text = if (count > 0) "Rename " + plural(count, "file") else "Rename"
        renameBtn.isEnabled = canRename
        renameBtn.alpha = if (canRename) 1f else 0.4f
        val canShare = !busy && count > 0
        shareBtn.isEnabled = canShare
        shareBtn.alpha = if (canShare) 1f else 0.4f
        folderCard.isEnabled = !busy
        selectAllBtn.isEnabled = !busy
        clearBtn.isEnabled = !busy

        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(position: Int) {
        if (busy) return
        val entry = files.getOrNull(position) ?: return
        val idx = selectedIds.indexOf(entry.docId)
        if (idx >= 0) selectedIds.removeAt(idx) else selectedIds.add(entry.docId)
        hideKeyboard()
        refreshUi()
    }

    private fun selectAll() {
        if (busy) return
        val present = HashSet<String>(selectedIds)
        for (f in files) {
            if (present.add(f.docId)) selectedIds.add(f.docId)
        }
        refreshUi()
    }

    private fun clearSelection() {
        if (busy) return
        selectedIds.clear()
        refreshUi()
    }

    // ---------------------------------------------------------------------------------------------
    // Folder selection and listing (Storage Access Framework)
    // ---------------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        val current = treeUri
        if (current != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, current)
        }
        try {
            startActivityForResult(intent, REQ_PICK_FOLDER)
        } catch (e: Exception) {
            Toast.makeText(this, "No folder picker is available on this device.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreLastFolder(restoredSelection: List<String>?) {
        val saved = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = try {
            Uri.parse(saved)
        } catch (e: Exception) {
            return
        }
        var permitted = false
        for (p in contentResolver.persistedUriPermissions) {
            if (p.uri == uri && p.isReadPermission && p.isWritePermission) {
                permitted = true
                break
            }
        }
        if (!permitted) return
        treeUri = uri
        loadFolder(uri, restoredSelection)
    }

    private fun queryDisplayName(tree: Uri, docId: String): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val cursor = contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
            cursor?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadFolder(tree: Uri, restoreSelection: List<String>?) {
        loading = true
        loadToken += 1
        val token = loadToken
        refreshUi()

        worker.execute {
            val list = ArrayList<DocEntry>()
            val names = HashSet<String>()
            var displayName = ""
            var errorMessage: String? = null
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(tree)
                displayName = queryDisplayName(tree, treeDocId) ?: treeDocId.substringAfterLast(':')
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_FLAGS
                )
                val cursor = contentResolver.query(childrenUri, projection, null, null, null)
                    ?: throw IllegalStateException("The storage provider returned no data.")
                cursor.use { c ->
                    val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    val iFlags = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_FLAGS)
                    while (c.moveToNext()) {
                        if (c.isNull(iId) || c.isNull(iName)) continue
                        val docId = c.getString(iId)
                        val name = c.getString(iName)
                        val mime = if (c.isNull(iMime)) "" else c.getString(iMime)
                        val size = if (c.isNull(iSize)) -1L else c.getLong(iSize)
                        val flags = if (c.isNull(iFlags)) 0 else c.getInt(iFlags)
                        names.add(name.lowercase(Locale.ROOT))
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        list.add(
                            DocEntry(
                                docId,
                                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                                name, mime, size, flags
                            )
                        )
                    }
                }
                list.sortWith(Comparator { a, b -> naturalCompare(a.name, b.name) })
            } catch (e: Exception) {
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }

            val finalError = errorMessage
            val finalName = displayName
            ui.post {
                if (!isFinishing && !isDestroyed && token == loadToken) {
                    loading = false
                    files.clear()
                    allNamesLower.clear()
                    selectedIds.clear()
                    if (finalError != null) {
                        folderName = finalName
                        setStatus("Could not read this folder: $finalError", true)
                    } else {
                        folderName = finalName
                        files.addAll(list)
                        allNamesLower.addAll(names)
                        if (restoreSelection != null) {
                            val present = HashSet<String>()
                            for (f in list) present.add(f.docId)
                            val seen = HashSet<String>()
                            for (id in restoreSelection) {
                                if (present.contains(id) && seen.add(id)) selectedIds.add(id)
                            }
                        }
                    }
                    refreshUi()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Renaming
    // ---------------------------------------------------------------------------------------------

    private fun startRename() {
        if (busy || loading) return
        refreshUi()
        val base = curBase
        if (base.isEmpty()) {
            setStatus("Enter a base name before renaming.", true)
            return
        }
        val selected = selectedEntries()
        if (selected.isEmpty()) {
            setStatus("Select at least one file.", true)
            return
        }

        val all = ArrayList<RenameJob>()
        for ((i, e) in selected.withIndex()) {
            all.add(RenameJob(e, targetNameFor(i, e)))
        }

        for (job in all) {
            if ((job.entry.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) == 0) {
                setStatus("This storage location does not allow renaming “${job.entry.name}”.", true)
                return
            }
            if (job.targetName.toByteArray(Charsets.UTF_8).size > 255) {
                setStatus("The new name is too long. Shorten the base name.", true)
                return
            }
        }

        val active = ArrayList<RenameJob>()
        for (job in all) {
            if (job.targetName != job.entry.name) active.add(job)
        }
        if (active.isEmpty()) {
            setStatus("The selected files already carry these names.", false)
            return
        }

        val activeOriginals = HashSet<String>()
        for (job in active) activeOriginals.add(job.entry.name.lowercase(Locale.ROOT))

        val fixedNames = HashSet<String>(allNamesLower)
        fixedNames.removeAll(activeOriginals)
        for (job in active) {
            if (fixedNames.contains(job.targetName.lowercase(Locale.ROOT))) {
                setStatus(
                    "“${job.targetName}” already exists in this folder. Change the base name or start number.",
                    true
                )
                return
            }
        }

        var needsTemp = false
        for (job in active) {
            val t = job.targetName.lowercase(Locale.ROOT)
            if (t != job.entry.name.lowercase(Locale.ROOT) && activeOriginals.contains(t)) {
                needsTemp = true
                break
            }
        }
        val useTemp = needsTemp

        val first = active.first()
        val last = active.last()
        val example = if (active.size == 1) {
            "“${first.entry.name}” becomes “${first.targetName}”."
        } else {
            "“${first.entry.name}” becomes “${first.targetName}”, through to “${last.targetName}”."
        }

        AlertDialog.Builder(this)
            .setTitle("Rename " + plural(active.size, "file") + "?")
            .setMessage(
                example + "\n\nFiles are renamed in place on the storage provider. " +
                    "Nothing is copied or deleted, and any failure reverts every change."
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
                busy = false
                if (!isFinishing && !isDestroyed) {
                    val tree = treeUri
                    if (failure == null) {
                        selectedIds.clear()
                        setStatus("Renamed " + plural(jobs.size, "file") + " successfully.", false)
                        if (tree != null) loadFolder(tree, null) else refreshUi()
                    } else {
                        setStatus(failure, true)
                        if (tree != null) loadFolder(tree, ArrayList(selectedIds)) else refreshUi()
                    }
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
                    renameOne(job, "brtmp_" + stamp + "_" + i + extensionOf(job.entry.name))
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
            val failedAt = current?.entry?.name ?: "a file"
            val unresolved = rollback(jobs, useTemp, stamp)
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return if (unresolved == 0) {
                "Renaming stopped at “$failedAt”: $reason. Every change was reverted and no file was deleted."
            } else {
                "Renaming stopped at “$failedAt”: $reason. $unresolved " +
                    (if (unresolved == 1) "file" else "files") +
                    " could not be restored automatically and may carry a temporary name. " +
                    "Open the folder to review. No file was deleted."
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
                        renameOne(job, "brtmp_" + stamp + "_rb" + i + extensionOf(job.entry.name))
                        job.stage = 1
                    } else {
                        renameOne(job, job.entry.name)
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
                    renameOne(job, job.entry.name)
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

    private fun shareSelected() {
        if (busy) return
        val selected = selectedEntries()
        if (selected.isEmpty()) {
            setStatus("Select at least one file to share.", true)
            return
        }

        val uris = ArrayList<Uri>()
        val mimes = ArrayList<String>()
        for (e in selected) {
            uris.add(e.uri)
            mimes.add(if (e.mime.isEmpty()) "application/octet-stream" else e.mime)
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
        override fun getCount(): Int = files.size

        override fun getItem(position: Int): Any = files[position]

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
            bindRow(holder, files[position])
            return view
        }
    }

    private fun createRow(): RowHolder {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(24), dp(13), dp(24), dp(13))

        val badge = makeText("", 12f, Color.WHITE, sansMedium)
        badge.gravity = Gravity.CENTER
        val badgeOn = GradientDrawable()
        badgeOn.shape = GradientDrawable.OVAL
        badgeOn.setColor(cAccent)
        val badgeOff = GradientDrawable()
        badgeOff.shape = GradientDrawable.OVAL
        badgeOff.setColor(Color.TRANSPARENT)
        badgeOff.setStroke(maxOf(1, dp(1)), cHairStrong)
        row.addView(badge, lp(dp(30), dp(30)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val name = makeText("", 15f, cInk, sans)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(name, lp(MATCH, WRAP))

        val meta = makeText("", 12f, cMuted, sans)
        meta.maxLines = 1
        meta.ellipsize = TextUtils.TruncateAt.MIDDLE
        col.addView(meta, lp(MATCH, WRAP, top = dp(4)))

        row.addView(col, lp(0, WRAP, 1f, left = dp(16)))
        outer.addView(row, lp(MATCH, WRAP))

        val divider = View(this)
        divider.setBackgroundColor(cHair)
        outer.addView(divider, lp(MATCH, hair(), left = dp(70), right = dp(24)))

        return RowHolder(outer, badge, name, meta, badgeOn, badgeOff)
    }

    private fun bindRow(h: RowHolder, entry: DocEntry) {
        h.name.text = entry.name
        val order = orderMap[entry.docId]
        if (order != null) {
            h.badge.text = (order + 1).toString()
            h.badge.setTextColor(Color.WHITE)
            h.badge.background = h.badgeOn
            h.root.setBackgroundColor(cAccentRow)
            h.meta.text = if (curBase.isEmpty()) {
                "Add a base name to rename"
            } else {
                "→  " + targetNameFor(order, entry)
            }
            h.meta.setTextColor(cAccent)
        } else {
            h.badge.text = ""
            h.badge.background = h.badgeOff
            h.root.setBackgroundColor(Color.TRANSPARENT)
            val type = extensionOf(entry.name).removePrefix(".").uppercase(Locale.ROOT)
            val size = formatSize(entry.size)
            val typeLabel = if (type.isEmpty()) "FILE" else type
            h.meta.text = if (size.isEmpty()) typeLabel else "$typeLabel   ·   $size"
            h.meta.setTextColor(cMuted)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------------------------------

    private data class DocEntry(
        val docId: String,
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val flags: Int
    )

    private class RenameJob(val entry: DocEntry, val targetName: String) {
        var currentUri: Uri = entry.uri
        var currentName: String = entry.name

        /** 0 = original name, 1 = temporary name, 2 = final name. */
        var stage: Int = 0
    }

    private class RowHolder(
        val root: LinearLayout,
        val badge: TextView,
        val name: TextView,
        val meta: TextView,
        val badgeOn: GradientDrawable,
        val badgeOff: GradientDrawable
    )
}
