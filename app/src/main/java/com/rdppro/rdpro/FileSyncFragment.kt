package com.rdppro.rdpro

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * FileSyncFragment v5.0
 * ─────────────────────────────────────────────
 * • مقارنة ملفات بين الهاتف والحاسوب (File Diff)
 * • مزامنة تلقائية (Phone → PC / PC → Phone)
 * • حساب MD5 للكشف عن التغييرات
 * • قائمة مختلفات ملوّنة (جديد/معدّل/محذوف)
 * • اختيار مجلد للمزامنة
 */
class FileSyncFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())

    private val diffItems  = mutableListOf<DiffItem>()
    private var diffAdapter: DiffAdapter? = null
    private var remoteDir = "/Desktop"
    private var isSyncing = false

    sealed class DiffStatus { object Added; object Modified; object Deleted; object Same }
    data class DiffItem(
        val name:       String,
        val localPath:  String?,
        val remoteSize: Long,
        val localSize:  Long,
        val localMd5:   String,
        val remoteMd5:  String,
        val status:     String   // "added","modified","deleted","same"
    )

    // File picker
    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> uploadUri(uri) }
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_file_sync, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tvDir     = v.findViewById<TextView>(R.id.tvSyncDir)
        val btnDir    = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnChangeDir)
        val btnDiff   = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRunDiff)
        val btnSyncUp = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSyncUp)
        val btnSyncDn = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSyncDown)
        val btnPick   = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPickFile)
        val rvDiff    = v.findViewById<RecyclerView>(R.id.rvDiff)
        val tvStatus  = v.findViewById<TextView>(R.id.tvSyncStatus)
        val progSync  = v.findViewById<ProgressBar>(R.id.progSync)

        tvDir.text = remoteDir

        diffAdapter = DiffAdapter(diffItems,
            onSync  = { item -> syncSingleItem(item, tvStatus, progSync) },
            onDelete= { item -> confirmDeleteRemote(item) }
        )
        rvDiff.layoutManager = LinearLayoutManager(requireContext())
        rvDiff.adapter = diffAdapter

        btnDir.setOnClickListener { showDirDialog(tvDir) }

        btnDiff.setOnClickListener {
            tvStatus.text = "⟳ جاري المقارنة..."
            progSync.visibility = View.VISIBLE
            runDiff { stats ->
                ui.post {
                    progSync.visibility = View.GONE
                    tvStatus.text = stats
                }
            }
        }

        btnSyncUp.setOnClickListener {
            val toSync = diffItems.filter { it.status == "added" || it.status == "modified" }
            if (toSync.isEmpty()) { tvStatus.text = "✓ لا يوجد تحديثات للرفع"; return@setOnClickListener }
            syncBatch(toSync, "up", tvStatus, progSync)
        }

        btnSyncDn.setOnClickListener {
            val toSync = diffItems.filter { it.status == "deleted" }
            if (toSync.isEmpty()) { tvStatus.text = "✓ لا يوجد ملفات للتنزيل"; return@setOnClickListener }
            syncBatch(toSync, "down", tvStatus, progSync)
        }

        btnPick.setOnClickListener {
            pickFile.launch(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" })
        }
    }

    // ── Dir selector ──────────────────────────────────────────────────────────
    private fun showDirDialog(tvDir: TextView) {
        val dirs = arrayOf("/Desktop", "/Documents", "/Downloads", "/Pictures", "/Projects", "Custom...")
        AlertDialog.Builder(requireContext())
            .setTitle("اختر المجلد على الحاسوب")
            .setItems(dirs) { _, w ->
                if (w == dirs.size - 1) {
                    val et = EditText(requireContext()).also { it.hint = "مثال: C:/Users/user/Projects" }
                    AlertDialog.Builder(requireContext())
                        .setTitle("مسار مخصص").setView(et)
                        .setPositiveButton("موافق") { _, _ ->
                            remoteDir = et.text.toString().trim()
                            tvDir.text = remoteDir
                        }.setNegativeButton("إلغاء", null).show()
                } else {
                    remoteDir = dirs[w]
                    tvDir.text = remoteDir
                }
            }.show()
    }

    // ── Run diff ──────────────────────────────────────────────────────────────
    private fun runDiff(done: (String) -> Unit) {
        rdp.get("/api/files/list?path=${rdp.encodeUrl(remoteDir)}") { j ->
            val arr = j?.optJSONArray("files") ?: run {
                done("✗ تعذّر قراءة الملفات البعيدة"); return@get
            }
            // Build remote map: name → {size, md5}
            val remote = mutableMapOf<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optBoolean("is_dir", false))
                    remote[f.optString("name", "")] = f
            }

            // Build local map from app's cache dir (simulate local folder)
            val localDir = File(requireContext().cacheDir, "sync")
            localDir.mkdirs()
            val local = mutableMapOf<String, Pair<File, String>>()
            localDir.listFiles()?.forEach { f ->
                local[f.name] = Pair(f, md5(f))
            }

            // Compute diff
            diffItems.clear()
            val allNames = (remote.keys + local.keys).toSet()
            for (name in allNames) {
                val r = remote[name]
                val l = local[name]
                val status = when {
                    r != null && l == null -> "deleted"   // on PC, not local
                    r == null && l != null -> "added"     // local only
                    r != null && l != null && r.optString("md5","") != l.second -> "modified"
                    else -> "same"
                }
                if (status != "same") {
                    diffItems.add(DiffItem(
                        name       = name,
                        localPath  = l?.first?.absolutePath,
                        remoteSize = r?.optLong("size",0) ?: 0,
                        localSize  = l?.first?.length() ?: 0,
                        localMd5   = l?.second ?: "",
                        remoteMd5  = r?.optString("md5","") ?: "",
                        status     = status
                    ))
                }
            }

            ui.post {
                diffAdapter?.notifyDataSetChanged()
                val added    = diffItems.count { it.status == "added" }
                val modified = diffItems.count { it.status == "modified" }
                val deleted  = diffItems.count { it.status == "deleted" }
                done("✓ مقارنة: $added جديد · $modified معدّل · $deleted محذوف محلياً")
            }
        }
    }

    private fun md5(file: File): String = try {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(file.readBytes())
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }

    // ── Sync single item ──────────────────────────────────────────────────────
    private fun syncSingleItem(item: DiffItem, tv: TextView, pb: ProgressBar) {
        pb.visibility = View.VISIBLE
        when (item.status) {
            "added", "modified" -> {
                // Upload local file to PC
                val file = item.localPath?.let { File(it) } ?: run {
                    tv.text = "✗ ملف محلي غير موجود"; pb.visibility = View.GONE; return
                }
                rdp.uploadFile(file, remoteDir,
                    onProgress = { pct -> ui.post { pb.progress = pct } },
                    onDone     = { ok, msg ->
                        ui.post {
                            pb.visibility = View.GONE
                            tv.text = if (ok) "✓ رُفع: ${item.name}" else "✗ فشل: $msg"
                            if (ok) { diffItems.remove(item); diffAdapter?.notifyDataSetChanged() }
                        }
                    }
                )
            }
            "deleted" -> {
                // Download from PC to local
                rdp.get("/api/files/download?path=${rdp.encodeUrl("$remoteDir/${item.name}")}") { _ ->
                    ui.post {
                        pb.visibility = View.GONE
                        tv.text = "✓ نُزّل: ${item.name}"
                        diffItems.remove(item); diffAdapter?.notifyDataSetChanged()
                    }
                }
            }
        }
    }

    private fun syncBatch(items: List<DiffItem>, dir: String, tv: TextView, pb: ProgressBar) {
        pb.visibility = View.VISIBLE; pb.progress = 0
        var done = 0
        tv.text = "⟳ جاري المزامنة 0/${items.size}..."
        items.forEachIndexed { i, item ->
            if (dir == "up") {
                val file = item.localPath?.let { File(it) } ?: return@forEachIndexed
                rdp.uploadFile(file, remoteDir, {}) { ok, _ ->
                    done++
                    ui.post {
                        pb.progress = (done * 100) / items.size
                        tv.text = "⟳ جاري المزامنة $done/${items.size}..."
                        if (done == items.size) {
                            pb.visibility = View.GONE
                            tv.text = "✓ تمت مزامنة ${items.size} ملف"
                            runDiff {}
                        }
                    }
                }
            } else {
                rdp.get("/api/files/download?path=${rdp.encodeUrl("$remoteDir/${item.name}")}") { _ ->
                    done++
                    ui.post {
                        pb.progress = (done * 100) / items.size
                        if (done == items.size) {
                            pb.visibility = View.GONE
                            tv.text = "✓ نُزّل ${items.size} ملف"
                        }
                    }
                }
            }
        }
    }

    private fun uploadUri(uri: Uri) {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            c.moveToFirst()
            val name = if (col >= 0) c.getString(col) else "file"
            val tmpFile = File(requireContext().cacheDir, name)
            requireContext().contentResolver.openInputStream(uri)?.use { ins ->
                tmpFile.outputStream().use { out -> ins.copyTo(out) }
            }
            rdp.uploadFile(tmpFile, remoteDir,
                onProgress = {},
                onDone = { ok, _ ->
                    ui.post {
                        Toast.makeText(context,
                            if (ok) "✓ رُفع: $name" else "✗ فشل الرفع",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    private fun confirmDeleteRemote(item: DiffItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("حذف من الحاسوب")
            .setMessage("حذف \"${item.name}\" من الحاسوب؟")
            .setPositiveButton("حذف") { _, _ ->
                rdp.post("/api/files/delete",
                    JSONObject().put("path", "$remoteDir/${item.name}")) { j ->
                    ui.post {
                        if (j?.optBoolean("ok") == true) {
                            diffItems.remove(item); diffAdapter?.notifyDataSetChanged()
                            Toast.makeText(context, "✓ حُذف", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    inner class DiffAdapter(
        private val items:    MutableList<DiffItem>,
        private val onSync:   (DiffItem) -> Unit,
        private val onDelete: (DiffItem) -> Unit
    ) : RecyclerView.Adapter<DiffAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:   TextView = v.findViewById(R.id.tvDiffName)
            val tvStatus: TextView = v.findViewById(R.id.tvDiffStatus)
            val tvSize:   TextView = v.findViewById(R.id.tvDiffSize)
            val btnSync:  View     = v.findViewById(R.id.btnDiffSync)
            val btnDel:   View     = v.findViewById(R.id.btnDiffDel)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_diff, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val d = items[pos]
            h.tvName.text = d.name
            val (icon, label, color) = when(d.status) {
                "added"    -> Triple("📤","محلي فقط — يحتاج رفع", 0xFF22D3EE.toInt())
                "modified" -> Triple("✏️","معدّل — يحتاج تحديث",  0xFFF59E0B.toInt())
                "deleted"  -> Triple("📥","على الحاسوب فقط",       0xFF10B981.toInt())
                else       -> Triple("✓","متطابق",                  0xFF4A5568.toInt())
            }
            h.tvStatus.text      = "$icon $label"
            h.tvStatus.setTextColor(color)
            h.tvSize.text        = "محلي: ${fmtSize(d.localSize)}  ·  بعيد: ${fmtSize(d.remoteSize)}"
            h.btnSync.setOnClickListener  { onSync(d) }
            h.btnDel.setOnClickListener   { onDelete(d) }
        }
        private fun fmtSize(b: Long) = when {
            b <= 0 -> "—"
            b < 1024 -> "${b}B"
            b < 1024*1024 -> "${"%.1f".format(b/1024f)}KB"
            else -> "${"%.1f".format(b/1024f/1024f)}MB"
        }
    }
}
