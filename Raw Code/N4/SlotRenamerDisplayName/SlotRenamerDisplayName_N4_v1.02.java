/*
================================================================================
Program: SlotRenamerDisplayName - Niagara N4.15
Author:  F. Lacroix
Version: v1.02
Date:    2026-11-13

Companion to SetFlagEditor. Uses the same search (Root Location or BQL,
Filter by Name) and then CHANGES the display name of chosen slots
(actions, properties or child components) on every matched component:

  Rename Rules     one rule per line (or ; between rules)
                     set = Set Setpoint
                     Boiler*.set = Boiler Enable
                     emergencyOverride = Emergency Override
                   [point name.]slot name on the left, new display name
                   on the right. Point names take * wildcards.
                   "set =" (nothing after =) puts the slot back to its
                   default display name.
  Already Renamed  Skip       leave slots that already have their own
                              display name alone (default)
                   Overwrite  rename them too

Where display names live
------------------------
A slot's own display name is stored on the component that owns the slot,
in its hidden "displayNames" slot (baja:NameMap, e.g.
{set=Set Setpoint;fallback=Default Value;}) - the same place Workbench's
slot sheet "Set Display Name" writes. Other entries in that map are kept.
When the first name is added the slot is created (readonly, hidden,
operator - as Workbench does); when the last one is removed it is removed.

Actions: dryRun previews every change without writing anything;
execute writes them. Every matched slot is logged to the CSV with its
Before and After display name. Each write is read back; a slot that
doesn't take the new name is logged FAILED. Changes persist when the
station is saved.

This program and everything inside it is never edited.

Reverse (action)
----------------
Every LIVE run that changes at least one slot writes a reverse record
(station home, logs/SlotRenamerDisplayName_<programName>_reverse.csv) holding
each changed slot's path, slot name and exact Before / After name. A live
run that changes nothing leaves the previous record alone, and dryRun never
touches it. Reverse puts each slot back to its Before name; a slot renamed
since (by hand or another run) is SKIPPED. After a reverse the record is
renamed ..._reverse_reversed_<timestamp>.csv so it can't be applied twice.

Changes
-------
  v0.01Alpha  First version. Search from the Common search tier (same as
              SetFlagAudit / SetFlagEditor).
  v0.02Alpha  Station test: every rename FAILED with AccessControlException
              (getClassLoader) - the security manager refuses it to Program
              Objects. BNameMap / BFormat are now reached by class literal,
              no class loader. No slot or import changes.
              Renamed from DisplayNameEditor to SlotRenamerDisplayName:
              log, CSV and reverse-record file names follow the new name
              (logs/SlotRenamerDisplayName_<programName>_reverse.csv). A
              reverse record written under the old name is not read.
  v1.00       Release (station-tested). Version reset from v0.02Alpha; no
              code changes.
  v1.01       Name map text is escaped the way Niagara stores it (\= \; \\),
              so a component whose existing display names hold = or ;
              (e.g. "Flag required (Blank \= All)") keeps them intact when
              another slot on it is renamed. Such names no longer stop
              the write. No slot or import changes.
  v1.02       Wildcards (not case-sensitive) in Filter by Name and in the
              rules:  Boiler = only Boiler, Boiler* = starts with Boiler,
              *Boiler* = has Boiler in it. Filter by Name takes a comma
              list and is no longer "contains" (SETTERS no longer matches
              SETTERS1 - use SETTERS*).
              A rule can name the points it applies to:
                Boiler*.set = Boiler Enable
                *Pump*.set  = Pump Command
                set         = Set Setpoint      (any point)
              The first rule that fits a point's slot wins. The same slot
              can be in several rules with different point names.
              Matching from the Common search tier (wildcardMatch /
              nameMatches). No slot or import changes.

================================================================================
*/

private static final String VERSION = "v1.02";

// Property-sheet order (Common orderSlots): grouped the way a user reads it.
// Slots not listed (internal ones, or added later) go after these.
private static final String[] SLOT_ORDER = {
  // about
  "version", "quickGuide",
  // last run
  "status", "lastRun", "scanned", "matched", "changed", "summary", "results",
  // what to search
  "rootOrd", "nameFilter", "maxResults",
  // what to change
  "renames", "skipRenamed",
  // output files
  "csvFile", "logFilePath", "maxArchives"
};
private static final String LOG_TAG = "SlotRenamerDisplayName";
private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("SlotRenamerDisplayName");

// On-station user help -- written into the read-only quickGuide slot
// during onStart() / Execute so it shows up on the property sheet.
private static final String QUICK_GUIDE =
  "SlotRenamerDisplayName " + VERSION + "\n" +
  "=====================================\n" +
  "\n" +
  "Changes the display name of chosen slots (actions, properties or\n" +
  "child components) on every component under Root Location or BQL.\n" +
  "e.g. the Set action of every writable point -> Set Setpoint\n" +
  "\n" +
  "Steps:\n" +
  "  1) BACK UP THE STATION first.\n" +
  "  2) Set the search: Root Location or BQL, Filter by Name.\n" +
  "  3) Write the Rename Rules, choose Already Renamed.\n" +
  "  4) Dry Run action. Check Results / the CSV.\n" +
  "  5) Execute to commit.\n" +
  "  6) Save the station.\n" +
  "  Keep Execute On Change OFF.\n" +
  "\n" +
  "Actions:\n" +
  "  Dry Run          preview: nothing is written (WOULD CHANGE)\n" +
  "  Execute          apply the Rename Rules. LIVE.\n" +
  "  Reverse          undo the last Execute that changed something.\n" +
  "                   Slots renamed since are SKIPPED. Works once.\n" +
  "  Save the station after Execute or Reverse.\n" +
  "\n" +
  "Root Location or BQL:\n" +
  "  Component  walks the whole subtree\n" +
  "    station:|slot:/Drivers/BacnetNetwork\n" +
  "  BQL/NEQL   each row is walked as its own root (points or folders)\n" +
  "    station:|slot:/Drivers|bql:select * from control:NumericWritable\n" +
  "\n" +
  "Search filters:\n" +
  "  Filter by Name     point names, comma list, * = wildcard,\n" +
  "                     not case-sensitive. Blank = all\n" +
  "                       Boiler     only Boiler\n" +
  "                       Boiler*    starts with Boiler\n" +
  "                       *Boiler*   has Boiler in it\n" +
  "                       AHU*, *Pump*\n" +
  "  Max. Results       caps CSV rows + Results lines (edits are NOT\n" +
  "                     capped). 0 = no CSV cap, 200 Results lines\n" +
  "\n" +
  "Rename Rules (one per line, or ; between rules):\n" +
  "  [point name.]slot name = new display name\n" +
  "    set = Set Setpoint                 any point\n" +
  "    Boiler*.set = Boiler Enable        points starting with Boiler\n" +
  "    *Pump*.set = Pump Command          points with Pump in the name\n" +
  "    emergencyOverride = Emergency Override\n" +
  "  slot name =        (nothing after =) back to the default name\n" +
  "  Point names: * = wildcard, not case-sensitive (as Filter by Name).\n" +
  "  First rule that fits a point's slot wins - put specific rules first.\n" +
  "  # at the start of a line = comment\n" +
  "  Left side is the slot NAME (slot sheet Name column: set, not Set).\n" +
  "  New names can't contain  ;  {  }  =  %\n" +
  "\n" +
  "Already Renamed:\n" +
  "  Skip       slots that already have their own display name are\n" +
  "             left alone (SKIPPED)\n" +
  "  Overwrite  rename them too\n" +
  "\n" +
  "Output CSV file:\n" +
  "  File      file:^reports/NameEdits.csv  (overwritten each run)\n" +
  "  Directory file:^reports  (new SlotRenamerDisplayName_<timestamp>.csv per run)\n" +
  "  (folder: newest Max Archives files kept, 0 = all)\n" +
  "  Must be under the station home. Blank = no CSV.\n" +
  "  Columns: Name, Path, Slot, Before, After, Result\n" +
  "  (default) after a name = the slot has no display name of its own\n" +
  "  Result: CHANGED / WOULD CHANGE / NO CHANGE / SKIPPED / FAILED\n" +
  "  Reverse: REVERSED / SKIPPED / FAILED\n" +
  "  Reverse record: logs/SlotRenamerDisplayName_<name>_reverse.csv\n" +
  "\n" +
  "Log file: logFilePath (blank = logs/SlotRenamerDisplayName.log), one per run,\n" +
  "  archived and pruned with Max Archives.\n" +
  "\n" +
  "Outputs:\n" +
  "  Results            Name.slot : before -> after [result]\n" +
  "  Summary            Slots Matched, Changed / Would change,\n" +
  "                     No change, Skipped, Failed\n" +
  "  Status             result line; lists MISSING SLOTS if an\n" +
  "                     output is absent\n" +
  "  LastRun            time of last completed run\n" +
  "  Components Scanned components walked\n" +
  "  Slots Matched      rule slots found on the matched components\n" +
  "  Slots Changed      slots renamed (or that would be, in Dry Run)\n";

// Max lines written to the "results" slot when maxResults = 0
private static final int RESULTS_CAP = 200;

// The slot (on the component that owns the renamed slots) holding its
// explicit display names. Workbench creates it with readonly | hidden |
// operator; so does this program.
private static final String DN_SLOT = "displayNames";

// Characters a new display name can't hold: ; { } = break the name map
// text, % starts a BFormat script.
private static final String BAD_NAME_CHARS = ";{}=%";

// Per-run output buffers (reset at the start of every run).
private StringBuffer runCsv = new StringBuffer();
private StringBuffer runResults = new StringBuffer();
private int runResultLines = 0;
private int runWritten = 0;
private int runMax = 0;
private int runResCap = RESULTS_CAP;

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

// Built-in Execute: LIVE edit.
public void onExecute() throws Exception
{
  updateVersion();
  updateQuickGuide();
  runEdit(false);
}

// dryRun action: preview only, nothing written, reverse record untouched.
public void onDryRun() throws Exception
{
  updateVersion();
  updateQuickGuide();
  runEdit(true);
}

// Undo the last LIVE run that changed something, from the reverse record.
public void onReverse() throws Exception
{
  updateVersion();
  updateQuickGuide();
  runReverse();
}

// ---------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------

private void runEdit(boolean dry)
{
  long t0 = System.currentTimeMillis();
  archiveAndPrune(resolveLogPath(), "LOG");
  String mode = dry ? "DRY RUN" : "LIVE";
  reportInfo(mode + " running...");

  int scanned = 0;
  int withSlot = 0;
  int changed = 0;
  int wouldChange = 0;
  int noChange = 0;
  int skipped = 0;
  int failed = 0;

  startRows();

  // Reverse record (Common): exact names of every slot actually changed.
  // A live run that changes nothing keeps the previous record.
  if (!dry) beginReverseRecord();

  try
  {
    // ---- rename rules -------------------------------------------------
    ArrayList<String> pats = new ArrayList<String>();   // point pattern or null
    ArrayList<String> keys = new ArrayList<String>();   // slot name
    ArrayList<String> vals = new ArrayList<String>();   // new name or null
    String ruleErr = parseRules(getRenames(), pats, keys, vals);
    if (ruleErr != null)
    {
      reportError("ERROR: Rename Rules - " + ruleErr);
      return;
    }
    boolean skipRenamed = getSkipRenamed();

    // ---- search -------------------------------------------------------
    ArrayList<BComponent> roots = new ArrayList<BComponent>();
    String rootErr = resolveRoots(roots);
    if (rootErr != null)
    {
      reportError("ERROR: " + rootErr);
      return;
    }
    String rootNote = (roots.size() == 1) ? "" : " | " + roots.size() + " query rows";
    String nameF = trimOr(getNameFilter(), "");

    HashSet<BComponent> seen = new HashSet<BComponent>();
    // Never edit this program or anything inside it
    BComponent self = getComponent();
    if (self != null) seen.add(self);

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
      if (!nameMatches(name, nameF)) continue;

      // ---- what changes on this component ----------------------------
      LinkedHashMap<String, String> names = null;     // read on first match
      ArrayList<String> rowSlot   = new ArrayList<String>();
      ArrayList<String> rowBefore = new ArrayList<String>();   // own name or null
      ArrayList<String> rowEff    = new ArrayList<String>();   // name shown before
      ArrayList<String> rowTarget = new ArrayList<String>();   // own name wanted or null
      ArrayList<String> rowResult = new ArrayList<String>();   // null = to change
      String readErr = null;

      for (int n = 0; n < keys.size(); n++)
      {
        if (pats.get(n) != null && !wildcardMatch(name, pats.get(n))) continue;
        Slot s = findSlot(c, keys.get(n));
        if (s == null) continue;
        if (rowSlot.contains(s.getName())) continue;     // first rule wins
        withSlot++;

        if (names == null)
        {
          try { names = readNames(c); }
          catch (Throwable e)
          {
            names = new LinkedHashMap<String, String>();
            readErr = "FAILED (can't read displayNames: " + describeException(e) + ")";
          }
        }

        String sn = s.getName();
        String cur = names.get(sn);
        String target = vals.get(n);
        String result = null;

        if (readErr != null)                              result = readErr;
        else if (target == null ? cur == null : target.equals(cur)) result = "NO CHANGE";
        else if (target != null && cur != null && skipRenamed)
          result = "SKIPPED (already renamed)";

        rowSlot.add(sn);
        rowBefore.add(cur);
        rowEff.add(shownName(c, s));
        rowTarget.add(target);
        rowResult.add(result);
      }
      if (rowSlot.isEmpty()) continue;

      // ---- live: one write of the whole name map for this component --
      LinkedHashMap<String, String> after = names;
      String writeErr = null;
      boolean anyToChange = false;
      for (int k = 0; k < rowSlot.size(); k++)
        if (rowResult.get(k) == null) anyToChange = true;

      if (!dry && anyToChange)
      {
        LinkedHashMap<String, String> want = new LinkedHashMap<String, String>(names);
        for (int k = 0; k < rowSlot.size(); k++)
        {
          if (rowResult.get(k) != null) continue;
          if (rowTarget.get(k) == null) want.remove(rowSlot.get(k));
          else                          want.put(rowSlot.get(k), rowTarget.get(k));
        }
        try
        {
          writeNames(c, want);
          after = readNames(c);
        }
        catch (Throwable e)
        {
          writeErr = "FAILED (" + describeException(e) + ")";
          try { after = readNames(c); } catch (Throwable e2) { after = names; }
        }
      }

      // ---- results / CSV / reverse record ----------------------------
      String path = (c.getSlotPath() == null) ? "" : c.getSlotPath().toString();
      for (int k = 0; k < rowSlot.size(); k++)
      {
        String sn = rowSlot.get(k);
        String before = rowBefore.get(k);
        String target = rowTarget.get(k);
        String result = rowResult.get(k);
        String beforeShown = showName(before, rowEff.get(k));
        String afterShown = beforeShown;

        if (result != null)
        {
          if (result.startsWith("NO CHANGE")) noChange++;
          else if (result.startsWith("SKIPPED")) skipped++;
          else failed++;
        }
        else if (dry)
        {
          result = "WOULD CHANGE";
          afterShown = (target == null) ? "(default)" : target;
          wouldChange++;
        }
        else
        {
          String now = after.get(sn);
          Slot s = c.getSlot(sn);
          afterShown = showName(now, (s == null) ? "" : shownName(c, s));
          if (writeErr != null && same(now, before)) { result = writeErr; failed++; }
          else if (same(now, target))  { result = "CHANGED"; changed++; }
          else                         { result = "FAILED (name not applied)"; failed++; }
          if (!same(now, before))
            recordReverse(new String[]{ path, sn, encName(before), encName(now) });
        }

        addRow(name, path, sn, beforeShown, afterShown, result);
      }
    }

    // ---- outputs ------------------------------------------------------
    int matched = withSlot;
    StringBuffer sum = new StringBuffer();
    sum.append(mode).append(" | Slots Matched as per filters: ").append(matched);
    if (dry) sum.append(" | Would change: ").append(wouldChange);
    else     sum.append(" | Changed: ").append(changed);
    sum.append(" | No change: ").append(noChange);
    sum.append(" | Skipped (already renamed): ").append(skipped);
    sum.append(" | Failed: ").append(failed);

    StringBuffer missing = new StringBuffer();
    setOut("scanned", BInteger.make(scanned),                       missing);
    setOut("matched", BInteger.make(matched),                       missing);
    setOut("changed", BInteger.make(dry ? wouldChange : changed),   missing);
    setOut("summary", BString.make(sum.toString()),                 missing);
    if (matched > runResultLines)
      runResults.append("... ").append(matched - runResultLines).append(" more (see CSV)\n");
    setOut("results", BString.make(runResults.toString()),          missing);

    String csvNote = writeCsv(LOG_TAG + ".csv", matched);

    String revNote = "";
    if (!dry && reverseRecordOpen)
      revNote = (resolveToFile(reverseRecordPath()) == null)
        ? " | REVERSE NOT SAVED: " + lastPathError : " | reverse saved";

    long ms = System.currentTimeMillis() - t0;
    setOut("lastRun", BString.make(now()), missing);
    String missNote = (missing.length() > 0) ? " | MISSING SLOTS: " + missing : "";
    String saveNote = (!dry && changed > 0) ? " | SAVE THE STATION" : "";
    reportInfo(mode + " OK: scanned " + scanned + ", rule slots found " + matched
              + (dry ? ", would change " + wouldChange : ", changed " + changed)
              + (skipped > 0 ? ", skipped " + skipped : "")
              + (failed > 0 ? ", FAILED " + failed : "")
              + " in " + ms + " ms" + rootNote + csvNote + revNote + saveNote + missNote);
  }
  catch (Throwable e)
  {
    reportError("ERROR: " + describeException(e));
  }
}

// ---------------------------------------------------------------------
// Reverse
// ---------------------------------------------------------------------

private void runReverse()
{
  long t0 = System.currentTimeMillis();
  archiveAndPrune(resolveLogPath(), "LOG");
  String mode = "REVERSE";
  reportInfo(mode + " running...");

  int rows = 0, reversed = 0, skipped = 0, failed = 0;
  startRows();

  try
  {
    java.util.List lines = readReverseRecord();
    if (lines.isEmpty())
    {
      reportInfo("REVERSE: nothing to reverse (no record from a live run)");
      return;
    }

    for (int i = 0; i < lines.size(); i++)
    {
      String[] f = (String[]) lines.get(i);
      if (f.length < 4) continue;
      rows++;

      String path = f[0];
      String slotName = f[1];
      String before = decName(f[2]);
      String after = decName(f[3]);

      String name = path;
      String result;
      String curShown = showName(after, "");
      String resShown = curShown;

      BComponent c = null;
      try
      {
        BObject o = BOrd.make("station:|" + path).resolve(getComponent()).get();
        if (o instanceof BComponent) c = (BComponent) o;
      }
      catch (Throwable e) { c = null; }

      if (c == null)
      {
        result = "FAILED (component not found)";
        failed++;
      }
      else
      {
        if (c.getName() != null) name = SlotPath.unescape(c.getName());
        Slot s = c.getSlot(slotName);
        if (s == null)
        {
          result = "FAILED (slot not found)";
          failed++;
        }
        else
        {
          try
          {
            LinkedHashMap<String, String> names = readNames(c);
            String cur = names.get(slotName);
            curShown = showName(cur, shownName(c, s));
            resShown = curShown;
            if (!same(cur, after))
            {
              result = "SKIPPED (renamed since run)";
              skipped++;
            }
            else
            {
              LinkedHashMap<String, String> want = new LinkedHashMap<String, String>(names);
              if (before == null) want.remove(slotName);
              else                want.put(slotName, before);
              writeNames(c, want);
              String now = readNames(c).get(slotName);
              resShown = showName(now, shownName(c, s));
              if (same(now, before)) { result = "REVERSED"; reversed++; }
              else                   { result = "FAILED (name not applied)"; failed++; }
            }
          }
          catch (Throwable e)
          {
            result = "FAILED (" + describeException(e) + ")";
            failed++;
          }
        }
      }

      addRow(name, path, slotName, curShown, resShown, result);
    }

    // Reverse consumes the record so it can't be applied twice
    consumeReverseRecord();
    String mfNote = " | record consumed";

    StringBuffer sum = new StringBuffer();
    sum.append(mode).append(" | Slots in record: ").append(rows);
    sum.append(" | Reversed: ").append(reversed);
    sum.append(" | Skipped: ").append(skipped)
       .append(" | Failed: ").append(failed);

    StringBuffer missing = new StringBuffer();
    setOut("scanned", BInteger.make(0),                       missing);
    setOut("matched", BInteger.make(rows),                    missing);
    setOut("changed", BInteger.make(reversed),                missing);
    setOut("summary", BString.make(sum.toString()),           missing);
    if (rows > runResultLines)
      runResults.append("... ").append(rows - runResultLines).append(" more (see CSV)\n");
    setOut("results", BString.make(runResults.toString()),    missing);

    String csvNote = writeCsv(LOG_TAG + "_Reverse.csv", -1);

    long ms = System.currentTimeMillis() - t0;
    setOut("lastRun", BString.make(now()), missing);
    String missNote = (missing.length() > 0) ? " | MISSING SLOTS: " + missing : "";
    String saveNote = (reversed > 0) ? " | SAVE THE STATION" : "";
    reportInfo(mode + " OK: " + rows + " in record, "
              + "reversed " + reversed
              + ", skipped " + skipped
              + (failed > 0 ? ", FAILED " + failed : "")
              + " in " + ms + " ms" + csvNote + mfNote + saveNote + missNote);
  }
  catch (Throwable e)
  {
    reportError("REVERSE ERROR: " + describeException(e));
  }
}

// ---------------------------------------------------------------------
// Rename rules
// ---------------------------------------------------------------------

// "[point.]slot = New Name" per line or between ; - "slot =" means back to
// the default name (vals entry null). The point part (pats entry, null =
// any point) takes * wildcards. Returns null, or what is wrong.
private String parseRules(String text, ArrayList<String> pats,
                          ArrayList<String> keys, ArrayList<String> vals)
{
  String t = trimOr(text, "");
  if (t.length() == 0) return "no rules - add e.g.  set = Set Setpoint";

  String[] parts = t.split("[\\r\\n;]+");
  StringBuffer bad = new StringBuffer();
  ArrayList<String> seenRules = new ArrayList<String>();
  for (int i = 0; i < parts.length; i++)
  {
    String p = parts[i].trim();
    if (p.length() == 0 || p.startsWith("#") || p.startsWith("//")) continue;

    int eq = p.indexOf('=');
    String why = null;
    String left = (eq < 0) ? p : p.substring(0, eq).trim();
    String v = (eq < 0) ? "" : p.substring(eq + 1).trim();

    // point pattern = everything before the last '.' (slot names never
    // hold a '.', Niagara stores it as $2e)
    int dot = left.lastIndexOf('.');
    String pat = (dot < 0) ? null : left.substring(0, dot).trim();
    String k = (dot < 0) ? left : left.substring(dot + 1).trim();
    String ruleId = ((pat == null) ? "" : pat.toLowerCase()) + "." + k;

    if (eq < 0)                          why = "no '='";
    else if (pat != null && pat.length() == 0) why = "no point name before '.'";
    else if (k.length() == 0)            why = "no slot name";
    else if (k.equals(DN_SLOT))          why = "displayNames itself can't be renamed";
    else if (seenRules.contains(ruleId)) why = "same point + slot listed twice";
    else
      for (int c = 0; c < v.length(); c++)
        if (BAD_NAME_CHARS.indexOf(v.charAt(c)) >= 0)
        { why = "name can't contain '" + v.charAt(c) + "'"; break; }

    if (why != null)
    {
      if (bad.length() > 0) bad.append(" | ");
      bad.append('"').append(p).append("\": ").append(why);
      continue;
    }
    seenRules.add(ruleId);
    pats.add(pat);
    keys.add(k);
    vals.add(v.length() == 0 ? null : v);
  }
  if (bad.length() > 0) return bad.toString();
  if (keys.isEmpty()) return "no rules - add e.g.  set = Set Setpoint";
  return null;
}

// The rule's slot on c: as typed, or escaped the way Workbench stores it
// ("Zone Temp" -> "Zone$20Temp").
private Slot findSlot(BComponent c, String typed)
{
  Slot s = c.getSlot(typed);
  if (s == null)
  {
    try { s = c.getSlot(SlotPath.escape(typed)); } catch (Throwable e) { s = null; }
  }
  if (s != null && DN_SLOT.equals(s.getName())) return null;
  return s;
}

// ---------------------------------------------------------------------
// displayNames (baja:NameMap) - read / write
// ---------------------------------------------------------------------

// The explicit display names on c: slot name -> name text, in map order.
// Empty when c has none.
private LinkedHashMap<String, String> readNames(BComponent c) throws Exception
{
  LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
  Slot s = c.getSlot(DN_SLOT);
  if (s == null || !s.isProperty()) return out;
  BValue v = c.get(s.asProperty());
  if (v == null) return out;

  // 1) BNameMap.list() + get(name)
  try
  {
    java.lang.reflect.Method mList = v.getClass().getMethod("list", new Class[0]);
    java.lang.reflect.Method mGet  = v.getClass().getMethod("get", new Class[]{ String.class });
    String[] keys = (String[]) mList.invoke(v, new Object[0]);
    for (int i = 0; i < keys.length; i++)
    {
      Object f = mGet.invoke(v, new Object[]{ keys[i] });
      if (f != null) out.put(keys[i], formatText(f));
    }
    return out;
  }
  catch (Throwable e)
  {
    out.clear();
  }

  // 2) the encoded text: {set=Set Setpoint;fallback=Default Value;}
  if (v instanceof BSimple) parseNameMapText(((BSimple) v).encodeToString(), out);
  return out;
}

// Writes the whole name map back to c. Empty map -> displayNames removed.
private void writeNames(BComponent c, LinkedHashMap<String, String> names) throws Exception
{
  Iterator<String> it = names.keySet().iterator();
  while (it.hasNext())
  {
    String k = it.next();
    String v = names.get(k);
    if (hasAny(k, "{}") || hasAny(v, "{}"))
      throw new Exception("existing display name '" + k + "=" + v
        + "' contains { or } - edit this component by hand");
  }

  Slot s = c.getSlot(DN_SLOT);
  if (names.isEmpty())
  {
    if (s == null) return;
    try { c.remove(DN_SLOT); return; }
    catch (Throwable e) { /* frozen or refused: write an empty map */ }
  }

  BValue map = buildNameMap(c, names);
  if (s != null && s.isProperty()) c.set(s.asProperty(), map);
  else c.add(DN_SLOT, map, Flags.READONLY | Flags.HIDDEN | Flags.OPERATOR);
}

// A baja:NameMap holding names. Built from its text form
// {k=v;k=v;} (the form Workbench and the Batch Editor use); falls back to
// BNameMap.make(Map of BFormat) if the text form is refused.
private BValue buildNameMap(BComponent c, LinkedHashMap<String, String> names) throws Exception
{
  StringBuffer sb = new StringBuffer("{");
  Iterator<String> it = names.keySet().iterator();
  while (it.hasNext())
  {
    String k = it.next();
    sb.append(nameMapEscape(k)).append('=').append(nameMapEscape(names.get(k))).append(';');
  }
  sb.append('}');

  Throwable first = null;
  try
  {
    BSimple proto = nameMapProto(c);
    return (BValue) proto.decodeFromString(sb.toString());
  }
  catch (Throwable e) { first = e; }

  try
  {
    // Class literals, not Class.forName(.., loader): the station's security
    // manager refuses getClassLoader() to Program Objects.
    HashMap<String, Object> m = new LinkedHashMap<String, Object>();
    it = names.keySet().iterator();
    while (it.hasNext())
    {
      String k = it.next();
      m.put(k, javax.baja.util.BFormat.make(names.get(k)));
    }
    java.lang.reflect.Method mk = javax.baja.util.BNameMap.class.getMethod(
      "make", new Class[]{ Map.class });
    return (BValue) mk.invoke(null, new Object[]{ m });
  }
  catch (Throwable e)
  {
    throw new Exception("can't build displayNames " + sb + ": "
      + describeException(first) + " / " + describeException(e));
  }
}

// An instance to decode with: c's current map, else BNameMap.DEFAULT/NULL.
private BSimple nameMapProto(BComponent c) throws Exception
{
  Slot s = c.getSlot(DN_SLOT);
  if (s != null && s.isProperty())
  {
    BValue v = c.get(s.asProperty());
    if (v instanceof BSimple) return (BSimple) v;
  }
  Class nm = javax.baja.util.BNameMap.class;
  String[] fields = { "DEFAULT", "NULL" };
  for (int i = 0; i < fields.length; i++)
  {
    try
    {
      Object o = nm.getField(fields[i]).get(null);
      if (o instanceof BSimple) return (BSimple) o;
    }
    catch (Throwable ignore) {}
  }
  throw new Exception("javax.baja.util.BNameMap has no DEFAULT / NULL");
}

// {a=b;c=d;} -> a:b, c:d   (\= \; \\ are a literal = ; \)
private void parseNameMapText(String t, LinkedHashMap<String, String> out)
{
  if (t == null) return;
  t = t.trim();
  if (t.startsWith("{")) t = t.substring(1);
  if (t.endsWith("}")) t = t.substring(0, t.length() - 1);
  StringBuffer key = new StringBuffer();
  StringBuffer val = new StringBuffer();
  boolean inVal = false;
  for (int i = 0; i < t.length(); i++)
  {
    char c = t.charAt(i);
    if (c == '\\' && i + 1 < t.length())
    {
      (inVal ? val : key).append(t.charAt(++i));
    }
    else if (c == '=' && !inVal)
    {
      inVal = true;
    }
    else if (c == ';')
    {
      if (inVal && key.toString().trim().length() > 0)
        out.put(key.toString().trim(), val.toString());
      key.setLength(0);
      val.setLength(0);
      inVal = false;
    }
    else (inVal ? val : key).append(c);
  }
  if (inVal && key.toString().trim().length() > 0)
    out.put(key.toString().trim(), val.toString());
}

// One key or value in name map text: \ = ; get a \ in front, the way
// Niagara writes them ("Flag required (Blank \= All)").
private String nameMapEscape(String s)
{
  StringBuffer sb = new StringBuffer(s.length() + 4);
  for (int i = 0; i < s.length(); i++)
  {
    char c = s.charAt(i);
    if (c == '\\' || c == '=' || c == ';') sb.append('\\');
    sb.append(c);
  }
  return sb.toString();
}

// Text of a BFormat (its pattern).
private String formatText(Object f)
{
  try
  {
    if (f instanceof BSimple) return ((BSimple) f).encodeToString();
  }
  catch (Exception ignore) {}
  return f.toString();
}

// The name Workbench shows for slot s on c right now.
private String shownName(BComponent c, Slot s)
{
  try
  {
    String d = c.getDisplayName(s, null);
    if (d != null) return d;
  }
  catch (Throwable ignore) {}
  return s.getName();
}

// Own name if set, else "<shown> (default)".
private String showName(String own, String shown)
{
  if (own != null) return own;
  return (shown == null || shown.length() == 0) ? "(default)" : shown + " (default)";
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

private boolean same(String a, String b)
{
  return (a == null) ? (b == null) : a.equals(b);
}

private boolean hasAny(String s, String chars)
{
  if (s == null) return false;
  for (int i = 0; i < s.length(); i++)
    if (chars.indexOf(s.charAt(i)) >= 0) return true;
  return false;
}

// Reverse record field: "0" = default name (no own name), "1:<name>".
// (The prefix keeps a name starting with = - + @ safe from csvEscape.)
private String encName(String own)
{
  return (own == null) ? "0" : "1:" + own;
}

private String decName(String f)
{
  if (f == null || !f.startsWith("1:")) return null;
  return f.substring(2);
}

// Run output: Results lines (capped) and CSV rows (capped by Max. Results).
private void startRows()
{
  runCsv = new StringBuffer("Name,Path,Slot,Before,After,Result\n");
  runResults = new StringBuffer();
  runResultLines = 0;
  runWritten = 0;
  runMax = getMaxResults();
  runResCap = (runMax > 0) ? runMax : RESULTS_CAP;
}

private void addRow(String name, String path, String slot,
                    String before, String after, String result)
{
  if (runResultLines < runResCap)
  {
    runResults.append(name).append('.').append(SlotPath.unescape(slot))
              .append(" : ").append(before);
    if (!after.equals(before)) runResults.append(" -> ").append(after);
    runResults.append("  [").append(result).append("]\n");
    runResultLines++;
  }
  if (runMax <= 0 || runWritten < runMax)
  {
    runCsv.append(csvEscape(name)).append(',')
          .append(csvEscape(path)).append(',')
          .append(csvEscape(slot)).append(',')
          .append(csvEscape(before)).append(',')
          .append(csvEscape(after)).append(',')
          .append(csvEscape(result)).append('\n');
    runWritten++;
  }
}

// Writes the run CSV to Output CSV file (if set). Returns the status note.
private String writeCsv(String defaultName, int total)
{
  BOrd csvOrd = getCsvFile();
  if (csvOrd == null || csvOrd.isNull() || csvOrd.toString().trim().length() == 0)
    return "";
  try
  {
    java.io.File out = resolveOutputFile(csvOrd.toString().trim(), defaultName, true);
    if (out == null) return " | CSV NOT WRITTEN: " + lastPathError;
    writeTextFile(out, runCsv.toString());
    pruneRunFiles(out);
    String note = " | CSV " + runWritten + " rows -> " + out.getName();
    if (runMax > 0 && total > runMax) note += " (capped at " + runMax + ")";
    return note;
  }
  catch (Throwable e)
  {
    return " | CSV NOT WRITTEN: " + describeException(e);
  }
}

// ============================================ COMMON (sync_common.py) ===
private String lastPathError = null;
private boolean reverseRecordOpen = false;

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

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

private String reverseRecordPath()
{
  String nm = "program";
  try
  {
    javax.baja.sys.BComponent me = (javax.baja.sys.BComponent) getComponent();
    if (me != null && me.getName() != null) nm = me.getName();
  }
  catch (Throwable ignore) {}
  // Anything but A-Z a-z 0-9 _ $ - becomes _ (no regex: AX J2ME).
  StringBuffer safe = new StringBuffer(nm.length());
  for (int i = 0; i < nm.length(); i++)
  {
    char c = nm.charAt(i);
    boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                 (c >= '0' && c <= '9') || c == '_' || c == '$' || c == '-';
    safe.append(ok ? c : '_');
  }
  nm = safe.toString();
  return "file:^logs/" + LOG_TAG + "_" + nm + "_reverse.csv";
}

// Call at the start of every live run. The old record is kept until this
// run records its first change.
private void beginReverseRecord()
{
  reverseRecordOpen = false;
}

// One row per change. First call of a run replaces the previous record.
private void recordReverse(String[] fields)
{
  String path = reverseRecordPath();
  if (!reverseRecordOpen)
  {
    clearFile(path);
    appendLine(path, "# " + LOG_TAG + " " + VERSION + " reverse record - " + now());
    reverseRecordOpen = true;
  }
  StringBuffer row = new StringBuffer();
  for (int i = 0; i < fields.length; i++)
  {
    if (i > 0) row.append(',');
    row.append(csvEscape(fields[i]));
  }
  appendLine(path, row.toString());
}

// Rows of the current record (each a String[]), oldest first. Empty list if
// there is no record.
private java.util.List readReverseRecord()
{
  java.util.ArrayList rows = new java.util.ArrayList();
  java.io.File f = resolveToFile(reverseRecordPath());
  if (f == null || !f.isFile()) return rows;
  java.io.BufferedReader br = null;
  try
  {
    br = new java.io.BufferedReader(new java.io.InputStreamReader(
      new java.io.FileInputStream(f), "UTF-8"));
    String ln;
    while ((ln = br.readLine()) != null)
    {
      if (ln.trim().length() == 0 || ln.startsWith("#")) continue;
      rows.add(parseCsvRow(ln));
    }
  }
  catch (Exception e)
  {
    writeToLog("REVERSE RECORD READ ERROR: " + describeException(e));
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
  }
  return rows;
}

// Renames the record to ..._reversed_<timestamp>.csv so it can't be applied
// twice (kept for the audit trail, pruned with maxArchives).
private void consumeReverseRecord()
{
  String path = reverseRecordPath();
  java.io.File f = resolveToFile(path);
  if (f == null || !f.isFile()) return;
  String done = buildTimestampedArchivePath(
    path.substring(0, path.length() - 4) + "_reversed.csv");
  java.io.File to = resolveToFile(done);
  if (to == null || !f.renameTo(to)) f.delete();
  pruneArchives(path.substring(0, path.length() - 4) + "_reversed.csv");
}

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

// s trimmed, or def when s is null or blank.
private String trimOr(String s, String def)
{
  if (s == null) return def;
  String t = s.trim();
  return (t.length() == 0) ? def : t;
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
