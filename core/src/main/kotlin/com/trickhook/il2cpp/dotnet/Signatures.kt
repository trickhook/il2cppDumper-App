package com.trickhook.il2cpp.dotnet

/** Codigos ELEMENT_TYPE (ECMA-335 II.23.1.16). */
object Elem {
    const val END = 0x00
    const val VOID = 0x01
    const val BOOLEAN = 0x02
    const val CHAR = 0x03
    const val I1 = 0x04
    const val U1 = 0x05
    const val I2 = 0x06
    const val U2 = 0x07
    const val I4 = 0x08
    const val U4 = 0x09
    const val I8 = 0x0A
    const val U8 = 0x0B
    const val R4 = 0x0C
    const val R8 = 0x0D
    const val STRING = 0x0E
    const val PTR = 0x0F
    const val BYREF = 0x10
    const val VALUETYPE = 0x11
    const val CLASS = 0x12
    const val VAR = 0x13
    const val ARRAY = 0x14
    const val GENERICINST = 0x15
    const val TYPEDBYREF = 0x16
    const val I = 0x18
    const val U = 0x19
    const val FNPTR = 0x1B
    const val OBJECT = 0x1C
    const val SZARRAY = 0x1D
    const val MVAR = 0x1E
    const val CMOD_REQD = 0x1F
    const val CMOD_OPT = 0x20
    const val SENTINEL = 0x41
    const val PINNED = 0x45
}

/** Convencoes de chamada que aparecem no primeiro byte da assinatura. */
object CallConv {
    const val DEFAULT = 0x00
    const val VARARG = 0x05
    const val FIELD = 0x06
    const val LOCAL = 0x07
    const val PROPERTY = 0x08
    const val GENERIC = 0x10
    const val HAS_THIS = 0x20
    const val EXPLICIT_THIS = 0x40
}

/**
 * Como um tipo aparece dentro de uma assinatura.
 *
 * E uma arvore em vez de um token porque tipos compostos - array de lista de
 * T, referencia a ponteiro - so existem codificados na propria assinatura, nao
 * como linha de tabela.
 */
sealed class SigType {

    /** Tipos primitivos, que sao um byte so. */
    data class Primitive(val code: Int) : SigType()

    /** Referencia a uma TypeDef, TypeRef ou TypeSpec. */
    data class Named(val table: Int, val rid: Int, val isValueType: Boolean) : SigType()

    data class SzArray(val element: SigType) : SigType()

    /** Array multidimensional, com limites opcionais. */
    data class Array(
        val element: SigType,
        val rank: Int,
        val sizes: IntArray = IntArray(0),
        val lowerBounds: IntArray = IntArray(0)
    ) : SigType() {
        override fun equals(other: Any?): Boolean =
            other is Array && element == other.element && rank == other.rank &&
                sizes.contentEquals(other.sizes) && lowerBounds.contentEquals(other.lowerBounds)

        override fun hashCode(): Int =
            (element.hashCode() * 31 + rank) * 31 + sizes.contentHashCode()
    }

    data class ByRef(val element: SigType) : SigType()
    data class Pointer(val element: SigType) : SigType()

    /** Parametro generico de tipo (!n) ou de metodo (!!n). */
    data class GenericParam(val index: Int, val onMethod: Boolean) : SigType()

    data class GenericInstance(val definition: Named, val arguments: List<SigType>) : SigType()

    companion object {
        val VOID = Primitive(Elem.VOID)
        val OBJECT = Primitive(Elem.OBJECT)
        val STRING = Primitive(Elem.STRING)
    }
}

/**
 * Escreve blobs de assinatura.
 *
 * As referencias a tipo usam o indice codificado TypeDefOrRef comprimido, que
 * NAO e o mesmo campo de 2 ou 4 bytes das colunas de tabela: aqui ele sempre
 * sai como inteiro comprimido, independentemente do tamanho das tabelas.
 */
object Signatures {

    private fun typeDefOrRef(out: ByteBuffer, table: Int, rid: Int) {
        out.compressed(Coded.TYPE_DEF_OR_REF.encode(table, rid))
    }

    fun writeType(out: ByteBuffer, type: SigType) {
        when (type) {
            is SigType.Primitive -> out.u8(type.code)

            is SigType.Named -> {
                out.u8(if (type.isValueType) Elem.VALUETYPE else Elem.CLASS)
                typeDefOrRef(out, type.table, type.rid)
            }

            is SigType.SzArray -> {
                out.u8(Elem.SZARRAY)
                writeType(out, type.element)
            }

            is SigType.Array -> {
                out.u8(Elem.ARRAY)
                writeType(out, type.element)
                out.compressed(type.rank)
                out.compressed(type.sizes.size)
                for (s in type.sizes) out.compressed(s)
                out.compressed(type.lowerBounds.size)
                for (b in type.lowerBounds) out.compressedSigned(b)
            }

            is SigType.ByRef -> {
                out.u8(Elem.BYREF)
                writeType(out, type.element)
            }

            is SigType.Pointer -> {
                out.u8(Elem.PTR)
                writeType(out, type.element)
            }

            is SigType.GenericParam -> {
                out.u8(if (type.onMethod) Elem.MVAR else Elem.VAR)
                out.compressed(type.index)
            }

            is SigType.GenericInstance -> {
                out.u8(Elem.GENERICINST)
                out.u8(if (type.definition.isValueType) Elem.VALUETYPE else Elem.CLASS)
                typeDefOrRef(out, type.definition.table, type.definition.rid)
                out.compressed(type.arguments.size)
                for (a in type.arguments) writeType(out, a)
            }
        }
    }

    fun field(type: SigType): ByteArray {
        val out = ByteBuffer(8)
        out.u8(CallConv.FIELD)
        writeType(out, type)
        return out.toByteArray()
    }

    fun method(
        returnType: SigType,
        parameters: List<SigType>,
        hasThis: Boolean,
        explicitThis: Boolean = false,
        genericParameterCount: Int = 0,
        varArg: Boolean = false
    ): ByteArray {
        val out = ByteBuffer(16)
        var first = if (varArg) CallConv.VARARG else CallConv.DEFAULT
        if (hasThis) first = first or CallConv.HAS_THIS
        if (explicitThis) first = first or CallConv.EXPLICIT_THIS
        if (genericParameterCount > 0) first = first or CallConv.GENERIC
        out.u8(first)
        if (genericParameterCount > 0) out.compressed(genericParameterCount)
        out.compressed(parameters.size)
        writeType(out, returnType)
        for (p in parameters) writeType(out, p)
        return out.toByteArray()
    }

    fun property(type: SigType, parameters: List<SigType>, hasThis: Boolean): ByteArray {
        val out = ByteBuffer(12)
        out.u8(CallConv.PROPERTY or (if (hasThis) CallConv.HAS_THIS else 0))
        out.compressed(parameters.size)
        writeType(out, type)
        for (p in parameters) writeType(out, p)
        return out.toByteArray()
    }

    /** Assinatura de variaveis locais, referenciada pela tabela StandAloneSig. */
    fun locals(types: List<SigType>): ByteArray {
        val out = ByteBuffer(8)
        out.u8(CallConv.LOCAL)
        out.compressed(types.size)
        for (t in types) writeType(out, t)
        return out.toByteArray()
    }

    /** Assinatura avulsa de tipo, usada pela tabela TypeSpec. */
    fun typeSpec(type: SigType): ByteArray {
        val out = ByteBuffer(8)
        writeType(out, type)
        return out.toByteArray()
    }
}
