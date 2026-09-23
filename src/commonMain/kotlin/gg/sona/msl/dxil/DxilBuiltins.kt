package gg.sona.msl.dxil

import gg.sona.msl.ir.BuiltinVariable
import gg.sona.msl.llvm.LlvmBuilder
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmValue

class DxilBuiltins(private val emitter: DxilEmitter) {
    private val builder = emitter.builder

    fun load(builtin: BuiltinVariable, component: Int, count: Int): List<LlvmValue>? = when (builtin) {
        BuiltinVariable.GlobalInvocationId -> components(component, count) { threadOp("threadId", DxilOpcode.ThreadId, it) }
        BuiltinVariable.LocalInvocationId -> components(component, count) { threadOp("threadIdInGroup", DxilOpcode.ThreadIdInGroup, it) }
        BuiltinVariable.WorkgroupId -> components(component, count) { threadOp("groupId", DxilOpcode.GroupId, it) }
        BuiltinVariable.LocalInvocationIndex -> listOf(flattened())
        BuiltinVariable.NumWorkgroups -> {
            val size = emitter.dispatchSize()
            components(component, count) { builder.extractValue(size, it) }
        }

        BuiltinVariable.SubgroupLocalInvocationId -> listOf(laneIndex())
        BuiltinVariable.SubgroupSize -> listOf(laneCount())
        BuiltinVariable.SubgroupId -> listOf(builder.binary(LlvmBuilder.BINOP_UDIV, flattened(), laneCount()))
        BuiltinVariable.NumSubgroups -> {
            val total = emitter.entry.workgroupSize.total
            val lanes = laneCount()
            listOf(
                builder.binary(
                    LlvmBuilder.BINOP_UDIV,
                    builder.binary(LlvmBuilder.BINOP_SUB, builder.binary(LlvmBuilder.BINOP_ADD, emitter.i32(total), lanes), emitter.i32(1)),
                    lanes,
                ),
            )
        }

        BuiltinVariable.SampleId -> listOf(
            emitter.callOp("sampleIndex", 90, LlvmIntType.I32, LlvmIntType.I32, emptyList()),
        )

        BuiltinVariable.SampleMaskIn -> listOf(
            emitter.callOp("coverage", DxilOpcode.Coverage, LlvmIntType.I32, LlvmIntType.I32, emptyList()),
        )

        else -> null
    }

    private inline fun components(component: Int, count: Int, producer: (Int) -> LlvmValue): List<LlvmValue> =
        List(count) { producer(component + it) }

    private fun threadOp(name: String, opcode: Int, component: Int): LlvmValue =
        emitter.callOp(name, opcode, LlvmIntType.I32, LlvmIntType.I32, listOf(emitter.i32(component)))

    private fun flattened(): LlvmValue =
        emitter.callOp("flattenedThreadIdInGroup", DxilOpcode.FlattenedThreadIdInGroup, LlvmIntType.I32, LlvmIntType.I32, emptyList())

    fun laneIndex(): LlvmValue {
        emitter.features.waveOps = true
        return emitter.callOp("waveGetLaneIndex", DxilOpcode.WaveGetLaneIndex, null, LlvmIntType.I32, emptyList(), DxilEmitter.READ_ONLY)
    }

    fun laneCount(): LlvmValue {
        emitter.features.waveOps = true
        return emitter.callOp("waveGetLaneCount", DxilOpcode.WaveGetLaneCount, null, LlvmIntType.I32, emptyList())
    }
}
