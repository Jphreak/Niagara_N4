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
is next to its code.

## Layout

- `Raw Code/N4/<Program>/`, `Raw Code/AX/<Program>/` — source code and slot sheet.
  The user guide, `Raw Code/N4/<Program>/<Program>_UserGuide.md` (every field
  explained, with complete setups), covers the AX version too - see its AX notes
- `Precompiled Bogs/` — ready-made programs to drop into a station, with a PDF user
  guide for each (`ForceRemove_UserGuide.pdf`, `ComponentCopier_UserGuide.pdf`,
  `Linker_UserGuide.pdf`, `Mailer_UserGuide.pdf`, `SetFlagAudit_UserGuide.pdf`,
  `SetFlagEditor_UserGuide.pdf`, `SlotRenamerDisplayName_UserGuide.pdf` — the N4 and
  AX bogs share one guide)

## Using a program

- **Bog:** open it in Workbench and copy the program into the station.
- **Raw Code:** add a new Program Object, add the imports and slots from the
  slot sheet, paste the `.java` code, compile.

Back up the station before running ForceRemove, ComponentCopier, Linker,
SetFlagEditor or SlotRenamerDisplayName, and try Dry Run first.
