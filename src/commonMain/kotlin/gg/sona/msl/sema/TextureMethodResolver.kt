package gg.sona.msl.sema

import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HLiteral
import gg.sona.msl.hir.HTextureOperation
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.TextureAtomicOperation
import gg.sona.msl.hir.TextureOperation
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.types.PointerType
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.SampleOptionKind
import gg.sona.msl.types.SampleOptionType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.TextureKind
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class TextureMethodResolver(private val diagnostics: Diagnostics) {
    fun resolve(texture: HExpr, method: String, arguments: List<HExpr>, location: SourceLocation): HExpr? {
        val type = texture.type as TextureType
        val cursor = ArgumentCursor(arguments)
        val result = when (method) {
            "sample" -> sample(type, texture, cursor, location, compare = false)
            "sample_compare" -> sample(type, texture, cursor, location, compare = true)
            "gather" -> gather(type, texture, cursor, location, compare = false)
            "gather_compare" -> gather(type, texture, cursor, location, compare = true)
            "read" -> read(type, texture, cursor, location)
            "write" -> write(type, texture, cursor, location)
            "get_width" -> size(type, texture, cursor, location, TextureOperation.GetWidth)
            "get_height" -> size(type, texture, cursor, location, TextureOperation.GetHeight)
            "get_depth" -> size(type, texture, cursor, location, TextureOperation.GetDepth)
            "get_array_size" -> query(type, texture, location, TextureOperation.GetArraySize)
            "get_num_mip_levels" -> query(type, texture, location, TextureOperation.GetNumMipLevels)
            "get_num_samples" -> query(type, texture, location, TextureOperation.GetNumSamples)
            "calculate_clamped_lod" -> lod(type, texture, cursor, location, TextureOperation.CalculateClampedLod)
            "calculate_unclamped_lod" -> lod(type, texture, cursor, location, TextureOperation.CalculateUnclampedLod)
            "fence" -> operation(TextureOperation.Fence, texture, VoidType, location)
            in ATOMICS -> atomic(type, texture, cursor, location, ATOMICS.getValue(method))
            else -> {
                diagnostics.error(location, "no member named '$method' in '$type'")
                return null
            }
        } ?: return null
        if (cursor.remaining) {
            diagnostics.error(location, "too many arguments to '$method' on '$type'")
            return null
        }
        return result
    }

    private fun operation(
        operation: TextureOperation,
        texture: HExpr,
        type: Type,
        location: SourceLocation,
        sampler: HExpr? = null,
        coordinate: HExpr? = null,
        arrayIndex: HExpr? = null,
        sampleOrLod: HExpr? = null,
        option: HExpr? = null,
        optionKind: SampleOptionKind? = null,
        minLodClamp: HExpr? = null,
        offset: HExpr? = null,
        compareValue: HExpr? = null,
        value: HExpr? = null,
        component: Int = 0,
    ) = HTextureOperation(
        operation, texture, sampler, coordinate, arrayIndex, sampleOrLod, option, optionKind, minLodClamp, offset,
        compareValue, value, component, type, location,
    )

    private fun floatVector(size: Int): Type = if (size == 1) ScalarType.Float else VectorType.of(ScalarType.Float, size)

    private fun uintVector(size: Int): Type = if (size == 1) ScalarType.UInt else VectorType.of(ScalarType.UInt, size)

    private fun intVector(size: Int): Type = if (size == 1) ScalarType.Int else VectorType.of(ScalarType.Int, size)

    private fun expect(argument: HExpr?, target: Type, what: String, location: SourceLocation): HExpr? {
        if (argument == null) {
            diagnostics.error(location, "missing $what argument")
            return null
        }
        val source = ConversionRules.valueType(argument.type)
        val convertible = ConversionRules.implicitCost(source, target) != null ||
            (source is VectorType && target is VectorType && source.size == target.size && source.element.kind.isInteger &&
                target.element.kind.isInteger)
        if (!convertible) {
            diagnostics.error(argument.location, "cannot convert $what of type '$source' to '$target'")
            return null
        }
        return ConversionRules.convert(argument, target)
    }

    private fun expectSampler(cursor: ArgumentCursor, location: SourceLocation): HExpr? {
        val sampler = cursor.next()
        if (sampler == null || sampler.type != SamplerType) {
            diagnostics.error(sampler?.location ?: location, "expected sampler argument")
            return null
        }
        return sampler
    }

    private fun sample(
        type: TextureType,
        texture: HExpr,
        cursor: ArgumentCursor,
        location: SourceLocation,
        compare: Boolean,
    ): HExpr? {
        val kind = type.kind
        if (type.access != gg.sona.msl.types.TextureAccess.Sample || kind.isMultisampled || kind.isBuffer) {
            diagnostics.error(location, "'$type' cannot be sampled")
            return null
        }
        if (compare && !kind.isDepth) {
            diagnostics.error(location, "sample_compare requires a depth texture")
            return null
        }
        val sampler = expectSampler(cursor, location) ?: return null
        val coordinate = expect(cursor.next(), floatVector(kind.coordinateCount), "coordinate", location) ?: return null
        val arrayIndex = if (kind.isArray) expect(cursor.next(), ScalarType.UInt, "array index", location) ?: return null else null
        val compareValue = if (compare) expect(cursor.next(), ScalarType.Float, "compare value", location) ?: return null else null
        var option: HExpr? = null
        var optionKind: SampleOptionKind? = null
        var minLodClamp: HExpr? = null
        val first = (cursor.peek()?.type as? SampleOptionType)?.kind
        if (first != null && first != SampleOptionKind.MinLodClamp) {
            option = cursor.next()
            optionKind = first
            if (!validOption(first, kind, compare)) {
                diagnostics.error(option!!.location, "'${first.spelling}' is not valid for '$type'")
                return null
            }
        }
        if ((cursor.peek()?.type as? SampleOptionType)?.kind == SampleOptionKind.MinLodClamp) minLodClamp = cursor.next()
        val offset = offsetArgument(cursor, kind)
        val resultType = if (kind.isDepth) type.sampleType else type.texelType
        return operation(
            if (compare) TextureOperation.SampleCompare else TextureOperation.Sample,
            texture,
            resultType,
            location,
            sampler = sampler,
            coordinate = coordinate,
            arrayIndex = arrayIndex,
            option = option,
            optionKind = optionKind,
            minLodClamp = minLodClamp,
            offset = offset,
            compareValue = compareValue,
        )
    }

    private fun validOption(option: SampleOptionKind, kind: TextureKind, compare: Boolean): Boolean = when (option) {
        SampleOptionKind.Bias -> !compare
        SampleOptionKind.Level -> true
        SampleOptionKind.Gradient2D -> kind.dimensions == 2 && !kind.isCube
        SampleOptionKind.Gradient3D -> kind == TextureKind.Texture3D
        SampleOptionKind.GradientCube -> kind.isCube
        SampleOptionKind.MinLodClamp -> true
    }

    private fun offsetArgument(cursor: ArgumentCursor, kind: TextureKind): HExpr? {
        val candidate = cursor.peek() ?: return null
        if (kind.isCube) return null
        val source = ConversionRules.valueType(candidate.type)
        if (!source.isIntegerScalarOrVector || source.componentCount != kind.dimensions) return null
        cursor.next()
        return ConversionRules.convert(candidate, intVector(kind.dimensions))
    }

    private fun gather(
        type: TextureType,
        texture: HExpr,
        cursor: ArgumentCursor,
        location: SourceLocation,
        compare: Boolean,
    ): HExpr? {
        val kind = type.kind
        if (kind.dimensions != 2 || kind.isMultisampled || type.access != gg.sona.msl.types.TextureAccess.Sample) {
            diagnostics.error(location, "gather is not supported on '$type'")
            return null
        }
        if (compare && !kind.isDepth) {
            diagnostics.error(location, "gather_compare requires a depth texture")
            return null
        }
        val sampler = expectSampler(cursor, location) ?: return null
        val coordinate = expect(cursor.next(), floatVector(kind.coordinateCount), "coordinate", location) ?: return null
        val arrayIndex = if (kind.isArray) expect(cursor.next(), ScalarType.UInt, "array index", location) ?: return null else null
        val compareValue = if (compare) expect(cursor.next(), ScalarType.Float, "compare value", location) ?: return null else null
        val offset = offsetArgument(cursor, kind)
        var component = 0
        val componentArgument = cursor.peek()
        if (componentArgument != null && componentArgument.type == BuiltinEnums.component) {
            cursor.next()
            val value = (componentArgument as? HLiteral)?.value as? EnumConstant
            if (value == null) {
                diagnostics.error(componentArgument.location, "gather component must be a constant")
                return null
            }
            if (kind.isDepth) {
                diagnostics.error(componentArgument.location, "depth gather does not take a component")
                return null
            }
            component = value.value.toInt()
        }
        return operation(
            if (compare) TextureOperation.GatherCompare else TextureOperation.Gather,
            texture,
            VectorType.of(type.sampleType, 4),
            location,
            sampler = sampler,
            coordinate = coordinate,
            arrayIndex = arrayIndex,
            offset = offset,
            compareValue = compareValue,
            component = component,
        )
    }

    private fun readCoordinateSize(kind: TextureKind): Int = if (kind.isCube) 2 else kind.dimensions

    private fun read(type: TextureType, texture: HExpr, cursor: ArgumentCursor, location: SourceLocation): HExpr? {
        val kind = type.kind
        if (!type.access.canRead) {
            diagnostics.error(location, "'$type' is not readable")
            return null
        }
        if (kind.isCube) {
            diagnostics.error(location, "read on cube textures is not supported")
            return null
        }
        val coordinate = expect(cursor.next(), uintVector(readCoordinateSize(kind)), "coordinate", location) ?: return null
        val arrayIndex = if (kind.isArray) expect(cursor.next(), ScalarType.UInt, "array index", location) ?: return null else null
        var sampleOrLod: HExpr? = null
        if (cursor.remaining) sampleOrLod = expect(cursor.next(), ScalarType.UInt, "level or sample", location) ?: return null
        if (kind.isMultisampled && sampleOrLod == null) {
            diagnostics.error(location, "read on multisampled textures requires a sample index")
            return null
        }
        val resultType = if (kind.isDepth) type.sampleType else type.texelType
        return operation(
            TextureOperation.Read,
            texture,
            resultType,
            location,
            coordinate = coordinate,
            arrayIndex = arrayIndex,
            sampleOrLod = sampleOrLod,
        )
    }

    private fun write(type: TextureType, texture: HExpr, cursor: ArgumentCursor, location: SourceLocation): HExpr? {
        val kind = type.kind
        if (!type.access.canWrite || kind.isDepth || kind.isMultisampled || kind.isCube) {
            diagnostics.error(location, "'$type' is not writable")
            return null
        }
        val value = expect(cursor.next(), type.texelType, "color", location) ?: return null
        val coordinate = expect(cursor.next(), uintVector(readCoordinateSize(kind)), "coordinate", location) ?: return null
        val arrayIndex = if (kind.isArray) expect(cursor.next(), ScalarType.UInt, "array index", location) ?: return null else null
        var lod: HExpr? = null
        if (cursor.remaining) lod = expect(cursor.next(), ScalarType.UInt, "level", location) ?: return null
        return operation(
            TextureOperation.Write,
            texture,
            VoidType,
            location,
            coordinate = coordinate,
            arrayIndex = arrayIndex,
            sampleOrLod = lod,
            value = value,
        )
    }

    private fun atomic(type: TextureType, texture: HExpr, cursor: ArgumentCursor, location: SourceLocation, atomic: TextureAtomicOperation): HExpr? {
        val kind = type.kind
        if (type.access != gg.sona.msl.types.TextureAccess.ReadWrite || !type.sampleType.kind.isInteger || type.sampleType.kind.bits != 32) {
            diagnostics.error(location, "atomic operations require a read_write texture of int or uint, not '$type'")
            return null
        }
        if (kind.isCube || kind.isDepth || kind.isMultisampled || kind.isBuffer || kind == TextureKind.Texture1DArray) {
            diagnostics.error(location, "atomic operations are not supported on '$type'")
            return null
        }
        val coordinate = expect(cursor.next(), uintVector(readCoordinateSize(kind)), "coordinate", location) ?: return null
        val arrayIndex = if (kind.isArray) expect(cursor.next(), ScalarType.UInt, "array index", location) ?: return null else null
        val expected = if (atomic == TextureAtomicOperation.CompareExchange) {
            val pointer = cursor.next()
            val pointerType = pointer?.let { ConversionRules.valueType(it.type) } as? PointerType
            if (pointer == null || pointerType == null || pointerType.addressSpace != AddressSpace.Thread || pointerType.pointee != type.texelType) {
                diagnostics.error(pointer?.location ?: location, "expected a 'thread ${type.texelType}*' argument")
                return null
            }
            pointer
        } else {
            null
        }
        val value = if (atomic == TextureAtomicOperation.Load) null else expect(cursor.next(), type.texelType, "value", location) ?: return null
        val result = when (atomic) {
            TextureAtomicOperation.Store -> VoidType
            TextureAtomicOperation.CompareExchange -> ScalarType.Bool
            else -> type.texelType
        }
        return operation(
            TextureOperation.Atomic,
            texture,
            result,
            location,
            coordinate = coordinate,
            arrayIndex = arrayIndex,
            compareValue = expected,
            value = value,
            component = atomic.ordinal,
        )
    }

    private fun size(
        type: TextureType,
        texture: HExpr,
        cursor: ArgumentCursor,
        location: SourceLocation,
        operation: TextureOperation,
    ): HExpr? {
        val kind = type.kind
        val dimension = when (operation) {
            TextureOperation.GetWidth -> 1
            TextureOperation.GetHeight -> 2
            else -> 3
        }
        if (dimension > kind.dimensions) {
            diagnostics.error(location, "'$type' has no ${operation.name.removePrefix("Get").lowercase()}")
            return null
        }
        var lod: HExpr? = null
        if (cursor.remaining) {
            if (!kind.supportsMipmaps) {
                diagnostics.error(location, "'$type' has no mipmaps")
                return null
            }
            lod = expect(cursor.next(), ScalarType.UInt, "level", location) ?: return null
        }
        if (lod == null && kind.supportsMipmaps) lod = HLiteral(ScalarConstant.of(ScalarType.UInt, 0L), location)
        return operation(operation, texture, ScalarType.UInt, location, sampleOrLod = lod)
    }

    private fun query(type: TextureType, texture: HExpr, location: SourceLocation, operation: TextureOperation): HExpr? {
        val kind = type.kind
        val valid = when (operation) {
            TextureOperation.GetArraySize -> kind.isArray
            TextureOperation.GetNumMipLevels -> kind.supportsMipmaps
            else -> kind.isMultisampled
        }
        if (!valid) {
            diagnostics.error(location, "invalid texture query on '$type'")
            return null
        }
        return operation(operation, texture, ScalarType.UInt, location)
    }

    private fun lod(
        type: TextureType,
        texture: HExpr,
        cursor: ArgumentCursor,
        location: SourceLocation,
        operation: TextureOperation,
    ): HExpr? {
        val kind = type.kind
        if (!kind.supportsMipmaps || type.access != gg.sona.msl.types.TextureAccess.Sample) {
            diagnostics.error(location, "cannot calculate level of detail on '$type'")
            return null
        }
        val sampler = expectSampler(cursor, location) ?: return null
        val coordinate = expect(cursor.next(), floatVector(kind.coordinateCount), "coordinate", location) ?: return null
        return operation(operation, texture, ScalarType.Float, location, sampler = sampler, coordinate = coordinate)
    }

    private companion object {
        val ATOMICS: Map<String, TextureAtomicOperation> = mapOf(
            "atomic_load" to TextureAtomicOperation.Load,
            "atomic_store" to TextureAtomicOperation.Store,
            "atomic_exchange" to TextureAtomicOperation.Exchange,
            "atomic_compare_exchange_weak" to TextureAtomicOperation.CompareExchange,
            "atomic_fetch_add" to TextureAtomicOperation.Add,
            "atomic_fetch_sub" to TextureAtomicOperation.Sub,
            "atomic_fetch_and" to TextureAtomicOperation.And,
            "atomic_fetch_or" to TextureAtomicOperation.Or,
            "atomic_fetch_xor" to TextureAtomicOperation.Xor,
            "atomic_fetch_min" to TextureAtomicOperation.Min,
            "atomic_fetch_max" to TextureAtomicOperation.Max,
        )
    }
}
