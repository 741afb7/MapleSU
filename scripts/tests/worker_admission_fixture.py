"""Compile the unchanged production admission function at transport boundaries."""
import hashlib
import json


def prepare_worker_admission(root, out):
    name = 'runtime/maplesu-sdk/sdk/src/kernel_request/module_base_kernel_request.cpp'
    raw = (root / name).read_bytes()
    source = raw.decode('utf-8')
    start = source.index('KModErr maplesu_request_worker_admission() {')
    opening = source.index('{', start)
    depth = 0
    for index in range(opening, len(source)):
        depth += (source[index] == '{') - (source[index] == '}')
        if not depth:
            end = index + 1
            break
    else:
        raise ValueError('Production admission function has no closing brace')
    body = source[start:end].encode('utf-8')
    unit = (b'#include "module_err_def.h"\n#include "maplesu_request_private.h"\n'
            b'#include <cerrno>\n#include <unistd.h>\nnamespace kernel_module {\n' + body + b'\n}\n')
    (out / 'worker_admission.cpp').write_bytes(unit)
    record = dict(source=name, sourceSha256=hashlib.sha256(raw).hexdigest(),
                  startLine=source[:start].count('\n') + 1,
                  sourceByteOffset=len(source[:start].encode('utf-8')), sourceBytes=len(body),
                  functionSha256=hashlib.sha256(body).hexdigest(),
                  translationUnitSha256=hashlib.sha256(unit).hexdigest(), unchangedFunction=True,
                  boundary='owner_channel and helper_vendor transport only; production guard is compiled verbatim')
    (out / 'worker-admission-source.json').write_bytes((json.dumps(record, indent=2) + '\n').encode())
    return record
