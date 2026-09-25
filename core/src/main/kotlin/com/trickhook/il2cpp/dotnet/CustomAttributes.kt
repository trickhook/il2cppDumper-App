package com.trickhook.il2cpp.dotnet

/**
 * Monta os blobs de atributo customizado (ECMA-335 II.23.3).
 *
 * O DummyDll usa isso para pendurar em cada membro o endereco, o offset no
 * arquivo e o token que ele tinha no jogo - e a informacao que faz o dump
 * servir para alguma coisa alem de listar nomes.
 *
 * Todos os atributos do Il2CppDummyDll tem construtor sem parametros e
 * carregam os dados em CAMPOS, nao em argumentos posicionais, entao o blob e
 * sempre: prologo 0x0001, zero argumentos fixos, e a lista de campos nomeados.
 */
object CustomAttributes {

    private const val PROLOG = 0x0001

    /** Marca que distingue campo de propriedade na secao nomeada. */
    private const val FIELD = 0x53

    /** ELEMENT_TYPE_STRING, o unico tipo que estes atributos usam. */
    private const val STRING = Elem.STRING

    /**
     * Blob de um atributo cujo construtor nao recebe nada e que so tem campos
     * string, que e a forma dos cinco atributos do Il2CppDummyDll.
     */
    fun withStringFields(fields: List<Pair<String, String?>>): ByteArray {
        val out = ByteBuffer(32)
        out.u16(PROLOG)
        // Nenhum argumento posicional: o construtor e sem parametros.
        out.u16(fields.size)
        for ((name, value) in fields) {
            out.u8(FIELD)
            out.u8(STRING)
            writeSerString(out, name)
            writeSerString(out, value)
        }
        return out.toByteArray()
    }

    fun withStringField(name: String, value: String?): ByteArray =
        withStringFields(listOf(name to value))

    /** Atributo sem argumento nenhum. */
    fun empty(): ByteArray {
        val out = ByteBuffer(4)
        out.u16(PROLOG)
        out.u16(0)
        return out.toByteArray()
    }

    /**
     * SerString: tamanho comprimido e UTF-8, com 0xFF representando null - que
     * e diferente de string vazia, codificada como tamanho zero.
     */
    private fun writeSerString(out: ByteBuffer, value: String?) {
        if (value == null) {
            out.u8(0xFF)
            return
        }
        val raw = value.toByteArray(Charsets.UTF_8)
        out.compressed(raw.size)
        out.bytes(raw)
    }
}
