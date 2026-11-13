# SetFlagAudit — User Guide

SetFlagAudit is a Niagara Program Object that reports the **slot flags** (operator,
hidden, readonly, confirmRequired…) on a slot — usually the `set` action — across many
components, and shows which ones differ from the type's defaults. It changes nothing.
Its companion [SetFlagEditor](../SetFlagEditor/SetFlagEditor_UserGuide.md) makes the changes.

Covers **N4 v1.01 – v1.03**.

---

## Contents

1. [How it works in one minute](#1-how-it-works-in-one-minute)
2. [Every field, explained](#2-every-field-explained)
3. [Flag names](#3-flag-names)
4. [Reading the results](#4-reading-the-results)
5. [The CSV](#5-the-csv)
6. [Complete setups (recipes)](#6-complete-setups)
7. [Problems and fixes](#7-problems-and-fixes)

---

## 1. How it works in one minute

```
 rootOrd (a folder, or a BQL query)
   └─► every component below it that has a slot in slotNames (e.g. set, fallback)
         └─► passes nameFilter and requireFlags?
               └─► one row: its flags now, and (optional) how they differ from the default
                     ──►  results / summary / counts  +  CSV
```

- Run it with the built-in **Execute** action. Leave **Execute On Change off** on every
  slot, or each settings edit would start a full scan.
- It only reads. Nothing in the station changes.
- **Why it matters:** a slot flag like `operator` decides who can use the `set` action on
  a point; `hidden` hides it; `confirmRequired` asks before running it. "Modified" means
  someone changed it on that point from what the type normally has.

---

## 2. Every field, explained

Legend: **Set** = you set it · **Out** = the program writes it (read only).
Display names (what the property sheet shows) are in brackets.

### About

| Field | | What it is |
|---|---|---|
| `version` (Version) | Out | The program version. |
| `quickGuide` | Out | Short built-in help. |

### Last run

| Field | | What it is |
|---|---|---|
| `status` (Status) | Out | `OK` or `ERROR`, time, how many scanned and matched, how long it took, BQL row count, CSV file name. Lists `MISSING SLOTS: …` if an output slot wasn't added. |
| `lastRun` (LastRun) | Out | When the last scan finished. |
| `scanned` (Slots Scanned) | Out | Components walked. |
| `matched` (Slots Matched) | Out | Slot rows that passed all filters. A point with both `set` and `fallback` counts twice. |
| `modified` (Previously Modified Slots) | Out | Matched rows whose flags differ from the type default. |
| `summary` (Summary) | Out | Totals per flag, e.g. `Slots Matched as per filters: 412 \| Previously modified: 7 \| operator=405, hidden=7`. |
| `results` (Results) | Out | One line per matched slot — see [Reading the results](#4-reading-the-results). Open it from the property sheet. |

### What to search

| Field | | Default | What it is |
|---|---|---|---|
| `rootOrd` (Root Location or BQL) | Set | `station:\|slot:/Drivers` | **A component** → walks everything under it. **A BQL/NEQL query** → each row is walked as its own root (rows can be the points or the folders holding them). Something reached twice is counted once. |
| `slotNames` (Slot Name(s)) | Set | `set` | Which slots to look at, comma or space separated. Actions or properties both work: `set`, `set, fallback`, `set, emergencyOverride, override, auto, fallback`. Blank = `set`. |
| `nameFilter` (Filter by Name (Blank = All)) | Set | blank | Only components whose display name **contains** this (not case-sensitive). |
| `requireFlags` (Flag required (Blank = All)) | Set | blank | Only slots that have **all** of these flags, e.g. `operator` or `operator, confirmRequired`. |
| `maxResults` (Max. Results) | Set | 0 | Caps the CSV rows and `results` lines; the counts stay exact. 0 = no CSV limit, 200 `results` lines. |

### Output files

| Field | | Default | What it is |
|---|---|---|---|
| `csvFile` (Output CSV file) | Set | `file:^Flags.csv` | **A file** → overwritten every run. **A folder** (no extension, or ending in `/`) → a new `SetFlagAudit_<date>_<time>.csv` each run. Blank = no CSV. Must be inside the station folder. |
| `includeDetail` (Include Modified Details) | Set | false | true adds Modified, DefaultFlags, Added and Removed columns to the CSV. |
| `maxArchives` (Max Archives) | Set | 10 | In folder mode, how many CSVs to keep; also how many old logs. 0 = keep all. |
| `logFilePath` (Log File) | Set | `file:^logs/SetFlagAudit.log` | A new log each run; old ones archived. |

---

## 3. Flag names

Use these in `requireFlags` (not case-sensitive):

`readonly, transient, hidden, summary, async, noRun, defaultOnClone, confirmRequired,
operator, noAudit, composite, removeOnClone, metadata, linkTarget, nonCritical,
userDefined1, userDefined2, userDefined3, userDefined4`

The common ones for `set`: **operator** (operator-level users can use it),
**confirmRequired** (asks before running), **hidden** (not shown), **readonly**.
`transient` on a property means its value isn't saved in the bog.

---

## 4. Reading the results

```
AHU_1_SAT_SP.set : operator|summary
AHU_1_SAT_SP.fallback : operator
VAV_102_Flow_SP.set : operator|hidden   *MODIFIED +hidden
```

- `name.slot : flags now`
- `*MODIFIED` = different from the type default; `+flag` was added on this point,
  `-flag` was removed.

---

## 5. The CSV

| Column | Contents |
|---|---|
| Name | Display name |
| Path | Slot path (usable as an ord — e.g. in SetFlagEditor or a BQL) |
| Slot | Which slot (from `slotNames`) |
| Flags | Flags now, `\|`-separated |
| Modified *(detail)* | Y / N |
| DefaultFlags *(detail)* | The type's default flags |
| Added *(detail)* | Set here but not in the default |
| Removed *(detail)* | In the default but cleared here |

---

## 6. Complete setups

Placeholders throughout.

### A. Which `set` actions anywhere in Drivers have been changed?

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers` |
| slotNames | `set` |
| includeDetail | true |
| csvFile | `file:^reports/` (keeps a CSV per run) |

Execute → `modified` gives the count; filter the CSV on Modified = Y.

### B. Which setpoints can operators command?

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers\|bql:select * from control:NumericWritable where name like '*_SP'` |
| slotNames | `set, fallback` |
| requireFlags | `operator` |

### C. One building only

`rootOrd` = `station:|slot:/Drivers/BacnetNetwork/BXXXXX_Site` (a folder → its whole
subtree).

---

## 7. Problems and fixes

| Symptom | Fix |
|---|---|
| `MISSING SLOTS: …` in status | Add the named output slots (or ignore — the scan still runs). |
| Takes a long time / station slow | Narrow `rootOrd`; the scan runs on the action thread. |
| Path refused | `csvFile` must be inside the station folder. |
| Compile error on a Flags constant | Delete that entry from **both** `FLAG_NAMES` and `FLAG_VALUES` at the same position (most likely LINK_TARGET or NON_CRITICAL). |
| Scans every time a setting changes | Untick Execute On Change on the slots. |
