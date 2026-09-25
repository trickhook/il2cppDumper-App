package com.trickhook.il2cpp.dotnet

/**
 * Junta os corpos de metodo num unico blob e devolve o RVA de cada um.
 *
 * O DummyDll nao reimplementa o jogo: cada metodo so precisa de um corpo que
 * compile e retorne algo do tipo certo, para que o assembly possa ser aberto e
 * ate referenciado. Sao tres formas, as mesmas que o dumper de PC emite:
 *
 *   void            -> ret
 *   tipo por valor  -> ldloca.s 0 / initobj T / ldloc.0 / ret
 *   resto           -> ldnull / ret
 *
 * So a forma do meio tem variavel local, e e por isso que ela exige cabecalho
 * "fat" e uma linha na tabela StandAloneSig - as outras duas cabem no
 * cabecalho "tiny" de um byte.
 */
class MethodBodyWriter {

    private val buffer = ByteBuffer(1 shl 12)

    private companion object {
        const val OP_LDNULL = 0x14
        const val OP_LDLOCA_S = 0x12
        const val OP_LDLOC_0 = 0x06
        const val OP_RET = 0x2A
        const val OP_PREFIX = 0xFE
        const val OP_INITOBJ = 0x15

        const val TINY_FORMAT = 0x02
        const val FAT_FORMAT = 0x03
        const val INIT_LOCALS = 0x10

        /** Acima disto o cabecalho tiny nao serve. */
        const val TINY_MAX_CODE = 64
    }

    val size: Int get() = buffer.size

    fun toByteArray(): ByteArray = buffer.toByteArray()

    /** `ret` puro, para metodos que devolvem void. */
    fun returnVoid(): Int = emit(byteArrayOf(OP_RET.toByte()), maxStack = 0, localsToken = 0)

    /** `ldnull; ret`, para qualquer tipo por referencia. */
    fun returnNull(): Int =
        emit(byteArrayOf(OP_LDNULL.toByte(), OP_RET.toByte()), maxStack = 1, localsToken = 0)

    /**
     * Zera um local do tipo de retorno e devolve ele, que e a unica forma
     * valida para um tipo por valor sem saber construi-lo.
     * [typeToken] e o token de metadata do tipo de retorno.
     * [localsToken] e o token da linha de StandAloneSig com esse local.
     */
    fun returnDefaultValueType(typeToken: Int, localsToken: Int): Int {
        val code = ByteBuffer(12)
        code.u8(OP_LDLOCA_S)
        code.u8(0)
        code.u8(OP_PREFIX)
        code.u8(OP_INITOBJ)
        code.u32(typeToken)
        code.u8(OP_LDLOC_0)
        code.u8(OP_RET)
        return emit(code.toByteArray(), maxStack = 1, localsToken = localsToken)
    }

    private fun emit(code: ByteArray, maxStack: Int, localsToken: Int): Int {
        val tiny = localsToken == 0 && code.size < TINY_MAX_CODE
        if (!tiny) {
            // Cabecalho fat tem que cair em fronteira de 4 bytes.
            buffer.align(4)
        }
        val rva = PeWriter.CODE_RVA + buffer.size
        if (tiny) {
            buffer.u8((code.size shl 2) or TINY_FORMAT)
        } else {
            buffer.u16(((3 shl 12) or FAT_FORMAT or INIT_LOCALS))
            buffer.u16(maxOf(maxStack, 1))
            buffer.u32(code.size)
            buffer.u32(localsToken)
        }
        buffer.bytes(code)
        return rva
    }
}
