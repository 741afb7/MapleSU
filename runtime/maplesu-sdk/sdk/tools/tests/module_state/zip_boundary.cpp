#include "zip.h"
#include <algorithm>
#include <cstring>
struct zip {};
struct zip_file { size_t cursor = 0; };
static constexpr char payload[] = "fixture:fixture_mod";
extern "C" {
zip_t* zip_open(const char*, int, int*) { return new zip; }
int zip_close(zip_t* archive) { delete archive; return 0; }
void zip_discard(zip_t* archive) { delete archive; }
zip_int64_t zip_get_num_entries(zip_t*, zip_flags_t) { return 1; }
int zip_stat_index(zip_t*, zip_uint64_t, zip_flags_t, zip_stat_t* stat) {
    memset(stat, 0, sizeof(*stat)); stat->size = sizeof(payload) - 1; return 0;
}
const char* zip_get_name(zip_t*, zip_uint64_t, zip_flags_t) { return "fixture_mod"; }
zip_file_t* zip_fopen_index(zip_t*, zip_uint64_t, zip_flags_t) { return new zip_file; }
zip_int64_t zip_fread(zip_file_t* file, void* out, zip_uint64_t count) {
    const size_t size = std::min<size_t>(count, sizeof(payload) - 1 - file->cursor);
    memcpy(out, payload + file->cursor, size); file->cursor += size; return size;
}
int zip_fclose(zip_file_t* file) { delete file; return 0; }
const char* zip_strerror(zip_t*) { return "fixture zip boundary"; }
const char* zip_file_strerror(zip_file_t*) { return "fixture zip boundary"; }
int zip_error_to_str(char* out, zip_uint64_t size, int, int) {
    if (size) out[0] = 0; return 0;
}
}
