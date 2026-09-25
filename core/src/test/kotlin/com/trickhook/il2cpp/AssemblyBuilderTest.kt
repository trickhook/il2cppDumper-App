package com.trickhook.il2cpp

import com.trickhook.il2cpp.dotnet.AssemblyBuilder
import com.trickhook.il2cpp.dotnet.Elem
import com.trickhook.il2cpp.dotnet.SigType
import com.trickhook.il2cpp.dotnet.Tbl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AssemblyBuilderTest {

    @Test
    fun `builds an assembly with the shapes DummyDll needs`() {
        val b = AssemblyBuilder("Sample", "Sample.dll", intArrayOf(1, 2, 3, 4))

        val mscorlib = b.addAssemblyRef("mscorlib", 4, 0, 0, 0)
        val dummy = b.addAssemblyRef("Il2CppDummyDll", 1, 0, 0, 0)
        val objectRef = b.addTypeRef(mscorlib, "System", "Object")
        val valueTypeRef = b.addTypeRef(mscorlib, "System", "ValueType")
        val enumRef = b.addTypeRef(mscorlib, "System", "Enum")
        val listRef = b.addTypeRef(mscorlib, "System.Collections.Generic", "List`1")
        val disposableRef = b.addTypeRef(mscorlib, "System", "IDisposable")

        val tokenCtor = b.addAttributeCtorRef(b.addTypeRef(dummy, "Il2CppDummyDll", "TokenAttribute"))
        val addressCtor = b.addAttributeCtorRef(b.addTypeRef(dummy, "Il2CppDummyDll", "AddressAttribute"))
        val fieldOffsetCtor =
            b.addAttributeCtorRef(b.addTypeRef(dummy, "Il2CppDummyDll", "FieldOffsetAttribute"))

        val objectSig = SigType.Named(Tbl.TYPE_REF, objectRef, false)
        val valueTypeSig = SigType.Named(Tbl.TYPE_REF, valueTypeRef, false)

        // Tipo generico com restricao, propriedade, evento e metodo.
        val box = b.defineType("Sample", "Box`1", 0x00100001, objectSig)
        box.genericParams += AssemblyBuilder.GenericParamDef("T", 0, 0).apply {
            constraints += SigType.Named(Tbl.TYPE_REF, disposableRef, false)
        }
        box.attributes += AssemblyBuilder.AttributeUse(tokenCtor, listOf("Token" to "0x2000001"))

        val tSig = SigType.GenericParam(0, onMethod = false)
        val valueField = AssemblyBuilder.FieldDef("value", 0x0001, tSig)
        valueField.attributes += AssemblyBuilder.AttributeUse(
            fieldOffsetCtor, listOf("Offset" to "0x10")
        )
        box.fields += valueField

        val getter = AssemblyBuilder.MethodDef(
            "get_Value", 0x0886, 0, tSig, emptyList(), hasThis = true
        ).apply {
            body = AssemblyBuilder.BodyKind.Null
            attributes += AssemblyBuilder.AttributeUse(
                addressCtor, listOf("RVA" to "0x1234", "Offset" to "0x1234", "VA" to "0x7000001234")
            )
        }
        box.methods += getter
        box.properties += AssemblyBuilder.PropertyDef("Value", 0, tSig, hasThis = true).apply {
            this.getter = getter
        }

        // Struct, para exercitar corpo com local de tipo por valor.
        val point = b.defineType("Sample", "Point", 0x00100109, valueTypeSig)
        point.fields += AssemblyBuilder.FieldDef("X", 0x0006, SigType.Primitive(Elem.I4))
        val pointSig = point.asSig(isValueType = true)

        val holder = b.defineType("Sample", "Holder", 0x00100001, objectSig)
        holder.interfaces += SigType.Named(Tbl.TYPE_REF, disposableRef, false)
        holder.fields += AssemblyBuilder.FieldDef(
            "items", 0x0001,
            SigType.GenericInstance(
                SigType.Named(Tbl.TYPE_REF, listRef, false),
                listOf(SigType.Primitive(Elem.I4))
            )
        )
        holder.methods += AssemblyBuilder.MethodDef(
            "MakePoint", 0x0086, 0, pointSig, emptyList(), hasThis = true
        ).apply { body = AssemblyBuilder.BodyKind.DefaultValueType }
        holder.methods += AssemblyBuilder.MethodDef(
            "Dispose", 0x01E6, 0, SigType.VOID, emptyList(), hasThis = true
        ).apply { body = AssemblyBuilder.BodyKind.Void }
        holder.methods += AssemblyBuilder.MethodDef(
            "Swap", 0x0086, 0,
            SigType.GenericParam(0, onMethod = true),
            listOf(SigType.ByRef(SigType.GenericParam(0, onMethod = true))),
            hasThis = true
        ).apply {
            genericParams += AssemblyBuilder.GenericParamDef("TItem", 0, 0)
            params += AssemblyBuilder.ParamDef("item", 0, 1)
            body = AssemblyBuilder.BodyKind.Null
        }

        // Tipo aninhado.
        val inner = b.defineType("", "Inner", 0x00100002, objectSig)
        inner.declaringType = holder

        // Enum com constante, que exercita a tabela Constant.
        val mode = b.defineType("Sample", "Mode", 0x00100101, SigType.Named(Tbl.TYPE_REF, enumRef, false))
        mode.fields += AssemblyBuilder.FieldDef("value__", 0x0601, SigType.Primitive(Elem.I4))
        mode.fields += AssemblyBuilder.FieldDef("Off", 0x8056, mode.asSig(isValueType = true)).apply {
            constant = AssemblyBuilder.Constant(Elem.I4, byteArrayOf(0, 0, 0, 0))
        }
        mode.fields += AssemblyBuilder.FieldDef("On", 0x8056, mode.asSig(isValueType = true)).apply {
            constant = AssemblyBuilder.Constant(Elem.I4, byteArrayOf(1, 0, 0, 0))
        }

        val image = b.build()
        val out = File(System.getProperty("java.io.tmpdir"), "trickhook-builder.dll")
        out.writeBytes(image)
        println("gravado em ${out.absolutePath} (${image.size} bytes, ${b.typeCount} tipos)")
        assertTrue(image.size > 0x400)
    }
}
