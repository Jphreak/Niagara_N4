/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.04
Date:    2026-10-27

Changes
-------
  v1.00  Initial release.
  v1.01  Added equipName output: name of the equipment folder this block
         lives in, found from the block's own slot path. Folders with
         "folder" in the name were skipped.
  v1.02  equipName drops the building prefix (B + digits + "_"):
         B90009_VAV_117 -> VAV_117.
  v1.03  equipName sub-folder rule: if the block's folder holds no points,
         it is a sub-folder. equipName becomes the parent equipment name
         plus the sub-folder's number:
         VAV_117/VAV_117_Folder_2 -> "VAV_117 (2)".
         Replaces the v1.01 "folder"-in-the-name rule.
  v1.04  Compile fix: point check no longer references
         javax.baja.control (control-rt not on the Program classpath).
         Uses the registered type "control:ControlPoint" instead.

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

  equipName
    1. Start at the folder this block is in.
    2. If that folder holds at least one point (a direct child that is a
       control point), it is the equipment: equipName = its name.
    3. If it holds no points, it is a sub-folder. Take the last number
       in its name (Folder_2 -> 2) and move up to its parent. Repeat
       until a folder with points is found.
    4. equipName = equipment name + " (n)". If the sub-folder name has
       no number, the equipment name is used alone.
    The building prefix (B + digits + "_") is removed from the
    equipment name. Escaped names are shown as Workbench displays them
    ($20 -> space, $2d -> -). Re-evaluated every run, so a copied or
    moved block picks up its new location. Empty if no folder with
    points is found.

  Examples
    .../VAV/B90009_VAV_102/zoneTempDelta                  -> VAV_102
    .../VAV/B90009_VAV_117/B90009_VAV_117_Folder_2/zoneTempDelta
                                                          -> VAV_117 (2)
    .../VAV/B90009_VAV_117/Folder_3/zoneTempDelta         -> VAV_117 (3)

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

private static final String VERSION = "v1.04";

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

// Walk up from the folder this block is in. A folder with no points is
// a sub-folder: remember its number (innermost one wins) and keep
// going. The first folder with points is the equipment.
private String findEquipName()
{
  javax.baja.sys.BComponent folder;
  try
  {
    javax.baja.sys.BComponent self = (javax.baja.sys.BComponent) getComponent();
    if (self == null) return "";
    folder = self.getParentComponent();
  }
  catch (Throwable t)
  {
    return "";
  }

  String subNum = null;
  int guard = 0;
  while (folder != null && guard++ < 20)
  {
    String n = unescapeName(folder.getName());
    if (hasPoints(folder))
    {
      String equip = stripBuilding(n);
      if (subNum != null) return equip + " (" + subNum + ")";
      return equip;
    }
    if (subNum == null) subNum = lastNumber(n);
    try
    {
      folder = folder.getParentComponent();
    }
    catch (Throwable t)
    {
      folder = null;
    }
  }
  return "";
}

// True if any direct child of c is a control point. Looks the type up
// by name so no control-rt import is needed.
private boolean hasPoints(javax.baja.sys.BComponent c)
{
  try
  {
    javax.baja.sys.Type cp = javax.baja.sys.Sys.getType("control:ControlPoint");
    javax.baja.sys.BComponent[] kids = c.getChildComponents();
    for (int i = 0; i < kids.length; i++)
      if (kids[i].getType().is(cp)) return true;
  }
  catch (Throwable ignore) {}
  return false;
}

// Last run of digits in a name, without leading zeros.
// "B90009_VAV_117_Folder_2" -> "2", "Folder_03" -> "3", "Logic" -> null.
private String lastNumber(String n)
{
  if (n == null) return null;
  int end = n.length();
  while (end > 0 && !Character.isDigit(n.charAt(end - 1))) end--;
  if (end == 0) return null;
  int start = end;
  while (start > 0 && Character.isDigit(n.charAt(start - 1))) start--;
  String d = n.substring(start, end);
  try
  {
    return String.valueOf(Integer.parseInt(d));
  }
  catch (NumberFormatException e)
  {
    return d;
  }
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
  if (s == null) return "";
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
