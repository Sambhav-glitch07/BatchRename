package com.sambhav.batchrename

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.util.LinkedHashSet

class MainActivity : Activity() {

    data class PickedFile(
        var uri: Uri,
        val originalName: String,
        var displayName: String
    )

    private val TEMPLATE = 1001
    private val OTHERS = 1002

    private var template: PickedFile? = null
    private val otherFiles = mutableListOf<PickedFile>()
    private val successfulFiles = mutableListOf<PickedFile>()
    private val failedFiles = mutableListOf<String>()

    private lateinit var root: LinearLayout
    private lateinit var templateText: TextView
    private lateinit var selectedText: TextView
    private lateinit var preview: LinearLayout
    private lateinit var status: TextView
    private lateinit var renameButton: Button
    private lateinit var uploadButton: Button

    private val bg = Color.rgb(247, 248, 250)
    private val card = Color.WHITE
    private val ink = Color.rgb(27, 31, 39)
    private val muted = Color.rgb(116, 123, 136)
    private val accent = Color.rgb(92, 83, 210)
    private val green = Color.rgb(25, 155, 104)
    private val red = Color.rgb(195, 66, 66)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshUi()
    }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun addGap(v: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(v)))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun makeButton(label: String, primary: Boolean): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else ink)
            background = rounded(if (primary) accent else card, 13)
            minHeight = dp(52)
            stateListAnimator = null
        }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(30), dp(22), dp(30))
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("BATCHRENAME", 12f, accent, true))

        val heading = text("Your files.\nPerfectly named.", 31f, ink, true)
        heading.setPadding(0, dp(12), 0, dp(8))
        root.addView(heading)

        root.addView(
            text(
                "Rename multiple files using one naming template.",
                14f, muted
            )
        )

        addGap(28)
        section("01  /  NAMING TEMPLATE")

        val templateCard = makeCard()
        templateText = text("No template selected", 14f, muted)
        templateCard.addView(templateText)
        root.addView(templateCard)

        val templateButton = makeButton("Choose first file", false)
        templateButton.setOnClickListener {
            openPicker(TEMPLATE, false)
        }
        root.addView(templateButton, matchWrap())

        addGap(24)
        section("02  /  FILE SELECTION")

        val selectedCard = makeCard()
        selectedText = text("No additional files selected", 14f, muted)
        selectedCard.addView(selectedText)
        root.addView(selectedCard)

        val selectButton = makeButton("Select multiple files", false)
        selectButton.setOnClickListener {
            if (template == null) {
                toast("Choose a template first.")
            } else {
                openPicker(OTHERS, true)
            }
        }
        root.addView(selectButton, matchWrap())

        addGap(24)
        section("03  /  NAME PREVIEW")

        preview = makeCard()
        root.addView(preview)

        addGap(16)

        status = text("Choose your first file to begin.", 13f, muted)
        status.gravity = Gravity.CENTER
        root.addView(status, matchWrap())

        renameButton = makeButton("Rename all files", true)
        renameButton.setOnClickListener { renameAll() }
        root.addView(renameButton, matchWrap())

        uploadButton = makeButton("Upload to FTP Rush", false)
        uploadButton.setOnClickListener { uploadToFtpRush() }
        root.addView(uploadButton, matchWrap())

        addGap(16)
        root.addView(
            text(
                "Your original template remains unchanged. File extensions are preserved. Numbering follows selection order.",
                12f, muted
            )
        )
    }

    private fun section(value: String) {
        val label = text(value, 11f, muted, true)
        label.setPadding(0, 0, 0, dp(10))
        root.addView(label)
    }

    private fun makeCard(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) }

    private fun openPicker(code: Int, multiple: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            toast("Unable to open file picker.")
        }
    }

    @Deprecated("Document picker compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data == null) return

        if (requestCode == TEMPLATE) {
            val uri = data.data ?: return
            keepPermission(uri, data)

            val name = queryName(uri) ?: "Selected file"
            template = PickedFile(uri, name, name)

            otherFiles.clear()
            successfulFiles.clear()
            failedFiles.clear()

            status.text = "Template selected. Choose your files."
        }

        if (requestCode == OTHERS) {
            val uris = LinkedHashSet<Uri>()

            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            }

            data.data?.let { uris.add(it) }

            otherFiles.clear()
            successfulFiles.clear()
            failedFiles.clear()

            for (uri in uris) {
                if (uri == template?.uri) continue

                keepPermission(uri, data)

                val name = queryName(uri) ?: "Unknown file"
                otherFiles.add(PickedFile(uri, name, name))
            }

            status.text = "${otherFiles.size} files selected."
        }

        refreshUi()
    }

    private fun keepPermission(uri: Uri, data: Intent) {
        val flags = data.flags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: Exception) {
            // Provider may not support persistent permissions.
        }
    }

    private fun queryName(uri: Uri): String? {
        var cursor: android.database.Cursor? = null

        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )

            if (cursor != null && cursor.moveToFirst()) {
                cursor.getString(0)
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1)
            name.substring(dot) else ""
    }

    private fun baseName(name: String): String {
        val ext = extension(name)
        val base = if (ext.isNotEmpty()) name.dropLast(ext.length) else name

        return base
            .replace(Regex("\\s+\\d+$"), "")
            .trim()
            .ifEmpty { base }
    }

    private fun generatedName(index: Int, file: PickedFile): String {
        val source = template ?: return file.originalName

        return "${baseName(source.originalName)} $index${extension(file.originalName)}"
    }

    private fun refreshUi() {
        templateText.text = template?.originalName ?: "No template selected"

        selectedText.text =
            if (otherFiles.isEmpty()) "No additional files selected"
            else "${otherFiles.size} additional files selected"

        preview.removeAllViews()

        template?.let {
            addPreviewRow("01", it.originalName, green)
        }

        otherFiles.forEachIndexed { index, file ->
            addPreviewRow(
                (index + 2).toString().padStart(2, '0'),
                generatedName(index + 2, file),
                accent
            )
        }

        renameButton.isEnabled = template != null && otherFiles.isNotEmpty()
        renameButton.alpha = if (renameButton.isEnabled) 1f else 0.45f

        uploadButton.isEnabled = successfulFiles.isNotEmpty()
        uploadButton.alpha = if (uploadButton.isEnabled) 1f else 0.45f
    }

    private fun addPreviewRow(number: String, filename: String, color: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }

        val numberText = text(number, 11f, color, true)
        row.addView(numberText)

        val nameText = text(filename, 12f, ink)
        nameText.maxLines = 3
        nameText.setPadding(dp(14), 0, 0, 0)

        row.addView(
            nameText,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        preview.addView(row)

        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(235, 236, 240))
        }

        preview.addView(
            divider,
            LinearLayout.LayoutParams(-1, dp(1))
        )
    }

    private fun renameAll() {
        if (template == null || otherFiles.isEmpty()) return

        successfulFiles.clear()
        failedFiles.clear()

        otherFiles.forEachIndexed { index, file ->
            val newName = generatedName(index + 2, file)

            try {
                val newUri = DocumentsContract.renameDocument(
                    contentResolver,
                    file.uri,
                    newName
                )

                if (newUri != null) {
                    file.uri = newUri
                    file.displayName = newName
                    successfulFiles.add(file)
                } else {
                    failedFiles.add("${file.originalName}: Provider returned no result")
                }
            } catch (e: Exception) {
                failedFiles.add(
                    "${file.originalName}: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }

        val result = "Renamed: ${successfulFiles.size}  |  Failed: ${failedFiles.size}"

        status.text = result
        status.setTextColor(if (failedFiles.isEmpty()) green else red)

        refreshUi()
        showResults()
    }

    private fun showResults() {
        val details = StringBuilder()

        details.append("Successful: ${successfulFiles.size}\n")
        successfulFiles.forEach {
            details.append("✓ ${it.displayName}\n")
        }

        details.append("\nFailed: ${failedFiles.size}\n")
        failedFiles.forEach {
            details.append("• $it\n")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Rename results")
            .setMessage(details.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun uploadToFtpRush() {
        val files = successfulFiles.toList()

        if (files.isEmpty()) {
            toast("No successfully renamed files to upload.")
            return
        }

        val uris = ArrayList<Uri>()
        files.forEach { uris.add(it.uri) }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(
                contentResolver,
                "Renamed files",
                uris.first()
            ).apply {
                for (i in 1 until uris.size) {
                    addItem(
                        android.content.ClipData.Item(uris[i])
                    )
                }
            }
        }

        try {
            val ftpIntent = Intent(intent).apply {
                setPackage("com.wftpserver.ftprush")
            }

            if (ftpIntent.resolveActivity(packageManager) != null) {
                startActivity(ftpIntent)
            } else {
                startActivity(
                    Intent.createChooser(intent, "Send files to FTP Rush")
                )
            }
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent.createChooser(intent, "Share renamed files")
                )
            } catch (_: Exception) {
                toast("Unable to share files.")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
