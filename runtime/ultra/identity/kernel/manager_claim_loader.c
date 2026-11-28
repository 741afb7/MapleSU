// SPDX-License-Identifier: GPL-3.0-only
#include "manager_claim_loader.h"

static int msu_loader_within(msu_loader_u32 offset, msu_loader_u32 length,
                             msu_loader_u32 size)
{
    return offset <= size && length <= size - offset;
}

static int msu_loader_overlap(msu_loader_u32 a, msu_loader_u32 n,
                              msu_loader_u32 b, msu_loader_u32 m)
{
    return a < b + m && b < a + n;
}

static int msu_loader_address_range(msu_loader_uptr base, msu_loader_u32 bytes)
{
    return bytes <= (msu_loader_uptr)~(msu_loader_uptr)0 - base;
}

/* Both ranges are checked for pointer-width overflow before comparison. */
static int msu_loader_address_overlap(msu_loader_uptr a, msu_loader_u32 n,
    msu_loader_uptr b, msu_loader_u32 m)
{
    return a < b + m && b < a + n;
}

static msu_loader_u32 msu_loader_crc(msu_loader_u32 value,
                                    const msu_loader_u8 *p, msu_loader_u32 length)
{
    msu_loader_u32 bit;

    while (length--) {
        value ^= *p++;
        for (bit = 0; bit < 8; bit++)
            value = (value >> 1) ^ (0xedb88320U & (0U - (value & 1)));
    }
    return value;
}

static int msu_loader_section_has(const struct msu_loader_section *s,
                                  msu_loader_u32 count, msu_loader_u32 at,
                                  msu_loader_u32 bytes, msu_loader_u32 permission)
{
    msu_loader_u32 i;

    for (i = 0; i < count; i++)
        if ((s[i].flags & permission) == permission && at >= s[i].offset &&
            msu_loader_within(at - s[i].offset, bytes, s[i].memory_bytes))
            return 1;
    return 0;
}

static int msu_loader_materialize(const struct msu_loader_binding *b,
    const void *image, const void *payload, msu_loader_u32 payload_bytes,
    void *memory, msu_loader_u32 capacity, msu_loader_u32 version)
{
    const msu_loader_u8 *kernel = image;
    const msu_loader_u8 *source = payload;
    msu_loader_u8 *target = memory;
    const struct msu_loader_header *h;
    const struct msu_loader_section *s;
    const struct msu_loader_chunk *c;
    const msu_loader_u32 *imports;
    const struct msu_loader_reloc *r;
    msu_loader_u32 i, j, section = 0, cursor = 0, checksum = ~0U, previous = 0, bytes;
    msu_loader_uptr kernel_base = (msu_loader_uptr)image;
    msu_loader_uptr source_base = (msu_loader_uptr)payload;
    msu_loader_uptr target_base = (msu_loader_uptr)memory;
    msu_loader_u32 header_bytes = version == MSU_LOADER_VERSION ?
        sizeof(*h) : sizeof(struct msu_loader_source_header);

    if (!b || !kernel || !target || b->descriptor_offset % 4 || b->loader_offset % 4 ||
        !b->loader_bytes || b->descriptor_bytes < header_bytes ||
        !msu_loader_within(b->descriptor_offset, b->descriptor_bytes, b->image_bytes) ||
        !msu_loader_within(b->loader_offset, b->loader_bytes, b->image_bytes) ||
        msu_loader_overlap(b->loader_offset, b->loader_bytes,
                           b->descriptor_offset, b->descriptor_bytes) ||
        !msu_loader_address_range(kernel_base, b->image_bytes) ||
        !msu_loader_address_range(target_base, capacity) ||
        msu_loader_address_overlap(target_base, capacity, kernel_base, b->image_bytes) ||
        (version == MSU_LOADER_SOURCE_VERSION && kernel_base % _Alignof(struct msu_loader_header)))
        return MSU_LOADER_INVALID_BINDING;
    if (version == MSU_LOADER_SOURCE_VERSION) {
        if (!source || !payload_bytes || !msu_loader_address_range(source_base, payload_bytes))
            return MSU_LOADER_INVALID_SOURCE;
        if (msu_loader_address_overlap(target_base, capacity, source_base, payload_bytes))
            return MSU_LOADER_INVALID_BINDING;
    } else {
        payload_bytes = b->image_bytes;
    }
    h = (const void *)(kernel + b->descriptor_offset);
    if (h->magic != MSU_LOADER_MAGIC || h->version != version ||
        h->kernel_memory_bytes < b->image_bytes ||
        !msu_loader_address_range(kernel_base, h->kernel_memory_bytes) ||
        h->image_bytes != b->image_bytes || h->total_bytes != b->descriptor_bytes ||
        !h->section_count || h->section_count > MSU_LOADER_MAX_SECTIONS ||
        !h->chunk_count || h->chunk_count > MSU_LOADER_MAX_CHUNKS ||
        h->import_count > MSU_LOADER_MAX_IMPORTS || h->reloc_count > MSU_LOADER_MAX_RELOCS ||
        h->import_count > h->reloc_count ||
        !h->memory_bytes || h->memory_bytes > MSU_LOADER_MAX_MEMORY_BYTES ||
        h->reloc_count > h->memory_bytes / 8U || h->memory_bytes > capacity)
        return MSU_LOADER_INVALID_HEADER;
    if (version == MSU_LOADER_SOURCE_VERSION) {
        const struct msu_loader_source_header *source_header = (const void *)h;
        if (source_header->source_bytes != payload_bytes)
            return MSU_LOADER_INVALID_SOURCE;
        if (msu_loader_address_overlap(target_base, capacity, kernel_base, h->kernel_memory_bytes))
            return MSU_LOADER_INVALID_BINDING;
    }
    bytes = h->chunk_count * sizeof(*c);
    if (h->chunk_table_offset % 4 ||
        !msu_loader_within(h->chunk_table_offset, bytes, b->image_bytes) ||
        msu_loader_overlap(h->chunk_table_offset, bytes,
                           b->descriptor_offset, b->descriptor_bytes) ||
        msu_loader_overlap(h->chunk_table_offset, bytes, b->loader_offset, b->loader_bytes))
        return MSU_LOADER_INVALID_CHUNK_TABLE;
    c = (const void *)(kernel + h->chunk_table_offset);
    if ((msu_loader_crc(msu_loader_crc(~0U, (const msu_loader_u8 *)h, b->descriptor_bytes),
                        (const msu_loader_u8 *)c, bytes) ^ ~0U) != b->descriptor_crc)
        return MSU_LOADER_INVALID_HEADER;
    /* Divide the remaining immutable descriptor budget before multiplying
     * untrusted counts, so no u32 size wrap can select a different record. */
    bytes = b->descriptor_bytes - header_bytes;
    if (h->section_count > bytes / sizeof(*s))
        return MSU_LOADER_INVALID_DESCRIPTOR_SIZE;
    bytes -= h->section_count * sizeof(*s);
    if (h->import_count > bytes / sizeof(*imports))
        return MSU_LOADER_INVALID_DESCRIPTOR_SIZE;
    bytes -= h->import_count * sizeof(*imports);
    if (bytes % sizeof(*r) || h->reloc_count != bytes / sizeof(*r))
        return MSU_LOADER_INVALID_DESCRIPTOR_SIZE;
    s = (const void *)((const msu_loader_u8 *)h + header_bytes);
    imports = (const void *)(s + h->section_count);
    r = (const void *)(imports + h->import_count);
    for (i = 0; i < h->section_count; i++) {
        if (s[i].offset < previous || s[i].file_bytes > s[i].memory_bytes ||
            !msu_loader_within(s[i].offset, s[i].memory_bytes, h->memory_bytes) ||
            !(s[i].flags & MSU_LOADER_READ) || (s[i].flags & ~7U) ||
            (s[i].flags & (MSU_LOADER_WRITE | MSU_LOADER_EXEC)) ==
                (MSU_LOADER_WRITE | MSU_LOADER_EXEC))
            return MSU_LOADER_INVALID_SECTION;
        previous = s[i].offset + s[i].memory_bytes;
    }
    if (h->entry_offset % 4 ||
        !msu_loader_section_has(s, h->section_count, h->entry_offset, 4, MSU_LOADER_EXEC))
        return MSU_LOADER_INVALID_ENTRY;
    cursor = s[0].offset;
    for (i = 0; i < h->chunk_count; i++) {
        while (section < h->section_count && cursor == s[section].offset + s[section].file_bytes) {
            section++;
            if (section < h->section_count)
                cursor = s[section].offset;
        }
        if (section == h->section_count || !c[i].length ||
            !msu_loader_within(c[i].source, c[i].length, payload_bytes) ||
            c[i].length > s[section].file_bytes - (cursor - s[section].offset) ||
            msu_loader_address_overlap(source_base + c[i].source, c[i].length,
                                       kernel_base + b->descriptor_offset, b->descriptor_bytes) ||
            msu_loader_address_overlap(source_base + c[i].source, c[i].length,
                                       kernel_base + b->loader_offset, b->loader_bytes) ||
            msu_loader_address_overlap(source_base + c[i].source, c[i].length,
                                       kernel_base + h->chunk_table_offset,
                                       h->chunk_count * sizeof(*c)))
            return MSU_LOADER_INVALID_CHUNK;
        for (j = 0; j < i; j++)
            if (msu_loader_overlap(c[i].source, c[i].length, c[j].source, c[j].length))
                return MSU_LOADER_INVALID_CHUNK;
        cursor += c[i].length;
        if (version == MSU_LOADER_VERSION)
            checksum = msu_loader_crc(checksum, source + c[i].source, c[i].length);
    }
    while (section < h->section_count && cursor == s[section].offset + s[section].file_bytes) {
        section++;
        if (section < h->section_count)
            cursor = s[section].offset;
    }
    if (section != h->section_count ||
        (version == MSU_LOADER_VERSION && (checksum ^ ~0U) != h->payload_crc))
        return MSU_LOADER_INVALID_PAYLOAD;
    for (i = 0; i < h->import_count; i++)
        if (imports[i] % 4 || !msu_loader_within(imports[i], 4, h->kernel_memory_bytes))
            return MSU_LOADER_INVALID_IMPORT;
    for (i = 0; i < h->reloc_count; i++) {
        if (r[i].reserved || r[i].target % 8 ||
            !msu_loader_section_has(s, h->section_count, r[i].target, 8, MSU_LOADER_WRITE) ||
            (r[i].kind == MSU_LOADER_RELOC_RELATIVE ?
                !msu_loader_section_has(s, h->section_count, r[i].value, 1, MSU_LOADER_READ) :
                r[i].kind != MSU_LOADER_RELOC_KERNEL_IMPORT || r[i].value >= h->import_count))
            return MSU_LOADER_INVALID_RELOCATION;
        for (j = 0; j < i; j++)
            if (r[j].target == r[i].target)
                return MSU_LOADER_INVALID_RELOCATION;
    }
    if (version == MSU_LOADER_SOURCE_VERSION) {
        /* Metadata failures must not read the separately mapped source. */
        for (i = 0; i < h->chunk_count; i++)
            checksum = msu_loader_crc(checksum, source + c[i].source, c[i].length);
        if ((checksum ^ ~0U) != h->payload_crc)
            return MSU_LOADER_INVALID_PAYLOAD;
    }
    for (i = 0; i < h->memory_bytes; i++)
        target[i] = 0;
    section = 0;
    cursor = s[0].offset;
    for (i = 0; i < h->chunk_count; i++) {
        while (cursor == s[section].offset + s[section].file_bytes) {
            section++;
            cursor = s[section].offset;
        }
        for (j = 0; j < c[i].length; j++)
            target[cursor + j] = source[c[i].source + j];
        cursor += c[i].length;
    }
    for (i = 0; i < h->reloc_count; i++) {
        msu_loader_u64 value = r[i].kind == MSU_LOADER_RELOC_RELATIVE ?
            target_base + r[i].value : kernel_base + imports[r[i].value];
        for (j = 0; j < 8; j++)
            target[r[i].target + j] = (msu_loader_u8)(value >> (j * 8));
    }
    return (int)h->entry_offset + 1;
}

int msu_manager_claim_materialize(const struct msu_loader_binding *binding,
                                  const void *image, void *destination,
                                  msu_loader_u32 capacity)
{
    return msu_loader_materialize(binding, image, image, 0, destination, capacity,
                                  MSU_LOADER_VERSION);
}

int msu_manager_claim_materialize_source(const struct msu_loader_binding *binding,
    const void *kernel_base, const void *source_base, msu_loader_u32 source_bytes,
    void *destination, msu_loader_u32 capacity)
{
    return msu_loader_materialize(binding, kernel_base, source_base, source_bytes,
                                  destination, capacity, MSU_LOADER_SOURCE_VERSION);
}
