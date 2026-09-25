package com.trickhook.il2cpp.dotnet

/** Identificadores das tabelas de metadata (ECMA-335 II.22). */
object Tbl {
    const val MODULE = 0x00
    const val TYPE_REF = 0x01
    const val TYPE_DEF = 0x02
    const val FIELD = 0x04
    const val METHOD_DEF = 0x06
    const val PARAM = 0x08
    const val INTERFACE_IMPL = 0x09
    const val MEMBER_REF = 0x0A
    const val CONSTANT = 0x0B
    const val CUSTOM_ATTRIBUTE = 0x0C
    const val FIELD_MARSHAL = 0x0D
    const val DECL_SECURITY = 0x0E
    const val CLASS_LAYOUT = 0x0F
    const val FIELD_LAYOUT = 0x10
    const val STAND_ALONE_SIG = 0x11
    const val EVENT_MAP = 0x12
    const val EVENT = 0x14
    const val PROPERTY_MAP = 0x15
    const val PROPERTY = 0x17
    const val METHOD_SEMANTICS = 0x18
    const val METHOD_IMPL = 0x19
    const val MODULE_REF = 0x1A
    const val TYPE_SPEC = 0x1B
    const val IMPL_MAP = 0x1C
    const val FIELD_RVA = 0x1D
    const val ASSEMBLY = 0x20
    const val ASSEMBLY_REF = 0x23
    const val FILE = 0x26
    const val EXPORTED_TYPE = 0x27
    const val MANIFEST_RESOURCE = 0x28
    const val NESTED_CLASS = 0x29
    const val GENERIC_PARAM = 0x2A
    const val METHOD_SPEC = 0x2B
    const val GENERIC_PARAM_CONSTRAINT = 0x2C

    const val COUNT = 64
}

/**
 * Um indice codificado guarda "qual tabela" nos bits baixos e o RID nos altos,
 * para que uma coluna possa apontar para tabelas diferentes. A largura depende
 * da maior das tabelas do conjunto, e errar essa conta produz um arquivo que
 * abre mas descreve os tipos errados - por isso as listas abaixo sao literais
 * da norma, na ordem exata em que ela define as tags.
 */
enum class Coded(vararg val tables: Int) {
    TYPE_DEF_OR_REF(Tbl.TYPE_DEF, Tbl.TYPE_REF, Tbl.TYPE_SPEC),
    HAS_CONSTANT(Tbl.FIELD, Tbl.PARAM, Tbl.PROPERTY),
    HAS_CUSTOM_ATTRIBUTE(
        Tbl.METHOD_DEF, Tbl.FIELD, Tbl.TYPE_REF, Tbl.TYPE_DEF, Tbl.PARAM,
        Tbl.INTERFACE_IMPL, Tbl.MEMBER_REF, Tbl.MODULE, Tbl.DECL_SECURITY,
        Tbl.PROPERTY, Tbl.EVENT, Tbl.STAND_ALONE_SIG, Tbl.MODULE_REF,
        Tbl.TYPE_SPEC, Tbl.ASSEMBLY, Tbl.ASSEMBLY_REF, Tbl.FILE,
        Tbl.EXPORTED_TYPE, Tbl.MANIFEST_RESOURCE, Tbl.GENERIC_PARAM,
        Tbl.GENERIC_PARAM_CONSTRAINT, Tbl.METHOD_SPEC
    ),
    HAS_FIELD_MARSHAL(Tbl.FIELD, Tbl.PARAM),
    HAS_DECL_SECURITY(Tbl.TYPE_DEF, Tbl.METHOD_DEF, Tbl.ASSEMBLY),
    MEMBER_REF_PARENT(Tbl.TYPE_DEF, Tbl.TYPE_REF, Tbl.MODULE_REF, Tbl.METHOD_DEF, Tbl.TYPE_SPEC),
    HAS_SEMANTICS(Tbl.EVENT, Tbl.PROPERTY),
    METHOD_DEF_OR_REF(Tbl.METHOD_DEF, Tbl.MEMBER_REF),
    MEMBER_FORWARDED(Tbl.FIELD, Tbl.METHOD_DEF),
    IMPLEMENTATION(Tbl.FILE, Tbl.ASSEMBLY_REF, Tbl.EXPORTED_TYPE),
    // As tags 0, 1 e 4 nao sao usadas, mas ocupam espaco na contagem de bits.
    CUSTOM_ATTRIBUTE_TYPE(-1, -1, Tbl.METHOD_DEF, Tbl.MEMBER_REF, -1),
    RESOLUTION_SCOPE(Tbl.MODULE, Tbl.MODULE_REF, Tbl.ASSEMBLY_REF, Tbl.TYPE_REF),
    TYPE_OR_METHOD_DEF(Tbl.TYPE_DEF, Tbl.METHOD_DEF);

    /** Bits necessarios para distinguir as tabelas do conjunto. */
    val bits: Int = run {
        var n = 0
        while ((1 shl n) < tables.size) n++
        n
    }

    fun tagOf(table: Int): Int {
        val index = tables.indexOf(table)
        require(index >= 0) { "tabela $table nao pertence a $name" }
        return index
    }

    /** Codifica uma referencia; rid 0 significa "nenhuma". */
    fun encode(table: Int, rid: Int): Int =
        if (rid == 0) 0 else (rid shl bits) or tagOf(table)
}

/** Tipo de cada coluna, que decide como ela e escrita e quanto ocupa. */
sealed class Col {
    object U8 : Col()
    object U16 : Col()
    object U32 : Col()
    object Str : Col()
    object Blob : Col()
    object Guid : Col()
    data class Idx(val table: Int) : Col()
    data class Code(val kind: Coded) : Col()
}

/** Esquema de colunas de cada tabela que este emissor sabe escrever. */
object Schema {

    private val schemas = HashMap<Int, List<Col>>()

    operator fun get(table: Int): List<Col> =
        schemas[table] ?: error("tabela 0x${table.toString(16)} sem esquema")

    fun has(table: Int) = schemas.containsKey(table)

    private fun define(table: Int, vararg cols: Col) {
        schemas[table] = cols.toList()
    }

    init {
        define(Tbl.MODULE, Col.U16, Col.Str, Col.Guid, Col.Guid, Col.Guid)
        define(Tbl.TYPE_REF, Col.Code(Coded.RESOLUTION_SCOPE), Col.Str, Col.Str)
        define(
            Tbl.TYPE_DEF, Col.U32, Col.Str, Col.Str,
            Col.Code(Coded.TYPE_DEF_OR_REF), Col.Idx(Tbl.FIELD), Col.Idx(Tbl.METHOD_DEF)
        )
        define(Tbl.FIELD, Col.U16, Col.Str, Col.Blob)
        define(Tbl.METHOD_DEF, Col.U32, Col.U16, Col.U16, Col.Str, Col.Blob, Col.Idx(Tbl.PARAM))
        define(Tbl.PARAM, Col.U16, Col.U16, Col.Str)
        define(Tbl.INTERFACE_IMPL, Col.Idx(Tbl.TYPE_DEF), Col.Code(Coded.TYPE_DEF_OR_REF))
        define(Tbl.MEMBER_REF, Col.Code(Coded.MEMBER_REF_PARENT), Col.Str, Col.Blob)
        define(Tbl.CONSTANT, Col.U8, Col.U8, Col.Code(Coded.HAS_CONSTANT), Col.Blob)
        define(
            Tbl.CUSTOM_ATTRIBUTE, Col.Code(Coded.HAS_CUSTOM_ATTRIBUTE),
            Col.Code(Coded.CUSTOM_ATTRIBUTE_TYPE), Col.Blob
        )
        define(Tbl.STAND_ALONE_SIG, Col.Blob)
        define(Tbl.EVENT_MAP, Col.Idx(Tbl.TYPE_DEF), Col.Idx(Tbl.EVENT))
        define(Tbl.EVENT, Col.U16, Col.Str, Col.Code(Coded.TYPE_DEF_OR_REF))
        define(Tbl.PROPERTY_MAP, Col.Idx(Tbl.TYPE_DEF), Col.Idx(Tbl.PROPERTY))
        define(Tbl.PROPERTY, Col.U16, Col.Str, Col.Blob)
        define(
            Tbl.METHOD_SEMANTICS, Col.U16, Col.Idx(Tbl.METHOD_DEF),
            Col.Code(Coded.HAS_SEMANTICS)
        )
        define(Tbl.TYPE_SPEC, Col.Blob)
        define(
            Tbl.ASSEMBLY, Col.U32, Col.U16, Col.U16, Col.U16, Col.U16,
            Col.U32, Col.Blob, Col.Str, Col.Str
        )
        define(
            Tbl.ASSEMBLY_REF, Col.U16, Col.U16, Col.U16, Col.U16, Col.U32,
            Col.Blob, Col.Str, Col.Str, Col.Blob
        )
        define(Tbl.NESTED_CLASS, Col.Idx(Tbl.TYPE_DEF), Col.Idx(Tbl.TYPE_DEF))
        define(Tbl.GENERIC_PARAM, Col.U16, Col.U16, Col.Code(Coded.TYPE_OR_METHOD_DEF), Col.Str)
        define(Tbl.METHOD_SPEC, Col.Code(Coded.METHOD_DEF_OR_REF), Col.Blob)
        define(
            Tbl.GENERIC_PARAM_CONSTRAINT, Col.Idx(Tbl.GENERIC_PARAM),
            Col.Code(Coded.TYPE_DEF_OR_REF)
        )
    }
}

/**
 * Mascara Sorted, copiada do que o Mono.Cecil grava.
 *
 * As tabelas que o runtime pesquisa por busca binaria tem que sair ordenadas,
 * e so elas dariam 0x16003301FA00 - ClassLayout, Constant, CustomAttribute,
 * DeclSecurity, FieldLayout, FieldMarshal, FieldRVA, GenericParam,
 * GenericParamConstraint, ImplMap, InterfaceImpl, MethodImpl, MethodSemantics
 * e NestedClass. Medido nos DLLs de referencia, porem, o Cecil grava
 * 0x00C416003301FA00: ele liga tambem os bits 50, 54 e 55, que nao
 * correspondem a tabela nenhuma (a norma para em 0x2C).
 *
 * Sao bits inertes, mas a saida deste emissor tem que ser indistinguivel da do
 * dumper de PC para as ferramentas que leem os dois, e igualar custa uma
 * constante.
 */
private const val SORTED_MASK = 0x00C416003301FA00L

/** Coleciona as linhas e depois grava a stream #~ inteira. */
class TableSet {

    private val rows = Array(Tbl.COUNT) { mutableListOf<IntArray>() }

    fun count(table: Int): Int = rows[table].size

    /** Acrescenta uma linha e devolve o RID dela, que e 1-based. */
    fun add(table: Int, vararg values: Int): Int {
        val expected = Schema[table].size
        require(values.size == expected) {
            "tabela 0x${table.toString(16)} espera $expected colunas, veio ${values.size}"
        }
        rows[table] += values
        return rows[table].size
    }

    fun row(table: Int, rid: Int): IntArray = rows[table][rid - 1]

    /**
     * Reordena uma tabela pelas chaves indicadas e devolve o mapa
     * "rid antigo -> rid novo". Usado nas tabelas que tem que sair ordenadas.
     */
    fun sortBy(table: Int, vararg columns: Int) {
        val list = rows[table]
        if (list.size < 2) return
        list.sortWith(Comparator { a, b ->
            for (c in columns) {
                val diff = a[c].compareTo(b[c])
                if (diff != 0) return@Comparator diff
            }
            0
        })
    }

    private fun indexWidth(table: Int): Int = if (count(table) >= 0x10000) 4 else 2

    private fun codedWidth(kind: Coded): Int {
        val limit = 1 shl (16 - kind.bits)
        for (t in kind.tables) {
            if (t >= 0 && count(t) >= limit) return 4
        }
        return 2
    }

    private fun width(col: Col, heapSizes: Int): Int = when (col) {
        is Col.U8 -> 1
        is Col.U16 -> 2
        is Col.U32 -> 4
        is Col.Str -> if (heapSizes and 0x01 != 0) 4 else 2
        is Col.Guid -> if (heapSizes and 0x02 != 0) 4 else 2
        is Col.Blob -> if (heapSizes and 0x04 != 0) 4 else 2
        is Col.Idx -> indexWidth(col.table)
        is Col.Code -> codedWidth(col.kind)
    }

    /**
     * Grava a stream #~ completa: cabecalho, mascaras, contagens e as linhas.
     * heapSizes vem de fora porque depende do tamanho final dos heaps.
     */
    fun write(heapSizes: Int): ByteArray {
        val out = ByteBuffer(1 shl 16)

        var valid = 0L
        for (t in 0 until Tbl.COUNT) {
            if (rows[t].isNotEmpty()) valid = valid or (1L shl t)
        }

        out.u32(0)
        out.u8(2)
        out.u8(0)
        out.u8(heapSizes)
        out.u8(1)
        out.u64(valid)
        out.u64(SORTED_MASK)
        for (t in 0 until Tbl.COUNT) {
            if (rows[t].isNotEmpty()) out.u32(rows[t].size)
        }

        for (t in 0 until Tbl.COUNT) {
            val list = rows[t]
            if (list.isEmpty()) continue
            val cols = Schema[t]
            val widths = IntArray(cols.size) { width(cols[it], heapSizes) }
            for (r in list) {
                for (c in cols.indices) {
                    when (widths[c]) {
                        1 -> out.u8(r[c])
                        2 -> out.u16(r[c])
                        else -> out.u32(r[c])
                    }
                }
            }
        }
        return out.toByteArray()
    }
}
