# SlotRenamerDisplayName — User Guide

SlotRenamerDisplayName uses the same search as SetFlagEditor (see its own user guide),
then **changes the display name of chosen slots** on every component it finds — e.g. the
`set` action of every writable point shows **Set Setpoint** instead of **Set**. Dry Run
previews it; Reverse undoes the last live run.

Covers **N4 v1.00 – v1.02**. From v1.02 Filter by Name and the rules take `*` wildcards.

---

## Contents

1. [How it works in one minute](#1-how-it-works-in-one-minute)
2. [Every field, explained](#2-every-field-explained)
3. [Actions](#3-actions)
4. [Where the names are stored](#4-where-the-names-are-stored)
5. [Reverse in detail](#5-reverse-in-detail)
6. [Reading the results and the CSV](#6-reading-the-results-and-the-csv)
7. [Complete setups](#7-complete-setups)
8. [Problems and fixes](#8-problems-and-fixes)

---

## 1. How it works in one minute

```
 search: rootOrd + nameFilter (wildcards)
   └─► each matched component, the first Rename Rule that fits each slot
       (rule's point name, if any, matches + the component has the slot):
         new display name = right side of the rule
         (blank right side = back to the default name)
         already has its own name + Already Renamed = Skip  ──► SKIPPED
   Dry Run  ──► shows Before → After, writes nothing
   Execute  ──► writes, reads back each one, saves a reverse record
   Reverse  ──► puts the last live run back
```

- **Always Dry Run first.** Then Execute. Then **save the station** — the names are only
  kept once the station is saved.
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
| `matched` (Slots Matched) | Out | Rule slots found on the matched components (after Reverse: rows in the record). |
| `changed` (Slots Changed) | Out | Slots renamed (Dry Run: that would be; Reverse: put back). |
| `summary` (Summary) | Out | e.g. `LIVE \| Slots Matched as per filters: 60 \| Changed: 58 \| No change: 0 \| Skipped (already renamed): 2 \| Failed: 0`. |
| `results` (Results) | Out | One line per slot: `name.slot : before -> after [result]`. |

### What to search

| Field | | Default | What it is |
|---|---|---|---|
| `rootOrd` (Root Location or BQL) | Set | `station:\|slot:/Drivers` | A component (walks everything under it) or a BQL/NEQL query (each row walked as its own root). |
| `nameFilter` (Filter by Name (Blank = All)) | Set | blank | Point names, comma list, `*` = wildcard, not case-sensitive: `Boiler` only Boiler · `Boiler*` starts with · `*Boiler*` contains · `AHU*, *Pump*` either. Blank = all. |
| `maxResults` (Max. Results) | Set | 0 | Caps CSV rows and `results` lines only — **every matched slot is still renamed**. 0 = no CSV limit, 200 lines. |

### What to change

| Field | | Default | What it is |
|---|---|---|---|
| `renames` (Rename Rules) | Set | `set = Set Setpoint` | One rule per line (or `;` between rules): `slot name = new display name`. Nothing after `=` puts the slot back to its default name. `#` starts a comment line. |
| `skipRenamed` (Already Renamed) | Set | Skip | **Skip**: a slot that already has its own display name is left alone (`SKIPPED`). **Overwrite**: rename it too. Rules that reset to default always apply. |

Rename Rules:

- The left side is `[point name.]slot name`. The point name is optional and takes the same
  `*` wildcards as Filter by Name; without it the rule fits any point.
  ```
  Boiler.set = Boiler Enable          only the point named Boiler
  *Pump*.set = Pump Command           points with Pump in the name
  set = Set Setpoint                  any other point
  ```
- **The first rule that fits a point's slot wins** — put point-named rules above the
  general ones. The same slot can be in several rules with different point names.
- The slot name is the **name** from the slot sheet Name column — `set`, not `Set`.
  A name with spaces can be typed as Workbench shows it.
- A rule whose slot isn't on a component is ignored for that component, so one list can
  cover numeric, boolean and enum writables together.
- A child component's name works too — its display name is renamed.
- New names can't contain `;` `{` `}` `=` `%`.

The run stops before changing anything if a rule has no `=`, no slot name, nothing before
the `.`, the same point + slot listed twice, or a name with one of those characters. Status lists every bad rule.

### Output files

| Field | | Default | What it is |
|---|---|---|---|
| `csvFile` (Output CSV file) | Set | `file:^NameEdits.csv` | A file (overwritten) or a folder (new `SlotRenamerDisplayName_<date>_<time>.csv` per run). **Use a folder to keep a history of every run.** Blank = no CSV. Inside the station folder only. |
| `maxArchives` (Max Archives) | Set | 10 | CSVs kept in folder mode, and old logs. 0 = keep all. |
| `logFilePath` (Log File) | Set | `file:^logs/SlotRenamerDisplayName.log` | A new log per run. |

---

## 3. Actions

| Action (slot) | What it does |
|---|---|
| **Dry Run** (`dryRun`) | Preview: every matched slot with Before → After and `WOULD CHANGE` / `NO CHANGE` / `SKIPPED`. Writes nothing; doesn't touch the reverse record. |
| **Execute** (`execute`, built-in) | Rename. Each component's names are written once and read back; one that didn't take is `FAILED`. |
| **Reverse** (`reverse`) | Undo the last live Execute (see below). |

---

## 4. Where the names are stored

The same place Workbench's **Set Display Name** puts them: a hidden `displayNames` slot
(`baja:NameMap`) on the component that owns the slot:

```
{set=Set Setpoint;emergencyOverride=Emergency Override;}
```

- Names already in that map (for other slots) are kept.
- The slot is created on the first name (readonly, hidden, operator — as Workbench does)
  and removed when its last name is reset.

---

## 5. Reverse in detail

- Each live Execute that changes at least one slot saves a reverse record:
  `logs/SlotRenamerDisplayName_<program name>_reverse.csv` (Path, Slot, Before, After).
  A live run that changes nothing keeps the previous record.
- Reverse puts each slot back to its Before name, **but only if it still has the After
  name** — anything renamed since is `SKIPPED`.
- After a Reverse the record is renamed `…_reverse_reversed_<timestamp>.csv`, so it can't
  be applied twice (up to `maxArchives` of these are kept).
- **Only the last live run** can be reversed. For older runs, use the Before column of
  that run's CSV (folder mode keeps them all).
- Save the station after a Reverse.

---

## 6. Reading the results and the CSV

```
VAV_101_Flow_SP.set : Set (default) -> Set Setpoint  [CHANGED]
VAV_102_Flow_SP.set : Command  [SKIPPED (already renamed)]
VAV_103_Flow_SP.set : Set Setpoint  [NO CHANGE]
```

`(default)` after a name means the slot has no display name of its own.

| CSV column | Contents |
|---|---|
| Name | Component name |
| Path | Slot path (usable as an ord) |
| Slot | Slot renamed |
| Before | Display name before |
| After | Display name after (Dry Run: what it would be) |
| Result | `CHANGED` · `WOULD CHANGE` · `NO CHANGE` · `SKIPPED (already renamed)` · `FAILED (reason)` · after Reverse: `REVERSED` · `SKIPPED` · `FAILED` |

---

## 7. Complete setups

Placeholders throughout.

### A. Rename the Set action on every writable point

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers` |
| renames | `set = Set Setpoint` |
| skipRenamed | Skip |
| csvFile | `file:^reports/` |

Dry Run → check `changed` and the CSV → Execute → **save the station**.

### B. Several slots on one building's points

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_Site` |
| renames | `set = Set Setpoint`<br>`emergencyOverride = Emergency Override`<br>`emergencyAuto = Emergency Release` |

### C. Different names by point name, one run

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers/BacnetNetwork/BXXXXX_Site` |
| renames | `Boiler*.set = Boiler Enable`<br>`*Pump*.set = Pump Command`<br>`*Flow_SP.set = Set Flow Setpoint`<br>`set = Set Setpoint` |

Points starting with Boiler get Boiler Enable, any with Pump get Pump Command, flow
setpoints get Set Flow Setpoint, every other point gets Set Setpoint.

### C2. Only one point type, by BQL

| Field | Value |
|---|---|
| rootOrd | `station:\|slot:/Drivers\|bql:select * from control:NumericWritable where name like '*Flow_SP'` |
| renames | `set = Set Flow Setpoint` |

### D. Put the default names back

| Field | Value |
|---|---|
| rootOrd | the same search |
| renames | `set =`<br>`emergencyOverride =` |

### E. Test on one point first

Set `nameFilter` to one point's name, Dry Run, Execute, check the point's right-click
Actions menu and slot sheet, then clear the filter for the full run.

---

## 8. Problems and fixes

| Symptom | Fix |
|---|---|
| Run stops: `Rename Rules - …` | Fix the rules Status lists (missing `=`, slot twice, `; { } = %` in a name). |
| Everything `SKIPPED (already renamed)` | Those slots have their own names already. Set Already Renamed to **Overwrite**. |
| `Slots Matched` is 0 | The left side must be the slot **name** (`set`), not the display name (`Set`). Check the search finds the points. |
| Names gone after a restart | The station wasn't saved after the run. |
| Reverse says SKIPPED | That slot was renamed again after the run; fix it by hand from the CSV's Before column. |
| Reverse does nothing | No reverse record (already reversed, or the last live run changed nothing). |
| `FAILED (… can't build displayNames …)` | Send the whole Status / Results line — it names what the station refused. |
