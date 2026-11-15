package com.samusynth.questcamera.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DurableIoQueueTest {
    @Test
    fun jobsRunInSubmissionOrderOffTheCallersThread() {
        val caller = Thread.currentThread()
        val order = Collections.synchronizedList(ArrayList<Int>())
        var ranOnCaller = false
        DurableIoQueue("order", capacity = 64).use { queue ->
            repeat(50) { i ->
                queue.submit {
                    if (Thread.currentThread() === caller) ranOnCaller = true
                    order.add(i)
                }
            }
            assertTrue(queue.awaitIdle(5_000))
        }
        assertEquals((0 until 50).toList(), order)
        assertFalse(ranOnCaller)
    }

    @Test
    fun submitNeverWaitsBehindASlowJob() {
        val release = CountDownLatch(1)
        DurableIoQueue("slow", capacity = 4).use { queue ->
            queue.submit { release.await(10, TimeUnit.SECONDS) } // a stalled fsync
            val startNs = System.nanoTime()
            repeat(4) { queue.submit {} }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue("submitting behind a stalled job took $elapsedMs ms", elapsedMs < 50)
            assertEquals(5, queue.maxPending)
            release.countDown()
            assertTrue(queue.awaitIdle(5_000))
        }
    }

    @Test
    fun aFullQueueFailsLoudlyAndStillRunsWhatItHolds() {
        val release = CountDownLatch(1)
        val ran = Collections.synchronizedList(ArrayList<Int>())
        val queue = DurableIoQueue("full", capacity = 2)
        queue.submit { release.await(10, TimeUnit.SECONDS); ran.add(0) } // running
        queue.submit { ran.add(1) } // queued
        queue.submit { ran.add(2) } // queued: capacity reached
        val overflow = runCatching { queue.submit { ran.add(3) } }.exceptionOrNull()
        assertTrue("expected BacklogExceeded, got $overflow", overflow is DurableIoQueue.BacklogExceeded)
        // The overflow is sticky: no later job slips in after the hole it left.
        assertTrue(runCatching { queue.submit { ran.add(4) } }.exceptionOrNull() is DurableIoQueue.BacklogExceeded)
        release.countDown()
        assertTrue(queue.awaitIdle(5_000))
        queue.close()
        assertEquals(listOf(0, 1, 2), ran)
        assertNotNull(queue.failure)
    }

    @Test
    fun aFailedJobSkipsLaterJobsAndSurfacesOnTheNextSubmit() {
        val ran = Collections.synchronizedList(ArrayList<Int>())
        val gate = CountDownLatch(1)
        DurableIoQueue("failed", capacity = 8).use { queue ->
            queue.submit { gate.await(10, TimeUnit.SECONDS); throw IOException("disk gone") }
            queue.submit { ran.add(1) } // queued before the failure: must not leave a hole
            gate.countDown()
            assertTrue(queue.awaitIdle(5_000))
            val thrown = runCatching { queue.submit { ran.add(2) } }.exceptionOrNull()
            assertTrue("expected IOException, got $thrown", thrown is IOException)
            assertTrue(thrown !is DurableIoQueue.BacklogExceeded)
        }
        assertEquals(emptyList<Int>(), ran)
    }

    @Test
    fun awaitIdleIsBounded() {
        val release = CountDownLatch(1)
        DurableIoQueue("bounded", capacity = 2).use { queue ->
            queue.submit { release.await(10, TimeUnit.SECONDS) }
            val startNs = System.nanoTime()
            assertFalse(queue.awaitIdle(100))
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue("awaitIdle(100) took $elapsedMs ms", elapsedMs in 90..1_000)
            release.countDown()
            assertTrue(queue.awaitIdle(5_000))
        }
    }
}
