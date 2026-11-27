"""Select path_mount's path qualifier from actual kernel type inputs."""
import re


def path_mount_path_const(header):
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", header.decode("utf-8"), flags=re.S)
    declarations = re.findall(r"\bint\s+path_mount\s*\(([^)]*)\)\s*;", text, re.S)
    if len(declarations) != 1:
        raise ValueError("PATH_MOUNT_DECLARATION_REQUIRED")
    names = r"(?:\s*[A-Za-z_]\w*)?"
    pattern = (r"\s*const\s+char\s*\*" + names + r"\s*,\s*(const\s+)?struct\s+path\s*\*" + names +
               r"\s*,\s*const\s+char\s*\*" + names + r"\s*,\s*unsigned\s+long\b" + names +
               r"\s*,\s*void\s*\*" + names + r"\s*")
    match = re.fullmatch(pattern, declarations[0])
    if match is None:
        raise ValueError("PATH_MOUNT_DECLARATION_UNSUPPORTED")
    return int(bool(match[1]))


def path_mount_path_const_btf(data):
    # Preserve the pointee's CONST node; canonical ABI strings erase it.
    from policy_btf_abi import Btf
    btf = Btf(data)
    functions = btf.functions.get("path_mount", [])
    if len(functions) != 1:
        raise ValueError("PATH_MOUNT_BTF_FUNCTION_REQUIRED")
    proto = btf.types[btf.resolve(btf.types[functions[0]][3])]
    if proto[1] != 13 or proto[2] != 5:
        raise ValueError("PATH_MOUNT_BTF_PROTOTYPE")
    pointer = btf.types[btf.resolve(btf.u32(proto[5] + 12))]
    if pointer[1] != 2:
        raise ValueError("PATH_MOUNT_BTF_PATH_POINTER")
    index, const = pointer[3], False
    for _ in range(64):
        item = btf.types[index]
        if item[1] not in (8, 10):
            break
        const = const or item[1] == 10
        index = item[3]
    else:
        raise ValueError("PATH_MOUNT_BTF_MODIFIER_CYCLE")
    if item[1] not in (4, 7) or btf.name(item[0]) != "path":
        raise ValueError("PATH_MOUNT_BTF_PATH_RECORD")
    return int(const)


def path_mount_qualifier_define(value):
    if value not in (0, 1):
        raise ValueError("PATH_MOUNT_FEATURE_VALUE")
    return "#define MSU_PATH_MOUNT_PATH_QUALIFIER " + ("const" if value else "") + "\n"
