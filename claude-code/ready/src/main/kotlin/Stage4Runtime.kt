/**
 * Stage 4 runtime — drives the bars given decisions from 3 sub-agents.
 * Shared by Stage4Vibecoding.kt and Stage4Fixed.kt.
 *
 * The decisions are applied DETERMINISTICALLY. Vibecoding-side broken decisions
 * produce broken bars; fixed-side correct decisions produce correct bars.
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

private const val BULB = "192.168.8.135"
private const val GOVEE = "https://openapi.api.govee.com"

private val RED = Triple(200, 0, 0)
private val YELLOW = Triple(255, 200, 0)
private val GREEN = Triple(0, 200, 0)

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

suspend fun runStage4(
    vision: VisionDecision,
    iot: IotDecision,
    eval: EvalDecision,
    title: String,
    camIndex: Int = 1,
    previewPort: Int = 8080,
    previewEnabled: Boolean = true,
    maxSeconds: Int? = null
) = coroutineScope {
    val goveeKey = envOrFail("GOVEE_API_KEY")
    val sku = envOrFail("GOVEE_H6056_SKU")
    val device = envOrFail("GOVEE_H6056_DEVICE")
    val facesDir = findFacesDirS4R() ?: error("faces/ not found")

    s4log("[$title] loading models + enrollment + camera…")
    val cascadePath = extractCascadeS4R()
    val cascade = CascadeClassifier(cascadePath)
    val faceModel = loadFaceFeatureModel()
    val facePredictor = faceModel.newPredictor()
    val emotionModel = loadEmotionModel()
    val emotionPredictor = emotionModel.newPredictor()
    val enrolled = enrollAllS4R(facesDir, facePredictor, cascade)
    enrolled.forEach { (name, _) -> s4log("  enrolled: $name") }

    val grabber = OpenCVFrameGrabber(camIndex).apply {
        imageWidth = 1280
        imageHeight = 720
    }
    runCatching { grabber.start() }.onFailure {
        System.err.println("Failed to open camera $camIndex: ${it.message}")
        exitProcess(1)
    }

    val preview = if (previewEnabled) Preview(previewPort, "Stage 4 — $title").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)

    // Translate sub-agent decisions into runtime functions
    val confidenceOf: (Float) -> Float = when (vision.method) {
        "piecewise" -> { d ->
            val s = vision.strongThreshold; val r = vision.rejectThreshold
            when {
                d <= s -> 1f
                d >= r -> 0f
                else -> (r - d) / (r - s)
            }
        }
        else -> { d -> (1f - d / vision.tolerance).coerceIn(0f, 1f) }
    }
    val zonesByZone = iot.zones.groupBy { it.zone }
    val bottomSegs = zonesByZone["BOTTOM"]?.flatMap { it.segments } ?: emptyList()
    val midSegs = zonesByZone["MID"]?.flatMap { it.segments } ?: emptyList()
    val topSegs = zonesByZone["TOP"]?.flatMap { it.segments } ?: emptyList()
    val emoSegs = zonesByZone["EMOTION"]?.flatMap { it.segments } ?: emptyList()
    val offRgb = if (iot.offRgb.size == 3) Triple(iot.offRgb[0], iot.offRgb[1], iot.offRgb[2]) else Triple(0, 0, 0)
    val stabilityTicks = if (eval.stabilityMethod == "n_tick") eval.nTicks.coerceAtLeast(1) else 1

    val govee = GoveeApplier(client, goveeKey, sku, device, bottomSegs, midSegs, topSegs, emoSegs, offRgb, stabilityTicks).also { it.start(this) }

    Runtime.getRuntime().addShutdownHook(Thread {
        kotlinx.coroutines.runBlocking {
            runCatching { client.get("http://$BULB/color/0?turn=off") }
            runCatching { goveeSetSegments(client, goveeKey, sku, device, (0..14).toList(), Triple(1, 1, 1)) }
        }
    })

    var frames = 0
    var lastIdentities: List<Triple<Rect, String, Float>> = emptyList()
    var lastEmotion = "neutral"
    var currentBulb: String? = null
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0
    val white = Scalar(255.0, 255.0, 255.0, 0.0)
    val ovBg = Scalar(0.0, 0.0, 0.0, 0.0)

    try {
        while (isActive) {
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
                    val emb = embedFaceFromMatS4R(color, r, matConverter, facePredictor)
                    val (who, dist) = enrolled
                        .map { (name, ref) -> name to cosineDistance(emb, ref) }
                        .minBy { it.second }
                    ids.add(Triple(r, who, dist))
                }
                lastIdentities = ids
            }

            if (doEmotion && lastIdentities.isNotEmpty()) {
                val (r, _, _) = lastIdentities.first()
                runCatching {
                    val emo = classifyEmotionS4R(color, r, matConverter, emotionPredictor)
                    if (emo != null) lastEmotion = emo
                }
            }

            // Apply sub-agent decisions
            val dMin = lastIdentities.minByOrNull { it.third }?.third
            val conf = if (dMin != null) confidenceOf(dMin) else 0f
            val level = when {
                conf < 0.33f -> 0
                conf < 0.67f -> 1
                else -> 2
            }
            govee.setLevel(level)
            govee.setEmotion(if (conf > 0f) lastEmotion else null)

            // Identity → bulb (single producer-side decision, edge-triggered)
            val labels = lastIdentities.map { it.second }.toSet()
            val bulb = when {
                lastIdentities.isEmpty() || dMin == null -> null
                "baruch" in labels && "viktor" in labels -> "both"
                "baruch" in labels -> "baruch"
                "viktor" in labels -> "viktor"
                else -> "unknown"
            }
            if (bulb != currentBulb) {
                val q = when (bulb) {
                    null -> "turn=off"
                    "baruch" -> "turn=on&red=0&green=0&blue=255&gain=100"
                    "viktor" -> "turn=on&red=255&green=0&blue=0&gain=100"
                    "both" -> "turn=on&red=128&green=0&blue=128&gain=100"
                    else -> "turn=on&red=255&green=255&blue=255&gain=100"
                }
                launch(Dispatchers.IO) { runCatching { client.get("http://$BULB/color/0?$q") } }
                currentBulb = bulb
            }

            // Draw face boxes
            for ((r, name, dist) in lastIdentities) {
                rectangle(color, Point(r.x(), r.y()), Point(r.x() + r.width(), r.y() + r.height()), white, 2, LINE_8, 0)
                putText(color, "$name ${"%.2f".format(dist)}  $lastEmotion", Point(r.x(), r.y() - 8), FONT_HERSHEY_SIMPLEX, 0.6, white, 1, LINE_8, false)
            }
            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val status = "$title  d=${dMin?.let { "%.2f".format(it) } ?: "—"}  level=$level(Y=${govee.committedLevel})  emo=$lastEmotion(G=${govee.committedEmotion})  fps=${"%.1f".format(fpsNow)}"
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
                s4log("[$title]  ${"%5.1f".format(elapsedNow)}s  fps=${"%.1f".format(fpsNow)}  d=${dMin?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  Y=$level→${govee.committedLevel}  emo=$lastEmotion→${govee.committedEmotion}  bulb=${currentBulb ?: "off"}")
                lastHeartbeat = now
            }
            if (maxSeconds != null && (now - t0) / 1000 >= maxSeconds) {
                s4log("[$title] MAX_SECONDS reached, exiting")
                break
            }
        }
    } finally {
        runCatching { grabber.stop() }
        runCatching { client.get("http://$BULB/color/0?turn=off") }
        runCatching { goveeSetSegments(client, goveeKey, sku, device, (0..14).toList(), Triple(1, 1, 1)) }
        client.close()
        preview?.stop()
        facePredictor.close(); faceModel.close()
        emotionPredictor.close(); emotionModel.close()
        s4log("[$title] done. frames=$frames")
    }
}

/** Applies the sub-agent IoT decision to the actual Govee bars. */
private class GoveeApplier(
    private val client: HttpClient,
    private val key: String, private val sku: String, private val device: String,
    private val bottom: List<Int>, private val mid: List<Int>, private val top: List<Int>, private val emotion: List<Int>,
    private val off: Triple<Int, Int, Int>,
    private val stabilityTicks: Int
) {
    @Volatile var pendingLevel: Int = -1
    @Volatile var pendingEmotion: String? = null
    @Volatile var committedLevel: Int = -1
    @Volatile var committedEmotion: String? = null
    private var stableLvl = 0
    private var stableEmo = 0
    private var lastApply = 0L

    fun setLevel(l: Int) { pendingLevel = l }
    fun setEmotion(e: String?) { pendingEmotion = e }

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            delay(400)
            val now = System.currentTimeMillis()
            if (now - lastApply < 1200) continue
            // Level stability
            if (pendingLevel != committedLevel) {
                stableLvl++
                if (stableLvl >= stabilityTicks) {
                    applyLevel(pendingLevel)
                    committedLevel = pendingLevel
                    lastApply = now
                    stableLvl = 0
                    continue
                }
            } else stableLvl = 0
            // Emotion stability
            if (pendingEmotion != committedEmotion) {
                stableEmo++
                if (stableEmo >= stabilityTicks) {
                    applyEmotion(pendingEmotion)
                    committedEmotion = pendingEmotion
                    lastApply = now
                    stableEmo = 0
                }
            } else stableEmo = 0
        }
    }

    private suspend fun applyLevel(level: Int) {
        goveeSetSegments(client, key, sku, device, bottom, RED)
        goveeSetSegments(client, key, sku, device, mid, if (level >= 1) YELLOW else off)
        goveeSetSegments(client, key, sku, device, top, if (level >= 2) GREEN else off)
    }

    private suspend fun applyEmotion(emotion: String?) {
        val rgb = if (emotion != null) EMOTION_COLORS[emotion] ?: off else off
        goveeSetSegments(client, key, sku, device, this.emotion, rgb)
    }
}

private suspend fun goveeSetSegments(
    client: HttpClient, key: String, sku: String, device: String,
    segments: List<Int>, rgb: Triple<Int, Int, Int>
): Int {
    if (segments.isEmpty()) return 0
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

internal fun s4log(msg: String) {
    val t = System.currentTimeMillis() % 100000
    println("[${"%5d".format(t)}] $msg")
}

// --- duplicated face-recognition helpers ---

private fun findFacesDirS4R(): File? {
    val candidates = listOfNotNull(
        env("FACES_DIR")?.let { File(it) },
        File("faces"), File("../faces"), File("../../faces"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/faces")
    )
    return candidates.firstOrNull { it.isDirectory }
}

private fun enrollAllS4R(
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
            val face = detectFaceFlexibleS4R(mat, cascade) ?: continue
            embeddings.add(embedFaceFromMatS4R(mat, face, matConverter, predictor))
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

private fun detectFaceFlexibleS4R(mat: Mat, cascade: CascadeClassifier): Rect? {
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

private fun embedFaceFromMatS4R(
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

private fun classifyEmotionS4R(
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

private fun extractCascadeS4R(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_s4r", ".xml").apply { deleteOnExit() }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
