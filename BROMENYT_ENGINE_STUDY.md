# BROMENYT ENGINE STUDY — Evidence Extraction

Baseline source:
- File: `Bromenyt.Free.Script.lua`
- Format: standard Lua 5.2 binary chunk
- Size: ~29 KiB
- Chunk layout: little-endian, 4-byte int, 4-byte size_t, 4-byte instruction, 8-byte float
- Source/debug metadata is stripped.
- Disassembly: `bytecode-disassembly.txt`, 4762 lines.
- Main stack size: 150.
- Main prototype tree contains `main/f0` through `main/f4/f26` plus nested functions.

## Proven host/runtime boundary

At `main/f4` the bytecode accesses global `gg`.

The action-layer function `main/f4/f4` repeatedly performs a characteristic GameGuardian search/mutation sequence. Function names are obfuscated, so identities are mapped from opcode argument signatures:

| Obfuscated symbol | Strong mapping | Evidence |
|---|---|---|
| `ysdhey` | `gg.setVisible(false)`-like UI control | one boolean argument |
| `kzegdo` | `gg.searchNumber(... )`-like search | six search-shaped arguments: encoded text, type, false, sign, 0, -1 |
| `gevrae` | `gg.getResults(50000)`-like retrieval | single numeric argument 50000 |
| `yhuaij` | `gg.editAll(value, type)`-like mutation | two arguments, replacement + type |
| `umakwf` | `gg.clearResults()`-like reset | zero arguments |
| `safeExecute` | protected execution wrapper | calls `pcall` |
| `searchAndReplace` | reusable search/mutation helper | assigned closure in `main/f4/f15` |

The exact host implementation of every alias is not inferred beyond the argument-shape evidence above. Confirm further identities before using them as facts.

## Proven control architecture

```
bootstrap / decoder
    ↓
host/runtime binding
    ↓
configuration / expiry
    ↓
menu dispatch (main/f4/f2)
    ↓
mode dispatch (main/f4/f3)
    ↓
action routines (main/f4/f4 and following functions)
    ↓
host API mutation
```

Reliability infrastructure:

```
action/helper
    ↓
safeExecute
    ↓
pcall
    ↓
error/report path
```

## Reusable architectural lesson

The script's apparent complexity is dominated by obfuscation, encoded constants, dispatch names, and loader logic. The operational pattern exposed by the VM is much smaller:

```
observe/target
    ↓
select operation
    ↓
execute one host mutation sequence
    ↓
reset search state
```

A higher-quality engine for Splendor should add the part that still has to be proven in Bromenyt:

```
observe
 ↓
normalize state
 ↓
decide
 ↓
select ONE action
 ↓
validate target
 ↓
dispatch
 ↓
confirm acceptance
 ↓
observe next state
 ↓
verify expected transition
 ↓
recover/replan if needed
```

## Do NOT copy

These are not architectural requirements for Splendor:

- Lua bytecode obfuscation
- encoded function names
- expiry/license logic
- Bromenyt menu labels
- GameGuardian-specific memory-search semantics
- any anti-ban implementation
- arbitrary magic constants whose purpose is not proven

## Critical comparison

Bromenyt has a privileged host API capable of directly searching/mutating game-process memory. Splendor-Assist currently works through Android-visible capture, state extraction, decision/contribution, execution bus, and Accessibility gesture submission.

Therefore "equivalent effectiveness" must be defined structurally, not by copying the mechanism:

**A Splendor action is effective only when a valid observed state produces one authoritative action, the action reaches the real injection boundary without silent loss/duplication, and the next observation can verify whether the intended transition occurred.**

That is the engineering target for the next task queue.
