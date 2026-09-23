package gg.sona.msl.api

import gg.sona.msl.reflect.ShaderReflection

class DxilShader(val bytes: ByteArray, val shaderModel: String, val reflection: ShaderReflection)
