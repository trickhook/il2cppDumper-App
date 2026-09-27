using System;
using System.Collections.Generic;
using System.Collections.Immutable;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Text;
using Mono.Cecil;
using TypeDefinition = Mono.Cecil.TypeDefinition;
using ModuleDefinition = Mono.Cecil.ModuleDefinition;
using MethodDefinition = Mono.Cecil.MethodDefinition;
using CustomAttribute = Mono.Cecil.CustomAttribute;
using AssemblyDefinition = Mono.Cecil.AssemblyDefinition;

static class Program
{
    static readonly List<string> SearchDirs = new List<string>();

    static int Main(string[] rawArgs)
    {
        // -L <dir> : extra assembly-resolution search directory (repeatable)
        var args = new List<string>();
        for (int i = 0; i < rawArgs.Length; i++)
        {
            if (rawArgs[i] == "-L" && i + 1 < rawArgs.Length) { SearchDirs.Add(Path.GetFullPath(rawArgs[++i])); continue; }
            args.Add(rawArgs[i]);
        }
        if (args.Count == 0) { Usage(); return 2; }
        try
        {
            switch (args[0])
            {
                case "probe": return Probe(args[1]);
                case "canon": return Canon(args[1], args.Count > 2 ? args[2] : null);
                case "srm": return Srm(args[1]);
                case "diff": return Diff(args[1], args[2]);
                case "folder": return Folder(args[1], args[2]);
                case "castats": return CaStats(args[1]);
                default: Usage(); return 2;
            }
        }
        catch (Exception e) { Console.Error.WriteLine("FATAL " + e); return 3; }
    }

    static void Usage() => Console.Error.WriteLine(
        "asmcanon [-L dir]... probe  <dll>        human-readable types/methods/fields/attrs (Mono.Cecil)\n" +
        "asmcanon [-L dir]... canon  <dll> [out]  deterministic canonical line dump (Mono.Cecil)\n" +
        "asmcanon             srm    <dll>        strict ECMA-335 structural walk (System.Reflection.Metadata)\n" +
        "asmcanon [-L dir]... diff   <A> <B>      canonicalize both and report differences\n" +
        "asmcanon             folder <dirA> <dirB>  compare two whole DummyDll folders\n" +
        "  -L adds an assembly-resolution search dir; Cecil needs the *defining* assembly of any\n" +
        "     enum used as a custom-attribute argument, so point -L at the reference DummyDll folder.");

    // ---------------------------------------------------------------- Cecil

    static ReaderParameters RP(string path)
    {
        var res = new DefaultAssemblyResolver();
        res.AddSearchDirectory(Path.GetDirectoryName(Path.GetFullPath(path)));
        foreach (var d in SearchDirs) res.AddSearchDirectory(d);
        return new ReaderParameters { AssemblyResolver = res, ReadingMode = ReadingMode.Immediate, ReadWrite = false };
    }

    // compare two whole DummyDll folders
    static int Folder(string dirA, string dirB)
    {
        var fa = Directory.GetFiles(dirA).Select(Path.GetFileName).OrderBy(x => x, StringComparer.Ordinal).ToList();
        var fb = Directory.GetFiles(dirB).Select(Path.GetFileName).OrderBy(x => x, StringComparer.Ordinal).ToList();
        int bad = 0;
        foreach (var only in fa.Except(fb)) { Console.WriteLine("MISSING in B: " + only); bad++; }
        foreach (var only in fb.Except(fa)) { Console.WriteLine("EXTRA   in B: " + only); bad++; }
        SearchDirs.Add(Path.GetFullPath(dirA));
        SearchDirs.Add(Path.GetFullPath(dirB));
        foreach (var f in fa.Intersect(fb))
        {
            var pa = Path.Combine(dirA, f); var pb = Path.Combine(dirB, f);
            var ta = Path.GetTempFileName(); var tb = Path.GetTempFileName();
            int d;
            try { Canon(pa, ta); Canon(pb, tb); }
            catch (Exception e) { Console.WriteLine("ERROR " + f + ": " + e.GetType().Name + ": " + e.Message); bad++; continue; }
            var la = File.ReadAllLines(ta); var lb = File.ReadAllLines(tb);
            d = CountDiffs(la, lb);
            File.Delete(ta); File.Delete(tb);
            var byteEq = new FileInfo(pa).Length == new FileInfo(pb).Length &&
                         File.ReadAllBytes(pa).AsSpan().SequenceEqual(File.ReadAllBytes(pb));
            Console.WriteLine((d == 0 ? "OK   " : "DIFF ") + f.PadRight(46) + " canonLines A=" + la.Length + " B=" + lb.Length +
                              " diffs=" + d + " bytesEqual=" + byteEq);
            if (d != 0) bad++;
        }
        Console.WriteLine(bad == 0 ? "FOLDER MATCH" : "FOLDER MISMATCH: " + bad + " file(s)");
        return bad == 0 ? 0 : 1;
    }

    static int CountDiffs(string[] la, string[] lb)
    {
        int n = Math.Min(la.Length, lb.Length), d = Math.Abs(la.Length - lb.Length);
        for (int i = 0; i < n; i++) if (!string.Equals(la[i], lb[i], StringComparison.Ordinal)) d++;
        return d;
    }

    static int Probe(string path)
    {
        using var asm = AssemblyDefinition.ReadAssembly(path, RP(path));
        var m = asm.MainModule;
        Console.WriteLine("assembly : " + asm.Name.FullName);
        Console.WriteLine("module   : " + m.Name + "  kind=" + m.Kind + " runtime=" + m.Runtime + " mvid=" + m.Mvid);
        Console.WriteLine("refs     : " + string.Join(", ", m.AssemblyReferences.Select(r => r.FullName)));
        Console.WriteLine();
        foreach (var t in AllTypes(m))
        {
            Console.WriteLine("type " + t.FullName + "  [token 0x" + t.MetadataToken.ToUInt32().ToString("X8") + "] attrs=0x" + ((uint)t.Attributes).ToString("X8"));
            if (t.BaseType != null) Console.WriteLine("    base       " + t.BaseType.FullName);
            foreach (var i in t.Interfaces) Console.WriteLine("    interface  " + i.InterfaceType.FullName);
            foreach (var a in t.CustomAttributes) Console.WriteLine("    [attr]     " + Attr(a));
            foreach (var f in t.Fields)
            {
                Console.WriteLine("    field      " + f.FieldType.FullName + " " + f.Name + "  attrs=0x" + ((uint)f.Attributes).ToString("X4") + (f.HasConstant ? " = " + Lit(f.Constant) : ""));
                foreach (var a in f.CustomAttributes) Console.WriteLine("        [attr] " + Attr(a));
            }
            foreach (var p in t.Properties)
            {
                Console.WriteLine("    property   " + p.PropertyType.FullName + " " + p.Name + " {" + (p.GetMethod != null ? " get;" : "") + (p.SetMethod != null ? " set;" : "") + " }");
                foreach (var a in p.CustomAttributes) Console.WriteLine("        [attr] " + Attr(a));
            }
            foreach (var e in t.Events)
            {
                Console.WriteLine("    event      " + e.EventType.FullName + " " + e.Name);
                foreach (var a in e.CustomAttributes) Console.WriteLine("        [attr] " + Attr(a));
            }
            foreach (var d in t.Methods)
            {
                Console.WriteLine("    method     " + MethodSig(d) + "  attrs=0x" + ((uint)d.Attributes).ToString("X4") + " impl=0x" + ((ushort)d.ImplAttributes).ToString("X4") + " body=" + (d.HasBody ? d.Body.CodeSize + "B" : "-"));
                foreach (var a in d.CustomAttributes) Console.WriteLine("        [attr] " + Attr(a));
                foreach (var pa in d.Parameters)
                    foreach (var a in pa.CustomAttributes) Console.WriteLine("        [attr on param " + pa.Name + "] " + Attr(a));
            }
            Console.WriteLine();
        }
        return 0;
    }

    static IEnumerable<TypeDefinition> AllTypes(ModuleDefinition m)
    {
        foreach (var t in m.Types) foreach (var x in Walk(t)) yield return x;
    }

    static IEnumerable<TypeDefinition> Walk(TypeDefinition t)
    {
        yield return t;
        foreach (var n in t.NestedTypes) foreach (var x in Walk(n)) yield return x;
    }

    static string Lit(object v)
    {
        if (v == null) return "null";
        if (v is string s) return "\"" + s.Replace("\\", "\\\\").Replace("\"", "\\\"").Replace("\n", "\\n").Replace("\r", "\\r") + "\"";
        if (v is byte[] b) return "byte[" + b.Length + "]:" + Convert.ToHexString(b);
        if (v is char c) return "'\\u" + ((int)c).ToString("X4") + "'";
        if (v is float f) return f.ToString("R", CultureInfo.InvariantCulture);
        if (v is double d) return d.ToString("R", CultureInfo.InvariantCulture);
        if (v is bool b2) return b2 ? "true" : "false";
        if (v is IFormattable f2) return f2.ToString(null, CultureInfo.InvariantCulture);
        return v.ToString();
    }

    static string ArgVal(CustomAttributeArgument a)
    {
        if (a.Value is CustomAttributeArgument inner) return ArgVal(inner);
        if (a.Value is CustomAttributeArgument[] arr) return "{" + string.Join(",", arr.Select(ArgVal)) + "}";
        return a.Type.FullName + "(" + Lit(a.Value) + ")";
    }

    static string Attr(CustomAttribute a)
    {
        var sb = new StringBuilder();
        sb.Append(a.Constructor.DeclaringType.FullName).Append("::.ctor(");
        try { sb.Append(string.Join(",", a.ConstructorArguments.Select(ArgVal))); }
        catch (Exception e) { sb.Append("<CTORARG-ERR:" + e.GetType().Name + ">"); }
        sb.Append(')');
        try
        {
            if (a.HasFields) sb.Append(" F{").Append(string.Join(",", a.Fields.Select(f => f.Name + "=" + ArgVal(f.Argument)))).Append('}');
            if (a.HasProperties) sb.Append(" P{").Append(string.Join(",", a.Properties.Select(f => f.Name + "=" + ArgVal(f.Argument)))).Append('}');
        }
        catch (Exception e) { sb.Append(" <NAMEDARG-ERR:" + e.GetType().Name + ">"); }
        return sb.ToString();
    }

    static string MethodSig(MethodDefinition d)
    {
        var gp = d.HasGenericParameters ? "<" + string.Join(",", d.GenericParameters.Select(g => g.Name)) + ">" : "";
        return d.ReturnType.FullName + " " + d.Name + gp + "(" +
            string.Join(", ", d.Parameters.Select(p => (p.IsOut ? "out " : "") + p.ParameterType.FullName + " " + p.Name + (p.HasConstant ? " = " + Lit(p.Constant) : ""))) + ")";
    }

    // canonical, line-oriented, order-preserving
    static int Canon(string path, string outPath)
    {
        using var asm = AssemblyDefinition.ReadAssembly(path, RP(path));
        var m = asm.MainModule;
        TextWriter w = outPath == null ? Console.Out : new StreamWriter(outPath, false, new UTF8Encoding(false), 1 << 20);
        try
        {
            w.WriteLine("ASM\t" + asm.Name.Name + "\t" + asm.Name.Version + "\tculture=" + asm.Name.Culture + "\tflags=0x" + ((uint)asm.Name.Attributes).ToString("X") + "\thash=" + asm.Name.HashAlgorithm);
            w.WriteLine("MOD\t" + m.Name + "\tkind=" + m.Kind + "\truntime=" + m.Runtime + "\tarch=" + m.Architecture + "\tattrs=" + m.Attributes + "\tchar=" + m.Characteristics);
            foreach (var r in m.AssemblyReferences.OrderBy(x => x.FullName, StringComparer.Ordinal)) w.WriteLine("AREF\t" + r.FullName);
            foreach (var a in asm.CustomAttributes) w.WriteLine("ASMCA\t" + Attr(a));
            foreach (var a in m.CustomAttributes) w.WriteLine("MODCA\t" + Attr(a));
            int ti = 0;
            foreach (var t in AllTypes(m))
            {
                w.WriteLine("TYPE\t" + ti + "\t" + t.FullName + "\tattrs=0x" + ((uint)t.Attributes).ToString("X8") + "\ttok=0x" + t.MetadataToken.ToUInt32().ToString("X8") +
                    "\tbase=" + (t.BaseType != null ? t.BaseType.FullName : "-") + "\tdecl=" + (t.DeclaringType != null ? t.DeclaringType.FullName : "-") +
                    "\tpack=" + t.PackingSize + "\tsize=" + t.ClassSize);
                foreach (var g in t.GenericParameters) w.WriteLine(" TGP\t" + g.Position + "\t" + g.Name + "\tattrs=0x" + ((uint)g.Attributes).ToString("X4") + "\tcons=" + string.Join(",", g.Constraints.Select(x => x.ConstraintType.FullName)));
                foreach (var i in t.Interfaces) w.WriteLine(" IFACE\t" + i.InterfaceType.FullName);
                foreach (var a in t.CustomAttributes) w.WriteLine(" TCA\t" + Attr(a));
                int k = 0;
                foreach (var f in t.Fields)
                    w.WriteLine(" FLD\t" + (k++) + "\t" + f.Name + "\t" + f.FieldType.FullName + "\tattrs=0x" + ((uint)f.Attributes).ToString("X8") + "\ttok=0x" + f.MetadataToken.ToUInt32().ToString("X8") +
                        "\tconst=" + (f.HasConstant ? Lit(f.Constant) : "-") + "\trva=" + f.RVA + "\t|" + string.Join(" ; ", f.CustomAttributes.Select(Attr)));
                k = 0;
                foreach (var d in t.Methods)
                {
                    w.WriteLine(" MTD\t" + (k++) + "\t" + MethodSig(d) + "\tattrs=0x" + ((uint)d.Attributes).ToString("X8") + "\timpl=0x" + ((ushort)d.ImplAttributes).ToString("X4") +
                        "\ttok=0x" + d.MetadataToken.ToUInt32().ToString("X8") + "\tbody=" + (d.HasBody ? d.Body.CodeSize.ToString() : "-") +
                        "\toverrides=" + string.Join(",", d.Overrides.Select(o => o.FullName)) + "\t|" + string.Join(" ; ", d.CustomAttributes.Select(Attr)));
                    foreach (var p in d.Parameters)
                        if (p.HasCustomAttributes) w.WriteLine("  PCA\t" + p.Index + "\t" + p.Name + "\t|" + string.Join(" ; ", p.CustomAttributes.Select(Attr)));
                }
                k = 0;
                foreach (var p in t.Properties)
                    w.WriteLine(" PROP\t" + (k++) + "\t" + p.Name + "\t" + p.PropertyType.FullName + "\tattrs=0x" + ((uint)p.Attributes).ToString("X4") + "\ttok=0x" + p.MetadataToken.ToUInt32().ToString("X8") +
                        "\tget=" + (p.GetMethod != null ? p.GetMethod.Name : "-") + "\tset=" + (p.SetMethod != null ? p.SetMethod.Name : "-") + "\t|" + string.Join(" ; ", p.CustomAttributes.Select(Attr)));
                k = 0;
                foreach (var e in t.Events)
                    w.WriteLine(" EVT\t" + (k++) + "\t" + e.Name + "\t" + e.EventType.FullName + "\tattrs=0x" + ((uint)e.Attributes).ToString("X4") + "\ttok=0x" + e.MetadataToken.ToUInt32().ToString("X8") +
                        "\tadd=" + (e.AddMethod != null ? e.AddMethod.Name : "-") + "\tremove=" + (e.RemoveMethod != null ? e.RemoveMethod.Name : "-") + "\t|" + string.Join(" ; ", e.CustomAttributes.Select(Attr)));
                ti++;
            }
            w.WriteLine("END\ttypes=" + ti);
            w.Flush();
        }
        finally { if (outPath != null) w.Dispose(); }
        return 0;
    }

    static int Diff(string a, string b)
    {
        var ta = Path.GetTempFileName(); var tb = Path.GetTempFileName();
        Canon(a, ta); Canon(b, tb);
        var la = File.ReadAllLines(ta); var lb = File.ReadAllLines(tb);
        int n = Math.Min(la.Length, lb.Length), shown = 0, diffs = 0;
        for (int i = 0; i < n; i++)
            if (!string.Equals(la[i], lb[i], StringComparison.Ordinal))
            {
                diffs++;
                if (shown++ < 40) Console.WriteLine("--- line " + (i + 1) + "\nA: " + la[i] + "\nB: " + lb[i]);
            }
        if (la.Length != lb.Length) Console.WriteLine("line count differs: A=" + la.Length + " B=" + lb.Length);
        bool ok = diffs == 0 && la.Length == lb.Length;
        Console.WriteLine(ok ? "IDENTICAL (canonical)" : "DIFFERENT: " + diffs + " differing lines (length delta " + (la.Length - lb.Length) + ")");
        File.Delete(ta); File.Delete(tb);
        return ok ? 0 : 1;
    }

    // ------------------------------------------- System.Reflection.Metadata

    static int Srm(string path)
    {
        using var fs = File.OpenRead(path);
        using var pe = new PEReader(fs);
        if (!pe.HasMetadata) { Console.Error.WriteLine("NO CLI METADATA"); return 1; }
        var h = pe.PEHeaders;
        Console.WriteLine("pe       : machine=" + h.CoffHeader.Machine + " chars=" + h.CoffHeader.Characteristics + " tsSec=" + h.CoffHeader.TimeDateStamp + " sections=" + h.SectionHeaders.Length);
        Console.WriteLine("opt      : magic=" + h.PEHeader.Magic + " entry=0x" + h.PEHeader.AddressOfEntryPoint.ToString("X") + " imagebase=0x" + h.PEHeader.ImageBase.ToString("X") + " salign=" + h.PEHeader.SectionAlignment + " falign=" + h.PEHeader.FileAlignment);
        Console.WriteLine("corflags : " + h.CorHeader.Flags + " rtver=" + h.CorHeader.MajorRuntimeVersion + "." + h.CorHeader.MinorRuntimeVersion);
        foreach (var s in h.SectionHeaders) Console.WriteLine("section  : " + s.Name.PadRight(8) + " va=0x" + s.VirtualAddress.ToString("X") + " vsize=0x" + s.VirtualSize.ToString("X") + " raw=0x" + s.PointerToRawData.ToString("X") + " rsize=0x" + s.SizeOfRawData.ToString("X") + " " + s.SectionCharacteristics);
        var md = pe.GetMetadataReader();
        Console.WriteLine("metadata : ver=" + md.MetadataVersion + " kind=" + md.MetadataKind + " strings=" + md.GetHeapSize(HeapIndex.String) + " blob=" + md.GetHeapSize(HeapIndex.Blob) + " guid=" + md.GetHeapSize(HeapIndex.Guid) + " us=" + md.GetHeapSize(HeapIndex.UserString));
        var tables = new (TableIndex, string)[] {
            (TableIndex.Module,"Module"),(TableIndex.TypeRef,"TypeRef"),(TableIndex.TypeDef,"TypeDef"),(TableIndex.Field,"Field"),
            (TableIndex.MethodDef,"MethodDef"),(TableIndex.Param,"Param"),(TableIndex.InterfaceImpl,"InterfaceImpl"),(TableIndex.MemberRef,"MemberRef"),
            (TableIndex.Constant,"Constant"),(TableIndex.CustomAttribute,"CustomAttribute"),(TableIndex.StandAloneSig,"StandAloneSig"),
            (TableIndex.EventMap,"EventMap"),(TableIndex.Event,"Event"),(TableIndex.PropertyMap,"PropertyMap"),(TableIndex.Property,"Property"),
            (TableIndex.MethodSemantics,"MethodSemantics"),(TableIndex.MethodImpl,"MethodImpl"),(TableIndex.TypeSpec,"TypeSpec"),
            (TableIndex.Assembly,"Assembly"),(TableIndex.AssemblyRef,"AssemblyRef"),(TableIndex.NestedClass,"NestedClass"),
            (TableIndex.GenericParam,"GenericParam"),(TableIndex.MethodSpec,"MethodSpec"),(TableIndex.GenericParamConstraint,"GenericParamConstraint"),
            (TableIndex.ClassLayout,"ClassLayout"),(TableIndex.FieldLayout,"FieldLayout"),(TableIndex.FieldRva,"FieldRva"),
        };
        foreach (var tv in tables) { int c = md.GetTableRowCount(tv.Item1); if (c > 0) Console.WriteLine("table    : " + tv.Item2.PadRight(22) + " " + c); }

        long types = 0, methods = 0, fields = 0, props = 0, evts = 0, cas = 0, sigs = 0;
        var errors = new List<string>();
        var prov = new SigProvider();
        foreach (var hnd in md.TypeDefinitions)
        {
            types++;
            var td = md.GetTypeDefinition(hnd);
            try { md.GetString(td.Name); md.GetString(td.Namespace); var _a = td.Attributes; var _b = td.BaseType; }
            catch (Exception e) { errors.Add("TypeDef 0x" + MetadataTokens.GetToken(hnd).ToString("X8") + ": " + e.Message); }
            foreach (var ii in td.GetInterfaceImplementations())
            { try { var _x = md.GetInterfaceImplementation(ii).Interface; } catch (Exception e) { errors.Add("InterfaceImpl: " + e.Message); } }
            foreach (var fh in td.GetFields())
            {
                fields++; var fd = md.GetFieldDefinition(fh);
                try { md.GetString(fd.Name); fd.DecodeSignature(prov, null); sigs++; } catch (Exception e) { errors.Add("Field 0x" + MetadataTokens.GetToken(fh).ToString("X8") + ": " + e.Message); }
                foreach (var c in fd.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
            }
            foreach (var mh in td.GetMethods())
            {
                methods++; var mdf = md.GetMethodDefinition(mh);
                try { md.GetString(mdf.Name); mdf.DecodeSignature(prov, null); sigs++; } catch (Exception e) { errors.Add("Method 0x" + MetadataTokens.GetToken(mh).ToString("X8") + ": " + e.Message); }
                foreach (var ph in mdf.GetParameters())
                {
                    var p = md.GetParameter(ph);
                    try { md.GetString(p.Name); } catch (Exception e) { errors.Add("Param: " + e.Message); }
                    foreach (var c in p.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
                }
                foreach (var c in mdf.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
                if (mdf.RelativeVirtualAddress != 0)
                    try { var body = pe.GetMethodBody(mdf.RelativeVirtualAddress); body.GetILBytes(); }
                    catch (Exception e) { errors.Add("Body 0x" + MetadataTokens.GetToken(mh).ToString("X8") + ": " + e.Message); }
            }
            foreach (var ph in td.GetProperties())
            {
                props++; var p = md.GetPropertyDefinition(ph);
                try { md.GetString(p.Name); p.DecodeSignature(prov, null); sigs++; } catch (Exception e) { errors.Add("Property: " + e.Message); }
                foreach (var c in p.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
            }
            foreach (var eh in td.GetEvents())
            {
                evts++; var ev = md.GetEventDefinition(eh);
                try { md.GetString(ev.Name); var _t = ev.Type; } catch (Exception e) { errors.Add("Event: " + e.Message); }
                foreach (var c in ev.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
            }
            foreach (var c in td.GetCustomAttributes()) { cas++; CheckCa(md, c, errors); }
        }
        Console.WriteLine("walk     : types=" + types + " methods=" + methods + " fields=" + fields + " props=" + props + " events=" + evts + " customattrs=" + cas + " sigsDecoded=" + sigs);

        // REACHABILITY: every row of an owned table must be reachable from exactly one owner.
        // A table that ECMA-335 requires to be sorted (CustomAttribute, MethodSemantics, NestedClass,
        // ClassLayout, ...) is looked up by binary search; if the emitter writes it unsorted the file
        // still "opens" but rows silently disappear. This is the check that catches that.
        void Reach(TableIndex t, string name, long walked)
        {
            int declared = md.GetTableRowCount(t);
            if (declared != walked)
            {
                errors.Add("UNREACHABLE ROWS: " + name + " table declares " + declared + " rows but only " +
                           walked + " are reachable from their owners (unsorted/mis-ranged table?)");
            }
        }
        // CustomAttribute is checked exactly: every row in the table must be returned by
        // GetCustomAttributes() on its OWN Parent. Parents include Assembly/Module/GenericParam/...,
        // not just the type members walked above, so this is done independently of `cas`.
        {
            var all = new HashSet<CustomAttributeHandle>();
            var parents = new HashSet<EntityHandle>();
            foreach (var ch in md.CustomAttributes) { all.Add(ch); parents.Add(md.GetCustomAttribute(ch).Parent); }
            int declared = all.Count;
            foreach (var par in parents)
                foreach (var ch in md.GetCustomAttributes(par)) all.Remove(ch);
            if (all.Count != 0)
                errors.Add("UNREACHABLE ROWS: CustomAttribute table has " + declared + " rows but " + all.Count +
                           " are NOT returned by GetCustomAttributes() on their own Parent " +
                           "(table not sorted by Parent, or mis-ranged) - first bad token 0x" +
                           MetadataTokens.GetToken(all.First()).ToString("X8"));
        }
        Reach(TableIndex.Field, "Field", fields);
        Reach(TableIndex.MethodDef, "MethodDef", methods);
        Reach(TableIndex.Property, "Property", props);
        Reach(TableIndex.Event, "Event", evts);
        Reach(TableIndex.TypeDef, "TypeDef", types);

        if (errors.Count == 0) Console.WriteLine("STRICT OK: every row, signature, attribute blob and method body decoded cleanly; all owned rows reachable");
        else { Console.WriteLine("STRICT FAIL: " + errors.Count + " errors"); foreach (var e in errors.Take(25)) Console.WriteLine("  " + e); }
        return errors.Count == 0 ? 0 : 1;
    }

    // What blob encodings does a real dump actually need? Scope the Kotlin blob writer.
    static int CaStats(string path)
    {
        using var fs = File.OpenRead(path);
        using var pe = new PEReader(fs);
        var md = pe.GetMetadataReader();
        var byAttr = new Dictionary<string, long>();
        var argKinds = new Dictionary<string, long>();
        var parentKinds = new Dictionary<string, long>();
        var prov = new CaProvider();
        long total = 0, fixedArgs = 0, namedArgs = 0, arrays = 0, nulls = 0, sysType = 0, boxed = 0;
        foreach (var h in md.CustomAttributes)
        {
            total++;
            var ca = md.GetCustomAttribute(h);
            parentKinds[ca.Parent.Kind.ToString()] = parentKinds.GetValueOrDefault(ca.Parent.Kind.ToString()) + 1;
            string owner;
            if (ca.Constructor.Kind == HandleKind.MemberReference)
            {
                var mr = md.GetMemberReference((MemberReferenceHandle)ca.Constructor);
                owner = mr.Parent.Kind == HandleKind.TypeReference
                    ? md.GetString(md.GetTypeReference((TypeReferenceHandle)mr.Parent).Name) : mr.Parent.Kind.ToString();
            }
            else
            {
                var mdf = md.GetMethodDefinition((MethodDefinitionHandle)ca.Constructor);
                owner = md.GetString(md.GetTypeDefinition(mdf.GetDeclaringType()).Name);
            }
            byAttr[owner] = byAttr.GetValueOrDefault(owner) + 1;
            var v = ca.DecodeValue(prov);
            foreach (var fa in v.FixedArguments) { fixedArgs++; Tally(argKinds, fa.Type, fa.Value, ref arrays, ref nulls, ref sysType); }
            foreach (var na in v.NamedArguments)
            {
                namedArgs++;
                argKinds["named:" + na.Kind] = argKinds.GetValueOrDefault("named:" + na.Kind) + 1;
                Tally(argKinds, na.Type, na.Value, ref arrays, ref nulls, ref sysType);
                if (na.Type == "Object") boxed++;
            }
        }
        Console.WriteLine("customattr rows      : " + total);
        Console.WriteLine("fixed args           : " + fixedArgs + "   named args: " + namedArgs);
        Console.WriteLine("array-valued args    : " + arrays + "   null args: " + nulls + "   System.Type args: " + sysType + "   boxed-object named: " + boxed);
        Console.WriteLine("-- attribute types (top 25 by row count)");
        foreach (var kv in byAttr.OrderByDescending(x => x.Value).Take(25)) Console.WriteLine($"   {kv.Value,10}  {kv.Key}");
        Console.WriteLine("   (" + byAttr.Count + " distinct attribute types total)");
        Console.WriteLine("-- argument element types");
        foreach (var kv in argKinds.OrderByDescending(x => x.Value)) Console.WriteLine($"   {kv.Value,10}  {kv.Key}");
        Console.WriteLine("-- attribute parent table kinds");
        foreach (var kv in parentKinds.OrderByDescending(x => x.Value)) Console.WriteLine($"   {kv.Value,10}  {kv.Key}");
        return 0;
    }

    static void Tally(Dictionary<string, long> d, string type, object val, ref long arrays, ref long nulls, ref long sysType)
    {
        d[type] = d.GetValueOrDefault(type) + 1;
        if (type != null && type.EndsWith("[]")) arrays++;
        if (val == null) nulls++;
        if (type == "System.Type") sysType++;
    }

    static void CheckCa(MetadataReader md, CustomAttributeHandle h, List<string> errors)
    {
        try { var ca = md.GetCustomAttribute(h); var v = ca.DecodeValue(new CaProvider()); var _f = v.FixedArguments.Length; var _n = v.NamedArguments.Length; }
        catch (Exception e) { errors.Add("CustomAttribute 0x" + MetadataTokens.GetToken(h).ToString("X8") + ": " + e.GetType().Name + ": " + e.Message); }
    }

    class SigProvider : ISignatureTypeProvider<string, object>
    {
        public string GetArrayType(string t, ArrayShape s) => t + "[" + new string(',', s.Rank - 1) + "]";
        public string GetByReferenceType(string t) => t + "&";
        public string GetFunctionPointerType(MethodSignature<string> si) => "fnptr";
        public string GetGenericInstantiation(string g, ImmutableArray<string> a) => g + "<" + string.Join(",", a) + ">";
        public string GetGenericMethodParameter(object gc, int i) => "!!" + i;
        public string GetGenericTypeParameter(object gc, int i) => "!" + i;
        public string GetModifiedType(string mod, string un, bool req) => un;
        public string GetPinnedType(string t) => t;
        public string GetPointerType(string t) => t + "*";
        public string GetPrimitiveType(PrimitiveTypeCode c) => c.ToString();
        public string GetSZArrayType(string t) => t + "[]";
        public string GetTypeFromDefinition(MetadataReader r, TypeDefinitionHandle h, byte raw)
        { var t = r.GetTypeDefinition(h); return r.GetString(t.Namespace) + "." + r.GetString(t.Name); }
        public string GetTypeFromReference(MetadataReader r, TypeReferenceHandle h, byte raw)
        { var t = r.GetTypeReference(h); return r.GetString(t.Namespace) + "." + r.GetString(t.Name); }
        public string GetTypeFromSpecification(MetadataReader r, object gc, TypeSpecificationHandle h, byte raw)
            => r.GetTypeSpecification(h).DecodeSignature(this, gc);
    }

    class CaProvider : ICustomAttributeTypeProvider<string>
    {
        readonly SigProvider s = new SigProvider();
        public string GetPrimitiveType(PrimitiveTypeCode c) => s.GetPrimitiveType(c);
        public string GetSystemType() => "System.Type";
        public string GetSZArrayType(string t) => t + "[]";
        public string GetTypeFromDefinition(MetadataReader r, TypeDefinitionHandle h, byte raw) => s.GetTypeFromDefinition(r, h, raw);
        public string GetTypeFromReference(MetadataReader r, TypeReferenceHandle h, byte raw) => s.GetTypeFromReference(r, h, raw);
        public string GetTypeFromSerializedName(string name) => name;
        public PrimitiveTypeCode GetUnderlyingEnumType(string type) => PrimitiveTypeCode.Int32;
        public bool IsSystemType(string type) => type == "System.Type";
    }
}
