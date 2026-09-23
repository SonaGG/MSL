package gg.sona.msl.lower

import gg.sona.msl.hir.CompositeConstant
import gg.sona.msl.hir.ConstValue
import gg.sona.msl.hir.EnumConstant
import gg.sona.msl.hir.ScalarConstant
import gg.sona.msl.hir.ZeroConstant
import gg.sona.msl.ir.ConstantComposite
import gg.sona.msl.ir.ConstantNull
import gg.sona.msl.ir.ConstantScalar
import gg.sona.msl.ir.Constants
import gg.sona.msl.ir.ImageAccess
import gg.sona.msl.ir.ImageDim
import gg.sona.msl.ir.IrArray
import gg.sona.msl.ir.IrBool
import gg.sona.msl.ir.IrConstant
import gg.sona.msl.ir.IrFloat
import gg.sona.msl.ir.IrImage
import gg.sona.msl.ir.IrInt
import gg.sona.msl.ir.IrMatrix
import gg.sona.msl.ir.IrMember
import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.IrPointer
import gg.sona.msl.ir.IrSampler
import gg.sona.msl.ir.IrScalar
import gg.sona.msl.ir.IrStruct
import gg.sona.msl.ir.IrType
import gg.sona.msl.ir.IrVector
import gg.sona.msl.ir.IrVoid
import gg.sona.msl.ir.SampledKind
import gg.sona.msl.ir.StorageClass
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.types.ArrayType
import gg.sona.msl.types.AtomicType
import gg.sona.msl.types.EnumType
import gg.sona.msl.types.MatrixType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.SamplerType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.StructType
import gg.sona.msl.types.TextureAccess
import gg.sona.msl.types.TextureKind
import gg.sona.msl.types.TextureType
import gg.sona.msl.types.Type
import gg.sona.msl.types.TypeLayout
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

class TypeLowering(private val module: IrModule) {
    private val structs = HashMap<StructType, IrStruct>()

    fun scalar(kind: ScalarKind): IrScalar = when (kind) {
        ScalarKind.Bool -> IrBool
        ScalarKind.Char -> IrInt.I8
        ScalarKind.UChar -> IrInt.U8
        ScalarKind.Short -> IrInt.I16
        ScalarKind.UShort -> IrInt.U16
        ScalarKind.Int -> IrInt.I32
        ScalarKind.UInt -> IrInt.U32
        ScalarKind.Long -> IrInt.I64
        ScalarKind.ULong -> IrInt.U64
        ScalarKind.Half, ScalarKind.BFloat -> IrFloat.F16
        ScalarKind.Float -> IrFloat.F32
    }

    fun lower(type: Type): IrType = when (type) {
        is ScalarType -> scalar(type.kind)
        is VectorType -> IrVector.of(scalar(type.element.kind), type.size)
        is MatrixType -> IrMatrix(IrVector.of(scalar(type.element.kind), type.rows), type.columns)
        is ArrayType -> IrArray(lower(type.element), if (type.isUnsized) 0 else type.size, TypeLayout.stride(type.element))
        is StructType -> struct(type)
        is EnumType -> scalar(type.underlying.kind)
        is AtomicType -> scalar(type.element.kind)
        is PointerType -> IrPointer(lower(type.pointee), storageFor(type.addressSpace))
        is TextureType -> image(type)
        is SamplerType -> IrSampler
        is VoidType -> IrVoid
        else -> error("cannot lower type $type")
    }

    fun storageFor(space: AddressSpace): StorageClass = when (space) {
        AddressSpace.Device -> StorageClass.StorageBuffer
        AddressSpace.Constant -> StorageClass.Uniform
        AddressSpace.Threadgroup -> StorageClass.Workgroup
        else -> StorageClass.Function
    }

    fun struct(type: StructType): IrStruct = structs.getOrPut(type) {
        val layout = type.layout()
        val members = type.fields.mapIndexed { index, field -> IrMember(field.name, lower(field.type), layout.offsets[index]) }
        val result = IrStruct(type.name, members, layout.size, layout.alignment)
        module.structs.add(result)
        result
    }

    fun image(type: TextureType): IrImage {
        val kind = type.kind
        val dim = when {
            kind.isBuffer -> ImageDim.Buffer
            kind.isCube -> ImageDim.Cube
            kind == TextureKind.Texture3D -> ImageDim.Dim3D
            kind.dimensions == 1 -> ImageDim.Dim1D
            else -> ImageDim.Dim2D
        }
        val sampled = when (type.sampleType.kind) {
            ScalarKind.Half -> SampledKind.Half
            ScalarKind.Int -> SampledKind.SInt
            ScalarKind.UInt -> SampledKind.UInt
            ScalarKind.Short -> SampledKind.SShort
            ScalarKind.UShort -> SampledKind.UShort
            else -> SampledKind.Float
        }
        val access = when (type.access) {
            TextureAccess.Sample -> ImageAccess.Sample
            TextureAccess.Read -> ImageAccess.Read
            TextureAccess.Write -> ImageAccess.Write
            TextureAccess.ReadWrite -> ImageAccess.ReadWrite
        }
        return IrImage(dim, sampled, kind.isArray, kind.isMultisampled, kind.isDepth, access)
    }

    fun constant(value: ConstValue): IrConstant {
        val type = lower(value.type)
        return when (value) {
            is ScalarConstant -> scalarConstant(type as IrScalar, value)
            is EnumConstant -> Constants.integer(type as IrScalar, value.value)
            is ZeroConstant -> Constants.zero(type)
            is CompositeConstant -> ConstantComposite(type, value.elements.map { constant(it) })
        }
    }

    private fun scalarConstant(type: IrScalar, value: ScalarConstant): IrConstant = when (type) {
        is IrBool -> ConstantScalar.bool(value.asBoolean)
        is IrInt -> ConstantScalar.int(type, value.bits)
        is IrFloat -> ConstantScalar.float(type, value.asDouble)
    }

    fun zero(type: IrType): IrConstant = if (type is IrScalar) Constants.zero(type) else ConstantNull(type)
}
