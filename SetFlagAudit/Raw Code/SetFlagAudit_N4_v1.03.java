/*
================================================================================
Program: SetFlagAudit - Niagara N4.15
Author:  F. Lacroix
Version: v1.03
Date:    2026-11-05

Walks the station from rootOrd, finds components that have any of the
slots listed in slotNames (default "set"; actions or properties, e.g.
"set, fallback"), reads the INSTANCE flags on each and reports them to
slots + CSV. One row per component per slot found.

  Instance flags = component.getFlags(slot)   (Slot has no getFlags())
  Default flags  = slot.getDefaultFlags()     (frozen type default)
  Modified       = the two differ

rootOrd modes
-------------
  Component  e.g. station:|slot:/Drivers
             Walks the whole subtree under that component.
  BQL/NEQL   e.g. station:|slot:/Drivers|bql:select * from control:ControlPoint
             Every row returned is walked as its own root, so a query can
             return points directly or the equipment folders that hold
             them. A component reached from two rows is counted once.

Changes
-------
  pre-   Development builds v1.00-v1.11. Version numbering restarts at
  v1.00  v1.00 for the first release.
  v1.00  Initial release:
           - rootOrd: component (subtree walk) or BQL/NEQL query (each
             row walked as its own root, duplicates counted once).
           - Slot Name(s): comma list of actions or properties
             (e.g. set, fallback); one row per slot found.
           - Filter by Name and Flag required filters.
           - Instance flags vs type default; MODIFIED with added /
             removed flags.
           - Output CSV file: file (overwritten) or directory
             (timestamped file per run), station-home only.
             Include Modified Details adds Modified, DefaultFlags,
             Added, Removed columns.
           - Outputs written by name; missing output slots reported
             in status instead of breaking the compile.
           - version and quickGuide slots set on start and Execute.
  v1.01  Common helper block (testing/Common, c1):
           - Directory CSV output keeps the newest Max Archives files
             (maxArchives slot, default 10, 0 = keep all) - they no
             longer pile up.
           - Log file per run (logFilePath, default logs/SetFlagAudit.log),
             archived and pruned like the CSVs; status lines also go to
             the Application Director.
           - A csvFile outside the station folder says why in status.
           - CSV fields quoted only when needed, and text a spreadsheet
             would run as a formula (= + - @ first) gets a leading '.
           - Error text shows the real cause (describeException).
  v1.02  Property sheet in a sensible order: on start the program sorts its
         own slots (Common order tier, orderSlots): about, last run, what
         to search, output files. Copies and upgraded objects sort
         themselves. No slot or import changes.
  v1.03  Search code (Root Location or BQL, output slots) now comes from
         the Common search tier, shared with SlotRenamerDisplayName. Error
         text says "Root Location or BQL" (was "rootOrd"). Examples use
         placeholder point names. No slot or import changes.

================================================================================
*/

private static final String VERSION = "v1.03";

// Property-sheet order (Common orderSlots): grouped the way a user reads it.
// Slots not listed (internal ones, or added later) go after these.
private static final String[] SLOT_ORDER = {
  // about
  "version", "quickGuide",
  // last run
  "status", "lastRun", "scanned", "matched", "modified", "summary", "results",
  // what to search
  "rootOrd", "slotNames", "nameFilter", "requireFlags", "maxResults",
  // output files
  "csvFile", "includeDetail", "logFilePath", "maxArchives"
};
private static final String LOG_TAG = "SetFlagAudit";
private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("SetFlagAudit");

// On-station user help -- written into the read-only quickGuide slot
// during onStart() / Execute so it shows up on the property sheet.
private static final String QUICK_GUIDE =
  "SetFlagAudit " + VERSION + "\n" +
  "=====================================\n" +
  "\n" +
  "Reports the flags set on chosen slots (actions or properties) of\n" +
  "every component under Root Location or BQL. Instance flags are\n" +
  "compared to the type default; a difference is flagged MODIFIED.\n" +
  "\n" +
  "Run:\n" +
  "  Execute - run the scan. Keep Execute On Change OFF.\n" +
  "\n" +
  "Root Location or BQL:\n" +
  "  Component  walks the whole subtree\n" +
  "    station:|slot:/Drivers/BacnetNetwork\n" +
  "  BQL/NEQL   each row is walked as its own root (points or folders)\n" +
  "    station:|slot:/Drivers|bql:select * from control:ControlPoint\n" +
  "      where name like '*Flow_SP'\n" +
  "\n" +
  "Filters:\n" +
  "  Slot Name(s)       comma list, actions or properties. Blank = set\n" +
  "                       set, fallback\n" +
  "  Filter by Name     component name contains (case-insensitive)\n" +
  "                     Blank = all\n" +
  "  Flag required      comma list, ALL must be set on the slot\n" +
  "                       operator, confirmRequired\n" +
  "                     Blank = all\n" +
  "  Max. Results       caps CSV rows + Results lines\n" +
  "                     0 = no CSV cap, 200 Results lines\n" +
  "\n" +
  "Flag names:\n" +
  "  readonly transient hidden summary async noRun defaultOnClone\n" +
  "  confirmRequired operator noAudit composite removeOnClone metadata\n" +
  "  linkTarget nonCritical userDefined1-4\n" +
  "\n" +
  "Output CSV file:\n" +
  "  File      file:^reports/Flags.csv  (overwritten each run)\n" +
  "  Directory file:^reports  (new SetFlagAudit_<timestamp>.csv per run)\n" +
  "  (folder: newest Max Archives files kept, 0 = all)\n" +
  "  Must be under the station home. Blank = no CSV.\n" +
  "  Include Modified Details:\n" +
  "    Show Simple Details    Name, Path, Slot, Flags\n" +
  "    Show Modified Details  adds Modified, DefaultFlags, Added, Removed\n" +
  "\n" +
  "Log file: logFilePath (blank = logs/SetFlagAudit.log), one per run,\n" +
  "  archived and pruned with Max Archives.\n" +
  "\n" +
  "Outputs:\n" +
  "  Results                    one line per slot: Name.slot : flags\n" +
  "  Summary                    Slots Matched as per filters,\n" +
  "                             Previously modified,\n" +
  "                             per-flag counts\n" +
  "  Status                     result line; lists MISSING SLOTS if an\n" +
  "                             output is absent\n" +
  "  LastRun                    time of last completed scan\n" +
  "  Slots Scanned              components walked\n" +
  "  Slots Matched              slot rows that passed the filters\n" +
  "  Previously Modified Slots  matched rows that differ from the\n" +
  "                             type default\n";

// Max lines written to the "results" slot when maxResults = 0
private static final int RESULTS_CAP = 200;

private static final String[] FLAG_NAMES = {
  "readonly", "transient", "hidden", "summary", "async", "noRun",
  "defaultOnClone", "confirmRequired", "operator",
  "noAudit", "composite", "removeOnClone", "metadata", "linkTarget",
  "nonCritical", "userDefined1", "userDefined2", "userDefined3", "userDefined4"
};

private static final int[] FLAG_VALUES = {
  Flags.READONLY, Flags.TRANSIENT, Flags.HIDDEN, Flags.SUMMARY, Flags.ASYNC, Flags.NO_RUN,
  Flags.DEFAULT_ON_CLONE, Flags.CONFIRM_REQUIRED, Flags.OPERATOR,
  Flags.NO_AUDIT, Flags.COMPOSITE, Flags.REMOVE_ON_CLONE, Flags.METADATA, Flags.LINK_TARGET,
  Flags.NON_CRITICAL, Flags.USER_DEFINED_1, Flags.USER_DEFINED_2, Flags.USER_DEFINED_3, Flags.USER_DEFINED_4
};

// ---------------------------------------------------------------------
// Lifecycle / Actions
// ---------------------------------------------------------------------

public void onStart() throws Exception
{
  updateVersion();
  updateQuickGuide();
  orderSlots(SLOT_ORDER);
  reportInfo(VERSION + " Ready");
}

public void onExecute() throws Exception
{
  updateVersion();
  updateQuickGuide();
  runScan();
}

// ---------------------------------------------------------------------
// Scan
// ---------------------------------------------------------------------

private void runScan()
{
  long t0 = System.currentTimeMillis();
  archiveAndPrune(resolveLogPath(), "LOG");
  reportInfo("Running...");

  int scanned = 0;
  int withSlot = 0;
  int matched = 0;
  int modifiedCount = 0;
  int written = 0;
  int[] flagCounts = new int[FLAG_VALUES.length];

  StringBuffer csv = new StringBuffer();
  boolean detail = getIncludeDetail();
  csv.append("Name,Path,Slot,Flags");
  if (detail) csv.append(",Modified,DefaultFlags,Added,Removed");
  csv.append('\n');

  StringBuffer results = new StringBuffer();
  int resultLines = 0;

  try
  {
    String rootNote = "";
    ArrayList<BComponent> roots = new ArrayList<BComponent>();
    String rootErr = resolveRoots(roots);
    if (rootErr != null)
    {
      reportError("ERROR: " + rootErr);
      return;
    }
    rootNote = (roots.size() == 1) ? "" : " | " + roots.size() + " query rows";

    String[] slotList = parseSlotNames(getSlotNames());
    String   slotDesc = joinNames(slotList);
    String nameF    = trimOr(getNameFilter(), "").toLowerCase();
    int    required = parseFlags(getRequireFlags());
    int    max      = getMaxResults();
    int    resCap   = (max > 0) ? max : RESULTS_CAP;

    HashSet<BComponent> seen = new HashSet<BComponent>();
    ArrayList<BComponent> stack = new ArrayList<BComponent>();
    for (int r = roots.size() - 1; r >= 0; r--) stack.add(roots.get(r));

    while (!stack.isEmpty())
    {
      BComponent c = stack.remove(stack.size() - 1);
      if (!seen.add(c)) continue;

      BComponent[] kids = c.getChildComponents();
      for (int i = kids.length - 1; i >= 0; i--) stack.add(kids[i]);

      scanned++;

      String rawName = c.getName();
      String name = (rawName == null) ? "" : SlotPath.unescape(rawName);
      if (nameF.length() > 0 && name.toLowerCase().indexOf(nameF) < 0) continue;

      String path = null;

      // One row per slot name found on this component (action or property)
      for (int n = 0; n < slotList.length; n++)
      {
        Slot s = c.getSlot(slotList[n]);
        if (s == null) continue;
        withSlot++;

        int flags = c.getFlags(s);
        int def   = s.getDefaultFlags();
        boolean mod = (flags != def);

        if (required != 0 && (flags & required) != required) continue;

        matched++;
        if (mod) modifiedCount++;
        for (int k = 0; k < FLAG_VALUES.length; k++)
          if ((flags & FLAG_VALUES[k]) != 0) flagCounts[k]++;

        if (resultLines < resCap)
        {
          String f = decode(flags);
          results.append(name).append('.').append(slotList[n]).append(" : ")
                 .append(f.length() == 0 ? "(none)" : f);
          if (mod)
          {
            String add = decode(flags & ~def);
            String rem = decode(def & ~flags);
            results.append("   *MODIFIED");
            if (add.length() > 0) results.append(" +").append(add);
            if (rem.length() > 0) results.append(" -").append(rem);
          }
          results.append('\n');
          resultLines++;
        }

        if (max <= 0 || written < max)
        {
          if (path == null)
            path = (c.getSlotPath() == null) ? "" : c.getSlotPath().toString();
          csv.append(csvEscape(name)).append(',')
             .append(csvEscape(path)).append(',')
             .append(csvEscape(slotList[n])).append(',')
             .append(csvEscape(decode(flags)));
          if (detail)
          {
            csv.append(',').append(mod ? "Y" : "N")
               .append(',').append(csvEscape(decode(def)))
               .append(',').append(csvEscape(decode(flags & ~def)))
               .append(',').append(csvEscape(decode(def & ~flags)));
          }
          csv.append('\n');
          written++;
        }
      }
    }

    // Summary
    StringBuffer sum = new StringBuffer();
    sum.append("Slots Matched as per filters: ").append(matched)
       .append(" | Previously modified: ").append(modifiedCount)
       .append(" | ");
    boolean any = false;
    for (int k = 0; k < FLAG_VALUES.length; k++)
    {
      if (flagCounts[k] == 0) continue;
      if (any) sum.append(", ");
      sum.append(FLAG_NAMES[k]).append('=').append(flagCounts[k]);
      any = true;
    }
    if (!any) sum.append("no flags set");

    StringBuffer missing = new StringBuffer();
    setOut("scanned",  BInteger.make(scanned),       missing);
    setOut("matched",  BInteger.make(matched),       missing);
    setOut("modified", BInteger.make(modifiedCount), missing);
    setOut("summary",  BString.make(sum.toString()), missing);
    if (matched > resultLines)
      results.append("... ").append(matched - resultLines).append(" more (see CSV)\n");
    setOut("results",  BString.make(results.toString()), missing);

    String csvNote = "";
    BOrd csvOrd = getCsvFile();
    if (csvOrd != null && !csvOrd.isNull() && csvOrd.toString().trim().length() > 0)
    {
      java.io.File out = resolveOutputFile(csvOrd.toString().trim(), LOG_TAG + ".csv", true);
      if (out == null)
      {
        csvNote = " | CSV NOT WRITTEN: " + lastPathError;
      }
      else
      {
        writeTextFile(out, csv.toString());
        pruneRunFiles(out);
        csvNote = " | CSV " + written + " rows -> " + out.getName();
        if (max > 0 && matched > max) csvNote += " (capped at " + max + ")";
      }
    }

    long ms = System.currentTimeMillis() - t0;
    setOut("lastRun", BString.make(now()), missing);
    String missNote = (missing.length() > 0) ? " | MISSING SLOTS: " + missing : "";
    reportInfo("OK: scanned " + scanned + ", '" + slotDesc + "' found " + withSlot
              + ", matched " + matched + " in " + ms + " ms" + rootNote + csvNote + missNote);
  }
  catch (Throwable e)
  {
    reportError("ERROR: " + describeException(e));
  }
}

// rootOrd slot -> roots (BComponents). A component is one root (the caller
// walks its subtree); a BQL / NEQL query gives one root per row.
// Returns null on success, or the reason for status.
private String resolveRoots(java.util.ArrayList roots) throws Exception
{
  Object v = get("rootOrd");
  if (!(v instanceof javax.baja.naming.BOrd) ||
      ((javax.baja.naming.BOrd) v).isNull() ||
      v.toString().trim().length() == 0)
    return "Root Location or BQL not set";

  Object o = ((javax.baja.naming.BOrd) v).resolve(getComponent()).get();
  if (o instanceof javax.baja.sys.BComponent)
  {
    roots.add(o);
    return null;
  }

  // BQL / NEQL result table, walked via reflection so no
  // javax.baja.collection import is needed.
  try
  {
    java.lang.reflect.Method cursorMethod = o.getClass().getMethod("cursor", new Class[0]);
    Object cursor = cursorMethod.invoke(o, new Object[0]);
    java.lang.reflect.Method nextMethod  = cursor.getClass().getMethod("next", new Class[0]);
    java.lang.reflect.Method getMethod   = cursor.getClass().getMethod("get", new Class[0]);
    java.lang.reflect.Method closeMethod = cursor.getClass().getMethod("close", new Class[0]);
    try
    {
      while (((Boolean) nextMethod.invoke(cursor, new Object[0])).booleanValue())
      {
        Object row = getMethod.invoke(cursor, new Object[0]);
        if (row instanceof javax.baja.sys.BComponent) roots.add(row);
      }
    }
    finally { closeMethod.invoke(cursor, new Object[0]); }
  }
  catch (Exception e)
  {
    return "Root Location resolved to " + o.getClass().getName()
         + " (not a component or query): " + describeException(e);
  }

  if (roots.isEmpty()) return "Root Location query returned no components";
  return null;
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

// Writes an output slot by name without depending on generated setters.
// A missing / wrong-type slot is added to `missing` (shown in status).
private void setOut(String name, javax.baja.sys.BValue v, StringBuffer missing)
{
  try
  {
    javax.baja.sys.BComponent me = getComponent();
    javax.baja.sys.Slot s = me.getSlot(name);
    if (s != null && s.isProperty())
    {
      me.set(s.asProperty(), v);
      return;
    }
  }
  catch (Throwable e) { }
  if (missing.length() > 0) missing.append(", ");
  missing.append(name);
}

// Reflection-based setter for the version slot - no hard dependency on the
// generated setVersion() signature.
private void updateVersion()
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setVersion", new Class[]{ String.class });
    m.invoke(this, new Object[]{ VERSION });
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setVersion", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, new Object[]{ javax.baja.sys.BString.make(VERSION) });
    }
    catch (Exception ignore2) {}
  }
}

// Same pattern for the quickGuide slot.
private void updateQuickGuide()
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setQuickGuide", new Class[]{ String.class });
    m.invoke(this, new Object[]{ QUICK_GUIDE });
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setQuickGuide", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, new Object[]{ javax.baja.sys.BString.make(QUICK_GUIDE) });
    }
    catch (Exception ignore2) {}
  }
}

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// slotNames: comma/space list of slot names, e.g. "set, fallback".
// Blank = "set". Duplicates dropped, order kept.
private String[] parseSlotNames(String s)
{
  String t = trimOr(s, "set");
  String[] parts = t.split("[,;\\s]+");
  ArrayList<String> out = new ArrayList<String>();
  for (int i = 0; i < parts.length; i++)
  {
    String p = parts[i].trim();
    if (p.length() > 0 && !out.contains(p)) out.add(p);
  }
  if (out.isEmpty()) out.add("set");
  return out.toArray(new String[out.size()]);
}

private String joinNames(String[] a)
{
  StringBuffer sb = new StringBuffer();
  for (int i = 0; i < a.length; i++)
  {
    if (i > 0) sb.append(',');
    sb.append(a[i]);
  }
  return sb.toString();
}

// requireFlags: comma/space list of names from FLAG_NAMES, case-insensitive
// e.g. "operator" or "operator, confirmRequired"
private int parseFlags(String s)
{
  String t = trimOr(s, "");
  if (t.length() == 0) return 0;
  int mask = 0;
  String[] parts = t.split("[,;\\s]+");
  for (int i = 0; i < parts.length; i++)
  {
    String p = parts[i].trim();
    if (p.length() == 0) continue;
    for (int k = 0; k < FLAG_NAMES.length; k++)
    {
      if (FLAG_NAMES[k].equalsIgnoreCase(p)) { mask |= FLAG_VALUES[k]; break; }
    }
  }
  return mask;
}

private String decode(int flags)
{
  if (flags == 0) return "";
  StringBuffer sb = new StringBuffer();
  int known = 0;
  for (int k = 0; k < FLAG_VALUES.length; k++)
  {
    known |= FLAG_VALUES[k];
    if ((flags & FLAG_VALUES[k]) != 0)
    {
      if (sb.length() > 0) sb.append('|');
      sb.append(FLAG_NAMES[k]);
    }
  }
  int unknown = flags & ~known;
  if (unknown != 0)
  {
    if (sb.length() > 0) sb.append('|');
    sb.append("unknown(0x").append(Integer.toHexString(unknown)).append(')');
  }
  return sb.toString();
}

private String trimOr(String s, String def)
{
  if (s == null) return def;
  String t = s.trim();
  return (t.length() == 0) ? def : t;
}



// Canonicalizes f and checks it is inside the station home, so neither an
// absolute path nor "../" can reach the wider file system. On refusal: null,
// lastPathError set, and the reason goes to status and the App Director.
private java.io.File sandboxToStationHome(java.io.File f, String original)
{
  String msg;
  try
  {
    java.io.File home = javax.baja.sys.Sys.getStationHome().getCanonicalFile();
    java.io.File canon = f.getCanonicalFile();
    if (canon.equals(home) ||
        canon.getPath().startsWith(home.getPath() + java.io.File.separator))
      return canon;
    msg = "PATH REFUSED: '" + original + "' is outside the station folder";
  }
  catch (Exception e)
  {
    msg = "PATH ERROR: could not check '" + original + "' - " + e.getMessage();
  }
  lastPathError = msg;
  setStatus("[" + now() + "] " + msg);
  log.warning("[" + LOG_TAG + "] " + msg);
  return null;
}


// ============================================ COMMON (sync_common.py) ===
private String lastPathError = null;

// Readable reason from an exception - never empty or "null". Unwraps
// reflection wrappers so the real cause shows.
private String describeException(Throwable t)
{
  if (t == null) return "unknown error";
  while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
    t = t.getCause();

  StringBuilder sb = new StringBuilder();
  String cls = t.getClass().getSimpleName();
  String msg = t.getMessage();

  if (msg != null && msg.trim().length() > 0)
    sb.append(cls).append(": ").append(msg.trim());
  else
    sb.append(cls).append(" (no detail message)");

  Throwable cause = t.getCause();
  if (cause != null && cause != t)
  {
    sb.append(" [cause: ").append(cause.getClass().getSimpleName());
    String cmsg = cause.getMessage();
    if (cmsg != null && cmsg.trim().length() > 0)
      sb.append(": ").append(cmsg.trim());
    sb.append("]");
  }
  return sb.toString();
}

// A baja:Ord slot as text, or def when the slot is missing, null or blank.
private String ordSlot(String slot, String def)
{
  try
  {
    Object o = get(slot);
    if (o instanceof javax.baja.naming.BOrd)
    {
      javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) o;
      String s = ord.toString().trim();
      if (!ord.isNull() && s.length() > 0 && !s.equals("null")) return s;
    }
  }
  catch (Exception ignore) {}
  return def;
}

// A baja:Integer slot, or def when the slot is missing or unreadable.
private int intSlot(String slot, int def)
{
  try
  {
    Object o = get(slot);
    if (o instanceof javax.baja.sys.BInteger)
      return ((javax.baja.sys.BInteger) o).getInt();
  }
  catch (Exception ignore) {}
  return def;
}

// "file:^logs/x.log", "^logs/x.log", "logs/x.log", "local:|file:^logs/x.log"
// -> a file under the station home. Anything that resolves outside the
// station home is refused (null) - see sandboxToStationHome().
private java.io.File resolveToFile(String pathOrOrd)
{
  lastPathError = null;
  if (pathOrOrd == null) return null;
  String p = pathOrOrd.trim();
  if (p.length() == 0) return null;

  // "local:|file:^x" / "station:|file:^x" -> "file:^x"
  int bar = p.lastIndexOf('|');
  if (bar >= 0 && p.substring(bar + 1).trim().startsWith("file:"))
    p = p.substring(bar + 1).trim();

  if (p.startsWith("file:")) p = p.substring(5);

  // "^" = relative to the station home
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

private void appendLine(String filePath, String line)
{
  java.io.BufferedWriter bw = null;
  try
  {
    java.io.File file = resolveToFile(filePath);
    if (file == null) return;

    java.io.File parent = file.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();

    // append=true creates the file if it does not exist yet
    bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
      new java.io.FileOutputStream(file, true), "UTF-8"));
    bw.write(line);
    bw.newLine();
  }
  catch (Exception e)
  {
    setStatus("[" + now() + "] FILE ERROR: " + e.getMessage());
  }
  finally
  {
    if (bw != null) try { bw.close(); } catch (Exception ignore) {}
  }
}

private void clearFile(String filePath)
{
  java.io.FileOutputStream fos = null;
  try
  {
    java.io.File file = resolveToFile(filePath);
    if (file == null) return;

    java.io.File parent = file.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();

    // append=false truncates if it exists, creates if it doesn't
    fos = new java.io.FileOutputStream(file, false);
  }
  catch (Exception e)
  {
    setStatus("[" + now() + "] CLEAR ERROR: " + e.getMessage());
  }
  finally
  {
    if (fos != null) try { fos.close(); } catch (Exception ignore) {}
  }
}

// "<dir>/name.ext" -> "<dir>/name_yyyy-MM-dd_HH-mm-ss.ext"
private String buildTimestampedArchivePath(String filePath)
{
  String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
    .format(new java.util.Date());

  int slashIdx = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
  int dotIdx = filePath.lastIndexOf('.');

  if (dotIdx > slashIdx)
    return filePath.substring(0, dotIdx) + "_" + ts + filePath.substring(dotIdx);
  return filePath + "_" + ts;
}

// Moves the active file to its timestamped archive name (rename, or copy +
// truncate if rename fails). Empty or missing files are left alone.
private void archiveFile(String activePath, String label)
{
  try
  {
    java.io.File activeFile = resolveToFile(activePath);
    if (activeFile == null || !activeFile.exists() || activeFile.length() == 0)
      return;

    String archivePath = buildTimestampedArchivePath(activePath);
    java.io.File archiveFile = resolveToFile(archivePath);
    if (archiveFile == null) return;

    java.io.File parent = archiveFile.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();

    if (!activeFile.renameTo(archiveFile))
    {
      java.io.FileInputStream  fis = null;
      java.io.FileOutputStream fos = null;
      try
      {
        fis = new java.io.FileInputStream(activeFile);
        fos = new java.io.FileOutputStream(archiveFile, false);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
      }
      finally
      {
        if (fis != null) try { fis.close(); } catch (Exception ignore) {}
        if (fos != null) try { fos.close(); } catch (Exception ignore) {}
      }
      clearFile(activePath);
    }
  }
  catch (Exception e)
  {
    writeToLog(label + " ARCHIVE ERROR: " + e.getMessage());
  }
}

// Deletes the oldest <name>_yyyy-MM-dd_HH-mm-ss.<ext> archives of activePath
// beyond maxArchives. 0 or negative = keep all.
private void pruneArchives(String activePath)
{
  try
  {
    java.io.File activeFile = resolveToFile(activePath);
    if (activeFile == null) return;

    String fileName = activeFile.getName();
    int dotIdx = fileName.lastIndexOf('.');
    String stem = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
    String ext  = (dotIdx > 0) ? fileName.substring(dotIdx) : "";
    pruneArchiveFiles(activeFile.getParentFile(), stem, ext);
  }
  catch (Exception e)
  {
    writeToLog("PRUNE EXCEPTION on " + activePath + " - " + e.getMessage());
  }
}

private void archiveAndPrune(String activePath, String label)
{
  archiveFile(activePath, label);
  pruneArchives(activePath);
}

private String resolveLogPath()
{
  return ordSlot("logFilePath", "file:^logs/" + LOG_TAG + ".log");
}

private int resolveMaxArchives()
{
  return intSlot("maxArchives", 10);
}

private void writeToLog(String message)
{
  appendLine(resolveLogPath(), "[" + now() + "] " + message);
}

// Log file + Application Director in one call.
private void logInfo(String message)
{
  log.info("[" + LOG_TAG + "] " + message);
  writeToLog(message);
}

private void logWarn(String message)
{
  log.warning("[" + LOG_TAG + "] " + message);
  writeToLog(message);
}

private void logError(String message)
{
  log.severe("[" + LOG_TAG + "] " + message);
  writeToLog(message);
}

// One CSV field. Quotes when needed, and guards text that a spreadsheet
// would run as a formula (= + - @ first) with a leading apostrophe.
// Numbers such as -0.27 are left as numbers.
private String csvEscape(String s)
{
  if (s == null) s = "";
  if (s.length() > 0 && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !looksNumeric(s))
    s = "'" + s;
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ||
      s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
    return '"' + s.replace("\"", "\"\"") + '"';
  return s;
}

private boolean looksNumeric(String s)
{
  try
  {
    Double.parseDouble(s.trim());
    return true;
  }
  catch (Exception e)
  {
    return false;
  }
}

// Quote-aware CSV row parser ("a, ""b"", c" style). Fields are trimmed.
private String[] parseCsvRow(String line)
{
  if (line == null) return new String[0];
  java.util.ArrayList fields = new java.util.ArrayList();
  StringBuffer cur = new StringBuffer();
  boolean inQuote = false;
  int n = line.length();
  for (int i = 0; i < n; i++)
  {
    char c = line.charAt(i);
    if (inQuote)
    {
      if (c == '"')
      {
        if (i + 1 < n && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
        else inQuote = false;
      }
      else cur.append(c);
    }
    else
    {
      if (c == ',')
      {
        fields.add(cur.toString());
        cur.setLength(0);
      }
      else if (c == '"' && cur.length() == 0) inQuote = true;
      else cur.append(c);
    }
  }
  fields.add(cur.toString());
  String[] out = new String[fields.size()];
  for (int i = 0; i < fields.size(); i++) out[i] = ((String) fields.get(i)).trim();
  return out;
}

// ============================================ COMMON (sync_common.py) ===
// Deletes the oldest <stem>_yyyy-MM-dd_HH-mm-ss<ext> files in dir beyond
// maxArchives. 0 or negative = keep all.
private void pruneArchiveFiles(java.io.File parent, String stem, String ext)
{
  try
  {
    int max = resolveMaxArchives();
    if (max <= 0) return;
    if (parent == null || !parent.isDirectory()) return;
    int expectedLen = stem.length() + 1 + 19 + ext.length();

    java.io.File[] listed = parent.listFiles();
    if (listed == null) return;

    java.util.ArrayList names = new java.util.ArrayList();
    for (int i = 0; i < listed.length; i++)
    {
      java.io.File f = listed[i];
      if (!f.isFile()) continue;
      String n = f.getName();
      if (n.length() != expectedLen) continue;
      if (!n.startsWith(stem + "_")) continue;
      if (ext.length() > 0 && !n.endsWith(ext)) continue;
      if (n.equals(stem + ext)) continue;            // never the active file
      names.add(n);
    }
    if (names.size() <= max) return;

    String[] sorted = new String[names.size()];
    for (int i = 0; i < sorted.length; i++) sorted[i] = (String) names.get(i);
    java.util.Arrays.sort(sorted);                   // timestamp order

    int toDelete = sorted.length - max;
    for (int i = 0; i < toDelete; i++)
    {
      java.io.File f = new java.io.File(parent, sorted[i]);
      try
      {
        if (f.delete()) writeToLog("PRUNED archive: " + sorted[i]);
        else            writeToLog("PRUNE WARN: could not delete " + sorted[i]);
      }
      catch (Exception e)
      {
        writeToLog("PRUNE ERROR: " + sorted[i] + " - " + e.getMessage());
      }
    }
  }
  catch (Exception e)
  {
    writeToLog("PRUNE EXCEPTION in " + parent + " - " + e.getMessage());
  }
}

// An output slot that may name a file or a folder (csvFile, configFile...).
// Folder = an existing folder, a path ending in / or \, or a last part with
// no dot (file:^reports). In a folder the file is defaultName, or with
// timestamped = true <stem>_yyyy-MM-dd_HH-mm-ss<ext> (one per run - prune
// with pruneArchiveFiles). Null if refused (lastPathError says why).
private java.io.File resolveOutputFile(String pathOrOrd, String defaultName,
                                       boolean timestamped)
{
  java.io.File f = resolveToFile(pathOrOrd);
  if (f == null) return null;
  String p = pathOrOrd.trim();
  boolean isDir = p.endsWith("/") || p.endsWith("\\") || f.isDirectory() ||
                  f.getName().indexOf('.') < 0;
  if (!isDir) return f;
  String name = timestamped ? buildTimestampedArchivePath(defaultName) : defaultName;
  return new java.io.File(f, name);
}

// ============================================ COMMON (sync_common.py) ===
// status slot + App Director + log file in one call: "[time] message".
private void reportInfo(String message)
{
  setStatus("[" + now() + "] " + message);
  logInfo(message);
}

private void reportWarn(String message)
{
  setStatus("[" + now() + "] " + message);
  logWarn(message);
}

private void reportError(String message)
{
  setStatus("[" + now() + "] " + message);
  logError(message);
}

// ============================================ COMMON (sync_common.py) ===
// Writes text to f (UTF-8, overwrites), creating the folder if needed.
// Get f from resolveToFile / resolveOutputFile so it is station-home only.
private void writeTextFile(java.io.File f, String text) throws Exception
{
  java.io.File parent = f.getParentFile();
  if (parent != null && !parent.exists()) parent.mkdirs();
  java.io.FileOutputStream os = new java.io.FileOutputStream(f, false);
  try { os.write(text.getBytes("UTF-8")); }
  finally { os.close(); }
}

// After writing a per-run file from resolveOutputFile(..., true): if its
// name is <stem>_yyyy-MM-dd_HH-mm-ss<ext>, keep only the newest maxArchives
// of that stem in its folder. A fixed file name is left alone.
// No regex: the AX JACE compiler (J2ME) has no java.util.regex.
private void pruneRunFiles(java.io.File written)
{
  if (written == null) return;
  String n = written.getName();
  int dot = n.lastIndexOf('.');
  String ext = dot >= 0 ? n.substring(dot) : "";
  String base = dot >= 0 ? n.substring(0, dot) : n;
  String stamp = "_dddd-dd-dd_dd-dd-dd";              // d = digit
  if (base.length() <= stamp.length()) return;
  String tail = base.substring(base.length() - stamp.length());
  for (int i = 0; i < stamp.length(); i++)
  {
    char want = stamp.charAt(i);
    char c = tail.charAt(i);
    if (want == 'd' ? (c < '0' || c > '9') : c != want) return;
  }
  pruneArchiveFiles(written.getParentFile(),
                    base.substring(0, base.length() - stamp.length()), ext);
}

// ============================================ COMMON (sync_common.py) ===
// Puts this program's dynamic slots in the order a user should read them on
// the property sheet. Call from onStart:  orderSlots(SLOT_ORDER);
// Names not on this program are skipped; slots not listed keep their current
// order after the listed ones. Does nothing when the order is already right,
// so it is safe on every start (a copied or upgraded object gets sorted too).
private void orderSlots(String[] names)
{
  try
  {
    javax.baja.sys.BComponent c = getComponent();
    javax.baja.sys.Property[] cur = c.getDynamicPropertiesArray();
    java.util.ArrayList out = new java.util.ArrayList();
    for (int i = 0; i < names.length; i++)
      for (int k = 0; k < cur.length; k++)
        if (cur[k].getName().equals(names[i]) && !out.contains(cur[k]))
          out.add(cur[k]);
    for (int k = 0; k < cur.length; k++)
      if (!out.contains(cur[k])) out.add(cur[k]);
    boolean same = true;
    for (int k = 0; k < cur.length; k++)
      if (out.get(k) != cur[k]) { same = false; break; }
    if (same) return;
    javax.baja.sys.Property[] order = new javax.baja.sys.Property[out.size()];
    for (int k = 0; k < order.length; k++) order[k] = (javax.baja.sys.Property) out.get(k);
    c.reorder(order);
  }
  catch (Throwable ignore) {}
}

// ============================================ COMMON (sync_common.py) ===
// Name against a wildcard pattern, not case-sensitive. * = any run of
// characters (none included). No * = the whole name must match.
//   Boiler     only Boiler
//   Boiler*    starts with Boiler
//   *Boiler    ends with Boiler
//   *Boiler*   has Boiler anywhere
private boolean wildcardMatch(String name, String pattern)
{
  String t = (name == null) ? "" : name.toLowerCase();
  String p = (pattern == null) ? "" : pattern.trim().toLowerCase();
  int ti = 0, pi = 0, star = -1, mark = 0;
  while (ti < t.length())
  {
    if (pi < p.length() && p.charAt(pi) != '*' && p.charAt(pi) == t.charAt(ti))
    {
      ti++;
      pi++;
    }
    else if (pi < p.length() && p.charAt(pi) == '*')
    {
      star = pi++;
      mark = ti;
    }
    else if (star >= 0)
    {
      pi = star + 1;
      ti = ++mark;
    }
    else return false;
  }
  while (pi < p.length() && p.charAt(pi) == '*') pi++;
  return pi == p.length();
}

// Name against a comma list of wildcard patterns (wildcardMatch). Blank
// list = every name matches.
private boolean nameMatches(String name, String patterns)
{
  if (patterns == null || patterns.trim().length() == 0) return true;
  String rest = patterns;
  while (rest.length() > 0)
  {
    int comma = rest.indexOf(',');
    String one = (comma < 0) ? rest : rest.substring(0, comma);
    rest = (comma < 0) ? "" : rest.substring(comma + 1);
    if (one.trim().length() > 0 && wildcardMatch(name, one)) return true;
  }
  return false;
}
