package ai.dui.data.agent.opencode

/**
 * One rule for "this model costs nothing" across the custom-provider dialog, the chat picker and the settings list.
 *
 * OpenCode does not always report cost for models coming from a custom endpoint, so two signals are accepted:
 * a zero-cost entry from OpenCode itself, and the `:free` id OpenRouter uses for its no-cost models (`openrouter/free`
 * is its auto-router). The id may be either `model` or `provider/model`.
 */
object OpenCodeModelFilter {
    fun isFreeId(id: String): Boolean {
        val modelPart = id.substringAfter('/')
        return modelPart.equals("free", ignoreCase = true) || modelPart.endsWith(":free", ignoreCase = true)
    }

    fun isFree(id: String, freeByCost: Boolean = false): Boolean = freeByCost || isFreeId(id)
}
