package gg.sona.msl.bench

object IsaCost {
    private const val TRIP_COUNT = 8L
    private val LABEL = Regex("^(_L[0-9a-fA-F]+):$")
    private val BRANCH = Regex("^s_c?branch\\S*\\s+(_L[0-9a-fA-F]+)")

    fun dynamic(text: String, isInstruction: (String) -> Boolean): Long {
        val lines = text.lines().map { it.substringBefore(';').trim() }
        val labels = HashMap<String, Int>()
        lines.forEachIndexed { index, line -> LABEL.find(line)?.let { labels[it.groupValues[1]] = index } }
        val loops = ArrayList<IntRange>()
        lines.forEachIndexed { index, line ->
            val target = BRANCH.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val start = labels[target] ?: return@forEachIndexed
            if (start < index) loops.add(start..index)
        }
        var total = 0L
        lines.forEachIndexed { index, line ->
            if (!isInstruction(line)) return@forEachIndexed
            var weight = 1L
            repeat(loops.count { index in it }) { weight *= TRIP_COUNT }
            total += weight
        }
        return total
    }
}
