/*
================================================================================
Program: LinkCreator (Direct / BQL / CSV) - Niagara N4.15
Author:  F. Lacroix
Version: v3.0
Date:    2026-09-01

Changes
-------
  v1.0   Initial release. Create/delete/verify Niagara component
         links between source and target components (Direct / BQL /
         CSV modes), 5-column CSV format (BOrd1, Slot1, Direction,
         BOrd2, Slot2).
  v1.01  Results CSV now archived with a timestamp every run, same as
         the active log file.
  v1.02  createSampleCsv changed from a property toggle to an Action.
  v2.00  maxArchives + auto-pruning of old archives; Cancel action;
         pruneArchives action; CSV reads forced to UTF-8; CSV parser
         made quote-aware.
  v2.01  Version-synced with Component Copier; no behavioural change
         here.
  v2.02- CSV error reporting matured across these four releases:
  v2.05  every problem row (validation and processing) gets its own
         results-CSV row, each BOrd is resolved (and reported)
         separately with a reason that's never blank
         (describeException()), double-counted error rows were
         fixed, and the slot is shown next to the ord
         (ordWithSlot()).
  v2.06  Version-synced with Component Copier; no behavioural change
         here - the matching Copier release brought its own link
         phase up to this program's CSV-error-reporting standard.
  v3.0   Multiple sources: sourceOrd/sourceSlot merged into one
         multi-line linkSource slot ("ord,slot" per line); every
         source now links to the target in Direct/BQL mode. BQL
         target lists materialized into memory before linking
         starts. buildLinkName() aligned with Component Copier's
         formula so a link created by either program's link phase
         gets the identical name for the same source/target/slot
         inputs (previously the two programs generated different
         names for the same link, so neither could recognize a link
         the other had created - MIGRATION NOTE: any link created by
         this program before this fix carries the OLD name and will
         not be recognized as already existing under the new naming;
         either rename the existing target slots to match, or let a
         re-run create the correctly-named link alongside the old one
         and remove the old one by hand once confirmed). Changes
         section condensed to this format.

Purpose
-------
Create, delete, or verify Niagara component links between one or more
source components and one or more target components in three operating
modes (Direct, BQL, CSV). Logs every action to the Application Director,
a station-rooted log file, and a results CSV.

Modes
-----
  Direct  Each source slot linked to one target slot (many to one)
  BQL     Each source slot linked to many targets via BQL query
  CSV     Source/target pairs read from a 5-column CSV file
          (BOrd1, Slot1, Direction (>|<), BOrd2, Slot2)

Actions
-------
  execute           Run the program in the configured operation mode
  dryRun            Simulate the run without creating or deleting any links
  verify            Check that expected links exist (no changes made)
  createSampleCsv   Write a starter CSV file to sampleCsvPath so the
                    user can edit it in place rather than guessing
                    the format.
  cancel            Stop the current run after the in-flight row
                    finishes. Writes a RUN CANCELLED summary.
  pruneArchives     Apply the maxArchives limit to the log and
                    results CSV archive folders right now, without
                    running a link/verify pass.

Key features
------------
  - Auto-archives the active log AND the results CSV to timestamped
    copies on every run so the user retains a full run history. Archive
    timestamp reflects the run trigger time. Old archives beyond
    maxArchives are auto-pruned.
  - Self-documenting CSV format: createSampleCsv (action) writes a
    starter file with example rows the user can edit in place.
  - Unique link-name hash suffix prevents collisions when two source
    components share the same display name.
  - dryRun previews are reported as DRYRUN with full skip-reason detail
    so summary counts always reflect what was previewed.
  - CSV mode now lists every problem row (validation + processing) in
    the results CSV with a reason, so troubleshooting no longer needs
    the log file.
  - Cancel action stops long-running BQL / CSV passes cleanly.
  - All output paths (log, archive, results CSV, sample CSV) are
    user-configurable Ord slots; archive log inherits the active log's
    name with the run timestamp appended.
  - quickGuide String slot displays the on-station user help next to
    the configuration slots.

Outputs
-------
  status (string)        live timestamped progress and final summary
  logFilePath            human-readable log of every operation
  resultsCsvPath         per-link CSV: timestamp, names, status, etc.
  Application Director   info / warning / severe lines for ops staff

Quick start
-----------
  1) Set linkSource (one "ord,slot" pair per line - the first
     non-blank line is the primary source, add more lines for
     additional sources) and configure the target ord/slot
  2) (Optional) Run Create Sample CSV to get a starter CSV file
  3) Right-click  >  Actions  >  dryRun   to preview
  4) Right-click  >  Actions  >  verify   to audit existing links
  5) Right-click  >  Actions  >  execute  to commit
  6) Inspect the log file and results CSV for full details
================================================================================
*/
private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("LinkCreator");

private static final String VERSION = "v3.0";

// On-station user help -- written into the read-only quickGuide slot
// during onStart() so it shows up at the bottom of the property sheet.
private static final String QUICK_GUIDE =
  "LinkCreator " + "v3.0\n" +
  "=====================================\n" +
  "\n" +
  "Modes (operationMode):\n" +
  "  Direct  source(s) -> target slot (many to one)\n" +
  "  BQL     source(s) -> each BQL target (many to many)\n" +
  "  CSV     source/target pairs from a 5-col CSV\n" +
  "\n" +
  "Sources: linkSource holds one \"ord,slot\" pair per line. The\n" +
  "first non-blank line is the primary source; further non-blank\n" +
  "lines are extra sources (lines starting with # are ignored).\n" +
  "In Direct/BQL mode every source is linked to the target.\n" +
  "\n" +
  "Actions:\n" +
  "  Execute           - run the configured mode\n" +
  "  Dry Run           - preview without changes\n" +
  "  Verify            - confirm expected links exist\n" +
  "  Create Sample CSV - write a starter CSV to sampleCsvPath.\n" +
  "                      Edit it in place.\n" +
  "  Cancel Run        - stop the current run cleanly after the\n" +
  "                      in-flight row finishes.\n" +
  "  Prune Old Archives- apply the maxArchives limit right now\n" +
  "                      without running a link/verify pass.\n" +
  "\n" +
  "CSV format (5 columns, header row required):\n" +
  "  BOrd1, Slot1, Direction, BOrd2, Slot2\n" +
  "  Direction:  >  links 1 -> 2     <  links 2 -> 1\n" +
  "\n" +
  "CSV troubleshooting:\n" +
  "  Problem rows are listed in the results CSV with a reason\n" +
  "  in the Message column. Status VALIDATION = caught before the\n" +
  "  run; Status ERROR = failed during link processing.\n" +
  "\n" +
  "Steps:\n" +
  "  1) Choose operationMode; set linkSource (one \"ord,slot\" pair\n" +
  "     per line) and the target ord/slot\n" +
  "  2) (optional) Create Sample CSV to get a starter file,\n" +
  "     then edit it and point the program at it (CSV mode)\n" +
  "  3) dryRun first to preview\n" +
  "  4) execute to commit\n" +
  "  5) check logFilePath and resultsCsvPath for details\n" +
  "\n" +
  "Tips:\n" +
  "  - linkSource: one \"ord,slot\" source per line. Each source is\n" +
  "    linked to the target in Direct/BQL mode.\n" +
  "  - set deleteLinks=true to remove links instead of\n" +
  "    creating them. Dry Run previews delete-mode as well.\n" +
  "  - maxArchives caps how many timestamped log/CSV archives\n" +
  "    are kept (default 10). 0 = keep all.";

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// ----------------------------------------------------
// Exception describer (v2.04)
// Build a human-readable reason from an exception that is NEVER empty
// or "null". Niagara resolve failures sometimes carry a null detail
// message, which used to leave the CSV Message column blank. We fall
// back to the exception's simple class name, and append the underlying
// cause when there is one (the cause is usually where the real reason
// lives -- e.g. UnresolvedException wrapping a "slot not found").
// ----------------------------------------------------
private String describeException(Throwable t)
{
  if (t == null) return "unknown error";

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

// Format an ord + slot for the results CSV as "<ord> [<slot>]" so the
// problem rows show which slot was involved, not just the component
// ord. Falls back to just the ord when the slot is blank. (v2.05)
private String ordWithSlot(String ord, String slot)
{
  String o = (ord == null) ? "" : ord;
  if (slot == null || slot.trim().length() == 0) return o;
  return o + " [" + slot.trim() + "]";
}

// ----------------------------------------------------
// Multiple sources (v3.0, ported from Component Copier)
// ----------------------------------------------------
// Lightweight carrier for one parsed "<ord>,<slot>" line of
// linkSource. No Baja types, matching CsvIssue's style below.
private static class LinkSourceSpec
{
  String ord;
  String slot;

  LinkSourceSpec(String ord, String slot)
  {
    this.ord  = ord;
    this.slot = slot;
  }
}

// Reads the linkSource slot (baja:String, one "ord,slot" pair per
// line).
private String resolveLinkSource()
{
  try
  {
    Object val = get("linkSource");
    if (val != null)
    {
      String s = val.toString().trim();
      if (s.length() > 0) return s;
    }
  }
  catch (Exception ignore) {}
  return "";
}

// Parses linkSource into an ordered list of LinkSourceSpec. Each
// non-blank, non-comment ("#") line is split on its FIRST comma:
// everything before is the source ord, everything after is the
// source slot. A line with no comma (no slot given) is logged and
// skipped, since a link needs both. Duplicate "ord,slot" pairs are
// skipped. The first entry in the returned list is the primary
// source.
private java.util.List getLinkSources()
{
  java.util.List out = new java.util.ArrayList();
  java.util.Set seen = new java.util.HashSet();

  String raw = resolveLinkSource();
  if (raw.length() == 0) return out;

  String[] lines = raw.split("[\\r\\n]+");
  for (int i = 0; i < lines.length; i++)
  {
    String line = lines[i].trim();
    if (line.length() == 0 || line.startsWith("#")) continue;

    int comma = line.indexOf(',');
    if (comma < 0)
    {
      writeToLog("SOURCE ERROR: linkSource line '" + line +
        "' has no slot (expected \"ord,slot\") - skipped");
      continue;
    }

    String ord  = line.substring(0, comma).trim();
    String slot = line.substring(comma + 1).trim();
    if (ord.length() == 0 || slot.length() == 0)
    {
      writeToLog("SOURCE ERROR: linkSource line '" + line +
        "' is missing an ord or a slot - skipped");
      continue;
    }

    String key = ord + "," + slot;
    if (seen.add(key))
      out.add(new LinkSourceSpec(ord, slot));
  }

  return out;
}

// Resolves a source ord string to a BComponent, logging (and
// returning null) if it cannot be resolved to one.
private javax.baja.sys.BComponent resolveSourceComponent(String ordStr)
{
  try
  {
    javax.baja.naming.BOrd ord =
      javax.baja.naming.BOrd.make(normalizeOrd(ordStr));
    Object resolved = ord.resolve().get();
    if (resolved instanceof javax.baja.sys.BComponent)
      return (javax.baja.sys.BComponent) resolved;
    String msg = "SOURCE ERROR: " + ordStr +
      " did not resolve to a component (" +
      resolved.getClass().getName() + ")";
    setStatus("[" + now() + "] " + msg);
    log.warning("[LinkCreator] " + msg);
    writeToLog(msg);
  }
  catch (Exception e)
  {
    String msg = "SOURCE ERROR: could not resolve '" + ordStr + "' - " +
      describeException(e);
    setStatus("[" + now() + "] " + msg);
    log.warning("[LinkCreator] " + msg);
    writeToLog(msg);
  }
  return null;
}

// ----------------------------------------------------
// CSV issue record (v2.02)
// Lightweight structured carrier for a validation problem so it can
// be written to the results CSV with full detail rather than just a
// log line. Kept as a plain inner-ish data holder -- no Baja types,
// no compiler features unavailable in the embedded environment.
// ----------------------------------------------------
private static class CsvIssue
{
  int rowNum;
  String bord1;
  String bord2;
  String reason;

  CsvIssue(int rowNum, String bord1, String bord2, String reason)
  {
    this.rowNum = rowNum;
    this.bord1  = bord1;
    this.bord2  = bord2;
    this.reason = reason;
  }
}

// ----------------------------------------------------
// Cancellation flag
// ----------------------------------------------------
// Set by onCancel(); checked between rows in the BQL and CSV loops.
// volatile because the cancel action and the running job may execute
// on different threads in some Niagara configurations.
private volatile boolean cancelRequested = false;

private boolean isCancelled() { return cancelRequested; }

// ----------------------------------------------------
// Slot readers
// ----------------------------------------------------
private String resolveLogPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("logFilePath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/LinkCreator.log";
}

// Build a timestamped archive path from the given active file path.
// Inserts "_yyyy-MM-dd_HH-mm-ss" before the file extension, where the
// timestamp is the current run's trigger time (i.e. when this archive
// operation runs). This is more reliable than reading the file's
// creation time -- on Windows, file tunneling can make the "creation"
// time stick to a stale value across renames, causing repeated archives
// to collide on the same filename.
//   file:^logs/LinkCreator.log
//     -> file:^logs/LinkCreator_2026-04-27_12-23-25.log
//   file:^logs/LinkCreator_results.csv
//     -> file:^logs/LinkCreator_results_2026-04-27_12-23-25.csv
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

private String resolveResultsCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("resultsCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/LinkCreator_results.csv";
}

// Set by onDryRun() to make isDryRun() report true for the duration of
// that one invocation. onExecute() and onVerify() both reset it on entry.
private boolean dryRunActive = false;

private boolean isDryRun()
{
  return dryRunActive;
}

private boolean isDeleteMode()
{
  try
  {
    Object val = get("deleteLinks");
    if (val instanceof javax.baja.sys.BBoolean)
      return ((javax.baja.sys.BBoolean) val).getBoolean();
  }
  catch (Exception ignore) {}
  return false;
}

private String resolveSampleCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("sampleCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/LinkCreator_SAMPLE.csv";
}

// Read the maxArchives slot. Returns 10 if the slot is missing or
// unreadable. 0 (or negative) means "keep all archives, never prune".
private int resolveMaxArchives()
{
  try
  {
    Object val = get("maxArchives");
    if (val instanceof javax.baja.sys.BInteger)
      return ((javax.baja.sys.BInteger) val).getInt();
  }
  catch (Exception ignore) {}
  return 10;
}

// Reflection-based setter for the version slot -- avoids a hard
// compile-time dependency on a generated setVersion() method.
private void updateVersion()
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setVersion", new Class[]{ String.class });
    m.invoke(this, VERSION);
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setVersion", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, javax.baja.sys.BString.make(VERSION));
    }
    catch (Exception ignore2) {}
  }
}

// Reflection-based setter for the quickGuide slot. Same pattern as
// updateVersion() -- the slot is a baja:String so we try a String
// setter first, then a BString setter as a fallback.
private void updateQuickGuide()
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setQuickGuide", new Class[]{ String.class });
    m.invoke(this, QUICK_GUIDE);
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setQuickGuide", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, javax.baja.sys.BString.make(QUICK_GUIDE));
    }
    catch (Exception ignore2) {}
  }
}

// ----------------------------------------------------
// File path resolution
// Resolves a Niagara ORD-style path ("file:^logs/foo.log") or a plain
// relative/absolute path into a real java.io.File rooted at station home.
// ----------------------------------------------------
private java.io.File resolveToFile(String pathOrOrd)
{
  if (pathOrOrd == null) return null;
  String p = pathOrOrd.trim();
  if (p.length() == 0) return null;

  // Strip "file:" scheme if present
  if (p.startsWith("file:"))
    p = p.substring(5);

  // "^" in a Niagara ORD means relative to station home
  boolean stationRelative = false;
  if (p.startsWith("^"))
  {
    stationRelative = true;
    p = p.substring(1);
    while (p.startsWith("/") || p.startsWith("\\"))
      p = p.substring(1);
  }

  java.io.File f = new java.io.File(p);
  if (stationRelative || !f.isAbsolute())
    f = new java.io.File(javax.baja.sys.Sys.getStationHome(), p);

  return f;
}

// ----------------------------------------------------
// Single unified file append method
// Appends a line to the file, creating the file (and parent folder)
// if they don't already exist.
// ----------------------------------------------------
private void appendLine(String filePath, String line)
{
  java.io.BufferedWriter bw = null;
  try
  {
    java.io.File file = resolveToFile(filePath);
    if (file == null) return;

    // Make sure the parent directory exists
    java.io.File parent = file.getParentFile();
    if (parent != null && !parent.exists())
      parent.mkdirs();

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

private void writeToLog(String message)
{
  appendLine(resolveLogPath(), "[" + now() + "] " + message);
}

private void writeToResults(String line)
{
  appendLine(resolveResultsCsvPath(), line);
}

// Truncate (or create empty) the file, making the parent folder if needed.
private void clearFile(String filePath)
{
  java.io.FileOutputStream fos = null;
  try
  {
    java.io.File file = resolveToFile(filePath);
    if (file == null) return;

    java.io.File parent = file.getParentFile();
    if (parent != null && !parent.exists())
      parent.mkdirs();

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

// Generic archive helper: rename the active file to a timestamped
// copy, falling back to byte-copy + truncate if rename fails.
// Used for both the active log and the results CSV so they share
// identical archive semantics.
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

    // Make sure the archive's parent folder exists
    java.io.File parent = archiveFile.getParentFile();
    if (parent != null && !parent.exists())
      parent.mkdirs();

    // Atomically rename the active file to the timestamped archive.
    // If rename fails (e.g. cross-filesystem or destination exists),
    // fall back to byte copy then truncate the original.
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
      // Truncate the original so the next run starts fresh
      clearFile(activePath);
    }
  }
  catch (Exception e)
  {
    writeToLog(label + " ARCHIVE ERROR: " + e.getMessage());
  }
}

// ----------------------------------------------------
// Archive pruning
// After each archive operation, scan the parent folder for archives
// matching the active file's stem + 19-char timestamp + extension and
// delete the oldest beyond maxArchives. Names sort lexicographically
// in chronological order because of the yyyy-MM-dd_HH-mm-ss format.
// ----------------------------------------------------
private void pruneArchives(String activePath)
{
  try
  {
    int max = resolveMaxArchives();
    if (max <= 0) return; // 0 or negative means keep all

    java.io.File activeFile = resolveToFile(activePath);
    if (activeFile == null) return;

    java.io.File parent = activeFile.getParentFile();
    if (parent == null || !parent.isDirectory()) return;

    String fileName = activeFile.getName();
    int dotIdx = fileName.lastIndexOf('.');
    final String stem = (dotIdx > 0) ? fileName.substring(0, dotIdx) : fileName;
    final String ext  = (dotIdx > 0) ? fileName.substring(dotIdx) : "";
    final int expectedLen = stem.length() + 1 + 19 + ext.length();

    java.io.File[] all = parent.listFiles(new java.io.FileFilter() {
      public boolean accept(java.io.File f) {
        if (!f.isFile()) return false;
        String n = f.getName();
        if (n.length() != expectedLen) return false;
        if (!n.startsWith(stem + "_")) return false;
        if (ext.length() > 0 && !n.endsWith(ext)) return false;
        // Defensive: don't ever match the active file itself
        if (n.equals(stem + ext)) return false;
        return true;
      }
    });

    if (all == null || all.length <= max) return;

    java.util.Arrays.sort(all, new java.util.Comparator() {
      public int compare(Object a, Object b) {
        return ((java.io.File) a).getName().compareTo(
               ((java.io.File) b).getName());
      }
    });

    int toDelete = all.length - max;
    for (int i = 0; i < toDelete; i++)
    {
      try
      {
        if (all[i].delete())
          writeToLog("PRUNED archive: " + all[i].getName());
        else
          writeToLog("PRUNE WARN: could not delete " + all[i].getName());
      }
      catch (Exception e)
      {
        writeToLog("PRUNE ERROR: " + all[i].getName() +
          " - " + e.getMessage());
      }
    }
  }
  catch (Exception e)
  {
    writeToLog("PRUNE EXCEPTION on " + activePath + " - " + e.getMessage());
  }
}

private void archiveLogFile()
{
  archiveFile(resolveLogPath(), "LOG");
  pruneArchives(resolveLogPath());
}

private void archiveResultsCsv()
{
  archiveFile(resolveResultsCsvPath(), "RESULTS-CSV");
  pruneArchives(resolveResultsCsvPath());
}

// ----------------------------------------------------
// Sample CSV - emit a self-documenting starter file
// Format reminder: header row is always skipped on read, so the
// header itself acts as the inline documentation.
// ----------------------------------------------------
private void writeSampleCsv()
{
  String path = resolveSampleCsvPath();
  clearFile(path);
  appendLine(path,
    "BOrd1 (source if Direction is '>'),Slot1," +
    "Direction (> sends 1->2; < sends 2->1)," +
    "BOrd2 (target if Direction is '>'),Slot2");
  appendLine(path,
    "station:|slot:/Drivers/Sensors/TempSensor1,Out,>," +
    "station:|slot:/Drivers/Logic/Controller1,SetPoint");
  appendLine(path,
    "slot:/Drivers/Sensors/TempSensor2,Out,>," +
    "slot:/Drivers/Logic/Output1,In16");
  appendLine(path,
    "station:|slot:/Drivers/Source,Out,<," +
    "station:|slot:/Drivers/Target,In");

  writeToLog("Sample CSV written to: " + path);
  log.info("[LinkCreator] Sample CSV written to: " + path);
}

// ----------------------------------------------------
// Results CSV
// ----------------------------------------------------
private void initResultsCsv()
{
  // Archive the previous run's CSV (if any) to a timestamped copy
  // before we wipe the file and write a fresh header. Same pattern
  // as archiveLogFile() so log + CSV histories stay in lockstep.
  archiveResultsCsv();
  clearFile(resolveResultsCsvPath());
  writeToResults(
    "Timestamp,SourceName,SourceSlotPath," +
    "TargetName,TargetSlotPath,Status,Message,Mode,LinkName,DurationMs");
}

// Compact summary row: "Total,<summary text>" -- two fields, no
// padding commas. The leading blank line keeps the visual gap
// between per-link rows and the summary when opened in Excel.
private void writeResultsSummary(
  int linked, int skipped, int errors, int dryrun, int deleted,
  long totalMs)
{
  appendLine(resolveResultsCsvPath(), "");
  writeToResults("Total,Linked:" + linked +
    " Skipped:" + skipped +
    " Errors:" + errors +
    " DryRun:" + dryrun +
    " Deleted:" + deleted +
    " TotalTime:" + totalMs + "ms" +
    (isCancelled() ? " [CANCELLED]" : ""));
}

// Write a single VALIDATION problem row to the results CSV.
// NOTE (v2.03): no longer called -- the processing pass is now the
// single source of truth for CSV problem rows (see executeCSV). Kept
// here in case you ever want pre-run VALIDATION rows back; if so, call
// it from the validation loop in executeCSV and re-add count seeding.
private void writeValidationRow(CsvIssue issue)
{
  writeToResults(csvEscape(now()) + "," +
    csvEscape("(row " + issue.rowNum + ")") + "," +
    csvEscape(issue.bord1 == null ? "" : issue.bord1) + "," +
    "," +
    csvEscape(issue.bord2 == null ? "" : issue.bord2) + "," +
    "VALIDATION," +
    csvEscape(issue.reason) + "," +
    "CSV,,0.000");
}

private String csvEscape(String s)
{
  if (s == null) s = "";
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ||
      s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
    return '"' + s.replace("\"", "\"\"") + '"';
  return s;
}

// ----------------------------------------------------
// Helpers
// ----------------------------------------------------
private String normalizeOrd(String ordStr)
{
  if (ordStr == null) return ordStr;
  ordStr = ordStr.trim();
  if (ordStr.startsWith("slot:/") && !ordStr.startsWith("station:|"))
    return "station:|" + ordStr;
  return ordStr;
}

private int getModeOrdinal()
{
  javax.baja.status.BStatusEnum m = getOperationMode();
  String tag = (m != null) ? m.toString().trim() : "Direct";
  if (tag.contains("BQL")) return 1;
  if (tag.contains("CSV")) return 2;
  return 0;
}

private String buildLinkName(
  javax.baja.sys.BComponent srcComp, String srcSlotStr, String tgtSlotStr)
{
  // Link names live as slot names ON the target component, so they must be
  // unique among the target's slots. We use the slot pair as the readable
  // body (e.g. "out_to_in16") and append a 4-digit hash of the source
  // component's slot path to disambiguate same-named sources sitting in
  // different parts of the station.
  //
  // v3.0: aligned with Component Copier's buildLinkName() so the two
  // programs generate the IDENTICAL link name for the same source/
  // target/slot combination. Previously LinkCreator omitted the
  // "link_" prefix and the source component's name, so a link made
  // by one program was never recognized as already existing by the
  // other -- verify could report MISSING when a link WAS present
  // (just under the other program's name), reverse/delete couldn't
  // find it to remove it, and a second run could add a duplicate
  // link doing the same job. See this version's header notes for the
  // one-time migration note on links created before this fix.
  String srcPath = srcComp.getSlotPath().toString();
  int pathHash = Math.abs(srcPath.hashCode()) % 10000;
  return "link_" + srcComp.getName() + "_" + srcSlotStr +
    "_to_" + tgtSlotStr + "_" + pathHash;
}

private int[] countResult(int[] counts, String result)
{
  if (result.equals("LINKED"))       counts[0]++;
  else if (result.equals("SKIPPED")) counts[1]++;
  else if (result.equals("ERROR"))   counts[2]++;
  else if (result.equals("DRYRUN"))  counts[3]++;
  else if (result.equals("DELETED")) counts[4]++;
  return counts;
}

private javax.baja.naming.BOrd resolveCsvOrd() throws Exception
{
  // v3.0: sourceOrd is gone; linkSource holds "ord,slot" lines, but a
  // user pointing this program at a CSV file (the old sourceOrd
  // convenience) will have put just a bare .csv path on its own
  // line with no comma. Check the raw, un-comma-split first non-blank
  // line for that case before falling through to targetOrd.
  String raw = resolveLinkSource();
  if (raw.length() > 0)
  {
    String[] lines = raw.split("[\\r\\n]+");
    for (int i = 0; i < lines.length; i++)
    {
      String line = lines[i].trim();
      if (line.length() == 0 || line.startsWith("#")) continue;
      if (line.toLowerCase().endsWith(".csv"))
      {
        writeToLog("CSV auto-detected from linkSource: " + line);
        return javax.baja.naming.BOrd.make(line);
      }
      break; // first non-blank line wasn't a bare .csv path
    }
  }

  javax.baja.naming.BOrd tgtOrd = getTargetOrd();
  if (tgtOrd != null && !tgtOrd.isNull() &&
      tgtOrd.toString().trim().toLowerCase().endsWith(".csv"))
  {
    writeToLog("CSV auto-detected from targetOrd: " + tgtOrd);
    return tgtOrd;
  }

  return null;
}

private int countCsvRows(javax.baja.naming.BOrd csvOrd)
{
  int count = 0;
  java.io.InputStream is = null;
  java.io.BufferedReader br = null;
  try
  {
    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    is = csvFile.getInputStream();
    br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is, "UTF-8"));
    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      if (!line.trim().isEmpty()) count++;
    }
  }
  catch (Exception ignore) {}
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (is != null) try { is.close(); } catch (Exception ignore) {}
  }
  return count;
}

// Validate the CSV. Returns a List of CsvIssue records (v2.02). Each
// record carries the row number, the raw BOrd strings, and a human-
// readable reason. The caller writes these into the results CSV as
// VALIDATION rows so the user can troubleshoot from the CSV alone.
private java.util.List validateCsv(javax.baja.naming.BOrd csvOrd)
{
  java.util.List issues = new java.util.ArrayList();
  java.io.InputStream is = null;
  java.io.BufferedReader br = null;
  try
  {
    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    is = csvFile.getInputStream();
    br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is, "UTF-8"));
    String line;
    int rowNum = 0;

    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      line = line.trim();
      if (line.isEmpty()) continue;

      String[] cols = parseCsvRow(line);
      if (cols.length < 5)
      {
        issues.add(new CsvIssue(rowNum, "", "",
          "Not enough columns (need 5, got " + cols.length + ")"));
        continue;
      }

      String bord1Str  = cols[0];
      String direction = cols[2];
      String bord2Str  = cols[3];

      if (!direction.equals(">") && !direction.equals("<"))
        issues.add(new CsvIssue(rowNum, bord1Str, bord2Str,
          "Invalid direction '" + direction + "' (expected > or <)"));

      try
      {
        javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
      }
      catch (Exception e)
      {
        issues.add(new CsvIssue(rowNum, bord1Str, bord2Str,
          "BOrd1 does not exist / cannot resolve: " + e.getMessage()));
      }

      try
      {
        javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();
      }
      catch (Exception e)
      {
        issues.add(new CsvIssue(rowNum, bord1Str, bord2Str,
          "BOrd2 does not exist / cannot resolve: " + e.getMessage()));
      }
    }
  }
  catch (Exception e)
  {
    issues.add(new CsvIssue(0, "", "", "CSV read error: " + e.getMessage()));
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (is != null) try { is.close(); } catch (Exception ignore) {}
  }
  return issues;
}

// Quote-aware CSV row parser. Splits a line into fields on commas
// but treats commas inside double-quoted fields as literal. Doubled
// quotes ("") inside a quoted field are unescaped to a single ".
// Used by the 5-column CSV reader so slot names that contain
// commas (when properly quoted) parse correctly. Plain unquoted
// CSVs are handled the same as before.
private String[] parseCsvRow(String line)
{
  if (line == null) return new String[0];
  java.util.ArrayList fields = new java.util.ArrayList();
  java.lang.StringBuilder cur = new java.lang.StringBuilder();
  boolean inQuote = false;
  int n = line.length();
  for (int i = 0; i < n; i++)
  {
    char c = line.charAt(i);
    if (inQuote)
    {
      if (c == '"')
      {
        if (i + 1 < n && line.charAt(i + 1) == '"')
        { cur.append('"'); i++; }
        else
        { inQuote = false; }
      }
      else
      {
        cur.append(c);
      }
    }
    else
    {
      if (c == ',')
      {
        fields.add(cur.toString());
        cur.setLength(0);
      }
      else if (c == '"' && cur.length() == 0)
      {
        inQuote = true;
      }
      else
      {
        cur.append(c);
      }
    }
  }
  fields.add(cur.toString());
  String[] out = new String[fields.size()];
  for (int i = 0; i < fields.size(); i++)
    out[i] = ((String) fields.get(i)).trim();
  return out;
}

// ----------------------------------------------------
// Link Verification - checks expected links exist
// without creating or deleting anything
// ----------------------------------------------------
private void verifyLinks() throws Exception
{
  writeToLog("--- VERIFY MODE ---");
  log.info("[LinkCreator] Mode: VERIFY");
  setStatus("[" + now() + "] Verifying links...");

  int mode = getModeOrdinal();
  int found = 0;
  int missing = 0;
  int errors = 0;

  if (mode == 0) // Direct
  {
    javax.baja.naming.BOrd tgtOrd = getTargetOrd();
    java.util.List sources = getLinkSources();

    if (sources.isEmpty() || tgtOrd == null || tgtOrd.isNull())
    {
      writeToLog("VERIFY ERROR: linkSource or targetOrd not set");
      return;
    }

    javax.baja.sys.BComponent tgtComp =
      (javax.baja.sys.BComponent) tgtOrd.resolve().get();

    for (int s = 0; s < sources.size(); s++)
    {
      LinkSourceSpec spec = (LinkSourceSpec) sources.get(s);
      javax.baja.sys.BComponent srcComp = resolveSourceComponent(spec.ord);
      if (srcComp == null) { errors++; continue; }

      String linkName = buildLinkName(srcComp, spec.slot, getTargetSlot());
      if (tgtComp.getSlot(linkName) != null)
      {
        writeToLog("VERIFY OK: " + linkName + " EXISTS");
        found++;
      }
      else
      {
        writeToLog("VERIFY MISSING: " + linkName + " NOT FOUND");
        missing++;
      }
    }
  }
  else if (mode == 1) // BQL
  {
    javax.baja.naming.BOrd tgtOrd = getTargetOrd();
    java.util.List sources = getLinkSources();

    if (sources.isEmpty() || tgtOrd == null || tgtOrd.isNull())
    {
      writeToLog("VERIFY ERROR: linkSource or targetOrd not set");
      return;
    }

    Object bqlResult = tgtOrd.resolve().get();
    java.lang.reflect.Method cursorMethod =
      bqlResult.getClass().getMethod("cursor");
    Object cursor = cursorMethod.invoke(bqlResult);

    java.lang.reflect.Method nextMethod =
      cursor.getClass().getMethod("next");
    java.lang.reflect.Method getMethod =
      cursor.getClass().getMethod("get");
    java.lang.reflect.Method closeMethod =
      cursor.getClass().getMethod("close");

    // Materialize the target list BEFORE verifying (v3.0, ported
    // from Component Copier v2.07) -- a BQL cursor can only be
    // walked once, and multiple sources need to check the same
    // target set.
    java.util.List targets = new java.util.ArrayList();
    int rowNum = 0;
    try
    {
      while (((Boolean) nextMethod.invoke(cursor)).booleanValue())
      {
        rowNum++;

        if (isCancelled())
        {
          writeToLog("VERIFY target read CANCELLED before row " + rowNum);
          break;
        }

        try
        {
          targets.add(getMethod.invoke(cursor));
        }
        catch (Exception e)
        {
          errors++;
          writeToLog("VERIFY ERROR reading row " + rowNum + ": " +
            describeException(e));
        }
      }
    }
    finally { closeMethod.invoke(cursor); }

    writeToLog("VERIFY BQL targets captured: " + targets.size() +
      " | Sources: " + sources.size());

    outerVerify:
    for (int s = 0; s < sources.size(); s++)
    {
      LinkSourceSpec spec = (LinkSourceSpec) sources.get(s);
      javax.baja.sys.BComponent srcComp = resolveSourceComponent(spec.ord);
      if (srcComp == null) { errors += targets.size(); continue; }

      for (int t = 0; t < targets.size(); t++)
      {
        if (isCancelled())
        {
          writeToLog("VERIFY CANCELLED before source " + (s + 1) +
            ", target " + (t + 1));
          break outerVerify;
        }

        try
        {
          javax.baja.sys.BComponent tgtComp =
            (javax.baja.sys.BComponent) targets.get(t);
          String linkName = buildLinkName(srcComp, spec.slot, getTargetSlot());
          if (tgtComp.getSlot(linkName) != null)
          {
            writeToLog("VERIFY OK: " + tgtComp.getName() +
              " -> " + linkName + " EXISTS");
            found++;
          }
          else
          {
            writeToLog("VERIFY MISSING: " + tgtComp.getName() +
              " -> " + linkName + " NOT FOUND");
            missing++;
          }
        }
        catch (Exception e)
        {
          errors++;
          writeToLog("VERIFY ERROR on source " + (s + 1) + ", target " +
            (t + 1) + ": " + describeException(e));
        }
      }
    }
  }
  else if (mode == 2) // CSV
  {
    javax.baja.naming.BOrd csvOrd = resolveCsvOrd();
    if (csvOrd == null)
    {
      writeToLog("VERIFY ERROR: No CSV file found");
      return;
    }

    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    java.io.InputStream is = csvFile.getInputStream();
    java.io.BufferedReader br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is, "UTF-8"));

    int rowNum = 0;
    String line;
    try
    {
      while ((line = br.readLine()) != null)
      {
        rowNum++;
        if (rowNum == 1) continue;
        line = line.trim();
        if (line.isEmpty()) continue;

        // Cancellation checkpoint
        if (isCancelled())
        {
          writeToLog("VERIFY CANCELLED before row " + rowNum);
          break;
        }

        String[] cols = parseCsvRow(line);
        if (cols.length < 5) continue;

        String bord1Str  = cols[0];
        String slot1Str  = cols[1];
        String direction = cols[2];
        String bord2Str  = cols[3];
        String slot2Str  = cols[4];

        try
        {
          javax.baja.sys.BComponent comp1 = (javax.baja.sys.BComponent)
            javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
          javax.baja.sys.BComponent comp2 = (javax.baja.sys.BComponent)
            javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();

          javax.baja.sys.BComponent srcComp =
            direction.equals(">") ? comp1 : comp2;
          javax.baja.sys.BComponent tgtComp =
            direction.equals(">") ? comp2 : comp1;
          String srcSlot = direction.equals(">") ? slot1Str : slot2Str;
          String tgtSlot = direction.equals(">") ? slot2Str : slot1Str;

          String linkName = buildLinkName(srcComp, srcSlot, tgtSlot);
          if (tgtComp.getSlot(linkName) != null)
          {
            writeToLog("VERIFY OK row " + rowNum + ": " +
              srcComp.getName() + " -> " + tgtComp.getName() +
              " [" + linkName + "] EXISTS");
            found++;
          }
          else
          {
            writeToLog("VERIFY MISSING row " + rowNum + ": " +
              srcComp.getName() + " -> " + tgtComp.getName() +
              " [" + linkName + "] NOT FOUND");
            missing++;
          }
        }
        catch (Exception e)
        {
          errors++;
          writeToLog("VERIFY ERROR on row " + rowNum + ": " + e.getMessage());
        }
      }
    }
    finally
    {
      br.close();
      is.close();
    }
  }

  String summary = "VERIFY complete - Found:" + found +
    " Missing:" + missing + " Errors:" + errors +
    (isCancelled() ? " [CANCELLED]" : "");
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
}

// ----------------------------------------------------
// Core link processor
// Returns: LINKED / SKIPPED / ERROR / DRYRUN / DELETED
// ----------------------------------------------------
private String processLink(
  javax.baja.sys.BComponent srcComp, String srcSlotStr,
  javax.baja.sys.BComponent tgtComp, String tgtSlotStr,
  String mode)
{
  long t0 = System.nanoTime();
  try
  {
    String linkName = buildLinkName(srcComp, srcSlotStr, tgtSlotStr);
    String srcSlotPath = srcComp.getSlotPath().toString();
    String tgtSlotPath = tgtComp.getSlotPath().toString();

    writeToLog("Processing link: " + linkName);

    // ---------------- DELETE MODE ----------------
    if (isDeleteMode())
    {
      if (tgtComp.getSlot(linkName) == null)
      {
        boolean dry = isDryRun();
        String detail = (dry ? "DRYRUN (delete): would skip - link not found"
                             : "SKIPPED (delete): link not found") +
          " --> " + srcComp.getName() + "[" + srcSlotStr + "] -> " +
          tgtComp.getName() + "[" + tgtSlotStr + "]";
        setStatus("[" + now() + "] " + detail);
        log.warning("[LinkCreator] " + detail);
        writeToLog(detail);
        writeToResults(csvEscape(now()) + "," +
          csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
          csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
          (dry ? "DRYRUN,Would skip - link not found"
               : "SKIPPED,Link not found") + "," +
          csvEscape(mode) + "," + csvEscape(linkName) + ",0.000");
        return dry ? "DRYRUN" : "SKIPPED";
      }

      if (isDryRun())
      {
        String durStr = String.format(java.util.Locale.ROOT, "%.3f",
          (System.nanoTime() - t0) / 1000000.0);
        String detail = "DRYRUN (delete): would remove --> " +
          srcComp.getName() + "[" + srcSlotStr + "] -> " +
          tgtComp.getName() + "[" + tgtSlotStr + "]";
        setStatus("[" + now() + "] " + detail);
        log.info("[LinkCreator] " + detail);
        writeToLog(detail);
        writeToResults(csvEscape(now()) + "," +
          csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
          csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
          "DRYRUN,Would delete," + csvEscape(mode) + "," +
          csvEscape(linkName) + "," + durStr);
        return "DRYRUN";
      }

      tgtComp.remove(linkName);
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DELETED: " + srcComp.getName() + "[" + srcSlotStr +
        "] -> " + tgtComp.getName() + "[" + tgtSlotStr + "] (" + durStr + "ms)";
      setStatus("[" + now() + "] " + detail);
      log.info("[LinkCreator] " + detail);
      writeToLog(detail);
      writeToResults(csvEscape(now()) + "," +
        csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
        csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
        "DELETED,," + csvEscape(mode) + "," + csvEscape(linkName) + "," + durStr);
      return "DELETED";
    }

    // ---------------- CREATE MODE ----------------
    if (tgtComp.getSlot(linkName) != null)
    {
      boolean dry = isDryRun();
      String detail = (dry ? "DRYRUN: would skip - link already exists"
                           : "SKIPPED: Link already exists") +
        " --> " + srcComp.getName() + "[" + srcSlotStr + "] -> " +
        tgtComp.getName() + "[" + tgtSlotStr + "]";
      setStatus("[" + now() + "] " + detail);
      log.warning("[LinkCreator] " + detail);
      writeToLog(detail);
      writeToResults(csvEscape(now()) + "," +
        csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
        csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
        (dry ? "DRYRUN,Would skip - link already exists"
             : "SKIPPED,Link already exists") + "," +
        csvEscape(mode) + "," + csvEscape(linkName) + ",0.000");
      return dry ? "DRYRUN" : "SKIPPED";
    }

    if (isDryRun())
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DRYRUN: would link --> " +
        srcComp.getName() + "[" + srcSlotStr + "] -> " +
        tgtComp.getName() + "[" + tgtSlotStr + "]";
      setStatus("[" + now() + "] " + detail);
      log.info("[LinkCreator] " + detail);
      writeToLog(detail);
      writeToResults(csvEscape(now()) + "," +
        csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
        csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
        "DRYRUN,Would create link," + csvEscape(mode) + "," +
        csvEscape(linkName) + "," + durStr);
      return "DRYRUN";
    }

    String srcHandle = srcComp.getHandle().toString();
    javax.baja.naming.BOrd srcHandleOrd =
      javax.baja.naming.BOrd.make("h:" + srcHandle);
    javax.baja.sys.BLink newLink =
      new javax.baja.sys.BLink(srcHandleOrd, srcSlotStr, tgtSlotStr, true);
    tgtComp.add(linkName, newLink, null);

    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
      (System.nanoTime() - t0) / 1000000.0);
    String detail = "LINKED: " + srcComp.getName() + "[" + srcSlotStr +
      "] -> " + tgtComp.getName() + "[" + tgtSlotStr + "] (" + durStr + "ms)";
    setStatus("[" + now() + "] " + detail);
    log.info("[LinkCreator] SUCCESS - " + detail);
    writeToLog("SUCCESS - " + detail);
    writeToResults(csvEscape(now()) + "," +
      csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
      csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
      "LINKED,," + csvEscape(mode) + "," + csvEscape(linkName) + "," + durStr);
    return "LINKED";
  }
  catch (Exception e)
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
      (System.nanoTime() - t0) / 1000000.0);
    String reason = describeException(e);
    String detail = "ERROR in processLink: " + reason;
    setStatus("[" + now() + "] " + detail);
    log.severe("[LinkCreator] " + detail);
    writeToLog(detail);
    writeToResults(csvEscape(now()) + "," +
      csvEscape(srcComp.getName()) + ",," +
      csvEscape(tgtComp.getName()) + ",," +
      "ERROR," + csvEscape(reason) + "," +
      csvEscape(mode) + ",," + durStr);
    return "ERROR";
  }
}

// ----------------------------------------------------
// MODE: Direct
// ----------------------------------------------------
private void executeDirect(long runStart) throws Exception
{
  writeToLog("Mode: DIRECT" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: DIRECT");

  javax.baja.naming.BOrd tgtOrd = getTargetOrd();
  if (tgtOrd == null || tgtOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd not set";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }

  java.util.List sources = getLinkSources();
  if (sources.isEmpty())
  {
    String msg = "[" + now() + "] ERROR: linkSource not set";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }

  javax.baja.sys.BComponent tgtComp =
    (javax.baja.sys.BComponent) tgtOrd.resolve().get();

  writeToLog("Sources: " + sources.size() +
    " | Target: " + tgtComp.getName());

  int[] counts = new int[5];
  for (int s = 0; s < sources.size(); s++)
  {
    LinkSourceSpec spec = (LinkSourceSpec) sources.get(s);
    javax.baja.sys.BComponent srcComp = resolveSourceComponent(spec.ord);
    if (srcComp == null) { counts[2]++; continue; }

    setStatus("[" + now() + "] Processing source " + (s + 1) +
      " of " + sources.size() + " (" + srcComp.getName() + ")...");

    String result = processLink(
      srcComp, spec.slot, tgtComp, getTargetSlot(), "Direct");
    countResult(counts, result);
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "Direct complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

// ----------------------------------------------------
// MODE: BQL
// ----------------------------------------------------
private void executeBQL(long runStart) throws Exception
{
  writeToLog("Mode: BQL" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: BQL");

  javax.baja.naming.BOrd tgtOrd = getTargetOrd();
  if (tgtOrd == null || tgtOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd (BQL) not set";
    setStatus(msg); writeToLog(msg); return;
  }

  java.util.List sources = getLinkSources();
  if (sources.isEmpty())
  {
    String msg = "[" + now() + "] ERROR: linkSource not set";
    setStatus(msg); writeToLog(msg); return;
  }

  Object bqlResult = tgtOrd.resolve().get();
  writeToLog("BQL result type: " + bqlResult.getClass().getName());

  // ----------------------------------------------------
  // Materialize the target list BEFORE any linking starts (v3.0,
  // ported from Component Copier v2.07). A BQL cursor can only be
  // walked once, and multiple sources need to run against the same
  // target set.
  // ----------------------------------------------------
  java.util.List targets = new java.util.ArrayList();
  int[] counts = new int[5];

  try
  {
    java.lang.reflect.Method cursorMethod =
      bqlResult.getClass().getMethod("cursor");
    Object cursor = cursorMethod.invoke(bqlResult);

    java.lang.reflect.Method nextMethod =
      cursor.getClass().getMethod("next");
    java.lang.reflect.Method getMethod =
      cursor.getClass().getMethod("get");
    java.lang.reflect.Method closeMethod =
      cursor.getClass().getMethod("close");

    int rowNum = 0;
    try
    {
      while (((Boolean) nextMethod.invoke(cursor)).booleanValue())
      {
        rowNum++;

        if (isCancelled())
        {
          writeToLog("BQL target read CANCELLED before row " + rowNum);
          break;
        }

        try
        {
          targets.add(getMethod.invoke(cursor));
        }
        catch (Exception e)
        {
          counts[2]++;
          writeToLog("BQL ERROR reading row " + rowNum + ": " +
            describeException(e));
        }
      }
    }
    finally { closeMethod.invoke(cursor); }
  }
  catch (Exception e)
  {
    writeToLog("BQL CURSOR ERROR: " + e.getMessage());
    setStatus("[" + now() + "] BQL CURSOR ERROR: " + e.getMessage());
    return;
  }

  writeToLog("BQL targets captured: " + targets.size() +
    " | Sources: " + sources.size());

  int totalPairs = targets.size() * sources.size();
  int pairNum = 0;

  outerBql:
  for (int s = 0; s < sources.size(); s++)
  {
    LinkSourceSpec spec = (LinkSourceSpec) sources.get(s);
    javax.baja.sys.BComponent srcComp = resolveSourceComponent(spec.ord);
    if (srcComp == null) { counts[2] += targets.size(); continue; }

    for (int t = 0; t < targets.size(); t++)
    {
      pairNum++;

      if (isCancelled())
      {
        writeToLog("BQL RUN CANCELLED before pair " + pairNum +
          " of " + totalPairs);
        break outerBql;
      }

      javax.baja.sys.BComponent tgtComp =
        (javax.baja.sys.BComponent) targets.get(t);
      setStatus("[" + now() + "] BQL: source " + (s + 1) + " of " +
        sources.size() + " (" + srcComp.getName() + "), target " +
        (t + 1) + " of " + targets.size() + "...");
      writeToLog("BQL " + srcComp.getName() + " -> " + tgtComp.getName());

      try
      {
        String result = processLink(
          srcComp, spec.slot, tgtComp, getTargetSlot(), "BQL");
        countResult(counts, result);
      }
      catch (Exception e)
      {
        counts[2]++;
        writeToLog("BQL ERROR on pair " + pairNum + ": " +
          describeException(e));
      }
    }
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "BQL complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

// ----------------------------------------------------
// MODE: CSV
// ----------------------------------------------------
private void executeCSV(long runStart) throws Exception
{
  writeToLog("Mode: CSV" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: CSV");

  javax.baja.naming.BOrd csvOrd = resolveCsvOrd();
  if (csvOrd == null)
  {
    String msg = "[" + now() + "] ERROR: No CSV file found in linkSource or targetOrd";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }

  writeToLog("Using CSV: " + csvOrd.toString());

  // --- Validation pass (v2.03: logs only; the processing pass below
  //     is the single source of truth for CSV problem rows, so we no
  //     longer write VALIDATION rows or seed the Errors count here --
  //     that was causing every bad row to appear twice). ---
  setStatus("[" + now() + "] CSV: validating...");
  java.util.List validationIssues = validateCsv(csvOrd);
  if (validationIssues.size() > 0)
  {
    writeToLog("CSV VALIDATION ISSUES (" + validationIssues.size() +
      ") - see ERROR/SKIPPED rows in results CSV for details:");
    for (int i = 0; i < validationIssues.size(); i++)
    {
      CsvIssue issue = (CsvIssue) validationIssues.get(i);
      writeToLog("  Row " + issue.rowNum + ": " + issue.reason);
    }
    writeToLog("Proceeding with valid rows...");
  }
  else
  {
    writeToLog("CSV validation passed - no errors found");
  }

  // --- Count total rows for progress display ---
  int totalRows = countCsvRows(csvOrd);
  writeToLog("CSV total data rows: " + totalRows);

  // --- Processing pass ---
  javax.baja.file.BIFile csvFile =
    (javax.baja.file.BIFile) csvOrd.resolve().get();
  java.io.InputStream is = csvFile.getInputStream();
  java.io.BufferedReader br = new java.io.BufferedReader(
    new java.io.InputStreamReader(is, "UTF-8"));

  int[] counts = new int[5];

  int rowNum = 0;
  int dataRow = 0;
  String line;

  try
  {
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;

      line = line.trim();
      if (line.isEmpty()) continue;

      // Cancellation checkpoint
      if (isCancelled())
      {
        writeToLog("CSV RUN CANCELLED before row " + rowNum);
        break;
      }

      dataRow++;
      setStatus("[" + now() + "] CSV: processing row " +
        dataRow + " of " + totalRows + "...");

      String[] cols = parseCsvRow(line);
      if (cols.length < 5)
      {
        writeToLog("SKIPPED row " + rowNum + ": not enough columns");
        // v2.02: also record the skip reason in the results CSV. This
        // is counted as SKIPPED (it was already validated above as an
        // ERROR row, so we don't double-count it into Errors here).
        writeToResults(csvEscape(now()) + "," +
          csvEscape("(row " + rowNum + ")") + ",,,," +
          "SKIPPED," +
          csvEscape("Not enough columns (need 5, got " + cols.length + ")") +
          ",CSV,,0.000");
        counts[1]++;
        continue;
      }

      String bord1Str  = cols[0];
      String slot1Str  = cols[1];
      String direction = cols[2];
      String bord2Str  = cols[3];
      String slot2Str  = cols[4];

      // v2.04: resolve each BOrd in its own block so the ERROR row can
      // name exactly which ord failed and give a real reason.
      javax.baja.sys.BComponent comp1 = null;
      javax.baja.sys.BComponent comp2 = null;

      // --- Resolve BOrd1 ---
      try
      {
        Object o1 =
          javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
        if (!(o1 instanceof javax.baja.sys.BComponent))
        {
          counts[2]++;
          String reason = "BOrd1 resolved to a non-component (" +
            (o1 == null ? "null" : o1.getClass().getSimpleName()) +
            "): " + bord1Str;
          writeToLog("ERROR on row " + rowNum + ": " + reason);
          log.warning("[LinkCreator] CSV row " + rowNum + " error: " + reason);
          writeToResults(csvEscape(now()) + "," +
            csvEscape("(row " + rowNum + ")") + "," +
            csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
            csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
            "ERROR," + csvEscape(reason) + ",CSV,,0.000");
          continue;
        }
        comp1 = (javax.baja.sys.BComponent) o1;
      }
      catch (Exception e)
      {
        counts[2]++;
        String reason = "BOrd1 cannot resolve: " + bord1Str +
          " -- " + describeException(e);
        writeToLog("ERROR on row " + rowNum + ": " + reason);
        log.warning("[LinkCreator] CSV row " + rowNum + " error: " + reason);
        writeToResults(csvEscape(now()) + "," +
          csvEscape("(row " + rowNum + ")") + "," +
          csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
          csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
          "ERROR," + csvEscape(reason) + ",CSV,,0.000");
        continue;
      }

      // --- Resolve BOrd2 ---
      try
      {
        Object o2 =
          javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();
        if (!(o2 instanceof javax.baja.sys.BComponent))
        {
          counts[2]++;
          String reason = "BOrd2 resolved to a non-component (" +
            (o2 == null ? "null" : o2.getClass().getSimpleName()) +
            "): " + bord2Str;
          writeToLog("ERROR on row " + rowNum + ": " + reason);
          log.warning("[LinkCreator] CSV row " + rowNum + " error: " + reason);
          writeToResults(csvEscape(now()) + "," +
            csvEscape("(row " + rowNum + ")") + "," +
            csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
            csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
            "ERROR," + csvEscape(reason) + ",CSV,,0.000");
          continue;
        }
        comp2 = (javax.baja.sys.BComponent) o2;
      }
      catch (Exception e)
      {
        counts[2]++;
        String reason = "BOrd2 cannot resolve: " + bord2Str +
          " -- " + describeException(e);
        writeToLog("ERROR on row " + rowNum + ": " + reason);
        log.warning("[LinkCreator] CSV row " + rowNum + " error: " + reason);
        writeToResults(csvEscape(now()) + "," +
          csvEscape("(row " + rowNum + ")") + "," +
          csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
          csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
          "ERROR," + csvEscape(reason) + ",CSV,,0.000");
        continue;
      }

      // --- Both components resolved: process the link ---
      try
      {
        String result;
        if (direction.equals(">"))
        {
          writeToLog("CSV row " + rowNum + ": " +
            comp1.getName() + "[" + slot1Str + "] -> " +
            comp2.getName() + "[" + slot2Str + "]");
          result = processLink(comp1, slot1Str, comp2, slot2Str, "CSV");
        }
        else if (direction.equals("<"))
        {
          writeToLog("CSV row " + rowNum + ": " +
            comp2.getName() + "[" + slot2Str + "] -> " +
            comp1.getName() + "[" + slot1Str + "]");
          result = processLink(comp2, slot2Str, comp1, slot1Str, "CSV");
        }
        else
        {
          writeToLog("SKIPPED row " + rowNum +
            ": unknown direction '" + direction + "'");
          // record the bad-direction skip in the results CSV.
          writeToResults(csvEscape(now()) + "," +
            csvEscape("(row " + rowNum + ")") + "," +
            csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
            csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
            "SKIPPED," +
            csvEscape("Invalid direction '" + direction +
              "' (expected > or <)") +
            ",CSV,,0.000");
          counts[1]++;
          continue;
        }
        countResult(counts, result);
      }
      catch (Exception e)
      {
        counts[2]++;
        String reason = describeException(e);
        writeToLog("ERROR on row " + rowNum + ": " + reason);
        log.warning("[LinkCreator] CSV row " + rowNum + " error: " + reason);
        writeToResults(csvEscape(now()) + "," +
          csvEscape("(row " + rowNum + ")") + "," +
          csvEscape(ordWithSlot(bord1Str, slot1Str)) + ",," +
          csvEscape(ordWithSlot(bord2Str, slot2Str)) + "," +
          "ERROR," + csvEscape(reason) + ",CSV,,0.000");
      }
    }
  }
  finally
  {
    br.close();
    is.close();
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "CSV complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

public void onStart() throws Exception
{
  updateVersion();
  updateQuickGuide();
  setStatus("[" + now() + "] Ready");
  log.info("[LinkCreator] " + VERSION + " Service started - Ready");
  writeToLog(VERSION + " Service started - Ready");
}

public void onExecute() throws Exception
{
  dryRunActive = false;
  cancelRequested = false;
  runJob();
}

public void onDryRun() throws Exception
{
  dryRunActive = true;
  cancelRequested = false;
  try { runJob(); }
  finally { dryRunActive = false; }
}

private void runJob() throws Exception
{
  long runStart = System.nanoTime();

  archiveLogFile();
  initResultsCsv();

  log.info("[LinkCreator] " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered");
  writeToLog(VERSION + " " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE MODE]" : ""));

  int mode = getModeOrdinal();
  writeToLog("Operation mode: " + mode +
    " (raw: " + getOperationMode().toString() + ")");

  try
  {
    if (mode == 0)      executeDirect(runStart);
    else if (mode == 1) executeBQL(runStart);
    else if (mode == 2) executeCSV(runStart);
    else
    {
      String msg = "[" + now() + "] ERROR: unknown operationMode " + mode;
      setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    }
  }
  catch (Exception e)
  {
    String detail = "ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[LinkCreator] EXCEPTION - " + e.getMessage());
    writeToLog("EXCEPTION - " + e.getMessage());
  }
}

public void onVerify() throws Exception
{
  dryRunActive = false;
  cancelRequested = false;
  archiveLogFile();
  initResultsCsv();
  writeToLog(VERSION + " onVerify triggered");

  try
  {
    verifyLinks();
  }
  catch (Exception e)
  {
    String detail = "VERIFY ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[LinkCreator] " + detail);
    writeToLog(detail);
  }
}

// Set the cancel flag. The currently running BQL / CSV row loop will
// notice between rows and exit cleanly; the in-flight row finishes
// first to avoid leaving a half-created link.
public void onCancel() throws Exception
{
  cancelRequested = true;
  String msg = "Cancel requested - run will stop after current row";
  setStatus("[" + now() + "] " + msg);
  log.info("[LinkCreator] " + msg);
  writeToLog(msg);
}

// Manual archive prune. Useful right after lowering maxArchives,
// without having to wait for the next execute / dryRun / verify run.
public void onPruneArchives() throws Exception
{
  String startMsg = "Manual prune requested (maxArchives=" +
    resolveMaxArchives() + ")";
  setStatus("[" + now() + "] " + startMsg);
  log.info("[LinkCreator] " + startMsg);
  writeToLog(startMsg);

  pruneArchives(resolveLogPath());
  pruneArchives(resolveResultsCsvPath());

  String done = "Prune complete (kept up to " +
    resolveMaxArchives() + " of each)";
  setStatus("[" + now() + "] " + done);
  log.info("[LinkCreator] " + done);
  writeToLog(done);
}

// Write the starter CSV to the path defined by sampleCsvPath. Pure
// side-effect on disk -- no link work, no archiving of the active
// log/results.
public void onCreateSampleCsv() throws Exception
{
  dryRunActive = false;
  cancelRequested = false;

  setStatus("[" + now() + "] Writing sample CSV file...");
  log.info("[LinkCreator] onCreateSampleCsv triggered");
  writeToLog(VERSION + " onCreateSampleCsv triggered");

  try
  {
    writeSampleCsv();
    String msg = "Sample CSV written. Edit it in place, then point the " +
      "program at it (CSV mode) and execute.";
    setStatus("[" + now() + "] " + msg);
    writeToLog(msg);
    log.info("[LinkCreator] " + msg);
  }
  catch (Exception e)
  {
    String detail = "SAMPLE CSV ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[LinkCreator] " + detail);
    writeToLog(detail);
  }
}

public void onStop() throws Exception
{
  log.info("[LinkCreator] Service stopped");
  writeToLog("Service stopped");
}
