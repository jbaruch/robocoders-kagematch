import java.io.File

/** Load KEY=VALUE pairs from a .env file into JVM system properties.
 *  Existing env vars take precedence. */
fun loadDotEnv() {
    val candidates = listOf(
        File(".env"),
        File("../../.env"),
        File("/Users/jbaruch/Projects/robocoders-kagematch/.env")
    )
    val file = candidates.firstOrNull { it.isFile } ?: return
    file.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val eq = trimmed.indexOf('=')
        if (eq <= 0) return@forEach
        val k = trimmed.substring(0, eq).trim()
        val v = trimmed.substring(eq + 1).trim().removeSurrounding("\"").removeSurrounding("'")
        if (System.getenv(k) == null && System.getProperty(k) == null) {
            System.setProperty(k, v)
        }
    }
    println("[env] loaded ${file.absolutePath}")
}

/** Resolve from env, then system properties. */
fun env(key: String): String? = System.getenv(key) ?: System.getProperty(key)
fun envOrFail(key: String): String = env(key) ?: error("Missing required env var: $key")
