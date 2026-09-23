package gg.sona.msl.dxil

import gg.sona.msl.lang.ShaderStage
import gg.sona.msl.llvm.LlvmAggregate
import gg.sona.msl.llvm.LlvmArrayType
import gg.sona.msl.llvm.LlvmConstantInt
import gg.sona.msl.llvm.LlvmFloatType
import gg.sona.msl.llvm.LlvmFunction
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmMetadata
import gg.sona.msl.llvm.LlvmPointerType
import gg.sona.msl.llvm.LlvmStructType
import gg.sona.msl.llvm.LlvmUndef
import gg.sona.msl.llvm.LlvmVectorType
import gg.sona.msl.llvm.MdNode
import gg.sona.msl.llvm.MdString
import gg.sona.msl.llvm.MdValue

class DxilMetadataBuilder(private val emitter: DxilEmitter) {
    fun build(function: LlvmFunction): Map<String, List<MdNode>> {
        val minor = emitter.shaderModelMinor
        val result = LinkedHashMap<String, List<MdNode>>()
        result["dx.version"] = listOf(node(i32(1), i32(minor)))
        result["dx.valver"] = listOf(node(i32(1), i32(emitter.options.validatorVersion)))
        result["dx.shaderModel"] = listOf(node(MdString(stageName()), i32(6), i32(minor)))
        val resources = resources()
        if (resources != null) result["dx.resources"] = listOf(resources)
        if (emitter.stage != ShaderStage.Kernel) {
            val state = DxilViewIdState(emitter.inputs, emitter.outputs).serialize()
            val array = LlvmAggregate(LlvmArrayType(LlvmIntType.I32, state.size), state.map { LlvmConstantInt(LlvmIntType.I32, it.toLong()) })
            result["dx.viewIdState"] = listOf(MdNode(listOf(MdValue(array))))
        }
        val entry = MdNode(
            listOf(
                MdValue(function),
                MdString(emitter.entry.name),
                signatures(),
                resources,
                properties(),
            ),
        )
        result["dx.entryPoints"] = listOf(entry)
        return result
    }

    private fun stageName(): String = when (emitter.stage) {
        ShaderStage.Vertex -> "vs"
        ShaderStage.Fragment -> "ps"
        ShaderStage.Kernel -> "cs"
    }

    private fun signatures(): MdNode? {
        if (emitter.stage == ShaderStage.Kernel) return null
        val inputs = emitter.inputs.map { element(it) }.ifEmpty { null }?.let { MdNode(it) }
        val outputs = emitter.outputs.map { element(it) }.ifEmpty { null }?.let { MdNode(it) }
        if (inputs == null && outputs == null) return null
        return MdNode(listOf(inputs, outputs, null))
    }

    private fun element(element: DxilSignatureElement): MdNode {
        val usage = if (element.usedMask != 0) node(i32(3), i32(element.usedMask)) else null
        return MdNode(
            listOf(
                i32(element.id),
                MdString(element.semanticName),
                i8(element.componentType),
                i8(element.semanticKind),
                node(i32(element.semanticIndex)),
                i8(element.interpolation),
                i32(1),
                i8(element.columns),
                i32(element.startRow),
                i8(element.startColumn),
                usage,
            ),
        )
    }

    private fun resources(): MdNode? {
        val lists = DxilResourceClass.entries.map { resourceClass ->
            emitter.resources.filter { it.resourceClass == resourceClass }.sortedBy { it.id }.map { resource(it) }
        }
        if (lists.all { it.isEmpty() }) return null
        return MdNode(lists.map { list -> list.ifEmpty { null }?.let { MdNode(it) } })
    }

    private fun resource(resource: DxilResource): MdNode {
        val symbol = MdValue(LlvmUndef(LlvmPointerType(symbolType(resource))))
        val common = listOf(
            i32(resource.id),
            symbol,
            MdString(resource.variable.name),
            i32(resource.space),
            i32(resource.register),
            i32(if (resource.count <= 0) -1 else resource.count),
        )
        val extra = if (resource.elementType != 0) node(i32(0), i32(resource.elementType)) else null
        val tail: List<LlvmMetadata?> = when (resource.resourceClass) {
            DxilResourceClass.Srv -> listOf(i32(resource.kind), i32(0), extra)
            DxilResourceClass.Uav -> listOf(i32(resource.kind), i1(false), i1(false), i1(false), extra)
            DxilResourceClass.CBuffer -> listOf(i32(resource.sizeInBytes), null)
            DxilResourceClass.Sampler -> listOf(i32(if (resource.comparison) 1 else 0), null)
        }
        return MdNode(common + tail)
    }

    private fun symbolType(resource: DxilResource): LlvmStructType {
        val name = when (resource.resourceClass) {
            DxilResourceClass.CBuffer -> "cbuffer.${resource.variable.name.filter { it.isLetterOrDigit() || it == '_' }}"
            DxilResourceClass.Sampler -> if (resource.comparison) "struct.SamplerComparisonState" else "struct.SamplerState"
            DxilResourceClass.Srv, DxilResourceClass.Uav -> {
                val prefix = if (resource.resourceClass == DxilResourceClass.Uav) "RW" else ""
                when (resource.kind) {
                    DxilResourceKind.RAW_BUFFER -> "struct.${prefix}ByteAddressBuffer"
                    DxilResourceKind.TYPED_BUFFER -> "class.${prefix}Buffer"
                    DxilResourceKind.TEXTURE_1D -> "class.${prefix}Texture1D"
                    DxilResourceKind.TEXTURE_1D_ARRAY -> "class.${prefix}Texture1DArray"
                    DxilResourceKind.TEXTURE_2D -> "class.${prefix}Texture2D"
                    DxilResourceKind.TEXTURE_2D_ARRAY -> "class.${prefix}Texture2DArray"
                    DxilResourceKind.TEXTURE_2D_MS -> "class.${prefix}Texture2DMS"
                    DxilResourceKind.TEXTURE_2D_MS_ARRAY -> "class.${prefix}Texture2DMSArray"
                    DxilResourceKind.TEXTURE_3D -> "class.${prefix}Texture3D"
                    DxilResourceKind.TEXTURE_CUBE -> "class.${prefix}TextureCube"
                    else -> "class.${prefix}TextureCubeArray"
                }
            }
        }
        if (resource.elementType == 0) return emitter.types.resource(name)
        val (scalar, spelled) = when (resource.elementType) {
            DxilEmitter.COMPONENT_F32 -> LlvmFloatType.Float to "float"
            DxilEmitter.COMPONENT_I32 -> LlvmIntType.I32 to "int"
            else -> LlvmIntType.I32 to "unsigned int"
        }
        return emitter.types.resource("$name<vector<$spelled, 4> >", LlvmVectorType(scalar, 4))
    }

    private fun properties(): MdNode? {
        val entries = ArrayList<LlvmMetadata?>()
        val uavCount = emitter.resources.filter { it.resourceClass == DxilResourceClass.Uav }.sumOf { maxOf(it.count, 1) }
        val flags = emitter.features.shaderFlags(emitter.entry.earlyFragmentTests, uavCount)
        if (flags != 0L) {
            entries.add(i32(0))
            entries.add(MdValue(LlvmConstantInt(LlvmIntType.I64, flags)))
        }
        if (emitter.stage == ShaderStage.Kernel) {
            val size = emitter.entry.workgroupSize
            entries.add(i32(4))
            entries.add(node(i32(size.x), i32(size.y), i32(size.z)))
        }
        return if (entries.isEmpty()) null else MdNode(entries)
    }

    private fun node(vararg elements: LlvmMetadata?): MdNode = MdNode(elements.toList())

    private fun i32(value: Int): MdValue = MdValue(LlvmConstantInt(LlvmIntType.I32, value.toLong()))

    private fun i8(value: Int): MdValue = MdValue(LlvmConstantInt(LlvmIntType.I8, value.toLong()))

    private fun i1(value: Boolean): MdValue = MdValue(LlvmConstantInt(LlvmIntType.I1, if (value) 1 else 0))
}
