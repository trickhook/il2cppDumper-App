package com.trickhook.il2cpp.output

import com.trickhook.il2cpp.dotnet.AssemblyBuilder
import com.trickhook.il2cpp.dotnet.ByteBuffer
import com.trickhook.il2cpp.dotnet.Elem
import com.trickhook.il2cpp.dotnet.SigType
import com.trickhook.il2cpp.dotnet.Tbl
import com.trickhook.il2cpp.il2cpp.Il2CppExecutor
import com.trickhook.il2cpp.il2cpp.Il2CppType
import com.trickhook.il2cpp.il2cpp.Il2CppTypeEnum
import com.trickhook.il2cpp.io.BinaryReader
import com.trickhook.il2cpp.metadata.Il2CppTypeDefinition

/**
 * Gera o conjunto DummyDll: um assembly .NET por imagem do jogo, com todos os
 * tipos, campos, metodos, propriedades e eventos como stubs, anotados com o
 * endereco, o offset no arquivo e o token que cada membro tem no binario.
 *
 * O dumper de PC faz isto com Mono.Cecil; aqui a montagem vai para o emissor
 * proprio em [com.trickhook.il2cpp.dotnet].
 *
 * O trabalho e feito em passadas sobre a metadata, emitindo linha por linha,
 * porque um Assembly-CSharp grande tem centenas de milhares de membros e mais
 * de um milhao de atributos: montar tudo como objetos antes de serializar
 * estourava o heap do celular. Reler a metadata e barato - ela ja esta na
 * memoria -, enquanto guardar um objeto por membro nao e.
 */
class DummyDllWriter(private val executor: Il2CppExecutor) {

    private val metadata = executor.metadata
    private val binary = executor.binary

    /** Em qual imagem cada typeDef vive, para saber o que e local e o que e referencia. */
    private val imageOfType = IntArray(metadata.typeDefs.size) { -1 }

    /**
     * Quantos parametros genericos o tipo e o metodo correntes declaram. Uma
     * assinatura so pode citar !n ou !!n dentro desse alcance.
     */
    private var typeParamCount = 0
    private var methodParamCount = 0

    /** Referencias genericas que caem fora do alcance e viraram object. */
    var outOfRangeGenericParams = 0
        private set

    init {
        for ((imageIndex, image) in metadata.imageDefs.withIndex()) {
            val end = image.typeStart + image.typeCount
            for (i in image.typeStart until end) {
                if (i in imageOfType.indices) imageOfType[i] = imageIndex
            }
        }
    }

    val assemblyCount: Int get() = metadata.imageDefs.size

    private inner class Context(imageIndex: Int) {
        val image = metadata.imageDefs[imageIndex]
        val imageName: String = metadata.getString(image.nameIndex)
        val builder: AssemblyBuilder

        /** typeDef global -> RID local, e -1 para tipos de outra imagem. */
        val ridOfType = HashMap<Int, Int>()

        private val assemblyRefs = HashMap<String, Int>()
        private val typeRefCache = HashMap<Int, Int>()

        val corlib: Int
        val tokenCtor: Int
        val addressCtor: Int
        val fieldOffsetCtor: Int
        val metadataOffsetCtor: Int

        init {
            val aname = metadata.assemblyDefs[image.assemblyIndex].aname
            val assemblyName = metadata.getString(aname.nameIndex)
            val version = if (aname.build >= 0) {
                intArrayOf(aname.major, aname.minor, aname.build, aname.revision)
            } else {
                // O __Generated nao tem versao; o dumper de PC usa 3.7.1.6.
                intArrayOf(3, 7, 1, 6)
            }
            builder = AssemblyBuilder(assemblyName, imageName, version)
            corlib = assemblyRef("mscorlib")
            val dummy = assemblyRef("Il2CppDummyDll")
            tokenCtor = ctor(dummy, "TokenAttribute")
            addressCtor = ctor(dummy, "AddressAttribute")
            fieldOffsetCtor = ctor(dummy, "FieldOffsetAttribute")
            metadataOffsetCtor = ctor(dummy, "MetadataOffsetAttribute")
        }

        private fun ctor(scope: Int, name: String) =
            builder.addAttributeCtorRef(builder.addTypeRef(scope, "Il2CppDummyDll", name))

        fun assemblyRef(name: String): Int = assemblyRefs.getOrPut(name) {
            val a = metadata.assemblyDefs.firstOrNull {
                metadata.getString(it.aname.nameIndex) == name
            }?.aname
            if (a != null && a.build >= 0) {
                builder.addAssemblyRef(name, a.major, a.minor, a.build, a.revision)
            } else {
                builder.addAssemblyRef(name, 0, 0, 0, 0)
            }
        }

        /**
         * Referencia a um tipo de outro assembly. Aninhados precisam da cadeia
         * inteira, porque o escopo de um aninhado e o tipo que o contem.
         */
        fun typeRefFor(typeIndex: Int): Int = typeRefCache.getOrPut(typeIndex) {
            val typeDef = metadata.typeDefs[typeIndex]
            val name = metadata.getString(typeDef.nameIndex)
            if (typeDef.declaringTypeIndex != -1) {
                val outerIndex = typeIndexOf(typeDef.declaringTypeIndex)
                if (outerIndex >= 0) builder.addNestedTypeRef(typeRefFor(outerIndex), name)
                else builder.addTypeRef(corlib, "", name)
            } else {
                val owner = imageOfType.getOrElse(typeIndex) { -1 }
                val scope = if (owner >= 0) {
                    assemblyRef(
                        metadata.getString(
                            metadata.assemblyDefs[metadata.imageDefs[owner].assemblyIndex].aname.nameIndex
                        )
                    )
                } else corlib
                builder.addTypeRef(scope, metadata.getString(typeDef.namespaceIndex), name)
            }
        }
    }

    /**
     * Gera cada assembly e entrega ao consumidor, um de cada vez. Depois de
     * cada entrega o contexto e descartado, entao o pico e o do maior
     * assembly, nao a soma dos 56.
     */
    fun forEachAssembly(consumer: (name: String, bytes: ByteArray) -> Unit) {
        for (imageIndex in metadata.imageDefs.indices) {
            val name = metadata.getString(metadata.imageDefs[imageIndex].nameIndex)
            try {
                var ctx: Context? = Context(imageIndex)
                val types = typeRange(ctx!!)
                declarePass(ctx, types)
                typeDefPass(ctx, types)
                fieldPass(ctx, types)
                methodPass(ctx, types)
                paramPass(ctx, types)
                detailPass(ctx, types)
                val bytes = ctx.builder.build()
                // Solta o contexto antes de gravar: o arquivo do
                // Assembly-CSharp passa de 80 MB e nao ha por que segurar as
                // tabelas dele enquanto o disco trabalha.
                ctx = null
                consumer(name, bytes)
            } catch (noMemory: OutOfMemoryError) {
                // Um assembly grande demais para o heap nao pode derrubar o
                // dump inteiro: o resto ainda e util. Segue para o proximo
                // depois de soltar o que ficou pendurado.
                skipped += name
                System.gc()
            }
        }
    }

    /** Assemblies que nao couberam na memoria e ficaram de fora. */
    val skippedAssemblies = mutableListOf<String>()
    private val skipped get() = skippedAssemblies

    private fun typeRange(ctx: Context): IntRange {
        val end = minOf(ctx.image.typeStart + ctx.image.typeCount, metadata.typeDefs.size)
        return ctx.image.typeStart until end
    }

    /** Passada 1: declara os tipos e reserva as faixas de membros. */
    private fun declarePass(ctx: Context, types: IntRange) {
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            val namespace =
                if (typeDef.declaringTypeIndex != -1) "" else metadata.getString(typeDef.namespaceIndex)
            ctx.ridOfType[i] = ctx.builder.declareType(
                namespace, metadata.getString(typeDef.nameIndex), typeDef.flags,
                typeDef.fieldCount, typeDef.methodCount
            )
        }
    }

    /** Passada 2: as linhas de TypeDef, que precisam da base ja resolvida. */
    private fun typeDefPass(ctx: Context, types: IntRange) {
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            scopeOf(typeDef)
            val base = if (typeDef.parentIndex >= 0) sig(ctx, binary.types[typeDef.parentIndex]) else null
            ctx.builder.emitTypeDef(ctx.ridOfType.getValue(i), base)
        }
    }

    /** Passada 3: campos, com offset, constante e token. */
    private fun fieldPass(ctx: Context, types: IntRange) {
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            scopeOf(typeDef)
            val end = typeDef.fieldStart + typeDef.fieldCount
            for (f in typeDef.fieldStart until end) {
                if (f !in metadata.fieldDefs.indices) continue
                val fieldDef = metadata.fieldDefs[f]
                val fieldType = binary.types[fieldDef.typeIndex]
                val rid = ctx.builder.emitField(
                    metadata.getString(fieldDef.nameIndex), fieldType.attrs,
                    ctx.builder.fieldSignature(sig(ctx, fieldType))
                )
                ctx.builder.emitAttribute(
                    Tbl.FIELD, rid, ctx.tokenCtor, token(ctx, fieldDef.token)
                )

                val default = metadata.getFieldDefaultValue(f)
                if (default != null && default.dataIndex != -1) {
                    val constant = decodeConstant(default.typeIndex, default.dataIndex)
                    if (constant != null) {
                        ctx.builder.emitFieldConstant(rid, constant.first, constant.second)
                    } else {
                        ctx.builder.emitAttribute(
                            Tbl.FIELD, rid, ctx.metadataOffsetCtor,
                            ctx.builder.attributeBlob(
                                listOf(
                                    "Offset" to hex(metadata.getDefaultValueData(default.dataIndex))
                                )
                            )
                        )
                    }
                }

                if (fieldType.attrs and FIELD_LITERAL == 0) {
                    val offset = binary.getFieldOffsetFromIndex(
                        i, f - typeDef.fieldStart, f, typeDef.isValueType,
                        fieldType.attrs and FIELD_STATIC != 0
                    )
                    if (offset >= 0) {
                        ctx.builder.emitAttribute(
                            Tbl.FIELD, rid, ctx.fieldOffsetCtor,
                            ctx.builder.attributeBlob(listOf("Offset" to hex(offset.toLong())))
                        )
                    }
                }
            }
        }
    }

    /** Passada 4: metodos, com corpo, endereco e token. */
    private fun methodPass(ctx: Context, types: IntRange) {
        var paramRid = 1
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            scopeOf(typeDef)
            val end = typeDef.methodStart + typeDef.methodCount
            for (m in typeDef.methodStart until end) {
                if (m !in metadata.methodDefs.indices) continue
                val methodDef = metadata.methodDefs[m]
                methodParamCount = genericParamCount(methodDef.genericContainerIndex)

                val returnType = binary.types[methodDef.returnType]
                val returnSig = sig(ctx, returnType)
                val parameters = ArrayList<SigType>(methodDef.parameterCount)
                for (p in 0 until methodDef.parameterCount) {
                    val index = methodDef.parameterStart + p
                    if (index !in metadata.parameterDefs.indices) continue
                    parameters += sig(ctx, binary.types[metadata.parameterDefs[index].typeIndex])
                }

                val isStatic = methodDef.flags and METHOD_STATIC != 0
                val isAbstract = methodDef.flags and METHOD_ABSTRACT != 0
                val body = when {
                    isAbstract || methodDef.flags and METHOD_PINVOKE != 0 -> AssemblyBuilder.BodyKind.None
                    returnType.type == Il2CppTypeEnum.IL2CPP_TYPE_VOID -> AssemblyBuilder.BodyKind.Void
                    isValueTypeReturn(returnType) -> AssemblyBuilder.BodyKind.DefaultValueType
                    else -> AssemblyBuilder.BodyKind.Null
                }
                val rid = ctx.builder.emitMethod(
                    metadata.getString(methodDef.nameIndex),
                    methodDef.flags, methodDef.iflags,
                    ctx.builder.methodSignature(returnSig, parameters, !isStatic, methodParamCount),
                    paramRid, body,
                    returnTypeToken = if (body == AssemblyBuilder.BodyKind.DefaultValueType) {
                        ctx.builder.typeToken(returnSig)
                    } else 0,
                    locals = returnSig
                )
                paramRid += methodDef.parameterCount

                ctx.builder.emitAttribute(Tbl.METHOD_DEF, rid, ctx.tokenCtor, token(ctx, methodDef.token))
                val pointer = if (isAbstract) 0L else binary.getMethodPointer(ctx.imageName, methodDef, m)
                if (pointer > 0L) {
                    val fields = mutableListOf<Pair<String, String?>>(
                        "RVA" to hex(binary.rva(pointer)),
                        "Offset" to hex(binary.mapVaToOffset(pointer)),
                        "VA" to hex(pointer)
                    )
                    if (methodDef.slot != UNSET_SLOT) fields += "Slot" to methodDef.slot.toString()
                    ctx.builder.emitAttribute(
                        Tbl.METHOD_DEF, rid, ctx.addressCtor, ctx.builder.attributeBlob(fields)
                    )
                }
                methodParamCount = 0
            }
        }
    }

    /** Passada 5: parametros, que tem que sair agrupados por metodo. */
    private fun paramPass(ctx: Context, types: IntRange) {
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            val end = typeDef.methodStart + typeDef.methodCount
            for (m in typeDef.methodStart until end) {
                if (m !in metadata.methodDefs.indices) continue
                val methodDef = metadata.methodDefs[m]
                for (p in 0 until methodDef.parameterCount) {
                    val index = methodDef.parameterStart + p
                    if (index !in metadata.parameterDefs.indices) continue
                    val parameterDef = metadata.parameterDefs[index]
                    ctx.builder.emitParam(
                        metadata.getString(parameterDef.nameIndex),
                        binary.types[parameterDef.typeIndex].attrs, p + 1
                    )
                }
            }
        }
    }

    /** Passada 6: interfaces, aninhados, genericos, propriedades e eventos. */
    private fun detailPass(ctx: Context, types: IntRange) {
        for (i in types) {
            val typeDef = metadata.typeDefs[i]
            val rid = ctx.ridOfType.getValue(i)
            scopeOf(typeDef)

            ctx.builder.emitAttribute(Tbl.TYPE_DEF, rid, ctx.tokenCtor, token(ctx, typeDef.token))

            for (k in 0 until typeDef.interfacesCount) {
                val index = typeDef.interfacesStart + k
                if (index !in metadata.interfaceIndices.indices) continue
                ctx.builder.emitInterface(rid, sig(ctx, binary.types[metadata.interfaceIndices[index]]))
            }

            if (typeDef.declaringTypeIndex != -1) {
                val outer = typeIndexOf(typeDef.declaringTypeIndex)
                ctx.ridOfType[outer]?.let { ctx.builder.emitNested(rid, it) }
            }

            addGenericParams(ctx, typeDef.genericContainerIndex, Tbl.TYPE_DEF, rid)

            val methodStart = ctx.builder.methodStartOf(rid)
            val end = typeDef.methodStart + typeDef.methodCount
            for (m in typeDef.methodStart until end) {
                if (m !in metadata.methodDefs.indices) continue
                val methodDef = metadata.methodDefs[m]
                val methodRid = methodStart + (m - typeDef.methodStart)
                addGenericParams(ctx, methodDef.genericContainerIndex, Tbl.METHOD_DEF, methodRid)
            }

            addProperties(ctx, typeDef, rid, methodStart)
            addEvents(ctx, typeDef, rid, methodStart)
        }
    }

    /**
     * Propriedades e eventos nao guardam o proprio tipo: ele sai da assinatura
     * do acessor. O indice do acessor e relativo ao inicio dos metodos do tipo.
     */
    private fun addProperties(ctx: Context, typeDef: Il2CppTypeDefinition, typeRid: Int, methodStart: Int) {
        if (typeDef.propertyCount <= 0) return
        ctx.builder.emitPropertyMap(typeRid, typeDef.propertyCount)
        val end = typeDef.propertyStart + typeDef.propertyCount
        for (i in typeDef.propertyStart until end) {
            if (i !in metadata.propertyDefs.indices) continue
            val def = metadata.propertyDefs[i]
            val accessor = accessorMethod(typeDef, def.get) ?: accessorMethod(typeDef, def.set)
            val type = when {
                def.get >= 0 && accessorMethod(typeDef, def.get) != null ->
                    sig(ctx, binary.types[accessorMethod(typeDef, def.get)!!.returnType])
                accessor != null && accessor.parameterCount > 0 -> {
                    val last = accessor.parameterStart + accessor.parameterCount - 1
                    if (last in metadata.parameterDefs.indices) {
                        sig(ctx, binary.types[metadata.parameterDefs[last].typeIndex])
                    } else SigType.OBJECT
                }
                else -> SigType.OBJECT
            }
            val hasThis = accessor == null || accessor.flags and METHOD_STATIC == 0
            val rid = ctx.builder.emitProperty(
                metadata.getString(def.nameIndex), def.attrs,
                ctx.builder.propertySignature(type, hasThis)
            )
            ctx.builder.emitAttribute(Tbl.PROPERTY, rid, ctx.tokenCtor, token(ctx, def.token))
            if (def.get >= 0) ctx.builder.emitSemantics(0x0002, methodStart + def.get, Tbl.PROPERTY, rid)
            if (def.set >= 0) ctx.builder.emitSemantics(0x0001, methodStart + def.set, Tbl.PROPERTY, rid)
        }
    }

    private fun addEvents(ctx: Context, typeDef: Il2CppTypeDefinition, typeRid: Int, methodStart: Int) {
        if (typeDef.eventCount <= 0) return
        ctx.builder.emitEventMap(typeRid, typeDef.eventCount)
        val end = typeDef.eventStart + typeDef.eventCount
        for (i in typeDef.eventStart until end) {
            if (i !in metadata.eventDefs.indices) continue
            val def = metadata.eventDefs[i]
            val rid = ctx.builder.emitEvent(
                metadata.getString(def.nameIndex), 0, sig(ctx, binary.types[def.typeIndex])
            )
            ctx.builder.emitAttribute(Tbl.EVENT, rid, ctx.tokenCtor, token(ctx, def.token))
            if (def.add >= 0) ctx.builder.emitSemantics(0x0008, methodStart + def.add, Tbl.EVENT, rid)
            if (def.remove >= 0) ctx.builder.emitSemantics(0x0010, methodStart + def.remove, Tbl.EVENT, rid)
            if (def.raise >= 0) ctx.builder.emitSemantics(0x0020, methodStart + def.raise, Tbl.EVENT, rid)
        }
    }

    private fun accessorMethod(typeDef: Il2CppTypeDefinition, relative: Int) =
        if (relative < 0 || relative >= typeDef.methodCount) null
        else metadata.methodDefs.getOrNull(typeDef.methodStart + relative)

    private fun addGenericParams(ctx: Context, containerIndex: Int, ownerTable: Int, ownerRid: Int) {
        if (containerIndex < 0 || containerIndex >= metadata.genericContainers.size) return
        val container = metadata.genericContainers[containerIndex]
        for (n in 0 until container.typeArgc) {
            val index = container.genericParameterStart + n
            if (index !in metadata.genericParameters.indices) continue
            val param = metadata.genericParameters[index]
            val constraints = mutableListOf<SigType>()
            for (c in 0 until param.constraintsCount) {
                val ci = param.constraintsStart + c
                if (ci !in metadata.constraintIndices.indices) continue
                constraints += sig(ctx, binary.types[metadata.constraintIndices[ci]])
            }
            ctx.builder.addGenericParam(
                ownerTable, ownerRid, param.num, param.flags,
                metadata.getString(param.nameIndex), constraints
            )
        }
    }

    private fun token(ctx: Context, value: Int) =
        ctx.builder.attributeBlob(listOf("Token" to hex(value.toLong() and 0xFFFFFFFFL)))

    private fun hex(value: Long) = "0x" + java.lang.Long.toHexString(value).uppercase()

    private fun scopeOf(typeDef: Il2CppTypeDefinition) {
        typeParamCount = genericParamCount(typeDef.genericContainerIndex)
        methodParamCount = 0
    }

    private fun genericParamCount(containerIndex: Int): Int {
        if (containerIndex < 0 || containerIndex >= metadata.genericContainers.size) return 0
        return metadata.genericContainers[containerIndex].typeArgc
    }

    private fun typeIndexOf(binaryTypeIndex: Int): Int {
        val type = binary.types.getOrNull(binaryTypeIndex) ?: return -1
        return runCatching {
            metadata.typeDefs.indexOf(executor.getTypeDefinitionFromIl2CppType(type))
        }.getOrDefault(-1)
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

        Il2CppTypeEnum.IL2CPP_TYPE_PTR -> SigType.Pointer(sig(ctx, binary.readType(type.elementType)))
        Il2CppTypeEnum.IL2CPP_TYPE_SZARRAY -> SigType.SzArray(sig(ctx, binary.readType(type.elementType)))

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
                val definition = namedFor(ctx, generic.typeDefinitionIndex.toInt())
                val inst = binary.genericInstAt(generic.context.classInst)
                val args = mutableListOf<SigType>()
                if (inst != null) {
                    runCatching { executor.getGenericInstTypes(inst) }.getOrNull()
                        ?.forEach { args += sig(ctx, it) }
                }
                if (args.isEmpty()) definition else SigType.GenericInstance(definition, args)
            }
        }

        Il2CppTypeEnum.IL2CPP_TYPE_CLASS, Il2CppTypeEnum.IL2CPP_TYPE_VALUETYPE ->
            namedFor(ctx, type.klassIndex)

        else -> SigType.OBJECT
    }

    /**
     * Um !n ou !!n so e valido se o dono realmente declarar aquele parametro.
     * Acontece de o indice apontar para fora, e nesse caso o leitor devolve
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

    /** Aponta para o TypeDef local se o tipo e deste assembly, senao TypeRef. */
    private fun namedFor(ctx: Context, typeIndex: Int): SigType.Named {
        if (typeIndex !in metadata.typeDefs.indices) {
            return SigType.Named(Tbl.TYPE_REF, ctx.corlib.coerceAtLeast(1), false)
        }
        val isValueType = metadata.typeDefs[typeIndex].isValueType
        val local = ctx.ridOfType[typeIndex]
        return if (local != null) SigType.Named(Tbl.TYPE_DEF, local, isValueType)
        else SigType.Named(Tbl.TYPE_REF, ctx.typeRefFor(typeIndex), isValueType)
    }

    /**
     * Le o valor padrao do blob no formato da tabela Constant: codigo do tipo
     * mais os bytes. Devolve null quando o blob nao e decodificavel, e ai o
     * chamador aponta o offset.
     */
    private fun decodeConstant(typeIndex: Int, dataIndex: Int): Pair<Int, ByteArray>? {
        val type = binary.types.getOrNull(typeIndex) ?: return null
        val typeEnum = type.type ?: return null
        val reader = BinaryReader(metadata.raw)
        reader.seek(metadata.getDefaultValueData(dataIndex))
        val blob = runCatching { executor.readConstantValueFromBlob(typeEnum, reader) }.getOrNull()
            ?: return null
        val out = ByteBuffer(8)
        val code = when (val value = blob.value) {
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
        return code to out.toByteArray()
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
