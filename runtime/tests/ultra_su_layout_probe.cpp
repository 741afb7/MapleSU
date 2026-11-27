#define main maplesu_client_main
#include "../src/su_main.cpp"
#undef main

int main(int argc, char** argv) {
    // Exercise the production forwarder through its real exec. Once the image
    // resolves to the Manager source, inspect its actual canonical layout.
    if (thin_shared_client()) return maplesu_client_main(argc, argv);
    std::string apk, library;
    if (!derive_layout(apk, library)) return 91;
    std::printf("LAYOUT:%s:%s:UID:%u\n", apk.c_str(), library.c_str(), getuid());
    return 0;
}
