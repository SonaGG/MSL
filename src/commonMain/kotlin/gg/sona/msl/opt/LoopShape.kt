package gg.sona.msl.opt

import gg.sona.msl.ir.Block

class LoopShape(
    val header: Block,
    val condition: Block,
    val body: Block,
    val latch: Block,
    val merge: Block,
    val preheader: Block,
    val blocks: Set<Block>,
    val trips: Int,
)
