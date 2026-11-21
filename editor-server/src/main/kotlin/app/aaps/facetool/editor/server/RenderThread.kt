package app.aaps.facetool.editor.server

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The one thread that draws (design 4.3): the Robolectric main thread. Other threads (HTTP) hand it
 * a job with [run] and wait for the answer. The owner of the thread calls [loop].
 *
 * @param direct true in a test that is itself on the Robolectric main thread: a job runs at once on
 *   the calling thread, and nobody calls [loop]
 */
class RenderThread(private val direct: Boolean = false) {

    private val jobs = LinkedBlockingQueue<FutureTask<*>>()
    private var thread: Thread? = null

    /** Runs [work] on the render thread and gives its result. An error of [work] is thrown here. */
    fun <T> run(work: () -> T): T {
        // A job that is already on the render thread runs at once: it must not wait for itself
        if (direct || Thread.currentThread() === thread) return work()
        val job = FutureTask(Callable(work))
        jobs.put(job)
        try {
            return job.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** Takes jobs and runs them on the calling thread while [running] says so. */
    fun loop(running: () -> Boolean) {
        thread = Thread.currentThread()
        while (running()) jobs.poll(500, TimeUnit.MILLISECONDS)?.run()
    }
}
