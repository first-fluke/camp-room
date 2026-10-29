package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RetainedCleanupOwnerTest {
    private class Scheduler {
        val queued = ArrayDeque<Pair<Long, () -> Unit>>()
        val owner = RetainedCleanupOwner(schedule = { delay, action -> synchronized(queued) { queued.add(delay to action) } })
        fun run(): Long {
            val (delay, action) = synchronized(queued) { queued.removeFirst() }
            action()
            return delay
        }
    }

    @Test fun failedReleaseRetainsExactClosureUntilRealRetrySuccess() {
        val scheduler = Scheduler()
        val engine = Any()
        var calls = 0
        scheduler.owner.retire(engine) { if (++calls == 1) throw IllegalStateException("release failed"); true }
        assertEquals(0, calls)
        assertEquals(0L, scheduler.run())
        assertTrue(scheduler.owner.hasPending())
        assertEquals(2000L, scheduler.run())
        assertEquals(2, calls)
        assertFalse(scheduler.owner.hasPending())
    }

    @Test fun duplicateRetirementPreservesFirstExactCleanupInsteadOfReplacingIt() {
        val scheduler = Scheduler()
        val engine = Any()
        var calls = 0
        scheduler.owner.retire(engine) { calls++; true }
        scheduler.owner.retire(engine) { fail("must not replace the exact original cleanup"); true }
        scheduler.run()
        assertEquals(1, calls)
        assertFalse(scheduler.owner.hasPending())
    }

    @Test fun pendingOldOwnerRejectsNewAdmissionAndLaterExternalRetryCanRecover() {
        val scheduler = Scheduler()
        val old = Any()
        var canRelease = false
        var calls = 0
        scheduler.owner.retire(old) { calls++; canRelease }
        repeat(5) { scheduler.run() }
        assertEquals(5, calls)
        assertTrue(scheduler.queued.isEmpty())
        assertThrows(IllegalStateException::class.java) { scheduler.owner.requireClearExcept(Any()) }
        assertTrue(scheduler.owner.hasPending())
        assertTrue(scheduler.queued.isEmpty())
        canRelease = true
        scheduler.owner.retryAfterExplicitRequest()
        scheduler.run()
        scheduler.owner.requireClear()
        assertEquals(6, calls)
    }

    @Test fun previousGenerationSuccessCannotEraseRetirementAddedDuringRelease() {
        val scheduler = Scheduler()
        val sameOwner = Any()
        var calls = 0
        scheduler.owner.retire(sameOwner) {
            calls++
            if (calls == 1) scheduler.owner.retire(sameOwner) { fail("keep original closure"); true }
            true
        }
        scheduler.run()
        assertTrue(scheduler.owner.hasPending())
        scheduler.run()
        assertEquals(2, calls)
        assertFalse(scheduler.owner.hasPending())
    }

    @Test fun equalButDistinctResourcesAreNotCollapsedByValueEquality() {
        val scheduler = Scheduler()
        data class Resource(val name: String)
        val a = Resource("equal")
        val b = Resource("equal")
        var aCalls = 0
        var bCalls = 0
        scheduler.owner.retire(a) { aCalls++; true }
        scheduler.owner.retire(b) { bCalls++; false }
        scheduler.run(); scheduler.run()
        assertEquals(1, aCalls); assertEquals(1, bCalls)
        assertTrue(scheduler.owner.hasPendingExcept(a))
        assertFalse(scheduler.owner.hasPendingExcept(b))
    }

    @Test(timeout = 4000) fun blockedCleanupDoesNotHoldAdmissionMetadataOrRunOnRetireCaller() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val owner = RetainedCleanupOwner(schedule = { _, task ->
            Thread { try { task() } finally { finished.countDown() } }.apply { isDaemon=true }.start()
        })
        val caller = Thread.currentThread()
        owner.retire(Any()) {
            assertNotSame(caller, Thread.currentThread())
            entered.countDown(); release.await(2, TimeUnit.SECONDS); true
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertTrue(owner.hasPending())
            val start=System.nanoTime()
            assertThrows(IllegalStateException::class.java) { owner.requireClear() }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300)
        } finally { release.countDown() }
        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertFalse(owner.hasPending())
    }

    @Test fun schedulingFailureStillKeepsOriginalObligationForLaterRetry() {
        var reject = true
        var task: (() -> Unit)? = null
        var failures = 0
        val owner = RetainedCleanupOwner(schedule={ _, next -> if(reject) throw IllegalStateException("executor unavailable") else task=next }, onFailure={failures++})
        var releases=0
        owner.retire(Any()) { releases++;true }
        assertTrue(owner.hasPending());assertEquals(1,failures);assertEquals(0,releases)
        reject=false;owner.retryAfterExplicitRequest();task!!()
        assertFalse(owner.hasPending());assertEquals(1,releases)
    }

    @Test fun retainedSpeechCleanupRetriesOnlyFailedEngineReleaseAfterHostRetires() {
        val scheduler=Scheduler()
        val cleanup=SpeechCleanupState()
        val exactEngine=Any()
        var stops=0;var removes=0;var releases=0
        scheduler.owner.retire(exactEngine) {
            cleanup.clean(true,{stops++},{removes++},{if(++releases==1)throw IllegalStateException("release failed")})
        }
        scheduler.run()
        assertTrue(scheduler.owner.hasPending())
        scheduler.run()
        assertFalse(scheduler.owner.hasPending())
        assertEquals(1,stops);assertEquals(1,removes);assertEquals(2,releases)
    }

    @Test fun explicitRequestRestartsExhaustedCleanupButLateRetirementDoesNot() {
        val scheduler=Scheduler()
        val exactEngine=Any()
        val state=SpeechCleanupState()
        var fail=true
        var releases=0
        val cleanup={ state.clean(false,{}, {}, { releases++;if(fail)throw IllegalStateException("release failed") }) }
        scheduler.owner.retire(exactEngine,cleanup)
        repeat(5) { scheduler.run() }
        assertTrue(scheduler.owner.hasPending());assertEquals(5,releases)
        fail=false
        scheduler.owner.retire(exactEngine,cleanup)
        assertTrue(scheduler.queued.isEmpty());assertEquals(5,releases)
        scheduler.owner.retryAfterExplicitRequest()
        scheduler.run()
        assertFalse(scheduler.owner.hasPending());assertEquals(6,releases)
        scheduler.owner.retire(exactEngine,cleanup)
        scheduler.run()
        assertFalse(scheduler.owner.hasPending());assertEquals(6,releases)
    }

    @Test fun permanentFailureAndLateAutomaticFailuresStopAfterFiveAttempts() {
        val scheduler=Scheduler()
        val identity=Any()
        var calls=0
        val cleanup={calls++;false}
        scheduler.owner.retire(identity,cleanup)
        repeat(5){scheduler.run()}
        repeat(50){
            scheduler.owner.retire(identity,cleanup)
            assertThrows(IllegalStateException::class.java){scheduler.owner.requireClear()}
            assertThrows(IllegalStateException::class.java){scheduler.owner.requireClearExcept(Any())}
        }
        assertEquals(5,calls);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
        scheduler.owner.retryAfterExplicitRequest()
        repeat(5){scheduler.run()}
        assertEquals(10,calls);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
    }

    @Test fun lateAsyncFailureDuringAndAfterFifthAttemptCannotReplenishBudget() {
        val scheduler=Scheduler()
        val identity=Any()
        var calls=0
        lateinit var cleanup:()->Boolean
        cleanup={
            calls++
            if(calls==5)scheduler.owner.retire(identity,cleanup)
            throw IllegalStateException("permanent release failure")
        }
        scheduler.owner.retire(identity,cleanup)
        repeat(5){scheduler.run()}
        // Models a previously scheduled stop cleanup reporting failure after
        // the direct retained pass has exhausted the last attempt.
        scheduler.owner.retire(identity,cleanup)
        assertEquals(5,calls);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
    }

    @Test fun lateFailureNotificationCannotExpediteTheAlreadyChosenBackoff() {
        val queued=ArrayDeque<Pair<Long,()->Unit>>()
        val identity=Any()
        lateinit var owner:RetainedCleanupOwner
        lateinit var cleanup:()->Boolean
        var calls=0
        owner=RetainedCleanupOwner(schedule={delay,action->queued.add(delay to action)},onFailure={
            // The native pass has returned and cleared its running flag, while
            // the chosen retry delay has not yet been submitted to the scheduler.
            owner.retire(identity,cleanup)
        })
        cleanup={calls++;throw IllegalStateException("failed release")}
        owner.retire(identity,cleanup)
        assertEquals(0L,queued.removeFirst().let{it.second();it.first})
        assertEquals(1,calls)
        assertEquals(2000L,queued.first().first)
        for(delay in listOf(2000L,4000L,8000L,15000L)) {
            val next=queued.removeFirst();assertEquals(delay,next.first);next.second()
        }
        assertEquals(5,calls);assertTrue(queued.isEmpty());assertTrue(owner.hasPending())
    }

    @Test fun automaticSuccessfulRevisionChurnConsumesRemainingBudgetWithoutFalseCompletion() {
        val scheduler=Scheduler()
        val identity=Any()
        var calls=0
        var notify=true
        lateinit var cleanup:()->Boolean
        cleanup={
            calls++
            if(notify)scheduler.owner.retire(identity,cleanup)
            true
        }
        scheduler.owner.retire(identity,cleanup)
        repeat(5){scheduler.run()}
        assertEquals(5,calls);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
        notify=false
        scheduler.owner.retryAfterExplicitRequest();scheduler.run()
        assertEquals(6,calls);assertFalse(scheduler.owner.hasPending())
    }

    @Test fun directAndPreviouslyScheduledSpeechFailuresCannotCreateAnotherAutomaticBurst() {
        val scheduler=Scheduler()
        val identity=Any()
        val engine=SpeechCleanupState()
        val asynchronous=ArrayDeque<() -> Unit>()
        var passes=0;var releases=0;var failing=true
        lateinit var cleanup:()->Boolean
        fun attempt():Boolean {
            val complete=engine.clean(false,{}, {}, {
                releases++
                if(failing)throw IllegalStateException("permanent engine release failure")
            })
            if(!complete)scheduler.owner.retire(identity,cleanup)
            return complete
        }
        cleanup={
            passes++
            asynchronous.add{attempt();Unit}
            attempt()
        }
        scheduler.owner.retire(identity,cleanup)
        repeat(5){scheduler.run()}
        assertEquals(5,passes);assertEquals(5,releases)
        // Models cleanup jobs admitted by stopTts/stopStt but reporting their
        // failures after the fifth direct retained cleanup pass completed.
        while(asynchronous.isNotEmpty())asynchronous.removeFirst()()
        assertEquals(5,passes);assertEquals(10,releases)
        assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
        failing=false
        scheduler.owner.retryAfterExplicitRequest();scheduler.run()
        while(asynchronous.isNotEmpty())asynchronous.removeFirst()()
        assertEquals(6,passes);assertEquals(11,releases);assertFalse(scheduler.owner.hasPending())
    }

    @Test(timeout=6000) fun explicitRetryDuringLastInFlightFailureIsReservedOnceAndStaysBounded() {
        val scheduler=Scheduler()
        val identity=Any()
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        scheduler.owner.retire(identity) {
            if(calls.incrementAndGet()==5){entered.countDown();release.await(3,TimeUnit.SECONDS)}
            false
        }
        repeat(4){scheduler.run()}
        val fifth=synchronized(scheduler.queued){scheduler.queued.removeFirst().second}
        Thread{try{fifth()}finally{finished.countDown()}}.apply{isDaemon=true}.start()
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        try {
            scheduler.owner.retryAfterExplicitRequest()
            scheduler.owner.retryAfterExplicitRequest()
            assertEquals(5,calls.get())
        }finally{release.countDown()}
        assertTrue(finished.await(1,TimeUnit.SECONDS))
        assertEquals(1,synchronized(scheduler.queued){scheduler.queued.size})
        repeat(5){scheduler.run()}
        assertEquals(10,calls.get());assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
    }
}
