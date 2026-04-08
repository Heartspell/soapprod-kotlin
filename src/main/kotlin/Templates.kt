import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class TemplateRenderer(private val root: Path) {
    private val cache = mutableMapOf<String, String>()

    fun render(name: String, values: Map<String, String>): String {
        var result = load(name)
        for ((key, value) in values) {
            result = result.replace("{{${key}}}", value)
        }
        return result
    }

    private fun load(name: String): String {
        return cache.getOrPut(name) {
            val path = root.resolve(name)
            Files.readString(path, StandardCharsets.UTF_8)
        }
    }
}
