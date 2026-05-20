import ai.djl.modality.Classifications
import ai.djl.modality.cv.Image
import ai.djl.modality.cv.ImageFactory
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.DataType
import ai.djl.ndarray.types.Shape
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.Batchifier
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext
import java.io.File
import java.net.URI

/** FER+ emotion labels in model output order. */
val FER_LABELS = listOf(
    "neutral", "happy", "surprise", "sad",
    "angry", "disgust", "fear", "contempt"
)

/** ONNX FER+ translator: 64×64 grayscale, NCHW (1,1,64,64). */
private class FerPlusTranslator : Translator<Image, Classifications> {
    override fun getBatchifier(): Batchifier? = null // we add the batch dim manually

    override fun processInput(ctx: TranslatorContext, input: Image): NDList {
        val manager = ctx.ndManager
        // Image is already 64x64 grayscale (resized + converted by caller)
        var array = input.toNDArray(manager, Image.Flag.GRAYSCALE) // (H,W,1)
        array = array.toType(DataType.FLOAT32, false)
        // FER+ expects (1, 1, 64, 64): batch, channel, H, W
        array = array.transpose(2, 0, 1).expandDims(0)
        return NDList(array)
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): Classifications {
        val logits = list.singletonOrThrow().squeeze().softmax(0)
        val probs = logits.toFloatArray().toList().map { it.toDouble() }
        return Classifications(FER_LABELS, probs)
    }
}

fun loadEmotionModel(): ZooModel<Image, Classifications> {
    val resourceUrl = object {}.javaClass.classLoader.getResource("emotion-ferplus-8.onnx")
        ?: error("emotion-ferplus-8.onnx not on classpath")
    // DJL needs a directory/file URI, not a jar:file:! URL; extract if needed.
    val modelFile = if (resourceUrl.protocol == "file") {
        File(resourceUrl.toURI())
    } else {
        // running from jar — extract to temp
        val tmp = File.createTempFile("emotion-ferplus-8", ".onnx").apply { deleteOnExit() }
        resourceUrl.openStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        tmp
    }
    val criteria = Criteria.builder()
        .setTypes(Image::class.java, Classifications::class.java)
        .optModelPath(modelFile.toPath())
        .optModelName(modelFile.nameWithoutExtension)
        .optTranslator(FerPlusTranslator())
        .optEngine("OnnxRuntime")
        .build()
    return criteria.loadModel()
}
