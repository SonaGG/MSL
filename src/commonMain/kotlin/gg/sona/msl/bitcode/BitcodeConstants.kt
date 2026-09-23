package gg.sona.msl.bitcode

object BitcodeConstants {
    const val END_BLOCK = 0
    const val ENTER_SUBBLOCK = 1
    const val DEFINE_ABBREV = 2
    const val UNABBREV_RECORD = 3

    const val MODULE_BLOCK = 8
    const val PARAMATTR_BLOCK = 9
    const val PARAMATTR_GROUP_BLOCK = 10
    const val CONSTANTS_BLOCK = 11
    const val FUNCTION_BLOCK = 12
    const val VALUE_SYMTAB_BLOCK = 14
    const val METADATA_BLOCK = 15
    const val TYPE_BLOCK = 17

    const val MODULE_VERSION = 1
    const val MODULE_TRIPLE = 2
    const val MODULE_DATALAYOUT = 3
    const val MODULE_GLOBALVAR = 7
    const val MODULE_FUNCTION = 8

    const val PARAMATTR_ENTRY = 2
    const val PARAMATTR_GROUP_ENTRY = 3

    const val TYPE_NUMENTRY = 1
    const val TYPE_VOID = 2
    const val TYPE_FLOAT = 3
    const val TYPE_DOUBLE = 4
    const val TYPE_LABEL = 5
    const val TYPE_INTEGER = 7
    const val TYPE_POINTER = 8
    const val TYPE_HALF = 10
    const val TYPE_ARRAY = 11
    const val TYPE_VECTOR = 12
    const val TYPE_METADATA = 16
    const val TYPE_STRUCT_ANON = 18
    const val TYPE_STRUCT_NAME = 19
    const val TYPE_STRUCT_NAMED = 20
    const val TYPE_FUNCTION = 21

    const val CST_SETTYPE = 1
    const val CST_NULL = 2
    const val CST_UNDEF = 3
    const val CST_INTEGER = 4
    const val CST_FLOAT = 6
    const val CST_AGGREGATE = 7

    const val FUNC_DECLAREBLOCKS = 1
    const val FUNC_BINOP = 2
    const val FUNC_CAST = 3
    const val FUNC_RET = 10
    const val FUNC_BR = 11
    const val FUNC_SWITCH = 12
    const val FUNC_UNREACHABLE = 15
    const val FUNC_PHI = 16
    const val FUNC_ALLOCA = 19
    const val FUNC_LOAD = 20
    const val FUNC_EXTRACTVAL = 26
    const val FUNC_INSERTVAL = 27
    const val FUNC_CMP2 = 28
    const val FUNC_VSELECT = 29
    const val FUNC_CALL = 34
    const val FUNC_ATOMICRMW = 38
    const val FUNC_GEP = 43
    const val FUNC_STORE = 44
    const val FUNC_CMPXCHG = 46

    const val VST_ENTRY = 1

    const val METADATA_STRING = 1
    const val METADATA_VALUE = 2
    const val METADATA_NODE = 3
    const val METADATA_NAME = 4
    const val METADATA_DISTINCT_NODE = 5
    const val METADATA_NAMED_NODE = 10
    const val METADATA_KIND = 6
}
