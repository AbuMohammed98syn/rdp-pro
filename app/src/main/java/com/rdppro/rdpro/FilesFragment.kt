package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

class FilesFragment : Fragment() {
    private lateinit var rdp: RdpService
    private val stack = ArrayDeque<String>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_files, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tvPath = v.findViewById<TextView>(R.id.tvFilePath)
        val lv     = v.findViewById<ListView>(R.id.lvFiles)
        val pb     = v.findViewById<ProgressBar>(R.id.filesProgress)
        val ql     = v.findViewById<LinearLayout>(R.id.quickLinks)

        v.findViewById<Button>(R.id.btnUp).setOnClickListener {
            if (stack.size > 1) { stack.removeLast(); loadPath(stack.last(), tvPath, lv, pb) }
        }

        rdp.get("/api/files/quick_links") { j ->
            if (j == null) return@get
            j.keys().forEach { key ->
                val path = j.getString(key)
                Button(requireContext()).apply {
                    text = key; textSize = 10f; setPadding(20,6,20,6)
                    setOnClickListener { stack.clear(); stack.addLast(path); loadPath(path, tvPath, lv, pb) }
                    ql.addView(this)
                }
            }
        }

        lv.setOnItemClickListener { _, _, pos, _ ->
            val item = lv.adapter.getItem(pos) as? String ?: return@setOnItemClickListener
            val tag  = lv.getChildAt(pos - (lv.firstVisiblePosition))?.tag as? String ?: return@setOnItemClickListener
            if (tag == "dir") { stack.addLast(item); loadPath(item, tvPath, lv, pb) }
        }
    }

    private fun loadPath(path: String, tvPath: TextView, lv: ListView, pb: ProgressBar) {
        tvPath.text = path; pb.visibility = View.VISIBLE
        rdp.get("/api/files/list?path=${rdp.encodeUrl(path)}") { j ->
            pb.visibility = View.GONE
            val files = j?.optJSONArray("files") ?: return@get
            val labels = mutableListOf<String>()
            val paths  = mutableListOf<String>()
            val types  = mutableListOf<String>()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val isDir = f.optBoolean("is_dir")
                val icon  = if (isDir) "📁" else "📄"
                labels.add("$icon ${f.optString("name")}   ${f.optString("size_h","")}")
                paths.add(f.optString("path",""))
                types.add(if(isDir) "dir" else "file")
            }
            val ctx = context ?: return@get
            lv.adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, labels) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val view = super.getView(pos, cv, parent)
                    view.tag = types[pos]
                    view.setOnClickListener {
                        if (types[pos] == "dir") {
                            stack.addLast(paths[pos])
                            loadPath(paths[pos], tvPath, lv, pb)
                        }
                    }
                    return view
                }
            }
        }
    }
}
