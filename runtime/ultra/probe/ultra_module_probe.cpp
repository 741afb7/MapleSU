
#include <cstdio>
#include <cstring>
#include <string>

#include "kernel_module_kit_umbrella.h"

namespace {

void print_escaped(const char* text) {
    if (text == nullptr) {
        std::printf("\"\"");
        return;
    }
    std::printf("\"");
    for (const char* p = text; *p != '\0'; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        if (c == '"' || c == '\\') {
            std::printf("\\%c", c);
        } else if (c < 0x20) {
            std::printf("\\u%04x", c);
        } else {
            std::putchar(c);
        }
    }
    std::printf("\"");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc != 2) {
        std::printf("{\"probe\":\"module\",\"ok\":false,\"error\":\"usage: %s <zip>\"}\n",
                    argv[0]);
        return 2;
    }
    const char* zip = argv[1];

    maplesu::sdk::module_desc desc{};
    const KModErr err = maplesu::sdk::parse_module_desc_from_zip_file(zip, desc);

    std::printf("{\"probe\":\"maplesu-ultra-module\",\"schemaVersion\":1,\"zip\":");
    print_escaped(zip);
    std::printf(",\"ok\":%s,\"sdkError\":\"%s\",\"sdkErrorCode\":%lld",
                is_ok(err) ? "true" : "false", to_string(err).c_str(),
                static_cast<long long>(to_num(err)));
    if (is_ok(err)) {
        std::printf(",\"desc\":{\"name\":");
        print_escaped(desc.name);
        std::printf(",\"version\":");
        print_escaped(desc.version);
        std::printf(",\"author\":");
        print_escaped(desc.author);
        std::printf(",\"id32\":");
        print_escaped(desc.id32);
        std::printf(",\"web_ui\":%s,\"min_sdk\":\"%u.%u.%u\"",
                    desc.web_ui ? "true" : "false", desc.min_sdk_ver.major,
                    desc.min_sdk_ver.minor, desc.min_sdk_ver.patch);
        std::printf(",\"update_json\":");
        print_escaped(desc.update_json);
        std::printf("}");
    }
    std::printf("}\n");
    return is_ok(err) ? 0 : 1;
}
