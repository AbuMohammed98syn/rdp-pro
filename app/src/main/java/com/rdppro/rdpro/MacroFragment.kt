package com.rdppro.rdpro

import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

/**
 * MacroFragment v5.0
 * ─────────────────────────────────────────────
 * • تسجيل حركات الماوس والكيبورد والأوامر
 * • تشغيل تلقائي مع تأخير قابل للضبط
 * • حفظ ماكرو بالاسم + تحرير + حذف
 * • إرسال للخادم للتشغيل على الحاسوب
 * • Loop mode (تكرار)
 */
class MacroFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())

    // Recording state
    private var isRecording = false
    private val currentSteps = mutableListOf<MacroStep>()
    private var recordStartTime = 0L

    // Saved macros
    private val savedMacros = mutableListOf<Macro>()
    private var macroAdapter: MacroAdapter? = null
    private var stepAdapter:  StepAdapter?  = null

    data class MacroStep(
        val type:  String,   // mouse_move, mouse_click, key_press, wait, type_text, cmd
        val data:  JSONObject,
        val delay: Long      // ms since last step
    )

    data class Macro(
        val name:   String,
        val steps:  List<MacroStep>,
        var loopCount: Int = 1
    )

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_macro, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val btnRecord  = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMacroRecord)
        val btnStop    = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMacroStop)
        val btnSave    = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMacroSave)
        val btnAddStep = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddStep)
        val rvSteps    = v.findViewById<RecyclerView>(R.id.rvMacroSteps)
        val rvMacros   = v.findViewById<RecyclerView>(R.id.rvMacros)
        val tvRecStatus= v.findViewById<TextView>(R.id.tvRecStatus)
        val tvStepCount= v.findViewById<TextView>(R.id.tvStepCount)

        // Steps adapter (current recording)
        stepAdapter = StepAdapter(currentSteps) { pos ->
            currentSteps.removeAt(pos)
            stepAdapter?.notifyDataSetChanged()
            tvStepCount.text = "${currentSteps.size} خطوة"
        }
        rvSteps.layoutManager = LinearLayoutManager(requireContext())
        rvSteps.adapter = stepAdapter

        // Saved macros adapter
        macroAdapter = MacroAdapter(savedMacros,
            onRun   = { macro -> runMacro(macro) },
            onEdit  = { macro -> loadMacroForEdit(macro) },
            onDelete= { pos  -> confirmDeleteMacro(pos) },
            onLoop  = { macro, count ->
                macro.loopCount = count
                toast("🔄 التكرار: $count مرة")
            }
        )
        rvMacros.layoutManager = LinearLayoutManager(requireContext())
        rvMacros.adapter = macroAdapter

        // Record
        btnRecord.setOnClickListener {
            isRecording = true
            recordStartTime = System.currentTimeMillis()
            currentSteps.clear()
            stepAdapter?.notifyDataSetChanged()
            tvRecStatus.text = "⏺ جاري التسجيل..."
            tvRecStatus.setTextColor(0xFFEF4444.toInt())
            btnRecord.isEnabled = false
            btnStop.isEnabled   = true
            btnSave.isEnabled   = false
            // Start capturing from server events
            startServerCapture()
        }

        // Stop
        btnStop.setOnClickListener {
            isRecording = false
            tvRecStatus.text = "⏹ توقف التسجيل"
            tvRecStatus.setTextColor(0xFF10B981.toInt())
            btnRecord.isEnabled = true
            btnStop.isEnabled   = false
            btnSave.isEnabled   = currentSteps.isNotEmpty()
            tvStepCount.text    = "${currentSteps.size} خطوة مسجّلة"
            stopServerCapture()
        }

        // Add manual step
        btnAddStep.setOnClickListener { showAddStepDialog() }

        // Save macro
        btnSave.setOnClickListener {
            showSaveMacroDialog()
        }

        // Load presets
        loadPresetMacros()
    }

    // ── Server capture ────────────────────────────────────────────────────────
    private fun startServerCapture() {
        rdp.post("/api/macro/record/start") { _ -> }
        // Poll for steps every 500ms
        pollCapture()
    }

    private fun pollCapture() {
        if (!isRecording) return
        rdp.get("/api/macro/record/steps") { j ->
            val arr = j?.optJSONArray("steps") ?: run { ui.postDelayed(::pollCapture, 500); return@get }
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                currentSteps.add(MacroStep(
                    s.optString("type", "unknown"),
                    s.optJSONObject("data") ?: JSONObject(),
                    s.optLong("delay", 100)
                ))
            }
            ui.post {
                stepAdapter?.notifyDataSetChanged()
                view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
            }
        }
        ui.postDelayed(::pollCapture, 500)
    }

    private fun stopServerCapture() {
        rdp.post("/api/macro/record/stop") { _ -> }
    }

    // ── Run macro ─────────────────────────────────────────────────────────────
    private fun runMacro(macro: Macro) {
        val body = JSONObject().apply {
            put("steps", JSONArray().also { arr ->
                macro.steps.forEach { step ->
                    arr.put(JSONObject().apply {
                        put("type",  step.type)
                        put("data",  step.data)
                        put("delay", step.delay)
                    })
                }
            })
            put("loop", macro.loopCount)
            put("name", macro.name)
        }
        rdp.post("/api/macro/run", body) { j ->
            ui.post {
                val ok = j?.optBoolean("ok") == true
                toast(if (ok) "▶ تشغيل: ${macro.name}" else "✗ فشل تشغيل الماكرو")
            }
        }
    }

    // ── Add step dialog ────────────────────────────────────────────────────────
    private fun showAddStepDialog() {
        val types = arrayOf(
            "⌨ كتابة نص", "🖱 ضغط ماوس", "⏱ انتظار", "⌨ مفتاح", "💻 أمر CMD"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("إضافة خطوة")
            .setItems(types) { _, which ->
                when (which) {
                    0 -> showTypeTextStep()
                    1 -> showMouseClickStep()
                    2 -> showWaitStep()
                    3 -> showKeyPressStep()
                    4 -> showCmdStep()
                }
            }.show()
    }

    private fun showTypeTextStep() {
        val et = EditText(requireContext()).also { it.hint = "النص للكتابة" }
        AlertDialog.Builder(requireContext())
            .setTitle("⌨ كتابة نص").setView(et)
            .setPositiveButton("إضافة") { _, _ ->
                val text = et.text.toString()
                if (text.isNotEmpty()) {
                    currentSteps.add(MacroStep("type_text", JSONObject().put("text", text), 200))
                    stepAdapter?.notifyDataSetChanged()
                    view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showMouseClickStep() {
        val btnTypes = arrayOf("يسار", "يمين", "دبل كليك")
        AlertDialog.Builder(requireContext())
            .setTitle("🖱 نوع الكليك").setItems(btnTypes) { _, w ->
                val btn = listOf("left","right","double")[w]
                currentSteps.add(MacroStep("mouse_click", JSONObject().put("button",btn).put("x",0).put("y",0), 100))
                stepAdapter?.notifyDataSetChanged()
                view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
            }.show()
    }

    private fun showWaitStep() {
        val et = EditText(requireContext()).also {
            it.hint = "المدة بالمللي ثانية"
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            it.setText("1000")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("⏱ انتظار").setView(et)
            .setPositiveButton("إضافة") { _, _ ->
                val ms = et.text.toString().toLongOrNull() ?: 1000
                currentSteps.add(MacroStep("wait", JSONObject().put("ms", ms), ms))
                stepAdapter?.notifyDataSetChanged()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun showKeyPressStep() {
        val keys = arrayOf("Enter","Escape","Tab","Ctrl+C","Ctrl+V","Ctrl+Z","Ctrl+A","Win+D","Alt+F4","F5")
        AlertDialog.Builder(requireContext())
            .setTitle("⌨ مفتاح").setItems(keys) { _, w ->
                currentSteps.add(MacroStep("key_press", JSONObject().put("key", keys[w]), 100))
                stepAdapter?.notifyDataSetChanged()
                view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
            }.show()
    }

    private fun showCmdStep() {
        val et = EditText(requireContext()).also { it.hint = "الأمر مثل: notepad.exe" }
        AlertDialog.Builder(requireContext())
            .setTitle("💻 تشغيل أمر").setView(et)
            .setPositiveButton("إضافة") { _, _ ->
                val cmd = et.text.toString().trim()
                if (cmd.isNotEmpty()) {
                    currentSteps.add(MacroStep("cmd", JSONObject().put("cmd", cmd), 500))
                    stepAdapter?.notifyDataSetChanged()
                    view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    // ── Save macro ────────────────────────────────────────────────────────────
    private fun showSaveMacroDialog() {
        val et = EditText(requireContext()).also { it.hint = "اسم الماكرو" }
        AlertDialog.Builder(requireContext())
            .setTitle("💾 حفظ الماكرو").setView(et)
            .setPositiveButton("حفظ") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "ماكرو ${savedMacros.size + 1}" }
                savedMacros.add(0, Macro(name, currentSteps.toList()))
                macroAdapter?.notifyDataSetChanged()
                currentSteps.clear()
                stepAdapter?.notifyDataSetChanged()
                toast("💾 تم حفظ: $name")
                view?.findViewById<TextView>(R.id.tvStepCount)?.text = "0 خطوة"
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun loadMacroForEdit(macro: Macro) {
        currentSteps.clear()
        currentSteps.addAll(macro.steps)
        stepAdapter?.notifyDataSetChanged()
        view?.findViewById<TextView>(R.id.tvStepCount)?.text = "${currentSteps.size} خطوة"
        toast("✏️ جاري تحرير: ${macro.name}")
    }

    private fun confirmDeleteMacro(pos: Int) {
        val name = savedMacros.getOrNull(pos)?.name ?: return
        AlertDialog.Builder(requireContext())
            .setTitle("حذف الماكرو").setMessage("حذف \"$name\"؟")
            .setPositiveButton("حذف") { _, _ ->
                savedMacros.removeAt(pos)
                macroAdapter?.notifyDataSetChanged()
            }.setNegativeButton("إلغاء", null).show()
    }

    // ── Preset macros ─────────────────────────────────────────────────────────
    private fun loadPresetMacros() {
        savedMacros.addAll(listOf(
            Macro("📸 لقطة شاشة", listOf(
                MacroStep("key_press", JSONObject().put("key","Win+Shift+S"), 100)
            )),
            Macro("🔒 قفل + WoL جاهز", listOf(
                MacroStep("key_press", JSONObject().put("key","Win+L"), 100)
            )),
            Macro("🔄 إعادة تشغيل Explorer", listOf(
                MacroStep("key_press", JSONObject().put("key","Ctrl+Shift+Esc"), 500),
                MacroStep("wait",      JSONObject().put("ms", 1000), 1000),
                MacroStep("key_press", JSONObject().put("key","Alt+F4"), 500)
            )),
            Macro("💻 فتح Terminal مخصص", listOf(
                MacroStep("key_press", JSONObject().put("key","Win+R"), 300),
                MacroStep("wait",      JSONObject().put("ms", 500), 500),
                MacroStep("type_text", JSONObject().put("text","powershell"), 200),
                MacroStep("key_press", JSONObject().put("key","Enter"), 100)
            ))
        ))
        macroAdapter?.notifyDataSetChanged()
    }

    private fun toast(msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        isRecording = false
        ui.removeCallbacksAndMessages(null)
    }

    // ── Adapters ──────────────────────────────────────────────────────────────
    inner class StepAdapter(
        private val items: MutableList<MacroStep>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<StepAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvType:  TextView = v.findViewById(R.id.tvStepType)
            val tvData:  TextView = v.findViewById(R.id.tvStepData)
            val tvDelay: TextView = v.findViewById(R.id.tvStepDelay)
            val btnDel:  View     = v.findViewById(R.id.btnDelStep)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_macro_step, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvType.text  = stepIcon(s.type) + " " + stepName(s.type)
            h.tvData.text  = stepDesc(s)
            h.tvDelay.text = "+${s.delay}ms"
            h.btnDel.setOnClickListener { onDelete(pos) }
        }
        private fun stepIcon(t: String) = when(t) {
            "type_text"   -> "⌨"; "mouse_click" -> "🖱"
            "key_press"   -> "⌨"; "wait"        -> "⏱"
            "cmd"         -> "💻"; else          -> "▸"
        }
        private fun stepName(t: String) = when(t) {
            "type_text"   -> "كتابة نص"
            "mouse_click" -> "ضغط ماوس"
            "key_press"   -> "مفتاح"
            "wait"        -> "انتظار"
            "cmd"         -> "أمر"
            else          -> t
        }
        private fun stepDesc(s: MacroStep) = when(s.type) {
            "type_text"   -> s.data.optString("text","").take(30)
            "mouse_click" -> "${s.data.optString("button","left")} @ (${s.data.optInt("x")},${s.data.optInt("y")})"
            "key_press"   -> s.data.optString("key","")
            "wait"        -> "${s.data.optLong("ms")}ms"
            "cmd"         -> s.data.optString("cmd","").take(30)
            else          -> ""
        }
    }

    inner class MacroAdapter(
        private val items: MutableList<Macro>,
        private val onRun:    (Macro) -> Unit,
        private val onEdit:   (Macro) -> Unit,
        private val onDelete: (Int) -> Unit,
        private val onLoop:   (Macro, Int) -> Unit
    ) : RecyclerView.Adapter<MacroAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:  TextView = v.findViewById(R.id.tvMacroName)
            val tvCount: TextView = v.findViewById(R.id.tvMacroCount)
            val btnRun:  View     = v.findViewById(R.id.btnRunMacro)
            val btnEdit: View     = v.findViewById(R.id.btnEditMacro)
            val btnDel:  View     = v.findViewById(R.id.btnDelMacro)
            val btnLoop: View     = v.findViewById(R.id.btnLoopMacro)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_macro, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = items[pos]
            h.tvName.text  = m.name
            h.tvCount.text = "${m.steps.size} خطوة · ×${m.loopCount}"
            h.btnRun.setOnClickListener  { onRun(m) }
            h.btnEdit.setOnClickListener { onEdit(m) }
            h.btnDel.setOnClickListener  { onDelete(pos) }
            h.btnLoop.setOnClickListener {
                val counts = arrayOf("1x","2x","3x","5x","10x","∞")
                val nums   = intArrayOf(1, 2, 3, 5, 10, 999)
                AlertDialog.Builder(requireContext())
                    .setTitle("تكرار الماكرو").setItems(counts) { _, w ->
                        m.loopCount = nums[w]
                        h.tvCount.text = "${m.steps.size} خطوة · ×${m.loopCount}"
                        onLoop(m, nums[w])
                    }.show()
            }
        }
    }
}
