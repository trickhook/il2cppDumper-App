# Handoff: off-heap dumping and Call of Duty Mobile (branch `codm-scale`)

Written when the mobile app was parked in favour of shipping the desktop dumper
first. Nothing here is broken: `:core:test` and `:app:assembleRelease` are green
and both games dump on the phone. This note is about what was verified, what was
decided, and what not to try again.

Commits on this branch (not pushed):

- `f8fdec8` Dump a 247 MB library off the Dalvik heap, and find it by symbol
- `46fc142` Stage only the protected section, and report every section that stays encrypted

## What works, with numbers

**Free Fire is byte-identical.** `dump.cs`, `script.json`, `stringliteral.json`
and `il2cpp.h` all match the v3.0 on-device dump of 2026-09-25, sha256 for sha256,
on three separate paths: the host `ByteArray` path, the host mapped path, and the
phone. `DummyDll` also matches: 55 assemblies plus `Il2CppDummyDll.dll`, with
`Assembly-CSharp.dll` skipped for memory exactly as v3.0 skipped it.

```
dump.cs            79 172 286  0b0e463077abdd71e3e02ac57ef0bc7747527745077f6420f30effbb7562fe3c
script.json       170 169 483  45484041f98d3941db1b4eb2f4f5694118a78a5fca4d5ef09281713aec4e986b
stringliteral.json  4 333 865  b4d0f3e07a0785f456e048439765b8b541f9d832db5a642b8b23834de97af7a0
il2cpp.h          131 693 161  00697589f8a5f5758b58e5089c30930b3697e75f51a5f42b1216abbee5104763
```

**Call of Duty Mobile dumps on the phone**, 1.0.57, arm64, no root, from the APK
and the extracted library only. 60 s end to end on a Galaxy M52:

```
libunity.so 247 830 320 bytes, chosen because its dynsym mentions il2cpp
staged for unpacking            13 674 116 of 247 830 320 bytes
DT_ANDROID_RELA (APS2)          2 183 367 relocations applied, 189 unsupported types
metadata v23 compact dialect    52 237 types, 477 894 methods, 21 images
CodeRegistration   0xdf0bad8    (file offset 0xdf03ad8)
MetadataRegistration 0xdf0bb80  (file offset 0xdf03b80)
dump.cs             78 546 151  script.json 209 306 191
stringliteral.json   5 748 296  il2cpp.h    135 006 551
DummyDll            20 of 21 assemblies; Assembly-CSharp.dll out of memory
```

All eight `Il2CppMetadataRegistration` pointers match the values read from a live
process (`scratchpad/codm/BINARY.md`), and every count matches: types 269 158,
methodPointers 470 836, invokers 22 035, fieldOffsets 52 237, methodSpecs 119 937,
metadataUsages 218 847. `System.Object` has its 10 methods, token `0x2000002`.
`Attribute : _Attribute`. `Int32.MaxValue = 2147483647`. All 21 image names and
`typeStart` partitions are right.

**Memory.** Measured on the phone during the CODM run, sampled every ~1.5 s from
staging through `il2cpp.h` (see "do not do this" below about sampling further):

```
peak total PSS            667 MB
peak Dalvik heap alloc    489 MB   of the 512 MB largeHeap cap
peak "Private Other"      308 MB   <- the two mappings, entirely off the Dalvik heap
```

308 MB is exactly the library plus the metadata (247 + 61). Before this branch
those bytes were `ByteArray`s on the Dalvik heap, so the same run would have
needed roughly 489 + 308 MB against a 512 MB cap. That is an inference from
measured numbers, not a measurement: I never captured a v3.0 baseline peak on the
phone (see below). What *is* measured is that v3.0 does not list Call of Duty at
all — it reports "1 apps IL2CPP" where this branch reports 2.

**Tests.** 87 total, 72 pass, 15 fail, identical set before and after the change.
All 15 need `Desktop/dump/libil2cpp.so` (arm32) or `*_memdump.so`, which are gone
from disk: 13 in `ElfTest`, 2 in `ProtectorInPlaceTest`.

## What is half-done

Nothing is left in a non-building state, but these are rough:

- **`DummyDll` for a large `Assembly-CSharp` still runs out of heap.** Pre-existing;
  it fails per assembly and is reported, not fatal. CODM gets 20 of 21, Free Fire
  55 of 56. The object graph is what fills the heap now, not the file bytes, so
  this is the next real memory problem and the `ByteSource` work does not touch it.
- **CODM's `.rodata` is still ciphertext on this branch**, so 24–25% of the data
  side is refused rather than emitted. See the next section: that is now fixable.
- **No tests were added** for `ByteSource`, the APS2 decoder, `SourceProtector` or
  the compact dialect. The proof is the byte-identical Free Fire fixtures plus the
  CODM cross-checks, which is good coverage of behaviour and none of unit
  boundaries. A test that maps a fixture and asserts `ArrayByteSource` and
  `MappedByteSource` agree on every accessor would be cheap and worth having.
- `Il2CppUnresolved` is emitted into `il2cpp.h` as a type name without a matching
  `struct` declaration in the prologue, so that header will not compile as C when
  anything was unresolved. Harmless for Free Fire (never happens) and for reading.

## What the solved `.rodata` cipher invalidates

The coordinator broke the cipher after this work: CODM uses the **same packer as
Free Fire** with different parameters — obfuscation constant `0x98` instead of
Free Fire's `0x4F`, XOR key `0x52`, `FirstGroupIndex` 1 instead of 2, permutation
solvable by the existing CRC32 affine oracle in ~27 s, and **three** encrypted
sections rather than one. Reference decryptor: `scratchpad/codm/rodata_decrypt.py`.

Assumptions in this branch that this invalidates:

1. "CODM cannot be decrypted statically, so refuse-and-report is the expected
   outcome." Wrong — it is now the safety net it was always supposed to be. Keep
   it; the CRC32 check is what makes the refusal trustworthy, and a build whose
   CRC does not close must still be refused loudly.
2. "`.rodata` is the only protected section." Wrong, and worse, **the library
   contains exactly one protector descriptor magic** (`.rodata`, at file offset
   `0xe82b710`) — I scanned the whole 247 MB and there is precisely one. So the
   other two encrypted sections are *not* descriptor-discoverable and
   `SourceProtector` will never find them by scanning. Whoever ports the decryptor
   has to get those ranges from somewhere else.
3. "Marking the protector's windows unreliable covers everything." It covers
   `.rodata` only. It happens to be sufficient today: every registration array the
   dump reads lives in `.rodata`, `.data.rel.ro` or `.data`, and the measured
   corruption rates (24.54% of field-offset tables, 24.71% of type sizes, 25.06%
   of method specs) match `.rodata`'s 16 KB-per-64 KB window pattern exactly. Do
   not assume that holds for another build.

## Decisions and why

**`MapMode.PRIVATE` needs a file we may open for writing.** Java's `FileChannel.map`
throws `NonWritableChannelException` for `PRIVATE` unless the channel is open for
both read and write, even though POSIX `mmap` would allow `PROT_WRITE|MAP_PRIVATE`
on an `O_RDONLY` fd. The libraries under `nativeLibraryDir` are owned by `system`,
so they cannot be opened `rw`. Hence `ApkSource` stages a copy into the app's own
cache with `FileChannel.transferFrom` (kernel side, nothing on the heap) and maps
that. The staged name carries the original's size and mtime so a rerun reuses it.
`MappedByteSource.readOnly` exists for probing files we do not own, which is what
host discovery uses. `PRIVATE` rather than `READ_WRITE` so nothing is ever flushed
back and the game's files cannot be corrupted even by a bug.

**Host discovery does not look for `il2cpp_init` exactly**, because that does not
work on either game. Order: `libil2cpp.so` by name in `nativeLibraryDir`, then the
extracted `.so` whose `.dynsym` contains any symbol mentioning `il2cpp` (preferring
one that mentions `il2cpp_init`), then the APK entry by name. Candidates are probed
biggest-first, which only sets the order, never the verdict, and the metadata is
located first because it is the cheap gate — only a game that ships a
`global-metadata.dat` is worth mapping 50 native libraries for. The UI and the log
say which library was chosen and why.

**Transcode, do not fork the parser.** `core/.../codm/CompactMetadata.kt` reads
compact records and repacks them into the layout the version asks for, then hands
the buffer to the existing `read*Definition` functions, in 8192-record chunks so a
20 MB table is never held twice. It refuses to transcode unless its hand-written
standard field list sums to the existing `sizeOf*` for that version, because a
misaligned buffer would be read as confident nonsense. Detection probes the
standard stride first and only tries compact if that fails, then requires
`sum(method_count)` to divide `methodsSize` and to yield the declared 42-byte
stride. Free Fire keeps the standard path because it *fails the probe*, not because
it is special-cased. The field tables mirror
`ff-il2cppdumper/Il2CppDumper/CODM/CompactMetadata.cs` line for line; the only
difference is Kotlin field names (`methodCount`, `typeArgc`, `isMethod`, `hashAlg`,
`publicKeyToken`).

## `Int` overflow in offset arithmetic

Fixed:

- `Metadata.getStringLiteral` did `(header.stringLiteralDataOffset + literal.dataIndex).toInt()`
  — a file offset truncated to 32 bits. Now `Long` throughout.
- `SectionHelper.occurrencesOf` did `minOf(to, fileLength).toInt() - pattern.size`
  and indexed the whole image as one array. Now chunked with `Long` bases.

Found and deliberately left alone:

- `MetadataHeader` declares `fieldMarshaledSizesOffset`, `interfaceOffsetsOffset`,
  `referencedAssembliesOffset`, `unresolvedVirtualCallParameterTypesOffset`,
  `unresolvedVirtualCallParameterRangesOffset`, `windowsRuntimeTypeNamesOffset`,
  `windowsRuntimeStringsOffset` and `exportedTypeDefinitionsOffset` as `Int` while
  every sibling offset is `Long`. None of them is used to address the file today,
  so nothing is wrong yet; a metadata over 2 GB would wrap.
- `Metadata.readMethodDefs` (the Free Fire layout prober) does `realCount * stride`
  and `count * expectedSize` as `Int` multiplies: 20 MB and 26.7 MB today,
  overflowing at about 38 M methods. Left as is because touching that function
  risks the byte-identical guarantee, and the compact path does not go through it.
- `ArrayByteSource` and `MappedByteSource` truncate offsets with `.toInt()` by
  design. `MappedByteSource.map` refuses anything over `Int.MAX_VALUE` rather than
  wrapping, which is the only reason that is safe. A library over 2 GB needs a
  multi-mapping `ByteSource`; the interface is `Long`-addressed so that is a
  drop-in.

## What I would do next, in order

1. Port `scratchpad/codm/rodata_decrypt.py`'s parameters into the protector so
   CODM's three sections decrypt with CRC32 verification. Derive the parameters
   (obfuscation constant, `FirstGroupIndex`) rather than changing Free Fire's
   defaults — `FFProtector.kt` has to keep behaving identically. They are
   `private const val OBFUSCATION_CONSTANT` and `FIRST_GROUP_INDEX` in
   `core/.../protector/FFProtector.kt`; the natural shape is a small parameter
   object with Free Fire's values as the default, tried in turn until the CRC
   closes. Then the refusal path in `Il2CppBinary.integrity` should report a clean
   image and the dump becomes fully correct.
2. Give the two extra encrypted sections to `SourceProtector`. They have no
   descriptor, so they need to come from the decryptor's own knowledge; the
   `Section` list and `MAX_STAGED_BYTES` (64 MB) are the places to extend. A
   152 MB section cannot be staged on a 512 MB heap at all — that one needs the
   cipher to run directly against the mapped `ByteSource`, not through
   `FFProtector`'s `ByteArray` API.
3. Fix `DummyDll` for a large `Assembly-CSharp`. It is now the only thing that
   touches the heap ceiling. Emitting the assembly in a streaming pass instead of
   building the whole PE in memory is the obvious move.
4. Add the unit tests listed above, especially `ArrayByteSource` vs
   `MappedByteSource` equivalence and an APS2 fixture.
5. Declare `Il2CppUnresolved` as an opaque struct in the `il2cpp.h` prologue.

## Do not repeat these

- **Do not look for an exact `il2cpp_init` dynamic symbol.** Free Fire's
  `libil2cpp.so` has zero symbols mentioning il2cpp (2717 dynsym entries, none
  match). CODM's `libunity.so` has only `__start_il2cpp` and `__stop_il2cpp` in
  dynsym; `ELF_HOOK_il2cpp_init` and `ELF_HOOK_BRIDGE_il2cpp_init` are present in
  `.dynstr` but are **not referenced by any dynsym entry**, so a symbol-table walk
  never sees them. Substring `il2cpp` over dynsym is what actually works.
- **Do not stage the prefix up to the protector descriptor.** Both packers keep
  their stub, and therefore the descriptor with the key material, near the *end* of
  the library: 175.5 MB into Free Fire's 184 MB one, 232.2 MB into CODM's 248 MB
  one. Staging `[0, descriptor+0x200)` copies almost the whole file and throws away
  the entire point of mapping it. The fix in `SourceProtector` is to copy the
  descriptor's own 0x200 bytes to offset 0 of a buffer that only reaches the end of
  the section — offset 0 is the earliest position `FFProtector.findDescriptor` can
  return, and it is outside the range being decrypted. 232 MB became 13.7 MB.
- **Do not trust a derived value in a log line.** `SourceUnpackResult.stagedBytes`
  was `if (detected) protectedEnd else 0L`, so the log confidently said "13 674 116
  of 247 830 320 bytes" while the real allocation was 243 MB. It cost an hour and a
  wrong conclusion in a report. It now returns the real staged length.
- **Do not poll `dumpsys meminfo` while the Dalvik heap is near the cap.** It kills
  the app. The `OutOfMemoryError` lands on the binder thread servicing
  `ActivityThread.dumpMemInfo` → `printRow` → `String.format`, where nothing
  catches it, and an uncaught OOME off the main thread is fatal:
  `FATAL EXCEPTION: binder:10547_1`. `DummyDllWriter` catches OOME on its own
  thread and reports "sem memoria para: ...", which is why the same run survives
  unpolled. Stop sampling when the log reaches "Gerando DummyDll".
- **Do not require `Il2CppCodeRegistration.methodPointersCount` to equal the
  metadata's method count.** CODM registers 470 836 pointers for 477 894 metadata
  methods, so the stock `findCodeRegistrationOld` finds nothing. `SectionHelper`
  now retries with any smaller count whose table really is that many pointers all
  landing in executable sections, and says so in the log. Free Fire is v31 and
  never reaches that function, so this cannot affect it.
- **Kotlin's `shl` only uses the low 6 bits of the shift count.** A 10-byte
  sleb128 reaches shift 70, and `shl 70` silently becomes `shl 6`, folding high
  bits back into the low word. bionic accumulates in a `size_t` and lets them fall
  off. The decoder guards with `if (shift < 64)`.
- **Do not expect blanking one unresolvable value to be enough.** Substituting the
  blank `Il2CppType` for one read out of ciphertext moved the crash five times:
  `Il2CppExecutor.getMethodSpecName`, `getTypeDefinitionFromIl2CppType`,
  `ScriptWriter.parseType`, `signatureCharOf`, `il2CppStructName`,
  `writeFieldUsage`, `HeaderWriter.buildVTable`. Each needed its own guard. Every
  guard is written so a well-formed image never takes the new branch, which is what
  keeps Free Fire byte-identical — verify that property rather than assuming it.
- **Do not use `git worktree remove` under the scratchpad path on Windows.** The
  paths are long enough that the delete fails after the registration is already
  gone; `git worktree prune` cleans up the metadata and the directory has to go by
  hand.

## Things I never got to

- A measured v3.0 baseline peak on the phone. I built v3.0 in a worktree and
  installed it, and it confirmed the qualitative before/after (it lists 1 IL2CPP
  app, not 2), but every attempt to drive its dump through `adb shell input` ended
  with the activity backgrounded and the process reaped by
  `ActivityManager: Killing ... (adj 905): remove task`. The before/after memory
  comparison in this note is therefore arithmetic over measured after-numbers.
- Mono.Cecil validation of the CODM `DummyDll` output. `tools/dllvalidate` exists
  for this and was never run against it.
- Comparing this branch's CODM `dump.cs` against the C# fork's byte for byte. The
  two should agree and the field tables were mirrored deliberately, but nobody has
  diffed them. Hashes of this branch's CODM output, for whoever does:
  `dump.cs de710ebd5b83de09`, `script.json 27e87f99e9921db9`,
  `stringliteral.json db6727c80e328399`, `il2cpp.h 196473deec4a096e`.

---

## CORRECTION (added after this note was written) — the descriptor DOES carry all three sections

This note claims above that CODM's `libunity.so` "contains exactly one protector
descriptor magic" and that the other two encrypted sections are "not
descriptor-discoverable" and must come from the decryptor's own knowledge.

**That is wrong, and acting on it would hardcode section ranges that do not need to be
hardcoded.** The observation was correct — there really is only one magic — but the
conclusion does not follow. Only **entry 0** of the descriptor table carries the magic in
its first dword; entries 1 and 2 hold unrelated values there. The table has an explicit
entry-count field:

```
table + 0x18C   u32   number of entries        (3 in this build)
table + 0x1A8         NUL-terminated base64 key material
entries are 0x30 bytes apart
```

So the right way to enumerate is **read the count, then stride 0x30** — never scan for
more magics.

Doing that yields all three encrypted sections, and each one's own CRC32 from the
descriptor proves the result. Verified by running the reference decryptor
(`scratchpad/codm/rodata_decrypt.py`) against the untouched packed library:

```
.rodata  off=0x00373380 size=0x00997304  CRC 0xD49063CE = descriptor  MATCH  (10056452/10056452 bytes)
.text    off=0x02F2B230 size=0x012C55B4  CRC 0x1F8A5F36 = descriptor  MATCH
il2cpp   off=0x041F07E4 size=0x09122F94  CRC 0x80F6C280 = descriptor  MATCH
```

Practical consequences for the work that resumes here:

- Nothing about the encrypted ranges needs to be hardcoded. Derive all of them from the
  descriptor, and verify each with the CRC32 the descriptor already carries. A build whose
  CRC does not close must be refused, not dumped.
- The staging insight in this note still stands and is still the right fix: the descriptor
  lives near the *end* of the library, so relocating its 0x200 bytes to offset 0 of a
  section-sized buffer is what keeps the staged prefix at 13.7 MB instead of 243 MB.
- The 152 MB `il2cpp` section genuinely cannot be staged through `FFProtector`'s
  `ByteArray` API on a 512 MB heap — that part of the note is correct. It has to be
  decrypted in place through the `MapMode.PRIVATE` mapping, window by window. The window
  geometry is deterministic and stateless (no byte depends on any other), so windows can
  be decrypted in any order and independently, which makes this straightforward.
- CODM's parameters differ from Free Fire's: obfuscation constant **0x98** (FF uses 0x4F),
  XOR key **0x52**, `FirstGroupIndex` **1** (FF uses 2), and a window permutation that
  matches none of `KnownPermutations` — but it does not need to be listed, because the
  existing CRC32 affine oracle solves it in about 27 seconds.
