package com.smartstudy.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyEngineTest {

    @Test
    fun classifiesDefinitionReasonProblemAndDate() {
        val def = StudyEngine.classify("تعريف الخلية هي الوحدة الأساسية للتركيب في الكائنات الحية")
        assertEquals(StudyType.DEFINITION, def.type)

        val reason = StudyEngine.classify("علل تمدد المعادن بالحرارة بسبب زيادة الطاقة الحركية")
        assertEquals(StudyType.REASON, reason.type)

        val prob = StudyEngine.classify("احسب السرعة إذا كانت المسافة 120 والزمن 4")
        assertEquals(StudyType.PROBLEM, prob.type)

        val date = StudyEngine.classify("في عام 1958 تم توقيع الاتفاقية تاريخيا")
        assertEquals(StudyType.DATE, date.type)
    }

    @Test
    fun retrievesRelevantBookChunkAndRefusesWeakMatch() {
        val chunks = listOf(
            SourceChunk("physics.pdf", 12, "قانون نيوتن الثاني ينص على أن القوة تساوي الكتلة في التسارع."),
            SourceChunk("history.pdf", 4, "تأسست المدينة في القرن الثاني الهجري.")
        )
        val hit = StudyEngine.answerFromBooks("ما هو قانون نيوتن الثاني والقوة والكتلة؟", chunks)
        assertEquals("physics.pdf", hit.source?.document)
        assertEquals(12, hit.source?.page)

        val miss = StudyEngine.answerFromBooks("xyzabc_nonexistent_term_999", chunks)
        assertEquals(null, miss.source)
    }

    @Test
    fun generatesFlashcardAndQuizWithDistractors() {
        val text = "التمثيل الضوئي عملية حيوية تحدث في البلاستيدات الخضراء لتحويل الطاقة الضوئية إلى غذاء."
        val (q, a) = StudyEngine.makeFlashcard(text)
        assertTrue(q.isNotBlank() && a == text)

        val quizItems = StudyEngine.quiz(text)
        assertTrue(quizItems.isNotEmpty())
        assertEquals(4, quizItems.first().second.size)
    }
}
