package gg.sona.msl.opt

import gg.sona.msl.ir.IrModule
import gg.sona.msl.ir.StorageClass

object NameMangling {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

    fun run(module: IrModule) {
        var counter = 0
        fun next(): String {
            var value = counter++
            val name = StringBuilder()
            do {
                name.append(ALPHABET[value % ALPHABET.length])
                value /= ALPHABET.length
            } while (value > 0)
            return name.toString()
        }

        for (global in module.globals) {
            if (global.resource != null || global.interfaceInfo != null) continue
            if (global.storage != StorageClass.Private && global.storage != StorageClass.Workgroup) continue
            global.name = next()
        }
        for (struct in module.structs) {
            struct.name = next()
            struct.members.forEachIndexed { index, member -> member.name = "m$index" }
        }
        for (function in module.functions) {
            for (block in function.blocks) block.name = next()
            for (instruction in function.instructions()) instruction.name = null
        }
    }
}
