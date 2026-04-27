/*
================================================================================
Program: Multi-Location Component Copier (Direct / BQL / CSV) - Niagara N4.15
Author:  F. Lacroix
Version: v1.00 (First Production Release)
Date:    2026-04-27

Inspiration
-----------
Original concept "Photocopier" by Matt Beatty (23/10/2023). This program
expands that idea with multi-mode destinations, dry-run support, sample-CSV
generation, and an integrated link-creation phase.

Purpose
-------
Copy one source component into one or many destination containers, with an
optional second pass that creates links to the freshly copied components
from a separate links CSV. Logs every action to the Application Director,
a station-rooted log file, and a results CSV.

Modes
-----
  Direct  Copy source to a single destination Ord
  BQL     Iterate a BQL query - each result row is a destination
  CSV     One destination Ord per row, read from a CSV file

Actions
-------
  execute   Run the program in the configured destination mode
  dryRun    Simulate the run without copying, deleting, or linking

Key features
------------
  - keepAllLinks toggle preserves incoming links on the copied component
  - deleteComponent toggle removes the source-named component from each
    destination instead of copying
  - Optional post-copy link phase - if linksCsvPath is set, the program
    reads a 5-column links CSV (same format as LinkCreator) and creates
    the listed links after the copy phase finishes
  - createSampleCsv writes two starter CSVs (copy + links) so the user
    can edit them in place rather than guessing the format
  - Auto-archives the active log to a timestamped copy on every run.
    Archive timestamp reflects the run trigger time.
  - quickGuide String slot displays the on-station user help next to
    the configuration slots.

Outputs
-------
  status (string)        live timestamped progress and final summary
  logFilePath            human-readable log of every operation
  resultsCsvPath         per-copy CSV: timestamp, names, status, etc.
  Application Director   info / warning / severe lines for ops staff

Quick start
-----------
  1) Set componentToCopy and copyTo
  2) Set destinationMode (Direct / BQL / CSV)
  3) Optionally set linksCsvPath for a post-copy link phase
  4) Right-click  >  Actions  >  dryRun   to preview
  5) Right-click  >  Actions  >  execute  to commit
  6) Inspect the log file and results CSV for full details
================================================================================
*/

private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("MultiLocationComponentCopier");

private static final String VERSION = "v1.00";

// On-station user help -- written into the read-only quickGuide slot
// during onStart() so it shows up at the bottom of the property sheet.
private static final String QUICK_GUIDE =
  "Multi-Location Component Copier " + "v1.00\n" +
  "=====================================\n" +
  "\n" +
  "Modes (destinationMode):\n" +
  "  Direct  copy source -> one destination\n" +
  "  BQL     copy source -> each BQL row\n" +
  "  CSV     copy source -> each destination ord listed in CSV\n" +
  "\n" +
  "Actions:\n" +
  "  Execute - run the configured mode\n" +
  "  Dry Run - preview without changes\n" +
  "\n" +
  "Copy CSV format (one column, header row required):\n" +
  "  DestinationOrd\n" +
  "\n" +
  "Links CSV format (5 cols, same as LinkCreator):\n" +
  "  BOrd1, Slot1, Direction, BOrd2, Slot2\n" +
  "\n" +
  "Steps:\n" +
  "  1) Set componentToCopy and copyTo\n" +
  "  2) Choose destinationMode (Direct/BQL/CSV)\n" +
  "  3) dryRun first to preview\n" +
  "  4) execute to commit\n" +
  "  5) check logFilePath and resultsCsvPath for details\n" +
  "\n" +
  "  (Optional) \n" +
  "     1) set createSampleCsv=true and execute to\n" +
  "        get starter CSVs with example rows\n" +
  "     2) (Future) linksCsvPath for post-copy link phase \n" +
  "\n" +
  "Tips:\n" +
  "  - keepAllLinks=true preserves incoming links on copy\n" +
  "  - deleteComponent=true removes source-named components\n" +
  "    instead of copying. dryRun previews delete-mode too.";

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

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
  return "file:^logs/MultiLocationComponentCopier.log";
}

// Build a timestamped archive path from the active log path.
// Inserts "_yyyy-MM-dd_HH-mm-ss" before the file extension, where the
// timestamp is the current run's trigger time (i.e. when this archive
// operation runs). This is more reliable than reading the log file's
// creation time -- on Windows, file tunneling can make the "creation"
// time stick to a stale value across renames, causing repeated archives
// to collide on the same filename.
//   file:^logs/MultiLocationComponentCopier.log
//     -> file:^logs/MultiLocationComponentCopier_2026-04-27_12-23-25.log
private String buildTimestampedArchivePath()
{
  String logPath = resolveLogPath();

  String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
    .format(new java.util.Date());

  int slashIdx = Math.max(logPath.lastIndexOf('/'), logPath.lastIndexOf('\\'));
  int dotIdx = logPath.lastIndexOf('.');

  if (dotIdx > slashIdx)
    return logPath.substring(0, dotIdx) + "_" + ts + logPath.substring(dotIdx);
  return logPath + "_" + ts;
}

private String resolveResultsCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("resultsCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/MultiLocationComponentCopier_results.csv";
}

// Set by onDryRun() to make isDryRun() report true for the duration of
// that one invocation. onExecute() resets it on entry.
private boolean dryRunActive = false;

private boolean isDryRun()
{
  return dryRunActive;
}

private boolean isKeepAllLinks()
{
  try
  {
    Object val = get("keepAllLinks");
    if (val instanceof javax.baja.sys.BBoolean)
      return ((javax.baja.sys.BBoolean) val).getBoolean();
  }
  catch (Exception ignore) {}
  return false;
}

private boolean isDeleteMode()
{
  try
  {
    Object val = get("deleteComponent");
    if (val instanceof javax.baja.sys.BBoolean)
      return ((javax.baja.sys.BBoolean) val).getBoolean();
  }
  catch (Exception ignore) {}
  return false;
}

private boolean isCreateSampleCsv()
{
  try
  {
    Object val = get("createSampleCsv");
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
  return "file:^logs/MultiLocationComponentCopier_SAMPLE.csv";
}

private String resolveLinksCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("linksCsvPath");
    if (ord != null && !ord.isNull())
    {
      String s = ord.toString().trim();
      if (s.length() > 0) return s;
    }
  }
  catch (Exception ignore) {}
  return "";
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

private void archiveLogFile()
{
  try
  {
    String logPath = resolveLogPath();
    java.io.File logFile = resolveToFile(logPath);
    if (logFile == null || !logFile.exists() || logFile.length() == 0)
      return;

    String archivePath = buildTimestampedArchivePath();
    java.io.File archiveFile = resolveToFile(archivePath);
    if (archiveFile == null) return;

    // Make sure the archive's parent folder exists
    java.io.File parent = archiveFile.getParentFile();
    if (parent != null && !parent.exists())
      parent.mkdirs();

    // Atomically rename the active log to the timestamped archive.
    // If rename fails (e.g. cross-filesystem or destination exists),
    // fall back to byte copy then truncate the original.
    if (!logFile.renameTo(archiveFile))
    {
      java.io.FileInputStream  fis = null;
      java.io.FileOutputStream fos = null;
      try
      {
        fis = new java.io.FileInputStream(logFile);
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
      // Truncate the original log so the next run starts fresh
      clearFile(logPath);
    }
  }
  catch (Exception e)
  {
    writeToLog("ARCHIVE ERROR: " + e.getMessage());
  }
}

// ----------------------------------------------------
// Results CSV
// ----------------------------------------------------
private void initResultsCsv()
{
  clearFile(resolveResultsCsvPath());
  writeToResults(
    "Timestamp,SourceName,SourceSlotPath," +
    "DestinationName,DestinationSlotPath," +
    "Status,Message,Mode,KeepAllLinks,DurationMs");
}

// Compact summary row: "Total,<summary text>" -- two fields, no
// padding commas. The leading blank line keeps the visual gap
// between per-copy rows and the summary when opened in Excel.
private void writeResultsSummary(
  int copied, int skipped, int failed, int dryrun,
  int deleted, long totalMs)
{
  appendLine(resolveResultsCsvPath(), "");
  writeToResults("Total,Copied:" + copied +
    " Skipped:" + skipped +
    " Failed:" + failed +
    " DryRun:" + dryrun +
    " Deleted:" + deleted +
    " TotalTime:" + totalMs + "ms");
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
  javax.baja.status.BStatusEnum m = getDestinationMode();
  String tag = (m != null) ? m.toString().trim() : "Direct";
  if (tag.contains("BQL")) return 1;
  if (tag.contains("CSV")) return 2;
  return 0;
}

private javax.baja.sys.BComponent makeParams()
{
  javax.baja.sys.BComponent params = new javax.baja.sys.BComponent();
  javax.baja.sys.BBoolean keepLinks =
    javax.baja.sys.BBoolean.make(isKeepAllLinks());
  params.add("keepAllLinks", keepLinks);
  return params;
}

private int countCsvRows(javax.baja.naming.BOrd csvOrd)
{
  int count = 0;
  try
  {
    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    java.io.InputStream is = csvFile.getInputStream();
    java.io.BufferedReader br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is));
    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      if (!line.trim().isEmpty()) count++;
    }
    br.close();
    is.close();
  }
  catch (Exception ignore) {}
  return count;
}

private javax.baja.naming.BOrd resolveCsvOrd() throws Exception
{
  javax.baja.naming.BOrd tgtOrd = getCopyTo();
  if (tgtOrd != null && !tgtOrd.isNull() &&
      tgtOrd.toString().trim().toLowerCase().endsWith(".csv"))
  {
    writeToLog("CSV auto-detected from copyTo: " + tgtOrd);
    return tgtOrd;
  }
  return null;
}

private javax.baja.naming.BOrd buildDestOrd(String cellValue)
{
  String v = (cellValue == null) ? "" : cellValue.trim();
  if (v.isEmpty()) return null;
  if (v.startsWith("station:")) return javax.baja.naming.BOrd.make(v);
  if (v.startsWith("slot:/"))   return javax.baja.naming.BOrd.make("station:|" + v);
  if (v.startsWith("/"))        return javax.baja.naming.BOrd.make("station:|slot:" + v);
  return javax.baja.naming.BOrd.make("station:|slot:/" + v);
}

private String firstCsvField(String line)
{
  if (line == null) return null;
  String s = line.trim();
  if (s.isEmpty()) return "";
  if (s.charAt(0) == '"')
  {
    java.lang.StringBuilder sb = new java.lang.StringBuilder();
    boolean inQuote = true;
    for (int i = 1; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (inQuote)
      {
        if (c == '"')
        {
          if (i + 1 < s.length() && s.charAt(i + 1) == '"')
          { sb.append('"'); i++; }
          else { inQuote = false; }
        }
        else { sb.append(c); }
      }
      else { if (c == ',') break; }
    }
    return sb.toString().trim();
  }
  int comma = s.indexOf(',');
  return (comma >= 0) ? s.substring(0, comma).trim() : s;
}

private int[] countResult(int[] counts, String result)
{
  // counts[0]=copied, [1]=skipped, [2]=failed, [3]=dryrun, [4]=deleted
  if (result.equals("COPIED"))       counts[0]++;
  else if (result.equals("SKIPPED")) counts[1]++;
  else if (result.equals("FAILED"))  counts[2]++;
  else if (result.equals("DRYRUN"))  counts[3]++;
  else if (result.equals("DELETED")) counts[4]++;
  return counts;
}

// ----------------------------------------------------
// Core processor - copy or delete
// Returns: COPIED / SKIPPED / FAILED / DRYRUN / DELETED
// ----------------------------------------------------
private String processCopy(
  javax.baja.sys.BComponent src,
  javax.baja.sys.BComponent dst,
  String mode)
{
  String srcName = "";
  String srcPath = "";
  String dstName = "";
  String dstPath = "";

  try
  {
    srcName = src.getName().toString();
    srcPath = src.getSlotPath().toString();
    dstName = dst.getName().toString();
    dstPath = dst.getSlotPath().toString();
  }
  catch (Exception e)
  {
    writeToLog("ERROR: could not resolve component names - " + e.getMessage());
    return "FAILED";
  }

  long t0 = System.nanoTime();

  // ----------------------------------------------------
  // DELETE MODE
  // ----------------------------------------------------
  if (isDeleteMode())
  {
    // Check if component with source name exists at destination
    if (dst.getSlot(srcName) == null)
    {
      boolean dry = isDryRun();
      String detail = (dry ? "DRYRUN (delete): would skip - "
                           : "SKIPPED (delete): ") +
        srcName + " not found at destination " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.warning("[MultiLocationComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        (dry ? "DRYRUN,Would skip - component not found at destination"
             : "SKIPPED,Component not found at destination") + "," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
      return dry ? "DRYRUN" : "SKIPPED";
    }

    // Dry run in delete mode
    if (isDryRun())
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DRYRUN (delete): would remove " + srcName +
        " from " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.info("[MultiLocationComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "DRYRUN,Would delete," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "DRYRUN";
    }

    // Perform delete
    try
    {
      dst.remove(srcName);
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DELETED: " + srcName + " from " + dstName +
        " (" + durStr + "ms)";
      setStatus("[" + now() + "] " + detail);
      log.info("[MultiLocationComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "DELETED,," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "DELETED";
    }
    catch (Exception e)
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "FAILED (delete): " + srcName +
        " from " + dstName + " - " + e.getMessage();
      setStatus("[" + now() + "] " + detail);
      log.severe("[MultiLocationComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "FAILED," + csvEscape(e.getMessage()) + "," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "FAILED";
    }
  }

  // ----------------------------------------------------
  // COPY MODE - skip checks
  // ----------------------------------------------------

  // 1. Source and destination are the same component
  if (srcPath.equals(dstPath))
  {
    boolean dry = isDryRun();
    String detail = (dry ? "DRYRUN: would skip - source and destination are the same"
                         : "SKIPPED: source and destination are the same") +
      " - " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - source and destination are the same"
           : "SKIPPED,Source and destination are the same") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // 2. Destination already contains a component with the same name
  if (dst.getSlot(srcName) != null)
  {
    boolean dry = isDryRun();
    String detail = (dry ? "DRYRUN: would skip - "
                         : "SKIPPED: ") +
      dstName + " already contains a component named " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - component already exists at destination"
           : "SKIPPED,Component already exists at destination") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // 3. Destination slot path is empty
  if (dstPath == null || dstPath.trim().isEmpty())
  {
    boolean dry = isDryRun();
    String detail = dry ? "DRYRUN: would skip - destination is not a valid container"
                        : "SKIPPED: destination is not a valid container";
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - destination is not a valid container"
           : "SKIPPED,Destination is not a valid container") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // ----------------------------------------------------
  // COPY MODE - dry run
  // ----------------------------------------------------
  if (isDryRun())
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "DRYRUN: would copy " + srcName + " -> " + dstName;
    setStatus("[" + now() + "] " + detail);
    log.info("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "DRYRUN,Would copy," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "DRYRUN";
  }

  // ----------------------------------------------------
  // COPY MODE - perform copy
  // ----------------------------------------------------
  try
  {
    javax.baja.sys.BComponent params = makeParams();
    Mark mark = new Mark(src);
    mark.copyTo(dst, params, null);

    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "COPIED: " + srcName + " -> " + dstName +
      " (" + durStr + "ms)";
    setStatus("[" + now() + "] " + detail);
    log.info("[MultiLocationComponentCopier] SUCCESS - " + detail);
    writeToLog("SUCCESS - " + detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "COPIED,," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "COPIED";
  }
  catch (Exception e)
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "FAILED: " + srcName + " -> " + dstName +
      " - " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "FAILED," + csvEscape(e.getMessage()) + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "FAILED";
  }
}

// ----------------------------------------------------
// Sample CSVs - emit two self-documenting starter files:
// one for copy-mode destinations, one for the post-copy links CSV.
// ----------------------------------------------------
private String deriveLinksSamplePath(String copySamplePath)
{
  if (copySamplePath == null) return "";
  int slashIdx = Math.max(
    copySamplePath.lastIndexOf('/'), copySamplePath.lastIndexOf('\\'));
  int dotIdx = copySamplePath.lastIndexOf('.');
  if (dotIdx > slashIdx)
    return copySamplePath.substring(0, dotIdx) + "_links" +
      copySamplePath.substring(dotIdx);
  return copySamplePath + "_links";
}

private void writeSampleCsvs()
{
  // ---- Copy-mode sample (one column: destination ORDs) ----
  String copyPath = resolveSampleCsvPath();
  clearFile(copyPath);
  appendLine(copyPath,
    "DestinationOrd (one ORD per row; container that will receive the copy)");
  appendLine(copyPath, "station:|slot:/Drivers/Site1/Floor1/Zone1");
  appendLine(copyPath, "station:|slot:/Drivers/Site1/Floor1/Zone2");
  appendLine(copyPath, "slot:/Drivers/Site1/Floor2/Zone1");
  appendLine(copyPath, "slot:/Drivers/Site2/Floor1/Lobby");
  writeToLog("Sample copy CSV written to: " + copyPath);

  // ---- Links sample (5 columns, same format as LinkCreator) ----
  String linksPath = deriveLinksSamplePath(copyPath);
  clearFile(linksPath);
  appendLine(linksPath,
    "BOrd1 (source if Direction is '>'),Slot1," +
    "Direction (> sends 1->2; < sends 2->1)," +
    "BOrd2 (target if Direction is '>'),Slot2");
  appendLine(linksPath,
    "station:|slot:/Drivers/Sensors/TempSensor1,Out,>," +
    "station:|slot:/Drivers/Site1/Floor1/Zone1/CopiedComponent,TempIn");
  appendLine(linksPath,
    "station:|slot:/Drivers/Sensors/TempSensor2,Out,>," +
    "station:|slot:/Drivers/Site1/Floor1/Zone2/CopiedComponent,TempIn");
  appendLine(linksPath,
    "slot:/Drivers/Site1/Floor1/Zone1/CopiedComponent,Output,<," +
    "slot:/Drivers/Site1/Floor1/Zone1/Damper,Command");
  writeToLog("Sample links CSV written to: " + linksPath);

  log.info("[MultiLocationComponentCopier] Sample CSVs written: " +
    copyPath + " and " + linksPath);
}

// ----------------------------------------------------
// Link creation (post-copy) - same format as LinkCreator CSV
// 5 cols: BOrd1, Slot1, Direction (> or <), BOrd2, Slot2
// ----------------------------------------------------
private String buildLinkName(
  javax.baja.sys.BComponent srcComp, String srcSlotStr, String tgtSlotStr)
{
  String srcPath = srcComp.getSlotPath().toString();
  int pathHash = Math.abs(srcPath.hashCode()) % 10000;
  return "link_" + srcComp.getName() + "_" + srcSlotStr +
    "_to_" + tgtSlotStr + "_" + pathHash;
}

// Single link result: LINKED / SKIPPED / ERROR / DRYRUN
private String processLinkRow(
  javax.baja.sys.BComponent srcComp, String srcSlotStr,
  javax.baja.sys.BComponent tgtComp, String tgtSlotStr)
{
  try
  {
    String linkName = buildLinkName(srcComp, srcSlotStr, tgtSlotStr);

    if (tgtComp.getSlot(linkName) != null)
    {
      boolean dry = isDryRun();
      writeToLog((dry ? "LINK DRYRUN: would skip - already exists --> "
                      : "LINK SKIPPED: already exists --> ") +
        srcComp.getName() + "[" + srcSlotStr + "] -> " +
        tgtComp.getName() + "[" + tgtSlotStr + "]");
      return dry ? "DRYRUN" : "SKIPPED";
    }

    if (isDryRun())
    {
      writeToLog("LINK DRYRUN: would link --> " +
        srcComp.getName() + "[" + srcSlotStr + "] -> " +
        tgtComp.getName() + "[" + tgtSlotStr + "]");
      return "DRYRUN";
    }

    String srcHandle = srcComp.getHandle().toString();
    javax.baja.naming.BOrd srcHandleOrd =
      javax.baja.naming.BOrd.make("h:" + srcHandle);
    javax.baja.sys.BLink newLink =
      new javax.baja.sys.BLink(srcHandleOrd, srcSlotStr, tgtSlotStr, true);
    tgtComp.add(linkName, newLink, null);

    writeToLog("LINKED: " + srcComp.getName() + "[" + srcSlotStr +
      "] -> " + tgtComp.getName() + "[" + tgtSlotStr + "]");
    return "LINKED";
  }
  catch (Exception e)
  {
    writeToLog("LINK ERROR: " + e.getMessage());
    return "ERROR";
  }
}

private void executeLinksCsv(String linksCsvPath)
{
  writeToLog("--- Processing links CSV: " + linksCsvPath + " ---");
  setStatus("[" + now() + "] Processing links CSV...");
  log.info("[MultiLocationComponentCopier] Processing links CSV: " +
    linksCsvPath);

  java.io.File linksFile = resolveToFile(linksCsvPath);
  if (linksFile == null || !linksFile.exists())
  {
    writeToLog("LINKS CSV NOT FOUND: " + linksCsvPath +
      " - skipping link phase");
    return;
  }

  int linked = 0, skipped = 0, errors = 0, dryrun = 0;
  int rowNum = 0;

  java.io.BufferedReader br = null;
  try
  {
    br = new java.io.BufferedReader(new java.io.InputStreamReader(
      new java.io.FileInputStream(linksFile), "UTF-8"));
    String line;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue; // skip header

      line = line.trim();
      if (line.isEmpty()) continue;

      String[] cols = line.split(",");
      if (cols.length < 5)
      {
        writeToLog("LINK row " + rowNum + " SKIPPED: not enough columns");
        skipped++;
        continue;
      }

      String bord1Str  = cols[0].trim();
      String slot1Str  = cols[1].trim();
      String direction = cols[2].trim();
      String bord2Str  = cols[3].trim();
      String slot2Str  = cols[4].trim();

      try
      {
        javax.baja.sys.BComponent comp1 = (javax.baja.sys.BComponent)
          javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
        javax.baja.sys.BComponent comp2 = (javax.baja.sys.BComponent)
          javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();

        String result;
        if (direction.equals(">"))
          result = processLinkRow(comp1, slot1Str, comp2, slot2Str);
        else if (direction.equals("<"))
          result = processLinkRow(comp2, slot2Str, comp1, slot1Str);
        else
        {
          writeToLog("LINK row " + rowNum +
            " SKIPPED: unknown direction '" + direction + "'");
          skipped++;
          continue;
        }

        if (result.equals("LINKED"))      linked++;
        else if (result.equals("SKIPPED")) skipped++;
        else if (result.equals("DRYRUN"))  dryrun++;
        else                               errors++;
      }
      catch (Exception e)
      {
        errors++;
        writeToLog("LINK row " + rowNum + " ERROR: " + e.getMessage());
      }
    }
  }
  catch (Exception e)
  {
    writeToLog("LINKS CSV READ ERROR: " + e.getMessage());
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
  }

  String linkSummary = "Links phase complete - Linked:" + linked +
    " Skipped:" + skipped + " Errors:" + errors + " DryRun:" + dryrun;
  writeToLog(linkSummary);
  setStatus("[" + now() + "] " + linkSummary);
  log.info("[MultiLocationComponentCopier] " + linkSummary);
}

// ----------------------------------------------------
// MODE: Direct
// ----------------------------------------------------
private void executeDirect(long runStart) throws Exception
{
  writeToLog("Mode: DIRECT" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[MultiLocationComponentCopier] Mode: DIRECT");

  javax.baja.naming.BOrd srcOrd = getComponentToCopy();
  javax.baja.naming.BOrd dstOrd = getCopyTo();

  if (srcOrd == null || srcOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: componentToCopy not set";
    setStatus(msg); log.warning("[MultiLocationComponentCopier] " + msg);
    writeToLog(msg); return;
  }
  if (dstOrd == null || dstOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: copyTo not set";
    setStatus(msg); log.warning("[MultiLocationComponentCopier] " + msg);
    writeToLog(msg); return;
  }

  javax.baja.sys.BComponent src =
    (javax.baja.sys.BComponent) srcOrd.resolve().get();
  javax.baja.sys.BComponent dst =
    (javax.baja.sys.BComponent) dstOrd.resolve().get();

  writeToLog("Source: " + src.getName() +
    " | Destination: " + dst.getName());
  setStatus("[" + now() + "] Processing 1 of 1...");

  int[] counts = new int[5];
  String result = processCopy(src, dst, "Direct");
  countResult(counts, result);

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = "Direct complete - Copied:" + counts[0] +
    " Skipped:" + counts[1] + " Failed:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms";
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
  log.info("[MultiLocationComponentCopier] Mode: BQL");

  javax.baja.naming.BOrd srcOrd = getComponentToCopy();
  javax.baja.naming.BOrd dstOrd = getCopyTo();

  if (srcOrd == null || srcOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: componentToCopy not set";
    setStatus(msg); writeToLog(msg); return;
  }
  if (dstOrd == null || dstOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: copyTo (BQL) not set";
    setStatus(msg); writeToLog(msg); return;
  }

  javax.baja.sys.BComponent src =
    (javax.baja.sys.BComponent) srcOrd.resolve().get();
  writeToLog("Source: " + src.getName());

  Object bqlResult = dstOrd.resolve().get();
  writeToLog("BQL result type: " + bqlResult.getClass().getName());

  int[] counts = new int[5];
  int rowNum = 0;

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

    try
    {
      while (((Boolean) nextMethod.invoke(cursor)).booleanValue())
      {
        rowNum++;
        try
        {
          Object rowObj = getMethod.invoke(cursor);

          if (!(rowObj instanceof javax.baja.sys.BComponent))
          {
            writeToLog("SKIPPED BQL row " + rowNum +
              ": result is not a BComponent (" +
              rowObj.getClass().getName() + ")");
            counts[1]++;
            continue;
          }

          javax.baja.sys.BComponent dst =
            (javax.baja.sys.BComponent) rowObj;
          setStatus("[" + now() + "] BQL processing row " + rowNum + "...");
          writeToLog("BQL Destination " + rowNum + ": " + dst.getName());
          String result = processCopy(src, dst, "BQL");
          countResult(counts, result);
        }
        catch (Exception e)
        {
          counts[2]++;
          writeToLog("BQL ERROR on row " + rowNum + ": " + e.getMessage());
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

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = "BQL complete - Copied:" + counts[0] +
    " Skipped:" + counts[1] + " Failed:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms";
  setStatus("[" + now() + "] " + summary);
  log.info("[MultiLocationComponentCopier] " + summary);
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
  log.info("[MultiLocationComponentCopier] Mode: CSV");

  javax.baja.naming.BOrd csvOrd = resolveCsvOrd();
  if (csvOrd == null)
  {
    String msg = "[" + now() + "] ERROR: No CSV file found in copyTo";
    setStatus(msg); log.warning("[MultiLocationComponentCopier] " + msg);
    writeToLog(msg); return;
  }

  writeToLog("Using CSV: " + csvOrd.toString());

  javax.baja.naming.BOrd srcOrd = getComponentToCopy();
  if (srcOrd == null || srcOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: componentToCopy not set";
    setStatus(msg); writeToLog(msg); return;
  }

  javax.baja.sys.BComponent src =
    (javax.baja.sys.BComponent) srcOrd.resolve().get();
  writeToLog("Source: " + src.getName());

  int totalRows = countCsvRows(csvOrd);
  writeToLog("CSV total data rows: " + totalRows);

  javax.baja.file.BIFile csvFile =
    (javax.baja.file.BIFile) csvOrd.resolve().get();
  java.io.InputStream is = csvFile.getInputStream();
  java.io.BufferedReader br = new java.io.BufferedReader(
    new java.io.InputStreamReader(is));

  int[] counts = new int[5];
  int rowNum = 0;
  int dataRow = 0;
  String line;

  try
  {
    while ((line = br.readLine()) != null)
    {
      rowNum++;

      // Smart header detection
      if (rowNum == 1)
      {
        String firstField = firstCsvField(line);
        if (firstField != null &&
            !firstField.trim().toLowerCase().startsWith("slot:") &&
            !firstField.trim().toLowerCase().startsWith("station:") &&
            !firstField.trim().toLowerCase().startsWith("file:") &&
            !firstField.trim().toLowerCase().startsWith("h:"))
        {
          writeToLog("Skipping header row: " + firstField);
          continue;
        }
      }

      line = line.trim();
      if (line.isEmpty()) continue;

      dataRow++;
      setStatus("[" + now() + "] CSV: processing row " +
        dataRow + " of " + totalRows + "...");

      String destStr = firstCsvField(line);
      if (destStr == null || destStr.isEmpty())
      {
        writeToLog("SKIPPED row " + rowNum + ": empty destination");
        counts[1]++;
        continue;
      }

      try
      {
        javax.baja.naming.BOrd destOrd = buildDestOrd(destStr);
        if (destOrd == null)
        {
          writeToLog("SKIPPED row " + rowNum +
            ": could not build ORD from '" + destStr + "'");
          counts[1]++;
          continue;
        }

        Object resolved = destOrd.resolve().get();

        if (!(resolved instanceof javax.baja.sys.BComponent))
        {
          writeToLog("SKIPPED row " + rowNum +
            ": destination is not a BComponent (" +
            resolved.getClass().getName() + ")");
          counts[1]++;
          continue;
        }

        javax.baja.sys.BComponent dst =
          (javax.baja.sys.BComponent) resolved;

        writeToLog("CSV row " + rowNum + ": " +
          src.getName() + " -> " + dst.getName());

        String result = processCopy(src, dst, "CSV");
        countResult(counts, result);
      }
      catch (Exception e)
      {
        counts[2]++;
        writeToLog("ERROR on row " + rowNum + ": " + e.getMessage());
        log.warning("[MultiLocationComponentCopier] CSV row " +
          rowNum + " error: " + e.getMessage());
      }
    }
  }
  finally
  {
    br.close();
    is.close();
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = "CSV complete - Copied:" + counts[0] +
    " Skipped:" + counts[1] + " Failed:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms";
  setStatus("[" + now() + "] " + summary);
  log.info("[MultiLocationComponentCopier] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

public void onStart() throws Exception
{
  updateVersion();
  updateQuickGuide();
  setStatus("[" + now() + "] Ready");
  log.info("[MultiLocationComponentCopier] " + VERSION +
    " Service started - Ready");
  writeToLog(VERSION + " Service started - Ready");
}

public void onExecute() throws Exception
{
  dryRunActive = false;
  runJob();
}

public void onDryRun() throws Exception
{
  dryRunActive = true;
  try { runJob(); }
  finally { dryRunActive = false; }
}

private void runJob() throws Exception
{
  long runStart = System.nanoTime();

  archiveLogFile();
  initResultsCsv();

  log.info("[MultiLocationComponentCopier] " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered");
  writeToLog(VERSION + " " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE MODE]" : ""));

  // Sample-CSV short-circuit: write the starter files and stop here so
  // the user can edit them before running for real.
  if (isCreateSampleCsv())
  {
    writeSampleCsvs();
    String msg = "Sample CSV mode - wrote samples and exited. " +
      "Set createSampleCsv to false to run normally.";
    setStatus("[" + now() + "] " + msg);
    writeToLog(msg);
    return;
  }

  int mode = getModeOrdinal();
  writeToLog("Operation mode: " + mode +
    " (raw: " + getDestinationMode().toString() + ")");

  try
  {
    if (mode == 0)      executeDirect(runStart);
    else if (mode == 1) executeBQL(runStart);
    else if (mode == 2) executeCSV(runStart);
    else
    {
      String msg = "[" + now() + "] ERROR: unknown destinationMode " + mode;
      setStatus(msg);
      log.warning("[MultiLocationComponentCopier] " + msg);
      writeToLog(msg);
    }

    // Optional post-copy link phase: if linksCsvPath is set,
    // process it the same way LinkCreator's CSV mode does.
    String linksPath = resolveLinksCsvPath();
    if (linksPath != null && linksPath.length() > 0)
      executeLinksCsv(linksPath);
  }
  catch (Exception e)
  {
    String detail = "ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[MultiLocationComponentCopier] EXCEPTION - " + e.getMessage());
    writeToLog("EXCEPTION - " + e.getMessage());
  }
}

public void onStop() throws Exception
{
  log.info("[MultiLocationComponentCopier] Service stopped");
  writeToLog("Service stopped");
}
