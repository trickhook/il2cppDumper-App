package com.trickhook.il2cpp.dotnet

/**
 * Monta um assembly .NET emitindo as linhas de tabela direto, sem construir um
 * modelo de objetos antes.
 *
 * A primeira versao guardava tipos, campos, metodos e atributos como objetos e
 * so serializava no fim. E confortavel, mas o Assembly-CSharp de um jogo grande
 * tem 1,4 milhao de atributos, e so as listas e strings intermediarias
 * estouravam o heap de 512 MB de um celular. Aqui nada disso existe: o que fica
 * em memoria sao as proprias tabelas e os heaps, que teriam de existir de
 * qualquer jeito.
 *
 * Em troca, a ordem passa a ser responsabilidade de quem chama, porque o
 * formato e rigido - um TypeDef guarda apenas o RID do PRIMEIRO campo e do
 * PRIMEIRO metodo, entao os membros tem que sair agrupados por tipo:
 *
 *   1. [declareType] para todos os tipos, em ordem
 *   2. [emitTypeDef] para todos, na mesma ordem
 *   3. [emitField] para todos os campos, tipo a tipo
 *   4. [emitMethod] para todos os metodos, tipo a tipo
 *   5. [emitParam] para todos os parametros, metodo a metodo
 *   6. o resto, em qualquer ordem
 *
 * Como todo tipo ja tem RID desde o passo 1, as assinaturas podem ser
 * codificadas na hora - e por isso que entram aqui como offset de blob e nao
 * como arvore.
 */
class AssemblyBuilder(
    private val assemblyName: String,
    private val moduleName: String,
    private val version: IntArray = intArrayOf(0, 0, 0, 0)
) {

    enum class BodyKind { None, Void, Null, DefaultValueType }

    private val tables = TableSet()
    private val strings = StringHeap()
    private val blobs = BlobHeap()
    private val guids = GuidHeap()
    private val userStrings = UserStringHeap()
    private val bodies = MethodBodyWriter()

    private val assemblyRefs = LinkedHashMap<String, Int>()
    private val typeRefs = LinkedHashMap<String, Int>()
    private val memberRefs = LinkedHashMap<Int, Int>()
    private val typeSpecs = LinkedHashMap<String, Int>()

    /** Dados dos tipos declarados, em arrays para nao pagar um objeto por tipo. */
    private var typeNamespace = IntArray(64)
    private var typeName = IntArray(64)
    private var typeFlags = IntArray(64)
    private var typeFieldStart = IntArray(64)
    private var typeMethodStart = IntArray(64)
    private var declared = 0

    /** True quando o <Module> foi criado aqui e nao veio de quem chama. */
    private var syntheticModule = false

    private var nextField = 1
    private var nextMethod = 1
    private var nextProperty = 1
    private var nextEvent = 1

    /** Parametros genericos, guardados ate poderem sair ordenados. */
    private class PendingGeneric(
        val owner: Int,
        val number: Int,
        val flags: Int,
        val name: Int,
        val constraints: IntArray
    )

    private val pendingGenerics = mutableListOf<PendingGeneric>()

    val typeCount: Int get() = declared

    fun str(text: String): Int = strings.add(text)

    fun blob(value: ByteArray): Int = blobs.add(value)

    fun addAssemblyRef(name: String, major: Int, minor: Int, build: Int, revision: Int): Int =
        assemblyRefs.getOrPut(name) {
            tables.add(
                Tbl.ASSEMBLY_REF,
                major and 0xFFFF, minor and 0xFFFF,
                maxOf(build, 0) and 0xFFFF, maxOf(revision, 0) and 0xFFFF,
                0, 0, strings.add(name), 0, 0
            )
        }

    fun addTypeRef(assemblyRefRid: Int, namespace: String, name: String): Int =
        typeRefs.getOrPut("$assemblyRefRid|$namespace|$name") {
            tables.add(
                Tbl.TYPE_REF,
                Coded.RESOLUTION_SCOPE.encode(Tbl.ASSEMBLY_REF, assemblyRefRid),
                strings.add(name), strings.add(namespace)
            )
        }

    /** TypeRef aninhado, cujo escopo e o tipo que o contem. */
    fun addNestedTypeRef(enclosingTypeRefRid: Int, name: String): Int =
        typeRefs.getOrPut("nested|$enclosingTypeRefRid|$name") {
            tables.add(
                Tbl.TYPE_REF,
                Coded.RESOLUTION_SCOPE.encode(Tbl.TYPE_REF, enclosingTypeRefRid),
                strings.add(name), 0
            )
        }

    /** MemberRef para o construtor sem parametros de um atributo. */
    fun addAttributeCtorRef(typeRefRid: Int): Int = memberRefs.getOrPut(typeRefRid) {
        tables.add(
            Tbl.MEMBER_REF,
            Coded.MEMBER_REF_PARENT.encode(Tbl.TYPE_REF, typeRefRid),
            strings.add(".ctor"),
            blobs.add(Signatures.method(SigType.VOID, emptyList(), hasThis = true))
        )
    }

    fun fieldSignature(type: SigType): Int = blobs.add(Signatures.field(type))

    fun methodSignature(
        returnType: SigType,
        parameters: List<SigType>,
        hasThis: Boolean,
        genericParameterCount: Int
    ): Int = blobs.add(
        Signatures.method(returnType, parameters, hasThis, genericParameterCount = genericParameterCount)
    )

    fun propertySignature(type: SigType, hasThis: Boolean): Int =
        blobs.add(Signatures.property(type, emptyList(), hasThis))

    fun attributeBlob(fields: List<Pair<String, String?>>): Int =
        blobs.add(CustomAttributes.withStringFields(fields))

    /**
     * Passo 1. Guarda o tipo e reserva as faixas de campo e metodo dele.
     * Devolve o RID, que ja pode ser usado em assinaturas.
     */
    fun declareType(namespace: String, name: String, flags: Int, fieldCount: Int, methodCount: Int): Int {
        if (declared == 0 && name != MODULE_TYPE) {
            // A norma exige <Module> na primeira linha. Quem vem de metadata
            // IL2CPP ja traz o seu, entao este so aparece quando falta.
            declareType("", MODULE_TYPE, 0, 0, 0)
            syntheticModule = true
        }
        if (declared == typeNamespace.size) grow()
        typeNamespace[declared] = strings.add(namespace)
        typeName[declared] = strings.add(name)
        typeFlags[declared] = flags
        typeFieldStart[declared] = nextField
        typeMethodStart[declared] = nextMethod
        nextField += fieldCount
        nextMethod += methodCount
        declared++
        return declared
    }

    private fun grow() {
        val size = typeNamespace.size * 2
        typeNamespace = typeNamespace.copyOf(size)
        typeName = typeName.copyOf(size)
        typeFlags = typeFlags.copyOf(size)
        typeFieldStart = typeFieldStart.copyOf(size)
        typeMethodStart = typeMethodStart.copyOf(size)
    }

    fun methodStartOf(rid: Int): Int = typeMethodStart[rid - 1]

    /** Passo 2. Uma linha de TypeDef, na mesma ordem das declaracoes. */
    fun emitTypeDef(rid: Int, baseType: SigType?) {
        // O <Module> sintetico nao pertence a quem chama, entao a linha dele
        // sai aqui, antes da primeira que o chamador pedir.
        if (syntheticModule && tables.count(Tbl.TYPE_DEF) == 0 && rid != 1) {
            writeTypeDefRow(1, null)
        }
        writeTypeDefRow(rid, baseType)
    }

    private fun writeTypeDefRow(rid: Int, baseType: SigType?) {
        val index = rid - 1
        val written = tables.add(
            Tbl.TYPE_DEF, typeFlags[index], typeName[index], typeNamespace[index],
            encodeTypeDefOrRef(baseType), typeFieldStart[index], typeMethodStart[index]
        )
        check(written == rid) { "TypeDef fora de ordem: $written != $rid" }
    }

    /** Passo 3. */
    fun emitField(name: String, flags: Int, signature: Int): Int =
        tables.add(Tbl.FIELD, flags and 0xFFFF, strings.add(name), signature)

    /** Passo 4. [locals] so importa quando o corpo devolve um tipo por valor. */
    fun emitMethod(
        name: String,
        flags: Int,
        implFlags: Int,
        signature: Int,
        paramStart: Int,
        body: BodyKind,
        returnTypeToken: Int = 0,
        locals: SigType? = null
    ): Int {
        val rva = when (body) {
            BodyKind.None -> 0
            BodyKind.Void -> bodies.returnVoid()
            BodyKind.Null -> bodies.returnNull()
            BodyKind.DefaultValueType -> {
                val sig = blobs.add(Signatures.locals(listOf(locals ?: SigType.OBJECT)))
                val rid = tables.add(Tbl.STAND_ALONE_SIG, sig)
                bodies.returnDefaultValueType(returnTypeToken, (Tbl.STAND_ALONE_SIG shl 24) or rid)
            }
        }
        return tables.add(
            Tbl.METHOD_DEF, rva, implFlags and 0xFFFF, flags and 0xFFFF,
            strings.add(name), signature, paramStart
        )
    }

    /** Passo 5. */
    fun emitParam(name: String, flags: Int, sequence: Int): Int =
        tables.add(Tbl.PARAM, flags and 0xFFFF, sequence, strings.add(name))

    fun emitAttribute(ownerTable: Int, ownerRid: Int, ctorRid: Int, blobOffset: Int) {
        tables.add(
            Tbl.CUSTOM_ATTRIBUTE,
            Coded.HAS_CUSTOM_ATTRIBUTE.encode(ownerTable, ownerRid),
            Coded.CUSTOM_ATTRIBUTE_TYPE.encode(Tbl.MEMBER_REF, ctorRid),
            blobOffset
        )
    }

    fun emitFieldConstant(fieldRid: Int, elementType: Int, value: ByteArray) {
        tables.add(
            Tbl.CONSTANT, elementType, 0,
            Coded.HAS_CONSTANT.encode(Tbl.FIELD, fieldRid), blobs.add(value)
        )
    }

    fun emitInterface(typeRid: Int, iface: SigType) {
        tables.add(Tbl.INTERFACE_IMPL, typeRid, encodeTypeDefOrRef(iface))
    }

    fun emitNested(innerRid: Int, outerRid: Int) {
        tables.add(Tbl.NESTED_CLASS, innerRid, outerRid)
    }

    /** Abre o mapa de propriedades do tipo e devolve o RID da primeira. */
    fun emitPropertyMap(typeRid: Int, count: Int): Int {
        tables.add(Tbl.PROPERTY_MAP, typeRid, nextProperty)
        val start = nextProperty
        nextProperty += count
        return start
    }

    fun emitProperty(name: String, flags: Int, signature: Int): Int =
        tables.add(Tbl.PROPERTY, flags and 0xFFFF, strings.add(name), signature)

    fun emitEventMap(typeRid: Int, count: Int): Int {
        tables.add(Tbl.EVENT_MAP, typeRid, nextEvent)
        val start = nextEvent
        nextEvent += count
        return start
    }

    fun emitEvent(name: String, flags: Int, type: SigType): Int =
        tables.add(Tbl.EVENT, flags and 0xFFFF, strings.add(name), encodeTypeDefOrRef(type))

    fun emitSemantics(kind: Int, methodRid: Int, ownerTable: Int, ownerRid: Int) {
        tables.add(
            Tbl.METHOD_SEMANTICS, kind, methodRid,
            Coded.HAS_SEMANTICS.encode(ownerTable, ownerRid)
        )
    }

    /**
     * Parametros genericos ficam pendentes ate o fim porque a tabela tem que
     * sair ordenada por dono, e GenericParamConstraint aponta para ela pelo
     * RID - ordenar depois de gravar as restricoes prenderia cada restricao ao
     * parametro errado.
     */
    fun addGenericParam(
        ownerTable: Int, ownerRid: Int, number: Int, flags: Int, name: String,
        constraints: List<SigType>
    ) {
        pendingGenerics += PendingGeneric(
            Coded.TYPE_OR_METHOD_DEF.encode(ownerTable, ownerRid),
            number, flags, strings.add(name),
            IntArray(constraints.size) { encodeTypeDefOrRef(constraints[it]) }
        )
    }

    fun typeToken(type: SigType): Int = when (type) {
        is SigType.Named -> (type.table shl 24) or type.rid
        else -> (Tbl.TYPE_SPEC shl 24) or typeSpecRid(type)
    }

    fun build(): ByteArray {
        tables.add(Tbl.MODULE, 0, strings.add(moduleName), guids.add(moduleGuid()), 0, 0)
        emitGenericParams()
        tables.add(
            Tbl.ASSEMBLY, 0x8004,
            version[0] and 0xFFFF, version[1] and 0xFFFF,
            version[2] and 0xFFFF, version[3] and 0xFFFF,
            0, 0, strings.add(assemblyName), 0
        )
        sortRequiredTables()
        val metadata = MetadataRoot.build(tables, strings, userStrings, guids, blobs)
        return PeWriter(metadata, bodies.toByteArray()).build()
    }

    private fun emitGenericParams() {
        pendingGenerics.sortWith(compareBy({ it.owner }, { it.number }))
        val rids = IntArray(pendingGenerics.size)
        for ((i, g) in pendingGenerics.withIndex()) {
            rids[i] = tables.add(Tbl.GENERIC_PARAM, g.number, g.flags, g.owner, g.name)
        }
        for ((i, g) in pendingGenerics.withIndex()) {
            for (c in g.constraints) tables.add(Tbl.GENERIC_PARAM_CONSTRAINT, rids[i], c)
        }
        pendingGenerics.clear()
    }

    private fun sortRequiredTables() {
        tables.sortBy(Tbl.CUSTOM_ATTRIBUTE, 0)
        tables.sortBy(Tbl.CONSTANT, 2)
        tables.sortBy(Tbl.INTERFACE_IMPL, 0, 1)
        tables.sortBy(Tbl.NESTED_CLASS, 0)
        tables.sortBy(Tbl.METHOD_SEMANTICS, 2)
    }

    /** MVID estavel derivado do nome, para a saida ser reproduzivel. */
    private fun moduleGuid(): ByteArray {
        val raw = ByteArray(16)
        var h = 0x811C9DC5L
        for (ch in "$assemblyName|$moduleName") {
            h = (h xor ch.code.toLong()) * 0x01000193L and 0xFFFFFFFFL
        }
        for (i in 0 until 16) {
            h = (h * 0x01000193L + i) and 0xFFFFFFFFL
            raw[i] = (h ushr 8).toByte()
        }
        return raw
    }

    private fun typeSpecRid(type: SigType): Int {
        val sig = Signatures.typeSpec(type)
        return typeSpecs.getOrPut(sig.joinToString(",")) { tables.add(Tbl.TYPE_SPEC, blobs.add(sig)) }
    }

    private fun encodeTypeDefOrRef(type: SigType?): Int = when (type) {
        null -> 0
        is SigType.Named -> Coded.TYPE_DEF_OR_REF.encode(type.table, type.rid)
        else -> Coded.TYPE_DEF_OR_REF.encode(Tbl.TYPE_SPEC, typeSpecRid(type))
    }

    private companion object {
        const val MODULE_TYPE = "<Module>"
    }
}
