package gg.sona.msl.hir

import gg.sona.msl.types.Type

sealed class ConstValue {
    abstract val type: Type
}
