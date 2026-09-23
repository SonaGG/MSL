package gg.sona.msl.ast

import gg.sona.msl.lang.AddressSpace
import gg.sona.msl.source.SourceLocation

class NamedTypeSyntax(
    val name: QualifiedName,
    val templateArguments: List<TemplateArgument>?,
    val isConst: Boolean,
    val isVolatile: Boolean,
    val addressSpace: AddressSpace,
    override val location: SourceLocation,
) : TypeSyntax() {
    fun with(
        isConst: Boolean = this.isConst,
        isVolatile: Boolean = this.isVolatile,
        addressSpace: AddressSpace = this.addressSpace,
    ): NamedTypeSyntax = NamedTypeSyntax(name, templateArguments, isConst, isVolatile, addressSpace, location)

    override fun toString(): String = buildString {
        if (addressSpace != AddressSpace.Unspecified) append(addressSpace.spelling).append(' ')
        if (isConst) append("const ")
        append(name)
        if (templateArguments != null) append(templateArguments.joinToString(prefix = "<", postfix = ">"))
    }
}
