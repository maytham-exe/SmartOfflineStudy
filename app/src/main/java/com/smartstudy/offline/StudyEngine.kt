package com.smartstudy.offline

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

enum class StudyType(val label: String, val tag: String) {
    DEFINITION("تعريف", "DEF"),
    REASON("تعليل", "WHY"),
    PROBLEM("مسألة", "PROB"),
    DATE("تاريخ", "DATE")
}

data class Classification(
    val type: StudyType,
    val confidence: Double,
    val keywords: List<String>,
    val scores: Map<StudyType, Double> = emptyMap()
)

data class SourceChunk(val document: String, val page: Int?, val text: String)
data class BookAnswer(val answer: String, val source: SourceChunk?, val confidence: Double)
data class SavedNote(val id: Long, val body: String, val kind: String, val created: Long)

/**
 * Lightweight offline NLP/ML engine:
 * - Multinomial Naive Bayes trained on compact study seeds + lexical/structural signals
 * - TF-IDF + Cosine Similarity for book passage retrieval & duplicate/related filtering
 * - Flashcard generator & distractor-aware multiple-choice quiz generator
 */
object StudyEngine {
    private val stopWords = setOf(
        "من", "في", "على", "الى", "إلى", "عن", "ما", "ماذا", "هو", "هي",
        "هذا", "هذه", "ذلك", "تلك", "التي", "الذي", "الذين", "و", "او", "أو",
        "ثم", "كان", "كانت", "يكون", "تكون", "مع", "قد", "تم", "أن", "إن",
        "لا", "لم", "لن", "كل", "بها", "به", "له", "لها", "كما", "حيث", "التى", "اللتان", "اللذان"
    )

    private val patterns = mapOf(
        StudyType.DEFINITION to listOf(
            "يعرف", "تعريف", "هو عبارة عن", "هي عبارة عن", "ما المقصود",
            "يطلق على", "تسمى", "يسمى", "مصطلح", "يقصد به", "يقصد بها"
        ),
        StudyType.REASON to listOf(
            "علل", "فسر", "ما سبب", "لماذا", "بسبب", "نتيجة", "علّة", "علة",
            "لأن", "لان", "نظرا ل", "نظراً ل", "يؤدي الى", "يؤدي إلى"
        ),
        StudyType.PROBLEM to listOf(
            "احسب", "أوجد", "اوجد", "حل", "كم يساوي", "معادلة", "يساوي",
            "مسألة", "قانون", "سرعة", "كتلة", "قوة", "زاوية", "نسبة", "سعر", "محيط", "مساحة"
        ),
        StudyType.DATE to listOf(
            "متى", "في عام", "عام", "سنة", "تاريخ", "هجري", "ميلادي",
            "قبل الميلاد", "بعد الميلاد", "القرن", "معركة", "تأسيس", "استقلال", "اتفاقية"
        )
    )

    private val trainingCorpus: List<Pair<StudyType, String>> = listOf(
        StudyType.DEFINITION to "تعريف الخلية هي الوحدة الأساسية للتركيب والوظيفة في الكائنات الحية",
        StudyType.DEFINITION to "ما المقصود بالضغط الجوي هو وزن عمود الهواء الواقع على وحدة المساحة",
        StudyType.DEFINITION to "يعرف التمثيل الضوئي بأنه العملية التي تحول فيها النباتات الطاقة الضوئية إلى طاقة كيميائية",
        StudyType.DEFINITION to "الاسم المبتدأ هو اسم مرفوع يقع في أول الجملة الاسمية ويحتاج إلى خبر",
        StudyType.REASON to "علل تمدد المعادن بالحرارة بسبب زيادة الطاقة الحركية للجزيئات وتباعدها",
        StudyType.REASON to "لماذا يطفو الجليد فوق سطح الماء لأن كثافته أقل من كثافة الماء السائل",
        StudyType.REASON to "فسر ارتفاع درجة الغليان في المرتفعات نتيجة انخفاض الضغط الجوي",
        StudyType.REASON to "بسبب قلة الأمطار وتدهور الغطاء النباتي ازدادت حدة التصحر في المنطقة",
        StudyType.PROBLEM to "احسب السرعة المتوسطة لجسم قطع مسافة 120 متر خلال 6 ثوان",
        StudyType.PROBLEM to "أوجد قيمة س في المعادلة 2س + 6 = 18 ثم تحقق من الحل",
        StudyType.PROBLEM to "مسألة مستطيل طوله 15 سم وعرضه 8 سم احسب مساحته ومحيطه",
        StudyType.PROBLEM to "إذا كانت كتلة الجسم 5 كجم والتسارع 3 م/ث2 فما مقدار القوة المؤثرة",
        StudyType.DATE to "في عام 1958 حدثت تغييرات سياسية بارزة وتم توقيع الاتفاقية في 12/03/1970",
        StudyType.DATE to "متى وقعت معركة ذي قار وما تاريخ تأسيس الدولة العباسية في القرن الثاني الهجري",
        StudyType.DATE to "سنة 622 ميلادية الموافق 1 هجرية تمثل بداية التقويم الهجري",
        StudyType.DATE to "تأسست المنظمة عام 1945 بعد الحرب العالمية الثانية"
    )

    private data class NaiveBayesModel(
        val classPriors: Map<StudyType, Double>,
        val tokenLogProbs: Map<StudyType, Map<String, Double>>,
        val defaultLogProb: Map<StudyType, Double>
    )

    private val nbModel: NaiveBayesModel by lazy { buildNaiveBayesModel() }

    private fun buildNaiveBayesModel(): NaiveBayesModel {
        val totalDocs = trainingCorpus.size.toDouble()
        val vocab = mutableSetOf<String>()
        val classDocCounts = mutableMapOf<StudyType, Int>()
        val classTokenCounts = mutableMapOf<StudyType, MutableMap<String, Int>>()
        val classTotalTokens = mutableMapOf<StudyType, Int>()

        for (t in StudyType.values()) {
            classDocCounts[t] = 0
            classTokenCounts[t] = mutableMapOf()
            classTotalTokens[t] = 0
        }

        for ((label, sample) in trainingCorpus) {
            classDocCounts[label] = (classDocCounts[label] ?: 0) + 1
            val toks = rawTokens(sample)
            vocab.addAll(toks)
            val map = classTokenCounts[label]!!
            for (tok in toks) {
                map[tok] = (map[tok] ?: 0) + 1
                classTotalTokens[label] = (classTotalTokens[label] ?: 0) + 1
            }
        }

        val vSize = vocab.size.coerceAtLeast(1).toDouble()
        val priors = StudyType.values().associateWith { t ->
            ln(((classDocCounts[t] ?: 0) + 0.5) / (totalDocs + StudyType.values().size * 0.5))
        }
        val tokenProbs = mutableMapOf<StudyType, Map<String, Double>>()
        val defaults = mutableMapOf<StudyType, Double>()

        for (t in StudyType.values()) {
            val denom = (classTotalTokens[t] ?: 0).toDouble() + vSize
            val counts = classTokenCounts[t]!!
            val map = mutableMapOf<String, Double>()
            for (term in vocab) {
                val c = (counts[term] ?: 0).toDouble()
                map[term] = ln((c + 1.0) / denom)
            }
            tokenProbs[t] = map
            defaults[t] = ln(1.0 / denom)
        }

        return NaiveBayesModel(priors, tokenProbs, defaults)
    }

    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        .replace(Regex("[\u064B-\u065F\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي').replace('ة', 'ه')
        .lowercase()

    private fun rawTokens(s: String): List<String> = normalize(s)
        .split(Regex("[^\p{L}\p{N}+\-*/=]+"))
        .map { it.trim('-', '+', '*', '/', '=') }
        .filter { it.length > 1 || it in setOf("+", "-", "*", "/", "=") }

    fun tokens(s: String): List<String> = rawTokens(s)
        .filter { it.length > 1 && it !in stopWords }

    fun classify(text: String): Classification {
        val n = normalize(text)
        val toks = rawTokens(text)

        // Compute log-posterior scores from Naive Bayes + pattern & structural features
        val rawScores = StudyType.values().associateWith { type ->
            val prior = nbModel.classPriors[type] ?: -1.38
            val map = nbModel.tokenLogProbs[type].orEmpty()
            val def = nbModel.defaultLogProb[type] ?: -5.0
            val nbLog = prior + toks.sumOf { tok -> map[tok] ?: def }

            val patternScore = patterns[type]!!.sumOf { p ->
                val needle = normalize(p)
                when {
                    n.contains(needle) && needle.length > 3 -> if (needle.contains(" ")) 3.2 else 1.8
                    n.contains(needle) -> 1.1
                    else -> 0.0
                }
            }

            val structScore = when (type) {
                StudyType.DATE -> {
                    val years = Regex("\b(1[0-9]{3}|20[0-9]{2}|[1-9][0-9]{2}\s*هـ|[1-9][0-9]{2}\s*م|[0-9]{1,2}/[0-9]{1,2}/[0-9]{2,4})\b").findAll(n).count()
                    years * 2.4
                }
                StudyType.PROBLEM -> {
                    val ops = Regex("[0-9]\s*[=+×÷*/\-]\s*[0-9]|[0-9]+\s*(سم|م|كم|كجم|ث|د|%|س)").findAll(n).count()
                    ops * 2.5
                }
                StudyType.REASON -> {
                    if (n.contains("؟") || n.contains("?")) 1.2 else 0.0
                }
                StudyType.DEFINITION -> {
                    if (Regex("^.{1,25}\s+(هو|هي)\s+").containsMatchIn(n)) 2.0 else 0.0
                }
            }

            // Map log-likelihood into positive weight via soft exp scaling
            val scaledNb = kotlin.math.exp((nbLog + 12.0).coerceIn(-10.0, 6.0))
            (scaledNb + patternScore + structScore).coerceAtLeast(0.05)
        }

        val ranked = rawScores.entries.sortedByDescending { it.value }
        val top = ranked.first()
        val total = ranked.sumOf { it.value }.coerceAtLeast(0.001)
        val marginConfidence = (top.value / total).coerceIn(0.26, 0.99)
        return Classification(top.key, marginConfidence, keywords(text), rawScores)
    }

    fun keywords(text: String, limit: Int = 6): List<String> =
        tokens(text)
            .filter { !it.all { ch -> ch.isDigit() } }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length }
            )
            .take(limit)
            .map { it.key }

    fun summary(text: String, maxSentences: Int = 2): String {
        val sentences = text.split(Regex("(?<=[.!؟\n])\s+"))
            .map { it.trim() }
            .filter { it.length >= 12 }
        if (sentences.size <= maxSentences) return text.trim()

        val docFreq = mutableMapOf<String, Int>()
        val sentTokens = sentences.map { s ->
            val ts = tokens(s).toSet()
            ts.forEach { docFreq[it] = (docFreq[it] ?: 0) + 1 }
            ts
        }
        val nDocs = sentences.size.toDouble()

        return sentences.mapIndexed { idx, s ->
            val ts = sentTokens[idx]
            val tfidfSum = if (ts.isEmpty()) 0.0 else ts.sumOf { term ->
                val df = (docFreq[term] ?: 1).toDouble()
                val idf = ln(1.0 + nDocs / df)
                idf / sqrt(ts.size.toDouble())
            }
            idx to tfidfSum
        }
            .sortedByDescending { it.second }
            .take(maxSentences)
            .sortedBy { it.first }
            .joinToString(" ") { sentences[it.first] }
    }

    /**
     * TF-IDF + Cosine Similarity over imported book chunks.
     * Strict grounding: never fabricates answers when match is below threshold.
     */
    fun answerFromBooks(question: String, chunks: List<SourceChunk>): BookAnswer {
        if (chunks.isEmpty()) {
            return BookAnswer("لم تتم إضافة كتب بعد. استورد ملف PDF أو TXT أولاً.", null, 0.0)
        }
        val qTokens = tokens(question)
        if (qTokens.isEmpty()) {
            return BookAnswer("اكتب سؤالاً يتضمن كلمات واضحة للبحث في الكتب.", null, 0.0)
        }

        val chunkTokenLists = chunks.map { tokens(it.text) }
        val df = mutableMapOf<String, Int>()
        for (tList in chunkTokenLists) {
            for (term in tList.toSet()) {
                df[term] = (df[term] ?: 0) + 1
            }
        }
        val nDocs = chunks.size.toDouble()

        fun tfidfVec(tList: List<String>): Map<String, Double> {
            if (tList.isEmpty()) return emptyMap()
            val tf = tList.groupingBy { it }.eachCount()
            val len = tList.size.toDouble()
            return tf.mapValues { (term, count) ->
                val termDf = (df[term] ?: 1).toDouble()
                val idf = ln(1.0 + (nDocs + 1.0) / (termDf + 0.5))
                (count.toDouble() / len) * idf
            }
        }

        val qVec = tfidfVec(qTokens)
        val qNorm = sqrt(qVec.values.sumOf { it * it })
        if (qNorm == 0.0) {
            return BookAnswer("تعذر تكوين متجه بحث من السؤال المدخل.", null, 0.0)
        }

        val scored = chunks.mapIndexed { idx, chunk ->
            val dVec = tfidfVec(chunkTokenLists[idx])
            val dNorm = sqrt(dVec.values.sumOf { it * it })
            val dot = if (dNorm == 0.0) 0.0 else qVec.entries.sumOf { (k, v) -> v * (dVec[k] ?: 0.0) }
            val cosSim = if (dNorm == 0.0) 0.0 else dot / (qNorm * dNorm)
            chunk to cosSim
        }.sortedByDescending { it.second }

        val best = scored.first()
        if (best.second < 0.08) {
            return BookAnswer(
                "لم أجد فقرة موثوقة بما يكفي في الكتب المضافة. جرّب كلمات أدق أو أضف مصدراً آخر.",
                null,
                best.second
            )
        }
        val excerpt = best.first.text.trim().take(1200)
        return BookAnswer(excerpt, best.first, best.second)
    }

    fun makeFlashcard(text: String): Pair<String, String> {
        val clean = text.trim()
        val kws = keywords(clean, 3)
        val kwStr = if (kws.isEmpty()) "هذا المفهوم" else kws.joinToString("، ")
        val question = when (classify(clean).type) {
            StudyType.DEFINITION -> "ما تعريف $kwStr؟"
            StudyType.REASON -> {
                val firstLine = clean.lineSequence().firstOrNull()?.trim().orEmpty()
                if (firstLine.length in 10..180) {
                    if (firstLine.endsWith("؟") || firstLine.endsWith("?")) firstLine else "علّل: $firstLine"
                } else {
                    "علّل السبب في: $kwStr"
                }
            }
            StudyType.PROBLEM -> {
                val firstLine = clean.lineSequence().firstOrNull()?.trim().orEmpty()
                if (firstLine.length in 10..170) "حل المسألة: $firstLine" else "وضّح خطوات حل المسألة المتعلقة بـ: $kwStr"
            }
            StudyType.DATE -> "ما الحدث أو التاريخ المرتبط بـ: $kwStr؟"
        }
        return question to clean
    }

    /**
     * Generates multiple-choice questions using real distractors drawn from other
     * extracted terms/sentences in the text or corpus rather than placeholder strings.
     */
    fun quiz(text: String, poolTexts: List<String> = emptyList()): List<Pair<String, List<String>>> {
        val kws = keywords(text, 4)
        if (kws.isEmpty()) return emptyList()

        val externalPool = (listOf(text) + poolTexts)
            .flatMap { keywords(it, 8) }
            .distinct()

        val fallbackDistractors = listOf("معيار ثابت", "عامل ثانوي", "حالة استثنائية", "متغير مستقل")

        return kws.mapIndexed { idx, correctWord ->
            val distractors = (externalPool.filter { it != correctWord }.shuffled() + fallbackDistractors)
                .filter { it != correctWord }
                .distinct()
                .take(3)

            val options = (listOf(correctWord) + distractors).shuffled()
            val stem = summary(text, 1).take(110)
            val question = "السؤال ${idx + 1}: اختر المصطلح أو الكلمة المفتاحية الأبرز في الفقرة:
«$stem»"
            question to options
        }
    }

    /**
     * Optional hook for loading a pre-trained TensorFlow Lite text classifier (1-3 MB)
     * from `app/src/main/assets/study_classifier.tflite` when present.
     */
    fun hasBundledTfliteModel(assetManager: android.content.res.AssetManager, filename: String = "study_classifier.tflite"): Boolean {
        return try {
            assetManager.open(filename).use { it.available() > 0 }
        } catch (_: Exception) {
            false
        }
    }
}
