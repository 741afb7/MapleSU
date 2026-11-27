# `helper/autorun_bootstrap`

The selected helper authenticates its installed executable through the existing
EarlyHelper lease. It reads the public deployment namespace from the authenticated
installation record and requires its own path to match `/data/<namespace16>/init`.
There is no embedded RootKey, token slot or installer byte substitution.

Boot/session receipts, safe mode and exact original-target argv/env handoff retain
their existing semantics. One existing boot worker owns each phase's session:
fixed early operations use the helper lease; generic kernel work uses a fresh
session after the same task has actually become Root. Every mode change drains
and destroys the old storage/session scope before creating a new one. This adds
no process, daemon, polling loop or ordinary-application probe.

`manifest.json` lists the selected production units. `tools/build_helper.py`
requires the SDK archive and paired backend from the same source generation.
The only dynamic exports remain `maplesu_helper_su_exec` and
`maplesu_helper_su_write`; both take module identity without a Key argument.

Focused validation commands:

```powershell
py -3 runtime/maplesu-sdk/sdk/tools/tests/p6_boot/run.py --out OUTPUT_DIRECTORY
py -3 runtime/maplesu-sdk/sdk/tools/tests/p6_boot/run_single_close.py --out NEW_OUTPUT_DIRECTORY
py -3 runtime/tests/ultra_exec_protocol_test.py --out OUTPUT_DIRECTORY
```

These are source/host/fixture checks. They do not establish a built release,
Android boot, no-Manager cold boot, or device side-channel behavior. Historical
hashes and pre-keyless fixture counts belong to their original evidence records.
