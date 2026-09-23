package gg.sona.msl.parse

import gg.sona.msl.ast.Attribute
import gg.sona.msl.ast.TypeSyntax
import gg.sona.msl.lex.Token

class Declarator(val type: TypeSyntax, val name: Token?, val attributes: List<Attribute>)
