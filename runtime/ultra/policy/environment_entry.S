.text
.global maplesu_environment_policy_entry
.type maplesu_environment_policy_entry,%function
maplesu_environment_policy_entry:
    stp x29, x30, [sp, #-224]!
    stp x0, x1, [sp, #16]
    stp x2, x3, [sp, #32]
    stp x4, x5, [sp, #48]
    stp x6, x7, [sp, #64]
    str x8, [sp, #80]
    stp q0, q1, [sp, #96]
    stp q2, q3, [sp, #128]
    stp q4, q5, [sp, #160]
    stp q6, q7, [sp, #192]
    bl maplesu_environment_policy_load
    cmp w0, #0
    cset w9, eq
    adrp x10, policy_ready_slot
    add x10, x10, :lo12:policy_ready_slot
    stlr w9, [x10]
    ldp x0, x1, [sp, #16]
    ldp x2, x3, [sp, #32]
    ldp x4, x5, [sp, #48]
    ldp x6, x7, [sp, #64]
    ldr x8, [sp, #80]
    ldp q0, q1, [sp, #96]
    ldp q2, q3, [sp, #128]
    ldp q4, q5, [sp, #160]
    ldp q6, q7, [sp, #192]
    ldp x29, x30, [sp], #224
    // A policy preparation failure must not replace the native initialization
    // path or its result. Only the later third-party module entry is held.
    b original_selinux_initializer
.size maplesu_environment_policy_entry, .-maplesu_environment_policy_entry

.global maplesu_environment_policy_module_entry
.type maplesu_environment_policy_module_entry,%function
maplesu_environment_policy_module_entry:
    // Called by the original Identity thunk after its management launcher.
    // The slot is zero-initialized by the ELF loader and lives only this boot.
    adrp x10, policy_ready_slot
    add x10, x10, :lo12:policy_ready_slot
    ldar w9, [x10]
    cbz w9, .Lhold_modules
    b original_module_loader
.Lhold_modules:
    mov x0, #-1
    ret
.size maplesu_environment_policy_module_entry, .-maplesu_environment_policy_module_entry
