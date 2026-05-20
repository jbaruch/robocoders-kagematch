/**
 * Stage 4 LIVE — full pipeline with all 5 plugin gains applied.
 *
 *   Yankee bar (0-5):  confidence semaphore (Stage 3 Fixed)
 *   Golf bar   (6-11): emotion color (this stage's new beat)
 *   Identity bulb:     Shelly (Stage 2)
 *
 *   Plugins in effect: govee-h6056, face-recognition-calibration-djl,
 *                      iot-actuator-patterns-kotlin, vision-pipeline-foundations-kotlin,
 *                      face-recognition-persistence
 *
 * Emotion classifier: emotion-ferplus-8.onnx (FER+, 8 classes, 64x64 grayscale).
 * Runs every 30th frame (~1 Hz) — emotions change slowly (frame-skip-policy).
 */
import ai.djl.modality.cv.ImageFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
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
import kotlin.system.exitProcess

// --- Physical layout (govee-h6056 plugin) ---
private val YANKEE_BOTTOM = listOf(5, 4)
private val YANKEE_MID = listOf(3, 2)
private val YANKEE_TOP = listOf(1, 0)
private val GOLF = (6..11).toList()
private val ALL_PHYSICAL = (0..11).toList()

private const val GOVEE = "https://openapi.api.govee.com"
private const val BULB = "192.168.8.135"

private val RED = Triple(200, 0, 0)
private val YELLOW = Triple(255, 200, 0)
private val GREEN = Triple(0, 200, 0)
private val OFF_RGB = Triple(1, 1, 1)

// Emotion → RGB palette (warm = positive, cool = negative, gray = neutral)
private val EMOTION_COLORS = mapOf(
    "happy" to Triple(255, 220, 30),
    "sad" to Triple(30, 80, 200),
    "angry" to Triple(255, 20, 20),
    "fear" to Triple(140, 40, 180),
    "disgust" to Triple(80, 160, 40),
    "surprise" to Triple(0, 255, 255),
    "neutral" to Triple(140, 140, 140),
    "contempt" to Triple(140, 140, 140)
)

// Identity → bulb RGB (BGR-ordered in OpenCV, but Shelly takes RGB literal)
private val IDENTITY_BULB = mapOf(
    "baruch" to Triple(0, 0, 255),   // blue
    "viktor" to Triple(255, 0, 0)    // red
)

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
    val facesDir = findFacesDirS4() ?: error("faces/ not found")

    log("Stage 4 LIVE — Yankee semaphore + Golf emotion + identity bulb")
    log("  camera=$camIndex  faces=$facesDir  preview=${if (previewEnabled) "http://localhost:$previewPort/" else "off"}")

    val cascadePath = extractCascadeS4()
    val cascade = CascadeClassifier(cascadePath)
    check(!cascade.isNull) { "Failed to load Haar cascade" }

    log("loading DJL face_feature model…")
    val faceModel = loadFaceFeatureModel()
    val facePredictor = faceModel.newPredictor()

    log("loading emotion-ferplus-8.onnx…")
    val emotionModel = loadEmotionModel()
    val emotionPredictor = emotionModel.newPredictor()

    log("enrolling from $facesDir…")
    val enrolled = enrollAllS4(facesDir, facePredictor, cascade)
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

    val preview = if (previewEnabled) Preview(previewPort, "Stage 4 LIVE").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)

    val yankee = YankeeController(client, key, sku, device).also { it.start(this) }
    val golf = GolfController(client, key, sku, device).also { it.start(this) }
    val persistence = FacePersistenceS4(maxAgeMs = 800)

    val white = Scalar(255.0, 255.0, 255.0, 0.0)
    val ovBg = Scalar(0.0, 0.0, 0.0, 0.0)

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            runCatching { client.get("http://$BULB/color/0?turn=off") }
            runCatching { goveeSet(client, key, sku, device, ALL_PHYSICAL, OFF_RGB) }
        }
    })

    var frames = 0
    var lastIdentities: List<Triple<Rect, String, Float>> = emptyList()
    var lastEmotion: String = "neutral"
    var currentBulbName: String? = null
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0

    try {
        while (true) {
            val frame = grabber.grab() ?: continue
            frames++

            val color = matConverter.convert(frame) ?: continue
            val doDetect = frames % 3 == 0
            val doEmotion = frames % 30 == 0

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
                    val emb = embedFaceFromMatS4(color, r, matConverter, facePredictor)
                    val (who, dist) = enrolled
                        .map { (name, ref) -> name to cosineDistance(emb, ref) }
                        .minBy { it.second }
                    ids.add(Triple(r, who, dist))
                }
                lastIdentities = ids
            }

            // Emotion every 30th frame, on the first detected face
            if (doEmotion && lastIdentities.isNotEmpty()) {
                val (r, _, _) = lastIdentities.first()
                runCatching {
                    val emo = classifyEmotion(color, r, matConverter, emotionPredictor)
                    if (emo != null) lastEmotion = emo
                }
            }

            // Confidence + persistence
            val dRaw: Float? = lastIdentities.minByOrNull { it.third }?.third
            val dHeld = persistence.observe(dRaw, System.currentTimeMillis())
            val conf = if (dHeld != null) confidenceOf(dHeld) else 0f
            val level = levelOf(conf)

            yankee.submit(level)
            // Golf: emotion only when we actually believe a person is in frame
            golf.submit(if (conf > 0f) lastEmotion else null)

            // Identity → bulb (edge-trigger on identity change, like Stage 2)
            val labelSet = lastIdentities.map { it.second }.toSet()
            val identityForBulb: String? = when {
                lastIdentities.isEmpty() || dHeld == null -> null
                "baruch" in labelSet && "viktor" in labelSet -> "both"
                "baruch" in labelSet -> "baruch"
                "viktor" in labelSet -> "viktor"
                else -> "unknown"
            }
            if (identityForBulb != currentBulbName) {
                val query = when (identityForBulb) {
                    null -> "turn=off"
                    "baruch" -> "turn=on&red=0&green=0&blue=255&gain=100"
                    "viktor" -> "turn=on&red=255&green=0&blue=0&gain=100"
                    "both" -> "turn=on&red=128&green=0&blue=128&gain=100"
                    else -> "turn=on&red=255&green=255&blue=255&gain=100"
                }
                launch(Dispatchers.IO) {
                    runCatching { client.get("http://$BULB/color/0?$query") }
                }
                currentBulbName = identityForBulb
            }

            // Draw boxes
            for ((r, name, dist) in lastIdentities) {
                val tag = "$name ${"%.2f".format(dist)}  $lastEmotion"
                rectangle(color, Point(r.x(), r.y()), Point(r.x() + r.width(), r.y() + r.height()), white, 2, LINE_8, 0)
                putText(color, tag, Point(r.x(), r.y() - 8), FONT_HERSHEY_SIMPLEX, 0.6, white, 1, LINE_8, false)
            }

            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val status = "STAGE 4 LIVE  d=${dHeld?.let { "%.2f".format(it) } ?: "—"}  level=$level (Y=${yankee.committed})  emo=$lastEmotion (G=${golf.committed})  fps=${"%.1f".format(fpsNow)}"
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.6, ovBg, 3, LINE_8, false)
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.6, white, 1, LINE_8, false)

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
                log("  ${"%5.1f".format(elapsedNow)}s  fps=${"%.1f".format(fpsNow)}  d=${dHeld?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  Y=$level→${yankee.committed}  emo=$lastEmotion→${golf.committed}  bulb=${currentBulbName ?: "off"}")
                lastHeartbeat = now
            }

            if (maxSeconds != null && (now - t0) / 1000 >= maxSeconds) {
                log("MAX_SECONDS reached, exiting")
                break
            }
        }
    } finally {
        runCatching { grabber.stop() }
        runCatching { client.get("http://$BULB/color/0?turn=off") }
        runCatching { goveeSet(client, key, sku, device, ALL_PHYSICAL, OFF_RGB) }
        client.close()
        preview?.stop()
        facePredictor.close(); faceModel.close()
        emotionPredictor.close(); emotionModel.close()
        log("done. frames=$frames")
    }
}

// --- Yankee semaphore controller (Stage 3 Fixed redux) ---
private class YankeeController(
    private val client: HttpClient, private val key: String,
    private val sku: String, private val device: String
) {
    @Volatile var target: Int = -1
    @Volatile var committed: Int = -1
    private var stable = 0
    private var lastApply = 0L

    fun submit(t: Int) { target = t }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(400)
            val t = target
            if (t == committed) { stable = 0; continue }
            stable++
            if (stable < 2) continue
            val now = System.currentTimeMillis()
            if (now - lastApply < 1200) continue
            try {
                goveeSet(client, key, sku, device, YANKEE_BOTTOM, RED)
                goveeSet(client, key, sku, device, YANKEE_MID, if (t >= 1) YELLOW else OFF_RGB)
                goveeSet(client, key, sku, device, YANKEE_TOP, if (t >= 2) GREEN else OFF_RGB)
                committed = t; stable = 0; lastApply = now
            } catch (e: Exception) {
                println("[yankee] failed: ${e.message}")
            }
        }
    }
}

// --- Golf emotion controller ---
private class GolfController(
    private val client: HttpClient, private val key: String,
    private val sku: String, private val device: String
) {
    @Volatile var target: String? = null
    @Volatile var committed: String? = null
    private var stable = 0
    private var lastApply = 0L

    fun submit(t: String?) { target = t }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(400)
            val t = target
            if (t == committed) { stable = 0; continue }
            stable++
            if (stable < 2) continue
            val now = System.currentTimeMillis()
            if (now - lastApply < 1200) continue
            try {
                val rgb = if (t != null) EMOTION_COLORS[t] ?: OFF_RGB else OFF_RGB
                goveeSet(client, key, sku, device, GOLF, rgb)
                committed = t; stable = 0; lastApply = now
            } catch (e: Exception) {
                println("[golf] failed: ${e.message}")
            }
        }
    }
}

// --- face-recognition-persistence ---
private class FacePersistenceS4(private val maxAgeMs: Long) {
    private var lastVal: Float? = null
    private var lastTs: Long = 0L
    fun observe(now: Float?, ts: Long): Float? {
        if (now != null) { lastVal = now; lastTs = ts; return now }
        return if (ts - lastTs > maxAgeMs) { lastVal = null; null } else lastVal
    }
}

private fun classifyEmotion(
    fullColor: Mat, rect: Rect,
    matConverter: OpenCVFrameConverter.ToMat,
    predictor: ai.djl.inference.Predictor<ai.djl.modality.cv.Image, ai.djl.modality.Classifications>
): String? {
    val safe = Rect(
        rect.x().coerceAtLeast(0),
        rect.y().coerceAtLeast(0),
        rect.width().coerceAtMost(fullColor.cols() - rect.x().coerceAtLeast(0)),
        rect.height().coerceAtMost(fullColor.rows() - rect.y().coerceAtLeast(0))
    )
    val crop = Mat(fullColor, safe)
    val gray = Mat()
    cvtColor(crop, gray, COLOR_BGR2GRAY)
    val resized = Mat()
    resize(gray, resized, Size(64, 64))
    val frame = matConverter.convert(resized)
    val java2D = Java2DFrameConverter()
    val bi = java2D.convert(frame) ?: return null
    val djlImg = ImageFactory.getInstance().fromImage(bi)
    val cls = predictor.predict(djlImg)
    return cls.topK<ai.djl.modality.Classifications.Classification>(1).firstOrNull()?.className
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

// --- Reused helpers ---

private fun findFacesDirS4(): File? {
    val candidates = listOfNotNull(
        env("FACES_DIR")?.let { File(it) },
        File("faces"), File("../faces"), File("../../faces"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/faces")
    )
    return candidates.firstOrNull { it.isDirectory }
}

private fun enrollAllS4(
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
            val face = detectFaceFlexibleS4(mat, cascade) ?: continue
            embeddings.add(embedFaceFromMatS4(mat, face, matConverter, predictor))
        }
        if (embeddings.isEmpty()) continue
        val avg = FloatArray(embeddings[0].size)
        for (e in embeddings) for (i in e.indices) avg[i] += e[i]
        for (i in avg.indices) avg[i] /= embeddings.size.toFloat()
        var sum = 0.0; for (v in avg) sum += v * v
        val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-8f)
        for (i in avg.indices) avg[i] /= norm
        result[personDir.name] = avg
    }
    return result
}

private fun detectFaceFlexibleS4(mat: Mat, cascade: CascadeClassifier): Rect? {
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

private fun embedFaceFromMatS4(
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

private fun extractCascadeS4(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_s4", ".xml").apply { deleteOnExit() }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
