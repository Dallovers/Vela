package com.dallovers.phoneagent

/** One small, locally verified task for the experimental secondary screen. */
internal class SecondaryCalculation private constructor(val expression: String, val result: Int) {
    fun matchesReadback(labels: List<String>): Boolean {
        val normalized = labels.map { text ->
            text.filterNot(Char::isWhitespace).replace('＋', '+').replace('＝', '=')
        }
        val printed = result.toString()
        val numeric = setOf(printed, if (printed.length > 3) printed.dropLast(3) + "," + printed.takeLast(3) else printed)
        return normalized.any { it == expression } && normalized.any { it.removePrefix("=") in numeric } ||
            normalized.any { it.startsWith("$expression=") && it.substringAfter('=') in numeric }
    }

    companion object {
        private val addition = Regex("([1-9][0-9]{0,2})\\s*[+＋]\\s*([1-9][0-9]{0,2})")

        fun parse(text: String): SecondaryCalculation? {
            val values = addition.matchEntire(text.trim())?.groupValues ?: return null
            val (left, right) = values.drop(1).map(String::toInt)
            return SecondaryCalculation("$left+$right", left + right)
        }
    }
}
