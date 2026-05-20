/**
 * Stage 3 — Vibecoding confidence semaphore on Govee H6056.
 * "Vibecoding" = the bare prompt without any plugins. This is what the agent emits
 * when it has no encoded context about the actual hardware or empirical calibration.
 *
 * FOUR designed misses:
 *   #1 phantom segments: TOTAL=15, but only 0..11 physically light
 *   #2 bar split: naive thirds (0-4, 5-9, 10-14) — MID spans Yankee+Golf
 *   #3 confidence: textbook  conf = 1 - d/TOL  — strong matches compress to mid
 *   #4 top-down: indices 0-4 are labeled "bottom" but physically TOP of Yankee
 *
 * Reuses Stage 2's face recognition pipeline (Haar + DJL face_feature + cosine distance).
 */
import ai.djl.modality.cv.ImageFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
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

private const val TOL = 0.6f
private const val TOTAL = 15 // MISS #1
private val BOTTOM = (0..4).toList()   // MISS #2 + #4
private val MID = (5..9).toList()      // MISS #2
private val TOP = (10..14).toList()    // MISS #1 (phantom) + #4

private const val GOVEE = "https://openapi.api.govee.com"

// RGB triples
private val RED = Triple(200, 0, 0)
private val YELLOW = Triple(255, 200, 0)
private val GREEN = Triple(0, 200, 0)
private val OFF_RGB = Triple(0, 0, 0)

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
    val facesDir = findFacesDirS3() ?: error("faces/ not found")

    log("Stage 3 VIBECODING — confidence semaphore (4 bugs baked in)")
    log("  camera=$camIndex  faces=$facesDir  preview=${if (previewEnabled) "http://localhost:$previewPort/" else "off"}")
    log("  Govee: sku=$sku device=${device.take(18)}…")

    val cascadePath = extractCascadeS3()
    val cascade = CascadeClassifier(cascadePath)
    check(!cascade.isNull) { "Failed to load Haar cascade" }

    log("loading DJL face_feature model…")
    val model = loadFaceFeatureModel()
    val predictor = model.newPredictor()
    log("model loaded")

    log("enrolling from $facesDir…")
    val enrolled = enrollAllS3(facesDir, predictor, cascade)
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

    val preview = if (previewEnabled) Preview(previewPort, "Stage 3 VIBECODING — semaphore").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)

    val white = Scalar(255.0, 255.0, 255.0, 0.0)
    val ovBg = Scalar(0.0, 0.0, 0.0, 0.0)

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking {
            // Clear all (still buggy — sends to phantom segments too)
            runCatching { goveeSet(client, key, sku, device, (0 until TOTAL).toList(), OFF_RGB) }
        }
    })

    var lastLevel = -1
    var frames = 0
    var lastIdentities: List<Triple<Rect, String, Float>> = emptyList()
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0
    var lastApiCodes = "[—,—,—]"

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
                    val emb = embedFaceFromMatS3(color, r, matConverter, predictor)
                    val (who, dist) = enrolled
                        .map { (name, ref) -> name to cosineDistance(emb, ref) }
                        .minBy { it.second }
                    ids.add(Triple(r, who, dist))
                }
                lastIdentities = ids
            }

            // Best confidence across detected faces — MISS #3: textbook formula
            var dMin: Float? = null
            var conf = 0f
            for ((_, _, dist) in lastIdentities) {
                if (dMin == null || dist < dMin) dMin = dist
                conf = max(conf, max(0f, 1f - dist / TOL))  // MISS #3
            }
            val level = when {
                conf < 0.33f -> 0
                conf < 0.67f -> 1
                else -> 2
            }

            // Draw face boxes
            for ((r, name, dist) in lastIdentities) {
                val tag = "$name ${"%.2f".format(dist)}"
                rectangle(color, Point(r.x(), r.y()), Point(r.x() + r.width(), r.y() + r.height()), white, 2, LINE_8, 0)
                putText(color, tag, Point(r.x(), r.y() - 8), FONT_HERSHEY_SIMPLEX, 0.6, white, 1, LINE_8, false)
            }

            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val confStr = "d=${dMin?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  level=$level"
            val status = "STAGE 3 VIBECODING  $confStr  fps=${"%.1f".format(fpsNow)}  api=$lastApiCodes"
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

            if (level != lastLevel) {
                // 3 API calls per state change — MISS #2 + #4: BOTTOM/MID/TOP layout broken
                val c1 = goveeSet(client, key, sku, device, BOTTOM, RED)
                val c2 = goveeSet(client, key, sku, device, MID, if (level >= 1) YELLOW else OFF_RGB)
                val c3 = goveeSet(client, key, sku, device, TOP, if (level >= 2) GREEN else OFF_RGB)
                lastApiCodes = "[$c1,$c2,$c3]"
                log("  @ ${"%5.1f".format(elapsedNow)}s  d=${dMin?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  level=$level  api=$lastApiCodes")
                lastLevel = level
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 2500) {
                log("  ${"%5.1f".format(elapsedNow)}s  frames=$frames  fps=${"%.1f".format(fpsNow)}  d=${dMin?.let { "%.2f".format(it) } ?: "—"}  conf=${"%.2f".format(conf)}  level=$level")
                lastHeartbeat = now
            }

            if (maxSeconds != null && (now - t0) / 1000 >= maxSeconds) {
                log("MAX_SECONDS reached, exiting")
                break
            }
        }
    } finally {
        runCatching { grabber.stop() }
        runCatching { goveeSet(client, key, sku, device, (0 until TOTAL).toList(), OFF_RGB) }
        client.close()
        preview?.stop()
        predictor.close()
        model.close()
        log("done. frames=$frames")
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

// --- Reused helpers (mirror of Stage2 — kept local for vibecoding narrative fidelity) ---

private fun findFacesDirS3(): File? {
    val candidates = listOfNotNull(
        env("FACES_DIR")?.let { File(it) },
        File("faces"),
        File("../faces"),
        File("../../faces"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/faces")
    )
    return candidates.firstOrNull { it.isDirectory }
}

private fun enrollAllS3(
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
            val face = detectFaceFlexibleS3(mat, cascade) ?: continue
            embeddings.add(embedFaceFromMatS3(mat, face, matConverter, predictor))
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

private fun detectFaceFlexibleS3(mat: Mat, cascade: CascadeClassifier): Rect? {
    val gray = Mat()
    cvtColor(mat, gray, COLOR_BGR2GRAY)
    val attempts = listOf(
        Triple(1.2, 4, 60),
        Triple(1.1, 3, 60),
        Triple(1.05, 3, 40),
        Triple(1.05, 2, 30)
    )
    for (p in attempts) {
        val faces = RectVector()
        cascade.detectMultiScale(gray, faces, p.first, p.second, 0, Size(p.third, p.third), Size(0, 0))
        if (faces.size() > 0L) {
            var best: Rect? = null
            var bestArea = 0
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

private fun embedFaceFromMatS3(
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

private fun extractCascadeS3(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_s3", ".xml").apply { deleteOnExit() }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
