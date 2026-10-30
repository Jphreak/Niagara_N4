# Niagara_N4
Tridium N4 Useful Objects
Force Remove v3.2 (Direct, BQL, CSV)
- Allows removal of components
- Reversal of Removal
  
Component Copier v3.2 (Direct, BQL, CSV)
- Allows the copying of objects from one area to many
- Linking capability by defining through additional csv
- Ability to reverse changes 

Linker v3.2 (Direct, BQL, CSV)
- Allows automated linking of slots
- Ability to reverse changes

*** Note The above 2 programs also have the ability to reverse the others' linking ***

Zone Temp Delta v1.00
- out = zoneTemp - zoneTempSp (positive = above setpoint)
- pointStatus faults if either input is fault, down, stale, disabled or null
- Executes on change of either input
- equipName: equipment folder name without the building prefix; a block in a sub-folder with no points shows the parent plus the sub-folder number, e.g. VAV_301 (2)
- buildingNum: B# from the equipment name, or the last B# in the slot path

Mailer v1.00 (N4_Versions/Mailer)
- Emails files from the station's Files through the EmailService
- files: one per line - a single file, a wildcard (*.csv) or a whole folder
- Per-file recipients: "<file> => a@company.com; cc: b@company.com; bcc: c@company.com"
- One email per line; lines with the same recipients are combined
- cc / bcc slots go on every email
- Sender drop-down lists the configured outgoing accounts
- Dry Run shows exactly what would be sent
- Needs the email-rt import (javax.baja.email); slot sheet in the folder
