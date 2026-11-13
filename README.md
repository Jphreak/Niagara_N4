# Niagara_N4

Program Objects for Tridium Niagara N4 (4.15) and AX (3.5–3.8).

## Programs

| Program | Version | What it does |
|---|---|---|
| ForceRemove | v3.4 (N4), v3.3 (AX) | Bulk-removes components, points or extensions (Direct, BQL, CSV). Backs up first; Reverse restores, links keep their names. |
| ComponentCopier | v3.4 (N4), v3.3 (AX) | Copies components to one or many destinations, with an optional links CSV. Reverse undoes it. |
| Linker | v3.4 (N4), v3.3 (AX) | Creates, verifies or removes links (Direct, BQL, CSV). Reverse undoes it. |
| Mailer | v1.04 (N4) | Emails station files through the EmailService, with per-file recipients (To / Cc / Bcc). |
| SetFlagAudit | v1.03 (N4) | Reports the flags on chosen slots (e.g. `set`, `fallback`) of every component found (component or BQL search), to Results and a CSV. |
| SetFlagEditor | v1.03 (N4) | Same search as SetFlagAudit, then adds, removes or resets those slot flags. Reverse undoes it. |
| SlotRenamerDisplayName | v1.02 (N4) | Changes slot display names (e.g. the `set` action → "Set Setpoint") on every point found; `*` wildcards on point names, per-point rules. Reverse undoes it. |

The tools (ForceRemove, ComponentCopier, Linker, Mailer, SetFlagEditor,
SlotRenamerDisplayName) have a Dry Run action;
on-station help is in each program's quickGuide slot, and the full user guide
is in each program's folder (PDF next to the bog, Markdown next to the code).

## Layout

One folder per program:

```
Linker/
  Linker_N4.bog            ready-made program (N4)
  Linker4AX.bog            ready-made program (AX)
  Linker_UserGuide.pdf     user guide - every field explained, complete setups
  Raw Code/
    Linker_N4_v3.4.java            source (N4) + its slot sheet
    Linker_N4_v3.4_SlotSheet.txt
    Linker4AX_v3.3.java            source (AX) + its slot sheet
    Linker4AX_v3.3_SlotSheet.txt
    Linker_UserGuide.md            the same guide as Markdown
```

The N4 and AX versions share one guide (see its AX notes). Programs without an
AX version have only the N4 files.

## Using a program

- **Bog:** open it in Workbench and copy the program into the station.
- **Raw Code:** add a new Program Object, add the imports and slots from the
  slot sheet in `<Program>/Raw Code/`, paste the `.java` code, compile.

Back up the station before running ForceRemove, ComponentCopier, Linker,
SetFlagEditor or SlotRenamerDisplayName, and try Dry Run first.
