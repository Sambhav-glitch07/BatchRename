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
import java.io.IOException
import java.util.LinkedHashMap

class MainActivity : Activity() {

    private data class FileItem(
        val uri: Uri,
        val name: String,
        val mime: String
    )

    private val folderRequest = 101
    private val ftpPackage = "com.wftpserver.ftprush"

    private var folderUri: Uri? = null
    private val files = mutableListOf<FileItem>()
    private val selected = LinkedHashMap<Uri, FileItem>()
    private val uploadedReady = mutableListOf<Uri>()

    private lateinit var root: LinearLayout
    private lateinit var folderLabel: TextView
    private lateinit var filenameInput: EditText
    private lateinit var fileList: LinearLayout
    private lateinit var preview: TextView
    private lateinit var status: TextView
    private lateinit var renameButton: Button
    private lateinit var uploadButton: Button

    private val bg = Color.rgb(247, 248, 250)
    private val ink = Color.rgb(30, 32, 40)
    private val muted = Color.rgb(115, 120, 133)
    private val accent = Color.rgb(91, 82, 205)
    private val green = Color.rgb(28, 145, 99)
    private val red = Color.rgb(190, 60, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refresh()
    }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(value: String, primary: Boolean) =
        Button(this).apply {
            text = value
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) Color.WHITE else ink)
            background = rounded(
                if (primary) accent else Color.WHITE, 13
            )
            minHeight = dp(50)
            stateListAnimator = null
        }

    private fun gap(v: Int) {
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(v)))
    }

    private fun section(value: String) {
        val t = label(value, 11f, muted, true)
        t.setPadding(0, 0, 0, dp(9))
        root.addView(t)
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 15)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }

    private fun buttonParams() =
        LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(9)
        }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(28), dp(21), dp(30))
        }

        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("BATCHRENAME", 12f, accent, true))

        val heading = label("Rename files.\nYour way.", 31f, ink, true)
        heading.setPadding(0, dp(12), 0, dp(7))
        root.addView(heading)

        root.addView(
            label("Choose a folder, enter a filename and rename multiple files.", 14f, muted)
        )

        gap(25)
        section("01  /  DESTINATION FOLDER")

        val folderCard = card()
        folderLabel = label("No folder selected", 13f, muted)
        folderCard.addView(folderLabel)
        root.addView(folderCard)

        val folderButton = button("Choose folder", false)
        folderButton.setOnClickListener { chooseFolder() }
        root.addView(folderButton, buttonParams())

        gap(23)
        section("02  /  CUSTOM FILENAME")

        filenameInput = EditText(this).apply {
            hint = "e.g. 09 Nov Patna Food Safety Department Raid"
            textSize = 14f
            setTextColor(ink)
            setHintTextColor(muted)
            singleLine = true
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = rounded(Color.WHITE, 13)
        }
        root.addView(filenameInput, buttonParams())

        gap(23)
        section("03  /  SELECT FILES")

        fileList = card()
        fileList.addView(label("Choose a folder first.", 13f, muted))
        root.addView(fileList)

        val clearButton = button("Clear selection", false)
        clearButton.setOnClickListener {
            selected.clear()
            refresh()
        }
        root.addView(clearButton, buttonParams())

        gap(23)
        section("04  /  PREVIEW")

        val previewCard = card()
        preview = label("Your new filenames will appear here.", 13f, muted)
        previewCard.addView(preview)
        root.addView(previewCard)

        gap(15)

        status = label("Ready.", 13f, muted)
        status.gravity = Gravity.CENTER
        root.addView(status, buttonParams())

        renameButton = button("Rename selected files", true)
        renameButton.setOnClickListener { renameFiles() }
        root.addView(renameButton, buttonParams())

        uploadButton = button("Upload to FTP Rush", false)
        uploadButton.setOnClickListener { uploadFiles() }
        root.addView(uploadButton, buttonParams())

        gap(15)
        root.addView(
            label(
                "Renaming creates new copies and deletes originals only after successful verification. If deletion is refused, the original is kept.",
                12f, muted
            )
        )

        filenameInput.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) {}

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    updatePreview()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        startActivityForResult(intent, folderRequest)
    }

    @Deprecated("Document picker compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != folderRequest ||
            resultCode != RESULT_OK || data?.data == null
        ) return

        val uri = data.data!!

        try {
            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            status.text = "Folder permission error: ${e.message}"
            return
        }

        folderUri = uri
        selected.clear()
        uploadedReady.clear()
        loadFolder()
    }

    private fun loadFolder() {
        val tree = folderUri ?: return

        files.clear()

        try {
            val documentId = DocumentsContract.getTreeDocumentId(tree)
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, documentId
                )

            val cursor = contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    val mime = it.getString(2)

                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        val uri =
                            DocumentsContract.buildDocumentUriUsingTree(
                                tree, id
                            )
                        files.add(FileItem(uri, name, mime))
                    }
                }
            }

            folderLabel.text = "Folder selected · ${files.size} files"
            status.text = "Select files in the order you want numbered."
            renderFiles()

        } catch (e: Exception) {
            status.text = "Could not read folder: ${e.message}"
        }
    }

    private fun renderFiles() {
        fileList.removeAllViews()

        if (files.isEmpty()) {
            fileList.addView(label("No files found in this folder.", 13f, muted))
            return
        }

        files.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }

            val checkbox = CheckBox(this).apply {
                text = file.name
                textSize = 12f
                setTextColor(ink)
                isChecked = selected.containsKey(file.uri)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selected[file.uri] = file
                    } else {
                        selected.remove(file.uri)
                    }
                    updatePreview()
                }
            }

            row.addView(
                checkbox,
                LinearLayout.LayoutParams(-1, -2)
            )

            fileList.addView(row)
        }

        updatePreview()
    }

    private fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1)
            name.substring(dot) else ""
    }

    private fun cleanBase(): String {
        return filenameInput.text.toString()
            .trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim('.')
    }

    private fun targetName(index: Int, file: FileItem): String {
        return "${cleanBase()} $index${extension(file.name)}"
    }

    private fun updatePreview() {
        if (!::preview.isInitialized) return

        val base = cleanBase()

        if (base.isBlank() || selected.isEmpty()) {
            preview.text = "Enter a filename and select files."
            return
        }

        val result = selected.values.mapIndexed { index, file ->
            targetName(index + 1, file)
        }

        preview.text = result.joinToString("\n\n")
    }

    private fun renameFiles() {
        val tree = folderUri ?: return
        val base = cleanBase()
        val chosen = selected.values.toList()

        if (base.isBlank()) {
            toast("Enter a filename first.")
            return
        }

        if (chosen.isEmpty()) {
            toast("Select at least one file.")
            return
        }

        uploadedReady.clear()

        val existingNames = files.map { it.name }.toMutableSet()
        var copied = 0
        var deleted = 0
        val errors = mutableListOf<String>()

        chosen.forEachIndexed { index, file ->
            val name = targetName(index + 1, file)

            if (name in existingNames && name != file.name) {
                errors.add("$name already exists")
                return@forEachIndexed
            }

            try {
                val outputUri = DocumentsContract.createDocument(
                    contentResolver,
                    tree,
                    file.mime.ifBlank { "application/octet-stream" },
                    name
                ) ?: throw IOException("Could not create destination file")

                try {
                    val input = contentResolver.openInputStream(file.uri)
                        ?: throw IOException("Cannot read original file")

                    val output = contentResolver.openOutputStream(outputUri)
                        ?: throw IOException("Cannot write destination file")

                    var bytes = 0L

                    input.use { source ->
                        output.use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                destination.write(buffer, 0, count)
                                bytes += count
                            }
                            destination.flush()
                        }
                    }

                    val copiedSize = querySize(outputUri)

                    if (copiedSize >= 0 && copiedSize != bytes) {
                        throw IOException("Copy verification failed")
                    }

                    copied++
                    existingNames.add(name)
                    uploadedReady.add(outputUri)

                    try {
                        if (DocumentsContract.deleteDocument(
                                contentResolver, file.uri
                            )
                        ) {
                            deleted++
                        } else {
                            errors.add("$name copied, but original could not be deleted")
                        }
                    } catch (e: Exception) {
                        errors.add("$name copied, but original could not be deleted: ${e.message}")
                    }

                } catch (e: Exception) {
                    try {
                        DocumentsContract.deleteDocument(contentResolver, outputUri)
                    } catch (_: Exception) {}
                    throw e
                }

            } catch (e: Exception) {
                errors.add("${file.name}: ${e.message ?: "Unknown error"}")
            }
        }

        status.text = "Created: $copied · Originals deleted: $deleted · Issues: ${errors.size}"
        status.setTextColor(if (errors.isEmpty()) green else red)

        if (errors.isNotEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Rename results")
                .setMessage(
                    "Created: $copied\nOriginals deleted: $deleted\n\n" +
                    errors.joinToString("\n\n")
                )
                .setPositiveButton("OK", null)
                .show()
        } else {
            toast("Successfully renamed $copied files.")
        }

        loadFolder()
    }

    private fun querySize(uri: Uri): Long {
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null, null, null
            )?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0)
                else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun uploadFiles() {
        if (uploadedReady.isEmpty()) {
            toast("Rename files before uploading.")
            return
        }

        val uris = ArrayList(uploadedReady)

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            clipData = android.content.ClipData.newUri(
                contentResolver,
                "BatchRename files",
                uris.first()
            ).apply {
                for (i in 1 until uris.size) {
                    addItem(android.content.ClipData.Item(uris[i]))
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
                    Intent.createChooser(intent, "Upload renamed files")
                )
            }
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent.createChooser(intent, "Share files")
                )
            } catch (_: Exception) {
                toast("Could not open FTP Rush.")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
