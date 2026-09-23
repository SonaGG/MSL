package gg.sona.msl.sema

import gg.sona.msl.ast.TypeAliasDecl

class AliasTemplateSymbol(val declaration: TypeAliasDecl, val scope: Scope) : Symbol()
