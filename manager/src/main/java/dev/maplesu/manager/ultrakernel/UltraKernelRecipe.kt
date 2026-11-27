package dev.maplesu.manager.ultrakernel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * The Ultra patch *recipe*: site locators, byte templates and relocations, release independent.
 *
 * A recipe carries no file offset and no absolute address. It has:
 *
 *  - one **site locator** per record - a symbol name plus a byte delta inside that symbol - so the
 *    site re-resolves on any kernel whose `kallsyms` decodes;
 *  - the replacement **bytes**, either a template blob (code) or a field list assembled from rules
 *    (envelopes, historical credential slots, the one-instruction stubs);
 *  - one **relocation** per address-bearing field, so every address is recomputed for the target;
 *  - the historical credential fields retained for reading older recipes; current generation omits them.
 *
 * Record order is part of the format: the applier writes records in file order, so the two records
 * that overlap k22's 108 byte cave only work in the order the recipe declares.
 *
 * This is a port of `tools/specgen/specgen/recipe.py`; the JSON it parses is byte-identical to
 * `tools/specgen/specgen/recipes/k22.json` (see `UltraKernelRecipeK22`).
 */
internal class UltraKernelRecipe(
    val id: String,
    val referenceRelease: String,
    val credentialLength: Int,
    val initPathTemplate: String,
    val records: List<Record>,
    private val legacyMetadataDisplacement: Int? = null,
) {

    /** One record: where it goes, how long it is, and how its bytes are produced. */
    class Record(
        val role: String,
        val symbol: String,
        val delta: Int,
        val length: Int,
        val template: ByteArray?,
        val relocs: List<Reloc>,
        /** Digest of this site's pristine bytes on the *reference* release; diagnostics only. */
        val referenceOrigSha256: String?,
        /**
         * Name of the code body that assembles this record's replacement, when it has one.
         *
         * A `code` record carries no `template`: its bytes are emitted for the target kernel by
         * [UltraKernelBodies], so nothing in it can be a leftover of the reference build. Its
         * [relocs] are empty by construction - the body computes its own displacements.
         */
        val code: String? = null,
        /**
         * Whether the record may be *absent* from a kernel and simply left out.
         *
         * One record is: `report_cfi_failure+0x0`, the CFI check stub. 5.10 does not export that
         * symbol at all, and a kernel without CFI has nothing to neutralise - so its absence is a
         * property of the kernel, not a failed generation. Required records keep failing closed;
         * this flag is never a way to skip a record that exists but looks wrong.
         */
        val optional: Boolean = false,
        /** Symbol capacity is mandatory unless an explicitly declared slot matches pristine bytes. */
        val space: String = "symbol",
        val slotExpectedSha256: String? = null,
        /** Additional independently reviewed pristine digests for the same fixed slot. */
        val slotAcceptedSha256: Set<String> = emptySet(),
        /** Records sharing this cave move together; hook sites have no movable cave. */
        val cave: String? = null,
    ) {
        /** True when every byte comes from a field rule (no template blob, no code body). */
        val constructed: Boolean get() = template == null && code == null

        /** True when the replacement is assembled for the target kernel rather than carried. */
        val generated: Boolean get() = code != null
    }

    /**
     * One address-bearing field inside a record's replacement bytes.
     *
     * The option fields are flat and nullable rather than a map so that a malformed recipe fails
     * while it is parsed instead of while a kernel is being patched.
     */
    class Reloc(
        val at: Int,
        val kind: String,
        val op: String? = null,
        val opKind: String? = null,
        val adrAt: Int? = null,
        val reg: Int? = null,
        val words: Int? = null,
        val targetSymbol: String? = null,
        val targetDelta: Int = 0,
        val selfDelta: Int = 0,
        val name: String? = null,
        val length: Int? = null,
        val imm: Long? = null,
        val rd: Int? = null,
        val shift: Int? = null,
        val sf: Int? = null,
        val constant: Long? = null,
    )

    /** One record resolved against a concrete kernel. */
    class Rendered(
        val record: Record,
        val site: Int,
        val original: ByteArray,
        val replacement: ByteArray,
        /** Offsets inside [replacement] that are data, not instructions (generated records only). */
        val dataOffsets: Set<Int> = emptySet(),
        val cave: String? = null,
        val caveOffset: Int? = null,
        val shiftedFrom: Int? = null,
    ) {
        val role: String get() = record.role
    }

    /** The credential contract, read from the recipe rather than assumed by the caller. */
    fun credentialRecordIndex(): Int =
        records.indexOfFirst { record -> record.relocs.any { it.kind == KIND_CREDENTIAL } }
            .also { check(it >= 0) { "RECIPE_CREDENTIAL_SLOT_MISSING" } }

    fun initPathRecordIndices(): List<Int> =
        records.indices.filter { index -> records[index].relocs.any { it.kind == KIND_INIT_PATH } }

    // -----------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------

    /**
     * Resolve every site against [symbols] and emit `(orig, replacement)` for [kernel].
     *
     * @throws UltraKernelGenerateException `ULTRA_KERNEL_GENERATE_SITE_SYMBOL_MISSING`,
     *         `ULTRA_KERNEL_GENERATE_TARGET_SYMBOL_MISSING`, `ULTRA_KERNEL_GENERATE_SITE`,
     *         `ULTRA_KERNEL_GENERATE_STRUCT_OFFSET` or `ULTRA_KERNEL_GENERATE_CREDENTIAL_INVALID`.
     */
    fun render(
        symbols: UltraKernelSymbols,
        kernel: ByteArray,
        credential: UltraKernelCredential? = null,
        structOverrides: Map<String, Int> = emptyMap(),
    ): List<Rendered> = renderDetailed(symbols, kernel, credential, structOverrides).records

    /** Final effective recipe, rendered records and optional sites absent from this kernel. */
    class RenderResult(
        val effectiveRecipe: UltraKernelRecipe,
        val records: List<Rendered>,
        val skipped: List<String>,
        val placements: Map<String, UltraKernelCaveAllocator.Placement> = emptyMap(),
        val layout: UltraKernelLayout = UltraKernelLayout(emptyMap()),
    )

    /**
     * [render], but an `optional` record whose anchor symbol is absent is *left out* instead of
     * failing the whole plan. Every other failure still fails closed.
     */
    fun renderDetailed(
        symbols: UltraKernelSymbols,
        kernel: ByteArray,
        credential: UltraKernelCredential? = null,
        structOverrides: Map<String, Int> = emptyMap(),
        preservedMetadataDisplacement: Int? = null,
        deploymentNamespace: UltraKernelDeploymentNamespace? = null,
    ): RenderResult {
        if (credential == null && records.any { item -> item.relocs.any { it.kind == KIND_CREDENTIAL } }) {
            // No Key reservation overwrites the kernel's native panic implementation.
            return UltraKernelRecipe(id, referenceRelease, credentialLength, initPathTemplate,
                records.filterNot { item -> item.relocs.any { it.kind == KIND_CREDENTIAL } },
                legacyMetadataDisplacement).renderDetailed(symbols, kernel, null, structOverrides,
                    preservedMetadataDisplacement, deploymentNamespace)
        }
        if (credential != null && credential.length != credentialLength) {
            throw UltraKernelGenerateException(
                UltraKernelGenerateCodes.CREDENTIAL_INVALID,
                "length=${credential.length}!=${credentialLength}",
            )
        }
        // __do_execve_file also accepts a NULL filename with an open file in X7.
        // Such calls must replay the native prologue without entering the SDK filename gate.
        if (records.any { it.code == "execve-frame" } &&
            records.none { it.code == "execve-null-guard" } &&
            symbols.offsetOf("__do_execve_file")?.let {
                it == symbols.offsetOf("do_execveat_common")
            } == true
        ) {
            return UltraKernelRecipe(id, referenceRelease, credentialLength, initPathTemplate,
                records + Record("execve-null-guard", "arm64_notify_die", 52,
                    16, null, emptyList(), null, code = "execve-null-guard", cave = "arm64_notify_die"),
                legacyMetadataDisplacement)
                .renderDetailed(symbols, kernel, credential, structOverrides, preservedMetadataDisplacement, deploymentNamespace)
        }
        if (records.any { it.code == "execve-gate" } &&
            records.none { it.code == "request-v2" } &&
            symbols.offsetOf("copy_from_user_nofault") != null &&
            symbols.offsetOf("copy_to_user_nofault") != null
        ) {
            return UltraKernelRecipe(id, referenceRelease, credentialLength, initPathTemplate,
                records + Record("request-v2", "die", UltraKernelRequestV2.CAVE_OFFSET,
                    UltraKernelRequestV2.BYTES, null, emptyList(), null,
                    code = "request-v2", cave = "die"), legacyMetadataDisplacement)
                .renderDetailed(symbols, kernel, credential, structOverrides, preservedMetadataDisplacement, deploymentNamespace)
        }
        // The execmem API takes (type, size), unlike module_alloc(size). Keep the existing
        // call sites and their fixed offsets, and place one shared tail adapter after die's body.
        if (records.any { it.code == "execve-tail" } &&
            records.none { it.role == "module_alloc-adapter" } &&
            UltraKernelSymbolResolver(symbols).needsExecmemAdapter
        ) {
            return UltraKernelRecipe(id, referenceRelease, credentialLength, initPathTemplate,
                records + Record("module_alloc-adapter", "die", UltraKernelBodies.EXECVE_GATE_BYTES + 16,
                    12, null, emptyList(), null,
                    code = "module-alloc-adapter", cave = "die"), legacyMetadataDisplacement)
                .renderDetailed(symbols, kernel, credential, structOverrides, preservedMetadataDisplacement, deploymentNamespace)
        }
        // Retain the native prefix at its original address, including PAC's unwind address.
        // Feature symbol names are not a reliable proof that dynamic SCS is absent.
        var execvePrologue: Int? = null
        if (records.any { it.code == "execve-frame" }) {
            val entry = symbols.offsetOf("do_execveat_common")
                ?: throw UltraKernelGenerateException(UltraKernelGenerateCodes.TARGET_SYMBOL_MISSING,
                    "do_execveat_common")
            if (entry < 0 || entry.toLong() + 4 > kernel.size) throw UltraKernelGenerateException(
                UltraKernelGenerateCodes.SITE, "HOOK_ENTRY_UNSUPPORTED:do_execveat_common")
            val prefix = UltraKernelA64.le32Bits(kernel, entry)
            val retainsPac = prefix == UltraKernelA64.PACIA1716
            val stackBytes = (prefix ushr 10) and 0xFFF
            // A reviewed non-PAC shape allocates the aligned ordinary frame before pushing LR
            // to SCS. Leave SUB SP,SP,#imm in place and replay only that exact SCS instruction.
            val retainsStack = prefix and 0xFFC003FF.toInt() == 0xD10003FF.toInt() &&
                stackBytes > 0 && stackBytes % 16 == 0
            if (!retainsPac && !retainsStack) throw UltraKernelGenerateException(UltraKernelGenerateCodes.SITE,
                "HOOK_ENTRY_UNSUPPORTED:do_execveat_common")
            val end = symbols.nextOffset(entry)?.coerceAtMost(kernel.size) ?: kernel.size
            if (entry + 8 > end) throw UltraKernelGenerateException(UltraKernelGenerateCodes.SITE,
                "HOOK_FRAME_UNSUPPORTED:do_execveat_common")
            val word = UltraKernelA64.le32Bits(kernel, entry + 4)
            // Replay only reviewed PC-independent prologues. Some 5.15 builds place a static
            // STR LR,[X18],#8 after PAC instead of allocating their ordinary stack here.
            val savesFrame = word and 0xFFC07FFF.toInt() == 0xA9807BFD.toInt() &&
                UltraKernelA64.signExtend(((word ushr 15) and 0x7F).toLong(), 7) < 0
            val allocatesStack = word and 0xFFC003FF.toInt() == 0xD10003FF.toInt() &&
                (word ushr 10) and 0xFFF != 0
            val pushesShadow = word == 0xF800865E.toInt()
            val supportedFrame = if (retainsPac) savesFrame || allocatesStack || pushesShadow else pushesShadow
            if (!supportedFrame) throw UltraKernelGenerateException(
                UltraKernelGenerateCodes.SITE, "HOOK_FRAME_UNSUPPORTED:do_execveat_common")
            execvePrologue = word
        }
        val path = (credential?.let(::initPath) ?: "/data/${requireNotNull(deploymentNamespace) { "ULTRA_KERNEL_DEPLOYMENT_REQUIRED" }.name()}/init").toByteArray(Charsets.US_ASCII)
        val layout = resolveLayout(symbols, kernel, structOverrides)
        val structs = layout.fields.mapValues { it.value.offsetBytes }.toMutableMap()
        layout.offset(UltraKernelStructOffsets.SECCOMP_MODE)?.let {
            structs[UltraKernelStructOffsets.SECCOMP] = it
        }
        val fields = UltraKernelFields(
            cred = layout.offset(UltraKernelStructOffsets.CRED),
            comm = layout.offset(UltraKernelStructOffsets.COMM),
            seccomp = layout.offset(UltraKernelStructOffsets.SECCOMP_MODE),
            credSuid = layout.offset(UltraKernelStructOffsets.SUID),
            credEuid = layout.offset(UltraKernelStructOffsets.CRED_EUID),
            execvePrologue = execvePrologue,
            requestV2 = records.any { it.code == "request-v2" },
            pageShift = if (records.none { it.code == "printfn-bracket" }) 12 else when (
                (UltraKernelA64.le32Bits(kernel, 24) ushr 1) and 3
            ) {
                1 -> 12
                2 -> 14
                3 -> 16
                else -> throw UltraKernelGenerateException(UltraKernelGenerateCodes.SITE, "IMAGE_PAGE_SIZE")
            },
        )
        val excluded = LinkedHashSet<UltraKernelCaveAllocator.Span>()
        repeat(MAX_PLACEMENT_ATTEMPTS) {
            val allocation = try {
                UltraKernelCaveAllocator.allocate(this, symbols, kernel, excluded,
                    preservedMetadataDisplacement)
            } catch (error: UltraKernelGenerateException) {
                val executor = records.singleOrNull { it.code == "printfn-bracket" }
                if (preservedMetadataDisplacement == null || legacyMetadataDisplacement != null ||
                    executor?.cave == null || error.code != UltraKernelGenerateCodes.CAVE_UNAVAILABLE ||
                    error.detail !in setOf("EXECUTOR_IDENTITY_PADDING_UNAVAILABLE",
                        "METADATA_IDENTITY_PADDING_UNAVAILABLE")
                ) throw error
                // A historic key must never force either endpoint into live kernel instructions.
                // Keep its identity and relocate only complete SDK locator payloads before execution.
                return UltraKernelRecipe(id, referenceRelease, credentialLength, initPathTemplate,
                    records + Record("metadata-rebase", executor.symbol,
                        executor.delta + UltraKernelBodies.EXECUTOR_BYTES,
                        UltraKernelBodies.METADATA_REBASE_BYTES, null, emptyList(), null,
                        code = "metadata-rebase", cave = executor.cave),
                    preservedMetadataDisplacement)
                    .renderDetailed(symbols, kernel, credential, structOverrides,
                        deploymentNamespace = deploymentNamespace)
            }
            try {
                return renderPlaced(allocation, kernel, credential, path, structs, fields, layout)
            } catch (error: UltraKernelGenerateException) {
                val shifted = allocation.placements.values.filter { it.shifted }
                val placementFailure = shifted.isNotEmpty() &&
                    (error.code == UltraKernelGenerateCodes.BODY ||
                        error.code == UltraKernelGenerateCodes.SITE) &&
                    (error.detail.contains("BRANCH_OUT_OF_RANGE") ||
                        error.detail.startsWith("BRANCH:"))
                if (!placementFailure) throw error
                shifted.forEach { excluded += it.span }
            }
        }
        throw UltraKernelGenerateException(
            UltraKernelGenerateCodes.CAVE_UNAVAILABLE,
            "branch-compatible-padding-not-found",
        )
    }

    private fun renderPlaced(
        allocation: UltraKernelCaveAllocator.Allocation,
        kernel: ByteArray,
        credential: UltraKernelCredential? = null,
        path: ByteArray,
        structs: Map<String, Int>,
        fields: UltraKernelFields,
        layout: UltraKernelLayout,
    ): RenderResult {
        val symbols = allocation.symbols
        val resolver = UltraKernelSymbolResolver(symbols)
        val bodyFields = if (legacyMetadataDisplacement == null) fields else {
            val metadata = allocation.siteByRole.getValue("free_initmem+0x0").toLong() + 4
            val call = allocation.siteByRole.getValue("__drm_printfn_coredump+0x0").toLong() +
                UltraKernelBodies.EXECUTOR_CALL_OFFSET
            fields.copy(legacyMetadataDisplacement = legacyMetadataDisplacement,
                actualMetadataDisplacement = Math.toIntExact(metadata - call))
        }
        val rendered = ArrayList<Rendered>(records.size)
        val skipped = ArrayList<String>()
        for (record in records) {
            val site = allocation.siteByRole[record.role] ?: if (record.optional) {
                skipped += record.role
                continue
            } else throw UltraKernelGenerateException(
                UltraKernelGenerateCodes.SITE_SYMBOL_MISSING,
                "${record.role}:${record.symbol}",
            )
            var dataOffsets = emptySet<Int>()
            val blob = when {
                record.code != null -> {
                    val body = UltraKernelBodies.generate(record.code, site, resolver, bodyFields)
                    dataOffsets = body.dataOffsets
                    body.bytes
                }
                record.template != null -> record.template.copyOf()
                else -> ByteArray(record.length)
            }
            if (record.template != null && blob.size != record.length) {
                throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.RECIPE,
                    "TEMPLATE_LENGTH:${record.role}:${blob.size}!=${record.length}",
                )
            }
            // A generated body is validated word by word: every word it emits outside a declared
            // data range must classify as one of the encodings the emitter can produce. This is the
            // independent decode check that a frozen template never needed.
            if (record.code != null) {
                val invalid = UltraKernelA64Verify.invalidWords(blob, dataOffsets)
                if (invalid.isNotEmpty()) {
                    throw UltraKernelGenerateException(
                        UltraKernelGenerateCodes.BODY,
                        "BODY_INVALID_INSTRUCTION:${record.role}:+0x" +
                            Integer.toHexString(invalid.first()),
                    )
                }
            }
            for (reloc in record.relocs) {
                applyReloc(record, reloc, blob, site, symbols, path, credential, structs,
                    allocation.siteByRole)
            }
            if (blob.size != record.length) {
                throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.SITE,
                    "RENDER_LENGTH:${record.role}",
                )
            }
            if (site < 0 || site + record.length > kernel.size) {
                throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.SITE,
                    "SITE_TRUNCATED:${record.role}",
                )
            }
            val placement = record.cave?.let { allocation.placements.getValue(it) }
            if (placement != null && !placement.span.contains(site, site + blob.size)) {
                throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.CAVE_UNAVAILABLE,
                    "WRITE_OUTSIDE_CAVE:${record.role}",
                )
            }
            rendered += Rendered(
                record = record,
                site = site,
                original = kernel.copyOfRange(site, site + record.length),
                replacement = blob,
                dataOffsets = dataOffsets,
                cave = record.cave,
                caveOffset = placement?.let { site - it.base },
                shiftedFrom = placement?.shiftedFrom,
            )
        }
        return RenderResult(this, rendered, skipped, allocation.placements, layout)
    }

    /** `/data/<key16>/init`, built from the recipe's template so the two can never drift. */
    fun initPath(credential: UltraKernelCredential): String =
        initPathTemplate.replace("{key16}", credential.key16)

    private fun applyReloc(
        record: Record,
        reloc: Reloc,
        blob: ByteArray,
        site: Int,
        symbols: UltraKernelSymbols,
        path: ByteArray,
        credential: UltraKernelCredential? = null,
        structs: Map<String, Int>,
        sites: Map<String, Int>,
    ) {
        val at = reloc.at
        when (reloc.kind) {
            KIND_WORD -> UltraKernelA64.writeLe32(blob, at, encodeWord(record, reloc, site, symbols))
            KIND_BRANCH -> {
                val afterPac = record.role == "do_execveat_common+0x0" &&
                    reloc.targetSymbol == "arm64_notify_die" && reloc.targetDelta == 24
                // The dynamic frame's save half starts at +28; +24 is its resume branch.
                val target = if (afterPac && sites["execve-null-guard"] != null) {
                    sites.getValue("execve-null-guard").toLong()
                } else target(reloc, symbols) + if (afterPac) 4 else 0
                val here = (site + at).toLong()
                val word = try {
                    if (reloc.op == OP_BL) UltraKernelA64.encodeBl(here, target) else UltraKernelA64.encodeB(here, target)
                } catch (error: IllegalArgumentException) {
                    throw UltraKernelGenerateException(
                        UltraKernelGenerateCodes.SITE,
                        "BRANCH:${record.role}:${error.message}",
                    )
                }
                UltraKernelA64.writeLe32(blob, at, word.toLong() and 0xFFFFFFFFL)
            }
            KIND_IMM64_DELTA -> {
                val adrAt = reloc.adrAt ?: throw recipeError("IMM64_ADR_MISSING:${record.role}")
                val reg = reloc.reg ?: 13
                val words = reloc.words ?: throw recipeError("IMM64_WORDS_MISSING:${record.role}")
                val target = target(reloc, symbols)
                val delta = target - (site + adrAt)
                val encoded = UltraKernelA64.encodeImm64(reg, delta)
                if (encoded.size != words) {
                    throw UltraKernelGenerateException(
                        UltraKernelGenerateCodes.SITE,
                        "IMM64_WORD_COUNT:${record.role}:${encoded.size}!=$words",
                    )
                }
                encoded.forEachIndexed { index, word ->
                    UltraKernelA64.writeLe32(blob, at + index * 4, word.toLong() and 0xFFFFFFFFL)
                }
            }
            KIND_U32_TARGET -> {
                // SDK consumers need the actual hook instruction, including a retained PAC/SCS
                // prologue, rather than the function start that precedes it.
                val value = if (reloc.targetSymbol == "do_execveat_common" && reloc.targetDelta == 0) {
                    sites["do_execveat_common+0x0"]?.toLong() ?: target(reloc, symbols)
                } else target(reloc, symbols)
                UltraKernelA64.writeLe32(blob, at, value)
            }
            KIND_U32_SELF -> UltraKernelA64.writeLe32(blob, at, (site + reloc.selfDelta).toLong() and 0xFFFFFFFFL)
            KIND_U32_CONST -> UltraKernelA64.writeLe32(
                blob, at, reloc.constant ?: throw recipeError("CONST_MISSING:${record.role}"),
            )
            KIND_AVC_INFO -> {
                // SDK reads two tag bytes, an unaligned u32 function offset, then a signed
                // i16 instruction count. Its return = function + count * 4 - 4.
                // Use the resolved RET hook, not k22's +0x28 or its packed address constant.
                val function = target(reloc, symbols)
                val tail = sites["avc_denied+0x28"] ?: throw recipeError("AVC_HOOK_MISSING")
                val distance = tail.toLong() - function
                val count = distance / 4 + 1
                if (distance < 0 || distance % 4 != 0L || count !in 1..Short.MAX_VALUE.toLong()) {
                    throw recipeError("AVC_RETURN_RANGE:${record.role}")
                }
                blob[at] = 1 // filename argument register
                blob[at + 1] = 0 // struct filename pointer
                UltraKernelA64.writeLe32(blob, at + 2, function)
                UltraKernelA64.writeLe16(blob, at + 6, count.toInt())
            }
            KIND_U16_STRUCT -> {
                val name = reloc.name ?: throw recipeError("STRUCT_NAME_MISSING:${record.role}")
                val value = structs[name] ?: throw UltraKernelGenerateException(
                    UltraKernelGenerateCodes.STRUCT_OFFSET,
                    "STRUCT_OFFSET_UNRESOLVED:$name",
                )
                UltraKernelA64.writeLe16(blob, at, value)
            }
            KIND_INIT_PATH -> {
                val length = reloc.length ?: UltraKernelCredential.INIT_PATH_FIELD
                if (path.size + 1 > length || at + length > blob.size) {
                    throw UltraKernelGenerateException(
                        UltraKernelGenerateCodes.SITE,
                        "INIT_PATH_TOO_LONG:${path.size}:${record.role}",
                    )
                }
                // NUL terminated, zero padded to a fixed width - the width never depends on the key,
                // so a re-bind can never change a record's length.
                blob.fill(0, at, at + length)
                path.copyInto(blob, at)
            }
            KIND_CREDENTIAL -> {
                val length = reloc.length ?: credentialLength
                // Historical slot keeps its width; standard carriers contain no secret.
                val secret = credential?.copySecret() ?: ByteArray(length)
                try {
                    if (secret.size != length || at + length > blob.size) {
                        throw UltraKernelGenerateException(
                            UltraKernelGenerateCodes.CREDENTIAL_INVALID,
                            "slot=$length secret=${secret.size}",
                        )
                    }
                    secret.copyInto(blob, at)
                } finally {
                    secret.fill(0)
                }
            }
            else -> throw recipeError("REPLOC_KIND_UNSUPPORTED:${reloc.kind}")
        }
    }

    private fun target(reloc: Reloc, symbols: UltraKernelSymbols): Long {
        val symbol = reloc.targetSymbol ?: throw recipeError("REPLOC_TARGET_MISSING")
        for (candidate in UltraKernelSymbolAliases.candidates(symbol)) {
            symbols.offsetOf(candidate)?.let { return (it + reloc.targetDelta).toLong() }
        }
        throw UltraKernelGenerateException(UltraKernelGenerateCodes.TARGET_SYMBOL_MISSING, symbol)
    }

    private fun encodeWord(
        record: Record,
        reloc: Reloc,
        site: Int,
        symbols: UltraKernelSymbols,
    ): Long {
        val word = when (val op = reloc.op) {
            OP_RET -> UltraKernelA64.RET
            OP_NOP -> UltraKernelA64.NOP
            OP_MOVZ -> UltraKernelA64.movz(
                reloc.rd ?: 0,
                (reloc.imm ?: throw recipeError("WORD_IMM_MISSING:${record.role}")).toInt(),
                reloc.shift ?: 0,
                reloc.sf ?: 1,
            )
            OP_MOV_WZR -> 0x2A1F03E0 or (reloc.rd ?: 0)
            OP_BRANCH -> {
                val target = target(reloc, symbols)
                val here = (site + reloc.at).toLong()
                if (reloc.opKind == OP_BL) UltraKernelA64.encodeBl(here, target) else UltraKernelA64.encodeB(here, target)
            }
            else -> throw recipeError("WORD_OP_UNKNOWN:$op")
        }
        return word.toLong() and 0xFFFFFFFFL
    }

    // -----------------------------------------------------------------------------------------
    // task_struct offsets
    // -----------------------------------------------------------------------------------------

    /**
     * Which `task_struct` fields this recipe needs, resolved against the target kernel.
     *
     * Only consumers of a field require it. The remaining legacy saved-ID reply slot is declared
     * separately from directly observed task fields, so unused fields never receive defaults.
     */
    private fun resolveLayout(
        symbols: UltraKernelSymbols,
        kernel: ByteArray,
        overrides: Map<String, Int>,
    ): UltraKernelLayout {
        val needed = LinkedHashSet<String>()
        val credentialMembers = LinkedHashSet<String>()
        if (records.any { it.code in setOf("execve-gate", "printfn-bracket", "metadata-rebase") })
            credentialMembers += setOf(UltraKernelStructOffsets.SUID, UltraKernelStructOffsets.SGID)
        if (records.any { it.code in setOf("execve-gate", "printk-flush") })
            credentialMembers += UltraKernelStructOffsets.CRED_EUID
        if (credentialMembers.isNotEmpty()) needed += UltraKernelStructOffsets.CRED
        if (records.any { it.code in setOf("printk-flush", "dev-printk") }) needed += UltraKernelStructOffsets.COMM
        for (record in records) {
            for (reloc in record.relocs) {
                if (reloc.kind == KIND_U16_STRUCT) reloc.name?.let { needed += it }
            }
        }
        return UltraKernelStructOffsets.describe(symbols, kernel, needed, overrides, credentialMembers)
    }

    // -----------------------------------------------------------------------------------------
    // Post-render checks
    // -----------------------------------------------------------------------------------------

    /**
     * Addresses left behind in a rendered blob that no relocation explains.
     *
     * Two shapes, both of which already cost one defect each:
     *
     *  - an `adr` whose computed target is outside its own record and which has no `imm64_delta`
     *    relocation - the displacement is frozen at the reference kernel's layout;
     *  - a 4 byte word that equals some symbol's file offset but is not covered by a `u32_target`.
     *
     * On the reference kernel a stale address is *correct*, which is exactly why the applier's own
     * checks can never catch it. This is the only check that can.
     */
    fun residue(symbols: UltraKernelSymbols, rendered: List<Rendered>): List<String> {
        val problems = ArrayList<String>()
        for (item in rendered) {
            val blob = item.replacement
            val covered = BooleanArray(blob.size)
            for (reloc in item.record.relocs) {
                val width = when (reloc.kind) {
                    KIND_AVC_INFO -> 8
                    KIND_U16_STRUCT -> 2
                    KIND_INIT_PATH, KIND_CREDENTIAL -> reloc.length ?: 0
                    KIND_IMM64_DELTA -> 4 * (reloc.words ?: 1)
                    else -> 4
                }
                for (index in reloc.at until minOf(reloc.at + width, covered.size)) {
                    covered[index] = true
                }
            }
            val relocatedAdr = HashSet<Int>()
            for (reloc in item.record.relocs) {
                if (reloc.kind == KIND_IMM64_DELTA) reloc.adrAt?.let { relocatedAdr += it }
            }
            var position = 0
            while (position + 4 <= blob.size) {
                val printable = (0 until 4).all { offset ->
                    val value = blob[position + offset].toInt() and 0xFF
                    value in 0x20..0x7E
                }
                if (printable) {
                    position += 4
                    continue
                }
                val word = UltraKernelA64.le32Bits(blob, position)
                if (UltraKernelA64.isAdr(word)) {
                    val target = UltraKernelA64.decodeAdr(word, (item.site + position).toLong())
                    val inside = target != null && target >= item.site.toLong() &&
                        target < item.site.toLong() + blob.size
                    if (target != null && !inside && !relocatedAdr.contains(position)) {
                        problems += "${item.role}: adr at +0x${Integer.toHexString(position)} frozen to 0x${java.lang.Long.toHexString(target)}"
                    }
                }
                if (!item.record.generated && !covered[position]) {
                    val value = word.toInt()
                    if (symbols.isSymbolStart(value)) {
                        problems += "${item.role}: unrelocated symbol offset 0x${Integer.toHexString(value)}" +
                            " at +0x${Integer.toHexString(position)}"
                    }
                }
                position += 4
            }
        }
        return problems
    }

    /**
     * How many records still carry the reference release's pristine bytes.
     *
     * A record's `original` is always read from the *target* kernel, so it can never fail the
     * applier's own check. On the reference build this is the "is the image exactly the one k22 was
     * built for" probe; on any other build it is expected to be low, and it is a *diagnostic* only
     * now that the code bodies are generated here. Returns `(matching, total, differingRoles)`.
     */
    fun siteMatch(rendered: List<Rendered>): SiteMatch {
        var matching = 0
        var total = 0
        val differing = ArrayList<String>()
        for (item in rendered) {
            val expected = item.record.referenceOrigSha256 ?: continue
            total++
            if (sha256Hex(item.original) == expected) matching++ else differing += item.role
        }
        return SiteMatch(matching, total, differing)
    }

    /**
     * How many *required* record replacements were produced by this generator for this kernel.
     *
     * This is the invariant that replaced `siteMatch` as the *gate*. A record whose replacement is
     * still a carried machine-code blob was compiled for one build and is the exact thing that must
     * never be written to another, so it is counted as missing here and refused.
     *
     * `optional` records are excluded from `total` in both directions: an optional record that this
     * kernel cannot host is left out by design (see [Record.optional]), so counting it as missing
     * would refuse 5.10 for not having CFI. It is reported separately through [RenderResult.skipped].
     */
    fun generatedCoverage(rendered: List<Rendered>): SiteMatch {
        val renderedRoles = rendered.map { it.role }.toSet()
        var matching = 0
        var total = 0
        val missing = ArrayList<String>()
        for (record in (records + rendered.map { it.record }).distinctBy { it.role }) {
            // An optional record this kernel cannot host is left out by design; it belongs to
            // `skipped`, not to the denominator.
            if (record.optional && record.role !in renderedRoles) continue
            total++
            if (record.role in renderedRoles && (record.generated || record.constructed)) {
                matching++
            } else {
                missing += record.role
            }
        }
        return SiteMatch(matching, total, missing)
    }

    class SiteMatch(val matching: Int, val total: Int, val differingRoles: List<String>)

    companion object {
        const val SCHEMA = 1
        private const val MAX_PLACEMENT_ATTEMPTS = 64

        const val KIND_BRANCH = "branch"
        const val KIND_IMM64_DELTA = "imm64_delta"
        const val KIND_U32_TARGET = "u32_target"
        const val KIND_U32_SELF = "u32_self"
        const val KIND_U32_CONST = "u32_const"
        const val KIND_AVC_INFO = "avc_info"
        const val KIND_U16_STRUCT = "u16_struct"
        const val KIND_INIT_PATH = "init_path"
        const val KIND_CREDENTIAL = "credential"
        const val KIND_WORD = "word"

        const val OP_B = "b"
        const val OP_BL = "bl"
        const val OP_RET = "ret"
        const val OP_NOP = "nop"
        const val OP_MOVZ = "movz"
        const val OP_MOV_WZR = "mov_wzr"
        const val OP_BRANCH = "branch"

        /** The frozen recipe that produced k22; see `tools/specgen/specgen/recipes/k22.json`. */
        fun k22(): UltraKernelRecipe = parse(K22_RECIPE_JSON, "k22")

        /**
         * @throws UltraKernelGenerateException `ULTRA_KERNEL_GENERATE_RECIPE` for any schema, shape or
         *         option problem. A recipe that half-parses is never rendered.
         */
        fun parse(text: String, label: String = "recipe"): UltraKernelRecipe {
            val payload = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (error: Exception) {
                throw recipeError("RECIPE_JSON:$label:${error.message}")
            }
            val schema = payload["schema"]?.jsonPrimitive?.int ?: SCHEMA
            if (schema != SCHEMA) throw recipeError("RECIPE_SCHEMA_UNSUPPORTED:$schema")
            val recordsJson = payload["records"]?.jsonArray
                ?: throw recipeError("RECIPE_RECORDS_MISSING:$label")
            val records = recordsJson.map { element ->
                parseRecord(element.jsonObject)
            }
            if (records.isEmpty()) throw recipeError("RECIPE_EMPTY:$label")
            val credential = payload["credential"]?.jsonObject
            return UltraKernelRecipe(
                id = payload["id"]?.jsonPrimitive?.content ?: label,
                referenceRelease = payload["referenceKernelRelease"]?.jsonPrimitive?.content.orEmpty(),
                credentialLength = credential?.int("length") ?: UltraKernelCredential.LENGTH,
                initPathTemplate = credential?.string("initPathTemplate") ?: "/data/{key16}/init",
                records = records,
            )
        }

        private fun parseRecord(payload: JsonObject): Record {
            val role = payload.string("role") ?: throw recipeError("RECIPE_ROLE_MISSING")
            val site = payload["site"]?.jsonObject ?: throw recipeError("RECIPE_SITE_MISSING:$role")
            val symbol = site.string("symbol") ?: throw recipeError("RECIPE_SITE_SYMBOL_MISSING:$role")
            val code = payload.string("code")
            val template = payload.string("template")?.let { decodeHex(it, role) }
            if (code != null && template != null) {
                throw recipeError("RECIPE_TEMPLATE_AND_CODE:$role")
            }
            if (code != null && !UltraKernelBodies.has(code)) {
                throw recipeError("RECIPE_CODE_UNKNOWN:$role:$code")
            }
            val relocs = payload["relocs"]?.jsonArray?.map { parseReloc(it.jsonObject, role) }.orEmpty()
            if (code != null && relocs.isNotEmpty()) {
                // A generated body computes its own displacements; declaring relocs next to it would
                // mean two writers for the same word.
                throw recipeError("RECIPE_CODE_WITH_RELOCS:$role")
            }
            val space = payload.string("space") ?: "symbol"
            if (space != "symbol" && space != "slot") throw recipeError("RECIPE_SPACE_INVALID:$role")
            val slotExpected = payload.string("slotExpectedSha256")
            if (space == "slot" && (slotExpected == null || !slotExpected.matches(Regex("[0-9a-f]{64}")))) {
                throw recipeError("RECIPE_SLOT_EXPECTATION_MISSING:$role")
            }
            val acceptedSlots = payload["slotAcceptedSha256"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.also { values ->
                    if (values.any { !it.matches(Regex("[0-9a-f]{64}")) }) {
                        throw recipeError("RECIPE_SLOT_EXPECTATION_INVALID:$role")
                    }
                }
                ?.toSet()
                .orEmpty()
            return Record(
                role = role,
                symbol = symbol,
                delta = site.int("delta") ?: 0,
                length = payload.int("length") ?: throw recipeError("RECIPE_LENGTH_MISSING:$role"),
                template = template,
                relocs = relocs,
                referenceOrigSha256 = payload.string("referenceOrigSha256"),
                code = code,
                optional = payload.boolean("optional") ?: false,
                space = space,
                slotExpectedSha256 = slotExpected,
                slotAcceptedSha256 = acceptedSlots,
                cave = payload.string("cave"),
            )
        }

        private fun parseReloc(payload: JsonObject, role: String): Reloc {
            val kind = payload.string("kind") ?: throw recipeError("RECIPE_RELOC_KIND_MISSING:$role")
            val target = payload["target"]?.jsonObject
            return Reloc(
                at = payload.int("at") ?: throw recipeError("RECIPE_RELOC_AT_MISSING:$role"),
                kind = kind,
                op = payload.string("op"),
                opKind = payload.string("op_kind"),
                adrAt = payload.int("adr"),
                reg = payload.int("reg"),
                words = payload.int("words"),
                targetSymbol = target?.string("symbol"),
                targetDelta = target?.int("delta") ?: 0,
                selfDelta = if (kind == KIND_U32_SELF) payload.int("delta") ?: 0 else 0,
                name = payload.string("name"),
                length = payload.int("length"),
                imm = payload.number("imm"),
                rd = payload.int("rd"),
                shift = if (kind == KIND_WORD) payload.int("shift") else null,
                sf = if (kind == KIND_WORD) payload.int("sf") else null,
                constant = payload.string("value")?.let { parseInteger(it, role) },
            )
        }

        private fun parseInteger(text: String, role: String): Long = try {
            when {
                text.startsWith("0x") || text.startsWith("0X") -> text.substring(2).toLong(16)
                else -> text.toLong()
            }
        } catch (error: NumberFormatException) {
            throw recipeError("RECIPE_CONST_INVALID:$role:$text")
        }

        private fun decodeHex(text: String, role: String): ByteArray {
            if (text.length % 2 != 0 || !text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                throw recipeError("RECIPE_TEMPLATE_HEX_INVALID:$role")
            }
            return ByteArray(text.length / 2) {
                ((Character.digit(text[it * 2], 16) shl 4) or Character.digit(text[it * 2 + 1], 16)).toByte()
            }
        }

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.content?.takeIf { this[key]?.jsonPrimitive?.isString == true }

        private fun JsonObject.int(key: String): Int? =
            (this[key]?.jsonPrimitive?.takeIf { !it.isString && it.content.isNotEmpty() })?.content?.toIntOrNull()

        private fun JsonObject.number(key: String): Long? =
            (this[key]?.jsonPrimitive?.takeIf { !it.isString && it.content.isNotEmpty() })?.content?.toLongOrNull()

        private fun JsonObject.boolean(key: String): Boolean? =
            (this[key]?.jsonPrimitive?.takeIf { !it.isString })?.content?.toBooleanStrictOrNull()

        internal fun recipeError(detail: String): UltraKernelGenerateException =
            UltraKernelGenerateException(UltraKernelGenerateCodes.RECIPE, detail)

        internal fun sha256Of(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
