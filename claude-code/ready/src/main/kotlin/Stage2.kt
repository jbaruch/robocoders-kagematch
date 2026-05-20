import ai.djl.modality.cv.ImageFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
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
import kotlin.system.exitProcess

private const val BULB = "192.168.8.135"
private const val UNKNOWN_THRESHOLD = 0.6f // cosine distance; >this means "not enrolled"
private const val DETECT_EVERY = 3 // run face detection + recognition every Nth frame (~10 Hz at 30 fps capture)

// Bulb RGB mapping (edge-triggered on state change)
private data class BulbState(val name: String, val r: Int, val g: Int, val b: Int, val on: Boolean = true) {
    companion object {
        val OFF = BulbState("off", 0, 0, 0, on = false)
        val BLUE = BulbState("blue", 0, 0, 255)
        val RED = BulbState("red", 255, 0, 0)
        val PURPLE = BulbState("purple", 255, 0, 255)
        val WHITE = BulbState("white", 255, 255, 255)
    }
}

fun main(args: Array<String>) = runBlocking {
    val camIndex = args.getOrNull(0)?.toIntOrNull()
        ?: System.getenv("CAM")?.toIntOrNull()
        ?: 1
    val previewPort = System.getenv("PREVIEW_PORT")?.toIntOrNull() ?: 8080
    val previewEnabled = System.getenv("PREVIEW")?.lowercase() !in setOf("0", "false", "off", "no")
    val maxSeconds = System.getenv("MAX_SECONDS")?.toIntOrNull()
    val facesDir = findFacesDir() ?: error("faces/ not found. Set FACES_DIR or place faces/ at project root.")

    log("Stage 2 — face recognition → identity color")
    log("  camera=$camIndex  faces=$facesDir  preview=${if (previewEnabled) "http://localhost:$previewPort/" else "off"}")

    val cascadePath = extractCascadeStage2()
    val cascade = CascadeClassifier(cascadePath)
    check(!cascade.isNull) { "Failed to load Haar cascade" }

    log("loading DJL face_feature model (first run downloads ~100MB)…")
    val model = loadFaceFeatureModel()
    val predictor = model.newPredictor()
    log("model loaded")

    log("enrolling from $facesDir…")
    val enrolled = enrollAll(facesDir, predictor, cascade)
    enrolled.forEach { (name, _) -> log("  enrolled: $name") }
    check(enrolled.isNotEmpty()) { "No faces enrolled. Check $facesDir." }

    val grabber = OpenCVFrameGrabber(camIndex).apply {
        imageWidth = 1280
        imageHeight = 720
    }
    runCatching { grabber.start() }.onFailure {
        System.err.println("Failed to open camera $camIndex: ${it.message}")
        exitProcess(1)
    }
    log("camera opened ${grabber.imageWidth}x${grabber.imageHeight}")

    val preview = if (previewEnabled) Preview(previewPort, "Stage 2 — identity → bulb").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)

    val white = Scalar(255.0, 255.0, 255.0, 0.0)
    val palette = mapOf(
        "baruch" to Scalar(255.0, 100.0, 0.0, 0.0),   // BGR: blue-ish
        "viktor" to Scalar(0.0, 0.0, 255.0, 0.0),     // BGR: red
        "unknown" to white
    )

    var currentBulb: BulbState? = null
    var frames = 0
    var faceFrames = 0
    var flips = 0
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0
    var lastIdentities: List<Pair<Rect, Pair<String, Float>>> = emptyList()
    var lastN = 0

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { runCatching { client.get("http://$BULB/color/0?turn=off") } }
    })

    try {
        while (true) {
            val frame = grabber.grab() ?: continue
            frames++

            val color = matConverter.convert(frame) ?: continue

            // Only run detection + recognition every Nth frame (~10 Hz; faster than perception, plus natural flicker suppression)
            val doDetect = frames % DETECT_EVERY == 0
            val identities: List<Pair<Rect, Pair<String, Float>>>
            val n: Int

            if (doDetect) {
                // Downscale 4x before detection — suppresses Haar false-positives AND runs 4x faster (vision-pipeline-foundations-kotlin)
                val small = Mat()
                resize(color, small, Size(color.cols() / 4, color.rows() / 4))
                val gray = Mat()
                cvtColor(small, gray, COLOR_BGR2GRAY)

                val raw = RectVector()
                cascade.detectMultiScale(gray, raw, 1.2, 5, 0, Size(20, 20), Size(0, 0))
                val ids = mutableListOf<Pair<Rect, Pair<String, Float>>>()
                for (i in 0 until raw.size()) {
                    val s = raw.get(i)
                    // Scale Haar box back to full-res coordinates
                    val r = Rect(s.x() * 4, s.y() * 4, s.width() * 4, s.height() * 4)
                    val emb = embedFaceFromMat(color, r, matConverter, predictor)
                    val (who, dist) = enrolled
                        .map { (name, ref) -> name to cosineDistance(emb, ref) }
                        .minBy { it.second }
                    val label = if (dist > UNKNOWN_THRESHOLD) "unknown" else who
                    ids.add(r to (label to dist))
                }
                n = ids.size
                if (n > 0) faceFrames++
                identities = ids
                lastIdentities = ids
                lastN = n
            } else {
                identities = lastIdentities
                n = lastN
            }

            // Draw boxes + labels
            for ((r, idDist) in identities) {
                val (label, dist) = idDist
                val boxColor = palette[label] ?: white
                rectangle(
                    color, Point(r.x(), r.y()),
                    Point(r.x() + r.width(), r.y() + r.height()),
                    boxColor, 2, LINE_8, 0
                )
                val tag = "$label ${"%.2f".format(dist)}"
                putText(color, tag, Point(r.x(), r.y() - 8), FONT_HERSHEY_SIMPLEX, 0.7, boxColor, 2, LINE_8, false)
            }

            // Decide bulb state from identity set
            val labelSet = identities.map { it.second.first }.toSet()
            val target = when {
                identities.isEmpty() -> BulbState.OFF
                "baruch" in labelSet && "viktor" in labelSet -> BulbState.PURPLE
                "baruch" in labelSet -> BulbState.BLUE
                "viktor" in labelSet -> BulbState.RED
                else -> BulbState.WHITE
            }

            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val status = "bulb=${currentBulb?.name ?: "?"}  faces=$n  fps=${"%.1f".format(fpsNow)}  flips=$flips"
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.7, Scalar(0.0, 0.0, 0.0, 0.0), 3, LINE_8, false)
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.7, white, 1, LINE_8, false)

            if (preview != null) {
                val buf = BytePointer()
                if (imencode(".jpg", color, buf)) {
                    val bytes = ByteArray(buf.limit().toInt())
                    buf.get(bytes, 0, bytes.size)
                    preview.update(bytes)
                }
                buf.deallocate()
            }

            if (target != currentBulb) {
                runCatching { client.get("http://$BULB/color/0?${buildQuery(target)}") }
                currentBulb = target
                flips++
                val distStr = identities.joinToString(", ") { (_, l) -> "${l.first}=${"%.3f".format(l.second)}" }
                log("flip → ${target.name.padEnd(7)} @ ${"%.1f".format(elapsedNow)}s  (faces=$n  [$distStr])")
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 2000) {
                val faceRate = if (frames > 0) faceFrames * 100 / frames else 0
                log("  ${"%5.1f".format(elapsedNow)}s  frames=$frames  fps=${"%.1f".format(fpsNow)}  face%=$faceRate  bulb=${currentBulb?.name}  flips=$flips")
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
        client.close()
        preview?.stop()
        predictor.close()
        model.close()
        log("done. frames=$frames flips=$flips faceFrames=$faceFrames")
    }
}

private fun buildQuery(b: BulbState): String =
    if (!b.on) "turn=off"
    else "turn=on&red=${b.r}&green=${b.g}&blue=${b.b}&gain=100"

private fun findFacesDir(): File? {
    val candidates = listOfNotNull(
        System.getenv("FACES_DIR")?.let { File(it) },
        File("faces"),
        File("../faces"),
        File("../../faces"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/faces")
    )
    return candidates.firstOrNull { it.isDirectory }
}

private fun enrollAll(
    facesDir: File,
    predictor: ai.djl.inference.Predictor<ai.djl.modality.cv.Image, FloatArray>,
    cascade: CascadeClassifier
): Map<String, FloatArray> {
    val people = facesDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    val matConverter = OpenCVFrameConverter.ToMat()
    val result = mutableMapOf<String, FloatArray>()
    for (personDir in people) {
        val jpgs = personDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.toList().orEmpty()
        if (jpgs.isEmpty()) continue
        val embeddings = mutableListOf<FloatArray>()
        for (jpg in jpgs) {
            val mat = org.bytedeco.opencv.global.opencv_imgcodecs.imread(jpg.absolutePath)
            if (mat.empty()) {
                log("  skip ${jpg.name} (could not read)")
                continue
            }
            val (face, attemptIdx) = detectFaceFlexible(mat, cascade)
            if (face == null) {
                log("  skip ${jpg.name} (no face after relaxed retries)")
                continue
            }
            if (attemptIdx > 0) log("  ${jpg.name} detected on relaxed attempt #${attemptIdx + 1}")
            val emb = embedFaceFromMat(mat, face, matConverter, predictor)
            embeddings.add(emb)
        }
        if (embeddings.isEmpty()) {
            log("  ${personDir.name}: no usable photos")
            continue
        }
        val avg = FloatArray(embeddings[0].size)
        for (e in embeddings) for (i in e.indices) avg[i] += e[i]
        for (i in avg.indices) avg[i] /= embeddings.size.toFloat()
        // re-normalize
        var sum = 0.0
        for (v in avg) sum += v * v
        val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-8f)
        for (i in avg.indices) avg[i] /= norm
        result[personDir.name] = avg
        log("  ${personDir.name}: ${embeddings.size} photos averaged")
    }
    return result
}

private fun detectFaceFlexible(mat: Mat, cascade: CascadeClassifier): Pair<Rect?, Int> {
    val gray = Mat()
    cvtColor(mat, gray, COLOR_BGR2GRAY)
    val attempts = listOf(
        Triple(1.2, 4, 60),    // strict (matches live runtime)
        Triple(1.1, 3, 60),    // medium
        Triple(1.05, 3, 40),   // loose
        Triple(1.05, 2, 30)    // very loose
    )
    for ((idx, p) in attempts.withIndex()) {
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
            return best to idx
        }
    }
    return null to -1
}

private fun embedFaceFromMat(
    fullColor: Mat,
    rect: Rect,
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

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100000
    println("[${"%5d".format(t)}] $msg")
}

private fun extractCascadeStage2(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_frontalface_default_s2", ".xml").apply {
        deleteOnExit()
    }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
