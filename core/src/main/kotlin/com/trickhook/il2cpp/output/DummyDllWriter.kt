package com.trickhook.il2cpp.output

import com.trickhook.il2cpp.dotnet.AssemblyBuilder
import com.trickhook.il2cpp.dotnet.ByteBuffer
import com.trickhook.il2cpp.dotnet.Elem
import com.trickhook.il2cpp.dotnet.SigType
import com.trickhook.il2cpp.dotnet.Tbl
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.il2cpp.Il2CppType
import com.trickhook.il2cpp.il2cpp.Il2CppTypeEnum
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition

/**
 * Gera o conjunto DummyDll: um assembly .NET por imagem do jogo, com todos os
 * tipos, campos, metodos, propriedades e eventos como stubs, anotados com o
 * endereco, o offset no arquivo e o token que cada membro tem no binario.
 *
 * O dumper de PC faz isto com Mono.Cecil; aqui a montagem vai para o emissor
 * proprio em [com.trickhook.il2cpp.dotnet].
 *
 * Os assemblies sao entregues um de cada vez por [forEachAssembly]: o
 * Assembly-CSharp de um jogo grande passa de 80 MB, e segurar os 56 ao mesmo
 * tempo estouraria a memoria de um celular.
 */
class DummyDllWriter(private val executor: Il2CppExecutor) {

    private val metadata = executor.metadata
    private val binary = executor.binary

    /**
     * Quantos parametros genericos o tipo e o metodo em construcao declaram.
     * Uma assinatura so pode citar !n ou !!n dentro desse alcance; fora dele o
     * leitor nao consegue resolver o parametro e o arquivo fica ilegivel.
     */
    private var typeParamCount = 0
    private var methodParamCount = 0

    /** Referencias genericas que caem fora do alcance e viraram object. */
    var outOfRangeGenericParams = 0
        private set

    /** Em qual imagem cada typeDef vive, para saber o que e local e o que e referencia. */
    private val imageOfType = IntArray(metadata.typeDefs.size) { -1 }

    init {
        for ((imageIndex, image) in metadata.imageDefs.withIndex()) {
            val end = image.typeStart + image.typeCount
            for (i in image.typeStart until end) {
                if (i in imageOfType.indices) imageOfType[i] = imageIndex
            }
        }
    }

    /** Estado por assembly, descartado assim que o arquivo e gravado. */
    private inner class Context(val imageIndex: Int) {
        val image = metadata.imageDefs[imageIndex]
        val imageName: String = metadata.getString(image.nameIndex)
        val assemblyName: String =
            metadata.getString(metadata.assemblyDefs[image.assemblyIndex].aname.nameIndex)

        val builder: AssemblyBuilder
        val localTypes = HashMap<Int, AssemblyBuilder.TypeDef>()

        private val assemblyRefs = HashMap<String, Int>()
        private val typeRefCache = HashMap<Int, Int>()

        val corlib: Int
        val dummyRef: Int
        val tokenCtor: AssemblyBuilder.MemberRefHandle
        val addressCtor: AssemblyBuilder.MemberRefHandle
        val fieldOffsetCtor: AssemblyBuilder.MemberRefHandle
        val metadataOffsetCtor: AssemblyBuilder.MemberRefHandle

        init {
            val aname = metadata.assemblyDefs[image.assemblyIndex].aname
            val version = if (aname.build >= 0) {
                intArrayOf(aname.major, aname.minor, aname.build, aname.revision)
            } else {
                // O __Generated nao tem versao; o dumper de PC usa 3.7.1.6.
                intArrayOf(3, 7, 1, 6)
            }
            builder = AssemblyBuilder(assemblyName, imageName, version)
            corlib = assemblyRef("mscorlib")
            dummyRef = assemblyRef("Il2CppDummyDll")
            tokenCtor = attributeCtor("TokenAttribute")
            addressCtor = attributeCtor("AddressAttribute")
            fieldOffsetCtor = attributeCtor("FieldOffsetAttribute")
            metadataOffsetCtor = attributeCtor("MetadataOffsetAttribute")
        }

        fun assemblyRef(name: String): Int = assemblyRefs.getOrPut(name) {
            val def = metadata.assemblyDefs.firstOrNull {
                metadata.getString(it.aname.nameIndex) == name
            }
            val a = def?.aname
            if (a != null && a.build >= 0) {
                builder.addAssemblyRef(name, a.major, a.minor, a.build, a.revision)
            } else {
                builder.addAssemblyRef(name, 0, 0, 0, 0)
            }
        }

        private fun attributeCtor(name: String) =
            builder.addAttributeCtorRef(builder.addTypeRef(dummyRef, "Il2CppDummyDll", name))

        /**
         * Referencia a um tipo que vive noutro assembly. Tipos aninhados
         * precisam da cadeia inteira, porque o escopo de um aninhado e o tipo
         * que o contem, nao o assembly.
         */
        fun typeRefFor(typeIndex: Int): Int = typeRefCache.getOrPut(typeIndex) {
            val typeDef = metadata.typeDefs[typeIndex]
            val name = metadata.getString(typeDef.nameIndex)
            if (typeDef.declaringTypeIndex != -1) {
                val outer = binary.types[typeDef.declaringTypeIndex]
                val outerDef = executor.getTypeDefinitionFromIl2CppType(outer)
                val outerIndex = metadata.typeDefs.indexOf(outerDef)
                builder.addNestedTypeRef(typeRefFor(outerIndex), name)
            } else {
                val owner = imageOfType.getOrElse(typeIndex) { -1 }
                val scope = if (owner >= 0) {
                    assemblyRef(
                        metadata.getString(
                            metadata.assemblyDefs[metadata.imageDefs[owner].assemblyIndex].aname.nameIndex
                        )
                    )
                } else {
                    corlib
                }
                builder.addTypeRef(scope, metadata.getString(typeDef.namespaceIndex), name)
            }
        }
    }

    /**
     * Gera cada assembly e entrega ao consumidor. O builder e liberado logo
     * depois, para o pico de memoria ser o do maior assembly e nao a soma.
     */
    fun forEachAssembly(consumer: (name: String, bytes: ByteArray) -> Unit) {
        for (imageIndex in metadata.imageDefs.indices) {
            val ctx = Context(imageIndex)
            declareTypes(ctx)
            fillTypes(ctx)
            consumer(ctx.imageName, ctx.builder.build())
        }
    }

    val assemblyCount: Int get() = metadata.imageDefs.size

    /** Primeira passada: so declara os tipos, para que todos tenham RID. */
    private fun declareTypes(ctx: Context) {
        val end = ctx.image.typeStart + ctx.image.typeCount
        for (i in ctx.image.typeStart until end) {
            if (i !in metadata.typeDefs.indices) continue
            val typeDef = metadata.typeDefs[i]
            val name = metadata.getString(typeDef.nameIndex)
            val namespace = if (typeDef.declaringTypeIndex != -1) {
                ""
            } else {
                metadata.getString(typeDef.namespaceIndex)
            }
            ctx.localTypes[i] = ctx.builder.defineType(namespace, name, typeDef.flags, null)
        }
    }

    /** Segunda passada: agora que todo tipo tem RID, monta o conteudo. */
    private fun fillTypes(ctx: Context) {
        for ((typeIndex, target) in ctx.localTypes) {
            val typeDef = metadata.typeDefs[typeIndex]
            fillType(ctx, typeIndex, typeDef, target)
        }
    }

    private fun fillType(
        ctx: Context,
        typeIndex: Int,
        typeDef: Il2CppTypeDefinition,
        target: AssemblyBuilder.TypeDef
    ) {
        typeParamCount = genericParamCount(typeDef.genericContainerIndex)
        methodParamCount = 0
        if (typeDef.parentIndex >= 0) {
            target.baseType = sig(ctx, binary.types[typeDef.parentIndex])
        }
        if (typeDef.declaringTypeIndex != -1) {
            val outer = executor.getTypeDefinitionFromIl2CppType(binary.types[typeDef.declaringTypeIndex])
            val outerIndex = metadata.typeDefs.indexOf(outer)
            ctx.localTypes[outerIndex]?.let { target.declaringType = it }
        }
        for (k in 0 until typeDef.interfacesCount) {
            val index = metadata.interfaceIndices[typeDef.interfacesStart + k]
            target.interfaces += sig(ctx, binary.types[index])
        }
        addGenericParams(ctx, typeDef.genericContainerIndex, target.genericParams)
        typeParamCount = target.genericParams.size

        target.attributes += AssemblyBuilder.AttributeUse(
            ctx.tokenCtor, listOf("Token" to "0x${typeDef.token.toString(16).uppercase()}")
        )

        addFields(ctx, typeIndex, typeDef, target)
        addMethods(ctx, typeDef, target)
        addProperties(ctx, typeDef, target)
        addEvents(ctx, typeDef, target)
    }

    /**
     * Propriedades e eventos nao guardam o proprio tipo: ele sai da assinatura
     * do acessor. O indice do acessor e relativo ao inicio dos metodos do
     * tipo, e -1 quando o acessor nao existe.
     */
    private fun addProperties(ctx: Context, typeDef: Il2CppTypeDefinition, target: AssemblyBuilder.TypeDef) {
        val end = typeDef.propertyStart + typeDef.propertyCount
        for (i in typeDef.propertyStart until end) {
            if (i !in metadata.propertyDefs.indices) continue
            val def = metadata.propertyDefs[i]
            val getter = target.methods.getOrNull(def.get)
            val setter = target.methods.getOrNull(def.set)
            val type = when {
                getter != null -> getter.returnType
                setter != null -> setter.parameters.lastOrNull()
                else -> null
            } ?: continue
            val hasThis = (getter ?: setter)?.hasThis ?: true
            val property = AssemblyBuilder.PropertyDef(
                metadata.getString(def.nameIndex), def.attrs, type, hasThis
            )
            property.getter = getter
            property.setter = setter
            property.attributes += AssemblyBuilder.AttributeUse(
                ctx.tokenCtor, listOf("Token" to "0x${def.token.toString(16).uppercase()}")
            )
            target.properties += property
        }
    }

    private fun addEvents(ctx: Context, typeDef: Il2CppTypeDefinition, target: AssemblyBuilder.TypeDef) {
        val end = typeDef.eventStart + typeDef.eventCount
        for (i in typeDef.eventStart until end) {
            if (i !in metadata.eventDefs.indices) continue
            val def = metadata.eventDefs[i]
            val event = AssemblyBuilder.EventDef(
                metadata.getString(def.nameIndex), 0, sig(ctx, binary.types[def.typeIndex])
            )
            event.adder = target.methods.getOrNull(def.add)
            event.remover = target.methods.getOrNull(def.remove)
            event.raiser = target.methods.getOrNull(def.raise)
            event.attributes += AssemblyBuilder.AttributeUse(
                ctx.tokenCtor, listOf("Token" to "0x${def.token.toString(16).uppercase()}")
            )
            target.events += event
        }
    }

    private fun addFields(
        ctx: Context,
        typeIndex: Int,
        typeDef: Il2CppTypeDefinition,
        target: AssemblyBuilder.TypeDef
    ) {
        val end = typeDef.fieldStart + typeDef.fieldCount
        for (i in typeDef.fieldStart until end) {
            if (i !in metadata.fieldDefs.indices) continue
            val fieldDef = metadata.fieldDefs[i]
            val fieldType = binary.types[fieldDef.typeIndex]
            val field = AssemblyBuilder.FieldDef(
                metadata.getString(fieldDef.nameIndex), fieldType.attrs, sig(ctx, fieldType)
            )
            field.attributes += AssemblyBuilder.AttributeUse(
                ctx.tokenCtor, listOf("Token" to "0x${fieldDef.token.toString(16).uppercase()}")
            )

            val isStatic = fieldType.attrs and FIELD_STATIC != 0
            val isLiteral = fieldType.attrs and FIELD_LITERAL != 0

            // Valor padrao: quando da para decodificar o blob ele vira uma
            // constante de verdade, que e o que um leitor espera de um const
            // ou de um membro de enum. So quando nao da e que se recorre a
            // apontar o offset onde o valor mora.
            val default = metadata.getFieldDefaultValue(i)
            if (default != null && default.dataIndex != -1) {
                val constant = decodeConstant(default.typeIndex, default.dataIndex)
                if (constant != null) {
                    field.constant = constant
                } else {
                    field.attributes += AssemblyBuilder.AttributeUse(
                        ctx.metadataOffsetCtor,
                        listOf(
                            "Offset" to "0x${
                                metadata.getDefaultValueData(default.dataIndex).toString(16).uppercase()
                            }"
                        )
                    )
                }
            }

            // O offset vale para qualquer campo nao-literal, estatico ou nao.
            if (!isLiteral) {
                val offset = binary.getFieldOffsetFromIndex(
                    typeIndex, i - typeDef.fieldStart, i,
                    typeDef.isValueType, isStatic
                )
                if (offset >= 0) {
                    field.attributes += AssemblyBuilder.AttributeUse(
                        ctx.fieldOffsetCtor,
                        listOf("Offset" to "0x${offset.toString(16).uppercase()}")
                    )
                }
            }
            target.fields += field
        }
    }

    private fun addMethods(ctx: Context, typeDef: Il2CppTypeDefinition, target: AssemblyBuilder.TypeDef) {
        val end = typeDef.methodStart + typeDef.methodCount
        for (i in typeDef.methodStart until end) {
            if (i !in metadata.methodDefs.indices) continue
            val methodDef = metadata.methodDefs[i]
            val returnType = binary.types[methodDef.returnType]
            val parameters = mutableListOf<SigType>()
            val paramDefs = mutableListOf<AssemblyBuilder.ParamDef>()
            for (p in 0 until methodDef.parameterCount) {
                val index = methodDef.parameterStart + p
                if (index !in metadata.parameterDefs.indices) continue
                val parameterDef = metadata.parameterDefs[index]
                val parameterType = binary.types[parameterDef.typeIndex]
                parameters += sig(ctx, parameterType)
                paramDefs += AssemblyBuilder.ParamDef(
                    metadata.getString(parameterDef.nameIndex), parameterType.attrs, p + 1
                )
            }

            methodParamCount = genericParamCount(methodDef.genericContainerIndex)
            val isStatic = methodDef.flags and METHOD_STATIC != 0
            val method = AssemblyBuilder.MethodDef(
                metadata.getString(methodDef.nameIndex),
                methodDef.flags, methodDef.iflags,
                sig(ctx, returnType), parameters, hasThis = !isStatic
            )
            method.params += paramDefs
            addGenericParams(ctx, methodDef.genericContainerIndex, method.genericParams)

            method.attributes += AssemblyBuilder.AttributeUse(
                ctx.tokenCtor, listOf("Token" to "0x${methodDef.token.toString(16).uppercase()}")
            )

            val isAbstract = methodDef.flags and METHOD_ABSTRACT != 0
            val pointer = if (isAbstract) 0L else binary.getMethodPointer(ctx.imageName, methodDef, i)
            if (pointer > 0L) {
                method.attributes += AssemblyBuilder.AttributeUse(
                    ctx.addressCtor,
                    listOf(
                        "RVA" to "0x${binary.rva(pointer).toString(16).uppercase()}",
                        "Offset" to "0x${binary.mapVaToOffset(pointer).toString(16).uppercase()}",
                        "VA" to "0x${pointer.toString(16).uppercase()}",
                        "Slot" to if (methodDef.slot != UNSET_SLOT) methodDef.slot.toString() else null
                    ).filter { it.second != null }
                )
            }

            method.body = when {
                isAbstract || methodDef.flags and METHOD_PINVOKE != 0 -> AssemblyBuilder.BodyKind.None
                returnType.type == Il2CppTypeEnum.IL2CPP_TYPE_VOID -> AssemblyBuilder.BodyKind.Void
                isValueTypeReturn(returnType) -> AssemblyBuilder.BodyKind.DefaultValueType
                else -> AssemblyBuilder.BodyKind.Null
            }
            target.methods += method
            methodParamCount = 0
        }
    }

    private fun genericParamCount(containerIndex: Int): Int {
        if (containerIndex < 0 || containerIndex >= metadata.genericContainers.size) return 0
        return metadata.genericContainers[containerIndex].typeArgc
    }

    private fun addGenericParams(
        ctx: Context,
        containerIndex: Int,
        into: MutableList<AssemblyBuilder.GenericParamDef>
    ) {
        if (containerIndex < 0 || containerIndex >= metadata.genericContainers.size) return
        val container = metadata.genericContainers[containerIndex]
        for (n in 0 until container.typeArgc) {
            val index = container.genericParameterStart + n
            if (index !in metadata.genericParameters.indices) continue
            val param = metadata.genericParameters[index]
            val def = AssemblyBuilder.GenericParamDef(
                metadata.getString(param.nameIndex), param.num, param.flags
            )
            for (c in 0 until param.constraintsCount) {
                val ci = param.constraintsStart + c
                if (ci !in metadata.constraintIndices.indices) continue
                def.constraints += sig(ctx, binary.types[metadata.constraintIndices[ci]])
            }
            into += def
        }
    }

    /** Um tipo por valor precisa de corpo que zere um local e devolva ele. */
    private fun isValueTypeReturn(type: Il2CppType): Boolean {
        if (type.byref) return false
        return when (type.type) {
            Il2CppTypeEnum.IL2CPP_TYPE_VOID,
            Il2CppTypeEnum.IL2CPP_TYPE_STRING,
            Il2CppTypeEnum.IL2CPP_TYPE_OBJECT,
            Il2CppTypeEnum.IL2CPP_TYPE_CLASS,
            Il2CppTypeEnum.IL2CPP_TYPE_ARRAY,
            Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY,
            Il2CppTypeEnum.IL2CPP_TYPE_VAR,
            Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> false
            Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE -> true
            Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
                val generic = binary.genericClassAt(type.genericClass) ?: return false
                val def = generic.typeDefinitionIndex.toInt()
                def >= 0 && def < metadata.typeDefs.size && metadata.typeDefs[def].isValueType
            }
            else -> true
        }
    }

    /** Converte um Il2CppType na arvore de assinatura que o emissor entende. */
    private fun sig(ctx: Context, type: Il2CppType): SigType {
        val base = sigCore(ctx, type)
        return if (type.byref) SigType.ByRef(base) else base
    }

    private fun sigCore(ctx: Context, type: Il2CppType): SigType = when (type.type) {
        Il2CppTypeEnum.IL2CPP_TYPE_VOID -> SigType.Primitive(Elem.VOID)
        Il2CppTypeEnum.IL2CPP_TYPE_BOOLEAN -> SigType.Primitive(Elem.BOOLEAN)
        Il2CppTypeEnum.IL2CPP_TYPE_CHAR -> SigType.Primitive(Elem.CHAR)
        Il2CppTypeEnum.IL2CPP_TYPE_I1 -> SigType.Primitive(Elem.I1)
        Il2CppTypeEnum.IL2CPP_TYPE_U1 -> SigType.Primitive(Elem.U1)
        Il2CppTypeEnum.IL2CPP_TYPE_I2 -> SigType.Primitive(Elem.I2)
        Il2CppTypeEnum.IL2CPP_TYPE_U2 -> SigType.Primitive(Elem.U2)
        Il2CppTypeEnum.IL2CPP_TYPE_I4 -> SigType.Primitive(Elem.I4)
        Il2CppTypeEnum.IL2CPP_TYPE_U4 -> SigType.Primitive(Elem.U4)
        Il2CppTypeEnum.IL2CPP_TYPE_I8 -> SigType.Primitive(Elem.I8)
        Il2CppTypeEnum.IL2CPP_TYPE_U8 -> SigType.Primitive(Elem.U8)
        Il2CppTypeEnum.IL2CPP_TYPE_R4 -> SigType.Primitive(Elem.R4)
        Il2CppTypeEnum.IL2CPP_TYPE_R8 -> SigType.Primitive(Elem.R8)
        Il2CppTypeEnum.IL2CPP_TYPE_STRING -> SigType.Primitive(Elem.STRING)
        Il2CppTypeEnum.IL2CPP_TYPE_OBJECT -> SigType.Primitive(Elem.OBJECT)
        Il2CppTypeEnum.IL2CPP_TYPE_I -> SigType.Primitive(Elem.I)
        Il2CppTypeEnum.IL2CPP_TYPE_U -> SigType.Primitive(Elem.U)
        Il2CppTypeEnum.IL2CPP_TYPE_TYPEDBYREF -> SigType.Primitive(Elem.TYPEDBYREF)

        Il2CppTypeEnum.IL2CPP_TYPE_PTR ->
            SigType.Pointer(sig(ctx, binary.readType(type.elementType)))

        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY ->
            SigType.SzArray(sig(ctx, binary.readType(type.elementType)))

        Il2CppTypeEnum.IL2CPP_TYPE_ARRAY -> {
            val array = binary.arrayTypeAt(type.arrayType)
            if (array == null) SigType.OBJECT
            else SigType.Array(sig(ctx, binary.readType(array.etype)), array.rank)
        }

        Il2CppTypeEnum.IL2CPP_TYPE_VAR -> genericParam(type, onMethod = false)

        Il2CppTypeEnum.IL2CPP_TYPE_MVAR -> genericParam(type, onMethod = true)

        Il2CppTypeEnum.IL2CPP_TYPE_GENERICINST -> {
            val generic = binary.genericClassAt(type.genericClass)
            if (generic == null) SigType.OBJECT else {
                val defIndex = generic.typeDefinitionIndex.toInt()
                val definition = namedFor(ctx, defIndex)
                val inst = binary.genericInstAt(generic.context.classInst)
                val args = mutableListOf<SigType>()
                if (inst != null) {
                    for (p in executor.getGenericInstTypes(inst)) args += sig(ctx, p)
                }
                if (args.isEmpty()) definition
                else SigType.GenericInstance(definition, args)
            }
        }

        Il2CppTypeEnum.IL2CPP_TYPE_CLASS, Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE ->
            namedFor(ctx, type.klassIndex)

        else -> SigType.OBJECT
    }

    /**
     * Um !n ou !!n so e valido se o dono realmente declarar aquele parametro.
     * Acontece de o indice apontar para fora - tipo aninhado que cita um
     * parametro do tipo externo, por exemplo - e nesse caso o leitor devolve
     * nulo e o assembly inteiro deixa de abrir. Melhor degradar para object.
     */
    private fun genericParam(type: Il2CppType, onMethod: Boolean): SigType {
        val number = runCatching {
            executor.getGenericParameterFromIl2CppType(type).num
        }.getOrDefault(-1)
        val limit = if (onMethod) methodParamCount else typeParamCount
        if (number < 0 || number >= limit) {
            outOfRangeGenericParams++
            return SigType.OBJECT
        }
        return SigType.GenericParam(number, onMethod)
    }

    /** Aponta para o TypeDef local se o tipo e deste assembly, senao cria TypeRef. */
    private fun namedFor(ctx: Context, typeIndex: Int): SigType.Named {
        if (typeIndex !in metadata.typeDefs.indices) {
            return SigType.Named(Tbl.TYPE_REF, ctx.typeRefFor(0), false)
        }
        val typeDef = metadata.typeDefs[typeIndex]
        val isValueType = typeDef.isValueType
        val local = ctx.localTypes[typeIndex]
        return if (local != null) {
            SigType.Named(Tbl.TYPE_DEF, local.rid, isValueType)
        } else {
            SigType.Named(Tbl.TYPE_REF, ctx.typeRefFor(typeIndex), isValueType)
        }
    }

    /**
     * Le o valor padrao do blob e o devolve no formato da tabela Constant:
     * codigo do tipo mais os bytes em little-endian. Devolve null quando o
     * blob nao e decodificavel, e ai o chamador aponta o offset.
     */
    private fun decodeConstant(typeIndex: Int, dataIndex: Int): AssemblyBuilder.Constant? {
        val type = binary.types.getOrNull(typeIndex) ?: return null
        val typeEnum = type.type ?: return null
        val reader = BinaryReader(metadata.raw)
        reader.seek(metadata.getDefaultValueData(dataIndex))
        val blob = runCatching { executor.readConstantValueFromBlob(typeEnum, reader) }.getOrNull()
            ?: return null
        val value = blob.value
        val out = ByteBuffer(8)
        val code = when (value) {
            is Boolean -> { out.u8(if (value) 1 else 0); Elem.BOOLEAN }
            is Byte -> { out.u8(value.toInt()); Elem.I1 }
            is Short -> { out.u16(value.toInt()); Elem.I2 }
            is Char -> { out.u16(value.code); Elem.CHAR }
            is Int -> { out.u32(value); Elem.I4 }
            is Long -> { out.u64(value); Elem.I8 }
            is Float -> { out.u32(java.lang.Float.floatToRawIntBits(value)); Elem.R4 }
            is Double -> { out.u64(java.lang.Double.doubleToRawLongBits(value)); Elem.R8 }
            is String -> { out.bytes(value.toByteArray(Charsets.UTF_16LE)); Elem.STRING }
            null -> { out.u32(0); Elem.CLASS }
            else -> return null
        }
        return AssemblyBuilder.Constant(code, out.toByteArray())
    }

    private companion object {
        const val FIELD_STATIC = 0x0010
        const val FIELD_LITERAL = 0x0040
        const val METHOD_STATIC = 0x0010
        const val METHOD_ABSTRACT = 0x0400
        const val METHOD_PINVOKE = 0x2000
        const val UNSET_SLOT = 65535
    }
}
