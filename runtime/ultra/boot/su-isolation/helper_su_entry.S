.text
.global maplesu_helper_su_writer_entry
.type maplesu_helper_su_writer_entry,%function
maplesu_helper_su_writer_entry:
    // This continuation shares F508C's existing stack and cleanup paths.
    add x0, sp, #0x40
    adrp x1, original_module_id
    add x1, x1, :lo12:original_module_id
    bl maplesu_helper_su_writer_load
    sxtw x22, w0
    cbnz x22, original_su_init_error
    b original_su_init_success
.size maplesu_helper_su_writer_entry, .-maplesu_helper_su_writer_entry

.global maplesu_helper_su_exec_entry
.type maplesu_helper_su_exec_entry,%function
maplesu_helper_su_exec_entry:
    // Tail adapter retains this/control and the native caller's LR/frame.
    adrp x2, original_module_id
    add x2, x2, :lo12:original_module_id
    b maplesu_helper_su_exec_load
.size maplesu_helper_su_exec_entry, .-maplesu_helper_su_exec_entry
