package com.trickhook.il2cpp

import com.trickhook.il2cpp.dotnet.BlobHeap
import com.trickhook.il2cpp.dotnet.Coded
import com.trickhook.il2cpp.dotnet.Elem
import com.trickhook.il2cpp.dotnet.MethodBodyWriter
import com.trickhook.il2cpp.dotnet.GuidHeap
import com.trickhook.il2cpp.dotnet.MetadataRoot
import com.trickhook.il2cpp.dotnet.PeWriter
import com.trickhook.il2cpp.dotnet.SigType
import com.trickhook.il2cpp.dotnet.Signatures
import com.trickhook.il2cpp.dotnet.StringHeap
import com.trickhook.il2cpp.dotnet.TableSet
import com.trickhook.il2cpp.dotnet.Tbl
import com.trickhook.il2cpp.dotnet.UserStringHeap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotnetEmitterTest {

    /**
     * Gera o assembly mais simples que ainda e valido: modulo, o tipo
     * <Module> obrigatorio, um tipo proprio herdando de System.Object, e a
     * referencia ao mscorlib.
     */
    private fun buildMinimal(): ByteArray {
        val tables = TableSet()
        val strings = StringHeap()
        val blobs = BlobHeap()
        val guids = GuidHeap()
        val userStrings = UserStringHeap()

        val mvid = guids.add(ByteArray(16) { (it + 1).toByte() })
        tables.add(Tbl.MODULE, 0, strings.add("Hello.dll"), mvid, 0, 0)

        val mscorlib = tables.add(
            Tbl.ASSEMBLY_REF,
            4, 0, 0, 0, 0, 0, strings.add("mscorlib"), 0, 0
        )
        val objectRef = tables.add(
            Tbl.TYPE_REF,
            Coded.RESOLUTION_SCOPE.encode(Tbl.ASSEMBLY_REF, mscorlib),
            strings.add("Object"), strings.add("System")
        )

        // A primeira linha de TypeDef e sempre o pseudo-tipo <Module>.
        tables.add(Tbl.TYPE_DEF, 0, strings.add("<Module>"), 0, 0, 1, 1)
        tables.add(
            Tbl.TYPE_DEF,
            0x00100001,
            strings.add("Hello"), strings.add("Sample"),
            Coded.TYPE_DEF_OR_REF.encode(Tbl.TYPE_REF, objectRef),
            1, 1
        )

        tables.add(
            Tbl.ASSEMBLY,
            0x8004, 1, 0, 0, 0, 0, 0, strings.add("Hello"), 0
        )

        val metadata = MetadataRoot.build(tables, strings, userStrings, guids, blobs)
        return PeWriter(metadata).build()
    }

    /**
     * Assembly com campos, metodos e as tres formas de corpo, que e o que
     * exercita o codificador de assinaturas de verdade.
     */
    private fun buildRich(): ByteArray {
        val tables = TableSet()
        val strings = StringHeap()
        val blobs = BlobHeap()
        val guids = GuidHeap()
        val userStrings = UserStringHeap()
        val bodies = MethodBodyWriter()

        val mvid = guids.add(ByteArray(16) { (it * 7 + 3).toByte() })
        tables.add(Tbl.MODULE, 0, strings.add("Rich.dll"), mvid, 0, 0)

        val mscorlib = tables.add(Tbl.ASSEMBLY_REF, 4, 0, 0, 0, 0, 0, strings.add("mscorlib"), 0, 0)
        fun typeRef(ns: String, name: String) = tables.add(
            Tbl.TYPE_REF,
            Coded.RESOLUTION_SCOPE.encode(Tbl.ASSEMBLY_REF, mscorlib),
            strings.add(name), strings.add(ns)
        )
        val objectRef = typeRef("System", "Object")
        val valueTypeRef = typeRef("System", "ValueType")
        val listRef = typeRef("System.Collections.Generic", "List`1")

        val objectSig = SigType.Named(Tbl.TYPE_REF, objectRef, isValueType = false)

        tables.add(Tbl.TYPE_DEF, 0, strings.add("<Module>"), 0, 0, 1, 1)

        // Um struct, para termos um tipo por valor de verdade como retorno.
        val pointDef = tables.add(
            Tbl.TYPE_DEF, 0x00100109,
            strings.add("Point"), strings.add("Sample"),
            Coded.TYPE_DEF_OR_REF.encode(Tbl.TYPE_REF, valueTypeRef),
            1, 1
        )
        val pointSig = SigType.Named(Tbl.TYPE_DEF, pointDef, isValueType = true)
        tables.add(Tbl.FIELD, 0x0006, strings.add("X"), blobs.add(Signatures.field(SigType.Primitive(Elem.I4))))
        tables.add(Tbl.FIELD, 0x0006, strings.add("Y"), blobs.add(Signatures.field(SigType.Primitive(Elem.I4))))

        val typeStart = tables.count(Tbl.FIELD) + 1
        val methodStart = tables.count(Tbl.METHOD_DEF) + 1
        val richDef = tables.add(
            Tbl.TYPE_DEF, 0x00100001,
            strings.add("Rich"), strings.add("Sample"),
            Coded.TYPE_DEF_OR_REF.encode(Tbl.TYPE_REF, objectRef),
            typeStart, methodStart
        )

        tables.add(
            Tbl.FIELD, 0x0006, strings.add("Names"),
            blobs.add(Signatures.field(SigType.SzArray(SigType.STRING)))
        )
        tables.add(
            Tbl.FIELD, 0x0006, strings.add("Items"),
            blobs.add(
                Signatures.field(
                    SigType.GenericInstance(
                        SigType.Named(Tbl.TYPE_REF, listRef, isValueType = false),
                        listOf(SigType.Primitive(Elem.I4))
                    )
                )
            )
        )
        tables.add(
            Tbl.FIELD, 0x0006, strings.add("Grid"),
            blobs.add(Signatures.field(SigType.Array(SigType.Primitive(Elem.R8), rank = 2)))
        )

        // void DoNothing(int, string)
        val paramStart = tables.count(Tbl.PARAM) + 1
        tables.add(
            Tbl.METHOD_DEF, bodies.returnVoid(), 0, 0x0086,
            strings.add("DoNothing"),
            blobs.add(
                Signatures.method(
                    SigType.VOID,
                    listOf(SigType.Primitive(Elem.I4), SigType.STRING),
                    hasThis = true
                )
            ),
            paramStart
        )
        tables.add(Tbl.PARAM, 0, 1, strings.add("count"))
        tables.add(Tbl.PARAM, 0, 2, strings.add("label"))

        // object GetObject()
        tables.add(
            Tbl.METHOD_DEF, bodies.returnNull(), 0, 0x0086,
            strings.add("GetObject"),
            blobs.add(Signatures.method(objectSig, emptyList(), hasThis = true)),
            tables.count(Tbl.PARAM) + 1
        )

        // Point GetPoint() - o unico que precisa de local e StandAloneSig
        val localsRid = tables.add(Tbl.STAND_ALONE_SIG, blobs.add(Signatures.locals(listOf(pointSig))))
        val localsToken = (Tbl.STAND_ALONE_SIG shl 24) or localsRid
        val pointToken = (Tbl.TYPE_DEF shl 24) or pointDef
        tables.add(
            Tbl.METHOD_DEF,
            bodies.returnDefaultValueType(pointToken, localsToken), 0, 0x0086,
            strings.add("GetPoint"),
            blobs.add(Signatures.method(pointSig, emptyList(), hasThis = true)),
            tables.count(Tbl.PARAM) + 1
        )

        // ref int Passthrough(ref int)
        tables.add(
            Tbl.METHOD_DEF, bodies.returnNull(), 0, 0x0086,
            strings.add("Passthrough"),
            blobs.add(
                Signatures.method(
                    SigType.ByRef(SigType.Primitive(Elem.I4)),
                    listOf(SigType.ByRef(SigType.Primitive(Elem.I4))),
                    hasThis = true
                )
            ),
            tables.count(Tbl.PARAM) + 1
        )

        tables.add(Tbl.ASSEMBLY, 0x8004, 2, 1, 0, 0, 0, 0, strings.add("Rich"), 0)

        val metadata = MetadataRoot.build(tables, strings, userStrings, guids, blobs)
        return PeWriter(metadata, bodies.toByteArray()).build()
    }

    @Test
    fun `emits an assembly with signatures and bodies`() {
        val image = buildRich()
        val out = File(System.getProperty("java.io.tmpdir"), "trickhook-rich.dll")
        out.writeBytes(image)
        println("gravado em ${out.absolutePath} (${image.size} bytes)")
        assertTrue(image.size > 0x400)
    }

    @Test
    fun `emits a loadable minimal assembly`() {
        val image = buildMinimal()

        assertEquals(0x4D.toByte(), image[0], "assinatura MZ")
        assertEquals(0x5A.toByte(), image[1], "assinatura MZ")
        val peAt = readInt(image, 0x3c)
        assertEquals(0x00004550, readInt(image, peAt), "assinatura PE")
        assertEquals(0x014C, readShort(image, peAt + 4), "machine i386")
        assertEquals(2, readShort(image, peAt + 6), "duas secoes")
        assertEquals(0x010B, readShort(image, peAt + 24), "optional header PE32")
        assertEquals(0, image.size % 0x200, "tamanho alinhado ao file alignment")

        val out = File(System.getProperty("java.io.tmpdir"), "trickhook-minimal.dll")
        out.writeBytes(image)
        println("gravado em ${out.absolutePath} (${image.size} bytes)")
        assertTrue(image.size >= 0x400, "imagem pequena demais: ${image.size}")
    }

    private fun readInt(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun readShort(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}
