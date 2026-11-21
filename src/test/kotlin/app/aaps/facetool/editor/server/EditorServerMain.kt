package app.aaps.facetool.editor.server

import android.app.Application
import android.graphics.Bitmap
import app.aaps.facetool.snapshot.SnapshotClock
import app.aaps.facetool.snapshot.SnapshotData
import app.aaps.facetool.snapshot.SnapshotEngine
import app.aaps.facetool.wff.savePng
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPausedSystemClock
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue

/**
 * The spike of step 1 of `docs/face-editor-design.md`: Robolectric as a server. One test that does
 * not end: it starts an HTTP server on 127.0.0.1 and draws a design with the engine on request.
 *
 * It runs only with `-Deditor.serve=true`. Settings (system properties): `editor.portFile` the file
 * that gets the port when the server is ready, `editor.out` the folder for the pictures.
 *
 * Requests: `/render?zip=<file>&wake=<ms>&size=450&time=<epoch ms>` (without `wake`: the rest
 * picture), `/bench?zip=<file>` (the measures of step 1), `/stop`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["app.aaps.wear"], qualifiers = "xhdpi")
class EditorServerMain {

    private val jobs = LinkedBlockingQueue<FutureTask<String>>()
    private val clock = SnapshotClock()
    private var faces = 0

    @Volatile
    private var running = true

    @Test
    fun serve() {
        assumeTrue("Runs only as the editor server, with -Deditor.serve=true", System.getProperty("editor.serve") == "true")
        val out = File(System.getProperty("editor.out") ?: "build/editor/out").apply { mkdirs() }
        val app = RuntimeEnvironment.getApplication()
        clock.start()
        val engine = SnapshotEngine(app)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/render") { exchange ->
            answer(exchange) {
                val query = query(exchange)
                val started = System.nanoTime()
                val file = File(out, "render-${System.nanoTime()}.png")
                render(engine, File(query.getValue("zip")), query["time"]?.toLong() ?: DEFAULT_TIME, query["wake"]?.toLong(), query["size"]?.toInt() ?: 450).let { it.savePng(file); it.recycle() }
                """{"path": "${file.path}", "ms": ${(System.nanoTime() - started) / 1_000_000}}"""
            }
        }
        server.createContext("/bench") { exchange -> answer(exchange) { bench(engine, File(query(exchange).getValue("zip"))) } }
        server.createContext("/kept") { exchange -> answer(exchange) { query(exchange).let { benchKept(engine, File(it.getValue("zip")), File(it.getValue("other"))) } } }
        server.createContext("/stop") { exchange ->
            answer(exchange) {
                running = false
                """{"stopped": true}"""
            }
        }
        server.start()
        val upMs = System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime
        System.getProperty("editor.portFile")?.let { File(it).apply { parentFile?.mkdirs() }.writeText("${server.address.port}\n") }
        println("editor server ready on 127.0.0.1:${server.address.port} after $upMs ms")
        try {
            // The render thread: the Robolectric main thread takes the jobs one by one
            while (running) jobs.take().run()
        } finally {
            server.stop(0)
            clock.stop()
        }
    }

    /** Puts [work] into the queue of the render thread and answers the request with what it gives. */
    private fun answer(exchange: HttpExchange, work: () -> String) {
        val job = FutureTask(Callable(work))
        jobs.put(job)
        val (code, body) = try {
            200 to job.get()
        } catch (e: Exception) {
            500 to """{"error": "${(e.cause ?: e).toString().replace("\"", "'")}"}"""
        }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery.orEmpty().split("&").filter { "=" in it }.associate { URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

    /** Moves the frozen clock to [epochMs]. Robolectric's clock only goes forward, so a move back starts it again. */
    private fun clockTo(epochMs: Long) {
        if (epochMs < clock.now) ShadowPausedSystemClock.reset()
        if (epochMs != clock.now) clock.setTo(epochMs)
    }

    /**
     * The design of [zip] at [timeMs]: at rest, or [wakeMs] after its wake began. A new face per
     * picture, so a picture never depends on the pictures before it.
     */
    private fun render(engine: SnapshotEngine, zip: File, timeMs: Long, wakeMs: Long?, size: Int): Bitmap {
        val design = engine.loadDesign(zip)
        val start = timeMs - (wakeMs ?: REST_LEAD_MS)
        clockTo(start)
        val face = engine.Face("spike${faces++}")
        try {
            face.settingsAtDefaults()
            face.feed(SnapshotData(weather = "clear"), timeMs)
            face.show(design)
            // The wake starts with the first frame drawn
            face.frame(size, clock.now).recycle()
            clockTo(timeMs)
            return face.frame(size, clock.now)
        } finally {
            face.release()
        }
    }

    private var kept: SnapshotEngine.Face? = null
    private var keptFedAt = -1L

    /**
     * As [render], with one face that is kept between the pictures: its values are fed only when the
     * time of the scene changes (the feed waits for the data store of the watch, 4 times half a second).
     * The part that draws is made new for each picture, so the wake starts again.
     */
    private fun renderKept(engine: SnapshotEngine, zip: File, timeMs: Long, wakeMs: Long?, size: Int): Bitmap {
        val design = engine.loadDesign(zip)
        clockTo(timeMs - (wakeMs ?: REST_LEAD_MS))
        val face = kept ?: engine.Face("kept").also { kept = it }
        face.release()
        face.settingsAtDefaults()
        if (keptFedAt != timeMs) {
            face.feed(SnapshotData(weather = "clear"), timeMs)
            keptFedAt = timeMs
        }
        face.show(design)
        face.frame(size, clock.now).recycle()
        clockTo(timeMs)
        return face.frame(size, clock.now)
    }

    /** The kept face: how long a picture takes, and whether it is the picture of a new face. */
    private fun benchKept(engine: SnapshotEngine, zip: File, other: File): String {
        fun timed(work: () -> Unit): Long = System.nanoTime().let { started -> work(); (System.nanoTime() - started) / 1_000_000 }
        val times = ArrayList<Long>()
        var same = true
        for (round in 0 until 3) for (design in listOf(zip, other)) {
            for (wake in listOf(null, 2500L)) {
                var keptPicture: Bitmap? = null
                times += timed { keptPicture = renderKept(engine, design, DEFAULT_TIME, wake, 450) }
                val fresh = render(engine, design, DEFAULT_TIME, wake, 450)
                same = same && fresh.sameAs(keptPicture)
                fresh.recycle()
                keptPicture?.recycle()
            }
        }
        return """{"keptMs": $times, "keptSameAsFresh": $same}"""
    }

    /** The measures of step 1, in ms. */
    private fun bench(engine: SnapshotEngine, zip: File): String {
        fun timed(work: () -> Unit): Long = System.nanoTime().let { started -> work(); (System.nanoTime() - started) / 1_000_000 }
        val rests = (0 until 5).map { timed { render(engine, zip, DEFAULT_TIME, null, 450).recycle() } }
        val wakes = (0 until 5).map { timed { render(engine, zip, DEFAULT_TIME, 2500, 450).recycle() } }
        // 100 frames of one wake, in one face
        val design = engine.loadDesign(zip)
        clockTo(DEFAULT_TIME)
        val face = engine.Face("spike${faces++}")
        var first = 0L
        val hundred = try {
            face.settingsAtDefaults()
            face.feed(SnapshotData(weather = "clear"), DEFAULT_TIME)
            face.show(design)
            first = timed { face.frame(450, clock.now).recycle() }
            timed {
                for (i in 1..100) {
                    clockTo(DEFAULT_TIME + i * 60L)
                    face.frame(450, clock.now).recycle()
                }
            }
        } finally {
            face.release()
        }
        // Two pictures of the same moment must be the same, also after the clock went back
        val a = render(engine, zip, DEFAULT_TIME, null, 450)
        val b = render(engine, zip, DEFAULT_TIME, null, 450)
        val same = a.sameAs(b)
        a.recycle()
        b.recycle()
        System.gc()
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1 shl 20)
        return """{"restMs": $rests, "wakeFrameMs": $wakes, "firstFrameOfFaceMs": $first, "hundredWakeFramesMs": $hundred, "sameAfterClockBack": $same, "heapUsedMb": $heapMb, "heapTotalMb": ${runtime.totalMemory() / (1 shl 20)}}"""
    }

    private companion object {

        /** Longer than the longest wake-up animation the engine allows (30 s), as the baker. */
        const val REST_LEAD_MS = 31_000L

        /** 17 November 2026, 10:08 in Amsterdam: the moment of the parity suite. */
        val DEFAULT_TIME: Long = SnapshotClock.epochMs(LocalDateTime.of(2026, 11, 17, 10, 8))
    }
}
