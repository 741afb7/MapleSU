package dev.maplesu.manager.ultrakernel

/**
 * A stable, machine-readable failure of the on-device spec *generator*.
 *
 * Generation is the one step of the Ultra pipeline that can legitimately refuse for reasons the
 * catalogue never sees: a symbol that no longer exists, a `kallsyms` layout this build cannot walk,
 * a `task_struct` anchor whose shape changed. Every one of those has to name itself, because the
 * alternative - a partially rendered plan - would be a boot image that patches the wrong bytes.
 *
 * [code] is the value the UI branches on and the receipt records; [detail] is diagnostic only and
 * never contains a credential.
 */
internal class UltraKernelGenerateException(
    val code: String,
    val detail: String = "",
) : IllegalStateException(if (detail.isEmpty()) code else "$code:$detail")

/** Every code [UltraKernelSpecGenerator] and its dependencies may raise. */
internal object UltraKernelGenerateCodes {
    /** The kernel's `kallsyms` table could not be decoded (see the detail for the anchor). */
    const val KALLSYMS = "ULTRA_KERNEL_GENERATE_KALLSYMS"

    /** The embedded recipe is missing, malformed, or of an unsupported schema. */
    const val RECIPE = "ULTRA_KERNEL_GENERATE_RECIPE"

    /** A record's anchor symbol (`die`, `panic`, ...) is absent from this kernel. */
    const val SITE_SYMBOL_MISSING = "ULTRA_KERNEL_GENERATE_SITE_SYMBOL_MISSING"

    /** A relocation target (`module_alloc`, `kern_path`, ...) is absent from this kernel. */
    const val TARGET_SYMBOL_MISSING = "ULTRA_KERNEL_GENERATE_TARGET_SYMBOL_MISSING"

    /** A record resolved outside the image, or its template did not fit. */
    const val SITE = "ULTRA_KERNEL_GENERATE_SITE"

    /** A declared cave extends beyond the next occupied symbol and has no verified fixed slot. */
    const val CAVE_TOO_SMALL = "ULTRA_KERNEL_GENERATE_CAVE_TOO_SMALL"

    /** No proven executable padding can host an entire cave group. */
    const val CAVE_UNAVAILABLE = "ULTRA_KERNEL_GENERATE_CAVE_UNAVAILABLE"

    /**
     * A code body could not be assembled for this kernel, or outgrew the cave its record reserves.
     *
     * This is the failure mode that replaced the old "these are the reference build's bytes" check:
     * the body is now emitted here, so what can go wrong is a symbol it calls being absent, a
     * displacement that does not encode, or a body that no longer fits.
     */
    const val BODY = "ULTRA_KERNEL_GENERATE_BODY"

    /**
     * The records could be rendered and relocated, but too few of the recipe's sites still carry
     * the reference build's pristine bytes (`3/18` on 6.1.25).
     *
     * This is a *refusal*, not a warning. Addresses can be recomputed for any kernel, but the nine
     * shellcode templates are a compiled artifact of one build: on a kernel whose site bytes differ,
     * the bytes that would be written were never compiled against it and can panic the device. A
     * half-covered plan is exactly the "half-finished candidate" this generator exists to avoid, so
     * it fails closed here instead of producing an image that boots into a different kernel.
     */
    const val SITE_COVERAGE = "ULTRA_KERNEL_GENERATE_SITE_COVERAGE"

    /** `task_struct` offsets needed by the recipe could not be read from the anchor symbol. */
    const val STRUCT_OFFSET = "ULTRA_KERNEL_GENERATE_STRUCT_OFFSET"

    /** The credential is unusable (wrong length or alphabet) - `UltraKernelCredential` refuses. */
    const val CREDENTIAL_INVALID = "ULTRA_KERNEL_GENERATE_CREDENTIAL_INVALID"

    /** Applying the rendered records to the base kernel did not reproduce the derived digest. */
    const val APPLY = "ULTRA_KERNEL_GENERATE_APPLY"

    /**
     * An address inside a rendered blob was not explained by a relocation, so it is frozen at the
     * reference kernel's layout. This is a hard failure: on the reference kernel such a byte is
     * *correct*, which is exactly why only this check can catch it.
     */
    const val RESIDUE = "ULTRA_KERNEL_GENERATE_RESIDUE"

    /** The base image is not something a plan can be generated for. */
    const val BASE = "ULTRA_KERNEL_GENERATE_BASE"

    /**
     * Legacy series-selection error retained for existing callers. The target generator resolves
     * concrete symbols, fields and instructions instead of using a series prerequisite.
     */
    const val SERIES_MISMATCH = "ULTRA_KERNEL_SERIES_MISMATCH"
}

