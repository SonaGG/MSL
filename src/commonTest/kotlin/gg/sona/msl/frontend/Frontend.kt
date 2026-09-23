package gg.sona.msl.frontend

import gg.sona.msl.hir.Program
import gg.sona.msl.ir.IrModule
import gg.sona.msl.lower.Lowering
import gg.sona.msl.lower.LoweringOptions
import gg.sona.msl.passes.PassPipeline
import gg.sona.msl.parse.Parser
import gg.sona.msl.preprocess.Preprocessor
import gg.sona.msl.sema.Sema
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceManager

class Frontend(val source: String) {
    val sources = SourceManager()
    val diagnostics = Diagnostics(sources)

    fun analyze(): Program {
        val file = sources.add("test.metal", source)
        val tokens = Preprocessor(sources, diagnostics).process(file)
        val unit = Parser(tokens, diagnostics).parseTranslationUnit()
        return Sema(diagnostics).analyze(unit)
    }

    fun lower(options: LoweringOptions = LoweringOptions()): IrModule {
        val program = analyze()
        if (diagnostics.hasErrors) return IrModule()
        val module = Lowering(program, options, diagnostics).lower()
        if (!diagnostics.hasErrors) PassPipeline.run(module)
        return module
    }

    val errors: List<String>
        get() = diagnostics.all.filter { it.severity == gg.sona.msl.source.Severity.Error }.map { it.toString() }
}
