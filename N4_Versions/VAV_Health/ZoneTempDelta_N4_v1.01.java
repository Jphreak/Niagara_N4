/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.01
Date:    2026-10-30

Changes
-------
  v1.00  Initial release.
  v1.01  Added band input (StatusNumeric, type a value or link a point
         per VAV; not set = 1.25, written back when unlinked) and
         inRange output (true when |out| <= band; {fault} when a temp
         input is bad). Removed pointStatus - inRange's status carries
         input health. equipName messages without the "?" prefix:
         "No Points Found", "No Parent Folder", "Block Not Found",
         "Error: ...".

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
inRange is true when |out| <= band; its status is {fault} when a temp
input is bad.
equipName names the equipment. buildingNum names the building.
Runs on change of any input, and once on start. No logging.

Outputs
-------
  Both inputs ok     -> out     = difference, status ok
                        inRange = |out| <= band, status ok
  Either input bad   -> out     = 0, status null
                        inRange = false, status fault
  Bad = fault, down, stale, disabled, null, or slot missing/unlinked.
  A flag set alongside good flags still counts (overridden + stale = bad).
  Overridden and alarm/unackedAlarm flags alone are NOT treated as bad.

  inRange (band is symmetric: within +/- band of setpoint)
    Temps ok            -> |out| <= band : true  {ok}
                           |out| >  band : false {ok}
    Temps bad           -> false {fault}
  band is a StatusNumeric input: type a value or link a point to set it
  per VAV. If band is not set (null, bad status or <= 0), 1.25 is used.
  Unlinked: 1.25 {ok} is written into band so it shows on the sheet.
  Linked:   band is left alone (the link owns it); 1.25 is used until
            the linked point is good again.

  All outputs are rewritten as whole new values on every run, so a
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
      Block Not Found     could not get this block
      No Parent Folder    could not get the block's folder
      No Points Found     walked to the top without finding a point
                          (e.g. a spare block in a Programs folder)
      Error: ...          anything else

  buildingNum
    1. If the equipment name starts with B + digits + "_", that
       B-number is used: BXXXXX_VAV_301 -> BXXXXX.
    2. Otherwise, the last B-number in the equipment's slot path is
       used (the block's own path if no equipment was found). A
       B-number is B + digits, not preceded by a letter or digit, so
       OT_BXXXXX_01 and BXXXXX_1234Lakeontario both give BXXXXX.
    3. Empty if neither finds one.
    Always returned in upper case.

  Examples
    .../VAV/BXXXXX_VAV_201/zoneTempDelta
        equipName VAV_201,        buildingNum BXXXXX
    .../VAV/BXXXXX_VAV_301/BYYYYY_VAV_301_Folder_2/zoneTempDelta
        equipName VAV_301 (2),    buildingNum BXXXXX (from the equipment,
                                  not the sub-folder)
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
  band         baja:StatusNumeric   Summary, Execute On Change,
                                    default 1.25 {ok}
  out          baja:StatusNumeric   Summary, Read Only
  inRange      baja:StatusBoolean   Summary, Read Only,
                                    facets trueText="In Range"
                                    falseText="Out of Range"
  equipName    baja:String          Summary, Read Only
  buildingNum  baja:String          Summary, Read Only
  version      baja:String          Read Only
================================================================================
*/

private static final String VERSION = "v1.01";

// Used when band is not set (null, bad status or <= 0).
private static final double DEFAULT_BAND = 1.25;

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

  BStatusNumeric bd = getBand();
  boolean bandSet = bd != null && bd.getStatus().isValid() && bd.getValue() > 0;
  double band = bandSet ? bd.getValue() : DEFAULT_BAND;
  if (!bandSet && !isSlotLinked("band"))
    setBand(new BStatusNumeric(DEFAULT_BAND, BStatus.ok));

  if (bad)
  {
    setOut(new BStatusNumeric(0, BStatus.nullStatus));
    setInRange(new BStatusBoolean(false, BStatus.fault));
  }
  else
  {
    double delta = zt.getValue() - sp.getValue();
    setOut(new BStatusNumeric(delta, BStatus.ok));
    setInRange(new BStatusBoolean(Math.abs(delta) <= band + 1e-9, BStatus.ok));
  }

  updateNames();
}

public void onStop() throws Exception
{
}

// True if the named slot on this block has a link into it.
private boolean isSlotLinked(String name)
{
  try
  {
    javax.baja.sys.BComponent self = (javax.baja.sys.BComponent) getComponent();
    javax.baja.sys.Slot slot = self.getSlot(name);
    if (slot == null) return false;
    javax.baja.sys.BLink[] links = self.getLinks(slot);
    return links != null && links.length > 0;
  }
  catch (Throwable t)
  {
    // Can't tell: treat as linked so we never fight a link.
    return true;
  }
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
    foundEquip = "Error: " + t;
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
    foundEquip = "Block Not Found";
    return;
  }
  if (self == null)
  {
    foundEquip = "Block Not Found";
    return;
  }

  javax.baja.sys.BComponent folder = parentOf(self);
  if (folder == null)
  {
    foundEquip = "No Parent Folder";
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

  foundEquip = "No Points Found";
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

// "BXXXXX_VAV_301" -> "BXXXXX". Null if the name has no B + digits + "_"
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
// ".../BXXXXX_1234Lakeontario/OT_BXXXXX_01/points/VAV" -> "BXXXXX".
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
// "BXXXXX_VAV_301_Folder_2" -> "2", "Folder_03" -> "3", "Logic" -> null.
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

// "BXXXXX_VAV_301" -> "VAV_301". Only strips B + digits + "_" at the
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
