package com.sambhav.batchrename

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.LinkedHashSet

class MainActivity : Activity() {

    private data class PickedFile(
        var uri: Uri,
        val originalName: String,
        var displayName: String
    )

    private val requestTemplate = 1001
    private val requestOthers = 1002

    private var template: PickedFile? = null
    private val otherFiles = mutableListOf<PickedFile>()

    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var templateText: TextView
    private lateinit var selectedText: TextView
    private lateinit var previewContainer: LinearLayout
    private lateinit var renameButton: Button

    private val bg = Color.rgb(16, 17, 26)
    private val panel = Color.rgb(29, 31, 45)
    private val accent = Color.rgb(126, 110, 255)
    private val primaryText = Color.rgb(245, 245, 250)
    private val secondaryText = Color.rgb(164, 166, 184)
    private val green = Color.rgb(102, 218, 164)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
        }
        scroll.addView(content)
        setContentView(scroll)

        val brand = TextView(this).apply {
            text = "BATCHRENAME"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(brand)

        val title = TextView(this).apply {
            text = "Rename files.\nIn one go."
            textSize = 32f
            setTextColor(primaryText)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        }
        content.addView(title)

        val subtitle = TextView(this).apply {
            text = "Create a naming pattern from your first file, then number the rest in the order you select them."
            textSize = 14f
            setTextColor(secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(subtitle)

        addGap(24)
        addSectionLabel("01  ·  CHOOSE YOUR TEMPLATE")
        val templateCard = makeCard()
        templateText = TextView(this).apply {
            text = "No template selected"
            textSize = 14f
            setTextColor(secondaryText)
        }
        templateCard.addView(templateText)
        content.addView(templateCard)

        val templateButton = makeButton("Choose first file", false)
        templateButton.setOnClickListener { openPicker(requestTemplate, false) }
        content.addView(templateButton, matchWrap())

        addGap(22)
        addSectionLabel("02  ·  SELECT THE OTHER FILES")
        val selectedCard = makeCard()
        selectedText = TextView(this).apply {
            text = "No additional files selected"
            textSize = 14f
            setTextColor(secondaryText)
        }
        selectedCard.addView(selectedText)
        content.addView(selectedCard)

        val othersButton = makeButton("Select multiple files", false)
        othersButton.setOnClickListener {
            if (template == null) {
                toast("Choose your first file before selecting the others.")
            } else {
                openPicker(requestOthers, true)
            }
        }
        content.addView(othersButton, matchWrap())

        addGap(22)
        addSectionLabel("03  ·  PREVIEW")
        previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 18)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(previewContainer, matchWrap())

        addGap(18)
        statusText = TextView(this).apply {
            text = "Choose a template to get started."
            textSize = 13f
            setTextColor(secondaryText)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        content.addView(statusText, matchWrap())

        renameButton = makeButton("Rename all files", true)
        renameButton.setOnClickListener { renameAll() }
        content.addView(renameButton, matchWrap())

        val note = TextView(this).apply {
            text = "The first file keeps its current name. Other files receive numbers 2, 3, 4… in picker selection order. File extensions are preserved."
            textSize = 12f
            setTextColor(secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(16), dp(4), 0)
        }
        content.addView(note)
    }

    private fun addSectionLabel(value: String) {
        val label = TextView(this).apply {
            text = value
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(secondaryText)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(10))
        }
        content.addView(label)
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
    }

    private fun makeButton(label: String, filled: Boolean): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) Color.WHITE else primaryText)
            background = rounded(if (filled) accent else panel, 14)
            minHeight = dp(52)
            stateListAnimator = null
        }
    }

    private fun openPicker(requestCode: Int, multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            toast("Could not open the file picker.")
        }
    }

    @Deprecated("Kept for broad Android compatibility with the document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == requestTemplate) {
            val uri = data.data ?: return
            keepPermission(uri, data)
            val name = queryName(uri) ?: "Selected file"
            template = PickedFile(uri, name, name)
            otherFiles.clear()
            statusText.text = "Template selected. Now choose the remaining files."
            refreshUi()
        } else if (requestCode == requestOthers) {
            val selectedUris = LinkedHashSet<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    selectedUris.add(clip.getItemAt(i).uri)
                }
            }
            data.data?.let { selectedUris.add(it) }

            val templateUri = template?.uri
            val newFiles = mutableListOf<PickedFile>()
            for (uri in selectedUris) {
                if (uri == templateUri) continue
                keepPermission(uri, data)
                val name = queryName(uri) ?: "Selected file"
                newFiles.add(PickedFile(uri, name, name))
            }
            otherFiles.clear()
            otherFiles.addAll(newFiles)
            statusText.text = if (otherFiles.isEmpty()) {
                "No additional files selected."
            } else {
                "${otherFiles.size} file(s) ready. Check the preview before renaming."
            }
            refreshUi()
        }
    }

    private fun keepPermission(uri: Uri, data: Intent) {
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Some document providers grant temporary access only.
        }
    }

    private fun queryName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun fileExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(dot) else ""
    }

    private fun baseName(name: String): String {
        val extension = fileExtension(name)
        val withoutExtension = if (extension.isNotEmpty()) name.dropLast(extension.length) else name
        return withoutExtension.replace(Regex("\\s+\\d+$"), "").trim().ifEmpty { withoutExtension }
    }

    private fun generatedName(index: Int, file: PickedFile): String {
        val source = template ?: return file.originalName
        return "${baseName(source.originalName)} $index${fileExtension(file.originalName)}"
    }

    private fun refreshUi() {
        templateText.text = template?.originalName ?: "No template selected"
        templateText.setTextColor(if (template == null) secondaryText else primaryText)
        selectedText.text = if (otherFiles.isEmpty()) {
            "No additional files selected"
        } else {
            "${otherFiles.size} additional file(s) selected"
        }
        selectedText.setTextColor(if (otherFiles.isEmpty()) secondaryText else primaryText)

        previewContainer.removeAllViews()
        val first = template
        if (first == null) {
            addPreviewRow("1", "Your first file will appear here", true)
        } else {
            addPreviewRow("1", first.originalName, true)
            otherFiles.forEachIndexed { index, file ->
                addPreviewRow((index + 2).toString(), generatedName(index + 2, file), false)
            }
        }
        renameButton.isEnabled = template != null && otherFiles.isNotEmpty()
        renameButton.alpha = if (renameButton.isEnabled) 1f else 0.45f
    }

    private fun addPreviewRow(number: String, filename: String, isFirst: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val numberView = TextView(this).apply {
            text = number.padStart(2, '0')
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isFirst) green else accent)
            background = rounded(bg, 8)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        row.addView(numberView)
        val nameView = TextView(this).apply {
            text = filename
            textSize = 12f
            setTextColor(primaryText)
            maxLines = 3
            setPadding(dp(12), 0, 0, 0)
        }
        row.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        previewContainer.addView(row)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(54, 56, 72)) }
        previewContainer.addView(divider, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ))
    }

    private fun renameAll() {
        val source = template ?: return
        if (otherFiles.isEmpty()) return

        var renamed = 0
        val failures = mutableListOf<String>()

        otherFiles.forEachIndexed { index, file ->
            val targetName = generatedName(index + 2, file)
            try {
                val renamedUri = DocumentsContract.renameDocument(contentResolver, file.uri, targetName)
                if (renamedUri != null) {
                    file.uri = renamedUri
                    file.displayName = targetName
                    renamed++
                } else {
                    failures.add(file.originalName)
                }
            } catch (e: Exception) {
                failures.add(file.originalName)
            }
        }

        val message = if (failures.isEmpty()) {
            "Successfully renamed $renamed file(s)."
        } else {
            "Renamed $renamed. Failed: ${failures.size}. Some providers do not support renaming."
        }
        statusText.text = message
        refreshUi()
        toast(message)
    }

    private fun addGap(size: Int) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
