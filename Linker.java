/*
================================================================================
Program: LinkCreator (Direct / BQL / CSV) — Niagara N4.15
Author: F. Lacroix
Version Tag: v2.1

History / Notes:
  • v1.0  — Initial release
            Basic direct link creation between source and target components
            Single sourceOrd, targetOrd, sourceSlot, targetSlot configuration
  • v1.1  — Added Application Director logging via java.util.logging
            Added timestamped status field updates
            Added file logging via Niagara BIFile API
            Resolved Niagara 4.15 security manager file write restrictions
            Used file:^logs/ ORD pattern for file access
  • v1.2  — Added log archiving to LinkCreator_previous.log on each execution
            Added archive file pre-creation workaround for BIFile limitations
  • v2.0  — Major feature expansion
            Added Direct / BQL / CSV operation modes
            Direct  — one source to one target (original behavior)
            BQL     — one source to many targets via BQL query
            CSV     — individual source to individual target pairs from CSV file
            Added operationMode slot (BStatusEnum: Direct=0, BQL=1, CSV=2)
            Added CSV format: BOrd1, Slot1, Direction, BOrd2, Slot2
            Added direction support in CSV (> and <)
            Added auto-detection of CSV file from sourceOrd or targetOrd
            Added BQL iteration via reflection (BProjectionTable)
            Added normalizeOrd() to handle slot:/ prefix automatically
            Added dryRun boolean slot - simulate without creating links
            Added deleteLinks boolean slot - remove previously created links
            Added resultsCsvPath Ord slot - configurable results CSV location
            Added lastRun, lastRunSummary, version read-only status slots
            Added logFilePath, previousLogPath as editable Ord slots
            Added unified appendLine() file method shared across all outputs
            Added createLink() return value (LINKED/SKIPPED/ERROR) for
            accurate counting
            Fixed counting bug - OK renamed to LINKED, counts now reflect
            actual link creation vs skips
            Added CSV validation pass before processing
            Added CSV row progress display in status field
            Added row counter in status for BQL mode
  • v2.1  — Added verify action
            Checks expected links exist without creating or deleting anything
            Works across all three modes (Direct, BQL, CSV)
            Added unique link name hash suffix to prevent name collisions
            when two source components share the same name
            Added version slot auto-population on onStart()
            Added lastRunSummary slot updated after each execution
            Added updateLastRun() via reflection for Niagara 4.15 compatibility

QUICK GUIDE
--------------------------------------
Purpose: Create, delete or verify Niagara component links between a source
         and one or more target components. Logs all results to the
         Application Director, a log file and a results CSV.
Modes:
  • Direct — One source component slot linked to one target component slot
  • BQL    — One fixed source slot linked to many targets via BQL query
  • CSV    — Individual source/target pairs read from a CSV file
             CSV format: BOrd1, Slot1, Direction (> or <), BOrd2, Slot2
Required slots:
  • sourceSlot (String)         — source component slot name (Direct/BQL)
  • targetSlot (String)         — target component slot name (Direct/BQL)
  • sourceOrd (Ord)             — source component ORD (Direct/BQL)
  • targetOrd (Ord)             — target component ORD or BQL query (Direct/BQL)
                                  or CSV file path (CSV mode)
  • operationMode (StatusEnum)  — Direct=0, BQL=1, CSV=2
  • dryRun (Boolean)            — simulate without creating or deleting links
  • deleteLinks (Boolean)       — delete links instead of creating them
  • logFilePath (Ord)           — path to log file
  • previousLogPath (Ord)       — path to archived previous log
  • resultsCsvPath (Ord)        — path to results CSV output
  • lastRun (String)            — read only, timestamp of last execution
  • lastRunSummary (String)     — read only, summary of last run results
  • version (String)            — read only, current program version
Actions:
  • execute  — run the program in the current operation mode
  • verify   — check expected links exist without making any changes
High-level flow:
  1) Archive previous log and initialize fresh results CSV
  2) Detect mode from operationMode slot
  3) Resolve source and target(s) based on mode
  4) Create, delete or dry-run each link and log results
  5) Write summary line to results CSV and update status slots
================================================================================
*/
private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("LinkCreator");

private static final String VERSION = "v2.1";

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
  return "file:^logs/LinkCreator.log";
}

private String resolveArchivePath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("previousLogPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/LinkCreator_previous.log";
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

private boolean isDryRun()
{
  try
  {
    Object val = get("dryRun");
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
    Object val = get("deleteLinks");
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
    "TargetName,TargetSlotPath,Status,Message,Mode,LinkName");
}

private void writeResultsSummary(
  int linked, int skipped, int errors, int dryrun, int deleted)
{
  appendLine(resolveResultsCsvPath(), "");
  writeToResults("Total,,,,,Linked:" + linked +
    " Skipped:" + skipped +
    " Errors:" + errors +
    " DryRun:" + dryrun +
    " Deleted:" + deleted + ",,,");
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
  javax.baja.naming.BOrd srcOrd = getSourceOrd();
  if (srcOrd != null && !srcOrd.isNull() &&
      srcOrd.toString().trim().toLowerCase().endsWith(".csv"))
  {
    writeToLog("CSV auto-detected from sourceOrd: " + srcOrd);
    return srcOrd;
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

private java.util.List validateCsv(javax.baja.naming.BOrd csvOrd)
{
  java.util.List errors = new java.util.ArrayList();
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
      line = line.trim();
      if (line.isEmpty()) continue;

      String[] cols = line.split(",");
      if (cols.length < 5)
      {
        errors.add("Row " + rowNum + ": not enough columns");
        continue;
      }

      String bord1Str  = cols[0].trim();
      String direction = cols[2].trim();
      String bord2Str  = cols[3].trim();

      if (!direction.equals(">") && !direction.equals("<"))
        errors.add("Row " + rowNum + ": invalid direction '" + direction + "'");

      try
      {
        javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
      }
      catch (Exception e)
      {
        errors.add("Row " + rowNum + ": cannot resolve BOrd1 '" +
          bord1Str + "': " + e.getMessage());
      }

      try
      {
        javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();
      }
      catch (Exception e)
      {
        errors.add("Row " + rowNum + ": cannot resolve BOrd2 '" +
          bord2Str + "': " + e.getMessage());
      }
    }
    br.close();
    is.close();
  }
  catch (Exception e)
  {
    errors.add("CSV read error: " + e.getMessage());
  }
  return errors;
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
    javax.baja.naming.BOrd srcOrd = getSourceOrd();
    javax.baja.naming.BOrd tgtOrd = getTargetOrd();

    if (srcOrd == null || srcOrd.isNull() ||
        tgtOrd == null || tgtOrd.isNull())
    {
      writeToLog("VERIFY ERROR: sourceOrd or targetOrd not set");
      return;
    }

    javax.baja.sys.BComponent srcComp =
      (javax.baja.sys.BComponent) srcOrd.resolve().get();
    javax.baja.sys.BComponent tgtComp =
      (javax.baja.sys.BComponent) tgtOrd.resolve().get();

    String linkName = buildLinkName(srcComp, getSourceSlot(), getTargetSlot());
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
  else if (mode == 1) // BQL
  {
    javax.baja.naming.BOrd srcOrd = getSourceOrd();
    javax.baja.naming.BOrd tgtOrd = getTargetOrd();

    if (srcOrd == null || srcOrd.isNull() ||
        tgtOrd == null || tgtOrd.isNull())
    {
      writeToLog("VERIFY ERROR: sourceOrd or targetOrd not set");
      return;
    }

    javax.baja.sys.BComponent srcComp =
      (javax.baja.sys.BComponent) srcOrd.resolve().get();

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

    int rowNum = 0;
    try
    {
      while (((Boolean) nextMethod.invoke(cursor)).booleanValue())
      {
        rowNum++;
        try
        {
          javax.baja.sys.BComponent tgtComp =
            (javax.baja.sys.BComponent) getMethod.invoke(cursor);
          String linkName = buildLinkName(
            srcComp, getSourceSlot(), getTargetSlot());
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
          writeToLog("VERIFY ERROR on row " + rowNum + ": " + e.getMessage());
        }
      }
    }
    finally { closeMethod.invoke(cursor); }
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
      new java.io.InputStreamReader(is));

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

        String[] cols = line.split(",");
        if (cols.length < 5) continue;

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
    " Missing:" + missing + " Errors:" + errors;
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
  updateLastRunSummary(summary);
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
        String detail = "SKIPPED (delete): link not found --> " +
          srcComp.getName() + "[" + srcSlotStr + "] -> " +
          tgtComp.getName() + "[" + tgtSlotStr + "]";
        setStatus("[" + now() + "] " + detail);
        log.warning("[LinkCreator] " + detail);
        writeToLog(detail);
        writeToResults(csvEscape(now()) + "," +
          csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
          csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
          "SKIPPED,Link not found," + csvEscape(mode) + "," +
          csvEscape(linkName));
        return "SKIPPED";
      }

      if (isDryRun())
      {
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
          csvEscape(linkName));
        return "DRYRUN";
      }

      tgtComp.remove(linkName);
      String detail = "DELETED: " + srcComp.getName() + "[" + srcSlotStr +
        "] -> " + tgtComp.getName() + "[" + tgtSlotStr + "]";
      setStatus("[" + now() + "] " + detail);
      log.info("[LinkCreator] " + detail);
      writeToLog(detail);
      writeToResults(csvEscape(now()) + "," +
        csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
        csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
        "DELETED,," + csvEscape(mode) + "," + csvEscape(linkName));
      return "DELETED";
    }

    // ---------------- CREATE MODE ----------------
    if (tgtComp.getSlot(linkName) != null)
    {
      String detail = "SKIPPED: Link already exists --> " +
        srcComp.getName() + "[" + srcSlotStr + "] -> " +
        tgtComp.getName() + "[" + tgtSlotStr + "]";
      setStatus("[" + now() + "] " + detail);
      log.warning("[LinkCreator] " + detail);
      writeToLog(detail);
      writeToResults(csvEscape(now()) + "," +
        csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
        csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
        "SKIPPED,Link already exists," + csvEscape(mode) + "," +
        csvEscape(linkName));
      return "SKIPPED";
    }

    if (isDryRun())
    {
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
        csvEscape(linkName));
      return "DRYRUN";
    }

    String srcHandle = srcComp.getHandle().toString();
    javax.baja.naming.BOrd srcHandleOrd =
      javax.baja.naming.BOrd.make("h:" + srcHandle);
    javax.baja.sys.BLink newLink =
      new javax.baja.sys.BLink(srcHandleOrd, srcSlotStr, tgtSlotStr, true);
    tgtComp.add(linkName, newLink, null);

    String detail = "LINKED: " + srcComp.getName() + "[" + srcSlotStr +
      "] -> " + tgtComp.getName() + "[" + tgtSlotStr + "]";
    setStatus("[" + now() + "] " + detail);
    log.info("[LinkCreator] SUCCESS - " + detail);
    writeToLog("SUCCESS - " + detail);
    writeToResults(csvEscape(now()) + "," +
      csvEscape(srcComp.getName()) + "," + csvEscape(srcSlotPath) + "," +
      csvEscape(tgtComp.getName()) + "," + csvEscape(tgtSlotPath) + "," +
      "LINKED,," + csvEscape(mode) + "," + csvEscape(linkName));
    return "LINKED";
  }
  catch (Exception e)
  {
    String detail = "ERROR in processLink: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[LinkCreator] " + detail);
    writeToLog(detail);
    writeToResults(csvEscape(now()) + "," +
      csvEscape(srcComp.getName()) + ",," +
      csvEscape(tgtComp.getName()) + ",," +
      "ERROR," + csvEscape(e.getMessage()) + "," +
      csvEscape(mode) + ",");
    return "ERROR";
  }
}

// ----------------------------------------------------
// MODE: Direct
// ----------------------------------------------------
private void executeDirect() throws Exception
{
  writeToLog("Mode: DIRECT" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: DIRECT");

  javax.baja.naming.BOrd srcOrd = getSourceOrd();
  javax.baja.naming.BOrd tgtOrd = getTargetOrd();

  if (srcOrd == null || srcOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: sourceOrd not set";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }
  if (tgtOrd == null || tgtOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd not set";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }

  javax.baja.sys.BComponent srcComp =
    (javax.baja.sys.BComponent) srcOrd.resolve().get();
  javax.baja.sys.BComponent tgtComp =
    (javax.baja.sys.BComponent) tgtOrd.resolve().get();

  writeToLog("Source: " + srcComp.getName() +
    " | Target: " + tgtComp.getName());
  setStatus("[" + now() + "] Processing 1 of 1...");

  int[] counts = new int[5];
  String result = processLink(
    srcComp, getSourceSlot(), tgtComp, getTargetSlot(), "Direct");
  countResult(counts, result);

  String summary = "Direct complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4];
  setStatus("[" + now() + "] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts[0], counts[1], counts[2], counts[3], counts[4]);
  updateLastRunSummary(summary);
}

// ----------------------------------------------------
// MODE: BQL
// ----------------------------------------------------
private void executeBQL() throws Exception
{
  writeToLog("Mode: BQL" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: BQL");

  javax.baja.naming.BOrd srcOrd = getSourceOrd();
  javax.baja.naming.BOrd tgtOrd = getTargetOrd();

  if (srcOrd == null || srcOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: sourceOrd not set";
    setStatus(msg); writeToLog(msg); return;
  }
  if (tgtOrd == null || tgtOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd (BQL) not set";
    setStatus(msg); writeToLog(msg); return;
  }

  javax.baja.sys.BComponent srcComp =
    (javax.baja.sys.BComponent) srcOrd.resolve().get();
  writeToLog("BQL Source: " + srcComp.getName());

  Object bqlResult = tgtOrd.resolve().get();
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
          javax.baja.sys.BComponent tgtComp =
            (javax.baja.sys.BComponent) getMethod.invoke(cursor);
          setStatus("[" + now() + "] BQL processing row " + rowNum + "...");
          writeToLog("BQL Target " + rowNum + ": " + tgtComp.getName());
          String result = processLink(
            srcComp, getSourceSlot(), tgtComp, getTargetSlot(), "BQL");
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

  String summary = "BQL complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4];
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts[0], counts[1], counts[2], counts[3], counts[4]);
  updateLastRunSummary(summary);
}

// ----------------------------------------------------
// MODE: CSV
// ----------------------------------------------------
private void executeCSV() throws Exception
{
  writeToLog("Mode: CSV" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE]" : ""));
  log.info("[LinkCreator] Mode: CSV");

  javax.baja.naming.BOrd csvOrd = resolveCsvOrd();
  if (csvOrd == null)
  {
    String msg = "[" + now() + "] ERROR: No CSV file found in sourceOrd or targetOrd";
    setStatus(msg); log.warning("[LinkCreator] " + msg); writeToLog(msg);
    return;
  }

  writeToLog("Using CSV: " + csvOrd.toString());

  // --- Validation pass ---
  setStatus("[" + now() + "] CSV: validating...");
  java.util.List validationErrors = validateCsv(csvOrd);
  if (validationErrors.size() > 0)
  {
    writeToLog("CSV VALIDATION WARNINGS (" + validationErrors.size() + "):");
    for (int i = 0; i < validationErrors.size(); i++)
      writeToLog("  " + validationErrors.get(i).toString());
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
      if (rowNum == 1) continue;

      line = line.trim();
      if (line.isEmpty()) continue;

      dataRow++;
      setStatus("[" + now() + "] CSV: processing row " +
        dataRow + " of " + totalRows + "...");

      String[] cols = line.split(",");
      if (cols.length < 5)
      {
        writeToLog("SKIPPED row " + rowNum + ": not enough columns");
        counts[1]++;
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
          counts[1]++;
          continue;
        }
        countResult(counts, result);
      }
      catch (Exception e)
      {
        counts[2]++;
        writeToLog("ERROR on row " + rowNum + ": " + e.getMessage());
        log.warning("[LinkCreator] CSV row " + rowNum +
          " error: " + e.getMessage());
      }
    }
  }
  finally
  {
    br.close();
    is.close();
  }

  String summary = "CSV complete - Linked:" + counts[0] +
    " Skipped:" + counts[1] + " Errors:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4];
  setStatus("[" + now() + "] " + summary);
  log.info("[LinkCreator] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts[0], counts[1], counts[2], counts[3], counts[4]);
  updateLastRunSummary(summary);
}

public void onStart() throws Exception
{
  appendLine(resolveArchivePath(),
    "--- Log initialized " + now() + " ---");
  clearFile(resolveArchivePath());

  updateVersion();
  setStatus("[" + now() + "] Ready");
  log.info("[LinkCreator] " + VERSION + " Service started - Ready");
  writeToLog(VERSION + " Service started - Ready");
}

public void onExecute() throws Exception
{
  archiveLogFile();
  initResultsCsv();
  updateLastRun();

  log.info("[LinkCreator] onExecute triggered");
  writeToLog(VERSION + " onExecute triggered" +
    (isDryRun() ? " [DRY RUN]" : "") +
    (isDeleteMode() ? " [DELETE MODE]" : ""));

  int mode = getModeOrdinal();
  writeToLog("Operation mode: " + mode +
    " (raw: " + getOperationMode().toString() + ")");

  try
  {
    if (mode == 0)      executeDirect();
    else if (mode == 1) executeBQL();
    else if (mode == 2) executeCSV();
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
  archiveLogFile();
  initResultsCsv();
  updateLastRun();
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

public void onStop() throws Exception
{
  log.info("[LinkCreator] Service stopped");
  writeToLog("Service stopped");
}