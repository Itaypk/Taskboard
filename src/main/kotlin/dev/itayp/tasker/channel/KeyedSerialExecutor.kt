package dev.itayp.tasker.channel

import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs tasks in parallel across keys, but one at a time and in submission order within a key.
 *
 * Built for inbound chat messages: every conversation (key) gets its own ordered lane, so one
 * user's slow model call no longer holds up everyone else, while two messages from the same
 * conversation still never race on its stored state (a quick-add draft, a planning session).
 *
 * Each key's lane is a chain of futures: a new task is appended to the key's tail and runs on
 * [executor] once the previous one has finished, however that one ended. A lane is dropped from
 * the map as soon as its last task completes, so idle conversations cost nothing.
 *
 * Tasks are expected to handle their own exceptions; one that escapes is logged and does not
 * stop the tasks queued behind it.
 */
class KeyedSerialExecutor<K : Any>(
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : AutoCloseable {

    private val tails = ConcurrentHashMap<K, CompletableFuture<Unit>>()

    fun execute(key: K, task: Runnable) {
        val next = tails.compute(key) { _, tail ->
            (tail ?: DONE).handleAsync({ _, _ -> runLogged(key, task) }, executor)
        }!!
        // Outside compute(): a task that is already done would otherwise remove its own mapping
        // from inside the update, which ConcurrentHashMap forbids. remove(key, value) leaves a
        // newer tail alone.
        next.whenComplete { _, _ -> tails.remove(key, next) }
    }

    /** Number of keys with a running or queued task. For tests and diagnostics. */
    fun activeKeys(): Int = tails.size

    private fun runLogged(key: K, task: Runnable) {
        try {
            task.run()
        } catch (e: Exception) {
            logger.error("Unhandled error in a task for key {}", key, e)
        }
    }

    /**
     * Stops accepting work and gives running tasks [gracePeriodSeconds] to finish. Tasks still
     * queued behind a running one are rejected when their turn comes and are dropped.
     */
    fun close(gracePeriodSeconds: Long) {
        executor.shutdown()
        if (!executor.awaitTermination(gracePeriodSeconds, TimeUnit.SECONDS)) {
            logger.warn("Tasks still running after {}s; abandoning them", gracePeriodSeconds)
        }
    }

    override fun close() = close(DEFAULT_GRACE_PERIOD_SECONDS)

    companion object {
        private val logger = LoggerFactory.getLogger(KeyedSerialExecutor::class.java)
        private val DONE: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
        const val DEFAULT_GRACE_PERIOD_SECONDS = 10L
    }
}
