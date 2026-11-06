# Niagara_N4

Program Objects for Tridium Niagara N4 (4.15) and AX (3.5–3.8).

## Programs

| Program | Version | What it does |
|---|---|---|
| ForceRemove | v3.3 (N4, AX) | Bulk-removes components, points or extensions (Direct, BQL, CSV). Backs up first; Reverse restores, links keep their names. |
| ComponentCopier | v3.3 (N4, AX) | Copies components to one or many destinations, with an optional links CSV. Reverse undoes it. |
| Linker | v3.3 (N4, AX) | Creates, verifies or removes links (Direct, BQL, CSV). Reverse undoes it. |
| Mailer | v1.03 (N4) | Emails station files through the EmailService, with per-file recipients (To / Cc / Bcc). |

The tools (ForceRemove, ComponentCopier, Linker, Mailer) have a Dry Run action;
on-station help is in each program's quickGuide slot, and the full user guide
is next to its code.

## Layout

- `Raw Code/N4/<Program>/`, `Raw Code/AX/<Program>/` — source code, slot sheet and
  `<Program>_UserGuide.md` (every field explained, with complete setups)
- `Precompiled Bogs/` — ready-made programs to drop into a station

## Using a program

- **Bog:** open it in Workbench and copy the program into the station.
- **Raw Code:** add a new Program Object, add the imports and slots from the
  slot sheet, paste the `.java` code, compile.

Back up the station before running ForceRemove, ComponentCopier or Linker,
and try Dry Run first.
