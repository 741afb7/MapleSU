"""Select the current claim implementation without historical source overlays.

The receipt freezes real source and tool bytes. ABI, BTF, CFI, relocation and
retirement qualification remain in the claim builder and run on fresh objects.
"""
from pathlib import Path
import importlib.util
import json
import sys


SCHEMA = "maplesu-claim-current-source-selection/1"
LOCAL_ONLY_SU_ENTRYPOINTS = {
    "msu_su_registry_revoke_package": "Current installation events revoke the protected full UID with msu_su_registry_revoke",
    "msu_su_apply_current": "Current factory applies its prepared msu_su_exec_session through msu_su_apply_held_detailed",
    "msu_su_lease_saved_profile": "Saved profile metadata remains local; the current execution consumer uses msu_su_lease_profile",
    "msu_su_apply_current_detailed": "Only the unused msu_su_apply_current wrapper calls this entry; current sessions use the held entry",
}


def portable_identity(builder, path):
    return dict(builder.identity(path), path=path.resolve().relative_to(builder.ROOT.resolve()).as_posix())


def bridge_inputs(root):
    bridge = root / "runtime/ultra/boot/init-bridge"
    kernel = root / "runtime/ultra/identity/kernel"
    return [
        *(path for path in sorted(bridge.iterdir(), key=lambda path: path.name)
          if path.suffix in (".c", ".h") or path.name in ("Kbuild", "preinit_blob.S")),
        bridge / "preinit/runner.c",
        *sorted((root / "third_party/kernelsu/upstream/kernel/preinit/runner/nolibc").rglob("*.h"),
                key=lambda path: path.relative_to(root).as_posix()),
        *(kernel / (unit + suffix) for unit in
          ("manager_runtime_btf", "manager_runtime_accessor", "manager_task_profile_runtime")
          for suffix in (".c", ".h")),
        kernel / "manager_task_profile_workbit.h",
        *(root / "jailbreak-providers/root-loader" / name for name in
          ("module/maplesu_boot_sdk.c", "module/boot_sdk_call.S", "module/boot_sdk_protocol.h",
           "module/boot_sdk_allocator.h", "boot_sdk_abi.h")),
    ]


def selected_tools(builder):
    bridge = builder.ROOT / "runtime/ultra/boot/init-bridge"
    return list(dict.fromkeys([*builder.BUILD_TOOLS,
        *(path for path in sorted(bridge.glob("*.py"), key=lambda path: path.name)
          if not path.name.startswith("test_"))]))


def selected_inputs(builder):
    root = builder.ROOT
    native_root = root / "runtime/ultra/identity/kernel"
    extra = (
        "manager_task_state.c", "manager_task_state.h", "manager_credential_domain.c",
        "manager_credential_domain.h", "manager_credential_profile.h",
        "manager_deployment_namespace.h", "manager_early_helper.h",
        "manager_su_runtime_target_profile.h",
    )
    native = list(dict.fromkeys([
        *(native_root / item.name for item in builder.CORE_BASE_SOURCES),
        *(native_root / name for name in (*extra, *builder.HOST_RUNTIME_NATIVE_BASE)),
        *builder.RUNTIME_ADDITIONAL_INPUTS.values(),
    ]))
    support = [*(native_root / item.name for item in builder.CORE_BASE_SUPPORT_SOURCES),
               native_root / "manager_deployment_namespace.h"]
    su = [root / builder.SU_COMPONENT_ROOT / (unit + suffix)
          for unit in builder.SU_COMPONENT_OBJECTS for suffix in (".c", ".h")]
    groups = dict(native=native, support=support, su=su,
                  private=list(builder.PUBLICATION_PRIVATE_HEADERS),
                  protocol=[root / "runtime/ultra/authorization/request_protocol.h"],
                  frame=[root / "runtime/ultra/authorization/execution_frame.h"])
    available = list(dict.fromkeys(path for paths in groups.values() for path in paths))
    known, closure = builder.runtime_local_include_closure(available, available)
    closure = [dict(row, **{key: portable_identity(builder, Path(row[key]["path"]))
                           for key in ("source", "header")}) for row in closure]
    native.extend(path for path in known.values() if path not in available)
    # These are bridge-only build inputs, not extra raw-claim translation units.
    groups["bridge"] = bridge_inputs(root)
    if any(not path.is_file() or not path.resolve().is_relative_to(root.resolve())
           for paths in groups.values() for path in paths):
        raise ValueError("Current claim selection contains a missing/outside source")
    rows = [dict(role=role, logicalPath=path.relative_to(root).as_posix(),
                 before=None, after=portable_identity(builder, path))
            for role, paths in groups.items() for path in paths]
    return groups, rows, closure


def freeze_current_sources(builder, path):
    _, rows, closure = selected_inputs(builder)
    record = dict(schema=SCHEMA, **builder.claim_release_version(), rootKeyRequired=False,
                  sourceOwnerFrozen=True, targetQualified=False, files=rows,
                  additionalObjects=list(builder.RUNTIME_ADDITIONAL_OBJECTS),
                  tools=[portable_identity(builder, item) for item in selected_tools(builder)],
                  includeClosure=closure)
    path = path.resolve()
    if not path.is_relative_to(builder.ROOT.resolve()):
        raise ValueError("Current claim receipt must be inside the workspace")
    if path.exists():
        raise ValueError("Current claim receipt already exists; freeze to a new generation")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8", newline="\n")
    return builder.identity(path)


def _layout_facts(builder, prefix, kind, members, facts, previous_members):
    for member in previous_members:
        key = prefix + "".join(word.capitalize() for word in member.split("_"))
        facts.pop(key + "Offset", None)
        facts.pop(key + "Bytes", None)
    facts.pop(prefix + "Bytes", None)
    facts.pop(prefix + "Align", None)
    facts[prefix + "Bytes"] = "sizeof(struct " + kind + ")"
    facts[prefix + "Align"] = "__alignof__(struct " + kind + ")"
    for member in members:
        key = prefix + "".join(word.capitalize() for word in member.split("_"))
        facts[key + "Offset"] = "offsetof(struct " + kind + ", " + member + ")"
        facts[key + "Bytes"] = "sizeof(((struct " + kind + " *)0)->" + member + ")"


def validate_current_sources(builder, path, expected_sha256):
    path = path.resolve()
    if not path.is_relative_to(builder.ROOT.resolve()) or builder.sha(path) != expected_sha256:
        raise ValueError("Current claim source selection identity/mode mismatch")
    selected = json.loads(path.read_text(encoding="utf-8-sig"))
    groups, rows, closure = selected_inputs(builder)
    expected = dict(schema=SCHEMA, **builder.claim_release_version(), rootKeyRequired=False,
                    sourceOwnerFrozen=True, targetQualified=False, files=rows,
                    additionalObjects=list(builder.RUNTIME_ADDITIONAL_OBJECTS),
                    tools=[portable_identity(builder, item) for item in selected_tools(builder)],
                    includeClosure=closure)
    if selected != expected:
        fields = [key for key in expected if selected.get(key) != expected[key]]
        raise ValueError("Current claim source, tool or include closure changed since freeze: " + ",".join(fields))
    return selected, groups, rows, closure


def read_current_source_selection(repo, path, expected_sha256):
    root = Path(__file__).resolve().parents[1]
    if repo.resolve() != root:
        raise ValueError("Current claim repository mismatch")
    name = "_maplesu_current_claim_for_bridge"
    spec = importlib.util.spec_from_file_location(name, root / "scripts/build-ultra-manager-claim.py")
    builder = importlib.util.module_from_spec(spec)
    sys.modules[name] = builder
    sys.path.insert(0, str(root / "scripts"))
    try:
        spec.loader.exec_module(builder)
    finally:
        sys.path.pop(0)
    return validate_current_sources(builder, path, expected_sha256)[0]


def select_current_sources(builder, path, expected_sha256):
    if builder.CURRENT_SOURCE_SELECTION is not None or builder.CORE_SELECTION is not None:
        raise ValueError("Current claim source selection identity/mode mismatch")
    selected, groups, rows, closure = validate_current_sources(builder, path, expected_sha256)
    # Existing ABI emitters consume local identities; the frozen receipt stays
    # repository-relative so the same bytes validate under a container mount.
    rows = [dict(row, after=builder.identity(builder.ROOT / row["after"]["path"])) for row in rows]
    closure = [dict(row, **{key: builder.identity(builder.ROOT / row[key]["path"])
                           for key in ("source", "header")}) for row in closure]
    marker = dict(schema=SCHEMA, identity=builder.identity(path), targetQualified=False)
    builder.CURRENT_SOURCE_SELECTION = dict(selected, identity=marker["identity"])
    builder.SOURCE = builder.ROOT / "runtime/ultra/identity/kernel"
    builder.SOURCES, builder.SUPPORT_SOURCES = groups["native"], groups["support"]
    builder.PUBLICATION_PRIVATE_HEADERS = groups["private"]
    builder.PROTOCOL, builder.FRAME = groups["protocol"][0], groups["frame"][0]
    builder.RUNTIME_SU_SOURCE_PATHS = groups["su"]
    native_rows = [dict(row, snapshotIdentity=row["after"]) for row in rows if row["role"] == "native"]
    su_rows = [dict(row, snapshotIdentity=row["after"]) for row in rows if row["role"] == "su"]
    builder.CORE_SELECTION = dict(marker, lifecycleRevision=2, files=native_rows, nativeInputs=native_rows)
    builder.SU_COMPONENT_SELECTION = dict(marker, files=su_rows, readerBindingHistory=dict(
        sourceIdentity=builder.identity(builder.ROOT / builder.SU_NATIVE_LOGICAL_ROOT / "su_native_reader_abi.h")))
    builder.SU_SELECTION = builder.SU_COMPONENT_SELECTION
    builder.SU_COMPONENT_METADATA_SELECTION = dict(marker, files=su_rows)
    builder.HOST_DEPLOYMENT_SELECTION = dict(marker, runtimeChannel="current-authenticated-installation")
    builder.CORE_RUNTIME_SELECTION = dict(marker)
    builder.HOST_DURABLE_SELECTION = dict(marker)
    builder.HOST_INITIALIZATION_SELECTION = dict(marker, kernelProfile="android17-6.18")
    builder.CORE_CURRENT_TASK_SELECTION = dict(marker)
    builder.HOST_PUBLICATION_SELECTION = dict(marker)
    builder.HOST_SYNC_SELECTION = dict(marker)
    builder.HOST_RETIREMENT_SELECTION = dict(marker, files=[row for row in rows
        if row["role"] == "support" and Path(row["logicalPath"]).name in
        ("manager_claim_bootstrap.c", "manager_resident_abi.h")])
    builder.RUNTIME_LAYOUT_SELECTION = dict(marker, files=rows, includeClosure=closure,
        additionalObjects=list(builder.RUNTIME_ADDITIONAL_OBJECTS))
    builder.TASK_PROFILE_SELECTION = dict(marker, parentRuntimeLayoutSelection=marker["identity"],
        files=[row for row in native_rows if Path(row["logicalPath"]).name in builder.TASK_PROFILE_TRACE_SOURCES])

    # Use the current structures to emit the existing independent native probes.
    builder.core_revision_metadata(2)
    core = builder.selected_native_source("manager_management.c").read_text(encoding="utf-8-sig")
    for prefix, kind in (("service", "msu_runtime_service"), ("session", "msu_management_session"),
                         ("publication", "msu_publication"), ("sharedBool", "msu_shared_bool")):
        members = builder._su_selected_struct_members(core, kind)
        previous_members = builder.CORE_LAYOUT_TYPES.get(prefix, (None, None, ()))[2]
        builder.CORE_LAYOUT_TYPES[prefix] = (kind, "manager_core_facts", members)
        _layout_facts(builder, prefix, kind, members, builder.CORE_FACTS, previous_members)
    builder.apply_su_component_metadata_abi()
    builder.SU_COMPONENT_FUNCTIONS["su_profile_execution"] = (
        *builder.SU_COMPONENT_FUNCTIONS["su_profile_execution"], "msu_su_apply_held_detailed")
    execution = (builder.ROOT / builder.SU_COMPONENT_ROOT / "su_profile_execution.h").read_text(encoding="utf-8-sig")
    members = builder._su_selected_struct_members(execution, "msu_su_execution_receipt")
    previous_members = builder.SU_COMPONENT_LAYOUTS["executionReceipt"][2]
    builder.SU_COMPONENT_LAYOUTS["executionReceipt"] = (
        "msu_su_execution_receipt", "manager_su_component_facts", members)
    _layout_facts(builder, "executionReceipt", "msu_su_execution_receipt", members,
                  builder.SU_COMPONENT_FACTS, previous_members)
    builder.SU_COMPONENT_RELOCATIONS["su_profile_execution"] = {
        "msu_su_apply_current_detailed": ("msu_su_lease_begin", "msu_su_apply_held_stages", "msu_su_lease_end"),
        "msu_su_apply_held_detailed": ("msu_su_lease_validate", "msu_su_apply_held_stages"),
        "msu_su_apply_held_stages": ("msu_su_profile_apply_held", "msu_task_state_release_current",
            "msu_task_profile_apply_current", "msu_su_lease_validate", "msu_su_profile_namespace_apply_held"),
    }
    builder.HOST_RUNTIME_EXTRA_FUNCTIONS["manager_su_runtime_target"] = tuple(dict.fromkeys((
        *builder.HOST_RUNTIME_EXTRA_FUNCTIONS["manager_su_runtime_target"], "msu_su_runtime_target_bind")))
    builder.WIRE_FACTS = dict(builder.CORE_BASE_WIRE_FACTS,
        ownerCapabilities=("MAPLESU_OWNER_CAPABILITIES", 262119), queryViews=("5", 5),
        taskStateCommand=("MAPLESU_REQUEST_TASK_STATE", 26), taskStateCapability=("MAPLESU_CAP_TASK_STATE", 131072),
        taskQueryArg0=("MAPLESU_OWNER_TASK_QUERY", 260096),
        taskStateChangedEffect=("MAPLESU_EFFECT_TASK_STATE_CHANGED", 131072),
        taskStateCompletedEffect=("MAPLESU_EFFECT_TASK_STATE_COMPLETED", 262144),
        suRuntimeReadCommand=("MAPLESU_REQUEST_SU_RUNTIME_READ", 30),
        suRuntimeStatusBytes=("sizeof(maplesu_su_runtime_status_v3)", 104),
        suRuntimeStatusAlign=("__alignof__(maplesu_su_runtime_status_v3)", 8))
    builder.WIRE_FACTS.update(builder.HOST_RUNTIME_WIRE_FACTS)
    fields = dict(schema=0, bytes=4, host=8, installation_generation=24, runtime_generation=40,
                  installation_fence=56, flags=64, users=68, producers=72, hook_count=76,
                  status=80, target_error=84, cleanup_error=88, start_error=92,
                  target_qualified=96, installation_flags=100)
    for member, offset in fields.items():
        key = "suRuntimeStatus" + "".join(word.capitalize() for word in member.split("_")) + "Offset"
        builder.WIRE_FACTS[key] = ("offsetof(maplesu_su_runtime_status_v3, " + member + ")", offset)
    builder.EXPORTS = tuple(dict.fromkeys((*builder.CORE_BASE_EXPORTS, *builder.CORE_LAYOUT_EXPORTS,
        "msu_su_runtime_post_fs_data_event", "msu_su_runtime_refresh", "msu_su_runtime_sync",
        "msu_su_observed_refresh", "msu_runtime_layout", "msu_su_session_abi_words",
        "msu_boot_events_abi_words", "msu_ksu_compat_layout", *builder.task_profile_exports(),
        *(("msu_su_runtime_profiles",) if builder.runtime_profiles_enabled() else ()))))
    return builder.CURRENT_SOURCE_SELECTION


def public_selection(builder):
    selected = builder.CURRENT_SOURCE_SELECTION
    return dict(schema=SCHEMA, identity=selected["identity"], sourceOwnerFrozen=True,
                targetQualified=False, rootKeyRequired=False, files=selected["files"])


def source_evolution(builder):
    selected = public_selection(builder)
    return dict(schema=SCHEMA, selectionIdentity=selected["identity"], targetQualified=False,
                selectedInputs=[dict(path=row["logicalPath"], after=row["after"],
                                     nativeCopyIdentity=row["after"])
                                for row in selected["files"]])


def build_inputs(builder):
    selected = builder.CURRENT_SOURCE_SELECTION
    return list(dict.fromkeys([Path(selected["identity"]["path"]),
        *(builder.ROOT / row["after"]["path"] for row in selected["files"]),
        *builder.PUBLICATION_GATE_INPUTS, *selected_tools(builder), *builder.RAW_REFERENCE_INPUTS,
        builder.ROOT / "manager/build.gradle.kts"]))
