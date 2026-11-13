# ForceRemove — User Guide

ForceRemove is a Niagara Program Object that bulk-removes components, points or
extensions, including the ones Workbench won't delete the normal way. It backs up
everything before deleting it, so the last run can be reversed.

Covers **N4 v3.3 / v3.4** and **AX v3.3-AX** (Niagara AX 3.5–3.8). The fields and
actions are the same on both; see [AX notes](#ax-notes).

---

## Contents

1. [How it works in one minute](#1-how-it-works-in-one-minute)
2. [Every field, explained](#2-every-field-explained)
3. [The three modes](#3-the-three-modes)
4. [Actions](#4-actions)
5. [What it never deletes](#5-what-it-never-deletes)
6. [Files it writes](#6-files-it-writes)
7. [Complete setups (recipes)](#7-complete-setups)
8. [Results CSV: what each status means](#8-results-csv)
9. [Good habits](#9-good-habits)
10. [AX notes](#ax-notes)

---

## 1. How it works in one minute

```
 you set:  operationMode + targetOrd (or targetList) + filters
              │
   Dry Run ──►  list what WOULD be deleted  ──►  log + results CSV   (no changes)
   Execute ──►  for each target:  back it up into ForceRemoveBackup
                                  then delete it                      ──►  log + results CSV
   Reverse ──►  put back everything the last Execute deleted, with its links
```

- It only runs when you press an action — nothing happens on its own.
- **Back up the station (.dist) and run Dry Run first, every time.**
- Every deleted item is copied into the `ForceRemoveBackup` folder first. If the copy
  fails, that item is **not** deleted.

---

## 2. Every field, explained

Legend: **Set** = you set it · **Out** = the program writes it (read only).

### About

| Field | | What it is |
|---|---|---|
| `version` | Out | The program version, e.g. `v3.4`. |
| `quickGuide` | Out | Short built-in help. |

### Last run

| Field | | What it is |
|---|---|---|
| `status` | Out | Live progress while it runs, then the summary: how many removed, skipped, failed, and where the log is. A refused path (outside the station folder) is reported here. |

### What to remove

| Field | | Default | What it is |
|---|---|---|---|
| `operationMode` | Set | BQL | Drop-down **Direct / BQL / CSV** — how the list of targets is built. See [the three modes](#3-the-three-modes). |
| `targetOrd` | Set | blank | Direct: the component to remove (and everything matching inside it). BQL: the query. CSV: the CSV file. **Leave it blank in a template bog.** |
| `targetList` | Set | blank | Direct mode only: several targets, one ord per line; lines starting with `#` are ignored. Used instead of `targetOrd` when it isn't empty. |
| `nameFilter` | Set | blank | Only remove items whose name **contains** this text (case-sensitive, no wildcards). Blank (or `*`) = all. |
| `typeFilter` | Set | blank | Only remove items of exactly this type, `module:Type`, e.g. `control:NumericWritable` or `alarm:AlarmSourceExt`. Blank (or `*`) = all. Never applied to folders. |

Ords can use names as Workbench shows them: `slot:/Drivers/BacnetNetwork/VAV X-01`
is escaped for you (`VAV$20X$2d01`).

### Files and logs

| Field | | Default (blank =) | What it is |
|---|---|---|---|
| `logFilePath` | Set | `file:^logs/ForceRemove.log` | Plain-text log of every step. |
| `resultsCsvPath` | Set | `file:^logs/ForceRemove_results.csv` | One row per item: what happened and why. Opens in Excel. |
| `sampleCsvPath` | Set | `file:^logs/ForceRemove_SAMPLE.csv` | Where **Create Sample CSV** writes a starter CSV. |
| `maxArchives` | Set | 10 | How many old copies of the log and results CSV to keep. 0 = keep all. |

All paths must be inside the station folder (`file:^…`); anything outside is refused.

### Backups (reverse)

| Field | What it is |
|---|---|
| `ForceRemoveBackup` | A folder inside the program holding copies of what the last Execute deleted. Emptied at the start of each Execute, so **Reverse only restores the most recent run**. Don't put anything else in it. |

---

## 3. The three modes

| Mode | `targetOrd` holds | What gets removed |
|---|---|---|
| **Direct** | a component (or use `targetList` for several) | Everything inside it that matches the filters, **then the target itself**. Point it at one point to remove just that point. |
| **BQL** | a BQL query | Every row the query returns. |
| **CSV** | a CSV file | Every ord listed in the file. |

**Filters in each mode**
- **Direct** — this is what the filters are for. Point at a folder and set
  `typeFilter` = `alarm:AlarmSourceExt` to remove only the alarm extensions under it.
- **BQL / CSV** — the query or file already chooses the targets, so leave both filters
  blank. A filter here acts as a safety net: a row that doesn't match is **SKIPPED**
  (and listed), not removed.

**Order:** BQL and CSV remove the shallowest items first (a folder before what's inside
it); items already removed with their folder are skipped quietly. Direct with no
filters removes the target as one unit; with a filter it works from the bottom up. For
deep folder cleanups, run Execute a second time to collapse folders that became empty.

**CSV format** — one column, header row `Ord`:

```
Ord
station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points/OldPoint
station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_102/points/OldPoint
```

---

## 4. Actions

Right-click the program → **Actions**.

| Action (slot) | What it does |
|---|---|
| **Execute** (`execute`) | Run for real: back up, then delete. |
| **Dry Run** (`dryRun`) | A preview: logs and lists every item as `DRYRUN` exactly as Execute would, but deletes nothing, backs up nothing and changes no links. |
| **Reverse** (`reverse`) | Put back everything the last Execute removed: copies each backup to where it was and remakes its links (under their original names). Links whose other end no longer exists are skipped and logged `REVERSE LINK FAILED`. History records that were archived are **not** restored. |
| **Cancel** (`cancel`) | Stop after the item currently being removed. |
| **Create Sample CSV** (`createSampleCsv`) | Write a starter one-column CSV to `sampleCsvPath`. |
| **Prune Archives** (`pruneArchives`) | Apply `maxArchives` to the old logs right now. |

---

## 5. What it never deletes

In every mode, Dry Run included, these are logged `SKIPPED (PROTECTED)` instead:

- the station root
- the Services tree
- the `/Drivers` container itself (its contents can be removed)
- this program and its `ForceRemoveBackup` folder
- the folder the program sits in, **and everything in that folder** (sibling programs)

If the program can't work out where it is, it deletes nothing.

---

## 6. Files it writes

All under the station's `logs` folder unless you change the paths:

| File | Contents |
|---|---|
| `ForceRemove.log` | Every step, readable. |
| `ForceRemove_results.csv` | Columns: Timestamp, TargetName, TargetSlotPath, ExtName, ExtType, Status, Message, Mode, DurationMs. |
| `ForceRemove_reverse_manifest.csv` | What was backed up and where it came from (used by Reverse). |
| `ForceRemove_reverse_links.csv` | The links each removed item had (used by Reverse). |
| `ForceRemove_<date>_<time>.log` etc. | Archived copies from earlier runs (`maxArchives` kept). |

Lines also go to the Application Director (info / warning / severe).

---

## 7. Complete setups

Placeholders throughout — use your own paths.

### A. Remove one device's points folder (Direct)

| Field | Value |
|---|---|
| operationMode | Direct |
| targetOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points` |
| nameFilter / typeFilter | blank |

Dry Run → check the results CSV → Execute. The folder and everything inside it goes in
one unit, and is backed up in one copy.

### B. Strip alarm extensions off the points under a folder, keep the points (Direct)

| Field | Value |
|---|---|
| operationMode | Direct |
| targetOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points` |
| typeFilter | `alarm:AlarmSourceExt` |

Only the alarm extensions go; the points stay.

### C. Several devices at once (Direct with targetList)

```
# targetList
station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101
station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_102
# station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_103   (skipped - commented out)
```

### D. Remove every point named TEST* in the drivers (BQL)

| Field | Value |
|---|---|
| operationMode | BQL |
| targetOrd | `station:\|slot:/Drivers\|bql:select * from control:ControlPoint where name like 'TEST*'` |
| filters | blank |

Never use a whole-station query such as `select * from baja:Component` — Execute would
hit every unprotected component.

### E. Remove a list from a spreadsheet (CSV)

1. **Create Sample CSV**, open `logs/ForceRemove_SAMPLE.csv`, put one ord per row under `Ord`.
2. Save it, e.g. as `Csv/RemoveList.csv` in the station's Files.
3. `operationMode` = CSV, `targetOrd` = `file:^Csv/RemoveList.csv`, filters blank.
4. Dry Run → Execute.

### F. Undo

Press **Reverse** before the next Execute (the next Execute empties the backup folder).

---

## 8. Results CSV

| Status | Meaning |
|---|---|
| `REMOVED` | Backed up and deleted. |
| `DRYRUN` | Dry Run: would be deleted. |
| `SKIPPED` | Not deleted — the Message says why: protected, doesn't match the filters, already gone with its folder, or **backup failed - delete skipped to avoid data loss**. |
| `ERROR` | The delete was tried and failed; Message has the reason. |

---

## 9. Good habits

- Back up the station, Dry Run, read the results CSV, **then** Execute.
- Keep `targetOrd` blank in the copy you keep as a template.
- Put ForceRemove in its own folder (everything beside it is protected).
- Reverse straight away if something went wrong — the backup only holds the last run.

---

## AX notes

- Program name **ForceRemove4AX**, code `ForceRemove4AX_v3.3.java`; works on AX 3.5–3.8.
- Same slots and actions. No imports to add (the AX code uses full class names).
- Display names on the AX slot sheet: Dry Run - No Changes, Reverse Removal, Cancel
  Request, Remove Old Logs, Create Sample CSV; `maxArchives` is limited to 0–10.
- The AX version doesn't sort its own property sheet (that's N4 v3.4).
