# Linker (LinkCreator) — User Guide

Linker is a Niagara Program Object that makes, checks or removes links in bulk: one
source to many targets, many sources to one target, or any list of pairs from a CSV.
Every link is checked after it's made, and Reverse undoes the last run.

The program calls itself **LinkCreator** in its logs and quickGuide. Covers **N4 v3.3 /
v3.4** and **AX v3.3-AX** (Niagara AX 3.5–3.8); see [AX notes](#ax-notes).

---

## Contents

1. [How it works in one minute](#1-how-it-works-in-one-minute)
2. [Every field, explained](#2-every-field-explained)
3. [The three modes](#3-the-three-modes)
4. [Actions](#4-actions)
5. [Files it writes](#5-files-it-writes)
6. [Complete setups (recipes)](#6-complete-setups)
7. [Results CSV: what each status means](#7-results-csv)
8. [Good habits](#8-good-habits)
9. [AX notes](#ax-notes)

---

## 1. How it works in one minute

```
 linkSource (one "ord,slot" per line)  ──►  Direct: targetOrd . targetSlot      (many → one)
                                       ──►  BQL:    each query row . targetSlot (many → many)
 CSV file of pairs                     ──►  CSV:    exactly the pairs listed
```

- Before a link is made, both slots must exist. After, it's read back; a link Niagara
  dropped or can't activate (wrong slot type, unresolved source, licence limit) is
  removed again and reported as `ERROR`, so you never end up with dead links.
- A link that already exists is skipped (no duplicates).
- Links are named `link_<source>_<slot>_to_<targetSlot>_<number>`. Component Copier and
  ForceRemove use the same names, so the three tools recognise each other's links.
- It only runs when you press an action. Dry Run and Verify make no changes.

---

## 2. Every field, explained

Legend: **Set** = you set it · **Out** = the program writes it (read only).
The display name (what the property sheet shows) is in brackets where it differs.

### About

| Field | | What it is |
|---|---|---|
| `version` | Out | The program version. |
| `quickGuide` | Out | Short built-in help. |

### Last run

| Field | | What it is |
|---|---|---|
| `status` | Out | Live progress, then the summary (linked, skipped, errors). |

### What to link

| Field | | Default | What it is |
|---|---|---|---|
| `operationMode` | Set | BQL | **Direct / BQL / CSV**. See [the three modes](#3-the-three-modes). |
| `sourceNote` (Note:) | Out | | A fixed reminder of the `linkSource` format, typed in once when the program is built. The code never changes it. |
| `linkSource` (Source) | Set | blank | Direct and BQL: the source(s), one `ord,slot` per line, e.g. `slot:/Test/BXXXXX_OAT,out`. No slot (just the ord) = `out`. `#` lines are ignored. Not used in CSV mode. |
| `targetOrd` (Target) | Set | blank | Direct: the target component. BQL: a query, each row a target. CSV: the CSV file of pairs. |
| `targetSlot` (Target Slot) | Set | blank | Direct and BQL: the slot on the target the link writes to, e.g. `in10`. |

Names can be typed as Workbench shows them (`VAV X-01` is escaped for you).

### Files and logs

| Field | | Default (blank =) | What it is |
|---|---|---|---|
| `logFilePath` | Set | `file:^logs/LinkCreator.log` | Step-by-step log. |
| `resultsCsvPath` | Set | `file:^logs/LinkCreator_results.csv` | One row per link. |
| `sampleCsvPath` | Set | `file:^logs/LinkCreator_SAMPLE.csv` | Where **Create Sample CSV** writes a starter CSV. |
| `maxArchives` (Max Archives to Keep) | Set | 10 | Old copies of the log / CSV kept (0–30; 0 = keep all). |

All paths must be inside the station folder (`file:^…`).

---

## 3. The three modes

### Direct — many sources → one target

```
linkSource:   slot:/Drivers/BacnetNetwork/BXXXXX_AHU_1/points/OAT,out
targetOrd:    station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points/Logic
targetSlot:   in10
```

Several source lines all link to the same `targetSlot`, so normally use one line in
Direct mode unless the target slot accepts several links.

### BQL — sources → every target the query finds

```
linkSource:   slot:/Drivers/BacnetNetwork/BXXXXX_AHU_1/points/OAT,out
targetOrd:    station:|slot:/Drivers|bql:select * from control:NumericWritable where name = 'OutsideAir'
targetSlot:   in10
```

Every `OutsideAir` point gets a link from the OAT into `in10`. Test the query in
Workbench first to see how many rows it returns.

### CSV — exactly the pairs you list

`targetOrd` = `file:^Csv/Links.csv`. Five columns, header row required:

```
BOrd1,Slot1,Direction,BOrd2,Slot2
station:|slot:/Drivers/BacnetNetwork/BXXXXX_AHU_1/points/OAT,out,>,station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points/Logic,in10
station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_102/points/Logic,out,<,station:|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_102/points/DamperCmd,in16
```

- **Direction** `>` = from 1 to 2 · `<` = from 2 to 1.
- A blank Slot1 / Slot2 = `out`.
- `linkSource` and `targetSlot` are ignored in CSV mode.
- Bad rows are listed in the results CSV: `VALIDATION` = caught before the run (bad
  ord, missing column), `ERROR` = failed while linking.

---

## 4. Actions

| Action (slot name) | What it does |
|---|---|
| **Execute** (`execute`) | Make the links. |
| **Dry Run - No Changes** (`dryRun`) | Preview: every link listed as `DRYRUN`, with the reason if it would be skipped. |
| **Verify - Audit** (`verify`) | Checks each link the settings describe: `FOUND`, `MISSING`, or `BROKEN` (there but not working). No changes. |
| **Reverse Changes** (`reverse`) | Removes exactly the links the last Execute made, newest first. A link changed since is skipped. Not a preview. |
| **Cancel Request** (`cancel`) | Stop after the current row. |
| **Create Sample CSV** (`createSampleCsv`) | Write a starter 5-column CSV to `sampleCsvPath`. |
| **Remove Old Logs** (`pruneArchives`) | Apply `maxArchives` now. |

---

## 5. Files it writes

| File (in `logs`) | Contents |
|---|---|
| `LinkCreator.log` | Step by step. |
| `LinkCreator_results.csv` | Timestamp, SourceName, SourceSlotPath, TargetName, TargetSlotPath, Status, Message, Mode, LinkName, DurationMs. |
| `LinkCreator_<program name>_reverse.csv` | The links the last Execute made (used by Reverse). |
| `…_<date>_<time>.log / .csv` | Archives of earlier runs. |

---

## 6. Complete setups

Placeholders throughout.

### A. Outside air temperature to every VAV's logic (BQL)

| Field | Value |
|---|---|
| operationMode | BQL |
| linkSource | `slot:/Drivers/BacnetNetwork/BXXXXX_AHU_1/points/OAT,out` |
| targetOrd | `station:\|slot:/Drivers/BacnetNetwork\|bql:select * from control:NumericWritable where name = 'OutsideAir'` |
| targetSlot | `in10` |

Dry Run → Execute → Verify.

### B. One link (Direct)

| Field | Value |
|---|---|
| operationMode | Direct |
| linkSource | `slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points/ZoneTemp` |
| targetOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_VAV_101/points/Logic` |
| targetSlot | `in10` |

(No `,slot` on the source = `out`.)

### C. A spreadsheet of links (CSV)

1. **Create Sample CSV** → open `logs/LinkCreator_SAMPLE.csv` in Excel.
2. Fill in one row per link; save to `Csv/Links.csv` in the station's Files.
3. `operationMode` = CSV, `targetOrd` = `file:^Csv/Links.csv`.
4. Dry Run → fix any `VALIDATION` rows → Execute → Verify.

### D. Check links after a change

Leave the settings as they were for the run and press **Verify**. Every link should come
back `FOUND`; `BROKEN` ones exist but don't work (check the slots and the source).

### E. Undo

Press **Reverse** before the next Execute.

---

## 7. Results CSV

| Status | Meaning |
|---|---|
| `LINKED` | Link made and working. |
| `DRYRUN` | Dry Run: would be made (or skipped — see Message). |
| `SKIPPED` | Not made; Message says why (already exists, …). |
| `FOUND` / `MISSING` / `BROKEN` | Verify results. |
| `REMOVED` / `DELETED` | Reverse took it away. |
| `VALIDATION` | CSV row rejected before the run. |
| `ERROR` | Failed (or made but not working, and removed again); Message has the reason. |

---

## 8. Good habits

- Back up the station. Dry Run, then Execute, then Verify.
- In BQL mode check the query's row count first.
- Reverse straight away if something went wrong (last run only).

---

## AX notes

- Program name **Linker4AX**, code `Linker4AX_v3.3.java`; AX 3.5–3.8.
- Same slots and actions; no imports to add. `targetOrd` is shown as "Target, BQL or CSV".
- The AX version doesn't sort its own property sheet (that's N4 v3.4).
