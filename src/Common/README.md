# Common helper block — the standard for every Program Object

Published copy. The working copy lives in CladueXfer `testing/Common`;
change it there and copy it across when publishing. `programs.json` here
lists only the published programs that are on the Common block.

One copy of the code every program uses for the same job. A program never
gets its own version of a helper that is here; if a helper needs to
change, it changes **here** and `sync_common.py` copies it into every
program.

| File | What it is |
|---|---|
| `Common_N4.java` | The helpers for N4 Program Objects (and the mailer-rt module). Edit this one. |
| `Common_AX.java` | The same helpers for AX 3.5–3.8, generated from `Common_N4.java` by `make_ax.py` (Java 1.4, `javax.baja.log.Log`, reflective station home, 2-argument `add`). Don't edit by hand. |
| `make_ax.py` | Regenerates `Common_AX.java` and checks the AX language rules. Run after every change to `Common_N4.java`. |
| `sync_common.py` | `check` reports any program whose copy differs; `apply <file>` / `apply --all` copies the helpers in. |
| `programs.json` | Every program and which tiers it carries. Add a new program here. |

## Tiers

| Tier | Who carries it | Helpers |
|---|---|---|
| core | every program | `now`, `describeException`, `updateVersion`, `updateQuickGuide`, `ordSlot`, `intSlot` |
| files | action-driven tools that write files | `resolveToFile`, `sandboxToStationHome`, `resolveOutputFile`, `writeTextFile`, `pruneRunFiles`, `appendLine`, `clearFile`, `archiveFile`, `pruneArchives`, `pruneArchiveFiles`, `archiveAndPrune`, `resolveLogPath`, `resolveMaxArchives`, `writeToLog`, `logInfo/Warn/Error`, `reportInfo/Warn/Error` |
| csv | tools that read or write CSV | `csvEscape`, `looksNumeric`, `parseCsvRow` |
| ord | tools that take ords from the user | `normalizeOrd`, `escapeSlotNames` (+ helpers), `compAt` |
| reverse | tools that change the station | `reverseRecordPath`, `beginReverseRecord`, `recordReverse`, `readReverseRecord`, `consumeReverseRecord` |
| links | tools that link or copy components | `undoReverseRow`, `createLink`, `buildLinkName`, `preLinkCheck`, `missingSlot`, `linkHealth`, `removeDeadLink`, `flagConstant`, `slotFlags`, `callNoArg`, `invoke0`, `strOf` |

Logic blocks that run on every input change or poll (ZoneTempDelta,
ZoneTempDeltaDual, ApiClient) carry **core only** — no file writes per
cycle.

## What a program defines itself

```java
private static final String VERSION = "vX.YY";
private static final String LOG_TAG = "<Tool>";        // log tag + default file names
private static final java.util.logging.Logger log =     // files tier (AX: javax.baja.log.Log)
  java.util.logging.Logger.getLogger("<Tool>");
private static final String QUICK_GUIDE = "...";
```

Slots: `version`, `quickGuide` (baja:String, Read Only) on every program;
`status`, `logFilePath` (baja:Ord) and `maxArchives` (baja:Integer,
default 10) on file-writing tools.

## Conventions

- **status** `"[yyyy-MM-dd HH:mm:ss] message"` — use `reportInfo/Warn/Error`,
  which also write the App Director and the log file. Exception: Mailer
  keeps its result word first (`SENT [time] ...`) because other programs
  read line 1.
- **files** always through `resolveToFile` / `resolveOutputFile`: station
  home only; a refusal sets status, the App Director and `lastPathError`.
- **archives** `<name>_yyyy-MM-dd_HH-mm-ss.<ext>`, a log per run, pruned to
  `maxArchives` (0 = keep all).
- **CSV** every field through `csvEscape` (quotes when needed, leading `'`
  on text a spreadsheet would run as a formula).
- **dry run** is an action, never a Boolean slot.
- **reverse** undoes a record of what the live run actually changed
  (`recordReverse` as each change is made); reverse consumes it. Items
  changed since the run are skipped, never removed.
- **links** are created by `createLink` and named by `buildLinkName` in
  every tool, so a link made by one tool is found by the others.

## Adding a new program

1. Copy the constants above; add the slots.
2. Add it to `programs.json` with its tiers.
3. `python3 sync_common.py apply <file>` — the helpers are added.
4. Write only the program's own logic; call the helpers.
5. `python3 sync_common.py check` before publishing — every program OK.
