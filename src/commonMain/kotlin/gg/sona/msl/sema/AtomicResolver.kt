package gg.sona.msl.sema

import gg.sona.msl.hir.AtomicOperation
import gg.sona.msl.hir.HAtomic
import gg.sona.msl.hir.HExpr
import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.Diagnostics
import gg.sona.msl.source.SourceLocation
import gg.sona.msl.types.AtomicType
import gg.sona.msl.types.PointerType
import gg.sona.msl.types.ScalarKind
import gg.sona.msl.types.ScalarType
import gg.sona.msl.types.VoidType

class AtomicResolver(private val diagnostics: Diagnostics) {
    fun isAtomicFunction(name: String): Boolean = name in OPERATIONS

    fun resolve(name: String, arguments: List<HExpr>, location: SourceLocation): HExpr? {
        val operation = OPERATIONS.getValue(name)
        val expectedCount = when (operation) {
            AtomicOperation.Load -> 2
            AtomicOperation.CompareExchange -> 5
            else -> 3
        }
        if (arguments.size !in expectedCount..expectedCount + 1) {
            diagnostics.error(location, "'$name' expects $expectedCount arguments, got ${arguments.size}")
            return null
        }
        val pointer = arguments[0]
        val pointerType = pointer.type as? PointerType
        val atomic = pointerType?.pointee as? AtomicType
        if (pointerType == null || atomic == null) {
            diagnostics.error(pointer.location, "first argument of '$name' must be a pointer to an atomic type")
            return null
        }
        if (pointerType.addressSpace != AddressSpace.Device && pointerType.addressSpace != AddressSpace.Threadgroup) {
            diagnostics.error(pointer.location, "atomics must reside in device or threadgroup memory")
            return null
        }
        val element = atomic.element
        if (!supports(operation, element.kind)) {
            diagnostics.error(location, "'$name' is not supported for atomic<${element.kind.spelling}>")
            return null
        }
        val firstOrder = when (operation) {
            AtomicOperation.Load -> 1
            AtomicOperation.CompareExchange -> 3
            else -> 2
        }
        for (order in arguments.drop(firstOrder)) {
            if (order.type != BuiltinEnums.memoryOrder && order.type != BuiltinEnums.threadScope) {
                diagnostics.error(order.location, "expected memory_order argument")
                return null
            }
        }
        return when (operation) {
            AtomicOperation.Load -> HAtomic(operation, pointer, null, null, element, location)
            AtomicOperation.CompareExchange -> {
                val expected = arguments[1]
                val expectedType = expected.type as? PointerType
                if (expectedType == null || ConversionRules.valueType(expectedType.pointee) != element) {
                    diagnostics.error(expected.location, "expected value must be a pointer to '${element.kind.spelling}'")
                    return null
                }
                val desired = convert(arguments[2], element) ?: return null
                HAtomic(operation, pointer, desired, expected, ScalarType.Bool, location)
            }

            AtomicOperation.Store -> {
                val value = convert(arguments[1], element) ?: return null
                HAtomic(operation, pointer, value, null, VoidType, location)
            }

            else -> {
                val value = convert(arguments[1], element) ?: return null
                HAtomic(operation, pointer, value, null, element, location)
            }
        }
    }

    private fun convert(argument: HExpr, target: ScalarType): HExpr? {
        if (ConversionRules.implicitCost(argument.type, target) == null) {
            diagnostics.error(argument.location, "cannot convert '${argument.type}' to '$target'")
            return null
        }
        return ConversionRules.convert(argument, target)
    }

    private fun supports(operation: AtomicOperation, kind: ScalarKind): Boolean = when (kind) {
        ScalarKind.Int, ScalarKind.UInt -> true
        ScalarKind.Bool -> operation == AtomicOperation.Load || operation == AtomicOperation.Store ||
            operation == AtomicOperation.Exchange || operation == AtomicOperation.CompareExchange

        ScalarKind.Float -> operation == AtomicOperation.Load || operation == AtomicOperation.Store ||
            operation == AtomicOperation.Exchange || operation == AtomicOperation.Add || operation == AtomicOperation.Sub

        ScalarKind.ULong, ScalarKind.Long -> operation == AtomicOperation.Min || operation == AtomicOperation.Max ||
            operation == AtomicOperation.Load || operation == AtomicOperation.Store

        else -> false
    }

    private companion object {
        val OPERATIONS = mapOf(
            "atomic_load_explicit" to AtomicOperation.Load,
            "atomic_store_explicit" to AtomicOperation.Store,
            "atomic_exchange_explicit" to AtomicOperation.Exchange,
            "atomic_compare_exchange_weak_explicit" to AtomicOperation.CompareExchange,
            "atomic_compare_exchange_strong_explicit" to AtomicOperation.CompareExchange,
            "atomic_fetch_add_explicit" to AtomicOperation.Add,
            "atomic_fetch_sub_explicit" to AtomicOperation.Sub,
            "atomic_fetch_and_explicit" to AtomicOperation.And,
            "atomic_fetch_or_explicit" to AtomicOperation.Or,
            "atomic_fetch_xor_explicit" to AtomicOperation.Xor,
            "atomic_fetch_min_explicit" to AtomicOperation.Min,
            "atomic_fetch_max_explicit" to AtomicOperation.Max,
        )
    }
}
