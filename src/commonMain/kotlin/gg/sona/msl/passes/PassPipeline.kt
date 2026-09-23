package gg.sona.msl.passes

import gg.sona.msl.ir.IrModule

object PassPipeline {
    fun run(module: IrModule) {
        Inliner.run(module)
        for (function in module.functions) {
            UnreachableBlockElimination.run(function)
            PointerTypeFixup.run(function)
            Mem2Reg.run(function)
            PhiSimplification.run(function)
            PointerLegalization.run(function)
            DeadCodeElimination.run(function)
            UnreachableBlockElimination.pruneIncoming(function)
        }
        DeadCodeElimination.removeUnusedGlobals(module)
    }
}
