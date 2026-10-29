package camp.room.app

import camp.room.app.observability.Action
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CampInteractionStateTest {
    private fun delivery() =
        DeliveryDetails(
            destination = "home",
            recipientName = "Camper",
            address = "Address A",
            siteReference = "A-12",
            campsiteId = "site-a",
            checkIn = "2026-11-07",
            checkOut = "2026-11-08",
        )

    private fun cart() = Cart(listOf(CartItem("lantern", "Lantern", 1, 1000, 1000)), 1000, 0, 1000)

    @Test fun `quote eligibility cannot survive changed delivery dates or cart facts`() {
        val details = delivery()
        val cart = cart()
        val quoted = DeliveryQuoteSnapshot.capture(details, cart)
        assertEquals(quoted, DeliveryQuoteSnapshot.capture(details.copy(), cart.copy()))
        for (changed in listOf(
            details.copy(address = "Address B"),
            details.copy(recipientName = "Other camper"),
            details.copy(destination = "campsite"),
            details.copy(siteReference = "B-13"),
            details.copy(checkOut = "2026-11-09"),
        )) {
            assertNotEquals(quoted, DeliveryQuoteSnapshot.capture(changed, cart))
        }
        assertNotEquals(
            quoted,
            DeliveryQuoteSnapshot.capture(
                details,
                cart.copy(items = listOf(cart.items.single().copy(quantity = 2, lineTotal = 2000)), subtotal = 2000, total = 2000),
            ),
        )
        assertNotEquals(
            quoted,
            DeliveryQuoteSnapshot.capture(
                details,
                cart.copy(items = listOf(cart.items.single().copy(unitPrice = 1200, lineTotal = 1200)), subtotal = 1200, total = 1200),
            ),
        )
    }

    @Test fun `captured cart is immutable even when a caller replaces its mutable source items`() {
        val items = cart().items.toMutableList()
        val mutableCart = cart().copy(items = items)
        val captured = DeliveryQuoteSnapshot.capture(delivery(), mutableCart)
        items[0] = items[0].copy(quantity = 2, lineTotal = 2000)
        assertNotEquals(captured, DeliveryQuoteSnapshot.capture(delivery(), mutableCart))
        assertEquals(captured, DeliveryQuoteSnapshot.capture(delivery(), cart()))
    }

    @Test fun `closing voice cancels recognition runs cleanup and rejects a late result`() =
        runBlocking {
            val owned = CampActionJobs()
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<String>()
            var published = false
            var stopped = false
            var busy = true
            val recognition =
                launch(start = CoroutineStart.LAZY) {
                    try {
                        started.complete(Unit)
                        response.await()
                        published = true
                    } finally {
                        stopped = true
                        busy = false
                        owned.complete(currentCoroutineContext()[Job]!!)
                    }
                }
            owned.track(Action.LISTEN, recognition)
            recognition.start()
            started.await()
            owned.cancelRecognition()
            withTimeout(1000) { recognition.join() }
            response.complete("Late recognition callback")
            assertTrue(recognition.isCancelled)
            assertTrue(stopped)
            assertFalse(busy)
            assertFalse(published)
        }

    @Test fun `UX restriction cancels user action while read-only loads and unrelated voice close stay scoped`() =
        runBlocking {
            val owned = CampActionJobs()
            val loadResult = CompletableDeferred<Unit>()
            val load = launch { loadResult.await() }
            owned.track(Action.LOAD, load)
            owned.cancelUserAction()
            assertFalse(load.isCancelled)
            loadResult.complete(Unit)
            withTimeout(1000) { load.join() }

            val orderResult = CompletableDeferred<Unit>()
            val order = launch { orderResult.await() }
            owned.track(Action.CHECKOUT, order)
            owned.cancelRecognition()
            assertFalse(order.isCancelled)
            owned.cancelUserAction()
            withTimeout(1000) { order.join() }
            assertTrue(order.isCancelled)
        }
}
