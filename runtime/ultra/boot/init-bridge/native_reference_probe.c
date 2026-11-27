// SPDX-License-Identifier: GPL-3.0-only
#include "native_imports.h"

struct file *msu_direct_probe(struct path *root, const char *name,
    struct filename **filename, struct group_info **groups)
{
    *filename = getname_kernel(name);
    *groups = groups_alloc(1);
    putname(*filename);
    return file_open_root(root->dentry, root->mnt, name, O_RDONLY | O_NOFOLLOW, 0);
}

noinline void *msu_indirect_probe(void *(*callback)(void *), void *argument)
{
    return callback(argument);
}

MODULE_LICENSE("GPL");
