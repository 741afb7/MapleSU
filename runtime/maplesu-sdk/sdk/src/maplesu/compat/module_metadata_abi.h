#pragma once
#include <cstddef>
#define module_desc public_packed_module_desc
#define module_record public_packed_module_record
#include "kernel_module_kit_umbrella.h"
#undef module_desc
#undef module_record

namespace maplesu::sdk {
struct module_desc {
    char name[1024] = {}, version[1024] = {}, desc[4096] = {}, author[1024] = {};
    char id32[1024] = {}, update_json[1024] = {};
    bool web_ui = false;
    MapleSUSdkVersion min_sdk_ver = {};
};
struct module_record { module_desc desc; ModuleRunState state = ModuleRunState::NotRunning; };
static_assert(sizeof(module_desc) == 9232 && alignof(module_desc) == 4);
static_assert(offsetof(module_desc, name) == 0);
static_assert(offsetof(module_desc, version) == 1024);
static_assert(offsetof(module_desc, desc) == 2048);
static_assert(offsetof(module_desc, author) == 6144);
static_assert(offsetof(module_desc, id32) == 7168);
static_assert(offsetof(module_desc, update_json) == 8192);
static_assert(offsetof(module_desc, web_ui) == 9216);
static_assert(offsetof(module_desc, min_sdk_ver) == 9220);
static_assert(sizeof(MapleSUSdkVersion) == 12 && alignof(MapleSUSdkVersion) == 4);
static_assert(sizeof(module_record) == 9236 && alignof(module_record) == 4);
static_assert(offsetof(module_record, desc) == 0 && offsetof(module_record, state) == 9232);
static_assert(sizeof(ModuleRunState) == 4);
}
