import org.junit.Assert.*
import org.junit.Test

class MyUnitTest {

    @Test
    fun addition_isCorrect() {
        val result = 2 + 2
        assertEquals(4, result)
    }

    @Test
    fun string_contains_word() {
        val text = "WkWhisperKey"
        assertTrue(text.contains("Whisper"))
    }
}
