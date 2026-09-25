package com.trickhook.il2cpp.dotnet

/**
 * API de alto nivel para montar um assembly .NET.
 *
 * O formato impoe uma ordem que nao da para respeitar escrevendo linha por
 * linha na hora em que o tipo aparece: os campos de um tipo tem que ficar
 * contiguos na tabela Field, os metodos contiguos na MethodDef, os parametros
 * contiguos na Param, e cada TypeDef guarda so o RID do PRIMEIRO de cada lista.
 * Ou seja, o RID de um membro so e conhecido depois que todos os tipos foram
 * declarados.
 *
 * Por isso aqui se monta um modelo em memoria primeiro e a serializacao
 * acontece no fim, quando os RIDs ja estao definidos. As assinaturas guardam
 * arvores de [SigType] em vez de bytes, pelo mesmo motivo: elas referenciam
 * tipos cujo RID ainda nao existia quando foram criadas.
 */
class AssemblyBuilder(
    private val assemblyName: String,
    private val moduleName: String,
    private val version: IntArray = intArrayOf(0, 0, 0, 0)
) {

    class FieldDef(
        val name: String,
        val flags: Int,
        val type: SigType
    ) {
        var constant: Constant? = null
        val attributes = mutableListOf<AttributeUse>()
        var rid = 0
    }

    class ParamDef(val name: String, val flags: Int, val sequence: Int) {
        val attributes = mutableListOf<AttributeUse>()
        var rid = 0
    }

    class MethodDef(
        val name: String,
        val flags: Int,
        val implFlags: Int,
        val returnType: SigType,
        val parameters: List<SigType>,
        val hasThis: Boolean
    ) {
        val params = mutableListOf<ParamDef>()
        val attributes = mutableListOf<AttributeUse>()
        val genericParams = mutableListOf<GenericParamDef>()
        var body: BodyKind = BodyKind.None
        var rid = 0
    }

    enum class BodyKind { None, Void, Null, DefaultValueType }

    class PropertyDef(val name: String, val flags: Int, val type: SigType, val hasThis: Boolean) {
        var getter: MethodDef? = null
        var setter: MethodDef? = null
        val attributes = mutableListOf<AttributeUse>()
        var rid = 0
    }

    class EventDef(val name: String, val flags: Int, val type: SigType) {
        var adder: MethodDef? = null
        var remover: MethodDef? = null
        var raiser: MethodDef? = null
        val attributes = mutableListOf<AttributeUse>()
        var rid = 0
    }

    class GenericParamDef(val name: String, val number: Int, val flags: Int) {
        val constraints = mutableListOf<SigType>()
        var rid = 0
    }

    /** Valor constante de campo ou parametro, ja no formato do heap de blob. */
    class Constant(val elementType: Int, val value: ByteArray)

    /** Uso de um atributo: o construtor referenciado e os campos string. */
    class AttributeUse(val ctor: MemberRefHandle, val fields: List<Pair<String, String?>>)

    /** Handle de um MemberRef ja criado, para nao recriar por membro anotado. */
    class MemberRefHandle(val rid: Int)

    inner class TypeDef(
        val namespace: String,
        val name: String,
        val flags: Int,
        val baseType: SigType?
    ) {
        val fields = mutableListOf<FieldDef>()
        val methods = mutableListOf<MethodDef>()
        val properties = mutableListOf<PropertyDef>()
        val events = mutableListOf<EventDef>()
        val interfaces = mutableListOf<SigType>()
        val genericParams = mutableListOf<GenericParamDef>()
        val attributes = mutableListOf<AttributeUse>()
        var declaringType: TypeDef? = null
        var rid = 0

        fun asSig(isValueType: Boolean) = SigType.Named(Tbl.TYPE_DEF, rid, isValueType)
    }

    private val tables = TableSet()
    private val strings = StringHeap()
    private val blobs = BlobHeap()
    private val guids = GuidHeap()
    private val userStrings = UserStringHeap()
    private val bodies = MethodBodyWriter()

    private val types = mutableListOf<TypeDef>()
    private val assemblyRefs = LinkedHashMap<String, Int>()
    private val typeRefs = LinkedHashMap<String, Int>()
    private val memberRefs = LinkedHashMap<String, Int>()

    /** Tipos que precisam de um TypeSpec porque nao sao uma simples TypeDef/TypeRef. */
    private val typeSpecs = LinkedHashMap<String, Int>()

    init {
        // O <Module> e sempre o primeiro TypeDef e nao tem conteudo.
        defineType("", "<Module>", 0, null)
    }

    fun addAssemblyRef(name: String, major: Int, minor: Int, build: Int, revision: Int): Int =
        assemblyRefs.getOrPut(name) {
            tables.add(
                Tbl.ASSEMBLY_REF,
                major and 0xFFFF, minor and 0xFFFF,
                maxOf(build, 0) and 0xFFFF, maxOf(revision, 0) and 0xFFFF,
                0, 0, strings.add(name), 0, 0
            )
        }

    fun addTypeRef(assemblyRefRid: Int, namespace: String, name: String): Int {
        val key = "$assemblyRefRid|$namespace|$name"
        return typeRefs.getOrPut(key) {
            tables.add(
                Tbl.TYPE_REF,
                Coded.RESOLUTION_SCOPE.encode(Tbl.ASSEMBLY_REF, assemblyRefRid),
                strings.add(name), strings.add(namespace)
            )
        }
    }

    /** TypeRef aninhado, cujo escopo e o tipo que o contem. */
    fun addNestedTypeRef(enclosingTypeRefRid: Int, name: String): Int {
        val key = "nested|$enclosingTypeRefRid|$name"
        return typeRefs.getOrPut(key) {
            tables.add(
                Tbl.TYPE_REF,
                Coded.RESOLUTION_SCOPE.encode(Tbl.TYPE_REF, enclosingTypeRefRid),
                strings.add(name), 0
            )
        }
    }

    /** MemberRef para o construtor sem parametros de um atributo. */
    fun addAttributeCtorRef(typeRefRid: Int): MemberRefHandle {
        val key = "ctor|$typeRefRid"
        val rid = memberRefs.getOrPut(key) {
            val sig = Signatures.method(SigType.VOID, emptyList(), hasThis = true)
            tables.add(
                Tbl.MEMBER_REF,
                Coded.MEMBER_REF_PARENT.encode(Tbl.TYPE_REF, typeRefRid),
                strings.add(".ctor"), blobs.add(sig)
            )
        }
        return MemberRefHandle(rid)
    }

    fun defineType(namespace: String, name: String, flags: Int, baseType: SigType?): TypeDef {
        val t = TypeDef(namespace, name, flags, baseType)
        types += t
        t.rid = types.size
        return t
    }

    val typeCount: Int get() = types.size

    /**
     * Serializa tudo. Depois disto o builder nao deve ser reutilizado.
     */
    fun build(): ByteArray {
        val mvid = guids.add(moduleGuid())
        tables.add(Tbl.MODULE, 0, strings.add(moduleName), mvid, 0, 0)

        assignMemberRids()
        emitTypeDefs()
        emitFields()
        emitMethodsAndParams()
        emitInterfaces()
        emitNested()
        emitPropertiesAndEvents()
        emitGenericParams()
        emitAttributes()
        emitConstants()

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

    private fun assignMemberRids() {
        var field = 1
        var method = 1
        var param = 1
        var property = 1
        var event = 1
        for (t in types) {
            for (f in t.fields) f.rid = field++
            for (m in t.methods) {
                m.rid = method++
                for (p in m.params) p.rid = param++
            }
            for (p in t.properties) p.rid = property++
            for (e in t.events) e.rid = event++
        }
    }

    private fun emitTypeDefs() {
        var field = 1
        var method = 1
        for (t in types) {
            val rid = tables.add(
                Tbl.TYPE_DEF, t.flags,
                strings.add(t.name), strings.add(t.namespace),
                encodeTypeDefOrRef(t.baseType),
                field, method
            )
            check(rid == t.rid) { "RID de TypeDef fora de ordem: $rid != ${t.rid}" }
            field += t.fields.size
            method += t.methods.size
        }
    }

    private fun emitFields() {
        for (t in types) for (f in t.fields) {
            val rid = tables.add(
                Tbl.FIELD, f.flags and 0xFFFF,
                strings.add(f.name), blobs.add(Signatures.field(f.type))
            )
            check(rid == f.rid)
        }
    }

    private fun emitMethodsAndParams() {
        var param = 1
        for (t in types) for (m in t.methods) {
            val rva = when (m.body) {
                BodyKind.None -> 0
                BodyKind.Void -> bodies.returnVoid()
                BodyKind.Null -> bodies.returnNull()
                BodyKind.DefaultValueType -> {
                    val localsRid = tables.add(
                        Tbl.STAND_ALONE_SIG, blobs.add(Signatures.locals(listOf(m.returnType)))
                    )
                    bodies.returnDefaultValueType(
                        typeToken(m.returnType),
                        (Tbl.STAND_ALONE_SIG shl 24) or localsRid
                    )
                }
            }
            val rid = tables.add(
                Tbl.METHOD_DEF, rva, m.implFlags and 0xFFFF, m.flags and 0xFFFF,
                strings.add(m.name),
                blobs.add(
                    Signatures.method(
                        m.returnType, m.parameters, m.hasThis,
                        genericParameterCount = m.genericParams.size
                    )
                ),
                param
            )
            check(rid == m.rid)
            param += m.params.size
        }
        for (t in types) for (m in t.methods) for (p in m.params) {
            val rid = tables.add(Tbl.PARAM, p.flags and 0xFFFF, p.sequence, strings.add(p.name))
            check(rid == p.rid)
        }
    }

    private fun emitInterfaces() {
        for (t in types) for (i in t.interfaces) {
            tables.add(Tbl.INTERFACE_IMPL, t.rid, encodeTypeDefOrRef(i))
        }
    }

    private fun emitNested() {
        for (t in types) {
            val outer = t.declaringType ?: continue
            tables.add(Tbl.NESTED_CLASS, t.rid, outer.rid)
        }
    }

    private fun emitPropertiesAndEvents() {
        var property = 1
        var event = 1
        for (t in types) {
            if (t.properties.isNotEmpty()) {
                tables.add(Tbl.PROPERTY_MAP, t.rid, property)
                property += t.properties.size
            }
            if (t.events.isNotEmpty()) {
                tables.add(Tbl.EVENT_MAP, t.rid, event)
                event += t.events.size
            }
        }
        for (t in types) for (p in t.properties) {
            val rid = tables.add(
                Tbl.PROPERTY, p.flags and 0xFFFF, strings.add(p.name),
                blobs.add(Signatures.property(p.type, emptyList(), p.hasThis))
            )
            check(rid == p.rid)
        }
        for (t in types) for (e in t.events) {
            val rid = tables.add(
                Tbl.EVENT, e.flags and 0xFFFF, strings.add(e.name), encodeTypeDefOrRef(e.type)
            )
            check(rid == e.rid)
        }
        for (t in types) {
            for (p in t.properties) {
                p.getter?.let { semantics(0x0002, it, Tbl.PROPERTY, p.rid) }
                p.setter?.let { semantics(0x0001, it, Tbl.PROPERTY, p.rid) }
            }
            for (e in t.events) {
                e.adder?.let { semantics(0x0008, it, Tbl.EVENT, e.rid) }
                e.remover?.let { semantics(0x0010, it, Tbl.EVENT, e.rid) }
                e.raiser?.let { semantics(0x0020, it, Tbl.EVENT, e.rid) }
            }
        }
    }

    private fun semantics(kind: Int, method: MethodDef, ownerTable: Int, ownerRid: Int) {
        tables.add(
            Tbl.METHOD_SEMANTICS, kind, method.rid,
            Coded.HAS_SEMANTICS.encode(ownerTable, ownerRid)
        )
    }

    private fun emitGenericParams() {
        val pending = mutableListOf<Pair<GenericParamDef, Int>>()
        for (t in types) {
            for (g in t.genericParams) {
                g.rid = tables.add(
                    Tbl.GENERIC_PARAM, g.number, g.flags,
                    Coded.TYPE_OR_METHOD_DEF.encode(Tbl.TYPE_DEF, t.rid), strings.add(g.name)
                )
                pending += g to g.rid
            }
            for (m in t.methods) for (g in m.genericParams) {
                g.rid = tables.add(
                    Tbl.GENERIC_PARAM, g.number, g.flags,
                    Coded.TYPE_OR_METHOD_DEF.encode(Tbl.METHOD_DEF, m.rid), strings.add(g.name)
                )
                pending += g to g.rid
            }
        }
        for ((g, rid) in pending) {
            for (c in g.constraints) {
                tables.add(Tbl.GENERIC_PARAM_CONSTRAINT, rid, encodeTypeDefOrRef(c))
            }
        }
    }

    private fun emitAttributes() {
        fun emit(list: List<AttributeUse>, ownerTable: Int, ownerRid: Int) {
            for (a in list) {
                tables.add(
                    Tbl.CUSTOM_ATTRIBUTE,
                    Coded.HAS_CUSTOM_ATTRIBUTE.encode(ownerTable, ownerRid),
                    Coded.CUSTOM_ATTRIBUTE_TYPE.encode(Tbl.MEMBER_REF, a.ctor.rid),
                    blobs.add(CustomAttributes.withStringFields(a.fields))
                )
            }
        }
        for (t in types) {
            emit(t.attributes, Tbl.TYPE_DEF, t.rid)
            for (f in t.fields) emit(f.attributes, Tbl.FIELD, f.rid)
            for (m in t.methods) {
                emit(m.attributes, Tbl.METHOD_DEF, m.rid)
                for (p in m.params) emit(p.attributes, Tbl.PARAM, p.rid)
            }
            for (p in t.properties) emit(p.attributes, Tbl.PROPERTY, p.rid)
            for (e in t.events) emit(e.attributes, Tbl.EVENT, e.rid)
        }
    }

    private fun emitConstants() {
        for (t in types) for (f in t.fields) {
            val c = f.constant ?: continue
            tables.add(
                Tbl.CONSTANT, c.elementType, 0,
                Coded.HAS_CONSTANT.encode(Tbl.FIELD, f.rid),
                blobs.add(c.value)
            )
        }
    }

    /**
     * As tabelas que o runtime pesquisa por busca binaria tem que sair
     * ordenadas pela coluna de dono - a mascara Sorted promete isso.
     */
    private fun sortRequiredTables() {
        tables.sortBy(Tbl.CUSTOM_ATTRIBUTE, 0)
        tables.sortBy(Tbl.CONSTANT, 2)
        tables.sortBy(Tbl.INTERFACE_IMPL, 0, 1)
        tables.sortBy(Tbl.NESTED_CLASS, 0)
        tables.sortBy(Tbl.GENERIC_PARAM, 2, 0)
        tables.sortBy(Tbl.GENERIC_PARAM_CONSTRAINT, 0)
        tables.sortBy(Tbl.METHOD_SEMANTICS, 2)
    }

    private fun typeToken(type: SigType): Int = when (type) {
        is SigType.Named -> (type.table shl 24) or type.rid
        else -> (Tbl.TYPE_SPEC shl 24) or typeSpecRid(type)
    }

    private fun typeSpecRid(type: SigType): Int {
        val sig = Signatures.typeSpec(type)
        val key = sig.joinToString(",") { it.toString() }
        return typeSpecs.getOrPut(key) { tables.add(Tbl.TYPE_SPEC, blobs.add(sig)) }
    }

    /**
     * Codifica uma referencia de tipo para uma coluna TypeDefOrRef. Tipos
     * compostos nao cabem nessa coluna e precisam de uma linha TypeSpec.
     */
    private fun encodeTypeDefOrRef(type: SigType?): Int {
        if (type == null) return 0
        return when (type) {
            is SigType.Named -> Coded.TYPE_DEF_OR_REF.encode(type.table, type.rid)
            else -> Coded.TYPE_DEF_OR_REF.encode(Tbl.TYPE_SPEC, typeSpecRid(type))
        }
    }
}
