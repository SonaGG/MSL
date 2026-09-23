package gg.sona.msl.dxil

import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.llvm.LlvmBuilder
import gg.sona.msl.llvm.LlvmConstantInt
import gg.sona.msl.llvm.LlvmIntType
import gg.sona.msl.llvm.LlvmType
import gg.sona.msl.llvm.LlvmUndef
import gg.sona.msl.llvm.LlvmValue
import gg.sona.msl.llvm.LlvmVoidType

class DxilBufferAccess(private val emitter: DxilEmitter) {
    private val builder = emitter.builder

    private val modern: Boolean
        get() = emitter.shaderModelMinor >= 2

    fun load(pointer: BufferPointer): List<LlvmValue> = load(pointer.resource, pointer.offset, pointer.type)

    fun store(pointer: BufferPointer, components: List<LlvmValue>) {
        store(pointer.resource, pointer.offset, pointer.type, components, 0)
    }

    private fun load(resource: DxilResource, offset: LlvmValue, type: IrType): List<LlvmValue> = when (type) {
        is IrScalar -> loadVector(resource, offset, type, 1)
        is IrVector -> loadVector(resource, offset, type.element, type.count)
        is IrMatrix -> (0 until type.columns).flatMap {
            loadVector(resource, emitter.addConstant(offset, it * emitter.columnStride(type)), type.column.element, type.rows)
        }

        is IrArray -> (0 until type.length).flatMap { load(resource, emitter.addConstant(offset, it * type.stride), type.element) }
        is IrStruct -> type.members.flatMap { load(resource, emitter.addConstant(offset, it.offset), it.type) }
        else -> {
            emitter.error("cannot load $type from a buffer")
            emptyList()
        }
    }

    private fun store(resource: DxilResource, offset: LlvmValue, type: IrType, components: List<LlvmValue>, start: Int): Int = when (type) {
        is IrScalar -> storeVector(resource, offset, type, components.subList(start, start + 1)).let { start + 1 }
        is IrVector -> storeVector(resource, offset, type.element, components.subList(start, start + type.count)).let { start + type.count }
        is IrMatrix -> {
            var position = start
            for (column in 0 until type.columns) {
                storeVector(
                    resource,
                    emitter.addConstant(offset, column * emitter.columnStride(type)),
                    type.column.element,
                    components.subList(position, position + type.rows),
                )
                position += type.rows
            }
            position
        }

        is IrArray -> {
            var position = start
            for (i in 0 until type.length) position = store(resource, emitter.addConstant(offset, i * type.stride), type.element, components, position)
            position
        }

        is IrStruct -> {
            var position = start
            for (member in type.members) position = store(resource, emitter.addConstant(offset, member.offset), member.type, components, position)
            position
        }

        else -> {
            emitter.error("cannot store $type to a buffer")
            start
        }
    }

    private fun handle(resource: DxilResource): LlvmValue = emitter.handle(resource, null)

    private fun loadVector(resource: DxilResource, offset: LlvmValue, scalar: IrScalar, count: Int): List<LlvmValue> {
        if (resource.resourceClass == DxilResourceClass.CBuffer) return (0 until count).map { loadConstant(resource, emitter.addConstant(offset, it * byteSize(scalar)), scalar) }
        if (scalar is IrBool || scalar.bits == 8) return (0 until count).map { loadByte(resource, emitter.addConstant(offset, it * byteSize(scalar)), scalar) }
        if (scalar.bits == 64) {
            return (0 until count).map { index ->
                val words = rawLoad(resource, emitter.addConstant(offset, index * 8), LlvmIntType.I32, 2)
                val low = builder.cast(LlvmBuilder.CAST_ZEXT, words[0], LlvmIntType.I64)
                val high = builder.cast(LlvmBuilder.CAST_ZEXT, words[1], LlvmIntType.I64)
                val combined = builder.binary(LlvmBuilder.BINOP_OR, low, builder.binary(LlvmBuilder.BINOP_SHL, high, LlvmConstantInt(LlvmIntType.I64, 32)))
                if (scalar is IrFloat) builder.cast(LlvmBuilder.CAST_BITCAST, combined, emitter.scalarType(scalar)) else combined
            }
        }
        val type = emitter.scalarType(scalar)
        if (!modern) {
            val words = rawLoad(resource, offset, LlvmIntType.I32, count)
            return words.map { if (type == LlvmIntType.I32) it else builder.cast(LlvmBuilder.CAST_BITCAST, it, type) }
        }
        return rawLoad(resource, offset, type, count)
    }

    private fun rawLoad(resource: DxilResource, offset: LlvmValue, type: LlvmType, count: Int): List<LlvmValue> {
        val result = if (modern) {
            emitter.callOp(
                "rawBufferLoad",
                DxilOpcode.RawBufferLoad,
                type,
                emitter.types.resRet(type),
                listOf(handle(resource), offset, LlvmUndef(LlvmIntType.I32), emitter.i8((1 shl count) - 1), emitter.i32(alignment(type))),
                DxilEmitter.READ_ONLY,
            )
        } else {
            emitter.callOp(
                "bufferLoad",
                DxilOpcode.BufferLoad,
                LlvmIntType.I32,
                emitter.types.resRet(LlvmIntType.I32),
                listOf(handle(resource), offset, LlvmUndef(LlvmIntType.I32)),
                DxilEmitter.READ_ONLY,
            )
        }
        return List(count) { builder.extractValue(result, it) }
    }

    private fun alignment(type: LlvmType): Int = when (type) {
        is LlvmIntType -> type.bits / 8
        is gg.sona.msl.llvm.LlvmFloatType -> type.bits / 8
        else -> 4
    }

    private fun byteSize(scalar: IrScalar): Int = if (scalar is IrBool) 1 else scalar.bits / 8

    private fun alignedWord(offset: LlvmValue): Pair<LlvmValue, LlvmValue> {
        if (offset is LlvmConstantInt) {
            val value = offset.value.toInt()
            return emitter.i32(value and 3.inv()) to emitter.i32((value and 3) * 8)
        }
        val aligned = builder.binary(LlvmBuilder.BINOP_AND, offset, emitter.i32(3.inv()))
        val shift = builder.binary(LlvmBuilder.BINOP_SHL, builder.binary(LlvmBuilder.BINOP_AND, offset, emitter.i32(3)), emitter.i32(3))
        return aligned to shift
    }

    private fun loadByte(resource: DxilResource, offset: LlvmValue, scalar: IrScalar): LlvmValue {
        val (aligned, shift) = alignedWord(offset)
        val word = if (resource.resourceClass == DxilResourceClass.CBuffer) {
            loadConstant(resource, aligned, IrInt.U32)
        } else {
            rawLoad(resource, aligned, LlvmIntType.I32, 1)[0]
        }
        val byte = builder.binary(LlvmBuilder.BINOP_AND, builder.binary(LlvmBuilder.BINOP_LSHR, word, shift), emitter.i32(0xFF))
        return when {
            scalar is IrBool -> builder.compare(LlvmBuilder.ICMP_NE, byte, emitter.i32(0))
            scalar is IrInt && scalar.signed -> DxilArithmetic(emitter).normalizeValue(byte, scalar)
            else -> byte
        }
    }

    private fun loadConstant(resource: DxilResource, offset: LlvmValue, scalar: IrScalar): LlvmValue {
        if (scalar is IrBool || scalar.bits == 8) return loadByte(resource, offset, scalar)
        val type = emitter.scalarType(scalar)
        val lanesPerRow = when (scalar.bits) {
            16 -> 8
            64 -> 2
            else -> 4
        }
        val laneBytes = 16 / lanesPerRow
        val row: LlvmValue
        val lane: LlvmValue
        if (offset is LlvmConstantInt) {
            row = emitter.i32((offset.value / 16).toInt())
            lane = emitter.i32(((offset.value % 16) / laneBytes).toInt())
        } else {
            row = builder.binary(LlvmBuilder.BINOP_LSHR, offset, emitter.i32(4))
            lane = builder.binary(
                LlvmBuilder.BINOP_LSHR,
                builder.binary(LlvmBuilder.BINOP_AND, offset, emitter.i32(15)),
                emitter.i32(laneBytes.countTrailingZeroBits()),
            )
        }
        val handle = handle(resource)
        val load = {
            emitter.callOp("cbufferLoadLegacy", DxilOpcode.CBufferLoadLegacy, type, emitter.types.cbufRet(type), listOf(handle, row), DxilEmitter.READ_ONLY)
        }
        val result = if (row is LlvmConstantInt) emitter.constantRows.getOrPut(listOf(builder.block, handle, row.value, type), load) else load()
        if (lane is LlvmConstantInt) return builder.extractValue(result, lane.value.toInt())
        val lanes = List(lanesPerRow) { builder.extractValue(result, it) }
        var selected = lanes[0]
        for (i in 1 until lanesPerRow) selected = builder.select(builder.compare(LlvmBuilder.ICMP_EQ, lane, emitter.i32(i)), lanes[i], selected)
        return selected
    }

    private fun storeVector(resource: DxilResource, offset: LlvmValue, scalar: IrScalar, components: List<LlvmValue>) {
        if (resource.resourceClass != DxilResourceClass.Uav) {
            emitter.error("cannot write to a read-only buffer")
            return
        }
        if (scalar is IrBool || scalar.bits == 8) {
            components.forEachIndexed { index, value -> storeByte(resource, emitter.addConstant(offset, index * byteSize(scalar)), scalar, value) }
            return
        }
        if (scalar.bits == 64) {
            components.forEachIndexed { index, value ->
                val integer = if (scalar is IrFloat) builder.cast(LlvmBuilder.CAST_BITCAST, value, LlvmIntType.I64) else value
                val low = builder.cast(LlvmBuilder.CAST_TRUNC, integer, LlvmIntType.I32)
                val high = builder.cast(LlvmBuilder.CAST_TRUNC, builder.binary(LlvmBuilder.BINOP_LSHR, integer, LlvmConstantInt(LlvmIntType.I64, 32)), LlvmIntType.I32)
                rawStore(resource, emitter.addConstant(offset, index * 8), LlvmIntType.I32, listOf(low, high))
            }
            return
        }
        if (!modern) {
            val words = components.map { if (it.type == LlvmIntType.I32) it else builder.cast(LlvmBuilder.CAST_BITCAST, it, LlvmIntType.I32) }
            rawStore(resource, offset, LlvmIntType.I32, words)
            return
        }
        rawStore(resource, offset, emitter.scalarType(scalar), components)
    }

    private fun rawStore(resource: DxilResource, offset: LlvmValue, type: LlvmType, components: List<LlvmValue>) {
        val values = List(4) { components.getOrElse(it) { LlvmUndef(type) } }
        val mask = emitter.i8((1 shl components.size) - 1)
        if (modern) {
            emitter.callOp(
                "rawBufferStore",
                DxilOpcode.RawBufferStore,
                type,
                LlvmVoidType,
                listOf(handle(resource), offset, LlvmUndef(LlvmIntType.I32)) + values + listOf(mask, emitter.i32(alignment(type))),
                DxilEmitter.NO_UNWIND,
            )
        } else {
            emitter.callOp(
                "bufferStore",
                DxilOpcode.BufferStore,
                LlvmIntType.I32,
                LlvmVoidType,
                listOf(handle(resource), offset, LlvmUndef(LlvmIntType.I32)) + values + mask,
                DxilEmitter.NO_UNWIND,
            )
        }
    }

    private fun storeByte(resource: DxilResource, offset: LlvmValue, scalar: IrScalar, value: LlvmValue) {
        val (aligned, shift) = alignedWord(offset)
        val byte = if (scalar is IrBool) {
            builder.cast(LlvmBuilder.CAST_ZEXT, value, LlvmIntType.I32)
        } else {
            builder.binary(LlvmBuilder.BINOP_AND, value, emitter.i32(0xFF))
        }
        val mask = builder.binary(LlvmBuilder.BINOP_XOR, builder.binary(LlvmBuilder.BINOP_SHL, emitter.i32(0xFF), shift), emitter.i32(-1))
        val bits = builder.binary(LlvmBuilder.BINOP_SHL, byte, shift)
        atomic(resource, aligned, ATOMIC_AND, mask)
        atomic(resource, aligned, ATOMIC_OR, bits)
    }

    fun atomic(resource: DxilResource, offset: LlvmValue, operation: Int, value: LlvmValue): LlvmValue =
        emitter.callOp(
            "atomicBinOp",
            DxilOpcode.AtomicBinOp,
            value.type,
            value.type,
            listOf(handle(resource), emitter.i32(operation), offset, LlvmUndef(LlvmIntType.I32), LlvmUndef(LlvmIntType.I32), value),
            DxilEmitter.NO_UNWIND,
        )

    fun compareExchange(resource: DxilResource, offset: LlvmValue, comparand: LlvmValue, value: LlvmValue): LlvmValue =
        emitter.callOp(
            "atomicCompareExchange",
            DxilOpcode.AtomicCompareExchange,
            value.type,
            value.type,
            listOf(handle(resource), offset, LlvmUndef(LlvmIntType.I32), LlvmUndef(LlvmIntType.I32), comparand, value),
            DxilEmitter.NO_UNWIND,
        )

    companion object {
        const val ATOMIC_ADD = 0
        const val ATOMIC_AND = 1
        const val ATOMIC_OR = 2
        const val ATOMIC_XOR = 3
        const val ATOMIC_IMIN = 4
        const val ATOMIC_IMAX = 5
        const val ATOMIC_UMIN = 6
        const val ATOMIC_UMAX = 7
        const val ATOMIC_EXCHANGE = 8
    }
}
