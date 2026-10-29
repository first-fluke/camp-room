package camp.room.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class NavigationSdkIoTest {
    private class CleanupScheduler {
        val queued=ArrayDeque<() -> Unit>()
        val owner=RetainedCleanupOwner(schedule={_,action->queued.add(action)})
        fun run()=queued.removeFirst()()
    }

    private fun isolatedIo(nowMs: () -> Long = { System.nanoTime()/1_000_000 }, onFailure: (Exception) -> Unit = {}) =
        NavigationSdkIo(nowMs=nowMs, onFailure=onFailure,
            retainedOwner=RetainedCleanupOwner(schedule={ _, _ -> }))

    @Test(timeout = 6000) fun blockedPreviewCannotHoldLifecycleMetadataOrReviveOldRequestAfterRestart() {
        val io = isolatedIo()
        val requests = NavigationRequestState()
        var epoch = 1
        var foreground = true
        val token = requests.begin(NavigationRequestKind.PREVIEW, SdkDestinationRequest("same", 37.0, 127.0), epoch)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val accepted = AtomicInteger()
        Thread {
            try {
                io.call({ foreground && epoch == token.connectionEpoch }) {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    synchronized(io.stateLock) {
                        if (foreground && requests.claim(token, epoch) == NavigationCallbackAdmission.CURRENT) accepted.incrementAndGet()
                    }
                }
            } finally { finished.countDown() }
        }.apply { isDaemon = true }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            val before = System.nanoTime()
            synchronized(io.stateLock) { foreground = false; epoch++; requests.invalidate() }
            synchronized(io.stateLock) { foreground = true }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500)
            assertFalse(finished.await(20, TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, accepted.get())
    }

    @Test(timeout = 6000) fun queuedDestinationReadFromSdkCallbackNeverWaitsVendorAndIsDroppedAfterStop() {
        val io = isolatedIo()
        var epoch = 4
        var foreground = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val destinationReads = AtomicInteger()
        val followupsDrained = CountDownLatch(1)
        Thread {
            try { io.call { entered.countDown(); release.await(3, TimeUnit.SECONDS) } }
            finally { finished.countDown() }
        }.apply { isDaemon = true }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        try {
            val before = System.nanoTime()
            assertTrue(io.enqueue("destination:old", { foreground && epoch == 4 }) { destinationReads.incrementAndGet() })
            assertTrue(io.enqueue("destination:sentinel", { true }) { followupsDrained.countDown() })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500)
            synchronized(io.stateLock) { foreground = false; epoch++ }
        } finally { release.countDown() }
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        // The same FIFO callback consumer must process the old destination task
        // before this sentinel; a direct call could otherwise win the vendor lock.
        assertTrue(followupsDrained.await(2, TimeUnit.SECONDS))
        assertEquals(0, destinationReads.get())
    }

    @Test fun metadataHeldNativeEntryIsRejectedInsteadOfWaitingForVendor() {
        val io = isolatedIo()
        synchronized(io.stateLock) {
            assertThrows(IllegalStateException::class.java) { io.call { fail("native must not execute") } }
            assertThrows(IllegalStateException::class.java) { io.enqueue("bad", { true }) { fail("native must not execute") } }
        }
    }

    @Test(timeout = 6000) fun exactRetiredResourcesAreCleanedBeforeNextNativeEntryWithoutBlockingLifecycleCaller() {
        val scheduler=CleanupScheduler()
        val io=NavigationSdkIo(retainedOwner=scheduler.owner)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val cleanupCalls=AtomicInteger()
        val identity=Any()
        Thread {
            try {io.call{entered.countDown();release.await(3,TimeUnit.SECONDS)}}
            finally{finished.countDown()}
        }.apply{isDaemon=true}.start()
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        try {
            val before=System.nanoTime()
            io.retire(identity){cleanupCalls.incrementAndGet()}
            io.retire(identity){fail("duplicate retirement must retain first exact cleanup")}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500)
            assertEquals(0,cleanupCalls.get())
        }finally{release.countDown()}
        assertTrue(finished.await(2,TimeUnit.SECONDS))
        assertThrows(IllegalStateException::class.java){io.call{fail("native while cleanup pending")}}
        scheduler.run()
        io.call{assertEquals(1,cleanupCalls.get())}
        assertFalse(io.cleanupPending())
    }

    @Test fun failedNativeReleaseIsRetainedAndActuallyRetriedBeforeNewFactoryCanRun() {
        val clock=AtomicLong()
        val scheduler=CleanupScheduler()
        val io=NavigationSdkIo(nowMs=clock::get,retainedOwner=scheduler.owner)
        val state=NavigationConnectionState()
        state.connect({}, {}){done,_->done()}
        var releases=0
        io.retire(state){state.close{if(++releases==1)throw IllegalStateException("temporary release failure")}}
        scheduler.run()
        assertTrue(io.cleanupPending())
        var factories=0
        assertThrows(IllegalStateException::class.java){io.call{factories++}}
        assertEquals(0,factories)
        clock.set(2000);scheduler.run()
        io.call{factories++}
        assertEquals(2,releases);assertEquals(1,factories);assertFalse(io.cleanupPending())
    }

    @Test fun retiredNavigationHostSurvivesUntilExactFailedReleaseIsRetriedBeforeReplacementFactory() {
        val scheduler=CleanupScheduler()
        val clock=AtomicLong()
        val old=NavigationSdkIo(nowMs=clock::get,retainedOwner=scheduler.owner)
        val connection=NavigationConnectionState()
        connection.connect({}, {}){ready,_->ready()}
        var releases=0
        old.retire(connection){connection.close{if(++releases==1)throw IllegalStateException("release failure")}}
        assertTrue(scheduler.owner.hasPending());assertEquals(0,releases)
        scheduler.run()
        val replacement=NavigationSdkIo(retainedOwner=scheduler.owner)
        var factories=0
        assertThrows(IllegalStateException::class.java){replacement.call{factories++}}
        assertEquals(0,factories)
        clock.set(2000);scheduler.run()
        replacement.call{factories++}
        assertEquals(2,releases);assertEquals(1,factories);assertFalse(scheduler.owner.hasPending())
    }

    @Test fun retiredBundleIsProcessVisibleBeforeBudgetedCleanupAndNeverUsesPrivateDrain() {
        val scheduled=ArrayDeque<() -> Unit>()
        lateinit var owner:RetainedCleanupOwner
        owner=RetainedCleanupOwner(schedule={_,action->assertTrue(owner.hasPending());scheduled.add(action)})
        var privateEntries=0;var cleanups=0
        val io=NavigationSdkIo(executor=java.util.concurrent.Executor{privateEntries++;it.run()},retainedOwner=owner)
        io.retire(Any()){cleanups++}
        assertEquals(0,privateEntries);assertEquals(0,cleanups);assertEquals(1,scheduled.size)
        scheduled.removeFirst()()
        assertEquals(1,cleanups);assertFalse(owner.hasPending())
    }

    @Test fun explicitOwnerRequestCanRetryExactNativeReleaseAfterFiveFailureBurstWithoutSpinning() {
        val scheduler=CleanupScheduler()
        val clock=AtomicLong()
        val old=NavigationSdkIo(nowMs=clock::get,retainedOwner=scheduler.owner)
        val state=NavigationConnectionState()
        state.connect({}, {}){ready,_->ready()}
        var failing=true;var releases=0
        old.retire(state){state.close{releases++;if(failing)throw IllegalStateException("release failed")}}
        assertEquals(0,releases)
        for(time in listOf(0L,2000L,6000L,14000L,29000L)){clock.set(time);scheduler.run()}
        assertEquals(5,releases);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
        val replacement=NavigationSdkIo(retainedOwner=scheduler.owner)
        var factories=0
        clock.set(44000)
        assertThrows(IllegalStateException::class.java){replacement.call{factories++}}
        assertEquals(0,factories);assertTrue(scheduler.queued.isEmpty())
        failing=false
        replacement.retryAfterExplicitRequest();scheduler.run()
        replacement.call{factories++}
        assertEquals(6,releases);assertEquals(1,factories);assertFalse(scheduler.owner.hasPending())
    }

    @Test fun automaticPreviewAndCallbackAdmissionsCannotBypassExhaustedCleanupBudget() {
        val scheduler=CleanupScheduler()
        val clock=AtomicLong()
        val old=NavigationSdkIo(executor=java.util.concurrent.Executor{it.run()},nowMs=clock::get,retainedOwner=scheduler.owner)
        val identity=Any()
        var releases=0
        var failing=true
        val nativeAttemptTimes=mutableListOf<Long>()
        val cleanup={
            releases++;nativeAttemptTimes.add(clock.get())
            if(failing)throw IllegalStateException("permanent release failure")
        }
        old.retire(identity,cleanup)
        for(time in listOf(0L,2000L,6000L,14000L,29000L)){clock.set(time);scheduler.run()}
        assertEquals(5,releases);assertTrue(scheduler.queued.isEmpty())
        repeat(30){
            clock.addAndGet(60000)
            assertThrows(IllegalStateException::class.java){old.call{fail("native factory while cleanup pending")}}
            old.enqueue("auto:$it",{true}){fail("callback native read while cleanup pending")}
            old.retire(identity,cleanup)
        }
        assertEquals(5,releases);assertTrue(scheduler.queued.isEmpty());assertTrue(old.cleanupPending())
        old.retryAfterExplicitRequest()
        val callsAfterPass=mutableListOf<Int>()
        for(time in listOf(2000000L,2002000L,2006000L,2014000L,2029000L)){
            clock.set(time);scheduler.run();callsAfterPass.add(releases)
        }
        // A new coordinator budget does not erase the existing native 15s
        // backoff. Passes at +2/+6/+14s cannot invoke release again early.
        assertEquals(listOf(6,6,6,6,7),callsAfterPass)
        assertEquals(listOf(2000000L,2029000L),nativeAttemptTimes.drop(5))
        assertEquals(7,releases);assertTrue(scheduler.queued.isEmpty());assertTrue(old.cleanupPending())
        repeat(20){
            clock.addAndGet(1000)
            assertThrows(IllegalStateException::class.java){old.call{fail("automatic factory while cleanup pending")}}
            old.enqueue("late:$it",{true}){fail("late callback native read while cleanup pending")}
            old.retire(identity,cleanup)
        }
        assertEquals(7,releases);assertTrue(scheduler.queued.isEmpty());assertTrue(scheduler.owner.hasPending())
        failing=false;clock.set(2050000L)
        old.retryAfterExplicitRequest();scheduler.run()
        val replacement=NavigationSdkIo(retainedOwner=scheduler.owner)
        var factories=0
        replacement.call{factories++}
        assertEquals(8,releases);assertEquals(2050000L,nativeAttemptTimes.last());assertEquals(1,factories)
        assertFalse(old.cleanupPending());assertFalse(scheduler.owner.hasPending());assertTrue(scheduler.queued.isEmpty())
    }

    @Test(timeout=6000) fun immediateFreshPreviewWaitsForHealthyAdmittedCleanupOutsideVendorLane() {
        val entered=CountDownLatch(1);val allowCleanup=CountDownLatch(1);val cleanupFinished=CountDownLatch(1)
        val owner=RetainedCleanupOwner(schedule={_,action->
            Thread{action()}.apply{isDaemon=true}.start()
        })
        val io=NavigationSdkIo(retainedOwner=owner,cleanupWaitMillis=1000)
        var releases=0
        io.retire(Any()){
            entered.countDown();check(allowCleanup.await(2,TimeUnit.SECONDS));releases++;cleanupFinished.countDown()
        }
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        val freshFinished=CountDownLatch(1);val factories=AtomicInteger()
        Thread{try{io.call{factories.incrementAndGet()}}finally{freshFinished.countDown()}}
            .apply{isDaemon=true}.start()
        try {
            assertFalse(freshFinished.await(20,TimeUnit.MILLISECONDS))
            val before=System.nanoTime()
            synchronized(io.stateLock){assertTrue(owner.hasPending())}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<300)
        }finally{allowCleanup.countDown()}
        assertTrue(cleanupFinished.await(1,TimeUnit.SECONDS));assertTrue(freshFinished.await(1,TimeUnit.SECONDS))
        assertEquals(1,releases);assertEquals(1,factories.get());assertFalse(owner.hasPending())
    }

    @Test(timeout=6000) fun blockedCleanupWaitIsBoundedAndDoesNotAdmitAnotherNativeAttempt() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val completed=CountDownLatch(1)
        val owner=RetainedCleanupOwner(schedule={_,action->
            Thread{try{action()}finally{completed.countDown()}}.apply{isDaemon=true}.start()
        })
        val io=NavigationSdkIo(retainedOwner=owner,cleanupWaitMillis=80)
        val releases=AtomicInteger()
        io.retire(Any()){releases.incrementAndGet();entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        try {
            val before=System.nanoTime()
            assertThrows(IllegalStateException::class.java){io.call{fail("native while cleanup blocked")}}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1000)
            synchronized(io.stateLock){assertTrue(owner.hasPending())}
            assertEquals(1,releases.get())
        }finally{release.countDown()}
        assertTrue(completed.await(1,TimeUnit.SECONDS));assertFalse(owner.hasPending())
    }
}
