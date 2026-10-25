package camp.room.app

import org.junit.Assert.*
import org.junit.Test

class CabinTopologyTest {
    @Test fun exactOrderedCoordinatesAndOriginalNativeIdentitiesNeverOverwrite() {
        val index = CabinTargetIndex<Any>()
        val originals = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_3_LEFT").map { it to Any() }
        originals.forEach { (member, native) -> index.put("readingLight", CabinArea("seat", listOf(member)), native) }
        assertEquals(2, index.targets("readingLight", "front").size)
        assertEquals(2, index.targets("readingLight", "rear").size)
        originals.forEach { (member, native) ->
            val entry = index.all().single { it.area.members == listOf(member) }
            assertSame(native, index.get("readingLight", entry.area.id))
        }
        val ordered = listOf("ROW_1_LEFT", "ROW_1_RIGHT")
        val reversed = ordered.reversed()
        val duplicated = listOf("ROW_1_LEFT", "ROW_1_LEFT")
        val first = Any(); val second = Any()
        index.put("readingLight", CabinArea("seat", ordered), first)
        index.put("readingLight", CabinArea("seat", reversed), Any())
        index.put("readingLight", CabinArea("seat", duplicated), Any())
        index.put("readingLight", CabinArea("seat", ordered), second)
        assertEquals(8, index.all().size)
        val duplicates = index.all().filter { it.area.members == ordered }
        assertEquals(2, duplicates.size)
        assertEquals(2, duplicates.map { it.area.id }.distinct().size)
        assertSame(first, duplicates[0].native); assertSame(second, duplicates[1].native)
        assertTrue(index.all().any { it.area.members == reversed })
        assertTrue(index.all().any { it.area.members == duplicated })
        val spanning = Any()
        index.put("readingLight", CabinArea("seat", listOf("ROW_1_LEFT", "ROW_2_LEFT")), spanning)
        assertSame(spanning, index.ambiguous("readingLight").single().native)
        index.put("ambientBrightness", CabinArea("seat", listOf("ROW_1_LEFT")), Any())
        assertSame(originals.first().second, index.all().first().native)
        val unspecified = Any()
        index.put("blindLevel", CabinArea("window", emptyList()), unspecified)
        assertSame(unspecified, index.ambiguous("blindLevel").single().native)
        assertEquals(11, index.all().size)
    }
}
