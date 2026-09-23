package gg.sona.msl.passes

import gg.sona.msl.ir.Block
import gg.sona.msl.ir.Instruction
import gg.sona.msl.ir.Value

class RenameFrame(val block: Block, val values: HashMap<Instruction, Value>)
