cmake_minimum_required(VERSION 3.22)
project(maplesu_runtime LANGUAGES CXX ASM)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The MapleSU runtime target requires Android ARM64")
endif()
add_subdirectory(ultra/boot/ultra-init EXCLUDE_FROM_ALL)
add_library(maplesu_root_runtime STATIC
    upstream/src/jni/rootkit_command.cpp
    upstream/src/jni/rootkit_fork_helper.cpp)
target_include_directories(maplesu_root_runtime PUBLIC upstream/include PRIVATE upstream/src/jni)
target_compile_features(maplesu_root_runtime PUBLIC cxx_std_20)
# Upstream omits these includes in its fork helper; keep imported bytes intact.
target_compile_options(maplesu_root_runtime PRIVATE "SHELL:-include utility" "SHELL:-include fcntl.h"
    -ffunction-sections -fdata-sections "$<$<CONFIG:Release>:-g0>")
add_executable(maplesu-runtime src/exec_main.cpp src/su_session.cpp)
# The upstream kit is a superset of the two sources imported above: it also carries
# get_root, run_root_cmd_with_cb and the parasite primitives. Linking both would define
# those symbols twice, so this executable links the kit alone - which is also the more
# faithful "reuse upstream bytes" choice.
target_link_libraries(maplesu-runtime PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/upstream/lib/libkernel_root_kit_static.a)
target_include_directories(maplesu-runtime PRIVATE upstream/include)
target_compile_options(maplesu-runtime PRIVATE -Wall -Wextra -Werror "$<$<CONFIG:Release>:-g0>")
target_link_options(maplesu-runtime PRIVATE -Wl,--gc-sections -Wl,-z,relro,-z,now)

add_executable(maplesu-su src/su_main.cpp)
# A delivered image admits itself: it carries the application's credential in a trailer
# and calls get_root directly, so it needs the same upstream kit as the runtime. That is
# what removes the Binder helper, the directory owner and the root service from the
# request path for an already-authorised application.
target_link_libraries(maplesu-su PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/upstream/lib/libkernel_root_kit_static.a)
target_include_directories(maplesu-su PRIVATE upstream/include)
target_compile_features(maplesu-su PRIVATE cxx_std_20)
target_compile_options(maplesu-su PRIVATE -Wall -Wextra -Werror)
target_link_options(maplesu-su PRIVATE -Wl,--gc-sections -Wl,-z,relro,-z,now)

add_library(maplesu_bootstrap SHARED src/su_bootstrap.cpp src/session_process.cpp src/block_partition_access.cpp)
target_compile_features(maplesu_bootstrap PRIVATE cxx_std_20)
target_compile_options(maplesu_bootstrap PRIVATE -Wall -Wextra -Werror)
target_link_options(maplesu_bootstrap PRIVATE -Wl,-z,relro,-z,now)

add_executable(maplesu-block-partition-test EXCLUDE_FROM_ALL tests/block_partition_ioctl_test.cpp)
target_compile_features(maplesu-block-partition-test PRIVATE cxx_std_20)
target_compile_options(maplesu-block-partition-test PRIVATE -Wall -Wextra -Werror)

# Hidden-mode parasite loader. It is not injected by us at runtime: it is copied next to
# a host app's own native libraries and named by a DT_NEEDED entry we add there, so
# bionic loads it inside that app's process. Its whole contract is "run the root-owned
# start script through a su channel the app domain can execute, silently on success".
add_library(maplesu_parasite SHARED src/parasite/parasite_loader.cpp)
target_compile_features(maplesu_parasite PRIVATE cxx_std_20)
target_compile_options(maplesu_parasite PRIVATE -Wall -Wextra -Werror)
target_link_libraries(maplesu_parasite PRIVATE log)
target_link_options(maplesu_parasite PRIVATE -Wl,-z,relro,-z,now)

add_executable(maplesu-modules src/module_owner.cpp)
target_compile_features(maplesu-modules PRIVATE cxx_std_20)
target_compile_options(maplesu-modules PRIVATE -Wall -Wextra -Werror "$<$<CONFIG:Release>:-g0>")
target_link_options(maplesu-modules PRIVATE -Wl,-z,relro,-z,now)

# --- Selected Ultra SDK delivery -------------------------------------------------
# Source builds are staged as a complete SDK directory with their headers, archive
# and identity. The default remains the retained delivery until candidate acceptance.
# The Ultra and Lite archives expose different products and are linked separately.
#
# The bridge target is deliberately separate from every Lite target. The reference
# Manager bridge is not compiled at all - see ultra/reference/native-lib.cpp and
# ultra/bridge/native_lib_ultra.cpp, which returns structured JSON instead of text.
add_library(maplesu_ultra_sdk STATIC IMPORTED)
set(MAPLESU_ULTRA_SDK_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/maplesu-sdk" CACHE PATH "Verified Ultra SDK archive and build identity")
set_target_properties(maplesu_ultra_sdk PROPERTIES
    IMPORTED_LOCATION "${MAPLESU_ULTRA_SDK_DIRECTORY}/lib/arm64-v8a/libkernel_module_kit_static.a"
    INTERFACE_INCLUDE_DIRECTORIES "${MAPLESU_ULTRA_SDK_DIRECTORY}/include")

# The retained SDK archive predates two source-owned public entry points used by
# the Manager probes. Build only those source translation units here and keep the
# archive as the provider for their remaining implementation dependencies. This
# is intentionally a Manager-runtime supplement, not a replacement SDK delivery.
add_library(maplesu_sdk_source_exports STATIC
    maplesu-sdk/sdk/src/maplesu/install/bootstrap_state.cpp
    maplesu-sdk/sdk/src/maplesu/module/api.cpp)
target_compile_features(maplesu_sdk_source_exports PRIVATE cxx_std_20)
target_compile_options(maplesu_sdk_source_exports PRIVATE
    -fexceptions -ffunction-sections -fdata-sections -fvisibility=hidden
    -D_FORTIFY_SOURCE=2)
target_include_directories(maplesu_sdk_source_exports PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/maplesu-sdk/sdk/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/maplesu-sdk/sdk/src"
    "${MAPLESU_ULTRA_SDK_DIRECTORY}/include")

# Candidate boot backend. Keep it outside packaging until the synchronous SDK
# entry and real-device kernel-hook acceptance are complete.
add_library(maplesu_policy_boot SHARED EXCLUDE_FROM_ALL
    ultra/policy/environment_policy.cpp ultra/policy/kernel_policy_gate.cpp
    ultra/policy/module_policy_config.cpp ultra/boot/su-isolation/helper_su_backend.cpp)
target_compile_features(maplesu_policy_boot PRIVATE cxx_std_20)
target_compile_options(maplesu_policy_boot PRIVATE -Wall -Wextra -Werror -fexceptions -fno-rtti
    -ffunction-sections -fdata-sections)
target_link_options(maplesu_policy_boot PRIVATE -Wl,--no-undefined -Wl,--gc-sections
    -Wl,-z,relro,-z,now -Wl,-z,max-page-size=65536)
target_link_libraries(maplesu_policy_boot PRIVATE maplesu_ultra_sdk z log dl)

# Explicitly supplied generated candidates only. No payload or new SDK startup
# entry is added to manager packaging by this option.
set(MAPLESU_POLICY_BUNDLE_SOURCE "" CACHE FILEPATH "Generated Ultra policy candidate bundle source")
if(MAPLESU_POLICY_BUNDLE_SOURCE)
    # Older accepted SDK archives do not contain the shared parser. A separate
    # archive is extracted only when the selected SDK has not already supplied it.
    add_library(maplesu_kernel_layout STATIC EXCLUDE_FROM_ALL ultra/policy/kernel_layout.cpp)
    target_compile_features(maplesu_kernel_layout PRIVATE cxx_std_20)
    target_compile_options(maplesu_kernel_layout PRIVATE -Wall -Wextra -Werror -fexceptions)
    target_link_libraries(maplesu_policy_boot PRIVATE maplesu_kernel_layout)
    target_sources(maplesu_policy_boot PRIVATE
        ultra/policy/kernel_payload.cpp
        ultra/policy/kernel_payload_loader.cpp ultra/policy/kernel_payload_sdk.cpp ultra/policy/kernel_payload_metadata.cpp
        "${MAPLESU_POLICY_BUNDLE_SOURCE}")
    target_include_directories(maplesu_policy_boot PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/ultra/policy")
    target_compile_definitions(maplesu_policy_boot PRIVATE MAPLESU_POLICY_HIDE_PAYLOAD=1)
endif()

# Offline byte emitter used by Unicorn ABI tests. It never invokes SdkIo.
add_library(maplesu_policy_dispatch_probe SHARED EXCLUDE_FROM_ALL
    ultra/policy/kernel_payload_sdk.cpp ultra/policy/kernel_payload_metadata.cpp ultra/policy/module_policy_config.cpp tests/kernel_payload_sdk_probe.cpp)
target_include_directories(maplesu_policy_dispatch_probe PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/ultra/policy")
target_compile_features(maplesu_policy_dispatch_probe PRIVATE cxx_std_20)
target_compile_options(maplesu_policy_dispatch_probe PRIVATE -Wall -Wextra -Werror -fexceptions
    -fno-rtti -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(maplesu_policy_dispatch_probe PRIVATE -Wl,--no-undefined -Wl,--gc-sections)
target_link_libraries(maplesu_policy_dispatch_probe PRIVATE maplesu_ultra_sdk z log dl)

# --- Bundled build identity (5.0.5) --------------------------------------------
# SDK-IDENTITY.cmake is written by scripts/import-ultra-sdk-505.ps1 from
# evidence/ultra-sdk-505/sdk/sdk-build.json, only after that script has verified the
# archive/bootstrap/release/provenance digests. The include is OPTIONAL and every
# variable is defaulted here: a checkout that has not imported an SDK still
# configures and builds, and the bridge then reports the "unknown" sentinel instead
# of a false identity. The values are baked in as compile definitions so the bridge
# reports the identity of the bytes that were actually linked in, not a source
# constant that can drift away from the archive.
include("${MAPLESU_ULTRA_SDK_DIRECTORY}/SDK-IDENTITY.cmake" OPTIONAL)
if(NOT DEFINED MAPLESU_ULTRA_SDK_BUILD_ID)
    set(MAPLESU_ULTRA_SDK_BUILD_ID "unknown")
endif()
if(NOT DEFINED MAPLESU_ULTRA_SDK_VERSION)
    set(MAPLESU_ULTRA_SDK_VERSION "unknown")
endif()
if(NOT DEFINED MAPLESU_ULTRA_SDK_SHA256)
    set(MAPLESU_ULTRA_SDK_SHA256 "unknown")
endif()
if(NOT DEFINED MAPLESU_ULTRA_HELPER_SHA256)
    set(MAPLESU_ULTRA_HELPER_SHA256 "unknown")
endif()
if(NOT DEFINED MAPLESU_ULTRA_HELPER_BUILD_ID)
    set(MAPLESU_ULTRA_HELPER_BUILD_ID "unknown")
endif()
if(NOT DEFINED MAPLESU_ULTRA_HELPER_SIZE)
    set(MAPLESU_ULTRA_HELPER_SIZE -1)
endif()
if(NOT DEFINED MAPLESU_ULTRA_KEY_SLOT_OFFSET)
    set(MAPLESU_ULTRA_KEY_SLOT_OFFSET -1)
endif()
if(NOT DEFINED MAPLESU_APP_VERSION)
    set(MAPLESU_APP_VERSION "unknown")
endif()

add_library(maplesu_ultra_bridge SHARED
    ultra/bridge/native_lib_ultra.cpp
    ultra/bridge/module_environment_capture.cpp
    ultra/policy/module_policy_config.cpp
    ultra/policy/module_policy_management.cpp
    ultra/policy/backend_publish.cpp
    ultra/upgrade/executable_publish.cpp
    ultra/bridge/sdk_call_scope.cpp
    ultra/bridge/manager_owner_root_probe.cpp
    ultra/bridge/installed_helper_readback.cpp
    ultra/bridge/sdk_syscall.S
    ultra/bridge/uninstall_callback_watchdog.cpp
    ultra/bridge/webui_lifecycle.cpp
    ultra/third_party/cJSON/cJSON.cpp)
target_include_directories(maplesu_ultra_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/ultra/authorization
    ${CMAKE_CURRENT_SOURCE_DIR}/ultra/third_party/cJSON
    ${CMAKE_CURRENT_SOURCE_DIR}/ultra/third_party/include)
target_compile_features(maplesu_ultra_bridge PRIVATE cxx_std_20)
# Bundled build identity: the same values the 5.0.5 BuildConfig fields carry (see
# manager/build.gradle.kts). getSdkBuildIdentity reports them from the linked bytes.
target_compile_definitions(maplesu_ultra_bridge PRIVATE
    # The archive's existing su installer already defines the legacy writer.
    # Only add private-backend publication here; never redefine that SDK export.
    MAPLESU_BACKEND_PUBLISH_ONLY=1
    MAPLESU_ULTRA_SDK_BUILD_ID="${MAPLESU_ULTRA_SDK_BUILD_ID}"
    MAPLESU_ULTRA_SDK_VERSION="${MAPLESU_ULTRA_SDK_VERSION}"
    MAPLESU_ULTRA_SDK_SHA256="${MAPLESU_ULTRA_SDK_SHA256}"
    MAPLESU_ULTRA_HELPER_SHA256="${MAPLESU_ULTRA_HELPER_SHA256}"
    MAPLESU_ULTRA_HELPER_BUILD_ID="${MAPLESU_ULTRA_HELPER_BUILD_ID}"
    MAPLESU_ULTRA_HELPER_SIZE=${MAPLESU_ULTRA_HELPER_SIZE}
    MAPLESU_ULTRA_KEY_SLOT_OFFSET=${MAPLESU_ULTRA_KEY_SLOT_OFFSET}
    MAPLESU_APP_VERSION="${MAPLESU_APP_VERSION}")
# -fexceptions is explicit: the bridge converts C++ exceptions into error receipts and
# must not rely on an inherited default.
target_compile_options(maplesu_ultra_bridge PRIVATE -fexceptions -ffunction-sections -fdata-sections)
set(MAPLESU_UNINSTALL_CALLBACK_SYMBOL
    maplesu_module_uninstall_callback)
target_link_options(maplesu_ultra_bridge PRIVATE
    -Wl,--wrap=${MAPLESU_UNINSTALL_CALLBACK_SYMBOL}
    -Wl,--wrap=__set_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW
    -Wl,--wrap=__get_current_module_root_key_hd04aBTnbSZlTRP7S4mQpIeVvr2ssGjW
    -Wl,--wrap=syscall
    -Wl,--wrap=fork -Wl,--wrap=pipe2 -Wl,--wrap=close -Wl,--wrap=waitpid
    -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,relro,-z,now)
# dlsym alone does not extract archive members. Retain the three legacy exports.
set(MAPLESU_PARASITE_SYMBOLS
    _ZN11kernel_root29parasite_precheck_app_with_cbEPKcS1_PFvS1_NS_15AppDynlibStatusEE
    _ZN11kernel_root20parasite_implant_appEPKcS1_S1_
    _ZN11kernel_root23parasite_implant_su_envEPKcS1_S1_S1_)
foreach(symbol IN LISTS MAPLESU_PARASITE_SYMBOLS)
    target_link_options(maplesu_ultra_bridge PRIVATE "-Wl,--undefined=${symbol}")
endforeach()
target_link_libraries(maplesu_ultra_bridge PRIVATE maplesu_sdk_source_exports maplesu_ultra_sdk z log dl)

# No root or installed Ultra environment required: exercises the production watchdog
# against normal, hung and crashing cleanup fixtures without touching real modules.
add_executable(maplesu-ultra-uninstall-test EXCLUDE_FROM_ALL
    tests/ultra_uninstall_watchdog_test.cpp
    tests/ultra_uninstall_callback_fixture.cpp
    ultra/bridge/uninstall_callback_watchdog.cpp)
target_include_directories(maplesu-ultra-uninstall-test PRIVATE "${MAPLESU_ULTRA_SDK_DIRECTORY}/include")
target_compile_features(maplesu-ultra-uninstall-test PRIVATE cxx_std_20)
target_compile_options(maplesu-ultra-uninstall-test PRIVATE -Wall -Wextra -Werror -fexceptions)
target_compile_definitions(maplesu-ultra-uninstall-test PRIVATE MAPLESU_UNINSTALL_CALLBACK_TIMEOUT_MS=100)
target_link_options(maplesu-ultra-uninstall-test PRIVATE -Wl,--wrap=${MAPLESU_UNINSTALL_CALLBACK_SYMBOL})
target_link_libraries(maplesu-ultra-uninstall-test PRIVATE log)

add_executable(maplesu-ultra-webui-test EXCLUDE_FROM_ALL
    tests/ultra_webui_lifecycle_test.cpp ultra/bridge/webui_lifecycle.cpp)
target_include_directories(maplesu-ultra-webui-test PRIVATE "${MAPLESU_ULTRA_SDK_DIRECTORY}/include" ultra/bridge)
target_compile_features(maplesu-ultra-webui-test PRIVATE cxx_std_20)
target_compile_options(maplesu-ultra-webui-test PRIVATE -Wall -Wextra -Werror -fexceptions)

# Read-only device probe: prints the SDK's self-declared version and the host's
# SELinux/seccomp state. Activates nothing, needs no root key.
add_executable(maplesu-ultra-version ultra/probe/ultra_version_probe.cpp)
target_compile_features(maplesu-ultra-version PRIVATE cxx_std_20)
target_compile_options(maplesu-ultra-version PRIVATE -ffunction-sections -fdata-sections "$<$<CONFIG:Release>:-g0>")
target_link_options(maplesu-ultra-version PRIVATE
    -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,relro,-z,now)
target_link_libraries(maplesu-ultra-version PRIVATE maplesu_sdk_source_exports maplesu_ultra_sdk z log dl)

# Read-only module archive probe: asks the real SDK to parse a module ZIP's descriptor.
# Activates nothing and installs nothing; reports the SDK's exact error when it refuses.
add_executable(maplesu-ultra-module ultra/probe/ultra_module_probe.cpp)
target_compile_features(maplesu-ultra-module PRIVATE cxx_std_20)
target_compile_options(maplesu-ultra-module PRIVATE -ffunction-sections -fdata-sections "$<$<CONFIG:Release>:-g0>")
target_link_options(maplesu-ultra-module PRIVATE
    -Wl,--no-undefined -Wl,--gc-sections -Wl,-z,relro,-z,now)
target_link_libraries(maplesu-ultra-module PRIVATE maplesu_sdk_source_exports maplesu_ultra_sdk z log dl)

# NDK r26 defaults to 4 KB; all packaged runtime entries also load on 16 KB pages.
foreach(target maplesu-runtime maplesu-su maplesu_bootstrap maplesu_parasite maplesu_ultra_bridge)
    target_link_options(${target} PRIVATE -Wl,-z,max-page-size=16384,-z,common-page-size=16384)
endforeach()
