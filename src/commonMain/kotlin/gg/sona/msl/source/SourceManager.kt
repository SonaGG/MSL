package gg.sona.msl.source

class SourceManager {
    private val files = ArrayList<SourceFile>()

    val all: List<SourceFile>
        get() = files

    fun add(name: String, text: String): SourceFile {
        val file = SourceFile(files.size, name, text)
        files.add(file)
        return file
    }

    operator fun get(id: Int): SourceFile? = files.getOrNull(id)

    fun describe(location: SourceLocation): String {
        val file = if (location.isValid) get(location.fileId) else null
        if (file == null) return "<unknown>"
        return "${file.name}:${file.lineOf(location.offset)}:${file.columnOf(location.offset)}"
    }
}
