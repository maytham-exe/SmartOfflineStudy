package com.smartstudy.offline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.smartstudy.offline.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: StudyStore
    private var activeFilter: String? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = StudyStore(this)
        setupFilterChips()
        setupListeners()
        handleIncomingIntent(intent)
        refreshStats()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                    if (shared.isNotBlank()) {
                        binding.etInput.setText(shared)
                        analyzeText()
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme == "content" || uri.scheme == "file") {
                        importFromUri(uri)
                    }
                }
            }
        }
    }

    private fun setupFilterChips() {
        binding.filterBar.removeAllViews()
        val categories = listOf<Pair<String, String?>>(
            "الكل" to null,
            "تعريف" to "تعريف",
            "تعليل" to "تعليل",
            "مسألة" to "مسألة",
            "تاريخ" to "تاريخ"
        )
        for ((label, kind) in categories) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8 }
                layoutParams = lp
                setOnClickListener {
                    activeFilter = kind
                    showFilteredNotes(kind)
                }
            }
            binding.filterBar.addView(btn)
        }
    }

    private fun setupListeners() {
        binding.btnAnalyze.setOnClickListener { analyzeText() }
        binding.btnFlashcard.setOnClickListener { generateFlashcard() }
        binding.btnSummary.setOnClickListener { summarizeText() }
        binding.btnQuiz.setOnClickListener { runQuizSession() }
        binding.btnImport.setOnClickListener {
            pickFileLauncher.launch(arrayOf("application/pdf", "text/plain"))
        }
        binding.btnAskBook.setOnClickListener { askBooks() }
        binding.btnFilter.setOnClickListener { openFilterDialog() }
        binding.btnExport.setOnClickListener { exportNotes() }
        binding.btnClear.setOnClickListener {
            binding.etInput.text?.clear()
            binding.tvOutput.text = ""
        }
    }

    private fun analyzeText() {
        val text = binding.etInput.text.toString().trim()
        if (text.isBlank()) {
            binding.tvOutput.text = "أدخل فقرة أو سؤالاً دراسياً أولاً."
            return
        }
        val c = StudyEngine.classify(text)
        store.saveNote(text, c.type.label)
        val breakdown = c.scores.entries
            .sortedByDescending { it.value }
            .joinToString(" | ") { "${it.key.label}: ${"%.1f".format(it.value)}" }

        binding.tvOutput.text = buildString {
            appendLine("التصنيف: ${c.type.label} (${c.type.tag})")
            appendLine("الثقة: ${(c.confidence * 100).toInt()}%")
            appendLine("الكلمات المفتاحية: ${if (c.keywords.isEmpty()) "—" else c.keywords.joinToString("، ")}")
            appendLine("أوزان المؤشرات: $breakdown")
            append("تم الحفظ محلياً في قاعدة البيانات.")
        }
        refreshStats()
    }

    private fun importFromUri(uri: Uri) {
        binding.tvOutput.text = "جارٍ استيراد وفهرسة الملف محلياً…"
        lifecycleScope.launch {
            try {
                val (name, chunks) = withContext(Dispatchers.IO) {
                    val displayName = DocumentImporter.resolveDisplayName(this@MainActivity, uri)
                    val extracted = DocumentImporter.read(this@MainActivity, uri, displayName)
                    store.addChunks(extracted)
                    displayName to extracted
                }
                binding.tvOutput.text =
                    "تم استيراد «$name» بنجاح.
عدد المقاطع المفهرسة: ${chunks.size}
يمكنك الآن كتابة سؤال والضغط على «اسأل الكتب»."
                refreshStats()
            } catch (e: Exception) {
                binding.tvOutput.text = "تعذر استيراد الملف: ${e.message ?: "خطأ غير معروف"}"
            }
        }
    }

    private fun askBooks() {
        val q = binding.etInput.text.toString().trim()
        if (q.isBlank()) {
            binding.tvOutput.text = "اكتب سؤالاً في حقل الإدخال للبحث في الكتب المستوردة."
            return
        }
        lifecycleScope.launch {
            val answer = withContext(Dispatchers.IO) {
                StudyEngine.answerFromBooks(q, store.chunks())
            }
            val srcText = answer.source?.let { src ->
                val pageInfo = src.page?.let { " – صفحة $it" } ?: ""
                "

المصدر: ${src.document}$pageInfo (تطابق ${(answer.confidence * 100).toInt()}%)"
            } ?: ""
            binding.tvOutput.text = answer.answer + srcText
        }
    }

    private fun generateFlashcard() {
        val text = binding.etInput.text.toString().trim()
        if (text.isBlank()) {
            binding.tvOutput.text = "أدخل فقرة لإنشاء بطاقة مراجعة."
            return
        }
        val (q, a) = StudyEngine.makeFlashcard(text)
        binding.tvOutput.text = "بطاقة مراجعة

السؤال: $q

الإجابة: $a"
    }

    private fun summarizeText() {
        val text = binding.etInput.text.toString().trim()
        if (text.isBlank()) {
            binding.tvOutput.text = "أدخل فقرة لتلخيصها."
            return
        }
        binding.tvOutput.text = "التلخيص:
${StudyEngine.summary(text, 2)}"
    }

    private fun runQuizSession() {
        val text = binding.etInput.text.toString().trim()
        if (text.isBlank()) {
            binding.tvOutput.text = "أدخل فقرة دراسية لتوليد اختبار سريع."
            return
        }
        val pool = store.notes().map { it.body }
        val questions = StudyEngine.quiz(text, pool)
        if (questions.isEmpty()) {
            binding.tvOutput.text = "النص قصير جداً أو لا يحتوي كلمات مفتاحية كافية لتوليد اختبار."
            return
        }
        askQuestionAt(questions, 0, 0)
    }

    private fun askQuestionAt(questions: List<Pair<String, List<String>>>, index: Int, score: Int) {
        if (index >= questions.size) {
            binding.tvOutput.text = "انتهى الاختبار السريع.
النتيجة: $score من ${questions.size}"
            return
        }
        val (qText, options) = questions[index]
        // Correct answer is matched by checking if selected option is a top keyword of the passage
        val validAnswers = StudyEngine.keywords(binding.etInput.text.toString(), 4).toSet()
        AlertDialogBuilder.show(this, qText, options, validAnswers.firstOrNull() ?: options.first()) { picked ->
            val isCorrect = picked in validAnswers
            val nextScore = if (isCorrect) score + 1 else score
            binding.tvOutput.text = if (isCorrect) {
                "إجابة صحيحة (${index + 1}/${questions.size})"
            } else {
                "خيار غير مطابق للنص (${index + 1}/${questions.size}) — الخيارات الصحيحة المحتملة: ${validAnswers.joinToString("، ")}"
            }
            askQuestionAt(questions, index + 1, nextScore)
        }
    }

    private fun openFilterDialog() {
        val opts = arrayOf("الكل", "تعريف", "تعليل", "مسألة", "تاريخ")
        AlertDialogBuilder.showFilterPicker(this, opts) { _, selected ->
            activeFilter = if (selected == "الكل") null else selected
            showFilteredNotes(activeFilter)
        }
    }

    private fun showFilteredNotes(kind: String?) {
        val list = store.notes(kind)
        if (list.isEmpty()) {
            binding.tvOutput.text = "لا توجد ملاحظات محفوظة تحت الفئة: ${kind ?: "الكل"}."
            return
        }
        binding.tvOutput.text = list.take(15).joinToString("

") { note ->
            "• [${note.kind}] ${note.body}"
        }
    }

    private fun exportNotes() {
        val content = store.exportJson(activeFilter)
        val exportDir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(exportDir, "smart_offline_study_export.json")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "تصدير الملاحظات المصنفة"))
    }

    private fun refreshStats() {
        val s = store.stats()
        binding.tvStats.text =
            "المحفوظات: ${s["total_notes"]} (تعريف: ${s["تعريف"]} | تعليل: ${s["تعليل"]} | مسألة: ${s["مسألة"]} | تاريخ: ${s["تاريخ"]}) • مقاطع الكتب: ${s["total_chunks"]}"
    }
}
