package gg.sona.msl.spirv

import java.io.File
import java.util.concurrent.TimeUnit

object SpirvValidator {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("spirv-val", "--version").redirectErrorStream(true).start()
            process.inputStream.readAllBytes()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    fun validate(binary: ByteArray, targetEnvironment: String = "vulkan1.2"): String? {
        val file = File.createTempFile("msl", ".spv")
        try {
            file.writeBytes(binary)
            val process = ProcessBuilder(
                "spirv-val",
                "--target-env",
                targetEnvironment,
                "--scalar-block-layout",
                file.absolutePath,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            process.waitFor(60, TimeUnit.SECONDS)
            return if (process.exitValue() == 0) null else output
        } finally {
            file.delete()
        }
    }

    fun disassemble(binary: ByteArray): String {
        val file = File.createTempFile("msl", ".spv")
        try {
            file.writeBytes(binary)
            val process = ProcessBuilder("spirv-dis", file.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            process.waitFor(60, TimeUnit.SECONDS)
            return output
        } finally {
            file.delete()
        }
    }
}
