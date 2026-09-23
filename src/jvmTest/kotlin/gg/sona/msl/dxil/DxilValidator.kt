package gg.sona.msl.dxil

import java.io.File
import java.util.concurrent.TimeUnit

object DxilValidator {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("dxv", "-help").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(30, TimeUnit.SECONDS)
        }.getOrDefault(false)
    }

    fun validate(container: File): String? {
        val output = run("dxv", container.absolutePath)
        return if (output.contains("Validation succeeded")) null else output
    }

    fun disassemble(container: File): String = run("dxc", "-dumpbin", container.absolutePath)

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        process.waitFor(60, TimeUnit.SECONDS)
        return output
    }
}
