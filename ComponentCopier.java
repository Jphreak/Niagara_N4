/*
================================================================================
Program: Multi-Location Component Copier (Direct / BQL / CSV) — Niagara N4.15
Author: F. Lacroix
Version Tag: v6.2

History / Notes:
  • v1.0  — Inspiration from "Photocopier" by Matt Beatty (23/10/2023)
  • v2.0  — Added Input Source as CSV
  • v3.0  — Added All Links Toggle
  • v4.0  — Added Testing Mode (Dry Run)
  • v5.0  — Added Output Actions to CSV logging and Console; startup banner
            (mode-aware). Polished: CSV preflight, dynamic banner
            (mode/source/results), improved SKIPPED reasons, self-tests
  • v6.0  — Full rewrite to match LinkCreator look and feel
            Replaced BRunnableJob background task with synchronous execution
            Added Application Director logging via java.util.logging
            Added timestamped status field updates
            Added logFilePath, previousLogPath, resultsCsvPath as editable Ord slots
            Added lastRun, lastRunSummary, version as read-only status slots
            Added log archiving to previousLogPath on each execution
            Added CSV row progress display in status field
            Smart header detection - skips header only if first row is not an ORD
            Unified file append method shared across log and results CSV
            Removed executeCopy action, displayNames slot and resultsCsvDestination slot
  • v6.1  — Added skip checks in processCopy():
            - Destination already contains source component name
            - Source and destination are the same component
            - Destination is not a valid BComponent container
            - BQL row did not resolve to a BComponent
  • v6.2  — Added Delete mode (deleteComponent boolean slot)
            Deletes component at destination with same name as source
            Dry Run works with Delete mode - shows what would be deleted
            Added total elapsed time to summary line

QUICK GUIDE
--------------------------------------
Purpose: Copy one source component into one or more destination containers
         and log results to a CSV, log file and Application Director.
Modes:
  • Direct — Copy source to a single destination Ord
  • BQL    — Iterate a BQL query; each result row is a destination
  • CSV    — Read a CSV file; first column supplies destination ORDs
Required slots:
  • componentToCopy (BOrd)      — the component to copy
  • copyTo (BOrd)               — destination, BQL query, or CSV file path
  • destinationMode             — Direct=0, BQL=1, CSV=2
  • keepAllLinks (Boolean)      — preserve incoming links on copy
  • dryrun (Boolean)            — simulate without making changes
  • deleteComponent (Boolean)   — delete source-named component from destinations
  • logFilePath (Ord)           — path to log file
  • previousLogPath (Ord)       — path to archived previous log
  • resultsCsvPath (Ord)        — path to results CSV output
  • lastRun (String)            — read only, timestamp of last execution
  • lastRunSummary (String)     — read only, summary of last run results
  • version (String)            — read only, current program version
High-level flow:
  1) Archive previous log and initialize fresh results CSV
  2) Detect mode from destinationMode slot
  3) Resolve source component and destination(s)
  4) Copy or delete (or dry-run) each destination and log results
  5) Write summary line to results CSV and update status slots
================================================================================
*/

private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("MultiLocationComponentCopier");

private static final String VERSION = "v6.2";

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
  return "file:^logs/MultiLocationComponentCopier.log".replace("file:^",Sys.getStationHome()+System.getProperty("file.separator"));
}

private String resolveArchivePath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("previousLogPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/MultiLocationComponentCopier_previous.log".replace("file:^",Sys.getStationHome()+System.getProperty("file.separator"));
}

private String resolveResultsCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("resultsCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/MultiLocationComponentCopier_results.csv".replace("file:^",Sys.getStationHome()+System.getProperty("file.separator"));
}

private boolean isDryRun()
{
  try
  {
    Object val = get("dryrun");
    if (val instanceof javax.baja.sys.BBoolean)
      return ((javax.baja.sys.BBoolean) val).getBoolean();
  }
  catch (Exception ignore) {}
  return false;
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

private void updateLastRun()
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setLastRun", new Class[]{ String.class });
    m.invoke(this, now());
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setLastRun", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, javax.baja.sys.BString.make(now()));
    }
    catch (Exception ignore2) {}
  }
}

private void updateLastRunSummary(String summary)
{
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "setLastRunSummary", new Class[]{ String.class });
    m.invoke(this, summary);
  }
  catch (Exception ignore)
  {
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "setLastRunSummary", new Class[]{ javax.baja.sys.BString.class });
      m.invoke(this, javax.baja.sys.BString.make(summary));
    }
    catch (Exception ignore2) {}
  }
}

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

// ----------------------------------------------------
// Single unified file append method
// ----------------------------------------------------
private void appendLine(String filePath, String line)
{
  try
  {
    javax.baja.naming.BOrd fileOrd =
      javax.baja.naming.BOrd.make(filePath);
    javax.baja.file.BIFile bfile =
      (javax.baja.file.BIFile) fileOrd.resolve().get();

    String existing = "";
    try
    {
      java.io.InputStream is = bfile.getInputStream();
      java.io.BufferedReader br = new java.io.BufferedReader(
        new java.io.InputStreamReader(is));
      java.lang.StringBuilder sb = new java.lang.StringBuilder();
      String l;
      while ((l = br.readLine()) != null)
      {
        sb.append(l);
        sb.append(System.getProperty("line.separator"));
      }
      br.close();
      is.close();
      existing = sb.toString();
    }
    catch (Exception ignore) {}

    java.io.OutputStream os = bfile.getOutputStream();
    java.io.PrintWriter pw = new java.io.PrintWriter(
      new java.io.BufferedWriter(new java.io.OutputStreamWriter(os)));
    pw.print(existing);
    pw.println(line);
    pw.close();
    os.close();
  }
  catch (Exception e)
  {
    setStatus("[" + now() + "] FILE ERROR: " + e.getMessage());
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

private void clearFile(String filePath)
{
  try
  {
    javax.baja.naming.BOrd fileOrd =
      javax.baja.naming.BOrd.make(filePath);
    javax.baja.file.BIFile bfile =
      (javax.baja.file.BIFile) fileOrd.resolve().get();
    java.io.OutputStream os = bfile.getOutputStream();
    os.close();
  }
  catch (Exception e)
  {
    setStatus("[" + now() + "] CLEAR ERROR: " + e.getMessage());
  }
}

private void archiveLogFile()
{
  try
  {
    String logPath = resolveLogPath();
    String archivePath = resolveArchivePath();

    javax.baja.naming.BOrd fileOrd =
      javax.baja.naming.BOrd.make(logPath);
    javax.baja.file.BIFile bfile =
      (javax.baja.file.BIFile) fileOrd.resolve().get();

    java.io.InputStream is;
    try { is = bfile.getInputStream(); is.close(); }
    catch (Exception e) { return; }

    is = bfile.getInputStream();
    java.io.BufferedReader br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is));
    java.lang.StringBuilder sb = new java.lang.StringBuilder();
    String line;
    while ((line = br.readLine()) != null)
    {
      sb.append(line);
      sb.append(System.getProperty("line.separator"));
    }
    br.close();
    is.close();

    if (sb.toString().trim().isEmpty())
      return;

    clearFile(archivePath);
    String[] lines = sb.toString().split("\n");
    for (int i = 0; i < lines.length; i++)
    {
      String l = lines[i].replace("\r", "").trim();
      if (l.length() > 0)
        appendLine(archivePath, l);
    }
    clearFile(logPath);
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

private void writeResultsSummary(
  int copied, int skipped, int failed, int dryrun,
  int deleted, long totalMs)
{
  appendLine(resolveResultsCsvPath(), "");
  writeToResults("Total,,,,,Copied:" + copied +
    " Skipped:" + skipped +
    " Failed:" + failed +
    " DryRun:" + dryrun +
    " Deleted:" + deleted +
    " TotalTime:" + totalMs + "ms,,,");
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
      String detail = "SKIPPED (delete): " + srcName +
        " not found at destination " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.warning("[MultiLocationComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "SKIPPED,Component not found at destination," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + ",0");
      return "SKIPPED";
    }

    // Dry run in delete mode
    if (isDryRun())
    {
      long durMs = (System.nanoTime() - t0) / 1000000L;
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
        durMs);
      return "DRYRUN";
    }

    // Perform delete
    try
    {
      dst.remove(srcName);
      long durMs = (System.nanoTime() - t0) / 1000000L;
      String detail = "DELETED: " + srcName + " from " + dstName +
        " (" + durMs + "ms)";
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
        durMs);
      return "DELETED";
    }
    catch (Exception e)
    {
      long durMs = (System.nanoTime() - t0) / 1000000L;
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
        durMs);
      return "FAILED";
    }
  }

  // ----------------------------------------------------
  // COPY MODE - skip checks
  // ----------------------------------------------------

  // 1. Source and destination are the same component
  if (srcPath.equals(dstPath))
  {
    String detail = "SKIPPED: source and destination are the same - " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "SKIPPED,Source and destination are the same," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0");
    return "SKIPPED";
  }

  // 2. Destination already contains a component with the same name
  if (dst.getSlot(srcName) != null)
  {
    String detail = "SKIPPED: " + dstName +
      " already contains a component named " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "SKIPPED,Component already exists at destination," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0");
    return "SKIPPED";
  }

  // 3. Destination slot path is empty
  if (dstPath == null || dstPath.trim().isEmpty())
  {
    String detail = "SKIPPED: destination is not a valid container";
    setStatus("[" + now() + "] " + detail);
    log.warning("[MultiLocationComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "SKIPPED,Destination is not a valid container," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0");
    return "SKIPPED";
  }

  // ----------------------------------------------------
  // COPY MODE - dry run
  // ----------------------------------------------------
  if (isDryRun())
  {
    long durMs = (System.nanoTime() - t0) / 1000000L;
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
      durMs);
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

    long durMs = (System.nanoTime() - t0) / 1000000L;
    String detail = "COPIED: " + srcName + " -> " + dstName +
      " (" + durMs + "ms)";
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
      durMs);
    return "COPIED";
  }
  catch (Exception e)
  {
    long durMs = (System.nanoTime() - t0) / 1000000L;
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
      durMs);
    return "FAILED";
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
  updateLastRunSummary(summary);
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
  updateLastRunSummary(summary);
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
  updateLastRunSummary(summary);
}

public void onStart() throws Exception
{
  appendLine(resolveArchivePath(),
    "--- Log initialized " + now() + " ---");
  clearFile(resolveArchivePath());

  updateVersion();
  setStatus("[" + now() + "] Ready");
  log.info("[MultiLocationComponentCopier] " + VERSION +
    " Service started - Ready");
  writeToLog(VERSION + " Service started - Ready");
}

public void onExecute() throws Exception
{
  long runStart = System.nanoTime();

  archiveLogFile();
  initResultsCsv();
  updateLastRun();

  log.info("[MultiLocationComponentCopier] onExecute triggered");
  writeToLog(VERSION + " onExecute triggered" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE MODE]" : ""));

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