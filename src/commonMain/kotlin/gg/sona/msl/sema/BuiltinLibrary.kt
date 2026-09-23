package gg.sona.msl.sema

import gg.sona.msl.hir.HConvert
import gg.sona.msl.hir.HExpr
import gg.sona.msl.hir.HIntrinsic
import gg.sona.msl.ir.Intrinsic
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.Type
import gg.sona.msl.types.VectorType
import gg.sona.msl.types.VoidType

object BuiltinLibrary {
    private val table = HashMap<String, MutableList<BuiltinSignature>>()

    fun lookup(name: String): List<BuiltinSignature>? = table[name]

    fun contains(name: String): Boolean = name in table

    private val T = BuiltinParameter.T
    private val S = BuiltinParameter.Scalar

    private fun define(
        name: String,
        parameters: List<BuiltinParameter>,
        result: BuiltinParameter,
        elements: ElementClass,
        shape: ShapeClass = ShapeClass.ScalarOrVector,
        build: (BuiltinCall) -> HExpr,
    ) {
        table.getOrPut(name) { ArrayList() }.add(BuiltinSignature(parameters, result, elements, shape, build))
    }

    private fun defineFixed(name: String, parameters: List<Type>, result: Type, build: (BuiltinCall) -> HExpr) {
        define(name, parameters.map { fixed(it) }, fixed(result), ElementClass.Any, ShapeClass.Scalar, build)
    }

    private fun defineSimple(
        name: String,
        parameters: List<BuiltinParameter>,
        result: BuiltinParameter,
        build: (BuiltinCall) -> HExpr,
    ) {
        define(name, parameters, result, ElementClass.Any, ShapeClass.Scalar, build)
    }

    private fun defineEach(
        intrinsics: Map<String, Intrinsic>,
        parameters: List<BuiltinParameter>,
        result: BuiltinParameter,
        elements: ElementClass,
    ) {
        for ((name, intrinsic) in intrinsics) define(name, parameters, result, elements, build = intrinsic(intrinsic))
    }

    private fun intrinsic(intrinsic: Intrinsic): (BuiltinCall) -> HExpr =
        { call -> HIntrinsic(intrinsic, call.arguments, call.resultType, call.location) }

    private fun signed(signed: Intrinsic, unsigned: Intrinsic): (BuiltinCall) -> HExpr = { call ->
        val kind = ConversionRules.scalarOf(call.typeArgument)!!.kind
        HIntrinsic(if (kind.isSigned) signed else unsigned, call.arguments, call.resultType, call.location)
    }

    private fun fixed(type: Type) = BuiltinParameter.fixed(type)

    private val float2 = VectorType.of(ScalarType.Float, 2)
    private val float4 = VectorType.of(ScalarType.Float, 4)
    private val half2 = VectorType.of(ScalarType.Half, 2)
    private val half4 = VectorType.of(ScalarType.Half, 4)

    init {
        val floatUnary = mapOf(
            "acos" to Intrinsic.Acos,
            "acosh" to Intrinsic.Acosh,
            "asin" to Intrinsic.Asin,
            "asinh" to Intrinsic.Asinh,
            "atan" to Intrinsic.Atan,
            "atanh" to Intrinsic.Atanh,
            "ceil" to Intrinsic.Ceil,
            "cos" to Intrinsic.Cos,
            "cosh" to Intrinsic.Cosh,
            "cospi" to Intrinsic.Cospi,
            "exp" to Intrinsic.Exp,
            "exp2" to Intrinsic.Exp2,
            "exp10" to Intrinsic.Exp10,
            "fabs" to Intrinsic.FAbs,
            "floor" to Intrinsic.Floor,
            "fract" to Intrinsic.Fract,
            "log" to Intrinsic.Log,
            "log2" to Intrinsic.Log2,
            "log10" to Intrinsic.Log10,
            "rint" to Intrinsic.Rint,
            "round" to Intrinsic.Round,
            "rsqrt" to Intrinsic.Rsqrt,
            "sin" to Intrinsic.Sin,
            "sinh" to Intrinsic.Sinh,
            "sinpi" to Intrinsic.Sinpi,
            "sqrt" to Intrinsic.Sqrt,
            "tan" to Intrinsic.Tan,
            "tanh" to Intrinsic.Tanh,
            "tanpi" to Intrinsic.Tanpi,
            "trunc" to Intrinsic.Trunc,
            "saturate" to Intrinsic.Saturate,
            "sign" to Intrinsic.FSign,
            "dfdx" to Intrinsic.Dfdx,
            "dfdy" to Intrinsic.Dfdy,
            "fwidth" to Intrinsic.Fwidth,
            "normalize" to Intrinsic.Normalize,
        )
        defineEach(floatUnary, listOf(T), T, ElementClass.Float)

        val floatBinary = mapOf(
            "atan2" to Intrinsic.Atan2,
            "copysign" to Intrinsic.Copysign,
            "fdim" to Intrinsic.Fdim,
            "fmax" to Intrinsic.FMax,
            "fmin" to Intrinsic.FMin,
            "fmod" to Intrinsic.Fmod,
            "pow" to Intrinsic.Pow,
            "powr" to Intrinsic.Powr,
            "step" to Intrinsic.Step,
            "reflect" to Intrinsic.Reflect,
            "max" to Intrinsic.FMax,
            "min" to Intrinsic.FMin,
        )
        defineEach(floatBinary, listOf(T, T), T, ElementClass.Float)

        val floatTernary = mapOf(
            "fma" to Intrinsic.Fma,
            "mix" to Intrinsic.Mix,
            "smoothstep" to Intrinsic.Smoothstep,
            "clamp" to Intrinsic.FClamp,
            "faceforward" to Intrinsic.FaceForward,
            "fmax3" to Intrinsic.Max3,
            "fmin3" to Intrinsic.Min3,
            "fmedian3" to Intrinsic.Median3,
            "max3" to Intrinsic.Max3,
            "min3" to Intrinsic.Min3,
            "median3" to Intrinsic.Median3,
        )
        defineEach(floatTernary, listOf(T, T, T), T, ElementClass.Float)

        define("abs", listOf(T), T, ElementClass.Float, build = intrinsic(Intrinsic.FAbs))
        define("abs", listOf(T), T, ElementClass.Integer) { call ->
            if (ConversionRules.scalarOf(call.typeArgument)!!.kind.isSigned) {
                HIntrinsic(Intrinsic.SAbs, call.arguments, call.resultType, call.location)
            } else {
                call.arguments[0]
            }
        }

        val integerUnary = mapOf(
            "clz" to Intrinsic.Clz,
            "ctz" to Intrinsic.Ctz,
            "popcount" to Intrinsic.Popcount,
            "reverse_bits" to Intrinsic.ReverseBits,
        )
        defineEach(integerUnary, listOf(T), T, ElementClass.Integer)

        define("absdiff", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SAbsDiff, Intrinsic.UAbsDiff))
        define("addsat", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SAddSat, Intrinsic.UAddSat))
        define("subsat", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SSubSat, Intrinsic.USubSat))
        define("hadd", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SHAdd, Intrinsic.UHAdd))
        define("rhadd", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SRHAdd, Intrinsic.URHAdd))
        define("mulhi", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SMulHi, Intrinsic.UMulHi))
        define("mul24", listOf(T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Mul24))
        define("rotate", listOf(T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Rotate))
        define("max", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SMax, Intrinsic.UMax))
        define("min", listOf(T, T), T, ElementClass.Integer, build = signed(Intrinsic.SMin, Intrinsic.UMin))
        define("clamp", listOf(T, T, T), T, ElementClass.Integer, build = signed(Intrinsic.SClamp, Intrinsic.UClamp))
        define("madhi", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.MadHi))
        define("madsat", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.MadSat))
        define("mad24", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Mad24))
        define("max3", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Max3))
        define("min3", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Min3))
        define("median3", listOf(T, T, T), T, ElementClass.Integer, build = intrinsic(Intrinsic.Median3))
        define(
            "extract_bits",
            listOf(T, fixed(ScalarType.UInt), fixed(ScalarType.UInt)),
            T,
            ElementClass.Integer,
            build = signed(Intrinsic.SExtractBits, Intrinsic.UExtractBits),
        )
        define(
            "insert_bits",
            listOf(T, T, fixed(ScalarType.UInt), fixed(ScalarType.UInt)),
            T,
            ElementClass.Integer,
            build = intrinsic(Intrinsic.InsertBits),
        )

        define("ldexp", listOf(T, BuiltinParameter.IntOfT), T, ElementClass.Float, build = intrinsic(Intrinsic.Ldexp))
        define(
            "frexp",
            listOf(T, BuiltinParameter.ReferenceToIntOfT),
            T,
            ElementClass.Float,
            build = intrinsic(Intrinsic.Frexp),
        )
        define(
            "modf",
            listOf(T, BuiltinParameter.ReferenceToT),
            T,
            ElementClass.Float,
            build = intrinsic(Intrinsic.Modf),
        )
        define(
            "sincos",
            listOf(T, BuiltinParameter.ReferenceToT),
            T,
            ElementClass.Float,
            build = intrinsic(Intrinsic.Sincos),
        )

        val classification = mapOf(
            "isfinite" to Intrinsic.IsFinite,
            "isinf" to Intrinsic.IsInf,
            "isnan" to Intrinsic.IsNan,
            "isnormal" to Intrinsic.IsNormal,
            "signbit" to Intrinsic.SignBit,
        )
        for ((name, intrinsic) in classification) {
            define(name, listOf(T), BuiltinParameter.BoolOfT, ElementClass.Float, build = intrinsic(intrinsic))
        }

        define("dot", listOf(T, T), S, ElementClass.Float, build = intrinsic(Intrinsic.Dot))
        define("cross", listOf(T, T), T, ElementClass.Float, ShapeClass.Vector3, intrinsic(Intrinsic.Cross))
        define("distance", listOf(T, T), S, ElementClass.Float, build = intrinsic(Intrinsic.Distance))
        define(
            "distance_squared",
            listOf(T, T),
            S,
            ElementClass.Float,
            build = intrinsic(Intrinsic.DistanceSquared),
        )
        define("length", listOf(T), S, ElementClass.Float, build = intrinsic(Intrinsic.Length))
        define("length_squared", listOf(T), S, ElementClass.Float, build = intrinsic(Intrinsic.LengthSquared))
        define("refract", listOf(T, T, S), T, ElementClass.Float, build = intrinsic(Intrinsic.Refract))

        define("all", listOf(T), fixed(ScalarType.Bool), ElementClass.Bool, build = intrinsic(Intrinsic.All))
        define("any", listOf(T), fixed(ScalarType.Bool), ElementClass.Bool, build = intrinsic(Intrinsic.Any))
        val selectParameters = listOf(T, T, BuiltinParameter.BoolOfT)
        define("select", selectParameters, T, ElementClass.Any, build = intrinsic(Intrinsic.Select))

        val determinant = intrinsic(Intrinsic.Determinant)
        define("determinant", listOf(T), S, ElementClass.Float, ShapeClass.SquareMatrix, determinant)
        define(
            "transpose",
            listOf(T),
            BuiltinParameter.Transposed,
            ElementClass.Float,
            ShapeClass.Matrix,
            intrinsic(Intrinsic.Transpose),
        )

        definePacking()

        val memFlags = listOf<Type>(BuiltinEnums.memFlags)
        defineFixed("threadgroup_barrier", memFlags, VoidType, intrinsic(Intrinsic.ThreadgroupBarrier))
        defineFixed("simdgroup_barrier", listOf(BuiltinEnums.memFlags), VoidType, intrinsic(Intrinsic.SimdgroupBarrier))
        define(
            "atomic_thread_fence",
            listOf(fixed(BuiltinEnums.memFlags), fixed(BuiltinEnums.memoryOrder)),
            fixed(VoidType),
            ElementClass.Any,
            ShapeClass.Scalar,
            intrinsic(Intrinsic.MemoryFence),
        )
        define(
            "atomic_thread_fence",
            listOf(fixed(BuiltinEnums.memFlags), fixed(BuiltinEnums.memoryOrder), fixed(BuiltinEnums.threadScope)),
            fixed(VoidType),
            ElementClass.Any,
            ShapeClass.Scalar,
        ) { call -> HIntrinsic(Intrinsic.MemoryFence, call.arguments.take(2), call.resultType, call.location) }
        defineFixed("discard_fragment", emptyList(), VoidType, intrinsic(Intrinsic.Discard))

        defineSimd()
    }

    private fun definePacking() {
        val uint = ScalarType.UInt
        defineFixed("pack_float_to_unorm4x8", listOf(float4), uint, intrinsic(Intrinsic.PackUnorm4x8))
        defineFixed("pack_float_to_snorm4x8", listOf(float4), uint, intrinsic(Intrinsic.PackSnorm4x8))
        defineFixed("pack_float_to_unorm2x16", listOf(float2), uint, intrinsic(Intrinsic.PackUnorm2x16))
        defineFixed("pack_float_to_snorm2x16", listOf(float2), uint, intrinsic(Intrinsic.PackSnorm2x16))
        defineFixed("pack_half_to_unorm4x8", listOf(half4), uint, widening(Intrinsic.PackUnorm4x8, float4))
        defineFixed("pack_half_to_snorm4x8", listOf(half4), uint, widening(Intrinsic.PackSnorm4x8, float4))
        defineFixed("pack_half_to_unorm2x16", listOf(half2), uint, widening(Intrinsic.PackUnorm2x16, float2))
        defineFixed("pack_half_to_snorm2x16", listOf(half2), uint, widening(Intrinsic.PackSnorm2x16, float2))
        defineFixed("unpack_unorm4x8_to_float", listOf(uint), float4, intrinsic(Intrinsic.UnpackUnorm4x8))
        defineFixed("unpack_snorm4x8_to_float", listOf(uint), float4, intrinsic(Intrinsic.UnpackSnorm4x8))
        defineFixed("unpack_unorm2x16_to_float", listOf(uint), float2, intrinsic(Intrinsic.UnpackUnorm2x16))
        defineFixed("unpack_snorm2x16_to_float", listOf(uint), float2, intrinsic(Intrinsic.UnpackSnorm2x16))
        defineFixed("unpack_unorm4x8_to_half", listOf(uint), half4, narrowing(Intrinsic.UnpackUnorm4x8, float4))
        defineFixed("unpack_snorm4x8_to_half", listOf(uint), half4, narrowing(Intrinsic.UnpackSnorm4x8, float4))
        defineFixed("unpack_unorm2x16_to_half", listOf(uint), half2, narrowing(Intrinsic.UnpackUnorm2x16, float2))
        defineFixed("unpack_snorm2x16_to_half", listOf(uint), half2, narrowing(Intrinsic.UnpackSnorm2x16, float2))
    }

    private fun widening(intrinsic: Intrinsic, wide: Type): (BuiltinCall) -> HExpr = { call ->
        val argument = HConvert(call.arguments[0], wide, call.location)
        HIntrinsic(intrinsic, listOf(argument), call.resultType, call.location)
    }

    private fun narrowing(intrinsic: Intrinsic, wide: Type): (BuiltinCall) -> HExpr = { call ->
        HConvert(HIntrinsic(intrinsic, call.arguments, wide, call.location), call.resultType, call.location)
    }

    private fun defineSimd() {
        val ushort = fixed(ScalarType.UShort)
        val bool = fixed(ScalarType.Bool)
        val ulong = fixed(ScalarType.ULong)
        val reductions = mapOf(
            "simd_sum" to Intrinsic.SimdSum,
            "simd_product" to Intrinsic.SimdProduct,
            "simd_min" to Intrinsic.SimdMin,
            "simd_max" to Intrinsic.SimdMax,
            "simd_prefix_inclusive_sum" to Intrinsic.SimdPrefixInclusiveSum,
            "simd_prefix_inclusive_product" to Intrinsic.SimdPrefixInclusiveProduct,
            "simd_prefix_exclusive_sum" to Intrinsic.SimdPrefixExclusiveSum,
            "simd_prefix_exclusive_product" to Intrinsic.SimdPrefixExclusiveProduct,
        )
        defineEach(reductions, listOf(T), T, ElementClass.Numeric)
        val bitwise = mapOf(
            "simd_and" to Intrinsic.SimdAnd,
            "simd_or" to Intrinsic.SimdOr,
            "simd_xor" to Intrinsic.SimdXor,
        )
        defineEach(bitwise, listOf(T), T, ElementClass.Integer)
        val lanes = mapOf(
            "simd_broadcast" to Intrinsic.SimdBroadcast,
            "simd_shuffle" to Intrinsic.SimdShuffle,
            "simd_shuffle_up" to Intrinsic.SimdShuffleUp,
            "simd_shuffle_down" to Intrinsic.SimdShuffleDown,
            "simd_shuffle_xor" to Intrinsic.SimdShuffleXor,
            "quad_broadcast" to Intrinsic.QuadBroadcast,
            "quad_shuffle" to Intrinsic.QuadShuffle,
            "quad_shuffle_xor" to Intrinsic.QuadShuffleXor,
        )
        defineEach(lanes, listOf(T, ushort), T, ElementClass.Any)
        define("simd_broadcast_first", listOf(T), T, ElementClass.Any, build = intrinsic(Intrinsic.SimdBroadcastFirst))
        defineSimple("simd_ballot", listOf(bool), ulong, intrinsic(Intrinsic.SimdBallot))
        defineSimple("simd_all", listOf(bool), bool, intrinsic(Intrinsic.SimdAll))
        defineSimple("simd_any", listOf(bool), bool, intrinsic(Intrinsic.SimdAny))
        defineSimple("simd_is_first", emptyList(), bool, intrinsic(Intrinsic.SimdIsFirst))
        defineSimple("simd_active_threads_mask", emptyList(), ulong, intrinsic(Intrinsic.SimdActiveThreadsMask))
    }
}
