package com.rdppro.rdpro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File

/**
 * FilesFragment v3 — مدير الملفات الكامل
 * - تصفح + breadcrumb + روابط سريعة
 * - رفع ملفات من الهاتف → الحاسوب (WebSocket)
 * - تحميل ملفات من الحاسوب → الهاتف
 * - حذف / إعادة تسمية
 * - إنشاء مجلد
 */
class FilesFragment : Fragment() {

    companion object {
        private const val PICK_FILE_RC = 9001
    }

    private lateinit var rdp: RdpService
    private val pathStack = ArrayDeque<String>()

    // Views
    private var tvPath:    TextView?               = null
    private var btnUp:     View?                   = null
    private var btnHome:   View?                   = null
    private var rvFiles:   RecyclerView?           = null
    private var chipGroup: ChipGroup?              = null
    private var progress:  LinearProgressIndicator? = null
    private var tvEmpty:   TextView?               = null
    private var fileAdapter: FileAdapter?          = null
    private var btnNewFolder: View?                = null
    private var btnUpload: View?                   = null

    private var pendingUploadUri: Uri? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_files, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        tvPath    = v.findViewById(R.id.tvFilePath)
        btnUp     = v.findViewById(R.id.btnUp)
        btnHome   = v.findViewById(R.id.btnHome)
        rvFiles   = v.findViewById(R.id.rvFiles)
        chipGroup = v.findViewById(R.id.chipQuickLinks)
        progress  = v.findViewById(R.id.fileProgress)
        tvEmpty   = v.findViewById(R.id.tvEmpty)
        btnNewFolder = v.findViewById(R.id.btnNewFolder)
        btnUpload    = v.findViewById(R.id.btnUpload)

        fileAdapter = FileAdapter(
            onOpen   = { item -> if (item.isDir) openDir(item.path) },
            onAction = { item -> showFileMenu(item) }
        )
        rvFiles?.layoutManager = LinearLayoutManager(requireContext())
        rvFiles?.adapter       = fileAdapter

        btnUp?.setOnClickListener {
            if (pathStack.size > 1) { pathStack.removeLast(); loadPath(pathStack.last()) }
        }
        btnHome?.setOnClickListener {
            pathStack.clear()
        }
        btnNewFolder?.setOnClickListener { promptNewFolder() }
        btnUpload?.setOnClickListener    { pickFileToUpload() }

        loadQuickLinks()
    }

    // ── Quick Links ───────────────────────────────────────────────────────────
    private fun loadQuickLinks() {
        rdp.get("/api/files/quick_links") { j ->
            if (j == null) return@get
            activity?.runOnUiThread {
                chipGroup?.removeAllViews()
                j.keys().forEach { key ->
                    val path = j.getString(key)
                    val chip = Chip(requireContext()).apply {
                        text    = key
                        textSize = 10f
                        isCheckable = false
                        setOnClickListener { openDir(path) }
                    }
                    chipGroup?.addView(chip)
                }
                // فتح أول رابط (Home) تلقائياً
                val firstPath = j.optString(j.keys().asSequence().firstOrNull() ?: return@runOnUiThread)
                if (pathStack.isEmpty() && firstPath.isNotEmpty()) openDir(firstPath)
            }
        }
    }

    private fun openDir(path: String) {
        if (pathStack.isEmpty() || pathStack.last() != path) pathStack.addLast(path)
        loadPath(path)
    }

    private fun loadPath(path: String) {
        tvPath?.text = path
        progress?.visibility = View.VISIBLE
        rdp.get("/api/files/list?path=${rdp.encodeUrl(path)}") { j ->
            activity?.runOnUiThread {
                progress?.visibility = View.GONE
                if (j == null) { showToast("فشل تحميل المجلد"); return@runOnUiThread }
                val arr   = j.optJSONArray("files") ?: run { tvEmpty?.visibility = View.VISIBLE; return@runOnUiThread }
                val items = mutableListOf<FileItem>()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    items.add(FileItem(
                        name  = f.optString("name"),
                        path  = f.optString("path"),
                        isDir = f.optBoolean("is_dir"),
                        sizeH = f.optString("size_h",""),
                        mtime = f.optLong("mtime",0),
                    ))
                }
                tvEmpty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                fileAdapter?.update(items)
            }
        }
    }

    // ── File Actions Menu ─────────────────────────────────────────────────────
    private fun showFileMenu(item: FileItem) {
        val options = if (item.isDir)
            arrayOf("فتح", "إعادة التسمية", "حذف")
        else
            arrayOf("تحميل للهاتف", "إعادة التسمية", "حذف")

        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (item.isDir) openDir(item.path) else downloadFile(item)
                    1 -> promptRename(item)
                    2 -> confirmDelete(item)
                }
            }.show()
    }

    private fun downloadFile(item: FileItem) {
        showToast("⟳ جاري التحميل...")
        // تحميل مباشر عبر HTTP
        val url = "http://${rdp.host}:${rdp.port}/api/files/download?path=${rdp.encodeUrl(item.path)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun promptRename(item: FileItem) {
        val et = EditText(requireContext()).apply { setText(item.name) }
        AlertDialog.Builder(requireContext())
            .setTitle("إعادة التسمية")
            .setView(et)
            .setPositiveButton("تأكيد") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton
                val parentPath = item.path.substringBeforeLast("/").substringBeforeLast("\\")
                val separator  = if (item.path.contains("/")) "/" else "\\"
                val newPath    = "$parentPath$separator$newName"
                rdp.post("/api/files/rename",
                    JSONObject().put("src", item.path).put("dst", newPath)
                ) { j ->
                    activity?.runOnUiThread {
                        if (j?.optBoolean("ok") == true) {
                            showToast("✓ تمت إعادة التسمية")
                            loadPath(pathStack.last())
                        } else showToast("✗ ${j?.optString("error","فشل")}")
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun confirmDelete(item: FileItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("حذف ${item.name}؟")
            .setMessage("لا يمكن التراجع عن هذا الإجراء.")
            .setPositiveButton("حذف") { _, _ ->
                rdp.post("/api/files/delete",
                    JSONObject().put("path", item.path)
                ) { j ->
                    activity?.runOnUiThread {
                        if (j?.optBoolean("ok") == true) {
                            showToast("✓ تم الحذف")
                            loadPath(pathStack.last())
                        } else showToast("✗ ${j?.optString("error","فشل")}")
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun promptNewFolder() {
        val et = EditText(requireContext()).apply { hint = "اسم المجلد" }
        AlertDialog.Builder(requireContext())
            .setTitle("مجلد جديد")
            .setView(et)
            .setPositiveButton("إنشاء") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val current = pathStack.lastOrNull() ?: return@setPositiveButton
                val sep     = if (current.contains("/")) "/" else "\\"
                rdp.post("/api/files/mkdir",
                    JSONObject().put("path", "$current$sep$name")
                ) { j ->
                    activity?.runOnUiThread {
                        if (j?.optBoolean("ok") == true) {
                            showToast("✓ تم إنشاء المجلد")
                            loadPath(current)
                        } else showToast("✗ فشل الإنشاء")
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ── Upload ────────────────────────────────────────────────────────────────
    private fun pickFileToUpload() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), PICK_FILE_RC)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == PICK_FILE_RC && res == Activity.RESULT_OK) {
            val uri  = data?.data ?: return
            val name = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
            val dest = pathStack.lastOrNull() ?: ""
            // نسخ إلى ملف مؤقت ثم رفع
            val tmp  = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            progress?.visibility = View.VISIBLE
            showToast("⟳ جاري الرفع: $name")
            rdp.uploadFile(
                file     = tmp,
                destDir  = dest,
                onProgress = { pct ->
                    activity?.runOnUiThread {
                        progress?.progress = pct
                    }
                },
                onDone   = { ok, path ->
                    tmp.delete()
                    activity?.runOnUiThread {
                        progress?.visibility = View.GONE
                        if (ok) {
                            showToast("✓ تم الرفع: $name")
                            loadPath(dest)
                        } else showToast("✗ فشل الرفع: $path")
                    }
                }
            )
        }
    }

    private fun getFileName(uri: Uri): String? {
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun showToast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        tvPath = null; rvFiles = null; progress = null
    }

    // ── File Data Model ───────────────────────────────────────────────────────
    data class FileItem(
        val name: String, val path: String, val isDir: Boolean,
        val sizeH: String, val mtime: Long
    )

    // ── RecyclerView Adapter ──────────────────────────────────────────────────
    inner class FileAdapter(
        private val onOpen:   (FileItem) -> Unit,
        private val onAction: (FileItem) -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private val items = mutableListOf<FileItem>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName:   TextView = view.findViewById(R.id.tvFileName)
            val tvMeta:   TextView = view.findViewById(R.id.tvFileMeta)
            val tvIcon:   TextView = view.findViewById(R.id.tvFileIcon)
            val btnMore:  View     = view.findViewById(R.id.btnFileMore)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_file, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            h.tvName.text = f.name
            h.tvMeta.text = if (f.isDir) "مجلد" else f.sizeH
            h.tvIcon.text = when {
                f.isDir                             -> "📁"
                f.name.endsWith(".pdf")             -> "📄"
                f.name.endsWith(".jpg", true) ||
                f.name.endsWith(".png", true) ||
                f.name.endsWith(".jpeg",true)       -> "🖼️"
                f.name.endsWith(".zip") ||
                f.name.endsWith(".rar")             -> "📦"
                f.name.endsWith(".mp4") ||
                f.name.endsWith(".mkv") ||
                f.name.endsWith(".avi")             -> "🎬"
                f.name.endsWith(".mp3") ||
                f.name.endsWith(".wav")             -> "🎵"
                f.name.endsWith(".kt")  ||
                f.name.endsWith(".py")  ||
                f.name.endsWith(".js")              -> "📝"
                else                               -> "📄"
            }
            h.itemView.setOnClickListener { onOpen(f) }
            h.btnMore.setOnClickListener  { onAction(f) }
        }

        fun update(list: List<FileItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }
    }
}

// Extension for missing ?.setOnClickListener
private fun View?.setOnClickListener(block: () -> Unit) =
    this?.setOnClickListener { block() }
