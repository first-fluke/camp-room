package camp.room.app

import org.junit.Assert.*
import org.junit.Test

class CampPaginationTest {
    private fun page(items:List<String>,next:String?="next",generation:String="g1")=CatalogPage(items,5,5,next,generation)
    @Test fun oldSearchCannotPublishAfterFilterChange() {
        val history=CatalogPageHistory<String>{it}
        val old=history.reset()
        val current=history.reset()
        assertTrue(history.accept(current,0,page(listOf("new"))))
        assertFalse(history.accept(old,0,page(listOf("old"))))
        assertEquals(listOf("new"),history.page(0)?.items)
    }
    @Test fun duplicatesBetweenAndWithinPagesAreRemoved() {
        val history=CatalogPageHistory<String>{it};val owner=history.reset()
        assertTrue(history.accept(owner,0,page(listOf("a","a","b"))))
        assertTrue(history.accept(owner,1,page(listOf("b","c","c"))))
        assertEquals(listOf("a","b"),history.page(0)?.items)
        assertEquals(listOf("c"),history.page(1)?.items)
        assertEquals(listOf("a","b"),history.select(0)?.items)
    }
    @Test fun failedNextRequestLeavesPublishedPageAndRetryCanComplete() {
        val history=CatalogPageHistory<String>{it};val owner=history.reset()
        history.accept(owner,0,page(listOf("a")))
        assertEquals(0,history.index)
        assertNull(history.page(1))
        assertTrue(history.accept(owner,1,page(listOf("b"),next=null)))
        assertNull(history.page(1)?.nextCursor)
    }
    @Test fun pagesFromDifferentCatalogGenerationsCannotMix() {
        val history=CatalogPageHistory<String>{it};val owner=history.reset()
        history.accept(owner,0,page(listOf("a")))
        assertFalse(history.accept(owner,1,page(listOf("b"),generation="g2")))
        assertEquals(0,history.index)
    }
}
