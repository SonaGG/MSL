package gg.sona.msl.api

import gg.sona.msl.dxil.DxilContainerWriter
import gg.sona.msl.dxil.DxilEmitter
import gg.sona.msl.dxil.DxilNativeIntrinsics
import gg.sona.msl.dxil.DxilOptions
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.ir.IrModule
import gg.sona.msl.opt.FloatPrecision
import gg.sona.msl.opt.MathAccuracy
import gg.sona.msl.opt.OptimizationLevel
import gg.sona.msl.opt.Optimizer
import gg.sona.msl.opt.Specialization
import gg.sona.msl.opt.StageLink
import gg.sona.msl.lower.Lowering
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.parse.Parser
import gg.sona.msl.passes.DeadCodeElimination
import gg.sona.msl.passes.IntrinsicExpansion
import gg.sona.msl.passes.PassPipeline
import gg.sona.msl.passes.PhiSimplification
import gg.sona.msl.lang.ShaderStage
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
        optimization: OptimizationLevel = OptimizationLevel.Aggressive,
        specialization: Specialization = Specialization(),
        precision: FloatPrecision = FloatPrecision.Full,
        links: List<StageLink> = emptyList(),
        accuracy: MathAccuracy = MathAccuracy.Precise,
        relaxInterpolation: Boolean = false,
    ): SpirvCompilation {
        val sources = SourceManager()
        val diagnostics = Diagnostics(sources)
        val shaders = ArrayList<SpirvShader>()
        try {
            val module = frontend(source, fileName, sources, diagnostics, lowering, "__MSL_TARGET_SPIRV__")
            if (module != null) {
                val isNative: (Intrinsic, Instruction) -> Boolean = { intrinsic, _ -> SpirvIntrinsicEmitter.isNative(intrinsic) }
                val expansion = IntrinsicExpansion(isNative)
                for (function in module.functions) {
                    expansion.run(function)
                    PhiSimplification.run(function)
                    DeadCodeElimination.run(function)
                }
                Optimizer(optimization, isNative, diagnostics, specialization, precision, links, vectorize = true, accuracy = accuracy, relaxInterpolation = relaxInterpolation).run(module)
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

    fun compileDxil(
        source: String,
        fileName: String = "shader.metal",
        options: DxilOptions = DxilOptions(),
        lowering: LoweringOptions = LoweringOptions(),
        optimization: OptimizationLevel = OptimizationLevel.Aggressive,
        specialization: Specialization = Specialization(),
        precision: FloatPrecision = FloatPrecision.Full,
        links: List<StageLink> = emptyList(),
        accuracy: MathAccuracy = MathAccuracy.Precise,
        relaxInterpolation: Boolean = false,
    ): DxilCompilation {
        val sources = SourceManager()
        val diagnostics = Diagnostics(sources)
        val shaders = ArrayList<DxilShader>()
        try {
            val module = frontend(source, fileName, sources, diagnostics, lowering, "__MSL_TARGET_DXIL__")
            if (module != null) {
                val expansion = IntrinsicExpansion(DxilNativeIntrinsics::isNative)
                for (function in module.functions) {
                    expansion.run(function)
                    PhiSimplification.run(function)
                    DeadCodeElimination.run(function)
                }
                Optimizer(optimization, DxilNativeIntrinsics::isNative, diagnostics, specialization, precision, links, accuracy = accuracy, relaxInterpolation = relaxInterpolation).run(module)
                for (entry in module.entryPoints) {
                    val emitter = DxilEmitter(module, entry, options, diagnostics)
                    val result = emitter.emit()
                    if (emitter.hasErrors) continue
                    val bytes = DxilContainerWriter(emitter).write(result)
                    val profile = when (entry.stage) {
                        ShaderStage.Vertex -> "vs"
                        ShaderStage.Fragment -> "ps"
                        ShaderStage.Kernel -> "cs"
                    }
                    shaders.add(
                        DxilShader(bytes, "${profile}_6_${emitter.shaderModelMinor}", Reflector.reflect(module, entry), emitter.dispatchSizeLocation),
                    )
                }
            }
        } catch (exception: CompilationException) {
            return DxilCompilation(emptyList(), exception.diagnostics)
        }
        return DxilCompilation(if (diagnostics.hasErrors) emptyList() else shaders, diagnostics.all.toList())
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
