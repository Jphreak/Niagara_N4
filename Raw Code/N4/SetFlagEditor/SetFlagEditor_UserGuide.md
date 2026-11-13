# SetFlagEditor — User Guide

SetFlagEditor is the companion to [SetFlagAudit](../SetFlagAudit/SetFlagAudit_UserGuide.md). It uses the
same search, then **adds, removes or resets slot flags** (operator, hidden,
confirmRequired…) on every slot it finds — e.g. take `operator` off every setpoint's
`set` action. Dry Run previews it; Reverse undoes the last live run.

Covers **N4 v1.01 – v1.03**.

---

## Contents

1. [How it works in one minute](#1-how-it-works-in-one-minute)
2. [Every field, explained](#2-every-field-explained)
3. [Actions](#3-actions)
4. [Reverse in detail](#4-reverse-in-detail)
5. [Reading the results and the CSV](#5-reading-the-results-and-the-csv)
6. [Complete setups (recipes)](#6-complete-setups)
7. [Cautions](#7-cautions)
8. [Problems and fixes](#8-problems-and-fixes)

---

## 1. How it works in one minute

```
 search (same as SetFlagAudit): rootOrd + slotNames + nameFilter + requireFlags
   └─► each matched slot:
         new flags = (flags now + Flags to Add) − Flags to Remove
         or, with Reset to Default: the type's default flags
   Dry Run  ──► shows Before → After, writes nothing
   Execute  ──► writes, reads back each one, saves a reverse record
   Reverse  ──► puts the last live run back
```

- **Always Dry Run first.** Then Execute. Then **save the station** — the changes are
  only kept once the station is saved.
- Leave **Execute On Change off** on every slot.
- This program and everything inside it are never edited.

---

## 2. Every field, explained

Legend: **Set** = you set it · **Out** = the program writes it (read only).
Display names are in brackets.

### About

| Field | | What it is |
|---|---|---|
| `version` (Version) | Out | The program version. |
| `quickGuide` | Out | Short built-in help. |

### Last run

| Field | | What it is |
|---|---|---|
| `status` (Status) | Out | Time, mode (LIVE / DRY RUN / REVERSE), counts, ms, CSV file. **`SAVE THE STATION`** after a live run that changed something. `MISSING SLOTS: …` if an output slot is missing. |
| `lastRun` (LastRun) | Out | When the last run finished. |
| `scanned` (Components Scanned) | Out | Components walked (0 after a Reverse). |
| `matched` (Slots Matched) | Out | Slots that passed the filters (after Reverse: rows in the record). |
| `changed` (Slots Changed) | Out | Slots written (Dry Run: that would be; Reverse: put back). |
| `summary` (Summary) | Out | e.g. `LIVE \| Slots Matched as per filters: 60 \| Changed: 15 \| No change: 45 \| Failed: 0`. Dry Run says "Would change". |
| `results` (Results) | Out | One line per slot: `name.slot : before -> after [result]`. |

### What to search

Exactly as in SetFlagAudit:

| Field | | Default | What it is |
|---|---|---|---|
| `rootOrd` (Root Location or BQL) | Set | `station:\|slot:/Drivers` | A component (walks everything under it) or a BQL/NEQL query (each row walked as its own root). |
| `slotNames` (Slot Name(s)) | Set | `set` | Comma list of actions or properties, e.g. `set, fallback`. Blank = `set`. |
| `nameFilter` (Filter by Name (Blank = All)) | Set | blank | Component name contains this (not case-sensitive). |
| `requireFlags` (Flag required (Blank = All)) | Set | blank | Only edit slots that **already** have all of these flags. |
| `maxResults` (Max. Results) | Set | 0 | Caps CSV rows and `results` lines only — **every matched slot is still edited**. 0 = no CSV limit, 200 lines. |

### What to change

| Field | | Default | What it is |
|---|---|---|---|
| `addFlags` (Flags to Add) | Set | blank | Comma list of flags to turn on. |
| `removeFlags` (Flags to Remove) | Set | blank | Comma list of flags to turn off. |
| `resetToDefault` (Reset to Default) | Set | false | true = put each slot back to its type's default flags; Add / Remove are ignored. |

The run stops before changing anything if: a flag is in both Add and Remove, a flag name
is misspelled, or Add and Remove are both blank with Reset off.

Flag names (not case-sensitive): `readonly, transient, hidden, summary, async, noRun,
defaultOnClone, confirmRequired, operator, noAudit, composite, removeOnClone, metadata,
linkTarget, nonCritical, userDefined1 … userDefined4`.

### Output files

| Field | | Default | What it is |
|---|---|---|---|
| `csvFile` (Output CSV file) | Set | `file:^FlagEdits.csv` | A file (overwritten) or a folder (new `SetFlagEditor_<date>_<time>.csv` per run). **Use a folder to keep a history of every run.** Blank = no CSV. Inside the station folder only. |
| `maxArchives` (Max Archives) | Set | 10 | CSVs kept in folder mode, and old logs. 0 = keep all. |
| `logFilePath` (Log File) | Set | `file:^logs/SetFlagEditor.log` | A new log per run. |

---

## 3. Actions

| Action (slot) | What it does |
|---|---|
| **Dry Run** (`dryRun`) | Preview: every matched slot with Before → After and `WOULD CHANGE` / `NO CHANGE`. Writes nothing; doesn't touch the reverse record. |
| **Execute** (`execute`, built-in) | Make the changes. Each write is read back; one that didn't take is `FAILED`. |
| **Reverse** (`reverse`) | Undo the last live Execute (see below). |

---

## 4. Reverse in detail

- Each live Execute that changes at least one slot saves a reverse record:
  `logs/SetFlagEditor_<program name>_reverse.csv` (Path, Slot, BeforeHex, AfterHex).
  A live run that changes nothing keeps the previous record.
- Reverse puts each slot back to its before-flags, **but only if it still has exactly
  the after-flags** — anything edited since is `SKIPPED`.
- After a Reverse the record is renamed `…_reverse_reversed_<timestamp>.csv`, so it
  can't be applied twice (up to `maxArchives` of these are kept).
- **Only the last live run** can be reversed. For older runs, use the Before column of
  that run's CSV (folder mode keeps them all).
- Save the station after a Reverse.

---

## 5. Reading the results and the CSV

```
VAV_101_Flow_SP.set : operator -> (none)  [CHANGED]
VAV_101_Flow_SP.fallback : (none)  [NO CHANGE]
```

| CSV column | Contents |
|---|---|
| Name | Display name |
| Path | Slot path (usable as an ord) |
| Slot | Slot edited |
| Before | Flags before |
| After | Flags after (Dry Run: what they would be) |
| Result | `CHANGED` · `WOULD CHANGE` · `NO CHANGE` · `FAILED (reason)` · after Reverse: `REVERSED` · `SKIPPED` · `FAILED` |

---

## 6. Complete setups

Placeholders throughout. Run SetFlagAudit with the same search first to see what you're
about to change.

### A. Stop operators commanding VAV flow setpoints

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers\|bql:select * from control:NumericWritable where name like '*Flow_SP'` |
| slotNames | `set, fallback` |
| requireFlags | `operator` |
| removeFlags | `operator` |
| csvFile | `file:^reports/` |

Dry Run → check `changed` and the CSV → Execute → **save the station**.

### B. Ask for confirmation before `set` on one building's points

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_Site` |
| slotNames | `set` |
| addFlags | `confirmRequired` |

### C. Put everything back to normal

| Field | Value |
|---|---|
| rootOrd | the same search |
| resetToDefault | true |

Every matched slot goes back to its type's default flags.

### D. Test on one point first

Set `nameFilter` to one point's name, Dry Run, Execute, check it in Workbench, then clear
the filter for the full run.

---

## 7. Cautions

- `readonly` stops operators writing that slot.
- `hidden` removes it from the property sheet.
- `transient` on a property means its value is **not saved** in the bog.
- Test on one point (`nameFilter`) before a wide run, and keep `rootOrd` narrow on a
  large Supervisor — edits run on the action thread.

---

## 8. Problems and fixes

| Symptom | Fix |
|---|---|
| Run stops: flag in both lists / unknown flag / nothing to do | Fix Flags to Add / Remove (spelling, or set one of them, or turn on Reset). |
| Changes gone after a restart | The station wasn't saved after the run. |
| Reverse says SKIPPED | That slot was changed again after the run; fix it by hand from the CSV's Before column. |
| Reverse does nothing | No reverse record (already reversed, or the last live run changed nothing). |
| `FAILED` rows | The write didn't take — Message / Result has the reason. |
| Compile error on a Flags constant | Delete that entry from **both** `FLAG_NAMES` and `FLAG_VALUES` at the same position. |
