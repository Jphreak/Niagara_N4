/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.02
Date:    2026-10-26

Changes
-------
  v1.00  Initial release.
  v1.01  Added equipName output: name of the equipment folder this block
         lives in, found from the block's own slot path. Any folder with
         "folder" in its name (any case) is skipped, so a block nested in
         e.g. B90009_VAV_117/B90009_VAV_117_Folder_2 still reports
         B90009_VAV_117.
  v1.02  equipName drops the building prefix (B + digits + "_"):
         B90009_VAV_117 -> VAV_117.

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
pointStatus flags a bad input. equipName names the equipment.
Runs on change of either input, and once on start. No logging.

Outputs
-------
  Both inputs ok     -> out         = difference, status ok
                        pointStatus = false, status ok
  Either input bad   -> out         = 0, status null
                        pointStatus = true, status fault
  Bad = fault, down, stale, disabled, null, or slot missing/unlinked.
  A flag set alongside good flags still counts (overridden + stale = bad).
  Overridden and alarm/unackedAlarm flags alone are NOT treated as bad.

  Both outputs are rewritten as whole new values on every run, so a
  copied block never keeps the source block's state.

  equipName  Walks up the block's slot path from its parent folder and
             returns the first folder name that does not contain
             "folder", with a leading building prefix (B + digits +
             "_", e.g. "B90009_") removed. Escaped names are shown as Workbench displays
             them ($20 -> space, $2d -> -). Re-evaluated every run, so a
             copied or moved block picks up its new location. Empty if
             no qualifying folder is found.

  Examples
    .../VAV/B90009_VAV_102/zoneTempDelta                  -> VAV_102
    .../VAV/B90009_VAV_117/B90009_VAV_117_Folder_2/zoneTempDelta
                                                          -> VAV_117

Imports
-------
  javax.baja.status.*

Slot Sheet
----------
  Name         Type                 Flags / Config
  -----------  -------------------  ------------------------------
  zoneTemp     baja:StatusNumeric   Summary, Execute On Change,
                                    default status null
  zoneTempSp   baja:StatusNumeric   Summary, Execute On Change,
                                    default status null
  out          baja:StatusNumeric   Summary, Read Only
  pointStatus  baja:StatusBoolean   Summary, Read Only,
                                    facets trueText=" " falseText="Ok"
  equipName    baja:String          Summary, Read Only
  version      baja:String          Read Only
================================================================================
*/

private static final String VERSION = "v1.02";

public void onStart() throws Exception
{
  setVersion(VERSION);
  onExecute();
}

public void onExecute() throws Exception
{
  BStatusNumeric zt = getZoneTemp();
  BStatusNumeric sp = getZoneTempSp();

  boolean bad = zt == null || sp == null
             || !zt.getStatus().isValid()
             || !sp.getStatus().isValid();

  if (bad)
  {
    setOut(new BStatusNumeric(0, BStatus.nullStatus));
    setPointStatus(new BStatusBoolean(true, BStatus.fault));
  }
  else
  {
    setOut(new BStatusNumeric(zt.getValue() - sp.getValue(), BStatus.ok));
    setPointStatus(new BStatusBoolean(false, BStatus.ok));
  }

  updateEquipName();
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// equipName
// ----------------------------------------------------

// Write equipName only when it changes, so the slot doesn't fire a
// change event on every execute.
private void updateEquipName()
{
  String name = findEquipName();
  String cur  = getEquipName();
  if (cur == null || !cur.equals(name)) setEquipName(name);
}

// Path of this block, e.g.
//   /Drivers/.../VAV/B90009_VAV_117/B90009_VAV_117_Folder_2/zoneTempDelta
// The last segment is the block itself. Walk back from its parent and
// return the first segment that isn't a "folder", minus its building
// prefix.
private String findEquipName()
{
  String path;
  try
  {
    javax.baja.sys.BComponent self = (javax.baja.sys.BComponent) getComponent();
    if (self == null || self.getSlotPath() == null) return "";
    path = self.getSlotPath().toString();
  }
  catch (Throwable t)
  {
    return "";
  }

  if (path.startsWith("slot:")) path = path.substring(5);
  String[] seg = path.split("/");

  // seg[seg.length - 1] is this block; start at its parent.
  for (int i = seg.length - 2; i >= 0; i--)
  {
    String s = seg[i];
    if (s == null || s.length() == 0) continue;
    String n = unescapeName(s);
    if (n.toLowerCase().indexOf("folder") >= 0) continue;
    return stripBuilding(n);
  }
  return "";
}

// "B90009_VAV_117" -> "VAV_117". Only strips B + digits + "_" at the
// start; a name that would end up empty is returned unchanged.
private String stripBuilding(String n)
{
  String t = n.replaceFirst("^[Bb][0-9]+_", "");
  return t.length() > 0 ? t : n;
}

// "VAV$20B$2d31" -> "VAV B-31". Handles $xx and $uxxxx. Bad sequences
// are copied through unchanged.
private String unescapeName(String s)
{
  if (s.indexOf('$') < 0) return s;
  StringBuffer out = new StringBuffer();
  int i = 0;
  int n = s.length();
  while (i < n)
  {
    char c = s.charAt(i);
    if (c == '$')
    {
      try
      {
        if (i + 5 < n && s.charAt(i + 1) == 'u')
        {
          out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
          i += 6;
          continue;
        }
        if (i + 2 < n)
        {
          out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
          i += 3;
          continue;
        }
      }
      catch (NumberFormatException e) {}
    }
    out.append(c);
    i++;
  }
  return out.toString();
}
