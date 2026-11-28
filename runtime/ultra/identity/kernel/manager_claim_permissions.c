// SPDX-License-Identifier: GPL-3.0-only
#include "manager_claim_bootstrap.h"

typedef int (*msu_permissions)(unsigned long, int);

static unsigned long msu_page_start(msu_loader_u32 offset, unsigned long mask)
{
    return offset & ~mask;
}

/* After materialization validates the immutable section table. Every resident
 * entry is checked; failures return to bootstrap cleanup. Anchor v4 addresses a
 * 56-byte deployment binding whose resident object is its first field, while
 * v2/v3 address the resident binding directly. */
int msu_manager_claim_permissions(const struct msu_manager_claim_anchor *a,
    const struct msu_loader_header *h, void *memory, int pages)
{
    const struct msu_loader_section *s;
    const unsigned char *image = (const unsigned char *)a - a->self_offset;
    const unsigned long page_mask = (1UL << a->page_shift) - 1;
    const unsigned long bytes = (unsigned long)pages << a->page_shift;
    msu_permissions rw = (msu_permissions)(image + a->set_rw_offset);
    msu_permissions ro = (msu_permissions)(image + a->set_ro_offset);
    msu_permissions executable = (msu_permissions)(image + a->set_x_offset);
    unsigned int i, j;
    int error;
    if (!msu_loader_header_bytes(h)) return -22;
    s = (const void *)((const msu_loader_u8 *)h + msu_loader_header_bytes(h));
    {
        const struct msu_resident_binding *resident;
        if (a->version == MSU_CLAIM_DEPLOYMENT_ANCHOR_VERSION ||
            a->version == MSU_CLAIM_SOURCE_ANCHOR_VERSION) {
            const struct msu_resident_deployment_binding *binding =
                (const void *)(image + a->reserved[0]);
            resident = &binding->resident;
        } else {
            resident = (const void *)(image + a->reserved[0]);
        }
        const msu_loader_u32 entries[] = { resident->init_offset, resident->discard_offset, resident->dispatch_offset };
        for (j = 0; j < sizeof(entries) / sizeof(entries[0]); ++j) {
            int found = 0;
            for (i = 0; i < h->section_count; ++i)
                if ((s[i].flags & MSU_LOADER_EXEC) && s[i].file_bytes >= 4 && entries[j] >= s[i].offset &&
                    entries[j] - s[i].offset <= s[i].file_bytes - 4) found = 1;
            if (!found) return -22;
        }
    }
    /* Sections can share read-only pages, but writable and executable sections
     * must never share a page, including otherwise disjoint byte ranges. */
    for (i = 0; i < h->section_count; ++i) {
        if (!s[i].memory_bytes) continue;
        for (j = 0; j < i; ++j) {
            if (s[j].memory_bytes &&
                ((s[i].flags | s[j].flags) & (MSU_LOADER_WRITE | MSU_LOADER_EXEC)) ==
                    (MSU_LOADER_WRITE | MSU_LOADER_EXEC) &&
                msu_page_start(s[i].offset, page_mask) <=
                    msu_page_start(s[j].offset + s[j].memory_bytes - 1, page_mask) &&
                msu_page_start(s[j].offset, page_mask) <=
                    msu_page_start(s[i].offset + s[i].memory_bytes - 1, page_mask))
                return -22;
        }
    }
    error = ro((unsigned long)memory, pages);
    if (error) return error;
    for (i = 0; i < h->section_count; ++i) {
        unsigned long begin, end;
        if (!s[i].memory_bytes || !(s[i].flags & MSU_LOADER_WRITE)) continue;
        begin = msu_page_start(s[i].offset, page_mask);
        end = (s[i].offset + s[i].memory_bytes + page_mask) & ~page_mask;
        error = rw((unsigned long)memory + begin, (end - begin) >> a->page_shift);
        if (error) return error;
    }
    /* arm64 rodata_full may update linear aliases for the whole vmalloc area
     * on a subrange RW call. Finish every data RW call before restoring code
     * RO and then X, so executable pages retain no writable linear alias. */
    for (i = 0; i < h->section_count; ++i) {
        unsigned long begin, end;
        int count;
        if (!s[i].memory_bytes || !(s[i].flags & MSU_LOADER_EXEC)) continue;
        begin = msu_page_start(s[i].offset, page_mask);
        end = (s[i].offset + s[i].memory_bytes + page_mask) & ~page_mask;
        count = (end - begin) >> a->page_shift;
        error = ro((unsigned long)memory + begin, count);
        if (error) return error;
        error = executable((unsigned long)memory + begin, count);
        if (error) return error;
    }
    /* Permission changes must be visible at every translation level before
     * entering newly executable staging pages, for each native page size. */
    {
        unsigned long operand = ((unsigned long)memory >> 12) & ((1UL << 44) - 1);
        unsigned long remaining = pages;
        unsigned long stride = 1UL << (a->page_shift - 12);
        __asm__ volatile("dsb ishst" ::: "memory");
        for (; remaining; --remaining, operand += stride)
            __asm__ volatile("tlbi vaae1is, %0" :: "r"(operand) : "memory");
        __asm__ volatile("dsb ish\nisb" ::: "memory");
    }
    ((void (*)(unsigned long, unsigned long))(image + a->cache_offset))(
        (unsigned long)memory, (unsigned long)memory + bytes);
    __asm__ volatile("dsb ish\nisb" ::: "memory");
    return 0;
}
