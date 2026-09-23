package gg.sona.msl.preprocess

import gg.sona.msl.source.SourceLocation

class ConditionalFrame(
    val parentActive: Boolean,
    var anyBranchTaken: Boolean,
    var active: Boolean,
    var seenElse: Boolean,
    val location: SourceLocation,
)
