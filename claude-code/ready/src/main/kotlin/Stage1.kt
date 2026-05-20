import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.opencv.global.opencv_imgcodecs.imencode
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.FONT_HERSHEY_SIMPLEX
import org.bytedeco.opencv.global.opencv_imgproc.LINE_8
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.global.opencv_imgproc.putText
import org.bytedeco.opencv.global.opencv_imgproc.rectangle
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
private const val PREVIEW_PORT_DEFAULT = 8080

fun main(args: Array<String>) = runBlocking {
    val camIndex = args.getOrNull(0)?.toIntOrNull()
        ?: System.getenv("CAM")?.toIntOrNull()
        ?: 1 // 0 is Insta360 virtual on Baruch's Mac; MacBook camera is 1
    val previewPort = System.getenv("PREVIEW_PORT")?.toIntOrNull() ?: PREVIEW_PORT_DEFAULT
    val previewEnabled = System.getenv("PREVIEW")?.lowercase() !in setOf("0", "false", "off", "no")
    val maxSeconds = System.getenv("MAX_SECONDS")?.toIntOrNull()

    log("Stage 1 — face detection → Shelly bulb")
    log("  camera=$camIndex  preview=${if (previewEnabled) "http://localhost:$previewPort/" else "off"}  max_seconds=${maxSeconds ?: "∞"}")

    val cascadePath = extractCascade()
    val cascade = CascadeClassifier(cascadePath)
    check(!cascade.isNull) { "Failed to load Haar cascade" }

    val grabber = OpenCVFrameGrabber(camIndex).apply {
        imageWidth = 1280
        imageHeight = 720
    }
    runCatching { grabber.start() }.onFailure {
        System.err.println("Failed to open camera $camIndex: ${it.message}")
        System.err.println("Tip: grant Camera permission to the terminal in System Settings > Privacy & Security > Camera.")
        exitProcess(1)
    }
    log("camera opened ${grabber.imageWidth}x${grabber.imageHeight}")

    val preview = if (previewEnabled) Preview(previewPort, "Stage 1 — face → bulb").also { it.start() } else null
    val matConverter = OpenCVFrameConverter.ToMat()
    val client = HttpClient(CIO)

    val green = Scalar(0.0, 255.0, 0.0, 0.0)
    val red = Scalar(0.0, 0.0, 255.0, 0.0)
    val white = Scalar(255.0, 255.0, 255.0, 0.0)

    var current: Boolean? = null
    var frames = 0
    var faceFrames = 0
    var flips = 0
    val t0 = System.currentTimeMillis()
    var lastHeartbeat = t0

    Runtime.getRuntime().addShutdownHook(Thread {
        runBlocking { runCatching { client.get("http://$BULB/color/0?turn=off") } }
    })

    try {
        while (true) {
            val frame = grabber.grab() ?: continue
            frames++

            val color = matConverter.convert(frame) ?: continue
            val gray = Mat()
            cvtColor(color, gray, COLOR_BGR2GRAY)

            val faces = RectVector()
            cascade.detectMultiScale(gray, faces, 1.2, 4, 0, Size(60, 60), Size(0, 0))
            val has = faces.size() > 0
            if (has) faceFrames++

            for (i in 0 until faces.size()) {
                val r: Rect = faces.get(i)
                rectangle(
                    color,
                    Point(r.x(), r.y()),
                    Point(r.x() + r.width(), r.y() + r.height()),
                    green, 2, LINE_8, 0
                )
            }
            val elapsedNow = (System.currentTimeMillis() - t0) / 1000.0
            val fpsNow = if (elapsedNow > 0) frames / elapsedNow else 0.0
            val status = "bulb=${if (current == true) "ON " else "OFF"}  faces=${faces.size()}  fps=${"%.1f".format(fpsNow)}  flips=$flips"
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.7, white, 2, LINE_8, false)
            putText(color, status, Point(12, 30), FONT_HERSHEY_SIMPLEX, 0.7, if (current == true) green else red, 1, LINE_8, false)

            if (preview != null) {
                val buf = BytePointer()
                if (imencode(".jpg", color, buf)) {
                    val bytes = ByteArray(buf.limit().toInt())
                    buf.get(bytes, 0, bytes.size)
                    preview.update(bytes)
                }
                buf.deallocate()
            }

            if (has != current) {
                val q = if (has) "turn=on&red=255&green=255&blue=255&gain=100" else "turn=off"
                runCatching { client.get("http://$BULB/color/0?$q") }
                current = has
                flips++
                log("flip → ${if (has) "ON " else "OFF"} @ ${"%.1f".format(elapsedNow)}s  (faces=${faces.size()})")
            }

            val now = System.currentTimeMillis()
            if (now - lastHeartbeat > 2000) {
                val faceRate = if (frames > 0) faceFrames * 100 / frames else 0
                log("  ${"%5.1f".format(elapsedNow)}s  frames=$frames  fps=${"%.1f".format(fpsNow)}  face%=$faceRate  state=${current ?: "?"}  flips=$flips")
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
        log("done. frames=$frames flips=$flips faceFrames=$faceFrames")
    }
}

private fun log(msg: String) {
    val t = System.currentTimeMillis() % 100000
    println("[${"%5d".format(t)}] $msg")
}

private fun extractCascade(): String {
    val resource = object {}.javaClass.classLoader
        .getResourceAsStream("haarcascade_frontalface_default.xml")
        ?: error("haarcascade_frontalface_default.xml not on classpath")
    val tmp = File.createTempFile("haarcascade_frontalface_default", ".xml").apply {
        deleteOnExit()
    }
    resource.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    return tmp.absolutePath
}
