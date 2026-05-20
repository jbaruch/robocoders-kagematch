import ai.djl.modality.cv.Image
import ai.djl.ndarray.NDList
import ai.djl.ndarray.types.DataType
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.Batchifier
import ai.djl.translate.Translator
import ai.djl.translate.TranslatorContext

/** Mirror of the canonical DJL face-recognition demo's FaceFeatureTranslator.
 *  Input: 112x112 RGB face crop. Output: 512-d L2-normalized embedding. */
class FaceFeatureTranslator : Translator<Image, FloatArray> {
    override fun getBatchifier(): Batchifier = Batchifier.STACK

    override fun processInput(ctx: TranslatorContext, input: Image): NDList {
        val manager = ctx.ndManager
        var array = input.toNDArray(manager, Image.Flag.COLOR)
        array = array.transpose(2, 0, 1).toType(DataType.FLOAT32, false)
        array = array.sub(127.5f).mul(0.0078125f) // (x - 127.5) / 128
        return NDList(array)
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): FloatArray {
        val out = list.singletonOrThrow()
        val raw = out.toFloatArray()
        // L2 normalize
        var sum = 0.0
        for (v in raw) sum += v * v
        val norm = kotlin.math.sqrt(sum).toFloat().coerceAtLeast(1e-8f)
        return FloatArray(raw.size) { raw[it] / norm }
    }
}

fun loadFaceFeatureModel(): ZooModel<Image, FloatArray> {
    val criteria = Criteria.builder()
        .setTypes(Image::class.java, FloatArray::class.java)
        .optModelUrls("https://resources.djl.ai/test-models/pytorch/face_feature.zip")
        .optModelName("face_feature")
        .optTranslator(FaceFeatureTranslator())
        .optEngine("PyTorch")
        .build()
    return criteria.loadModel()
}

/** Cosine distance ∈ [0, 2]. 0 = identical, ~1 = unrelated. */
fun cosineDistance(a: FloatArray, b: FloatArray): Float {
    require(a.size == b.size)
    var dot = 0f
    for (i in a.indices) dot += a[i] * b[i]
    return 1f - dot // both L2-normalized, so dot ∈ [-1,1] and 1-dot ∈ [0,2]
}
