import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GetComicNumbersToFetchTest {

    @Test
    fun alwaysReturnsTheSameResultGivenSameInputs() {
        val run1 = getComicNumbersToFetch(3000)
        val run2 = getComicNumbersToFetch(3000)

        assertEquals(run1, run2)
    }

    @Test
    fun returnsMostlyTheSameResultIfAnotherComicsIsAdded() {
        val run1 = getComicNumbersToFetch(3000)
        val run2 = getComicNumbersToFetch(3001)

        val run1WithoutTheLast = run1.take(run1.size - 1)
        val run2WithoutTheLast = run2.take(run2.size - 1)

        assertEquals(run1WithoutTheLast, run2WithoutTheLast)
    }

    @Test
    fun returnsDifferentResultsForDifferentDays() {
        val run1 = getComicNumbersToFetch(3000)
        val run2 = getComicNumbersToFetch(3000, LocalDate.now().plusDays(1))

        assertNotEquals(run1, run2)
    }

    @Test
    fun returnsAllComicsAfterRunningItEnoughTimes() {
        val daysUntilComicsRepeat = 20
        val latestComicNumber = 3000

        val allRuns = List(daysUntilComicsRepeat) { index ->
            getComicNumbersToFetch(latestComicNumber, targetDate = LocalDate.now().plusDays(index.toLong()), daysUntilComicsRepeat = daysUntilComicsRepeat)
        }

        val allComics = allRuns.flatten().distinct()

        assertEquals(3000, allComics.size)
        assertEquals(List(latestComicNumber) {it}, allComics.sorted())
    }
}
