/**
 * Stage 3 — FIXED confidence semaphore. Same prompt as Stage3Vibecoding, but with
 * plugin-encoded knowledge applied. All four designed bugs corrected:
 *
 *   FIX #1 (govee-h6056):       physical segments 0..11 only; Yankee=0-5, Golf=6-11
 *   FIX #2 (govee-h6056):       semaphore lives on Yankee bar ONLY; Golf untouched
 *   FIX #3 (face-recognition-calibration): piecewise confidence
 *                                d ≤ 0.30 → 1.0,  d ≥ 0.65 → 0.0,  linear between
 *   FIX #4 (govee-h6056):       segment[0] is the TOP of Yankee → bottom-up fill = high-to-low index
 *
 * Plus the implicit producer-loop fix (iot-actuator-patterns):
 *   - Off-thread debounce controller (min-interval 1.2 s, tick 0.4 s, stability filter 2 ticks, send-latest)
 *   - Target quantization to 3-level semaphore (0/1/2)
 *   - rgb=(1,1,1) for "off" — (0,0,0) is silently no-op on some firmware paths
 *
 * Plus face persistence (face-recognition-persistence): hold last distance for ~0.8 s of no-face.
 */
import ai.djl.modality.cv.ImageFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.opencv.global.opencv_imgcodecs.imencode
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX
import org.bytedeco.opencv.global.opencv_imgproc.LINE_8
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.putText
import org.bytedeco.opencv.global.opencv_imgproc.rectangle
import org.bytedeco.opencv.global.opencv_imgproc.resize
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Rect
import org.bytedeco.opencv.opencv_core.RectVector
import org.bytedeco.opencv.opencv_core.Scalar
import org.bytedeco.opencv.opencv_core.Size
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.system.exitProcess

// --- Physical device layout (govee-h6056 plugin) ---
private val YANKEE = (0..5).toList()   // segments in the physical Yankee bar (top bar)
private val GOLF = (6..11).toList()    // Golf (bottom bar) — unused by Stage 3 semaphore
private val ALL_PHYSICAL = (0..11).toList()

// Yankee zones (segment[0] is the TOP of Yankee — bottom-up fill goes high→low index):
private val Y_BOTTOM = listOf(5, 4)    // bottom two segments — RED (always on)
private val Y_MID = listOf(3, 2)       // middle two — YELLOW on level >= 1
private val Y_TOP = listOf(1, 0)       // top two — GREEN on level >= 2

private const val GOVEE = "https://openapi.api.govee.com"

private val RED = Triple(200, 0, 0)
private val YELLOW = Triple(255, 200, 0)
private val GREEN = Triple(0, 200, 0)
private val OFF_RGB = Triple(1, 1, 1)   // FIX: rgb(0,0,0) is silently no-op on some firmware paths

// --- Calibration (face-recognition-calibration adapted for FaceNet cosine distance) ---
private fun confidenceOf(d: Float): Float = when {
    d <= 0.30f -> 1.0f
    d >= 0.65f -> 0.0f
    else -> (0.65f - d) / 0.35f
}

private fun levelOf(conf: Float): Int = when {
    conf < 0.33f -> 0
    conf < 0.67f -> 1
    else -> 2
}

fun main(args: Array<String>) = runBlocking {
    loadDotEnv()
    val key = envOrFail("GOVEE_API_KEY")
    val sku = envOrFail("GOVEE_H6056_SKU")
    val device = envOrFail("GOVEE_H6056_DEVICE")

    val camIndex = args.getOrNull(0)?.toIntOrNull()
        ?: env("CAM")?.toIntOrNull()
        ?: 1
    val previewPort = env("PREVIEW_PORT")?.toIntOrNull() ?: 8080
    val previewEnabled = env("PREVIEW")?.lowercase() !in setOf("0", "false", "off", "no")
    val maxSeconds = env("MAX_SECONDS")?.toIntOrNull()
    val facesDir = findFacesDirS3F() ?: error("faces/ not found")

    log("Stage 3 FIXED — semaphore on Yankee, piecewise conf, off-thread controller")
    log("  camera=$camIndex  faces=$facesDir  preview=${if (previewEnabled) "http://localhost:$previewPort/" else "off"}")

    val cascadePath = extractCascadeS3F()
    val cascade = CascadeClassifier(cascadePath)
    check(!cascade.isNull) { "Failed to load Haar cascade" }

    log("loading DJL face_feature model…")
    val model = loadFaceFeatureModel()
    val predictor = model.newPredictor()
    log("model loaded")

    log("enrolling from $facesDir…")
    val enrolled = enrollAllS3F(facesDir, predictor, cascade)
    enrolled.forEach { (name, _) -> log("  enrolled: $name") }

    val grabber = OpenCVFrameGrabber(camIndex).apply {
        imageWidth = 1280
        imageHeight = 720
    }
    runCatching { grabber.start() }.onFailure {
        System.err.println("Failed to open camera $camIndex: ${it.message}")
        exitProcess(1)
    }
    log("camera opened ${grabber.imageWidth}x${grabber.imageHeight}")

    val preview = if (previewEnabled) Preview(previewPort, "Stage 3 FIXED — semaphore").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)
    val controller = DebounceController(client, key, sku, device).also { it.start(this) }

    val persistence = FacePersistence(maxAgeMs = 800)

    val white = Scalar(255.0, 255.0, 255.0, 0.0)
    val ovBg = Scalar(0.0, 0.0, 0.0, 0.0)

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            // Explicit all-physical-segments off on shutdown (govee-h6056)
            runCatching { goveeSet(client, key, sku, device, ALL_PHYSICAL, OFF_RGB) }
        }
    })

    var frames = 0
    var lastIdentities: List<Triple<Rect, String, Float>> = emptyList()
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0

    try {
        while (true) {
            val frame = grabber.grab() ?: continue
            frames++

            val color = matConverter.convert(frame) ?: continue
            val doDetect = frames % 3 == 0

            if (doDetect) {
                val small = Mat()
                resize(color, small, Size(color.cols() / 4, color.rows() / 4))
                val gray = Mat()
                cvtColor(small, gray, COLOR_BGR2GRAY)
                val raw = RectVector()
                cascade.detectMultiScale(gray, raw, 1.2, 5, 0, Size(20, 20), Size(0, 0))
                val ids = mutableListOf<Triple<Rect, String, Float>>()
                for (i in 0 until raw.size()) {
                    val s = raw.get(i)
                    val r = Rect(s.x() * 4, s.y() * 4, s.width() * 4, s.height() * 4)
                    val emb = embedFaceFromMatS3F(color, r, matConverter, predictor)
                    val (who, dist) = enrolled
                        .map { (name, ref) -> name to cosineDistance(emb, ref) }
                        .minBy { it.second }
                    ids.add(Triple(r, who, dist))
                }
                lastIdentities = ids
            }

            // Best distance across detected faces, with persistence
            val dRaw: Float? = lastIdentities.minByOrNull { it.third }?.third
            val dHeld = persistence.observe(dRaw, System.currentTimeMillis())
            val conf = if (dHeld != null) confidenceOf(dHeld) else 0f
            val level = levelOf(conf)

            controller.submit(level)

            // Draw face boxes
            for ((r, name, dist) in lastIdentities) {
                val tag = "$name ${"%.2f".format(dist)}"
                rectangle(color, Point(r.x(), r.y()), Point(r.x() + r.width(), r.y() + r.height()), white, 2, LINE_8, 0)
                putText(color, tag, Point(r.x(), r.y() - 8), FONT_HERSHEY_SIMPLEX, 0.6, white, 1, LINE_8, false)
            }

            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val confStr = "d=${dHeld?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  level=$level (committed=${controller.committed})"
            val status = "STAGE 3 FIXED  $confStr  fps=${"%.1f".format(fpsNow)}"
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.65, ovBg, 3, LINE_8, false)
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.65, white, 1, LINE_8, false)

            if (preview != null) {
                val buf = BytePointer()
                if (imencode(".jpg", color, buf)) {
                    val bytes = ByteArray(buf.limit().toInt())
                    buf.get(bytes, 0, bytes.size)
                    preview.update(bytes)
                }
                buf.deallocate()
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 2500) {
                log("  ${"%5.1f".format(elapsedNow)}s  frames=$frames  fps=${"%.1f".format(fpsNow)}  d=${dHeld?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  target=$level  committed=${controller.committed}")
                lastHeartbeat = now
            }

            if (maxSeconds != null && (now - t0) / 1000 >= maxSeconds) {
                log("MAX_SECONDS reached, exiting")
                break
            }
        }
    } finally {
        runCatching { grabber.stop() }
        runCatching { goveeSet(client, key, sku, device, ALL_PHYSICAL, OFF_RGB) }
        client.close()
        preview?.stop()
        predictor.close()
        model.close()
        log("done. frames=$frames")
    }
}

// --- iot-actuator-patterns: one-controller-per-device debounce ---
private class DebounceController(
    private val client: HttpClient,
    private val key: String, private val sku: String, private val device: String,
    private val minIntervalMs: Long = 1200,
    private val tickMs: Long = 400,
    private val stabilityTicks: Int = 2
) {
    @Volatile var target: Int = -1
    @Volatile var committed: Int = -1
    private var stableCount: Int = 0
    private var lastApply: Long = 0L

    fun submit(t: Int) { target = t }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(tickMs)
            val t = target
            if (t == committed) { stableCount = 0; continue }
            stableCount++
            if (stableCount < stabilityTicks) continue
            val now = System.currentTimeMillis()
            if (now - lastApply < minIntervalMs) continue
            try {
                applyLevel(t)
                committed = t
                stableCount = 0
                lastApply = now
                println("[ctrl] applied level=$t @ ${now % 100000}")
            } catch (e: Exception) {
                println("[ctrl] apply failed: ${e.message}")
            }
        }
    }

    private suspend fun applyLevel(level: Int) {
        // Bottom RED (always on when any signal — controller only commits non-zero from level 0+)
        goveeSet(client, key, sku, device, Y_BOTTOM, RED)
        goveeSet(client, key, sku, device, Y_MID, if (level >= 1) YELLOW else OFF_RGB)
        goveeSet(client, key, sku, device, Y_TOP, if (level >= 2) GREEN else OFF_RGB)
        // Golf left untouched — explicit no-op to make sure no stale state lingers from prior runs
        goveeSet(client, key, sku, device, GOLF, OFF_RGB)
    }
}

// --- face-recognition-persistence: hold last value across no-face gaps ---
private class FacePersistence(private val maxAgeMs: Long) {
    private var lastVal: Float? = null
    private var lastTs: Long = 0L

    fun observe(now: Float?, ts: Long): Float? {
        if (now != null) { lastVal = now; lastTs = ts; return now }
        return if (ts - lastTs > maxAgeMs) { lastVal = null; null } else lastVal
    }
}

private suspend fun goveeSet(
    client: HttpClient, key: String, sku: String, device: String,
    segments: List<Int>, rgb: Triple<Int, Int, Int>
): Int {
    val (r, g, b) = rgb
    val packed = (r shl 16) or (g shl 8) or b
    val segArr = segments.joinToString(",", "[", "]")
    val payload = """{"requestId":"${UUID.randomUUID()}","payload":{"sku":"$sku","device":"$device","capability":{"type":"devices.capabilities.segment_color_setting","instance":"segmentedColorRgb","value":{"segment":$segArr,"rgb":$packed}}}}"""
    return runCatching {
        client.post("$GOVEE/router/api/v1/device/control") {
            header("Content-Type", "application/json")
            header("Govee-API-Key", key)
            setBody(payload)
        }.status.value
    }.getOrElse { -1 }
}

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100000
    println("[${"%5d".format(t)}] $msg")
}

// --- Reused helpers (duplicated from Stage2/Stage3Vibecoding for clarity) ---

private fun findFacesDirS3F(): File? {
    val candidates = listOfNotNull(
        env("FACES_DIR")?.let { File(it) },
        File("faces"), File("../faces"), File("../../faces"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/faces")
    )
    return candidates.firstOrNull { it.isDirectory }
}

private fun enrollAllS3F(
    facesDir: File,
    predictor: ai.djl.inference.Predictor<ai.djl.modality.cv.Image, FloatArray>,
    cascade: CascadeClassifier
): Map<String, FloatArray> {
    val people = facesDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    val matConverter = OpenCVFrameConverter.ToMat()
    val result = mutableMapOf<String, FloatArray>()
    for (personDir in people) {
        val jpgs = personDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.toList().orEmpty()
        val embeddings = mutableListOf<FloatArray>()
        for (jpg in jpgs) {
            val mat = org.bytedeco.opencv.global.opencv_imgcodecs.imread(jpg.absolutePath)
            if (mat.empty()) continue
            val face = detectFaceFlexibleS3F(mat, cascade) ?: continue
            embeddings.add(embedFaceFromMatS3F(mat, face, matConverter, predictor))
        }
        if (embeddings.isEmpty()) continue
        val avg = FloatArray(embeddings[0].size)
        for (e in embeddings) for (i in e.indices) avg[i] += e[i]
        for (i in avg.indices) avg[i] /= embeddings.size.toFloat()
        var sum = 0.0
        for (v in avg) sum += v * v
        val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-8f)
        for (i in avg.indices) avg[i] /= norm
        result[personDir.name] = avg
    }
    return result
}

private fun detectFaceFlexibleS3F(mat: Mat, cascade: CascadeClassifier): Rect? {
    val gray = Mat()
    cvtColor(mat, gray, COLOR_BGR2GRAY)
    val attempts = listOf(
        Triple(1.2, 4, 60), Triple(1.1, 3, 60), Triple(1.05, 3, 40), Triple(1.05, 2, 30)
    )
    for (p in attempts) {
        val faces = RectVector()
        cascade.detectMultiScale(gray, faces, p.first, p.second, 0, Size(p.third, p.third), Size(0, 0))
        if (faces.size() > 0L) {
            var best: Rect? = null; var bestArea = 0
            for (i in 0 until faces.size()) {
                val r = faces.get(i)
                val area = r.width() * r.height()
                if (area > bestArea) { best = r; bestArea = area }
            }
            return best
        }
    }
    return null
}

private fun embedFaceFromMatS3F(
    fullColor: Mat, rect: Rect,
    matConverter: OpenCVFrameConverter.ToMat,
    predictor: ai.djl.inference.Predictor<ai.djl.modality.cv.Image, FloatArray>
): FloatArray {
    val safe = Rect(
        rect.x().coerceAtLeast(0),
        rect.y().coerceAtLeast(0),
        rect.width().coerceAtMost(fullColor.cols() - rect.x().coerceAtLeast(0)),
        rect.height().coerceAtMost(fullColor.rows() - rect.y().coerceAtLeast(0))
    )
    val crop = Mat(fullColor, safe)
    val resized = Mat()
    resize(crop, resized, Size(112, 112))
    val frame = matConverter.convert(resized)
    val java2D = Java2DFrameConverter()
    val bi = java2D.convert(frame)
    val djlImg = ImageFactory.getInstance().fromImage(bi)
    return predictor.predict(djlImg)
}

private fun extractCascadeS3F(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_s3f", ".xml").apply { deleteOnExit() }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
