package gg.sona.msl.dxil

class DxilOptions(
    val minimumShaderModel: Int = 0,
    val validatorVersion: Int = 8,
    val bindings: DxilBindingLayout = DxilBindingLayout.Default,
    val semantics: DxilSemantics = DxilSemantics.Default,
    val dispatchSizeSpace: Int = 0,
    val dispatchSizeRegister: Int = 13,
)
