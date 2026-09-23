package gg.sona.msl.sema

import gg.sona.msl.ast.CallExpr
import gg.sona.msl.ast.Expr
import gg.sona.msl.ast.NameExpr
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.SamplerAddressMode
import gg.sona.msl.hir.SamplerBorderColor
import gg.sona.msl.hir.SamplerCompareFunction
import gg.sona.msl.hir.SamplerFilter
import gg.sona.msl.hir.SamplerMipFilter
import gg.sona.msl.hir.SamplerState
import gg.sona.msl.hir.ScalarConstant

class SamplerStateBuilder(private val sema: Sema) {
    private val diagnostics = sema.diagnostics

    fun build(arguments: List<Expr>, scope: Scope): SamplerState {
        var state = SamplerState()
        for (argument in arguments) {
            if (argument is CallExpr) {
                state = function(state, argument, scope)
                continue
            }
            val value = (sema.expressions.analyze(argument, scope) as? HLiteral)?.value as? EnumConstant
            if (value == null) {
                diagnostics.error(argument.location, "invalid constexpr sampler argument")
                continue
            }
            val spelling = value.toString()
            state = when (value.type) {
                BuiltinEnums.coord -> state.copy(normalizedCoordinates = spelling == "normalized")
                BuiltinEnums.address -> address(spelling).let { state.copy(addressU = it, addressV = it, addressW = it) }
                BuiltinEnums.sAddress -> state.copy(addressU = address(spelling))
                BuiltinEnums.tAddress -> state.copy(addressV = address(spelling))
                BuiltinEnums.rAddress -> state.copy(addressW = address(spelling))
                BuiltinEnums.filter -> filter(spelling).let { state.copy(magFilter = it, minFilter = it) }
                BuiltinEnums.magFilter -> state.copy(magFilter = filter(spelling))
                BuiltinEnums.minFilter -> state.copy(minFilter = filter(spelling))
                BuiltinEnums.mipFilter -> state.copy(mipFilter = SamplerMipFilter.entries.first { it.spelling == spelling })
                BuiltinEnums.compareFunc -> state.copy(
                    compareFunction = if (spelling == "none") {
                        SamplerCompareFunction.Never
                    } else {
                        SamplerCompareFunction.entries.first { it.spelling == spelling }
                    },
                )

                BuiltinEnums.borderColor -> state.copy(borderColor = SamplerBorderColor.entries.first { it.spelling == spelling })
                else -> {
                    diagnostics.error(argument.location, "'${value.type}' is not a sampler property")
                    state
                }
            }
        }
        return state
    }

    private fun address(spelling: String) = SamplerAddressMode.entries.first { it.spelling == spelling }

    private fun filter(spelling: String) = SamplerFilter.entries.first { it.spelling == spelling }

    private fun function(state: SamplerState, call: CallExpr, scope: Scope): SamplerState {
        val name = (call.callee as? NameExpr)?.name?.withoutMetalPrefix()?.last
        val values = call.arguments.map { sema.fold(sema.expressions.analyze(it, scope)) as? ScalarConstant }
        if (values.any { it == null }) {
            diagnostics.error(call.location, "sampler property arguments must be constant")
            return state
        }
        return when (name) {
            "max_anisotropy" -> {
                val value = values.singleOrNull()?.asLong?.toInt()
                if (value == null || value !in 1..16) {
                    diagnostics.error(call.location, "max_anisotropy must be between 1 and 16")
                    state
                } else {
                    state.copy(maxAnisotropy = value)
                }
            }

            "lod_clamp" -> {
                if (values.size != 2) {
                    diagnostics.error(call.location, "lod_clamp expects two arguments")
                    state
                } else {
                    state.copy(lodMin = values[0]!!.asDouble.toFloat(), lodMax = values[1]!!.asDouble.toFloat())
                }
            }

            else -> {
                diagnostics.error(call.location, "unknown sampler property '$name'")
                state
            }
        }
    }
}
