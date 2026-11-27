#include "../maplesu-sdk/sdk/src/payload/webui_loader/src/webroot_server.h"
#include <cstdio>

namespace maplesu::payload::webui {
WebRootServer::~WebRootServer() = default;
bool WebRootServer::start(const ServerConfig&) {
    std::puts("STATIC_FALLBACK_CALLED");
    return false;
}
void WebRootServer::stop() {}
void wait_for_owner_exit(int) {}
}
