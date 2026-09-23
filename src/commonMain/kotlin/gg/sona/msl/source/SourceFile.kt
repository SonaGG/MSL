package gg.sona.msl.source

import gg.sona.msl.util.IntList

class SourceFile(val id: Int, val name: String, val text: String) {
    private val lineStarts: IntArray by lazy {
        val starts = IntList()
        starts.add(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        starts.toIntArray()
    }

    fun lineOf(offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (lineStarts[mid] <= offset) low = mid else high = mid - 1
        }
        return low + 1
    }

    fun columnOf(offset: Int): Int = offset - lineStarts[lineOf(offset) - 1] + 1

    fun lineText(line: Int): String {
        val start = lineStarts[line - 1]
        val end = if (line < lineStarts.size) lineStarts[line] - 1 else text.length
        return text.substring(start, end.coerceAtLeast(start)).trimEnd('\r')
    }
}
