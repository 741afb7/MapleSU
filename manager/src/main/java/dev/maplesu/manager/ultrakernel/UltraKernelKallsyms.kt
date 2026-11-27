package dev.maplesu.manager.ultrakernel

/**
 * "Where is this symbol inside the raw kernel image?" - the only thing the recipe needs from a
 * kernel besides the bytes themselves.
 *
 * Kept as an interface for two reasons: the recipe renderer never has to know how a table was
 * obtained, and a host test can drive the whole renderer with a hand-built table (which is how
 * k22's 18 blobs are re-derived without a 36 MB image and without a device).
 */
internal interface UltraKernelSymbols {

    /** Size of the raw kernel image these offsets are relative to. */
    val imageSize: Int

    /** File offset of [name]; the lowest one when the table carries aliases. `null` = absent. */
    fun offsetOf(name: String): Int?

    /** All distinct offsets of [name], in ascending order; simple symbol providers have one. */
    fun offsetsOf(name: String): List<Int> = listOfNotNull(offsetOf(name))

    /** Image-relative memory boundaries, including BSS beyond the bytes stored in the file. */
    fun memoryOffsetsOf(name: String): List<Int> = offsetsOf(name)

    /** A memory boundary is usable only when its exact name has one distinct address. */
    fun memoryOffsetOf(name: String): Int? = memoryOffsetsOf(name).singleOrNull()

    /** Includes file-external BSS symbols; this does not prove that a range is unoccupied. */
    fun hasMemorySymbolIn(start: Int, end: Int): Boolean = hasSymbolIn(start, end)

    /** True when [value] is exactly the offset of some symbol (used by the residue check). */
    fun isSymbolStart(value: Int): Boolean

    /** Offset of the first symbol strictly after [offset], or `null`. */
    fun nextOffset(offset: Int): Int?

    /** A name at an occupied boundary, used only in capacity diagnostics. */
    fun nameAt(offset: Int): String? = null

    /** True when any known symbol starts inside the half-open range. */
    fun hasSymbolIn(start: Int, end: Int): Boolean = false

    /** Nearest known symbol strictly before [offset], used to exclude init-only text. */
    fun nameBefore(offset: Int): String? = null

    /** One-line diagnostic; never includes symbol names a caller could confuse for a result. */
    val summary: String
}

/**
 * The in-image kallsyms table of a raw arm64 `Image`, decoded without any engine, ELF, or device.
 *
 * This is a port of `tools/specgen/specgen/kallsyms.py` - the decoder that resolved all 18 k22
 * sites and 106142 symbols on 6.6.89 with the same layout facts the engine logs. Having it on the
 * device is what makes "generate a plan for a kernel that has no shipped asset" possible at all:
 * every recipe site is a `(symbol, delta)` pair, so no file offset is ever copied between builds.
 *
 * Layout walked (all pointer-sized fields are `element_size`, discovered, not assumed):
 *
 *     kallsyms_num_syms      u32 / u64
 *     kallsyms_names         length-prefixed token strings (>= 0x80 escapes to 2 bytes)
 *     kallsyms_markers       one names offset per 256 symbols
 *     kallsyms_token_table   256 NUL-terminated token strings
 *     kallsyms_token_index   256 u16 offsets into the token table
 *     kallsyms_offsets       s32 per symbol, BASE_RELATIVE (kernel >= 4.6)
 *     kallsyms_relative_base u64
 *
 * Kernel >= 6.4 places `kallsyms_offsets` *after* `kallsyms_token_index`; older kernels place the
 * address table before `kallsyms_names`. Both orders are attempted, newest first.
 *
 * The decoder only reads: it never writes to the image and never executes anything.
 */
internal class UltraKernelKallsymsTable private constructor(
    override val imageSize: Int,
    val textBase: Long,
    val relativeBase: Long,
    val symbolCount: Int,
    private val byName: HashMap<String, Int>,
    private val duplicatesByName: Map<String, List<Int>>,
    private val starts: HashSet<Int>,
    private val ordered: IntArray,
    private val memoryTailByName: Map<String, List<Int>>,
    private val memoryTailStarts: IntArray,
    private val tokenTableAt: Int,
    private val tokenIndexAt: Int,
    private val namesAt: Int,
    private val markersAt: Int,
    private val offsetsAt: Int,
) : UltraKernelSymbols {

    // Clang LTO gives internal functions a $<32-hex> suffix. Resolve only a unique
    // body address; .cfi_jt entries and competing definitions remain distinct.
    private val ltoAliases: Map<String, Int> = run {
        val suffix = Regex("""^(.+)\$[0-9a-f]{32}$""")
        byName.keys.mapNotNull { name ->
            suffix.matchEntire(name)?.groupValues?.get(1)?.let { it to name }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (name, originals) ->
            val addresses = originals.flatMap { duplicatesByName[it] ?: listOf(byName.getValue(it)) }.distinct()
            addresses.singleOrNull()?.let { name to it }
        }.toMap()
    }

    override fun offsetOf(name: String): Int? = byName[name] ?: ltoAliases[name]

    override fun offsetsOf(name: String): List<Int> = duplicatesByName[name] ?: super.offsetsOf(name)

    override fun memoryOffsetsOf(name: String): List<Int> =
        (offsetsOf(name) + memoryTailByName[name].orEmpty()).distinct().sorted()

    override fun hasMemorySymbolIn(start: Int, end: Int): Boolean {
        if (hasSymbolIn(start, end)) return true
        if (start >= end) return false
        val found = memoryTailStarts.binarySearch(start)
        val at = if (found >= 0) found else -found - 1
        return at < memoryTailStarts.size && memoryTailStarts[at] < end
    }

    override fun isSymbolStart(value: Int): Boolean = starts.contains(value)

    override fun nameAt(offset: Int): String? = byName.entries.firstOrNull { it.value == offset }?.key

    override fun hasSymbolIn(start: Int, end: Int): Boolean {
        if (start >= end) return false
        val at = firstAtOrAfter(start)
        return at < ordered.size && ordered[at] < end
    }

    override fun nameBefore(offset: Int): String? {
        val at = firstAtOrAfter(offset) - 1
        return if (at >= 0) nameAt(ordered[at]) else null
    }

    override fun nextOffset(offset: Int): Int? {
        var low = 0
        var high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ordered[middle] <= offset) low = middle + 1 else high = middle
        }
        val at = low
        return if (at < ordered.size) ordered[at] else null
    }

    /** bisect_left: the first ordered entry greater than or equal to [offset]. */
    private fun firstAtOrAfter(offset: Int): Int {
        var low = 0
        var high = ordered.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ordered[middle] < offset) low = middle + 1 else high = middle
        }
        return low
    }

    override val summary: String
        get() = "symbols=$symbolCount textBase=0x${java.lang.Long.toHexString(textBase)}" +
            " tokenTable=0x${Integer.toHexString(tokenTableAt)}" +
            " tokenIndex=0x${Integer.toHexString(tokenIndexAt)}" +
            " names=0x${Integer.toHexString(namesAt)}" +
            " markers=0x${Integer.toHexString(markersAt)}" +
            " offsets=0x${Integer.toHexString(offsetsAt)}"

    companion object {
        /** arm64 `Image` magic sits at file offset 56 (`ARMd`). */
        private const val IMAGE_MAGIC_AT = 56
        private val IMAGE_MAGIC = byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'M'.code.toByte(), 'd'.code.toByte())

        /**
         * Run [block], turning every `check`/`require` failure into the one stable generation code
         * this class is allowed to emit. A raw `IllegalStateException` would leak internal
         * `KALLSYMS_*` anchors to the UI; a [UltraKernelGenerateException] carries `code` + `detail`.
         */
        private fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (already: UltraKernelGenerateException) {
                throw already
            } catch (error: IllegalStateException) {
                throw UltraKernelGenerateException(
                    "ULTRA_KERNEL_GENERATE_KALLSYMS",
                    error.message ?: error.javaClass.simpleName,
                )
            }

        /**
         * Decode the kallsyms table of [image] (raw arm64 `Image`).
         *
         * @throws UltraKernelGenerateException `ULTRA_KERNEL_GENERATE_KALLSYMS` for every malformed
         *         layout. Every failure is fail-closed: a partially decoded table would make a
         *         site resolve to a wrong offset, which is worse than refusing to generate.
         */
        fun decode(image: ByteArray): UltraKernelKallsymsTable = guard { decodeTable(image) }

        private fun decodeTable(image: ByteArray): UltraKernelKallsymsTable {
            val probe = Probe(image)
            probe.requireRawArm64()
            val tokenTable = probe.findTokenTable()
            val tokenIndex = probe.findTokenIndex(tokenTable)
            val markers = probe.findMarkers(tokenTable)
            val names = probe.findNames(markers.at, tokenTable, markers.elementSize)
            val counted = probe.findNumSyms(names, markers.at, tokenTable, markers.elementSize)
            val tokens = probe.readTokens(tokenTable)
            val offsets = try {
                guard { probe.findOffsets(counted.count, tokenIndex.end, markers.elementSize) }
            } catch (newer: UltraKernelGenerateException) {
                // Kernel < 6.4 keeps the address table before kallsyms_names. Same structures,
                // different order; retry with the older anchor before failing.
                try {
                    guard { probe.findOffsetsLegacy(counted.count, counted.numSymsAt, markers.elementSize) }
                } catch (older: UltraKernelGenerateException) {
                    throw UltraKernelGenerateException(
                        older.code,
                        "${older.detail}; newer=${newer.detail}",
                    )
                }
            }
            if (offsets.relativeBase == 0L) {
                check(counted.names == UltraKernelA64.alignUp(counted.numSymsAt + 4, 8)) {
                    "KALLSYMS_NUM_SYMS_ALIGNMENT"
                }
                check(tokenTable == UltraKernelA64.alignUp(
                    markers.at + (counted.count + 255) / 256 * markers.elementSize, 8)) {
                    "KALLSYMS_MARKER_TABLE_MISMATCH"
                }
            }
            val symbolNames = probe.readNames(counted.names, counted.count, tokens,
                if (offsets.relativeBase == 0L) markers else null)
            val addresses = probe.readAddresses(offsets.at, counted.count, offsets.relativeBase)
            if (offsets.relativeBase == 0L) {
                check(symbolNames.indices.any { symbolNames[it].drop(1) == "_text" && addresses[it] == 0L }) {
                    "KALLSYMS_IMAGE_RELATIVE_TEXT_MISMATCH"
                }
            }

            var firstText = 0L
            for (index in symbolNames.indices) {
                val name = symbolNames[index]
                if (name.isEmpty()) continue
                if (name[0].uppercaseChar() == 'T') {
                    firstText = addresses[index]
                    break
                }
            }
            // Unsigned comparison: kernel text addresses are in the top half of the address space.
            val textBase = if (java.lang.Long.compareUnsigned(offsets.relativeBase, firstText) < 0) {
                offsets.relativeBase and 0x1FFF.inv().toLong()
            } else {
                firstText and 0x1FFF.inv().toLong()
            }
            // _text is the start of the raw Image. A plausible base alone does not
            // detect a one-entry shift of the address table before generating hooks.
            check(symbolNames.indices.any { symbolNames[it].drop(1) == "_text" && addresses[it] == textBase }) {
                "KALLSYMS_TEXT_BASE_MISMATCH"
            }

            val byName = HashMap<String, Int>(counted.count * 2)
            val duplicatesByName = HashMap<String, MutableList<Int>>()
            val starts = HashSet<Int>(counted.count * 2)
            val collected = ArrayList<Int>(counted.count)
            val memoryTailByName = HashMap<String, MutableList<Int>>()
            val memoryTailStarts = ArrayList<Int>()
            for (index in addresses.indices) {
                val offset = addresses[index] - textBase
                if (offset < 0 || offset > Int.MAX_VALUE) continue
                val position = offset.toInt()
                // kallsyms stores the symbol *type* as the first character (`T_text`); the name is
                // everything after it. Everything that looks a symbol up by name (`die`, `panic`,
                // `module_alloc`) depends on this strip, and so does the text-base scan above,
                // which must therefore read the raw entry.
                val raw = symbolNames[index]
                val name = if (raw.isEmpty()) raw else raw.substring(1)
                if (position >= image.size) {
                    val boundaries = memoryTailByName.getOrPut(name) { ArrayList() }
                    if (position !in boundaries) boundaries += position
                    memoryTailStarts += position
                    continue
                }
                collected += position
                starts += position
                val previous = byName[name]
                if (previous != null && position != previous) {
                    val candidates = duplicatesByName.getOrPut(name) { arrayListOf(previous) }
                    if (position !in candidates) candidates += position
                }
                if (previous == null || position < previous) byName[name] = position
            }
            val ordered = collected.toIntArray()
            ordered.sort()
            return UltraKernelKallsymsTable(
                imageSize = image.size,
                textBase = textBase,
                relativeBase = offsets.relativeBase,
                symbolCount = collected.size,
                byName = byName,
                duplicatesByName = duplicatesByName.mapValues { it.value.sorted() },
                starts = starts,
                ordered = ordered,
                memoryTailByName = memoryTailByName.mapValues { it.value.sorted() },
                memoryTailStarts = memoryTailStarts.sorted().toIntArray(),
                tokenTableAt = tokenTable,
                tokenIndexAt = tokenIndex.start,
                namesAt = counted.names,
                markersAt = markers.at,
                offsetsAt = offsets.at,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Layout probing. Every method is a literal port; the comments name the Python anchor.
    // ---------------------------------------------------------------------------------------

    private class Markers(val elementSize: Int, val at: Int)

    private class TokenIndex(val start: Int, val end: Int, val bigEndian: Boolean)

    private class Counted(val count: Int, val names: Int, val numSymsAt: Int)

    private class Offsets(val at: Int, val relativeBase: Long)

    private class Probe(private val image: ByteArray) {

        private val size: Int get() = image.size

        private fun byteAt(index: Int): Int {
            check(index in 0 until size) { "KALLSYMS_BYTE_RANGE:$index" }
            return image[index].toInt() and 0xFF
        }

        private fun matchesAt(at: Int, needle: ByteArray): Boolean {
            if (at < 0 || at + needle.size > size) return false
            for (index in needle.indices) {
                if (image[at + index] != needle[index]) return false
            }
            return true
        }

        private fun indexOf(needle: ByteArray, from: Int, to: Int = size): Int {
            val last = minOf(to, size) - needle.size
            if (needle.isEmpty()) return if (from <= last) from else -1
            var start = maxOf(0, from)
            while (start <= last) {
                if (matchesAt(start, needle)) return start
                start++
            }
            return -1
        }

        private fun lastIndexOf(needle: ByteArray, from: Int, before: Int): Int {
            var start = minOf(before, size) - needle.size
            while (start >= from) {
                if (matchesAt(start, needle)) return start
                start--
            }
            return -1
        }

        /** Largest `position` such that `image[position, position+width)` are all zero. */
        private fun lastIndexOfZeros(width: Int, before: Int): Int {
            var position = minOf(before, size) - width
            while (position >= 0) {
                var index = 0
                while (index < width && image[position + index].toInt() == 0) index++
                if (index == width) return position
                position--
            }
            return -1
        }

        private fun readUnsigned(at: Int, width: Int): Long {
            check(at >= 0 && at + width <= size) { "KALLSYMS_TABLE_TRUNCATED:$at" }
            var value = 0L
            for (index in 0 until width) {
                value = value or ((image[at + index].toLong() and 0xFF) shl (8 * index))
            }
            return value
        }

        fun requireRawArm64() {
            check(size >= 64) { "KALLSYMS_IMAGE_TOO_SMALL" }
            check(matchesAt(IMAGE_MAGIC_AT, IMAGE_MAGIC)) { "KALLSYMS_RAW_ARM64_REQUIRED" }
        }

        /**
         * Locate `kallsyms_token_table`.
         *
         * Anchor: the ten one-character tokens `"0\0" "1\0" ... "9\0"` appear once. They are
         * preceded by 48 further tokens, which is why the walk goes back exactly 48 times.
         */
        fun findTokenTable(): Int {
            val needle = ByteArray(20) { index ->
                if (index % 2 == 0) ('0'.code + index / 2).toByte() else 0
            }
            val avoid = arrayOf(byteArrayOf(':'.code.toByte(), 0), byteArrayOf(0, 0), byteArrayOf(0, 1), byteArrayOf(0, 2), "ASCII".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
            val candidates = ArrayList<Int>(2)
            val clean = ArrayList<Int>(2)
            var position = -1
            while (true) {
                position = indexOf(needle, position + 1)
                if (position < 0) break
                val tail = position + needle.size
                if (avoid.any { matchesAt(tail, it) }) continue
                candidates += position
                if (tail < size && isAsciiAlphanumeric(byteAt(tail))) clean += position
            }
            val chosen = when {
                candidates.size != 1 && clean.size == 1 -> clean[0]
                candidates.size == 1 -> candidates[0]
                else -> throw UltraKernelGenerateException(
                    "ULTRA_KERNEL_GENERATE_KALLSYMS",
                    "KALLSYMS_TOKEN_TABLE_CANDIDATES:${candidates.size}",
                )
            }
            return anchorTokenTable(chosen)
        }

        private fun anchorTokenTable(found: Int): Int {
            var position = found - 1
            check(position >= 0 && image[position].toInt() == 0) { "KALLSYMS_TOKEN_TABLE_ANCHOR" }
            repeat('0'.code) {
                var stopped = false
                for (step in 0 until 50) {
                    position -= 1
                    check(position >= 0) { "KALLSYMS_TOKEN_TABLE_UNDERFLOW" }
                    val value = byteAt(position)
                    if (value == 0 || value > 'z'.code) {
                        stopped = true
                        break
                    }
                }
                check(stopped) { "KALLSYMS_TOKEN_TABLE_TOKEN_TOO_LONG" }
            }
            position += 1
            return UltraKernelA64.alignUp(position, 4)
        }

        fun readTokens(tokenTable: Int): Array<String> {
            val tokens = Array(256) { "" }
            var position = tokenTable
            for (index in 0 until 256) {
                val end = indexOf(byteArrayOf(0), position)
                check(end >= 0) { "KALLSYMS_TOKEN_TABLE_TRUNCATED" }
                tokens[index] = String(image, position, end - position, Charsets.ISO_8859_1)
                position = end + 1
            }
            return tokens
        }

        /** Locate `kallsyms_token_index`: 256 little-endian u16 offsets, right after the tokens. */
        fun findTokenIndex(tokenTable: Int): TokenIndex {
            val entries = IntArray(256)
            var position = tokenTable
            for (index in 0 until 256) {
                entries[index] = position - tokenTable
                val end = indexOf(byteArrayOf(0), position)
                check(end >= 0) { "KALLSYMS_TOKEN_INDEX_MISSING" }
                check(end - position <= 50) { "KALLSYMS_TOKEN_TOO_LONG" }
                position = end + 1
            }
            val windowEnd = minOf(size, position + 256 * 2 + 256)
            for (bigEndian in booleanArrayOf(false, true)) {
                val pattern = ByteArray(512)
                for (index in 0 until 256) {
                    val value = entries[index]
                    if (bigEndian) {
                        pattern[index * 2] = (value ushr 8).toByte()
                        pattern[index * 2 + 1] = value.toByte()
                    } else {
                        pattern[index * 2] = value.toByte()
                        pattern[index * 2 + 1] = (value ushr 8).toByte()
                    }
                }
                val found = indexOf(pattern, position, windowEnd)
                if (found >= 0) return TokenIndex(found, found + 512, bigEndian)
            }
            throw UltraKernelGenerateException("ULTRA_KERNEL_GENERATE_KALLSYMS", "KALLSYMS_TOKEN_INDEX_NOT_FOUND")
        }

        /** Locate `kallsyms_markers`: a repeated zero run whose entries ascend by <= 0x200. */
        fun findMarkers(tokenTable: Int): Markers {
            for (elementSize in intArrayOf(8, 4, 2)) {
                var position = tokenTable
                var attempt = 0
                while (attempt < 32) {
                    attempt++
                    val found = lastIndexOfZeros(elementSize, position)
                    if (found < 0) break
                    val aligned = found - (found % elementSize)
                    position = aligned
                    if (aligned + 4 * elementSize > size) continue
                    val entries = LongArray(4) { readUnsigned(aligned + it * elementSize, elementSize) }
                    if (entries[0] != 0L) continue
                    var monotone = true
                    for (index in 1 until entries.size) {
                        if (entries[index - 1] + 0x200 >= entries[index] ||
                            entries[index - 1] + 0x40000 < entries[index]
                        ) {
                            monotone = false
                            break
                        }
                    }
                    if (monotone) return Markers(elementSize, aligned)
                }
            }
            throw UltraKernelGenerateException("ULTRA_KERNEL_GENERATE_KALLSYMS", "KALLSYMS_MARKERS_NOT_FOUND")
        }

        /** `kallsyms_names` sits `markers[last]` bytes before the markers block. */
        fun findNames(markers: Int, tokenTable: Int, elementSize: Int): Int {
            val count = maxOf(1, minOf(3000, (tokenTable - markers) / elementSize))
            check(markers >= 0 && markers + count * elementSize <= size) {
                "KALLSYMS_MARKERS_TRUNCATED"
            }
            val entries = LongArray(count) { readUnsigned(markers + it * elementSize, elementSize) }
            var usable = entries.size
            for (index in 1 until entries.size) {
                if (entries[index - 1] + 0x200 >= entries[index] ||
                    entries[index - 1] + 0x40000 < entries[index]
                ) {
                    usable = index
                    break
                }
            }
            var last = -1L
            for (index in 0 until usable) {
                if (entries[index] != 0L) last = entries[index]
            }
            check(last > 0) { "KALLSYMS_NAMES_NOT_FOUND" }
            check(last <= markers.toLong()) { "KALLSYMS_NAMES_UNDERFLOW" }
            val position = markers - last.toInt()
            return UltraKernelA64.alignUp(position, elementSize)
        }

        /**
         * Walk the encoded names block backwards to find its true start and `kallsyms_num_syms`.
         *
         * This is the delicate one: the names block is not preceded by a length, so the start is
         * recovered by walking the length chain from `markers` downwards and requiring the chain to
         * yield a plausible symbol count, then confirming the count appears as a `num_syms` word
         * just before the block.
         */
        fun findNumSyms(startNames: Int, markers: Int, tokenTable: Int, elementSize: Int): Counted {
            val tokens = readTokens(tokenTable)
            val validTypes = "ABDRTVWGINPCSUuvw-?"
            var names = startNames
            var explored = IntArray(markers - startNames + 8)
            var exploredSize = 0
            while (true) {
                check(names >= 0) { "KALLSYMS_NAMES_UNDERFLOW" }
                check(names + 1 < size) { "KALLSYMS_NAMES_UNDERFLOW" }
                val firstToken = tokens[byteAt(names + 1)]
                if (firstToken.isEmpty()) {
                    names -= 4
                    continue
                }
                val head = firstToken[0]
                val plausible = ("uvw".indexOf(head.lowercaseChar()) >= 0 && validTypes.indexOf(head) >= 0) ||
                    validTypes.indexOf(head.uppercaseChar()) >= 0
                if (!plausible) {
                    names -= 4
                    continue
                }
                val span = markers - names
                check(span > 0 && span < size) { "KALLSYMS_NAMES_UNDERFLOW" }
                if (span >= explored.size) explored = explored.copyOf(maxOf(span + 1,
                    explored.size + explored.size / 2))
                var tail = exploredSize
                while (tail <= span) {
                    val value = byteAt(markers - tail)
                    val symbolSize = if (value and 0x80 != 0) {
                        check(markers - tail + 1 < size) { "KALLSYMS_NAMES_CORRUPT" }
                        ((value and 0x7F) or (byteAt(markers - tail + 1) shl 7)) + 2
                    } else {
                        value + 1
                    }
                    val hop = tail - symbolSize
                    val reached = when {
                        value == 0 -> if (tail <= 256) 0 else -1
                        hop < 0 || explored[hop] == -1 -> -1
                        else -> explored[hop] + 1
                    }
                    explored[tail] = reached
                    tail++
                }
                exploredSize = tail
                val count = explored[exploredSize - 1]
                if (count < 256) {
                    names -= 4
                    continue
                }
                check(elementSize > 2) { "KALLSYMS_NUM_SYMS_ELEMENT_SIZE" }
                val needle = ByteArray(elementSize) { index -> ((count.toLong() ushr (8 * index)) and 0xFF).toByte() }
                val found = lastIndexOf(needle, maxOf(0, names - 256 - 20), names)
                if (found < 0) {
                    names -= 4
                    continue
                }
                return Counted(count, names, found)
            }
        }

        fun readNames(startNames: Int, count: Int, tokens: Array<String>, markers: Markers? = null): Array<String> {
            val output = Array(count) { "" }
            var position = startNames
            for (index in 0 until count) {
                if (markers != null && index % 256 == 0) {
                    check(readUnsigned(markers.at + index / 256 * markers.elementSize,
                        markers.elementSize) == (position - startNames).toLong()) {
                        "KALLSYMS_NAMES_MARKER_MISMATCH:$index"
                    }
                }
                var length = byteAt(position)
                position++
                if (length and 0x80 != 0) {
                    check(position < size) { "KALLSYMS_NAMES_CORRUPT" }
                    length = (length and 0x7F) or (byteAt(position) shl 7)
                    position++
                }
                check(length > 0 && length < 0x4000 && position + length <= (markers?.at ?: size)) {
                    "KALLSYMS_NAMES_CORRUPT"
                }
                val builder = StringBuilder(length + 8)
                for (step in 0 until length) {
                    builder.append(tokens[byteAt(position + step)])
                }
                position += length
                output[index] = builder.toString()
            }
            if (markers != null) {
                check(markers.at == UltraKernelA64.alignUp(position, 8) &&
                    (position until markers.at).all { byteAt(it) == 0 }) {
                    "KALLSYMS_NAMES_MARKER_END_MISMATCH"
                }
            }
            return output
        }

        /** Locate `kallsyms_offsets` (kernel >= 6.4) and `kallsyms_relative_base`. */
        fun findOffsets(count: Int, tokenIndexEnd: Int, elementSize: Int): Offsets {
            val addressSize = 8
            val offsetSize = minOf(4, elementSize)
            val align = 8
            // The u64 base has its own alignment. With an odd number of s32 offsets,
            // baseAt-count*4 points four bytes into the table, pairing every name with
            // the following symbol. Anchor the table before aligning its END instead.
            val offsetsAt = UltraKernelA64.alignUp(tokenIndexEnd, align)
            val baseAt = UltraKernelA64.alignUp(offsetsAt + count * offsetSize, align)
            check(baseAt + addressSize <= size) { "KALLSYMS_OFFSETS_OUT_OF_RANGE" }
            check(baseAt > 0) { "KALLSYMS_RELATIVE_BASE_NOT_FOUND" }
            val relativeBase = readUnsigned(baseAt, addressSize)
            checkPlausibleBase(relativeBase)
            check(offsetsAt >= tokenIndexEnd && offsetsAt < baseAt) { "KALLSYMS_OFFSETS_OUT_OF_RANGE" }
            return Offsets(offsetsAt, relativeBase)
        }

        /** Kernel < 6.4: `kallsyms_relative_base` precedes `kallsyms_num_syms`. */
        fun findOffsetsLegacy(count: Int, numSymsAt: Int, elementSize: Int): Offsets {
            val addressSize = 8
            val offsetSize = minOf(4, elementSize)
            // Each label is eight-byte aligned and the base is a complete u64. A zero
            // base slot is data, not padding to skip into the final symbol offsets.
            val baseAt = numSymsAt - addressSize
            check(baseAt > 0) { "KALLSYMS_RELATIVE_BASE_NOT_FOUND" }
            val relativeBase = readUnsigned(baseAt, addressSize)
            if (relativeBase == 0L) {
                // A raw relocatable Image may leave this absolute pointer zero until
                // RELA applies _text. Use Image-relative coordinates only with the exact
                // count/names labels; the full marker walk and _text=0 are checked above.
                check(numSymsAt % addressSize == 0 && readUnsigned(numSymsAt + 4, 4) == 0L) {
                    "KALLSYMS_NUM_SYMS_ALIGNMENT"
                }
            } else {
                checkPlausibleBase(relativeBase)
            }
            // Both labels are aligned to eight bytes. Only the table's length determines
            // its end padding: zero is also a valid offset and must never be skipped.
            val offsetsAt = baseAt - UltraKernelA64.alignUp(count * offsetSize, addressSize)
            check(offsetsAt >= 0 && offsetsAt < baseAt) { "KALLSYMS_OFFSETS_OUT_OF_RANGE" }
            return Offsets(offsetsAt, relativeBase)
        }

        private fun checkPlausibleBase(relativeBase: Long) {
            // 0xFFFF000000000000.., i.e. a kernel-space address. Compared signed: every u64 in that
            // range lands in [-2^48, -1].
            check(relativeBase >= -(1L shl 48) && relativeBase < 0) {
                "KALLSYMS_RELATIVE_BASE_IMPLAUSIBLE:0x${java.lang.Long.toHexString(relativeBase)}"
            }
        }

        fun readAddresses(offsetsAt: Int, count: Int, relativeBase: Long): LongArray {
            check(offsetsAt >= 0 && offsetsAt + count * 4 <= size) { "KALLSYMS_OFFSETS_TRUNCATED" }
            val addresses = LongArray(count)
            for (index in 0 until count) {
                val raw = UltraKernelA64.le32(image, offsetsAt + index * 4).toInt()
                addresses[index] = relativeBase + raw.toLong()
            }
            return addresses
        }

        private fun isAsciiAlphanumeric(value: Int): Boolean =
            value in '0'.code..'9'.code || value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code
    }
}
