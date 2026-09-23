package gg.sona.msl.dxil

import gg.sona.msl.llvm.LlvmFunction
import gg.sona.msl.llvm.LlvmModule

class DxilModuleResult(val module: LlvmModule, val entry: LlvmFunction)
