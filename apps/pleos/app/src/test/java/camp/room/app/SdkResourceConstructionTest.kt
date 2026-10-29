package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SdkResourceConstructionTest {
    private class FakeEngine { var releases=0;var failRelease=true;fun release(){releases++;if(failRelease)throw IllegalStateException("native release failed")} }
    private class FakeRun(val engine:FakeEngine,val kind:SpeechKind) {val cleanup=SpeechCleanupState();val signals=SpeechRunSignals()}

    private fun lateCreationRace(kind:SpeechKind) {
        val lock=Any()
        val construction=SdkResourceConstruction(lock)
        val scheduled=ArrayDeque<() -> Unit>()
        val retained=RetainedCleanupOwner(schedule={_,action->synchronized(scheduled){scheduled.add(action)}})
        val identity=Any()
        val pending=mutableSetOf<FakeRun>()
        val staging=mutableSetOf<FakeRun>()
        var active:FakeRun?=null
        var closed=false
        val engine=FakeEngine()
        fun clean(run:FakeRun) {
            val complete=run.cleanup.clean(false,{}, {},{run.engine.release()})
            synchronized(lock){if(complete)pending.remove(run)else pending.add(run)}
        }
        val cleanup={
            synchronized(lock){pending.addAll(staging);staging.clear();pending.toList()}.forEach(::clean)
            synchronized(lock){active==null && staging.isEmpty() && pending.isEmpty() && !construction.hasOutstanding()}
        }
        val entered=CountDownLatch(1);val returnEngine=CountDownLatch(1);val finished=CountDownLatch(1)
        val rejectedPublication=AtomicBoolean()
        Thread {
            var run:FakeRun?=null
            try {
                val created=construction.create(admit={ !closed },factory={entered.countDown();check(returnEngine.await(3,TimeUnit.SECONDS));engine},
                    construct={FakeRun(it,kind)},releaseUnwrapped={it.release()},adopt={staging.add(it)})
                run=created
                synchronized(lock) {
                    if(closed){rejectedPublication.set(true);created.signals.settle(SpeechOutcome(SpeechPhase.CANCELLED,"CANCELLED"))}
                    else {staging.remove(created);active=created}
                }
            }finally {
                run?.let {owned->synchronized(lock){if(active===owned)active=null;staging.remove(owned);pending.add(owned)};clean(owned)}
                if(closed)retained.retire(identity,cleanup)
                finished.countDown()
            }
        }.apply{isDaemon=true}.start()
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        synchronized(lock){closed=true;assertTrue(pending.isEmpty());assertNull(active)}
        retained.retire(identity,cleanup)
        synchronized(scheduled){scheduled.removeFirst()}()
        // Empty published sets cannot mean complete while the factory lease is live.
        assertTrue(construction.hasOutstanding());assertTrue(retained.hasPending())
        assertThrows(IllegalStateException::class.java){retained.requireClearExcept(Any())}
        returnEngine.countDown()
        assertTrue(finished.await(2,TimeUnit.SECONDS))
        assertTrue(rejectedPublication.get());assertEquals(1,engine.releases)
        assertFalse(construction.hasOutstanding());assertTrue(retained.hasPending())
        engine.failRelease=false
        synchronized(scheduled){scheduled.removeFirst()}()
        retained.requireClear()
        assertEquals(2,engine.releases)
        assertTrue(staging.isEmpty());assertTrue(pending.isEmpty());assertNull(active)
    }

    @Test(timeout=6000) fun ttsFactoryLeaseSurvivesCloseLateReleaseFailureAndNewOwnerAdmission(){lateCreationRace(SpeechKind.PLAYBACK)}
    @Test(timeout=6000) fun sttFactoryLeaseSurvivesCloseLateReleaseFailureAndNewOwnerAdmission(){lateCreationRace(SpeechKind.RECOGNITION)}

    @Test fun providerFailureWithoutReturnedHandleEndsLeaseWithoutInventingAResource() {
        val construction=SdkResourceConstruction(Any())
        var releases=0
        val provider:()->FakeEngine={throw IllegalStateException("provider unavailable")}
        assertThrows(IllegalStateException::class.java) {
            construction.create(factory=provider,
                construct={FakeRun(it,SpeechKind.PLAYBACK)},releaseUnwrapped={releases++},adopt={fail("no run")})
        }
        assertFalse(construction.hasOutstanding());assertFalse(construction.hasAbandoned());assertEquals(0,releases)
    }

    @Test fun constructorFailureRetainsReturnedExactEngineUntilItsNativeReleaseActuallySucceeds() {
        val construction=SdkResourceConstruction(Any())
        val engine=FakeEngine()
        assertThrows(IllegalArgumentException::class.java) {
            construction.create(factory={engine},construct={throw IllegalArgumentException("wrapper failed")},
                releaseUnwrapped={assertSame(engine,it);it.release()},adopt={fail("no wrapper")})
        }
        assertTrue(construction.hasAbandoned());assertTrue(construction.hasOutstanding())
        construction.drainAbandoned()
        assertEquals(1,engine.releases);assertTrue(construction.hasOutstanding())
        engine.failRelease=false
        construction.drainAbandoned()
        assertEquals(2,engine.releases);assertFalse(construction.hasOutstanding())
        construction.drainAbandoned();assertEquals(2,engine.releases)
    }

    @Test fun closedHostRejectsLeaseBeforeCallingProvider() {
        val construction=SdkResourceConstruction(Any())
        var factories=0
        assertThrows(IllegalStateException::class.java) {
            construction.create(admit={false},factory={factories++;FakeEngine()},construct={it},releaseUnwrapped={it.release()},adopt={})
        }
        assertEquals(0,factories);assertFalse(construction.hasOutstanding())
    }

    @Test fun successfulConstructionTransfersObligationToOwnedSetWithoutAnEmptyGap() {
        val lock=Any();val construction=SdkResourceConstruction(lock)
        val owned=mutableSetOf<FakeRun>()
        val uiCleanupPending=mutableSetOf<FakeRun>()
        val engine=FakeEngine()
        val run=construction.create(factory={engine},construct={FakeRun(it,SpeechKind.PLAYBACK)},releaseUnwrapped={it.release()},adopt={
            assertTrue(Thread.holdsLock(lock));assertTrue(construction.hasOutstanding());owned.add(it)
            assertTrue(uiCleanupPending.isEmpty())
        })
        assertFalse(construction.hasOutstanding());assertEquals(setOf(run),owned);assertTrue(uiCleanupPending.isEmpty());assertEquals(0,engine.releases)
    }

    @Test(timeout=5000) fun blockedAbandonedNativeReleaseCannotHoldCallbackMetadata() {
        val lock=Any();val construction=SdkResourceConstruction(lock)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        assertThrows(IllegalStateException::class.java) {
            construction.create(factory={Any()},construct={throw IllegalStateException("wrapper")},
                releaseUnwrapped={assertFalse(Thread.holdsLock(lock));entered.countDown();release.await(3,TimeUnit.SECONDS)},adopt={})
        }
        Thread{try{construction.drainAbandoned()}finally{done.countDown()}}.apply{isDaemon=true}.start()
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        try {
            val start=System.nanoTime()
            synchronized(lock){assertTrue(construction.hasOutstanding())}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300)
        }finally{release.countDown()}
        assertTrue(done.await(1,TimeUnit.SECONDS));assertFalse(construction.hasOutstanding())
    }

    @Test fun privateRunOutcomeSettlesWaitersWithoutUiPublicationAndPreservesFirstTerminal()=runBlocking {
        val signals=SpeechRunSignals()
        val outcome=SpeechOutcome(SpeechPhase.FAILED,"PRIVATE_CREATION_FAILED")
        signals.settle(outcome)
        signals.settle(SpeechOutcome(SpeechPhase.CANCELLED,"LATE"))
        assertFalse(signals.initialized.await());assertFalse(signals.started.await())
        assertEquals(outcome,withTimeout(500){signals.terminal.await()})
    }
}
