package com.samusynth.questcamera.io

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One ordered I/O thread with a bounded backlog: the only place a recording's MP4 fragments
 * and timestamp sidecar touch storage while it records.
 *
 * Jobs run in submission order, so a job submitted after a fragment's write + fsync job runs
 * only once that fragment is durable. [submit] never blocks: when [capacity] jobs are already
 * waiting it throws [BacklogExceeded] (the recorder ends that eye with a capture error rather
 * than let storage stall the camera), and the jobs already queued still run. A job that throws
 * fails the queue: later jobs are skipped (a hole after a failed fragment would corrupt the
 * file) and the next [submit] throws. Pure JVM, so it is unit-tested on the host.
 */
class DurableIoQueue(name: String, val capacity: Int) : AutoCloseable {
    init {
        require(capacity > 0) { "Capacity must be positive" }
    }

    /** Storage fell [capacity] jobs behind the recorder. */
    class BacklogExceeded(message: String) : IOException(message)

    private val lock = Object()
    private var pending = 0 // queued + running, guarded by lock
    private var maxPendingJobs = 0
    @Volatile
    private var jobFailure: Throwable? = null
    @Volatile
    private var backlogFailure: BacklogExceeded? = null

    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(capacity),
        { task -> Thread(task, "DurableIo-$name").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    /** Most jobs ever waiting or running at once. */
    val maxPending: Int get() = synchronized(lock) { maxPendingJobs }

    /** The first job failure or backlog overflow, or null. */
    val failure: Throwable? get() = jobFailure ?: backlogFailure

    /** Queues [job] behind every earlier one; never blocks. */
    fun submit(job: () -> Unit) {
        throwIfFailed()
        synchronized(lock) {
            pending++
            if (pending > maxPendingJobs) maxPendingJobs = pending
        }
        try {
            executor.execute {
                try {
                    if (jobFailure == null) job()
                } catch (failure: Throwable) {
                    if (jobFailure == null) jobFailure = failure
                } finally {
                    synchronized(lock) {
                        pending--
                        lock.notifyAll()
                    }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            synchronized(lock) {
                pending--
                lock.notifyAll()
            }
            if (executor.isShutdown) throw IOException("Durable I/O queue is closed", rejected)
            val overflow = BacklogExceeded(
                "Storage fell behind the recorder: $capacity durable writes already waiting",
            )
            if (backlogFailure == null) backlogFailure = overflow
            throw overflow
        }
    }

    /**
     * Waits until every submitted job has run, at most [timeoutMs]. Returns false on timeout.
     * Never call it from the camera thread.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            while (pending > 0) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0L) return false
                lock.wait(remainingMs)
            }
        }
        return true
    }

    fun throwIfFailed() {
        jobFailure?.let { throw IOException("A durable write failed", it) }
        backlogFailure?.let { throw it }
    }

    /** Runs every queued job (bounded by [timeoutMs]), then stops the thread. */
    fun close(timeoutMs: Long) {
        executor.shutdown()
        if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) executor.shutdownNow()
    }

    override fun close() = close(DEFAULT_CLOSE_TIMEOUT_MS)

    companion object {
        private const val DEFAULT_CLOSE_TIMEOUT_MS = 30_000L
    }
}
