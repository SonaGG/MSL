package gg.sona.msl.lower

import gg.sona.msl.ir.Value

class ElementLValue(val base: MemoryLValue, val index: Value) : LValue()
