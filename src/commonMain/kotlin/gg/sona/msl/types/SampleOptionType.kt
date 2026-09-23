package gg.sona.msl.types

class SampleOptionType private constructor(val kind: SampleOptionKind) : Type() {
    override fun toString(): String = kind.spelling

    companion object {
        private val instances = SampleOptionKind.entries.map { SampleOptionType(it) }

        fun of(kind: SampleOptionKind): SampleOptionType = instances[kind.ordinal]
    }
}
