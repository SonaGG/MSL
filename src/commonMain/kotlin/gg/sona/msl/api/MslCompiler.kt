package gg.sona.msl.api

import gg.sona.msl.ir.IrModule
import gg.sona.msl.lower.Lowering
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.parse.Parser
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PassPipeline
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.preprocess.IncludeResolver
import gg.sona.msl.preprocess.Preprocessor
import gg.sona.msl.reflect.Reflector
import gg.sona.msl.sema.Sema
import gg.sona.msl.source.CompilationException
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceManager
import gg.sona.msl.spirv.SpirvEmitter
import gg.sona.msl.spirv.SpirvIntrinsicEmitter
import gg.sona.msl.spirv.SpirvOptions

class MslCompiler(
    private val includeResolver: IncludeResolver = IncludeResolver.None,
    private val defines: Map<String, String> = emptyMap(),
    private val metalVersion: Int = 320,
) {
    fun compileSpirv(
        source: String,
        fileName: String = "shader.metal",
        options: SpirvOptions = SpirvOptions(),
        lowering: LoweringOptions = LoweringOptions(),
    ): SpirvCompilation {
        val sources = SourceManager()
        val diagnostics = Diagnostics(sources)
        val shaders = ArrayList<SpirvShader>()
        try {
            val module = frontend(source, fileName, sources, diagnostics, lowering, "__MSL_TARGET_SPIRV__")
            if (module != null) {
                val expansion = IntrinsicExpansion { intrinsic, _ -> SpirvIntrinsicEmitter.isNative(intrinsic) }
                for (function in module.functions) {
                    expansion.run(function)
                    PhiSimplification.run(function)
                    DeadCodeElimination.run(function)
                }
                for (entry in module.entryPoints) {
                    val words = SpirvEmitter(module, entry, options, diagnostics).emit()
                    shaders.add(SpirvShader(words, Reflector.reflect(module, entry)))
                }
            }
        } catch (exception: CompilationException) {
            return SpirvCompilation(emptyList(), exception.diagnostics)
        }
        return SpirvCompilation(if (diagnostics.hasErrors) emptyList() else shaders, diagnostics.all.toList())
    }

    private fun frontend(
        source: String,
        fileName: String,
        sources: SourceManager,
        diagnostics: Diagnostics,
        options: LoweringOptions,
        targetMacro: String,
    ): IrModule? {
        val file = sources.add(fileName, source)
        val macros = LinkedHashMap<String, String>()
        macros["__METAL_VERSION__"] = metalVersion.toString()
        macros["__METAL__"] = "1"
        macros["__cplusplus"] = "201402L"
        macros[targetMacro] = "1"
        macros.putAll(defines)
        val tokens = Preprocessor(sources, diagnostics, includeResolver, macros).process(file)
        if (diagnostics.hasErrors) return null
        val unit = Parser(tokens, diagnostics).parseTranslationUnit()
        if (diagnostics.hasErrors) return null
        val program = Sema(diagnostics).analyze(unit)
        if (diagnostics.hasErrors) return null
        val module = Lowering(program, options, diagnostics).lower()
        if (diagnostics.hasErrors) return null
        PassPipeline.run(module)
        return module
    }
}
