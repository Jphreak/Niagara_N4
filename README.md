# Niagara_N4

Program Objects for Tridium Niagara N4 (4.15) and AX (3.5–3.8).

## Programs

| Program | Version | What it does |
|---|---|---|
| ForceRemove | v3.3 (N4), v3.2 (AX) | Bulk-removes components, points or extensions (Direct, BQL, CSV). Backs up first; Reverse restores, links keep their names. |
| ComponentCopier | v3.2 (N4, AX) | Copies components to one or many destinations, with an optional links CSV. Reverse undoes it. |
| Linker | v3.2 (N4, AX) | Creates, verifies or removes links (Direct, BQL, CSV). Reverse undoes it. |
| ZoneTempDelta | v1.01 (N4) | Per-VAV zone temp minus setpoint, in-range flag, equipment and building names. |
| MonthlyVavHealth | v1.00 (N4) | Monthly per-building VAV health report CSVs from the ZoneTempDelta samples. |
| Mailer | v1.02 (N4) | Emails station files through the EmailService, with per-file recipients. |

The tools (ForceRemove, ComponentCopier, Linker, Mailer) have a Dry Run action;
on-station help is in each program's quickGuide slot.

## Layout

- `src/N4/<Program>/`, `src/AX/<Program>/` — source and slot sheet
- `Precompiled Bogs/` — ready-made programs to drop into a station (ForceRemove_N4.bog is still v3.2 until re-exported)
- `Powershell_Scripts/` — helper scripts

## Using a program

- **Bog:** open it in Workbench and copy the program into the station.
- **Source:** add a new Program Object, add the imports and slots from the
  slot sheet, paste the `.java` code, compile.

Back up the station before running ForceRemove, ComponentCopier or Linker,
and try Dry Run first.
