package gg.sona.msl.ir

import kotlin.jvm.JvmInline

@JvmInline
value class Opcode(val id: Int) {
    val name: String
        get() = NAMES.getOrElse(id) { "op$id" }

    val isTerminator: Boolean
        get() = id in Branch.id..Unreachable.id

    override fun toString(): String = name

    companion object {
        private val NAMES = ArrayList<String>()

        private fun op(name: String): Opcode {
            NAMES.add(name)
            return Opcode(NAMES.size - 1)
        }

        val IAdd = op("iadd")
        val ISub = op("isub")
        val IMul = op("imul")
        val SDiv = op("sdiv")
        val UDiv = op("udiv")
        val SRem = op("srem")
        val URem = op("urem")
        val FAdd = op("fadd")
        val FSub = op("fsub")
        val FMul = op("fmul")
        val FDiv = op("fdiv")
        val FRem = op("frem")
        val FNeg = op("fneg")
        val INeg = op("ineg")
        val And = op("and")
        val Or = op("or")
        val Xor = op("xor")
        val Not = op("not")
        val Shl = op("shl")
        val LShr = op("lshr")
        val AShr = op("ashr")
        val LogicalAnd = op("land")
        val LogicalOr = op("lor")
        val LogicalNot = op("lnot")
        val LogicalEqual = op("leq")
        val LogicalNotEqual = op("lne")
        val IEqual = op("ieq")
        val INotEqual = op("ine")
        val SLess = op("slt")
        val SLessEqual = op("sle")
        val SGreater = op("sgt")
        val SGreaterEqual = op("sge")
        val ULess = op("ult")
        val ULessEqual = op("ule")
        val UGreater = op("ugt")
        val UGreaterEqual = op("uge")
        val FEqual = op("feq")
        val FNotEqual = op("fne")
        val FLess = op("flt")
        val FLessEqual = op("fle")
        val FGreater = op("fgt")
        val FGreaterEqual = op("fge")
        val Select = op("select")
        val SToF = op("stof")
        val UToF = op("utof")
        val FToS = op("ftos")
        val FToU = op("ftou")
        val FConvert = op("fconvert")
        val SConvert = op("sconvert")
        val UConvert = op("uconvert")
        val Bitcast = op("bitcast")
        val CompositeConstruct = op("construct")
        val CompositeExtract = op("extract")
        val CompositeInsert = op("insert")
        val VectorShuffle = op("shuffle")
        val VectorExtractDynamic = op("extractelement")
        val VectorInsertDynamic = op("insertelement")
        val MatrixTimesVector = op("mat_x_vec")
        val VectorTimesMatrix = op("vec_x_mat")
        val MatrixTimesMatrix = op("mat_x_mat")
        val MatrixTimesScalar = op("mat_x_scalar")
        val VectorTimesScalar = op("vec_x_scalar")
        val Variable = op("variable")
        val Load = op("load")
        val Store = op("store")
        val AccessChain = op("access")
        val PtrOffset = op("ptroffset")
        val ArrayLength = op("arraylength")
        val Call = op("call")
        val Intrinsic = op("intrinsic")
        val Phi = op("phi")
        val Branch = op("br")
        val CondBranch = op("condbr")
        val Switch = op("switch")
        val Return = op("ret")
        val Unreachable = op("unreachable")
    }
}
