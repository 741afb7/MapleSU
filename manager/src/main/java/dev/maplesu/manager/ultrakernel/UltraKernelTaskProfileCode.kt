package dev.maplesu.manager.ultrakernel

/** Bounded Image instruction proofs for the fixed KernelSU task flag contract. */
internal object UltraKernelTaskProfileCode {
    data class Evidence(val functions: Map<String, String>, val calls: List<String>, val stores: List<String>,
        val boundaries: List<String>)
    data class WorkBitEvidence(val member: Int, val bit: Int, val function: String,
        val branch: Int, val traceReference: Int, val functionSha256: String)

    /** Enumerate actual static-key and CPU alternatives; unresolved relocation remains a failure. */
    private fun runtimeChoices(kernel: ByteArray, symbols: UltraKernelSymbols, at: Int, end: Int,
        name: String): Map<Int, Set<Int>> {
        fun fail(reason: String): Nothing = error("ULTRA_KERNEL_TASK_PROFILE_" + reason)
        val choices = linkedMapOf<Int, Set<Int>>()
        val alternativeStart = symbols.offsetOf("__alt_instructions")
        val alternativeEnd = symbols.offsetOf("__alt_instructions_end")
        if (alternativeStart != null || alternativeEnd != null) {
            if (alternativeStart == null || alternativeEnd == null || alternativeStart < 0 || alternativeEnd > kernel.size ||
                alternativeEnd < alternativeStart || (alternativeEnd - alternativeStart) % 12 != 0) fail("ALTERNATIVE_METADATA")
            for (entry in alternativeStart until alternativeEnd step 12) {
                val original = entry.toLong() + UltraKernelA64.le32Bits(kernel, entry)
                if (original !in at.toLong() until end.toLong()) continue
                val replacement = entry.toLong() + 4 + UltraKernelA64.le32Bits(kernel, entry + 4)
                val oldBytes = kernel[entry + 10].toInt() and 255
                val newBytes = kernel[entry + 11].toInt() and 255
                val feature = (kernel[entry + 8].toInt() and 255) or ((kernel[entry + 9].toInt() and 255) shl 8)
                if (oldBytes == 0 || oldBytes % 4 != 0 || newBytes % 4 != 0 || newBytes > oldBytes ||
                    original % 4 != 0L || original + oldBytes > end || replacement < 0 || replacement + newBytes > kernel.size)
                    fail("ALTERNATIVE_BOUNDS:$name:$original")
                val callback = feature and 0x8000 != 0
                if (callback) {
                    if (newBytes != 0) fail("ALTERNATIVE_CALLBACK:$name:$original")
                    UltraKernelStructOffsets.verifyAlternativeNops(symbols, kernel, replacement.toInt(), oldBytes)
                } else if (newBytes != oldBytes) fail("ALTERNATIVE_LENGTH:$name:$original")
                for (delta in 0 until oldBytes step 4) {
                    val pc = original.toInt() + delta
                    var replacementWord = if (callback) UltraKernelA64.NOP
                        else UltraKernelA64.le32Bits(kernel, replacement.toInt() + delta)
                    if (replacementWord and 0x7c000000 == 0x14000000) {
                        val target = replacement + delta +
                            UltraKernelA64.signExtend((replacementWord and 0x03ffffff).toLong(), 26) * 4
                        if (target < replacement || target > replacement + newBytes) {
                            if (target < 0 || target >= kernel.size) fail("ALTERNATIVE_BRANCH_TARGET:$name:$pc")
                            replacementWord = UltraKernelA64.encodeB(pc.toLong(), target) or (replacementWord and Int.MIN_VALUE)
                        }
                    }
                    if (UltraKernelA64.isAdr(replacementWord) || UltraKernelA64.isAdrp(replacementWord) ||
                        replacementWord and 0x3b000000 == 0x18000000 ||
                        replacementWord and 0xff000010.toInt() == 0x54000000 ||
                        replacementWord and 0x7e000000 in setOf(0x34000000, 0x36000000))
                        fail("ALTERNATIVE_RELOCATION:$name:$pc")
                    choices[pc] = choices[pc].orEmpty() + setOf(UltraKernelA64.le32Bits(kernel, pc), replacementWord)
                }
            }
        }
        val jumpStart = symbols.offsetOf("__start___jump_table")
        val jumpEnd = symbols.offsetOf("__stop___jump_table")
        if (jumpStart != null || jumpEnd != null) {
            if (jumpStart == null || jumpEnd == null || jumpStart < 0 || jumpEnd > kernel.size ||
                jumpEnd < jumpStart || (jumpEnd - jumpStart) % 16 != 0) fail("JUMP_METADATA")
            for (entry in jumpStart until jumpEnd step 16) {
                val pc = entry.toLong() + UltraKernelA64.le32Bits(kernel, entry)
                if (pc !in at.toLong() until end.toLong()) continue
                val target = entry.toLong() + 4 + UltraKernelA64.le32Bits(kernel, entry + 4)
                if (target !in at.toLong() until end.toLong() || pc % 4 != 0L || target % 4 != 0L) fail("JUMP_TARGET:$name:$pc")
                val branch = UltraKernelA64.encodeB(pc, target)
                if (UltraKernelA64.le32Bits(kernel, pc.toInt()) !in setOf(branch, UltraKernelA64.NOP)) fail("JUMP_WORD:$name:$pc")
                choices[pc.toInt()] = choices[pc.toInt()].orEmpty() + setOf(branch, UltraKernelA64.NOP)
            }
        }
        return choices
    }

    /** The bit-set edge must exclusively reach a real sys_enter reference in the native CFG. */
    fun workBit(kernel: ByteArray, symbols: UltraKernelSymbols, taskWorkOffset: Int,
        genericEntry: Boolean, tracepointFuncsOffset: Int? = null): WorkBitEvidence {
        data class Register(val currentOffset: Int? = null, val work: Boolean = false)
        data class Cursor(val pc: Int, val registers: List<Register?>, val instruction: Int? = null)
        val tracepoint = symbols.offsetsOf("__tracepoint_sys_enter").singleOrNull()
            ?: error("ULTRA_KERNEL_TASK_PROFILE_TRACEPOINT_OBJECT")
        val tracepointFuncs = tracepointFuncsOffset?.let {
            require(it >= 0 && it % 8 == 0 && tracepoint.toLong() + it + 8 <= kernel.size) {
                "ULTRA_KERNEL_TASK_PROFILE_TRACEPOINT_FUNCS"
            }
            tracepoint + it
        }
        val traceAddresses = setOfNotNull(tracepoint, tracepointFuncs).map(Int::toLong).toSet()
        val iterators = listOf("__traceiter_sys_enter", "__do_trace_sys_enter").flatMap(symbols::offsetsOf).toSet()
        val traceHelper = symbols.offsetsOf("trace_sys_enter").singleOrNull()
        var helperReferences: Set<Int>? = null
        val matches = linkedSetOf<WorkBitEvidence>()
        for (name in listOf("syscall_trace_enter", "syscall_enter_from_user_mode_work")) {
            val begin = symbols.offsetsOf(name).singleOrNull() ?: continue
            val end = symbols.nextOffset(begin) ?: continue
            if (begin < 0 || begin % 4 != 0 || end <= begin || end > kernel.size || end - begin > 16384) continue
            val choices = runtimeChoices(kernel, symbols, begin, end, name)
            fun words(pc: Int) = choices[pc].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc)) }
            fun immediate(word: Int, pc: Int, bits: Int) = pc +
                (UltraKernelA64.signExtend(((word ushr 5) and ((1 shl bits) - 1)).toLong(), bits) * 4).toInt()
            fun direct(word: Int, pc: Int) = pc +
                (UltraKernelA64.signExtend((word and 0x03ffffff).toLong(), 26) * 4).toInt()
            fun terminal(word: Int) = word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
                word and 0xffe0001f.toInt() == 0xd4200000.toInt()
            fun checkControl(word: Int, pc: Int) {
                // Match the existing live work-bit decoder: RET and BRK end this local path.
                if (terminal(word)) return
                // Only decoded RET and BLR have known local behavior in this register-control family.
                if (word and 0xfe000000.toInt() == 0xd6000000.toInt() &&
                    word and 0xfffffc1f.toInt() !in setOf(0xd65f0000.toInt(), 0xd63f0000.toInt()))
                    error("ULTRA_KERNEL_TASK_PROFILE_TRACE_INDIRECT_CONTROL:$name:$pc")
                // Exceptions and unallocated controls do not supply a proven pc+4 successor.
                if (word and 0xff000000.toInt() == 0xd4000000.toInt() || word and 0xffff0000.toInt() == 0)
                    error("ULTRA_KERNEL_TASK_PROFILE_TRACE_EXCEPTION_CONTROL:$name:$pc")
                if (word and 0xff000000.toInt() == 0x54000000 && word and 0x10 != 0)
                    error("ULTRA_KERNEL_TASK_PROFILE_TRACE_UNKNOWN_CONTROL:$name:$pc")
            }
            fun references(from: Int, testedBranch: Int, referenceBegin: Int = begin,
                referenceEnd: Int = end, referenceName: String = name, inspectHelper: Boolean = true): Set<Int> {
                data class ReferenceCursor(val pc: Int, val registers: List<Long?>)
                val pending = java.util.ArrayDeque<ReferenceCursor>(); pending += ReferenceCursor(from, List(32) { null })
                val seen = hashSetOf<ReferenceCursor>(); val result = linkedSetOf<Int>()
                val referenceChoices = if (referenceBegin == begin) choices
                    else runtimeChoices(kernel, symbols, referenceBegin, referenceEnd, referenceName)
                while (pending.isNotEmpty()) {
                    val state = pending.removeLast()
                    val pc = state.pc
                    if (pc == testedBranch || !seen.add(state)) continue
                    require(pc in referenceBegin until referenceEnd && pc % 4 == 0 && seen.size <= 4096) {
                        "ULTRA_KERNEL_TASK_PROFILE_TRACE_CONTROL:$name:$pc"
                    }
                    for (word in referenceChoices[pc].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc)) }) {
                        checkControl(word, pc)
                        val regs = state.registers.toMutableList()
                        val rd = word and 31; val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
                        fun push(target: Int) { pending += ReferenceCursor(target, regs.toList()) }
                        if (UltraKernelA64.isBl(word) && direct(word, pc) in iterators) result += pc
                        if (inspectHelper && UltraKernelA64.isBl(word) && direct(word, pc) == traceHelper) {
                            val target = requireNotNull(traceHelper); val targetEnd = symbols.nextOffset(target)
                            require(target >= 0 && target % 4 == 0 && targetEnd != null && targetEnd > target &&
                                targetEnd <= kernel.size && targetEnd - target <= 16384) {
                                "ULTRA_KERNEL_TASK_PROFILE_TRACE_HELPER_BOUNDS"
                            }
                            result += helperReferences ?: references(target, -1, target, targetEnd,
                                "trace_sys_enter", false).also { helperReferences = it }
                        }
                        // Track each CFG path's address registers across unrelated scheduled instructions.
                        when {
                            UltraKernelA64.isAdr(word) -> {
                                regs[rd] = UltraKernelA64.decodeAdr(word, pc.toLong())
                                if (rd != 31 && regs[rd] in traceAddresses) result += pc
                            }
                            UltraKernelA64.isAdrp(word) -> {
                                val packed = (((word ushr 5) and 0x7ffff).toLong() shl 2) or ((word ushr 29) and 3).toLong()
                                regs[rd] = (pc.toLong() and -4096L) + (UltraKernelA64.signExtend(packed, 21) shl 12)
                            }
                            word and 0xff000000.toInt() == 0x91000000.toInt() -> {
                                regs[rd] = regs[rn]?.plus(((word ushr 10) and 4095).toLong() shl
                                    (if (word and (1 shl 22) != 0) 12 else 0))
                                if (rd != 31 && regs[rd] in traceAddresses) result += pc
                            }
                            word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() -> regs[rd] = regs[rm]
                            word and 0xffc00000.toInt() == 0xf9400000.toInt() -> {
                                if (tracepointFuncs != null && regs[rn]?.plus(((word ushr 10) and 4095) * 8) ==
                                    tracepointFuncs.toLong()) result += pc
                                regs[rd] = null
                            }
                            UltraKernelA64.isBl(word) || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt() ->
                                for (index in 0..18) regs[index] = null
                            terminal(word) || word and 0xfc000000.toInt() == 0x14000000 ||
                                word and 0xff000010.toInt() == 0x54000000 ||
                                word and 0x7e000000 in setOf(0x34000000, 0x36000000) -> Unit
                            word == UltraKernelA64.NOP || word and 0xfffff01f.toInt() == 0xd503201f.toInt() -> Unit
                            word and 0xfff00000.toInt() == 0xd5100000.toInt() -> Unit
                            word and 0x3b000000 == 0x39000000 || word and 0x3b200c00 == 0x38200800 ->
                                if (word ushr 22 and 3 != 0) regs[rd] = null
                            word and 0x3b200000 == 0x38000000 -> {
                                if (word ushr 22 and 3 != 0) regs[rd] = null
                                if (word ushr 10 and 3 in setOf(1, 3)) regs[rn] = null
                            }
                            word and 0x3e000000 == 0x28000000 -> {
                                if (word and (1 shl 22) != 0) { regs[rd] = null; regs[word ushr 10 and 31] = null }
                                if (word ushr 23 and 3 in setOf(1, 3)) regs[rn] = null
                            }
                            word and 0x1c000000 == 0x10000000 || word and 0x0e000000 == 0x0a000000 ||
                                word and 0xfff00000.toInt() == 0xd5300000.toInt() -> regs[rd] = null
                            else -> for (index in 0..30) regs[index] = null
                        }
                        regs[31] = null
                        when {
                            terminal(word) -> Unit
                            word and 0xfc000000.toInt() == 0x14000000 -> push(direct(word, pc))
                            word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000 -> {
                                push(immediate(word, pc, 19)); push(pc + 4)
                            }
                            word and 0x7e000000 == 0x36000000 -> {
                                push(immediate(word, pc, 14)); push(pc + 4)
                            }
                            word and 0xfffffc1f.toInt() == 0xd61f0000.toInt() ->
                                error("ULTRA_KERNEL_TASK_PROFILE_TRACE_INDIRECT_BRANCH:$name:$pc")
                            else -> push(pc + 4)
                        }
                    }
                }
                return result
            }
            val initial = MutableList<Register?>(32) { null }
            if (genericEntry) {
                val count = UltraKernelBtf.functionParameterCount(kernel, symbols, name) ?: continue
                val work = (0 until count).filter {
                    UltraKernelBtf.parameterSignature(kernel, symbols, name, it) == "i8:64"
                }.singleOrNull()
                if (work != null && work < 8) initial[work] = Register(work = true)
            }
            val queue = java.util.ArrayDeque<Cursor>(); queue += Cursor(begin, initial)
            val seen = hashSetOf<Cursor>()
            while (queue.isNotEmpty()) {
                val state = queue.removeLast()
                require(seen.size < 8192) { "ULTRA_KERNEL_TASK_PROFILE_TRACE_CFG_BUDGET:$name" }
                if (state.pc !in begin until end || state.pc % 4 != 0 || !seen.add(state)) continue
                val pc = state.pc
                if (state.instruction == null && words(pc).size > 1) {
                    words(pc).forEach { queue += state.copy(instruction = it) }; continue
                }
                val word = state.instruction ?: words(pc).single()
                checkControl(word, pc)
                val regs = state.registers.toMutableList()
                val rd = word and 31; val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
                fun push(target: Int) { queue += Cursor(target, regs.toList()) }
                when {
                    terminal(word) -> continue
                    word and 0xffffffe0.toInt() == UltraKernelA64.MRS_SP_EL0 -> regs[rd] = Register(currentOffset = 0)
                    word and 0xff000000.toInt() == 0x91000000.toInt() -> regs[rd] = regs[rn]?.currentOffset?.let {
                        Register(currentOffset = it + (((word ushr 10) and 4095) shl (if (word and (1 shl 22) != 0) 12 else 0))) }
                    word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() -> regs[rd] = regs[rm]
                    word and 0xffc00000.toInt() == 0xf9400000.toInt() -> regs[rd] = regs[rn]?.currentOffset?.let {
                        Register(work = it + ((word ushr 10) and 4095) * 8 == taskWorkOffset) }
                    word and 0xfffffc00.toInt() in setOf(0xc8dffc00.toInt(), 0xf8bfc000.toInt()) ->
                        regs[rd] = regs[rn]?.currentOffset?.let { Register(work = it == taskWorkOffset) }
                    word and 0x7e000000 == 0x36000000 -> {
                        val target = immediate(word, pc, 14)
                        val bit = ((word ushr 19) and 31) or ((word ushr 26) and 32)
                        if (regs[rd]?.work == true && bit < 63) {
                            val setEdge = if (word and (1 shl 24) == 0) pc + 4 else target
                            val clearEdge = if (setEdge == target) pc + 4 else target
                            val exclusive = references(setEdge, pc) - references(clearEdge, pc)
                            exclusive.sorted().forEach { ref -> matches += WorkBitEvidence(if (genericEntry) 1 else 0,
                                bit, name, pc, ref, UltraKernelSpec.sha256(kernel.copyOfRange(begin, end))) }
                        }
                        push(target)
                    }
                    word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000 ->
                        push(immediate(word, pc, 19))
                    word and 0xfc000000.toInt() == 0x14000000 -> { push(direct(word, pc)); continue }
                    UltraKernelA64.isBl(word) || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt() ->
                        for (index in 0..18) regs[index] = null
                    word and 0xfe000000.toInt() == 0xd6000000.toInt() ->
                        error("ULTRA_KERNEL_TASK_PROFILE_TRACE_INDIRECT_CONTROL:$name:$pc")
                    word == UltraKernelA64.NOP || word and 0xfffff01f.toInt() == 0xd503201f.toInt() -> Unit
                    word and 0xfff00000.toInt() == 0xd5100000.toInt() -> Unit
                    word and 0x3b000000 == 0x39000000 || word and 0x3b200c00 == 0x38200800 ->
                        if (word ushr 22 and 3 != 0) regs[rd] = null
                    word and 0x3b200000 == 0x38000000 -> {
                        if (word ushr 22 and 3 != 0) regs[rd] = null
                        if (word ushr 10 and 3 in setOf(1, 3)) regs[rn] = null
                    }
                    word and 0x3e000000 == 0x28000000 -> {
                        if (word and (1 shl 22) != 0) { regs[rd] = null; regs[word ushr 10 and 31] = null }
                        if (word ushr 23 and 3 in setOf(1, 3)) regs[rn] = null
                    }
                    word and 0x1c000000 == 0x10000000 || word and 0x0e000000 == 0x0a000000 ||
                        word and 0xfff00000.toInt() == 0xd5300000.toInt() -> regs[rd] = null
                    else -> for (index in 0..30) regs[index] = null
                }
                push(pc + 4)
            }
        }
        require(matches.map { it.bit }.distinct().size == 1) { "ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_BRANCH" }
        val selected = matches.first()
        if (genericEntry) require(UltraKernelBtf.enumValue(kernel, symbols, "SYSCALL_WORK_BIT_SYSCALL_TRACEPOINT") == selected.bit.toLong()) {
            "ULTRA_KERNEL_TASK_PROFILE_TRACE_WORK_ENUM"
        }
        return selected
    }

    /** Backwards native provenance over every predecessor and runtime instruction variant. */
    private fun argumentFrom(kernel: ByteArray, symbols: UltraKernelSymbols, start: Int, end: Int,
        before: Int, argument: Int, parameter: Int? = null, current: Boolean = false,
        callResult: Int? = null, callResultSite: Int? = null): Boolean {
        val choices = runtimeChoices(kernel, symbols, start, end, "argument-$before")
        fun words(pc: Int) = choices[pc].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc)) }
        val predecessors = hashMapOf<Int, MutableSet<Int>>()
        fun edge(from: Int, target: Int) {
            if (target in start until end && target % 4 == 0)
                predecessors.getOrPut(target) { linkedSetOf() }.add(from)
        }
        for (pc in start until end step 4) for (word in words(pc)) {
            when {
                word and 0xfc000000.toInt() == 0x14000000 ->
                    edge(pc, pc + ((word shl 6) shr 4))
                word and 0xff000010.toInt() == 0x54000000 || word and 0x7e000000 == 0x34000000 -> {
                    edge(pc, pc + (((word ushr 5 and 0x7ffff) shl 13) shr 11)); edge(pc, pc + 4)
                }
                word and 0x7e000000 == 0x36000000 -> {
                    edge(pc, pc + (((word ushr 5 and 0x3fff) shl 18) shr 16)); edge(pc, pc + 4)
                }
                word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() ||
                    word and 0xfffffc1f.toInt() == 0xd61f0000.toInt() -> Unit
                else -> edge(pc, pc + 4)
            }
        }
        fun preserves(word: Int, register: Int): Boolean {
            if (UltraKernelA64.isBl(word) || word and 0xfffffc1f.toInt() == 0xd63f0000.toInt()) return register in 19..29
            if (word and 0xffe0001f.toInt() == 0xd4200000.toInt()) return true
            if (word and 0xfc000000.toInt() == 0x14000000 || word and 0xff000010.toInt() == 0x54000000 ||
                word and 0x7e000000 in setOf(0x34000000, 0x36000000)) return true
            if (word == UltraKernelA64.NOP || word and 0xfffff01f.toInt() == 0xd503201f.toInt()) return true
            if (word and 0xfff00000.toInt() == 0xd5300000.toInt()) return word and 31 != register
            if (word and 0xfff00000.toInt() == 0xd5100000.toInt()) return true
            if (word and 0x3fff7c00 == 0x085f7c00 || word and 0x3ffffc00 == 0x08dffc00)
                return word and 31 != register
            if (word and 0x3fe07c00 == 0x08007c00 || word and 0x3fa07c00 == 0x08a07c00)
                return word ushr 16 and 31 != register
            if (word and 0x3ffffc00 == 0x089ffc00) return true
            if (word and 0x3b200c00 == 0x38200000) return word and 31 != register
            if (word and 0x3f000000 == 0x39000000)
                return word ushr 22 and 3 == 0 || word and 31 != register
            if (word and 0x3f000000 == 0x38000000) {
                val writeback = word and 0x00200000 == 0 && (word ushr 10 and 3) in setOf(1, 3)
                return (!writeback || word ushr 5 and 31 != register) && (word ushr 22 and 3 == 0 || word and 31 != register)
            }
            if (word and 0x3e000000 == 0x28000000) {
                val writeback = (word ushr 23 and 3) in setOf(1, 3)
                return (!writeback || word ushr 5 and 31 != register) &&
                    (word and 0x00400000 == 0 || (word and 31 != register && word ushr 10 and 31 != register))
            }
            if (word and 0x1c000000 == 0x10000000 || word and 0x0e000000 == 0x0a000000)
                return word and 31 != register
            return false
        }
        val pending = java.util.ArrayDeque<Pair<Int, Int>>(); pending += before to argument
        val visited = hashSetOf<Pair<Int, Int>>(); var root = false
        while (pending.isNotEmpty()) {
            val state = pending.removeLast()
            if (!visited.add(state)) continue
            if (visited.size > 65536) return false
            val (position, register) = state
            if (position == start) {
                if (parameter != register) return false
                root = true; continue
            }
            for (pc in predecessors[position] ?: return false) for (word in words(pc)) {
                if (current && word and 0xffffffe0.toInt() == UltraKernelA64.MRS_SP_EL0 && word and 31 == register) {
                    root = true; continue
                }
                if (callResult != null && register == 0 && UltraKernelA64.isBl(word) &&
                    pc + ((word shl 6) shr 4) == callResult && (callResultSite == null || pc == callResultSite)) {
                    root = true; continue
                }
                val source = if (word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() && word and 31 == register)
                    word ushr 16 and 31 else {
                    if (!preserves(word, register)) return false
                    register
                }
                if (source == 31) return false
                pending += pc to source
            }
        }
        return root
    }

    private data class Value(val base: String? = null, val offset: Long = 0,
        val constant: Long? = null, val source: String? = null, val sourceOffset: Long = 0,
        val width: Int = 0, val bit63: Boolean = false)
    private data class State(val pc: Int, val regs: List<Value?>, val stack: Map<Long, Value>,
        val copied: Boolean, val instruction: Int? = null)
    private class Unknown(val reason: String) : IllegalStateException(reason)

    fun prove(kernel: ByteArray, symbols: UltraKernelSymbols, flagsOffset: Int,
        taskBytes: Int, stackOffset: Int): Evidence {
        val hashes = linkedMapOf<String, String>()
        val calls = linkedSetOf<String>()
        val stores = linkedSetOf<String>()
        val boundaries = linkedSetOf<String>()
        val active = linkedSetOf<Int>()
        var instructions = 0
        fun fail(reason: String): Nothing = throw Unknown(reason)
        fun function(name: String): Int = symbols.offsetsOf(name).singleOrNull()
            ?: fail("FUNCTION_MISSING_OR_AMBIGUOUS:$name")
        fun body(at: Int): Int {
            val end = symbols.nextOffset(at) ?: fail("FUNCTION_END:$at")
            if (at < 0 || at % 4 != 0 || end <= at || end > kernel.size || end - at > 16384)
                fail("FUNCTION_BOUNDS:$at")
            return end
        }
        fun branch(word: Int, pc: Int) = pc + (UltraKernelA64.signExtend((word and 0x03ffffff).toLong(), 26) * 4).toInt()
        fun directCalls(name: String): Set<Int> {
            val begin = function(name); val end = body(begin)
            hashes[name] = UltraKernelSpec.sha256(kernel.copyOfRange(begin, end))
            return (begin until end step 4).mapNotNull { pc ->
                val word = UltraKernelA64.le32Bits(kernel, pc)
                if (UltraKernelA64.isBl(word)) branch(word, pc) else null
            }.toSet()
        }
        // These are the real exported architecture reset paths, not a version-derived offset list.
        val duplicate = function("arch_dup_task_struct")
        val copyName = listOf("copy_thread", "copy_thread_tls").firstOrNull { symbols.offsetsOf(it).size == 1 }
            ?: fail("COPY_THREAD_MISSING")
        val flush = function("flush_thread")
        val execName = listOf("begin_new_exec", "flush_old_exec").firstOrNull {
            symbols.offsetsOf(it).size == 1 && flush in directCalls(it)
        } ?: fail("EXEC_FLUSH_CALL_PATH")
        val dupName = "dup_task_struct".takeIf { symbols.offsetsOf(it).isNotEmpty() }
        if (dupName != null && duplicate !in directCalls(dupName)) fail("ARCH_DUP_CALL_PATH")
        val copyProcess = listOf("copy_process", "copy_process.constprop.0", "copy_process.isra.0")
            .firstOrNull { symbols.offsetsOf(it).size == 1 && (dupName?.let(::function) ?: duplicate) in directCalls(it) &&
                function(copyName) in directCalls(it) } ?: fail("COPY_PROCESS_CALL_PATH")
        fun requireArgument(caller: String, callee: Int, argument: Int, parameter: Int? = null,
            current: Boolean = false, callResult: Int? = null, callResultSite: Int? = null) {
            val begin = function(caller); val end = body(begin)
            val sites = (begin until end step 4).filter { pc ->
                val word = UltraKernelA64.le32Bits(kernel, pc)
                UltraKernelA64.isBl(word) && branch(word, pc) == callee
            }
            if (sites.isEmpty() || sites.any { !argumentFrom(kernel, symbols, begin, end, it,
                    argument, parameter, current, callResult, callResultSite) }) fail("CALL_ARGUMENT:$caller:$argument:$callee")
        }
        val taskParameter = (0 until (UltraKernelBtf.functionParameterCount(kernel, symbols, copyName)
            ?: fail("COPY_THREAD_BTF"))).filter { index ->
            UltraKernelBtf.parameterSignature(kernel, symbols, copyName, index) == "*stask_struct"
        }.singleOrNull() ?: fail("COPY_THREAD_TASK_PARAMETER")
        var freshChildAtEntry = false
        if (dupName != null) {
            requireArgument(copyProcess, function(dupName), 0, current = true)
            requireArgument(dupName, duplicate, 1, parameter = 0)
            requireArgument(copyProcess, function(copyName), taskParameter, callResult = function(dupName))
            calls += "$copyProcess->$dupName->arch_dup_task_struct"
        } else {
            // LTO can inline dup_task_struct. Bind both consumers to the same real allocation site.
            val begin = function(copyProcess); val end = body(begin)
            val allocation = function("kmem_cache_alloc_node")
            val site = (begin until end step 4).filter { pc ->
                val word = UltraKernelA64.le32Bits(kernel, pc)
                UltraKernelA64.isBl(word) && branch(word, pc) == allocation
            }.singleOrNull() ?: fail("INLINE_TASK_ALLOCATION")
            requireArgument(copyProcess, duplicate, 1, current = true)
            requireArgument(copyProcess, duplicate, 0, callResult = allocation, callResultSite = site)
            requireArgument(copyProcess, function(copyName), taskParameter, callResult = allocation, callResultSite = site)
            val duplicateSite = (begin until end step 4).filter { pc ->
                val word = UltraKernelA64.le32Bits(kernel, pc)
                UltraKernelA64.isBl(word) && branch(word, pc) == duplicate
            }.singleOrNull() ?: fail("FRESH_CHILD_CALL_SITE")
            if (site >= duplicateSite) fail("FRESH_CHILD_ORDER")
            val choices = runtimeChoices(kernel, symbols, begin, end, copyProcess)
            val allocated = mutableSetOf(0)
            var checkedAllocation = false
            // The unpublished allocation reaches arch_dup through register moves and its null test only.
            for (pc in site + 4 until duplicateSite step 4) {
                val words = choices[pc].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc)) }
                if (words.size != 1) fail("FRESH_CHILD_VARIANT:$copyProcess:$pc")
                val word = words.single()
                val rd = word and 31
                when {
                    word and 0xffe0ffe0.toInt() == 0xaa0003e0.toInt() -> {
                        val source = word ushr 16 and 31
                        if (rd != 31 && source in allocated) allocated += rd else allocated -= rd
                    }
                    word and 0xffffffe0.toInt() == UltraKernelA64.MRS_SP_EL0 -> allocated -= rd
                    word and 0xff000000.toInt() == 0xb4000000.toInt() -> {
                        val target = pc + (UltraKernelA64.signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) * 4).toInt()
                        if (rd !in allocated || target <= duplicateSite) fail("FRESH_CHILD_NULL_EDGE:$copyProcess:$pc")
                        checkedAllocation = true
                    }
                    word == UltraKernelA64.NOP -> Unit
                    else -> fail("FRESH_CHILD_PREFIX:$copyProcess:$pc")
                }
            }
            if (!checkedAllocation) fail("FRESH_CHILD_NULL_CHECK")
            freshChildAtEntry = true
            calls += "$copyProcess->arch_dup_task_struct:inline-dup:$site"
        }
        calls += "$copyProcess->$copyName"
        calls += "$execName->flush_thread"
        val flagStart = flagsOffset.toLong()
        val globalEnd = maxOf(kernel.size, symbols.memoryOffsetOf("_end") ?: kernel.size).toLong()
        val perCpuStart = symbols.memoryOffsetOf("__per_cpu_start")?.toLong()
        val perCpuEnd = symbols.memoryOffsetOf("__per_cpu_end")?.toLong()
        val initTaskFlags = symbols.memoryOffsetOf("init_task")?.toLong()?.plus(flagStart)
        fun overlaps(offset: Long, width: Int) = offset < flagStart + 8 && offset + width > flagStart
        val objectFields by lazy {
            val names = setOf("task_struct.mm", "task_struct.thread.sve_state", "task_struct.thread.sme_state",
                "task_struct.thread.debug.hbp_break", "task_struct.thread.debug.hbp_watch")
            UltraKernelBtf.abi(kernel, symbols, names, names)?.fields.orEmpty()
        }
        val objectSignatures by lazy { objectFields.keys.associateWith { member ->
            UltraKernelBtf.memberSignature(kernel, symbols, "task_struct", member.removePrefix("task_struct.")) } }
        fun fieldPointer(value: Value?, members: Set<String>, signature: String): Boolean {
            if (value?.width != 8 || value.source !in setOf("task", "source")) return false
            return members.any { member -> objectFields[member]?.let { field ->
                value.sourceOffset >= field.offsetBytes && value.sourceOffset + 8 <= field.offsetBytes + field.widthBytes &&
                    (value.sourceOffset - field.offsetBytes) % 8 == 0L &&
                    objectSignatures[member] == signature
            } == true }
        }
        fun logicalMask(word: Int): Long? {
            val sf = word ushr 31; val n = (word ushr 22) and 1
            val immr = (word ushr 16) and 63; val imms = (word ushr 10) and 63
            val packed = (n shl 6) or (imms.inv() and 63)
            val len = 31 - Integer.numberOfLeadingZeros(packed)
            if (len < 1 || sf == 0 && n != 0) return null
            val levels = (1 shl len) - 1; val s = imms and levels; val r = immr and levels
            if (s == levels) return null
            val bits = 1 shl len
            val ones = if (s == 63) -1L else (1L shl (s + 1)) - 1
            val mask = if (bits == 64) -1L else (1L shl bits) - 1
            val rotated = ((ones ushr r) or (ones shl ((bits - r) and (bits - 1)))) and mask
            var value = 0L
            for (shift in 0 until (if (sf == 0) 32 else 64) step bits) value = value or (rotated shl shift)
            return value
        }
        fun analyze(at: Int, args: List<Value?>, mustCopy: Boolean, depth: Int): Boolean {
            if (depth > 7 || !active.add(at)) fail("RECURSION_OR_DEPTH:$at")
            val end = body(at)
            val name = symbols.nameAt(at) ?: "at-$at"
            hashes[name] = UltraKernelSpec.sha256(kernel.copyOfRange(at, end))
            val choices = runtimeChoices(kernel, symbols, at, end, name)
            val copyTimeSnapshot = mustCopy && at == duplicate && depth == 0 && freshChildAtEntry
            val firstCall = (at until end step 4).firstOrNull { pc ->
                choices[pc].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc)) }.any(UltraKernelA64::isBl)
            }
            val stackName = "stack@$at:$depth"
            val regs = MutableList<Value?>(64) { null }
            args.take(8).forEachIndexed { i, value -> regs[i] = value }
            regs[31] = Value(base = stackName)
            val pending = java.util.ArrayDeque<State>()
            pending += State(at, regs, emptyMap(), false)
            var returns = 0; var success = 0; var allSuccessCopied = true
            val dedup = hashSetOf<State>()
            while (pending.isNotEmpty()) {
                val state = pending.removeLast()
                if (++instructions > 40000 || dedup.size > 4096) fail("PATH_BUDGET:$name")
                if (state.pc !in at until end || state.pc % 4 != 0) fail("CONTROL_RANGE:$name:${state.pc}")
                if (!dedup.add(state)) continue
                val pc = state.pc
                val variants = choices[pc].orEmpty()
                if (state.instruction == null && variants.size > 1) {
                    variants.forEach { pending += state.copy(instruction = it) }
                    continue
                }
                val word = state.instruction ?: variants.singleOrNull() ?: UltraKernelA64.le32Bits(kernel, pc)
                val values = state.regs.toMutableList(); val stack = state.stack.toMutableMap()
                var copied = state.copied
                val rd = word and 31; val rn = (word ushr 5) and 31; val rm = (word ushr 16) and 31
                fun register(index: Int, zero: Boolean = true) =
                    if (index == 31 && zero) Value(constant = 0) else values[index]
                fun push(target: Int) { pending += State(target, values.toList(), stack.toMap(), copied) }
                fun load(address: Value?, width: Int): Value? {
                    if (address?.base == stackName) return stack[address.offset]?.takeIf { it.width >= width }
                    if (address?.base in setOf("source", "task")) {
                        if (address?.offset == stackOffset.toLong() && width == 8)
                            return Value(base = "kernel-stack:" + address.base)
                        val value = Value(source = address?.base, sourceOffset = address?.offset ?: 0, width = width,
                            bit63 = address?.offset == flagStart && width == 8)
                        if (fieldPointer(value, setOf("task_struct.mm"), "*smm_struct"))
                            return value.copy(base = "object:mm_struct")
                        return value
                    }
                    return null
                }
                fun store(address: Value?, value: Value?, width: Int) {
                    if (address?.base == stackName) {
                        stack.keys.filter { slot -> slot < address.offset + width &&
                            slot + (stack[slot]?.width ?: 0) > address.offset }.forEach(stack::remove)
                        if (value != null) stack[address.offset] = value.copy(width = width)
                        return
                    }
                    if (copyTimeSnapshot && !copied) fail("PRECOPY_EXTERNAL_WRITE:$name:$pc")
                    if (address == null || address.base == null || address.base == "tainted")
                        fail("UNKNOWN_TASK_WRITE:$name:$pc")
                    if (address.base.startsWith("kernel-stack:") || address.base.startsWith("stack@")) return
                    if (address.base == "object:mm_struct") {
                        val bytes = UltraKernelBtf.abi(kernel, symbols, setOf("mm_struct.__size"))
                            ?.fields?.get("mm_struct.__size")?.widthBytes ?: fail("NATIVE_OBJECT_BTF:$name:$pc")
                        if (address.offset < 0 || address.offset + width > bytes) fail("NATIVE_OBJECT_RANGE:$name:$pc")
                        boundaries += "$name:$pc:typed-separate-object:mm_struct:$bytes"
                        return
                    }
                    if (address.base == "percpu") {
                        if (perCpuStart == null || perCpuEnd == null || address.offset < perCpuStart ||
                            address.offset + width > perCpuEnd) fail("PERCPU_WRITE_RANGE:$name:$pc")
                        return
                    }
                    if (address.base == "global") {
                        if (address.offset < 0 || address.offset + width > globalEnd) fail("GLOBAL_WRITE_RANGE:$name:$pc")
                        if (initTaskFlags != null && address.offset < initTaskFlags + 8 && address.offset + width > initTaskFlags + 7 &&
                            !(value != null && value.source == "global" && value.sourceOffset == address.offset &&
                                value.width >= width && value.bit63)) fail("INIT_TASK_FLAG63_WRITE:$name:$pc")
                        return
                    }
                    if (address.base !in setOf("task", "source")) fail("UNKNOWN_TASK_WRITE:$name:$pc")
                    if (address.offset < 0 || address.offset + width > taskBytes) fail("TASK_WRITE_RANGE:$name:$pc")
                    if (!overlaps(address.offset, width)) return
                    if (address.offset + width <= flagStart + 7) return // A low-byte write leaves bit63 intact.
                    val same = value != null && value.source == address.base && value.sourceOffset == address.offset && value.width >= width
                    val clone = address.base == "task" && value?.source == "source" &&
                        value.sourceOffset == address.offset && value.width >= width &&
                        address.offset <= flagStart && address.offset + width >= flagStart + 8
                    if (!same && !clone && !(address.offset == flagStart && width == 8 &&
                        value?.bit63 == true && value.source == address.base)) fail("FLAG63_WRITE:$name:$pc")
                    if (clone) copied = true
                    stores += "$name:$pc:${address.base}+${address.offset}:$width"
                }
                fun address(base: Value?, delta: Long): Value? = base?.let {
                    if (it.base != null) it.copy(offset = it.offset + delta) else null
                }
                when {
                    word and 0xfffffc1f.toInt() == 0xd65f0000.toInt() -> {
                        returns++
                        val result = values[0]?.constant
                        if (result == 0L || !mustCopy) {
                            if (mustCopy && !copied) fail("CLONE_FLAGS_NOT_COPIED:$name:$pc")
                            success++
                            if (!copied) allSuccessCopied = false
                        } else if (result == null) fail("UNKNOWN_CLONE_RETURN:$name:$pc")
                        continue
                    }
                    word and 0xffffffe0.toInt() == UltraKernelA64.MRS_SP_EL0 -> values[rd] = Value(base = "source")
                    word and 0xffffffe0.toInt() in setOf(0xd538d080.toInt(), 0xd53cd040.toInt()) ->
                        values[rd] = Value(base = "percpu-offset")
                    UltraKernelA64.isAdr(word) -> values[rd] = UltraKernelA64.decodeAdr(word, pc.toLong())?.let {
                        if (it in 0 until globalEnd) Value(base = "global", offset = it) else null }
                    UltraKernelA64.isAdrp(word) -> {
                        val packed = (((word ushr 5) and 0x7ffff).toLong() shl 2) or ((word ushr 29) and 3).toLong()
                        val page = (pc.toLong() and -4096L) + (UltraKernelA64.signExtend(packed, 21) shl 12)
                        values[rd] = if (page >= -4095 && page < globalEnd) Value(base = "global", offset = page) else null
                    }
                    word and 0xfff00000.toInt() == 0xd5300000.toInt() -> values[rd] = null
                    word and 0xfff00000.toInt() == 0xd5100000.toInt() -> Unit
                    word and 0xff800000.toInt() in setOf(0xd2800000.toInt(), 0x52800000) ->
                        values[rd] = Value(constant = ((word ushr 5) and 65535).toLong() shl (((word ushr 21) and 3) * 16))
                    word and 0xff800000.toInt() in setOf(0x92800000.toInt(), 0x12800000) -> {
                        val mask = if (word < 0) -1L else 0xffffffffL
                        values[rd] = Value(constant = (((word ushr 5) and 65535).toLong() shl (((word ushr 21) and 3) * 16)).inv() and mask)
                    }
                    word and 0xff800000.toInt() in setOf(0xf2800000.toInt(), 0x72800000) -> {
                        val shift = ((word ushr 21) and 3) * 16
                        values[rd] = values[rd]?.constant?.let { Value(constant =
                            (it and (65535L shl shift).inv()) or (((word ushr 5) and 65535).toLong() shl shift)) }
                    }
                    word and 0x7f2003e0 == 0x2a0003e0 -> values[rd] = register(rm)?.let {
                        if (word < 0) it else it.constant?.let { constant -> Value(constant = constant and 0xffffffffL) }
                    }
                    word and 0x1f000000 == 0x11000000 -> {
                        var delta = ((word ushr 10) and 4095).toLong() shl (if (word and (1 shl 22) != 0) 12 else 0)
                        if (word and (1 shl 30) != 0) delta = -delta
                        val value = register(rn, false)
                        if (rd != 31 || word and (1 shl 29) == 0) values[rd] = value?.constant?.let {
                            Value(constant = if (word < 0) it + delta else (it + delta) and 0xffffffffL) }
                            ?: if (word < 0) address(value, delta) else null
                    }
                    word and 0x1f200000 == 0x0b000000 -> {
                        val left = register(rn); val right = register(rm)?.constant
                        val shift = (word ushr 22) and 3; val amount = (word ushr 10) and 63
                        val delta = right?.let { when (shift) { 0 -> it shl amount; 1 -> it ushr amount
                            2 -> it shr amount; else -> null } }?.let { if (word and (1 shl 30) != 0) -it else it }
                        if (rd != 31) values[rd] = if (delta == null) null else left?.constant?.let {
                            Value(constant = if (word < 0) it + delta else (it + delta) and 0xffffffffL)
                        } ?: if (word < 0) address(left, delta) else null
                    }
                    word and 0x1f000000 == 0x0a000000 -> {
                        val first = register(rn); val second = register(rm)
                        val shift = (word ushr 22) and 3; val amount = (word ushr 10) and 63
                        val raw = second?.constant?.let { when (shift) { 0 -> it shl amount; 1 -> it ushr amount
                            2 -> it shr amount; else -> java.lang.Long.rotateRight(it, amount) } }
                        val mask = raw?.let { if (word and (1 shl 21) != 0) it.inv() else it }
                        val op = (word ushr 29) and 3
                        if (rd != 31) values[rd] = if (mask == null) null else first?.constant?.let {
                            val value = when (op) { 0, 3 -> it and mask; 1 -> it or mask; else -> it xor mask }
                            Value(constant = if (word < 0) value else value and 0xffffffffL)
                        } ?: first?.takeIf { it.bit63 && word < 0 &&
                            ((op in setOf(0, 3) && mask < 0) || (op in setOf(1, 2) && mask >= 0)) }
                    }
                    word and 0x1fe00000 == 0x1a800000 -> {
                        if (rd != 31) {
                            if (word and 0x40000c00 == 0 && word < 0) {
                                val yes = register(rn); val no = register(rm)
                                values[rd] = yes
                                if (yes != no) { push(pc + 4); values[rd] = no }
                            } else values[rd] = null
                        }
                    }
                    word and 0x1f800000 == 0x13000000 -> {
                        if (rd != 31) values[rd] = if (word < 0 && (word ushr 16) and 63 == 0 &&
                            (word ushr 10) and 63 == 63 && (word ushr 29) and 3 == 2) register(rn) else null
                    }
                    word and 0x1f800000 == 0x12000000 -> {
                        val value = register(rn); val mask = logicalMask(word) ?: fail("LOGICAL_MASK:$name:$pc")
                        val op = (word ushr 29) and 3
                        values[rd] = value?.constant?.let { Value(constant = when (op) {
                            0, 3 -> it and mask; 1 -> it or mask; else -> it xor mask
                        }) } ?: value?.takeIf { it.bit63 && word < 0 &&
                            ((op in setOf(0, 3) && mask < 0) || (op in setOf(1, 2) && mask >= 0)) }
                        if (op == 3 && rd == 31) values[31] = state.regs[31]
                    }
                    word and 0x3fff7c00 == 0x085f7c00 -> {
                        values[rd] = load(register(rn, false), 1 shl (word ushr 30))
                    }
                    word and 0x3fe07c00 == 0x08007c00 -> {
                        store(register(rn, false), register(rd), 1 shl (word ushr 30))
                        val status = (word ushr 16) and 31
                        if (status != 31) values[status] = null
                    }
                    word and 0x3ffffc00 == 0x08dffc00 -> {
                        values[rd] = load(register(rn, false), 1 shl (word ushr 30))
                    }
                    word and 0x3ffffc00 == 0x089ffc00 ->
                        store(register(rn, false), register(rd), 1 shl (word ushr 30))
                    word and 0x3fa07c00 == 0x08a07c00 -> {
                        val width = 1 shl (word ushr 30); val old = load(register(rn, false), width)
                        store(register(rn, false), register(rd), width)
                        val compare = (word ushr 16) and 31
                        if (compare != 31) values[compare] = old
                    }
                    word and 0x3b200c00 == 0x38200000 -> {
                        val width = 1 shl (word ushr 30); val old = load(register(rn, false), width)
                        val input = register(rm); val operation = (word ushr 12) and 15
                        if (operation > 8) fail("ATOMIC_OPERATION:$name:$pc")
                        val mask = input?.constant
                        val value = if (operation == 8) input else old?.takeIf { it.bit63 && width == 8 && mask != null &&
                            ((operation in setOf(1, 2, 3) && mask >= 0) || (operation == 0 && mask == 0L)) }
                        store(register(rn, false), value, width)
                        if (rd != 31) values[rd] = old
                    }
                    word and 0x3b000000 == 0x39000000 -> {
                        val simd = word and (1 shl 26) != 0
                        val width = if (simd) 1 shl ((word ushr 30) + (if (word and (1 shl 23) != 0) 4 else 0)) else 1 shl (word ushr 30)
                        if (width !in setOf(1, 2, 4, 8, 16)) fail("MEMORY_WIDTH:$name:$pc")
                        val delta = ((word ushr 10) and 4095).toLong() * width
                        val location = address(register(rn, false), delta)
                        val destination = rd + if (simd) 32 else 0
                        if (simd) {
                            if (word and (1 shl 22) != 0) values[destination] = load(location, width)
                            else store(location, values[destination], width)
                        } else if ((word ushr 22) and 3 == 0) store(location, register(rd), width)
                        else if ((word ushr 22) and 3 == 1) values[rd] = load(location, width)
                        else values[rd] = null
                    }
                    word and 0x3b200c00 == 0x38200800 -> {
                        val simd = word and (1 shl 26) != 0
                        val width = if (simd) 1 shl ((word ushr 30) + (if (word and (1 shl 23) != 0) 4 else 0)) else 1 shl (word ushr 30)
                        if (width !in setOf(1, 2, 4, 8, 16)) fail("MEMORY_WIDTH:$name:$pc")
                        val option = word ushr 13 and 7
                        val shift = if (word and (1 shl 12) != 0) Integer.numberOfTrailingZeros(width) else 0
                        val base = register(rn, false); val index = register(rm)
                        val delta = index?.constant?.let { when (option) {
                            2 -> it and 0xffffffffL; 3, 7 -> it; 6 -> it.toInt().toLong(); else -> null
                        } }?.let { it shl shift }
                        val location = if (delta != null) address(base, delta)
                            else if (option == 3 && shift == 0 && base?.base == "percpu-offset" && index?.base == "global")
                                Value(base = "percpu", offset = base.offset + index.offset)
                            else null
                        val destination = rd + if (simd) 32 else 0
                        if (simd) {
                            if (word and (1 shl 22) != 0) values[destination] = load(location, width)
                            else store(location, values[destination], width)
                        } else if ((word ushr 22) and 3 == 0) store(location, register(rd), width)
                        else if ((word ushr 22) and 3 == 1) values[rd] = load(location, width)
                        else values[rd] = null
                    }
                    word and 0x3b200000 == 0x38000000 -> {
                        val simd = word and (1 shl 26) != 0
                        val width = if (simd) 1 shl ((word ushr 30) + (if (word and (1 shl 23) != 0) 4 else 0)) else 1 shl (word ushr 30)
                        if (width !in setOf(1, 2, 4, 8, 16)) fail("MEMORY_WIDTH:$name:$pc")
                        val delta = UltraKernelA64.signExtend(((word ushr 12) and 511).toLong(), 9)
                        val mode = (word ushr 10) and 3
                        val base = register(rn, false)
                        val location = address(base, if (mode == 1) 0 else delta)
                        val destination = rd + if (simd) 32 else 0
                        if (simd) {
                            if (word and (1 shl 22) != 0) values[destination] = load(location, width)
                            else store(location, values[destination], width)
                        } else if ((word ushr 22) and 3 == 0) store(location, register(rd), width)
                        else if ((word ushr 22) and 3 == 1) values[rd] = load(location, width)
                        else values[rd] = null
                        if (mode in setOf(1, 3)) values[rn] = address(base, delta)
                    }
                    word and 0x3a000000 == 0x28000000 -> {
                        val simd = word and (1 shl 26) != 0
                        val width = if (simd) 4 shl (word ushr 30) else if (word < 0) 8 else 4
                        if (width !in setOf(4, 8, 16)) fail("PAIR_WIDTH:$name:$pc")
                        val delta = UltraKernelA64.signExtend(((word ushr 15) and 127).toLong(), 7) * width
                        val mode = (word ushr 23) and 3; val other = (word ushr 10) and 31
                        val base = register(rn, false); val location = address(base, if (mode == 1) 0 else delta)
                        val next = address(location, width.toLong())
                        val firstRegister = rd + if (simd) 32 else 0
                        val nextRegister = other + if (simd) 32 else 0
                        if (word and (1 shl 22) != 0) { values[firstRegister] = load(location, width); values[nextRegister] = load(next, width) }
                        else { store(location, if (simd) values[firstRegister] else register(rd), width)
                            store(next, if (simd) values[nextRegister] else register(other), width) }
                        if (mode in setOf(1, 3)) values[rn] = address(base, delta)
                    }
                    UltraKernelA64.isBl(word) -> {
                        val target = branch(word, pc); val callee = symbols.nameAt(target) ?: fail("CALL_NAME:$name:$pc")
                        calls += "$name:$pc->$callee"
                        val arguments = values.take(8)
                        if (callee in setOf("memcpy", "__memcpy", "memmove", "__memmove")) {
                            val dest = values[0]; val source = values[1]; val bytes = values[2]?.constant
                                ?: fail("COPY_SIZE:$name:$pc")
                            if (bytes < 0 || bytes > Int.MAX_VALUE) fail("COPY_SIZE_RANGE:$name:$pc")
                            if (dest == null || dest.base == null) fail("UNKNOWN_COPY_WRITE:$name:$pc")
                            if (copyTimeSnapshot && !copied && (dest.base != "task" || dest.offset != 0L ||
                                source?.base != "source" || source.offset != 0L || bytes != taskBytes.toLong()))
                                fail("PRECOPY_FULL_TASK_COPY:$name:$pc")
                            if (dest.base == stackName) stack.keys.filter { slot -> slot < dest.offset + bytes &&
                                slot + (stack[slot]?.width ?: 0) > dest.offset }.forEach(stack::remove)
                            else if (dest.base !in setOf("task", "source", "global") && !dest.base.startsWith("kernel-stack:") && !dest.base.startsWith("stack@"))
                                fail("UNKNOWN_COPY_WRITE:$name:$pc")
                            if (dest.base in setOf("task", "source") && (dest.offset < 0 || dest.offset + bytes > taskBytes))
                                fail("COPY_TASK_RANGE:$name:$pc")
                            if (dest.base == "global" && (dest.offset < 0 || dest.offset + bytes > globalEnd ||
                                initTaskFlags != null && dest.offset < initTaskFlags + 8 && dest.offset + bytes > initTaskFlags))
                                fail("COPY_GLOBAL_FLAGS_OR_RANGE:$name:$pc")
                            if (dest.base in setOf("task", "source") && overlaps(dest.offset, bytes.toInt())) {
                                if (dest.base != "task" || source?.base != "source" || source.offset != dest.offset ||
                                    dest.offset > flagStart || dest.offset + bytes < flagStart + 8 || bytes > taskBytes)
                                    fail("COPY_FLAGS_SOURCE:$name:$pc")
                                copied = true; stores += "$name:$pc:memcpy:$bytes"
                            }
                        } else if (callee in setOf("memset", "__memset", "get_random_bytes")) {
                            val random = callee == "get_random_bytes"
                            if (random && UltraKernelBtf.functionSignature(kernel, symbols, callee) !in
                                setOf("fv(*v;i8:64;)", "fv(*v;i4:16777248;)")) fail("RANDOM_API_SIGNATURE:$name:$pc")
                            val bytes = values[if (random) 1 else 2]?.constant ?: fail("CLEAR_SIZE:$name:$pc")
                            val dest = values[0]
                            if (bytes < 0 || bytes > Int.MAX_VALUE) fail("CLEAR_SIZE_RANGE:$name:$pc")
                            if (dest == null || dest.base == null) fail("UNKNOWN_CLEAR_WRITE:$name:$pc")
                            if (dest.base == stackName) stack.keys.filter { slot -> slot < dest.offset + bytes &&
                                slot + (stack[slot]?.width ?: 0) > dest.offset }.forEach(stack::remove)
                            else if (dest.base !in setOf("task", "source", "global") && !dest.base.startsWith("kernel-stack:") && !dest.base.startsWith("stack@"))
                                fail("UNKNOWN_CLEAR_WRITE:$name:$pc")
                            if (dest.base in setOf("task", "source") && (dest.offset < 0 || dest.offset + bytes > taskBytes))
                                fail("CLEAR_TASK_RANGE:$name:$pc")
                            if (dest.base == "global" && (dest.offset < 0 || dest.offset + bytes > globalEnd ||
                                initTaskFlags != null && dest.offset < initTaskFlags + 8 && dest.offset + bytes > initTaskFlags))
                                fail("CLEAR_GLOBAL_FLAGS_OR_RANGE:$name:$pc")
                            if (dest.base in setOf("task", "source") && overlaps(dest.offset, bytes.toInt())) fail("CLEAR_FLAGS:$name:$pc")
                            if (random) {
                                hashes[callee] = UltraKernelSpec.sha256(kernel.copyOfRange(target, body(target)))
                                boundaries += "$name:$pc->$callee:bounded-output:${dest.base}+${dest.offset}:$bytes"
                            }
                        } else if (callee in setOf("ptrauth_set_enabled_keys", "arch_prctl_spec_ctrl_set",
                            "relax_compatible_cpus_allowed_ptr", "force_compatible_cpus_allowed_ptr")) {
                            val controls = callee in setOf("ptrauth_set_enabled_keys", "arch_prctl_spec_ctrl_set")
                            val expected = if (controls) "fi4:16777248(*stask_struct;i8:64;i8:64;)" else "fv(*stask_struct;)"
                            val task = values[0]
                            if (copyTimeSnapshot && !copied || task?.base !in setOf("task", "source") || task?.offset != 0L ||
                                UltraKernelBtf.functionSignature(kernel, symbols, callee) != expected ||
                                controls && (1..2).any { values[it]?.constant?.let { mask -> mask < 0 || mask > 0xffffffffL } != false })
                                fail("NATIVE_TASK_API_ARGUMENT:$name:$pc")
                            hashes[callee] = UltraKernelSpec.sha256(kernel.copyOfRange(target, body(target)))
                            boundaries += "$name:$pc->$callee:native-kernelsu-bit63-preserving-task-api:$expected"
                        } else if (callee in setOf("kfree", "unregister_hw_breakpoint")) {
                            val event = callee == "unregister_hw_breakpoint"
                            val expected = if (event) "fv(*sperf_event;)" else "fv(*v;)"
                            val members = if (event) setOf("task_struct.thread.debug.hbp_break", "task_struct.thread.debug.hbp_watch")
                                else setOf("task_struct.thread.sve_state", "task_struct.thread.sme_state")
                            if (UltraKernelBtf.functionSignature(kernel, symbols, callee) != expected ||
                                values[0]?.constant != 0L && !fieldPointer(values[0], members,
                                    if (event) "a16:*sperf_event" else "*v")) fail("NATIVE_OBJECT_ARGUMENT:$name:$pc")
                            hashes[callee] = UltraKernelSpec.sha256(kernel.copyOfRange(target, body(target)))
                            boundaries += "$name:$pc->$callee:typed-separate-object:$expected"
                        } else if (callee in setOf("_find_next_bit", "local_bh_enable", "preempt_schedule", "this_cpu_has_cap",
                            "mte_thread_init_user")) {
                            val expected = when (callee) {
                                "_find_next_bit" -> "fi8:64(*i8:64;i8:64;i8:64;)"
                                "this_cpu_has_cap" -> "fi1:67108872(i4:32;)"
                                else -> "fv()"
                            }
                            if (copyTimeSnapshot && !copied ||
                                UltraKernelBtf.functionSignature(kernel, symbols, callee) != expected)
                                fail("NATIVE_API_SIGNATURE:$name:$pc")
                            hashes[callee] = UltraKernelSpec.sha256(kernel.copyOfRange(target, body(target)))
                            // Native queries, scheduling and MTE setup preserve the KernelSU task bit.
                            boundaries += "$name:$pc->$callee:native-kernelsu-bit63-preserving-api:$expected"
                        } else if (copyTimeSnapshot && !copied && pc == firstCall) {
                            if (UltraKernelBtf.functionSignature(kernel, symbols, callee) != "fv()" ||
                                UltraKernelBtf.functionParameterCount(kernel, symbols, callee) != 0)
                                fail("PRECOPY_CALL_SIGNATURE:$name:$pc")
                            val next = choices[pc + 4].orEmpty().ifEmpty { setOf(UltraKernelA64.le32Bits(kernel, pc + 4)) }
                            if (next.any { instruction ->
                                val source = instruction ushr 16 and 31
                                instruction and 0xffe0ffff.toInt() != 0xaa0003e0.toInt() ||
                                    source !in 19..28 || values[source]?.base != "task" || values[source]?.offset != 0L
                            } || (19..28).none { values[it]?.base == "source" && values[it]?.offset == 0L })
                                fail("PRECOPY_CALL_RESULT_OR_POINTER:$name:$pc")
                            // C v() has no child parameter; its ignored result is overwritten before any use.
                            // The source snapshot starts at the full copy, not before this preparation call.
                            hashes[callee] = UltraKernelSpec.sha256(kernel.copyOfRange(target, body(target)))
                            boundaries += "$name:$pc->$callee:unpublished-child:void0:copy-time-source-snapshot"
                        } else {
                            copied = analyze(target, arguments, false, depth + 1) || copied
                            if (arguments.any { it?.base == stackName }) stack.clear()
                        }
                        for (index in 0..18) values[index] = null
                        for (index in 32..63) values[index] = null
                    }
                    word and 0xfc000000.toInt() == 0x14000000 -> { push(branch(word, pc)); continue }
                    word and 0x7e000000 == 0x34000000 -> {
                        val value = register(rd)?.constant
                        val taken = value?.let { (if (word < 0) it else it and 0xffffffffL) == 0L }
                            ?.let { if (word and (1 shl 24) == 0) it else !it }
                        if (taken != false) push(pc + (UltraKernelA64.signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) * 4).toInt())
                        if (taken == true) continue
                    }
                    word and 0xff000010.toInt() == 0x54000000 ->
                        push(pc + (UltraKernelA64.signExtend(((word ushr 5) and 0x7ffff).toLong(), 19) * 4).toInt())
                    word and 0x7e000000 == 0x36000000 ->
                        push(pc + (UltraKernelA64.signExtend(((word ushr 5) and 0x3fff).toLong(), 14) * 4).toInt())
                    word and 0xfe000000.toInt() == 0xd6000000.toInt() -> fail("INDIRECT_CONTROL:$name:$pc")
                    word == 0xd4210000.toInt() -> {
                        // Keep the returning WARN edge; a fatal BUG adds no successful return.
                        boundaries += "$name:$pc:warning-exception-preserves-task-bit"
                    }
                    word == UltraKernelA64.NOP || word and 0xfffff01f.toInt() == 0xd503201f.toInt() ||
                        word and 0xfffff09f.toInt() == 0xd503309f.toInt() -> Unit
                    else -> fail("UNDECODED_INSTRUCTION:$name:$pc:${word.toUInt().toString(16)}")
                }
                push(pc + 4)
            }
            active.remove(at)
            if (returns == 0 || success == 0) fail("NO_SUCCESS_RETURN:$name")
            return allSuccessCopied
        }
        val dst = Value(base = "task"); val src = Value(base = "source")
        analyze(duplicate, listOf(dst, src), true, 0)
        analyze(function(copyName), List(8) { if (it == taskParameter) dst else null }, false, 0)
        analyze(flush, emptyList(), false, 0)
        symbols.offsetsOf("arch_setup_new_exec").singleOrNull()?.let { analyze(it, emptyList(), false, 0) }
        return Evidence(hashes.toMap(), calls.toList(), stores.toList(), boundaries.toList())
    }
}
