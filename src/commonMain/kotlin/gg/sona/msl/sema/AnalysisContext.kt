package gg.sona.msl.sema

import gg.sona.msl.hir.Function

class AnalysisContext(val function: Function) {
    var loopDepth = 0
    var breakableDepth = 0
}
