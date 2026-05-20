import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

class Preview(private val port: Int, private val title: String) {
    private val latest = AtomicReference<ByteArray?>(null)
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(CIO, port = port) {
            routing {
                get("/") {
                    call.respondText(indexHtml(), ContentType.Text.Html)
                }
                get("/stream") {
                    val boundary = "frame"
                    call.respondBytesWriter(
                        contentType = ContentType.parse("multipart/x-mixed-replace; boundary=$boundary")
                    ) {
                        try {
                            while (true) {
                                val jpeg = latest.get()
                                if (jpeg == null) {
                                    delay(20)
                                    continue
                                }
                                val header = "--$boundary\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: ${jpeg.size}\r\n\r\n"
                                writeFully(header.toByteArray(Charsets.US_ASCII))
                                writeFully(jpeg)
                                writeFully("\r\n".toByteArray(Charsets.US_ASCII))
                                flush()
                                delay(40) // ~25 fps cap
                            }
                        } catch (_: Throwable) {
                            // client disconnected
                        }
                    }
                }
            }
        }.also { it.start(wait = false) }
        println("[preview] http://localhost:$port/  ($title)")
    }

    fun update(jpegBytes: ByteArray) {
        latest.set(jpegBytes)
    }

    fun stop() {
        server?.stop(500, 1000)
    }

    private fun indexHtml() = """
        <!doctype html>
        <html><head>
          <meta charset="utf-8"><title>$title</title>
          <style>
            body { margin:0; background:#0b0f17; color:#cdd6f4; font-family:ui-monospace,Menlo,monospace; }
            header { padding:8px 12px; font-size:13px; color:#94e2d5; }
            img { display:block; width:100vw; height:calc(100vh - 32px); object-fit:contain; background:#000; }
          </style>
        </head><body>
          <header>$title — MJPEG @ /stream</header>
          <img src="/stream" alt="live"/>
        </body></html>
    """.trimIndent()
}
