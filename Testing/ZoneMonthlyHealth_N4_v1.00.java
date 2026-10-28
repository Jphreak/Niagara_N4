/*
================================================================================
Program: ZoneMonthlyHealth - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-28

Changes
-------
  v1.00  Initial release.

Purpose
-------
Built on ZoneTempDelta. Tracks, while the zone is occupied and its
points are healthy:
  A) how long |zoneTemp - zoneTempSp| was more than band (default 2.0)
  B) how many times the zone went out of band (excursions)
At the start of each month the block appends one row for the month just
finished to a running CSV (default "Monthly VAV health.csv"), then
resets its totals.

Health
------
  Temps healthy  = zoneTemp and zoneTempSp both valid (not fault, down,
                   stale, disabled or null; overridden/alarm are fine).
  Occupancy      = read from occBool or occEnum, chosen by occMode.
                   A bad occupancy point counts as no data.
  Time is put in one bucket:
    occupied + healthy      -> occupiedTime (and outOfBandTime if out)
    occupied + temps bad    -> noDataTime
    occupancy point bad     -> noDataTime
    unoccupied              -> not counted

Excursions
----------
  Counted when the zone goes out of band (|delta| > band) while occupied
  and healthy. It re-arms only after |delta| drops below band - 0.5, so
  a zone sitting right at the band edge counts once, not every scan.
  A no-data gap does not re-arm (same excursion if still out after).
  Going unoccupied re-arms.

Timing
------
  Time is added up at every run using the state from the run before, so
  it is accurate when the block only runs on change. Link a CurrentTime
  block to tick so the totals and the month rollover stay current when
  nothing else changes. Station downtime is not counted.

Occupancy selector (occMode)
----------------------------
  Bool_TrueOcc   occBool true  = occupied
  Bool_FalseOcc  occBool false = occupied
  Enum           occEnum is occupied when its tag or ordinal is listed
                 in occStates (comma separated, case and _ ignored),
                 e.g. "occupied,standby" or "0,3"

CSV
---
  File: csvPath (default file:^Monthly VAV health.csv). Header is
  written when the file is new or empty. One row per block per month:
    Month, Building, Equipment, Band, Occupied Hours, Out of Band Hours,
    % Out of Band, Excursions, No Data Hours, Written

Actions
-------
  Test CSV - Random Rows  Appends 3-10 rows of random values to
                          "TEST <csv name>" in the same folder. Run it a
                          few times to check appending. The real CSV
                          and the totals are not touched.
  Reset Totals            Clears this month's totals (no CSV row).

Copied blocks
-------------
  The block remembers its own slot path. If a copied or moved block
  finds it is somewhere new, it clears the totals it brought with it
  and starts counting from now.

Outputs (same as ZoneTempDelta)
-------------------------------
  out          zoneTemp - zoneTempSp (0, null status if inputs bad)
  pointStatus  true {fault} if either temp input is bad
  equipName    equipment folder name, building prefix removed; a block
               in a sub-folder with no points shows "<equip> (n)"
  buildingNum  B# from the equipment name, else last B# in the path

Examples (placeholders)
  .../VAV/BXXXXX_VAV_201/zoneMonthlyHealth
      equipName VAV_201,        buildingNum BXXXXX
  .../VAV/BXXXXX_VAV_301/BYYYYY_VAV_301_Folder_2/zoneMonthlyHealth
      equipName VAV_301 (2),    buildingNum BXXXXX

Imports
-------
  javax.baja.status.*   (javax.baja.sys and java.util are predefined)

Slot Sheet
----------
  Name           Type                 Flags / Config
  -------------  -------------------  ----------------------------------
  zoneTemp       baja:StatusNumeric   Summary, Execute On Change,
                                      default status null
  zoneTempSp     baja:StatusNumeric   Summary, Execute On Change,
                                      default status null
  occMode        baja:StatusEnum      facets range={Bool_TrueOcc=0,
                                      Bool_FalseOcc=1,Enum=2}
  occBool        baja:StatusBoolean   Summary, Execute On Change,
                                      default status null
  occEnum        baja:StatusEnum      Summary, Execute On Change,
                                      default status null
  occStates      baja:String          default "occupied"
  band           baja:Double          default 2.0
  tick           baja:AbsTime         Execute On Change (link from
                                      CurrentTime)
  csvPath        baja:Ord             default
                                      file:^Monthly VAV health.csv
  out            baja:StatusNumeric   Summary, Read Only
  pointStatus    baja:StatusBoolean   Summary, Read Only,
                                      facets trueText=" " falseText="Ok"
  equipName      baja:String          Summary, Read Only
  buildingNum    baja:String          Summary, Read Only
  period         baja:String          Read Only   (month being counted)
  occupiedTime   baja:RelTime         Read Only
  outOfBandTime  baja:RelTime         Read Only
  noDataTime     baja:RelTime         Read Only
  excursions     baja:Integer         Read Only
  pctOutOfBand   baja:Double          Read Only
  csvStatus      baja:String          Read Only
  lastTime       baja:AbsTime         Hidden, Read Only   (state)
  prevState      baja:Integer         Hidden, Read Only   (state)
  armed          baja:Boolean         Hidden, Read Only   (state)
  ownerPath      baja:String          Hidden, Read Only   (state)
  version        baja:String          Read Only
  quickGuide     baja:String          Read Only,
                                      facets fieldWidth=75,multiLine=true
  testCsv        Action               Display Name "Test CSV - Random Rows"
  resetTotals    Action               Display Name "Reset Totals"
================================================================================
*/

private static final String VERSION = "v1.00";

private static final String DEFAULT_CSV = "file:^Monthly VAV health.csv";
private static final double DEFAULT_BAND = 2.0;
private static final double REARM = 0.5;

// prevState values
private static final int ST_UNOCC  = 0;
private static final int ST_IN     = 1;
private static final int ST_OUT    = 2;
private static final int ST_NODATA = 3;

private static final String CSV_HEADER =
  "Month,Building,Equipment,Band,Occupied Hours,Out of Band Hours," +
  "% Out of Band,Excursions,No Data Hours,Written";

private static final String QUICK_GUIDE =
  "Zone Monthly Health " + "v1.00\n" +
  "=====================================\n" +
  "\n" +
  "While occupied and healthy, tracks time with |zoneTemp - zoneTempSp|\n" +
  "above band, and how many times it went out of band. On the 1st of\n" +
  "each month it appends last month's row to csvPath and resets.\n" +
  "\n" +
  "Setup:\n" +
  "  1. Link zoneTemp and zoneTempSp.\n" +
  "  2. Pick occMode, then link occBool or occEnum.\n" +
  "     Enum: list occupied states in occStates, e.g. occupied,standby\n" +
  "  3. Link a CurrentTime block to tick.\n" +
  "  4. Optional: change band (default 2.0) and csvPath.\n" +
  "\n" +
  "Time buckets:\n" +
  "  occupiedTime   occupied, points healthy\n" +
  "  outOfBandTime  part of occupiedTime with |delta| > band\n" +
  "  noDataTime     occupied but temps bad, or occupancy bad\n" +
  "\n" +
  "Excursions re-arm once |delta| < band - 0.5.\n" +
  "\n" +
  "Actions:\n" +
  "  Test CSV - Random Rows  append 3-10 random rows to\n" +
  "                          'TEST <csv name>' (real CSV untouched)\n" +
  "  Reset Totals            clear this month's totals\n" +
  "\n" +
  "Copied or moved blocks clear their totals and start fresh.\n";

public void onStart() throws Exception
{
  setVersion(VERSION);
  setQuickGuide(QUICK_GUIDE);
  // Don't count station downtime: start timing from now.
  setLastTime(BAbsTime.make(BAbsTime.now().getMillis()));
  onExecute();
}

public void onExecute() throws Exception
{
  long nowMs = BAbsTime.now().getMillis();

  updateNames();
  checkOwner(nowMs);

  // 1) Add the time since the last run to the bucket it belongs to.
  accumulate(nowMs);

  // 2) Month rollover: write the finished month, then start fresh.
  String month = monthOf(nowMs);
  String period = getPeriod();
  if (period != null && period.length() > 0 && !period.equals(month))
  {
    writeMonthRow(period, nowMs);
    resetTotals(nowMs);
  }

  // 3) Outputs and the state that applies from now until next run.
  BStatusNumeric zt = getZoneTemp();
  BStatusNumeric sp = getZoneTempSp();
  boolean tempsOk = zt != null && sp != null
                 && zt.getStatus().isValid()
                 && sp.getStatus().isValid();

  double delta = 0;
  if (tempsOk)
  {
    delta = zt.getValue() - sp.getValue();
    setOut(new BStatusNumeric(delta, BStatus.ok));
    setPointStatus(new BStatusBoolean(false, BStatus.ok));
  }
  else
  {
    setOut(new BStatusNumeric(0, BStatus.nullStatus));
    setPointStatus(new BStatusBoolean(true, BStatus.fault));
  }

  int occ = readOccupancy();   // 1 occupied, 0 unoccupied, -1 bad
  double band = bandValue();
  double abs = Math.abs(delta);

  int state;
  if (occ < 0)          state = ST_NODATA;
  else if (occ == 0)    state = ST_UNOCC;
  else if (!tempsOk)    state = ST_NODATA;
  else if (abs > band)  state = ST_OUT;
  else                  state = ST_IN;

  // Excursions
  if (state == ST_UNOCC)
  {
    if (!isArmed()) setArmed(true);
  }
  else if (state == ST_OUT)
  {
    if (isArmed())
    {
      setExcursions(getExcursions() + 1);
      setArmed(false);
    }
  }
  else if (state == ST_IN)
  {
    if (!isArmed() && abs < band - REARM) setArmed(true);
  }
  // ST_NODATA: leave armed as is.

  if (getPrevState() != state) setPrevState(state);

  updatePct();
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// Actions
// ----------------------------------------------------

// Append 3-10 rows of random values to "TEST <csv name>". Real CSV and
// totals are not touched.
public void onTestCsv() throws Exception
{
  updateNames();

  java.io.File real = resolveToFile(csvPathString());
  if (real == null) return;
  java.io.File test = new java.io.File(real.getParentFile(), "TEST " + real.getName());

  long nowMs = BAbsTime.now().getMillis();
  String month = monthOf(nowMs);
  String bldg  = getBuildingNum();
  String equip = getEquipName();
  if (equip == null || equip.length() == 0 || equip.startsWith("?")) equip = "TEST_EQUIP";

  java.util.Random r = new java.util.Random();
  int n = 3 + r.nextInt(8);
  int written = 0;
  for (int i = 0; i < n; i++)
  {
    double band = bandValue();
    long occMs = (long) ((120 + r.nextDouble() * 120) * 3600000.0);
    long outMs = (long) (occMs * r.nextDouble() * 0.3);
    long ndMs  = (long) (r.nextDouble() * 8 * 3600000.0);
    int exc    = r.nextInt(40);
    String line = buildRow(month, bldg, equip, band, occMs, outMs, exc, ndMs, nowMs);
    if (appendRow(test, line)) written++;
    else break;
  }

  if (written > 0)
    setCsvStatus("[" + now() + "] TEST: appended " + written + " row(s) to " + test.getName());
}

public void onResetTotals() throws Exception
{
  resetTotals(BAbsTime.now().getMillis());
  updatePct();
  setCsvStatus("[" + now() + "] Totals reset for " + getPeriod());
}

// ----------------------------------------------------
// Totals
// ----------------------------------------------------

private void accumulate(long nowMs)
{
  long last = 0;
  try
  {
    BAbsTime lt = getLastTime();
    if (lt != null) last = lt.getMillis();
  }
  catch (Throwable ignore) {}

  long e = nowMs - last;
  if (last > 0 && e > 0)
  {
    int st = getPrevState();
    if (st == ST_IN || st == ST_OUT)
      setOccupiedTime(BRelTime.make(getOccupiedTime().getMillis() + e));
    if (st == ST_OUT)
      setOutOfBandTime(BRelTime.make(getOutOfBandTime().getMillis() + e));
    if (st == ST_NODATA)
      setNoDataTime(BRelTime.make(getNoDataTime().getMillis() + e));
  }
  setLastTime(BAbsTime.make(nowMs));
}

private void resetTotals(long nowMs)
{
  setOccupiedTime(BRelTime.make(0));
  setOutOfBandTime(BRelTime.make(0));
  setNoDataTime(BRelTime.make(0));
  setExcursions(0);
  setArmed(true);
  setPeriod(monthOf(nowMs));
  setLastTime(BAbsTime.make(nowMs));
}

private void updatePct()
{
  long occ = getOccupiedTime().getMillis();
  double pct = occ > 0 ? 100.0 * getOutOfBandTime().getMillis() / occ : 0;
  pct = Math.round(pct * 10) / 10.0;
  if (getPctOutOfBand() != pct) setPctOutOfBand(pct);
}

// A copied or moved block (or a brand new one) clears what it brought
// with it and starts counting from now.
private void checkOwner(long nowMs)
{
  String path = "";
  try
  {
    javax.baja.sys.BComponent self = (javax.baja.sys.BComponent) getComponent();
    if (self != null) path = self.getSlotPath().toString();
  }
  catch (Throwable ignore) {}
  if (path.length() == 0) return;

  String owner = getOwnerPath();
  if (owner == null || !owner.equals(path))
  {
    resetTotals(nowMs);
    setPrevState(ST_UNOCC);
    setOwnerPath(path);
  }
}

private double bandValue()
{
  double b = getBand();
  return (b > 0) ? b : DEFAULT_BAND;
}

// ----------------------------------------------------
// Occupancy
// ----------------------------------------------------

// 1 occupied, 0 unoccupied, -1 point bad / missing.
private int readOccupancy()
{
  int mode = occModeOrdinal();
  try
  {
    if (mode == 2)
    {
      BStatusEnum se = getOccEnum();
      if (se == null || !se.getStatus().isValid()) return -1;
      BEnum e = se.getValue();
      return enumIsOccupied(e) ? 1 : 0;
    }

    BStatusBoolean sb = getOccBool();
    if (sb == null || !sb.getStatus().isValid()) return -1;
    boolean v = sb.getValue();
    if (mode == 1) return v ? 0 : 1;
    return v ? 1 : 0;
  }
  catch (Throwable t)
  {
    return -1;
  }
}

// Same tag-matching approach as ComponentCopier's mode selector.
private int occModeOrdinal()
{
  try
  {
    BStatusEnum m = getOccMode();
    String tag = (m != null) ? m.toString() : "";
    if (tag.indexOf("FalseOcc") >= 0) return 1;
    if (tag.indexOf("Enum") >= 0) return 2;
  }
  catch (Throwable ignore) {}
  return 0;
}

// True if the enum's tag or ordinal is listed in occStates.
private boolean enumIsOccupied(BEnum e)
{
  if (e == null) return false;
  String tag = norm(e.getTag());
  String ord = String.valueOf(e.getOrdinal());

  String list = getOccStates();
  if (list == null || list.trim().length() == 0) list = "occupied";
  String[] parts = list.split(",");
  for (int i = 0; i < parts.length; i++)
  {
    String p = norm(parts[i]);
    if (p.length() == 0) continue;
    if (p.equals(tag) || p.equals(ord)) return true;
  }
  return false;
}

// Lower case, no spaces or underscores, $xx escapes decoded.
private String norm(String s)
{
  if (s == null) return "";
  s = unescapeName(s.trim()).toLowerCase();
  StringBuffer b = new StringBuffer();
  for (int i = 0; i < s.length(); i++)
  {
    char c = s.charAt(i);
    if (c != ' ' && c != '_') b.append(c);
  }
  return b.toString();
}

// ----------------------------------------------------
// CSV
// ----------------------------------------------------

private void writeMonthRow(String month, long nowMs)
{
  java.io.File f = resolveToFile(csvPathString());
  if (f == null) return;
  String line = buildRow(month, getBuildingNum(), getEquipName(), bandValue(),
    getOccupiedTime().getMillis(), getOutOfBandTime().getMillis(),
    getExcursions(), getNoDataTime().getMillis(), nowMs);
  if (appendRow(f, line))
    setCsvStatus("[" + now() + "] Wrote " + month + " row to " + f.getName());
}

private String buildRow(String month, String bldg, String equip, double band,
                        long occMs, long outMs, int exc, long ndMs, long nowMs)
{
  double occH = occMs / 3600000.0;
  double outH = outMs / 3600000.0;
  double ndH  = ndMs  / 3600000.0;
  double pct  = occMs > 0 ? 100.0 * outMs / occMs : 0;

  return csv(month) + "," + csv(bldg) + "," + csv(equip) + "," +
    fmt(band, 1) + "," + fmt(occH, 2) + "," + fmt(outH, 2) + "," +
    fmt(pct, 1) + "," + exc + "," + fmt(ndH, 2) + "," +
    csv(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(nowMs)));
}

// Append one row, writing the header first if the file is new or empty.
// Locked on the file path so blocks rolling over together can't
// interleave or double the header.
private boolean appendRow(java.io.File f, String line)
{
  String key;
  try
  {
    key = f.getCanonicalPath().intern();
  }
  catch (Exception e)
  {
    key = f.getAbsolutePath().intern();
  }

  synchronized (key)
  {
    java.io.BufferedWriter bw = null;
    try
    {
      java.io.File parent = f.getParentFile();
      if (parent != null && !parent.exists()) parent.mkdirs();
      boolean needHeader = !f.exists() || f.length() == 0;

      bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
        new java.io.FileOutputStream(f, true), "UTF-8"));
      if (needHeader)
      {
        bw.write(CSV_HEADER);
        bw.newLine();
      }
      bw.write(line);
      bw.newLine();
      return true;
    }
    catch (Exception e)
    {
      setCsvStatus("[" + now() + "] CSV ERROR: " + e.getMessage());
      return false;
    }
    finally
    {
      if (bw != null) try { bw.close(); } catch (Exception ignore) {}
    }
  }
}

private String csvPathString()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("csvPath");
    if (ord != null && !ord.isNull() && ord.toString().trim().length() > 0)
      return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return DEFAULT_CSV;
}

private String csv(String s)
{
  if (s == null) return "";
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0)
    return "\"" + s.replace("\"", "\"\"") + "\"";
  return s;
}

private String fmt(double v, int dp)
{
  return String.format(java.util.Locale.US, "%." + dp + "f", v);
}

private String monthOf(long ms)
{
  return new java.text.SimpleDateFormat("yyyy-MM").format(new java.util.Date(ms));
}

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// ----------------------------------------------------
// File path resolution (from ComponentCopier / ForceRemove)
// "file:^Monthly VAV health.csv" -> <station home>/Monthly VAV health.csv
// Writes outside the station home folder are refused.
// ----------------------------------------------------
private java.io.File resolveToFile(String pathOrOrd)
{
  if (pathOrOrd == null) return null;
  String p = pathOrOrd.trim();
  if (p.length() == 0) return null;

  if (p.startsWith("file:")) p = p.substring(5);

  boolean stationRelative = false;
  if (p.startsWith("^"))
  {
    stationRelative = true;
    p = p.substring(1);
    while (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
  }

  java.io.File f = new java.io.File(p);
  if (stationRelative || !f.isAbsolute())
    f = new java.io.File(javax.baja.sys.Sys.getStationHome(), p);

  return sandboxToStationHome(f, pathOrOrd);
}

private java.io.File sandboxToStationHome(java.io.File f, String original)
{
  try
  {
    java.io.File home = javax.baja.sys.Sys.getStationHome().getCanonicalFile();
    java.io.File canon = f.getCanonicalFile();
    if (canon.equals(home) ||
        canon.getPath().startsWith(home.getPath() + java.io.File.separator))
      return canon;
  }
  catch (Exception e)
  {
    setCsvStatus("[" + now() + "] PATH ERROR: could not verify '" + original +
      "' - " + e.getMessage());
    return null;
  }
  setCsvStatus("[" + now() + "] PATH SANDBOX: refusing '" + original +
    "' - resolves outside the station home folder");
  return null;
}

// ----------------------------------------------------
// equipName / buildingNum (from ZoneTempDelta)
// ----------------------------------------------------

private String foundEquip;
private String foundBldg;

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

private String buildingPrefix(String n)
{
  if (n == null) return null;
  java.util.regex.Matcher m =
    java.util.regex.Pattern.compile("^([Bb][0-9]+)_").matcher(n);
  return m.find() ? m.group(1).toUpperCase() : null;
}

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

private String stripBuilding(String n)
{
  String t = n.replaceFirst("^[Bb][0-9]+_", "");
  return t.length() > 0 ? t : n;
}

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
