/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-27

Changes
-------
  v1.00  Initial release.

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
pointStatus flags a bad input. equipName names the equipment.
buildingNum names the building.
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
    2. If that folder holds at least one point, it is the equipment:
       equipName = its name.
    3. If it holds no points, it is a sub-folder. Take the last number
       in its name (Folder_2 -> 2) and move up to its parent. Repeat
       until a folder with points is found.
    4. equipName = equipment name + " (n)". If the sub-folder name has
       no number, the equipment name is used alone.
    A point = a direct child (not this block) with an "out" slot
    holding a status value and a "facets" slot.
    The building prefix (B + digits + "_") is removed from the
    equipment name. Escaped names are shown as Workbench displays them
    ($20 -> space, $2d -> -). Re-evaluated every run, so a copied or
    moved block picks up its new location.

    If no name can be found, equipName shows why:
      ?getComponent ...   could not get this block
      ?no parent          could not get the block's folder
      ?no points found    walked to the top without finding a point

  buildingNum
    1. If the equipment name starts with B + digits + "_", that
       B-number is used: B90009_VAV_117 -> B90009.
    2. Otherwise, the last B-number in the equipment's slot path is
       used (the block's own path if no equipment was found). A
       B-number is B + digits, not preceded by a letter or digit, so
       IO_B90009_01 and B90009_7755Hurontario both give B90009.
    3. Empty if neither finds one.
    Always returned in upper case.

  Examples
    .../VAV/BXXXXX_VAV_102/zoneTempDelta
        equipName VAV_102,        buildingNum BXXXXX
    .../VAV/BXXXXX_VAV_117/BXXXXY_VAV_117_Folder_2/zoneTempDelta
        equipName VAV_117 (2),    buildingNum BXXXXY
    .../BXXXXX_1234Lakeontario/OT_BXXXXX_01/points/VAV/VAV_5/zoneTempDelta
        equipName VAV_5,          buildingNum BXXXXX (from the path)

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
  buildingNum  baja:String          Summary, Read Only
  version      baja:String          Read Only
================================================================================
*/

private static final String VERSION = "v1.00";

// Results of one findEquipName() pass. Only used within a single run.
private String foundEquip;
private String foundBldg;

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

  updateNames();
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// equipName / buildingNum
// ----------------------------------------------------

// Write each slot only when it changes, so it doesn't fire a change
// event on every execute.
private void updateNames()
{
  foundEquip = "";
  foundBldg  = "";
  try
  {
    findNames();
  }
  catch (Throwable t)
  {
    foundEquip = "?error " + t;
  }

  String cur = getEquipName();
  if (cur == null || !cur.equals(foundEquip)) setEquipName(foundEquip);

  cur = getBuildingNum();
  if (cur == null || !cur.equals(foundBldg)) setBuildingNum(foundBldg);
}

// Walk up from the folder this block is in. A folder with no points is
// a sub-folder: remember its number (innermost one wins) and keep
// going. The first folder with points is the equipment.
// Sets foundEquip and foundBldg.
private void findNames()
{
  javax.baja.sys.BComponent self;
  try
  {
    self = (javax.baja.sys.BComponent) getComponent();
  }
  catch (Throwable t)
  {
    foundEquip = "?getComponent " + t;
    return;
  }
  if (self == null)
  {
    foundEquip = "?getComponent null";
    return;
  }

  javax.baja.sys.BComponent folder = parentOf(self);
  if (folder == null)
  {
    foundEquip = "?no parent";
    foundBldg  = lastBuildingInPath(self);
    return;
  }

  String subNum = null;
  int guard = 0;
  while (folder != null && guard++ < 20)
  {
    String n = unescapeName(folder.getName());
    if (hasPoints(folder, self))
    {
      String equip = stripBuilding(n);
      foundEquip = (subNum != null) ? equip + " (" + subNum + ")" : equip;

      String b = buildingPrefix(n);
      foundBldg = (b != null) ? b : lastBuildingInPath(folder);
      return;
    }
    if (subNum == null) subNum = lastNumber(n);
    folder = parentOf(folder);
  }

  foundEquip = "?no points found";
  foundBldg  = lastBuildingInPath(self);
}

// Parent component. Tries getParentComponent() first, then resolves
// the parent's slot path.
private javax.baja.sys.BComponent parentOf(javax.baja.sys.BComponent c)
{
  try
  {
    javax.baja.sys.BComponent p = c.getParentComponent();
    if (p != null && p != c) return p;
  }
  catch (Throwable ignore) {}

  try
  {
    String path = c.getSlotPath().toString();
    if (path.startsWith("slot:")) path = path.substring(5);
    int slash = path.lastIndexOf('/');
    if (slash <= 0) return null;
    Object o = javax.baja.naming.BOrd.make(
      "station:|slot:" + path.substring(0, slash)).resolve().get();
    if (o instanceof javax.baja.sys.BComponent && o != c)
      return (javax.baja.sys.BComponent) o;
  }
  catch (Throwable ignore) {}

  return null;
}

// True if any direct child of c (other than this block) is a point:
// an "out" slot holding a status value plus a "facets" slot.
private boolean hasPoints(javax.baja.sys.BComponent c,
                          javax.baja.sys.BComponent self)
{
  javax.baja.sys.BComponent[] kids;
  try
  {
    kids = c.getChildComponents();
  }
  catch (Throwable t)
  {
    return false;
  }
  for (int i = 0; i < kids.length; i++)
  {
    if (kids[i] == self) continue;
    try
    {
      Object out = kids[i].get("out");
      if (out instanceof BStatusValue && kids[i].get("facets") != null)
        return true;
    }
    catch (Throwable ignore) {}
  }
  return false;
}

// "B90009_VAV_117" -> "B90009". Null if the name has no B + digits + "_"
// prefix.
private String buildingPrefix(String n)
{
  if (n == null) return null;
  java.util.regex.Matcher m =
    java.util.regex.Pattern.compile("^([Bb][0-9]+)_").matcher(n);
  return m.find() ? m.group(1).toUpperCase() : null;
}

// Last B-number in c's slot path. A B-number is B + digits, not
// preceded by a letter or digit and not followed by a digit.
// ".../B90009_7755Hurontario/IO_B90009_01/points/VAV" -> "B90009".
// Empty if none.
private String lastBuildingInPath(javax.baja.sys.BComponent c)
{
  String path;
  try
  {
    path = unescapeName(c.getSlotPath().toString());
  }
  catch (Throwable t)
  {
    return "";
  }
  java.util.regex.Matcher m = java.util.regex.Pattern
    .compile("(?<![A-Za-z0-9])([Bb][0-9]+)(?![0-9])").matcher(path);
  String last = "";
  while (m.find()) last = m.group(1).toUpperCase();
  return last;
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
