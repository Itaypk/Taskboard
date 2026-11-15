package dev.itayp.tasker.channel

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyedSerialExecutorTest {

    private val lanes = KeyedSerialExecutor<Long>()

    @AfterEach
    fun close() = lanes.close()

    @Test
    fun `a blocked key does not hold up another key`() {
        val release = CountDownLatch(1)
        val otherRan = CountDownLatch(1)

        lanes.execute(1L) { release.await() }
        lanes.execute(2L) { otherRan.countDown() }

        assertTrue(otherRan.await(5, TimeUnit.SECONDS), "key 2 waited for key 1")
        release.countDown()
    }

    @Test
    fun `tasks of one key run one at a time, in submission order`() {
        val order = Collections.synchronizedList(mutableListOf<Int>())
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val done = CountDownLatch(200)

        repeat(200) { i ->
            lanes.execute(1L) {
                maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                order += i
                Thread.yield()
                running.decrementAndGet()
                done.countDown()
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals((0 until 200).toList(), order.toList())
        assertEquals(1, maxRunning.get())
    }

    @Test
    fun `a task that throws does not stop the ones queued behind it`() {
        val ran = CountDownLatch(1)

        lanes.execute(1L) { error("boom") }
        lanes.execute(1L) { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an idle key leaves no lane behind`() {
        val done = CountDownLatch(3)
        lanes.execute(1L) { done.countDown() }
        lanes.execute(1L) { done.countDown() }
        lanes.execute(2L) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))

        // The lane is dropped by a completion callback that runs just after the task returns.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (lanes.activeKeys() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(0, lanes.activeKeys())
    }

    @Test
    fun `close lets a running task finish`() {
        val started = CountDownLatch(1)
        val finished = AtomicInteger()
        lanes.execute(1L) {
            started.countDown()
            Thread.sleep(100)
            finished.incrementAndGet()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        lanes.close(gracePeriodSeconds = 5)

        assertEquals(1, finished.get())
    }
}
