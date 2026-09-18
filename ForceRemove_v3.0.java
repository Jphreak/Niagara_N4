/*
================================================================================
Program: ForceRemove (Direct / BQL / CSV) - Niagara N4.15
Author:  F. Lacroix
Version: v3.0
Date:    2026-09-18

IMPORTS TAB - required rows:
  Predefined : java.util, javax.baja.nre.util, javax.baja.sys,
               javax.baja.status, javax.baja.util, com.tridium.program
  User Def.  : control-rt | javax.baja.control
               file       | javax.baja.file
               baja       | javax.baja.space   <-- for Mark (backup copy)
  By Prop.   : baja       | javax.baja.naming

Changes
-------
  pre-   Original ForceRemovePoints. Direct tree-walk only (targetOrd +
  v3.0   recurse), deletes matching ControlPoints or any matching
         non-folder component, removes links first, optional bottom-up
         empty-folder cleanup, dry-run via an executeDelete toggle,
         quickGuide slot. Version numbering restarts here to align with
         the LinkCreator / Component Copier v3.0 line; the earlier
         standalone releases are collapsed into this "pre-v3.0" line.
  v3.0   Rewritten to match LinkCreator / Component Copier v3.0:
         status/logFilePath/resultsCsvPath slots, auto-archiving of the
         log and results CSV with maxArchives pruning, describeException()
         for never-blank error reasons, dryRun/cancel/pruneArchives/
         createSampleCsv/reverse as Actions instead of a boolean toggle,
         quickGuide + version slots. Notable points:
           - operationMode offers Direct, BQL and CSV. The removal target
             list comes from a recursive walk under targetOrd, a BQL
             query, or a one-column CSV of ords.
           - removeChild(): all deletes go through one helper that removes
             a child by its Property-in-parent (owner derived from the
             child's slot path), not a getProperty(name) lookup, which
             returned null for alarm/history extensions ("No property on
             parent"). This is what makes stripping an alarm/history ext
             off a point work.
           - reverse: every execute COPIES each component (newCopy(), a
             full deep copy) into a holding Folder created under this
             program object BEFORE deleting, and records a manifest row +
             its inbound/outbound links (LinkCreator 5-column CSV shape).
             The reverse action copies them back to their original owners
             and replays the links. If the backup copy fails, the delete
             is SKIPPED so nothing is lost without a backup. The holding
             folder is wiped each execute, so reverse restores the MOST
             RECENT run only. LIMIT: archived history records for a
             removed history extension are not restored (config only).
           - Simplified: pointsOnly / recurse / removeFolders / removeLinks
             slots and the ExtensionOnly removal mode were removed.
             recurse, removeFolders and removeLinks are now always on;
             every matched target is removed as a component. To strip an
             extension off a point, target the extension directly (BQL
             "select * from alarm:AlarmSourceExt", or a CSV/Direct target
             on the ext) - the point stays, only the ext goes.
           - Backup now uses Mark.copyTo (the same intact-copy path
             ComponentCopier uses) instead of newCopy(): a point copies
             WITH its alarm/history extensions, a folder copies WITH its
             whole subtree. Each backup lands in its own numbered
             subfolder ForceRemoveBackup/bk_<seq>/ so same-named items
             never collide. Folders are now backed up too (the folder-
             delete path was previously deleting without a backup).
             Requires the javax.baja.space import row for Mark (see
             IMPORTS TAB above).
           - Extension backup fix: an ext (alarm:AlarmSourceExt etc.)
             cannot be parented by a plain folder - Mark.copyTo threw
             IllegalParentException. When the target is an ext (its parent
             is a BControlPoint) the backup instead copies the ext's
             PARENT POINT into the holding subfolder, and the manifest
             Note column records "EXT:<extName>". On reverse, only that
             ext is copied back onto the live point (the point itself was
             never deleted). This is what makes deleting an alarm/history
             ext off a point reversible.
           - Protected targets: handleOneTarget() now hard-refuses two
             kinds of target before anything else runs, in every mode and
             even in dryRun - (1) anything under the station's Services
             tree, and (2) this program itself or any folder it lives
             inside. (2) exists because a folder delete removes its WHOLE
             subtree: pointing a broad target (or an ancestor folder) at
             this program's own container used to let a run delete the
             folder the program is sitting in - and itself along with it
             - mid-run. Both are logged as SKIPPED with a "PROTECTED"
             reason; nothing else about the target's siblings or the rest
             of the walk is affected.

Purpose
-------
Bulk-remove Niagara components/points that Workbench refuses to delete
in the normal way - including stripping an alarm/history extension off a
point by targeting the extension directly. Logs every action to the
Application Director, a station-rooted log file, and a results CSV, and
backs up every removed component so the run can be reversed.

Modes (operationMode) - how the target list is built
------------------------------------------------------
  Direct  Recursive walk from each target: deletes every matching
          descendant AND the target itself (last). Targets come from the
          multi-line targetList slot (one ord per line, #-comments
          ignored); if targetList is empty, the single targetOrd is used.
          recurse and removeFolders are always on, so a folder is emptied
          then removed, and a single point given as a target is removed.
          nameFilter/typeFilter still decide which descendants match.
  BQL     targetOrd is a BQL query; every result row is a target.
          Materialized into memory before removal starts (a BQL
          cursor can only be walked once).
  CSV     targetOrd points to a one-column CSV file (header "Ord",
          one Baja ord per data row). Each row is a target.
  In BQL and CSV mode nameFilter/typeFilter are still applied to every
  row as a safety net (a non-matching row is logged SKIPPED rather than
  silently ignored, since the list is short and explicit).

Actions
-------
  execute            Run the configured operationMode
  dryRun             Simulate the run - no deletions, no backup, no
                      link/enabled changes; full log + results-CSV
                      detail as if it had run
  createSampleCsv    Write a starter one-column CSV to sampleCsvPath
  cancel             Stop the current run after the in-flight item
                      finishes
  reverse            Restore the components removed by the most recent
                      execute: copies each backup from the holding folder
                      back to its original owner and replays captured
                      links. Does not restore archived history records.
  pruneArchives      Apply the maxArchives limit to the log/results
                      archive folders right now, without a run

Key features
------------
  - Auto-archives the active log AND the results CSV to timestamped
    copies on every run; old archives beyond maxArchives are pruned.
  - describeException() so the Message column is never blank/"null".
  - Every matched (or filter-rejected, in BQL/CSV mode) item gets its
    own results-CSV row: target, status, reason, mode, duration.
  - Cancel action stops long-running BQL/CSV/recursive passes cleanly
    between items.
  - Every removed component is deep-copied to a holding folder first
    (reverse), and its links are captured; if the copy fails the delete
    is skipped.
  - quickGuide String slot mirrors this header for on-station help.

Outputs
-------
  status (string)        live timestamped progress and final summary
  logFilePath            human-readable log of every operation
  resultsCsvPath         per-item CSV: target, status, reason, mode...
  Application Director   info / warning / severe lines for ops staff

Quick start
-----------
  1) BACK UP THE STATION (.dist) before any real (non-dryRun) run.
  2) Choose operationMode (Direct/BQL/CSV).
  3) Set targetOrd (root / BQL query / CSV file) and the filters
     (nameFilter, typeFilter).
  4) Right-click > Actions > dryRun to preview. Review the log and
     results CSV.
  5) Right-click > Actions > execute to commit.
  6) For deeply-nested folder cleanup, run execute a second time so
     newly-empty parent folders also get collapsed.
================================================================================
*/
private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("ForceRemove");

private static final String VERSION = "v3.0";

// On-station user help -- written into the read-only quickGuide slot
// during onStart() so it shows up at the bottom of the property sheet.
private static final String QUICK_GUIDE =
  "ForceRemove " + "v3.0\n" +
  "=====================================\n" +
  "\n" +
  "Target modes (operationMode):\n" +
  "  Direct  recursive walk from each target - deletes every matching\n" +
  "          descendant AND the target itself (last). Targets come from\n" +
  "          the multi-line targetList slot (one ord per line, # =\n" +
  "          comment); if targetList is empty, targetOrd is used.\n" +
  "  BQL     every row returned by the targetOrd BQL query\n" +
  "  CSV     targetOrd points to a 1-column CSV of ords (header \"Ord\")\n" +
  "\n" +
  "Every matched target is removed as a component. recurse,\n" +
  "removeFolders and removeLinks are always on. To strip an alarm or\n" +
  "history extension OFF a point (leaving the point), target the\n" +
  "extension directly, e.g. BQL:\n" +
  "  select * from alarm:AlarmSourceExt\n" +
  "The point stays; only the ext is removed.\n" +
  "\n" +
  "Actions:\n" +
  "  Execute           - run the configured mode\n" +
  "  Dry Run           - preview without changes (no deletes, no backup,\n" +
  "                      no link or enabled-flag edits)\n" +
  "  Create Sample CSV - write a starter 1-column CSV to sampleCsvPath\n" +
  "  Cancel Run        - stop cleanly after the in-flight item finishes\n" +
  "  Reverse           - restore the components removed by the most\n" +
  "                      recent execute (from the in-program backup\n" +
  "                      folder) and replay their captured links. Does\n" +
  "                      NOT restore archived history records.\n" +
  "  Prune Old Archives- apply maxArchives right now, no run\n" +
  "\n" +
  "Filters (nameFilter / typeFilter):\n" +
  "  Mainly for DIRECT mode, to scope a subtree walk - e.g. point at a\n" +
  "  folder and set typeFilter=alarm:AlarmSourceExt to delete only the\n" +
  "  alarm exts under it. substring (name) / exact module:type (type);\n" +
  "  typeFilter is ignored on folders.\n" +
  "  In BQL/CSV the query or file already picks the targets, so leave\n" +
  "  both at \"*\" (match all). A non-\"*\" filter there acts as an extra\n" +
  "  post-filter and will SKIP any row it doesn't match.\n" +
  "\n" +
  "CSV format (1 column, header row required):\n" +
  "  Ord\n" +
  "  station:|slot:/Drivers/BacnetNetwork/Device1/points/Pt1\n" +
  "\n" +
  "Steps:\n" +
  "  1) BACK UP THE STATION first.\n" +
  "  2) Set operationMode, targetOrd, and filters.\n" +
  "  3) dryRun to preview - check logFilePath / resultsCsvPath.\n" +
  "  4) execute to commit.\n" +
  "  5) For nested folder cleanup, run execute again.\n" +
  "\n" +
  "Reverse safety:\n" +
  "  - Every execute deep-copies each component into a holding folder\n" +
  "    under this program BEFORE deleting; if the copy fails, that\n" +
  "    delete is SKIPPED so nothing is lost without a backup.\n" +
  "  - The holding folder is wiped each execute, so Reverse restores\n" +
  "    the MOST RECENT run only.\n" +
  "\n" +
  "Tips:\n" +
  "  - Targeting a folder deletes the WHOLE folder in one unit: its\n" +
  "    points and their exts go with it (they are nested slots), and\n" +
  "    the whole subtree is backed up in one copy for reverse. No need\n" +
  "    to also list the points/exts inside it.\n" +
  "  - If a query/CSV returns a folder AND items inside it, the folder\n" +
  "    is deleted first and the now-gone inner items are skipped\n" +
  "    quietly.\n" +
  "  - Protected targets: the station's Services tree, this program\n" +
  "    itself, and any folder this program lives inside are never\n" +
  "    deleted, no matter what target/query matches them - logged as\n" +
  "    SKIPPED (PROTECTED). Prevents a broad target from deleting the\n" +
  "    folder this program is running in out from under itself.\n" +
  "  - maxArchives caps how many timestamped log/CSV archives are\n" +
  "    kept (default 10). 0 = keep all.";

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// ----------------------------------------------------
// Exception describer - ported from LinkCreator/Component Copier.
// Build a human-readable reason from an exception that is NEVER empty
// or "null".
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

// Format a target + extension name for logs/CSV as "<target> [<ext>]".
// Falls back to just the target when ext is blank.
private String withExt(String targetPath, String extName)
{
  String p = (targetPath == null) ? "" : targetPath;
  if (extName == null || extName.trim().length() == 0) return p;
  return p + " [" + extName.trim() + "]";
}

// ----------------------------------------------------
// CSV target row (validation carrier, same style as LinkCreator's
// CsvIssue). No Baja types.
// ----------------------------------------------------
private static class CsvIssue
{
  int rowNum;
  String ord;
  String reason;

  CsvIssue(int rowNum, String ord, String reason)
  {
    this.rowNum = rowNum;
    this.ord    = ord;
    this.reason = reason;
  }
}

// ----------------------------------------------------
// Cancellation flag
// ----------------------------------------------------
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
  return "file:^logs/ForceRemove.log";
}

// Build a timestamped archive path from the given active file path.
// Inserts "_yyyy-MM-dd_HH-mm-ss" before the file extension, timestamped
// to the current run's trigger time (avoids Windows file-tunneling
// stale-creation-time collisions across renames).
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
  return "file:^logs/ForceRemove_results.csv";
}

// Set by onDryRun() to make isDryRun() report true for the duration of
// that one invocation. onExecute() resets it on entry.
private boolean dryRunActive = false;

private boolean isDryRun()
{
  return dryRunActive;
}

private String resolveSampleCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("sampleCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/ForceRemove_SAMPLE.csv";
}

// Name of the child Folder created UNDER this program object that holds
// the live backup copies of removed components (for reverse). Living
// inside the program keeps the backups with the tool and out of the
// file system. Wiped at the start of every real execute, so reverse
// only ever restores the MOST RECENT run.
private static final String BACKUP_FOLDER_NAME = "ForceRemoveBackup";

// The reverse manifest CSV: one row per backed-up component, recording
// where it came from so reverse can copy it back. Lives under logs so it
// sits next to the run log/results. reverse reads this file.
private String resolveManifestPath()
{
  return "file:^logs/ForceRemove_reverse_manifest.csv";
}

// The captured-links CSV: inbound + outbound links of each backed-up
// component, in the 5-column shape LinkCreator uses
// (BOrd1,Slot1,Direction,BOrd2,Slot2), so reverse can replay them with
// the same link-creation logic. Lives next to the manifest.
private String resolveReverseLinksPath()
{
  return "file:^logs/ForceRemove_reverse_links.csv";
}

// Read the maxArchives slot. Returns 10 if missing/unreadable. 0 (or
// negative) means "keep all archives, never prune".
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
// updateVersion().
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
// File path resolution / append / clear / archive / prune
// Ported verbatim (renamed) from LinkCreator v3.0 so the two tools'
// log and results-CSV behaviour is identical.
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

  return sandboxToStationHome(f, pathOrOrd);
}

// Security: log/results/sample/manifest paths must resolve to somewhere
// under the station's own home folder. Canonicalizes f and verifies
// containment so neither an absolute path (e.g. "file:C:\Windows\...")
// nor a "../" segment in a station-relative path can escape onto the
// wider filesystem. Returns null (refusing the write/read) if it can't
// verify containment - every caller already no-ops safely on null.
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
    String msg = "PATH ERROR: could not verify '" + original + "' - " + e.getMessage();
    setStatus("[" + now() + "] " + msg);
    log.warning("[ForceRemove] " + msg);
    return null;
  }

  String msg = "PATH SANDBOX: refusing '" + original +
    "' - resolves outside the station home folder";
  setStatus("[" + now() + "] " + msg);
  log.warning("[ForceRemove] " + msg);
  return null;
}

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
// Sample CSV - one column of ords
// ----------------------------------------------------
private void writeSampleCsv()
{
  String path = resolveSampleCsvPath();
  clearFile(path);
  appendLine(path, "Ord");
  appendLine(path, "station:|slot:/Drivers/BacnetNetwork/Device1/points/OldPoint1");
  appendLine(path, "station:|slot:/Drivers/BacnetNetwork/Device1/points/OldPoint2");
  appendLine(path, "# lines starting with # are ignored");
  appendLine(path, "slot:/Drivers/Logic/StaleFolder/SomePoint");

  writeToLog("Sample CSV written to: " + path);
  log.info("[ForceRemove] Sample CSV written to: " + path);
}

// ----------------------------------------------------
// Results CSV
// ----------------------------------------------------
private void initResultsCsv()
{
  archiveResultsCsv();
  clearFile(resolveResultsCsvPath());
  writeToResults(
    "Timestamp,TargetName,TargetSlotPath,ExtName,ExtType," +
    "Status,Message,Mode,DurationMs");
}

private void writeResultRow(
  String targetName, String targetPath, String extName, String extType,
  String status, String message, String mode, String durStr)
{
  writeToResults(csvEscape(now()) + "," +
    csvEscape(targetName == null ? "" : targetName) + "," +
    csvEscape(targetPath == null ? "" : targetPath) + "," +
    csvEscape(extName == null ? "" : extName) + "," +
    csvEscape(extType == null ? "" : extType) + "," +
    csvEscape(status) + "," +
    csvEscape(message == null ? "" : message) + "," +
    csvEscape(mode) + "," + durStr);
}

// Compact summary row: "Total,<summary text>". Leading blank line keeps
// the visual gap between per-item rows and the summary in Excel.
private void writeResultsSummary(int[] counts, long totalMs)
{
  appendLine(resolveResultsCsvPath(), "");
  writeToResults("Total,Removed:" + counts[0] +
    " FoldersRemoved:" + counts[1] +
    " Skipped:" + counts[2] +
    " Errors:" + counts[3] +
    " DryRun:" + counts[4] +
    " TotalTime:" + totalMs + "ms" +
    (isCancelled() ? " [CANCELLED]" : ""));
}

private String csvEscape(String s)
{
  if (s == null) s = "";
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ||
      s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
    return '"' + s.replace("\"", "\"\"") + '"';
  return s;
}

// Quote-aware CSV row parser (same as LinkCreator's), kept in case
// sampleCsvPath rows ever carry a quoted Notes column with commas.
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
  try
  {
    javax.baja.sys.BDynamicEnum m = getOperationMode();
    if (m != null) return m.getOrdinal();
  }
  catch (Exception ignore) {}
  return 0;
}

// ----------------------------------------------------
// Filters
// ----------------------------------------------------
private boolean filterEmpty(String f)
{
  return f == null || f.length() == 0 || f.equals("*");
}

private boolean matchesName(javax.baja.sys.BComponent c)
{
  String nf = getNameFilter();
  if (filterEmpty(nf)) return true;
  String name = c.getName();
  return name != null && name.indexOf(nf) >= 0;
}

private boolean matchesType(javax.baja.sys.BComponent c)
{
  String tf = getTypeFilter();
  if (filterEmpty(tf)) return true;
  javax.baja.sys.Type t = c.getType();
  String typeName = t.getModule().getModuleName() + ":" + t.getTypeName();
  return typeName.equals(tf);
}

private boolean matchesPointOrComponent(javax.baja.sys.BComponent c)
{
  return matchesName(c) && matchesType(c);
}

// ====================================================
// Reverse support (v3.0) - copy-before-delete + restore
// ====================================================
// The BOG-encoder approach was dropped: BogEncoder/BogDecoder are not
// reachable by name from a Program Object in this build. Instead we back
// up each component the same proven way Component Copier does -- copy the
// live component (newCopy(), a full deep copy of its config) into a
// holding Folder created UNDER this program object. Reverse copies them
// back to their original owners.
//
// Because the backup is a real in-station copy, if the copy fails we do
// NOT delete (skipDeleteOnBackupFailure): a component is never lost
// without a backup.
//
// Links: inbound + outbound links of each backed-up component are
// captured to a 5-column CSV (BOrd1,Slot1,Direction,BOrd2,Slot2, the
// LinkCreator shape) BEFORE delete, and replayed on reverse with the
// same BLink-by-handle construction LinkCreator uses. Outbound links are
// also carried inside the copy itself; inbound links (from other
// components) are the ones the delete severs, and the CSV restores them.

private boolean backupInitDone = false;
private int backupSeq = 0;

// Resolve (creating if needed) the holding Folder under this program.
// Returns null if it can't be created.
private javax.baja.sys.BComponent getOrCreateBackupFolder()
{
  try
  {
    // 'this' (the Program subclass) exposes BComponent methods at
    // runtime but is not statically a BComponent. get("name") is proven
    // to work on self elsewhere in this file; use it to fetch an
    // existing holding folder, and reflection to add a new one (add()
    // is not reliably visible on the static type here).
    Object existing = null;
    try { existing = get(BACKUP_FOLDER_NAME); } catch (Throwable ignore) {}
    if (existing instanceof javax.baja.sys.BComponent)
      return (javax.baja.sys.BComponent) existing;

    javax.baja.sys.BComponent folder = null;
    try
    {
      folder = new javax.baja.sys.BComponent();
    }
    catch (Throwable t)
    {
      writeToLog("BACKUP FOLDER ERROR: cannot instantiate holding folder - " +
        describeException(t));
      return null;
    }
    if (folder == null) return null;

    // add(...) via reflection against self - signature varies across
    // builds. Try the common overloads in order until one takes.
    boolean added = false;
    String addErr = "";

    // add(String, BValue)
    try
    {
      java.lang.reflect.Method m = this.getClass().getMethod(
        "add", new Class[]{ String.class, javax.baja.sys.BValue.class });
      m.invoke(this, new Object[]{ BACKUP_FOLDER_NAME, folder });
      added = true;
    }
    catch (Throwable t) { addErr = t.getClass().getSimpleName(); }

    // add(String, BValue, Context)
    if (!added)
    {
      try
      {
        java.lang.reflect.Method m = this.getClass().getMethod(
          "add", new Class[]{ String.class, javax.baja.sys.BValue.class,
                              javax.baja.sys.Context.class });
        m.invoke(this, new Object[]{ BACKUP_FOLDER_NAME, folder, null });
        added = true;
      }
      catch (Throwable t) { addErr = addErr + "/" + t.getClass().getSimpleName(); }
    }

    // add(String, BValue, int, Context)
    if (!added)
    {
      try
      {
        java.lang.reflect.Method m = this.getClass().getMethod(
          "add", new Class[]{ String.class, javax.baja.sys.BValue.class,
                              int.class, javax.baja.sys.Context.class });
        m.invoke(this, new Object[]{ BACKUP_FOLDER_NAME, folder,
          new Integer(0), null });
        added = true;
      }
      catch (Throwable t) { addErr = addErr + "/" + t.getClass().getSimpleName(); }
    }

    if (!added)
    {
      writeToLog("BACKUP FOLDER ERROR: cannot add holding folder via " +
        "reflection (" + addErr + "). FIX: add a slot named '" +
        BACKUP_FOLDER_NAME + "' of type baja:Folder to this program's " +
        "slot sheet, then reverse backups will use it directly.");
      return null;
    }

    // Re-fetch to get the mounted instance.
    try
    {
      Object v = get(BACKUP_FOLDER_NAME);
      if (v instanceof javax.baja.sys.BComponent)
        return (javax.baja.sys.BComponent) v;
    }
    catch (Throwable ignore) {}
    return folder;
  }
  catch (Throwable t)
  {
    writeToLog("BACKUP FOLDER ERROR: " + describeException(t));
    return null;
  }
}

// Wipe the holding folder's children and write fresh manifest + links
// headers. Called once at the top of a real (non-dryRun) run.
private void initBackups()
{
  backupInitDone = false;
  backupSeq = 0;

  if (isDryRun()) return;

  try
  {
    javax.baja.sys.BComponent folder = getOrCreateBackupFolder();
    if (folder == null)
    {
      writeToLog("BACKUP INIT ERROR: no holding folder (reverse unavailable this run)");
      return;
    }

    // Clear previous run's copies.
    javax.baja.sys.BComponent[] kids = folder.getChildComponents();
    for (int i = 0; i < kids.length; i++)
    {
      try
      {
        javax.baja.sys.Property p = kids[i].getPropertyInParent();
        if (p != null) folder.remove(p);
      }
      catch (Throwable ignore) {}
    }

    clearFile(resolveManifestPath());
    appendLine(resolveManifestPath(),
      "Seq,OwnerPath,SlotName,RemovedPath,HoldingName,Mode,Note");

    clearFile(resolveReverseLinksPath());
    appendLine(resolveReverseLinksPath(),
      "BOrd1,Slot1,Direction,BOrd2,Slot2");

    backupInitDone = true;
    writeToLog("Reverse backups -> under program slot '" + BACKUP_FOLDER_NAME + "'");
  }
  catch (Exception e)
  {
    writeToLog("BACKUP INIT ERROR: " + describeException(e) +
      " (reverse unavailable this run)");
  }
}

// Reflection helpers for BLink introspection (method names vary / are
// not all public API). invoke0 calls a no-arg method, returns null on
// any failure. strOf null-safely stringifies.
private Object invoke0(Object target, String method)
{
  try
  {
    java.lang.reflect.Method m = target.getClass().getMethod(method, new Class[]{});
    return m.invoke(target, new Object[]{});
  }
  catch (Throwable t) { return null; }
}

private String strOf(Object o)
{
  if (o == null) return "";
  try { return o.toString(); } catch (Throwable t) { return ""; }
}

// Capture inbound + outbound links of comp to the reverse-links CSV in
// LinkCreator's 5-column shape. Direction is always ">" (source -> tgt).
// Outbound: comp is the source; its link slots name the target ords.
// Inbound: other components link INTO comp; we find them via
// comp.getLinks() where the link's parent is some other component.
private void captureLinks(javax.baja.sys.BComponent comp)
{
  if (isDryRun() || !backupInitDone) return;

  String compPath = "?";
  try { compPath = comp.getSlotPath().toString(); } catch (Throwable ignore) {}

  try
  {
    javax.baja.sys.BLink[] links = comp.getLinks();
    for (int i = 0; i < links.length; i++)
    {
      javax.baja.sys.BLink lk = links[i];
      try
      {
        // The link lives ON the target component. Use reflection for the
        // BLink accessors so an unexpected method name degrades at
        // runtime (logged) instead of breaking compilation.
        javax.baja.sys.BComponent linkOwner = null;
        try
        {
          Object parent = invoke0(lk, "getParent");
          if (parent instanceof javax.baja.sys.BComponent)
            linkOwner = (javax.baja.sys.BComponent) parent;
        }
        catch (Throwable ignore) {}
        if (linkOwner == null) continue;

        String targetSlot = strOf(invoke0(lk, "getTargetSlotName"));
        String sourceSlot = strOf(invoke0(lk, "getSourceSlotName"));

        javax.baja.sys.BComponent sourceComp = null;
        try
        {
          Object srcOrdObj = invoke0(lk, "getSourceOrd");
          if (srcOrdObj instanceof javax.baja.naming.BOrd)
          {
            Object so = ((javax.baja.naming.BOrd) srcOrdObj)
              .resolve(linkOwner, null).get();
            if (so instanceof javax.baja.sys.BComponent)
              sourceComp = (javax.baja.sys.BComponent) so;
          }
        }
        catch (Throwable ignore) {}

        String ownerPath = "?";
        try { ownerPath = linkOwner.getSlotPath().toString(); } catch (Throwable ignore) {}
        String sourcePath = (sourceComp != null)
          ? sourceComp.getSlotPath().toString() : "?";

        if (sourceSlot.length() == 0 || targetSlot.length() == 0)
          continue; // nothing useful to replay

        // Record as source -> target (BOrd1=source, BOrd2=target owner).
        appendLine(resolveReverseLinksPath(),
          csvEscape(sourcePath) + "," +
          csvEscape(sourceSlot) + ",>," +
          csvEscape(ownerPath) + "," +
          csvEscape(targetSlot));
      }
      catch (Throwable t)
      { writeToLog("CAPTURE LINK ERROR on " + compPath + ": " + describeException(t)); }
    }
  }
  catch (Throwable t)
  { writeToLog("CAPTURE LINKS ERROR on " + compPath + ": " + describeException(t)); }
}

// Params component for Mark.copyTo - keepAllLinks=true so a copied
// component carries its own links. Same shape ComponentCopier uses.
private javax.baja.sys.BComponent makeBackupParams()
{
  javax.baja.sys.BComponent params = new javax.baja.sys.BComponent();
  try { params.add("keepAllLinks", javax.baja.sys.BBoolean.make(true)); }
  catch (Throwable ignore) {}
  return params;
}

// Add a new empty BComponent child under 'parent' with the given name
// and return the MOUNTED instance. Uses add(String,BValue) then re-reads
// by name so callers get the live child (Mark.copyTo needs a mounted
// destination). Returns null on failure.
private javax.baja.sys.BComponent addChildComponent(
  javax.baja.sys.BComponent parent, String name)
{
  try
  {
    javax.baja.sys.BComponent child = new javax.baja.sys.BComponent();
    parent.add(name, child);
    // Re-fetch the mounted instance.
    javax.baja.sys.Slot s = parent.getSlot(name);
    if (s != null && s.isProperty())
    {
      Object v = parent.get((javax.baja.sys.Property) s);
      if (v instanceof javax.baja.sys.BComponent)
        return (javax.baja.sys.BComponent) v;
    }
    return child;
  }
  catch (Throwable t)
  {
    writeToLog("addChildComponent ERROR for " + name + ": " + describeException(t));
    return null;
  }
}

// If comp is an EXTENSION living on a control point, return that parent
// point; otherwise null. Used so ext backups copy the whole (legal-
// parent) point rather than trying to parent the ext under a plain
// folder, which throws IllegalParentException.
//
// An extension (alarm:AlarmSourceExt, history:*Ext, etc.) is NOT itself
// a control point and NOT a folder. A child point nested under a
// COMPOSITE point also has a control-point parent, but it IS a control
// point - it is a normal point, not an ext, and must copy as itself. So
// we require: parent is a BControlPoint AND comp is neither a control
// point nor a folder.
//
// getParentComponent() on a BQL-returned component is unreliable (it can
// hand back the component itself or a detached instance - the quirk that
// broke removeChild), so we also derive the parent from the slot path.
private javax.baja.sys.BComponent extParentPointOf(javax.baja.sys.BComponent comp)
{
  // comp must not itself be a point or a folder to be an extension.
  try
  {
    if (comp instanceof javax.baja.control.BControlPoint) return null;
    if (isFolderComponent(comp)) return null;
  }
  catch (Throwable ignore) {}

  // 1) Direct check first (cheap, works when the instance is live).
  try
  {
    javax.baja.sys.BComponent parent = comp.getParentComponent();
    if (parent instanceof javax.baja.control.BControlPoint)
      return parent;
  }
  catch (Throwable ignore) {}

  // 2) Path-derived check (robust for BQL-returned instances).
  try
  {
    String p = comp.getSlotPath().toString();
    int slash = p.lastIndexOf('/');
    if (slash > 0)
    {
      String parentPath = p.substring(0, slash);
      Object o = javax.baja.naming.BOrd.make(
        normalizeOrd(parentPath)).resolve().get();
      if (o instanceof javax.baja.control.BControlPoint)
        return (javax.baja.sys.BComponent) o;
    }
  }
  catch (Throwable ignore) {}

  return null;
}

// Copy comp into the holding folder and append a manifest row. Returns
// true only if the copy landed (caller uses this to decide whether the
// delete may proceed). ownerPath/slotName are what reverse needs.
private boolean backupComponent(
  javax.baja.sys.BComponent comp, javax.baja.sys.BComponent owner,
  String slotName, String mode)
{
  if (isDryRun()) return true;   // dryRun: pretend success, delete is a no-op anyway
  if (!backupInitDone) return false;

  try
  {
    javax.baja.sys.BComponent folder = getOrCreateBackupFolder();
    if (folder == null) return false;

    backupSeq++;
    // Per-item subfolder so the backup keeps the component's ORIGINAL
    // name and never collides with same-named items (e.g. the many
    // OutOfRangeAlarmExt across points). Layout:
    //   ForceRemoveBackup/bk_<seq>/<originalName>
    String subName = "bk_" + backupSeq;
    javax.baja.sys.BComponent sub = addChildComponent(folder, subName);
    if (sub == null)
    {
      writeToLog("BACKUP ERROR for " + slotName +
        ": could not create holding subfolder " + subName);
      return false;
    }

    // An extension (e.g. alarm:AlarmSourceExt) CANNOT be legally parented
    // by a plain baja:Component - Mark.copyTo into the bare holding folder
    // throws IllegalParentException. An ext can only live on its point.
    // So when the target is an ext, we copy its PARENT POINT (which
    // legally holds the ext) into the holding subfolder, and record the
    // ext's slot name in the Note column as "EXT:<extName>". On reverse,
    // we copy just that ext child off the stored point back onto the live
    // point. For everything else (points, folders, ordinary components)
    // we copy the target itself.
    javax.baja.sys.BComponent copySource = comp;
    String note = "";
    javax.baja.sys.BComponent extParentPoint = extParentPointOf(comp);
    if (extParentPoint != null)
    {
      copySource = extParentPoint;   // copy the whole point
      note = "EXT:" + slotName;      // remember which ext to restore
      writeToLog("Backup: '" + slotName + "' is an extension - backing up " +
        "its parent point " + safeName(extParentPoint));
    }

    // Copy the source into the subfolder using the same Mark.copyTo
    // mechanism ComponentCopier uses. A point copies WITH its extensions,
    // a folder copies WITH its entire subtree, as one intact unit.
    try
    {
      javax.baja.sys.BComponent params = makeBackupParams();
      Mark mark = new Mark(copySource);
      mark.copyTo(sub, params, null);
    }
    catch (Throwable t)
    {
      writeToLog("BACKUP COPY ERROR for " + slotName + ": " +
        describeException(t));
      return false;
    }

    // The copy landed inside the subfolder under copySource's own name.
    String holdingName = subName;   // manifest points at the subfolder

    // Capture links BEFORE the caller deletes (links still live here).
    captureLinks(comp);

    // Record the removed component's full slot path, and derive the
    // owner path by stripping the last segment. Do NOT use
    // owner.getSlotPath() - for some parents (e.g. points holding an
    // alarm ext, or top-level deletes) that instance returns a
    // truncated path that reverse then can't resolve.
    String removedPath = "?";
    try { removedPath = comp.getSlotPath().toString(); } catch (Throwable ignore) {}

    String ownerPath = "?";
    if (!removedPath.equals("?"))
    {
      int slash = removedPath.lastIndexOf('/');
      if (slash > 0) ownerPath = removedPath.substring(0, slash);
    }
    // Fallback to the owner instance only if the derivation failed.
    if (ownerPath.equals("?"))
    {
      try { ownerPath = owner.getSlotPath().toString(); } catch (Throwable ignore) {}
    }

    // For an ext backup, the owner is the point (removedPath's parent),
    // and the ext must be restored onto that live point. ownerPath as
    // derived above IS the point path, which is what reverse needs.
    appendLine(resolveManifestPath(),
      csvEscape(String.valueOf(backupSeq)) + "," +
      csvEscape(ownerPath) + "," +
      csvEscape(slotName) + "," +
      csvEscape(removedPath) + "," +
      csvEscape(holdingName) + "," +
      csvEscape(mode) + "," +
      csvEscape(note));
    return true;
  }
  catch (Exception e)
  {
    writeToLog("BACKUP ERROR for " + slotName + ": " + describeException(e));
    return false;
  }
}

// Delete one holding copy from the backup folder after it has been
// successfully restored, so a second reverse doesn't clash and the
// folder doesn't accumulate stale copies. Uses the robust removeChild
// helper (a plain folder.remove(Property) can miss, like it did for
// alarm exts). Best-effort; logs the outcome.
private void removeHoldingCopy(
  javax.baja.sys.BComponent folder, String holdingName)
{
  try
  {
    javax.baja.sys.BComponent holding = null;
    javax.baja.sys.Slot s = folder.getSlot(holdingName);
    if (s != null && s.isProperty())
    {
      Object v = folder.get((javax.baja.sys.Property) s);
      if (v instanceof javax.baja.sys.BComponent)
        holding = (javax.baja.sys.BComponent) v;
    }
    if (holding == null)
    {
      writeToLog("REVERSE: holding copy not found for cleanup: " + holdingName);
      return;
    }

    String r = removeChild(folder, holding);
    if (r.startsWith("OK:"))
      writeToLog("REVERSE: removed holding copy " + holdingName +
        " [" + r.substring(3) + "]");
    else
      writeToLog("REVERSE: could NOT remove holding copy " + holdingName +
        " (" + r + ")");
  }
  catch (Throwable t)
  {
    writeToLog("REVERSE: error removing holding copy " + holdingName +
      " after restore: " + describeException(t));
  }
}

// The reverse pass: read the manifest, copy each holding component back
// to its owner under the original slot name, then replay captured links.
// counts: [0]=Restored [2]=Skipped [3]=Errors.
// Count path segments in a slot path, for sorting owners shallowest
// first (a folder must be restored before its contents).
private int depthOf(String slotPath)
{
  if (slotPath == null) return 0;
  int d = 0;
  for (int i = 0; i < slotPath.length(); i++)
    if (slotPath.charAt(i) == '/') d++;
  return d;
}

// Sort a list of BComponent targets shallowest slot-path first, so a
// container folder is processed before its own contents. Components
// whose path can't be read sort last (treated as deepest).
private void sortTargetsShallowestFirst(java.util.List targets)
{
  try
  {
    java.util.Collections.sort(targets, new java.util.Comparator() {
      public int compare(Object a, Object b) {
        return depthOfComp(a) - depthOfComp(b);
      }
    });
  }
  catch (Throwable t)
  {
    writeToLog("Target sort skipped: " + describeException(t));
  }
}

private int depthOfComp(Object o)
{
  if (!(o instanceof javax.baja.sys.BComponent)) return Integer.MAX_VALUE;
  try { return depthOf(((javax.baja.sys.BComponent) o).getSlotPath().toString()); }
  catch (Throwable t) { return Integer.MAX_VALUE; }
}

private void runReverse(long runStart) throws Exception
{
  writeToLog("REVERSE triggered - restoring most recent run");
  log.info("[ForceRemove] REVERSE triggered");

  java.io.File manifest = resolveToFile(resolveManifestPath());
  if (manifest == null || !manifest.exists())
  {
    String msg = "[" + now() + "] REVERSE ERROR: no manifest found at " +
      resolveManifestPath();
    setStatus(msg); log.warning("[ForceRemove] " + msg); writeToLog(msg);
    return;
  }

  javax.baja.sys.BComponent folder = getOrCreateBackupFolder();
  if (folder == null)
  {
    String msg = "[" + now() + "] REVERSE ERROR: backup holding folder not found";
    setStatus(msg); writeToLog(msg); return;
  }

  int[] counts = new int[5]; // [0]=Restored [2]=Skipped [3]=Errors
  java.io.InputStream is = null;
  java.io.BufferedReader br = null;

  // Read all manifest rows first, so we can sort + multi-pass.
  java.util.List rows = new java.util.ArrayList();
  try
  {
    is = new java.io.FileInputStream(manifest);
    br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));

    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue; // header
      line = line.trim();
      if (line.length() == 0) continue;

      String[] cols = parseCsvRow(line);
      if (cols.length < 5)
      {
        counts[3]++;
        writeToLog("REVERSE ERROR row " + rowNum + ": too few columns");
        continue;
      }
      // [0]=ownerPath [1]=slotName [2]=holdingName [3]=note
      String noteCol = (cols.length >= 7) ? cols[6] : "";
      rows.add(new String[]{ cols[1], cols[2], cols[4], noteCol });
    }
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (is != null) try { is.close(); } catch (Exception ignore) {}
  }

  // Sort shallowest owner-path first, so a parent folder/container is
  // restored before the children that live inside it.
  java.util.Collections.sort(rows, new java.util.Comparator() {
    public int compare(Object a, Object b) {
      String pa = ((String[]) a)[0];
      String pb = ((String[]) b)[0];
      int da = depthOf(pa);
      int db = depthOf(pb);
      if (da != db) return da - db;
      return pa.compareTo(pb);
    }
  });

  // Multi-pass: a row whose owner does not exist yet (because the owner
  // is itself a not-yet-restored backup) is retried on a later pass once
  // an earlier restore has created it. Stop when a full pass restores
  // nothing new.
  boolean[] done = new boolean[rows.size()];
  int totalRows = rows.size();
  int pass = 0;

  while (true)
  {
    if (isCancelled())
    {
      writeToLog("REVERSE CANCELLED during pass " + (pass + 1));
      break;
    }

    pass++;
    int restoredThisPass = 0;
    int pendingOwner = 0;

    for (int i = 0; i < rows.size(); i++)
    {
      if (done[i]) continue;
      if (isCancelled()) break;

      String[] r = (String[]) rows.get(i);
      String ownerPath   = r[0];
      String slotName    = r[1];
      String holdingName = r[2];

      // Resolve the owner; if missing, leave for a later pass.
      javax.baja.sys.BComponent owner = null;
      try
      {
        Object o = javax.baja.naming.BOrd.make(
          normalizeOrd(ownerPath)).resolve().get();
        if (o instanceof javax.baja.sys.BComponent)
          owner = (javax.baja.sys.BComponent) o;
      }
      catch (Throwable ignore) {}

      if (owner == null)
      {
        pendingOwner++;
        continue; // retry next pass
      }

      setStatus("[" + now() + "] REVERSE pass " + pass + ": restoring " +
        slotName + "...");

      // Already present? Skip rather than clobber.
      try
      {
        if (owner.getSlot(slotName) != null)
        {
          counts[2]++;
          done[i] = true;
          writeToLog("REVERSE SKIP: slot already exists: " +
            ownerPath + "/" + slotName);
          continue;
        }
      }
      catch (Throwable ignore) {}

      // Find the holding subfolder (bk_<seq>), then the backed-up
      // component inside it (under its original slotName).
      javax.baja.sys.BComponent sub = null;
      try
      {
        javax.baja.sys.Slot hs = folder.getSlot(holdingName);
        if (hs != null && hs.isProperty())
        {
          Object hv = folder.get((javax.baja.sys.Property) hs);
          if (hv instanceof javax.baja.sys.BComponent)
            sub = (javax.baja.sys.BComponent) hv;
        }
      }
      catch (Throwable ignore) {}

      if (sub == null)
      {
        counts[3]++;
        done[i] = true;
        writeToLog("REVERSE ERROR: holding subfolder missing: " + holdingName);
        continue;
      }

      String note = (r.length >= 4) ? r[3] : "";
      boolean isExtRestore = note != null && note.startsWith("EXT:");

      javax.baja.sys.BComponent holding = null;
      try
      {
        if (isExtRestore)
        {
          // Ext backup: sub contains the whole PARENT POINT copy; the ext
          // we want is a child of that point copy, under slotName. Dig
          // one level: sub -> <pointCopy> -> <ext(slotName)>.
          javax.baja.sys.BComponent pointCopy = null;
          javax.baja.sys.BComponent[] subKids = sub.getChildComponents();
          if (subKids.length > 0) pointCopy = subKids[0]; // the stored point
          if (pointCopy != null)
          {
            javax.baja.sys.Slot es = pointCopy.getSlot(slotName);
            if (es != null && es.isProperty())
            {
              Object ev = pointCopy.get((javax.baja.sys.Property) es);
              if (ev instanceof javax.baja.sys.BComponent)
                holding = (javax.baja.sys.BComponent) ev;
            }
          }
          // Salvage: if the ext-in-point lookup fails, the row may have
          // been wrongly flagged EXT (an earlier bug flagged whole points
          // as exts). Fall back to treating the subfolder's own child as
          // the thing to restore, and clear the ext flag so the copy-back
          // targets the owner directly.
          if (holding == null && subKids.length > 0)
          {
            holding = subKids[0];
            isExtRestore = false;
            writeToLog("REVERSE: '" + slotName + "' flagged EXT but no ext " +
              "found in point copy - restoring the stored child directly.");
          }
        }
        else
        {
          javax.baja.sys.Slot cs = sub.getSlot(slotName);
          if (cs != null && cs.isProperty())
          {
            Object cv = sub.get((javax.baja.sys.Property) cs);
            if (cv instanceof javax.baja.sys.BComponent)
              holding = (javax.baja.sys.BComponent) cv;
          }
          // Fallback: exact name not found (renamed on copy-in) - take
          // the subfolder's first child component.
          if (holding == null)
          {
            javax.baja.sys.BComponent[] kids = sub.getChildComponents();
            if (kids.length > 0) holding = kids[0];
          }
        }
      }
      catch (Throwable ignore) {}

      if (holding == null)
      {
        counts[3]++;
        done[i] = true;
        writeToLog("REVERSE ERROR: holding copy missing inside " + holdingName +
          " (expected " + slotName + (isExtRestore ? ", ext-in-point" : "") + ")");
        continue;
      }

      // Copy the backed-up component back onto the owner using Mark.copyTo
      // (the same intact-copy mechanism used at backup time). It lands
      // under the holding component's own name = the original slotName.
      try
      {
        javax.baja.sys.BComponent params = makeBackupParams();
        Mark mark = new Mark(holding);
        mark.copyTo(owner, params, null);
        counts[0]++;
        done[i] = true;
        restoredThisPass++;
        writeToLog("RESTORED: " + ownerPath + "/" + slotName);
        removeHoldingCopy(folder, holdingName);
      }
      catch (Throwable t)
      {
        counts[3]++;
        done[i] = true;
        writeToLog("REVERSE ERROR copying back " + ownerPath + "/" + slotName +
          ": " + describeException(t));
      }
    }

    // Stop when nothing new was restored this pass (remaining rows are
    // stuck on owners that will never appear).
    if (restoredThisPass == 0)
    {
      if (pendingOwner > 0)
      {
        // Flush the stragglers as owner-not-found errors.
        for (int i = 0; i < rows.size(); i++)
        {
          if (done[i]) continue;
          String[] r = (String[]) rows.get(i);
          counts[3]++;
          done[i] = true;
          writeToLog("REVERSE ERROR: owner not found after " + pass +
            " pass(es): " + r[0] + " (slot " + r[1] + ")");
        }
      }
      break;
    }
  }

  // Replay captured inbound/outbound links.
  int linksRestored = replayReverseLinks();

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "REVERSE complete - Restored:" + counts[0] +
    " Skipped:" + counts[2] + " Errors:" + counts[3] +
    " LinksRestored:" + linksRestored +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[ForceRemove] " + summary);
  writeToLog(summary);
}

// Replay the captured-links CSV (5-column BOrd1,Slot1,>,BOrd2,Slot2),
// recreating each link with the same BLink-by-handle construction
// LinkCreator uses. Skips a link that already exists. Returns the count
// created.
private int replayReverseLinks()
{
  int created = 0;
  java.io.File f = resolveToFile(resolveReverseLinksPath());
  if (f == null || !f.exists()) return 0;

  java.io.InputStream is = null;
  java.io.BufferedReader br = null;
  try
  {
    is = new java.io.FileInputStream(f);
    br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));

    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      line = line.trim();
      if (line.length() == 0) continue;

      String[] cols = parseCsvRow(line);
      if (cols.length < 5) continue;

      String srcOrdStr = cols[0];
      String srcSlot   = cols[1];
      String tgtOrdStr = cols[3];
      String tgtSlot   = cols[4];

      if (srcOrdStr.equals("?") || tgtOrdStr.equals("?") ||
          srcSlot.length() == 0 || tgtSlot.length() == 0)
        continue;

      try
      {
        Object so = javax.baja.naming.BOrd.make(
          normalizeOrd(srcOrdStr)).resolve().get();
        Object to = javax.baja.naming.BOrd.make(
          normalizeOrd(tgtOrdStr)).resolve().get();
        if (!(so instanceof javax.baja.sys.BComponent) ||
            !(to instanceof javax.baja.sys.BComponent))
          continue;

        javax.baja.sys.BComponent srcComp = (javax.baja.sys.BComponent) so;
        javax.baja.sys.BComponent tgtComp = (javax.baja.sys.BComponent) to;

        String linkName = "revLink_" + srcComp.getName() + "_" +
          srcSlot + "_to_" + tgtSlot + "_" +
          (Math.abs(srcComp.getSlotPath().toString().hashCode()) % 10000);

        if (tgtComp.getSlot(linkName) != null) continue; // already there

        String srcHandle = srcComp.getHandle().toString();
        javax.baja.naming.BOrd srcHandleOrd =
          javax.baja.naming.BOrd.make("h:" + srcHandle);
        javax.baja.sys.BLink newLink =
          new javax.baja.sys.BLink(srcHandleOrd, srcSlot, tgtSlot, true);
        tgtComp.add(linkName, newLink, null);
        created++;
        writeToLog("REVERSE LINK: " + srcComp.getName() + "[" + srcSlot +
          "] -> " + tgtComp.getName() + "[" + tgtSlot + "]");
      }
      catch (Throwable t)
      { writeToLog("REVERSE LINK ERROR row " + rowNum + ": " + describeException(t)); }
    }
  }
  catch (Exception e)
  { writeToLog("REVERSE LINKS READ ERROR: " + describeException(e)); }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (is != null) try { is.close(); } catch (Exception ignore) {}
  }
  return created;
}

// ----------------------------------------------------
// Robust child removal (v3.0)
// ----------------------------------------------------
// Alarm/history extensions (and some other dynamic children) are not
// reliably removable via parent.getProperty(child.getName()) -- that
// lookup returns null for them, which is why a name-based remove logged
// "No property on parent" for every AlarmSourceExt/etc. Instead we ask
// the CHILD for the slot it occupies in its parent, and remove that. We
// try, in order:
//   1) child.getPropertyInParent()      -> parent.remove(Property)
//   2) parent.getSlot(name) as Property -> parent.remove(Property)
//   3) parent.getProperty(name)         -> parent.remove(Property)
//   4) reflection: parent.remove(String) by name, then by slot name
//      (BComponent has remove(String)/remove(Property)/remove(BComplex)
//      but NO remove(Slot), so we never hand it a raw Slot)
// Returns a short tag describing what happened, never throws.
//   "OK:<how>"        removed, <how> names the path that worked
//   "SKIP:not-found"  the child is genuinely not a slot on this parent
//   "ERROR:<reason>"  an attempt was made but every path failed
private String removeChild(
  javax.baja.sys.BComponent parent, javax.baja.sys.BComponent child)
{
  // The BQL row IS the live mounted extension. The earlier failure was
  // self-inflicted: getParentComponent() on an alarm ext returns the
  // ext itself (not the owning point), so removing "off the parent"
  // targeted the wrong component and threw NoSuchSlotException. The ext
  // DOES know its own Property-in-parent, and that Property's parent is
  // the real owner (the point). So: get child.getPropertyInParent(),
  // then remove that Property from ITS OWN parent component. No
  // re-resolve, no parent guessing.
  String childName = "?";
  try { childName = child.getName(); } catch (Throwable ignore) {}

  // Primary path: the child's Property in its parent, removed from the
  // owning component. Property has no getParent(); derive the owner by
  // resolving the child's slot-path parent (one level up from the ext),
  // which is the point that actually holds the ext property.
  try
  {
    javax.baja.sys.Property p = child.getPropertyInParent();
    if (p != null)
    {
      javax.baja.sys.BComponent owner = null;

      try
      {
        String cp = child.getSlotPath().toString();      // .../airRqsts/ThisExt
        int slash = cp.lastIndexOf('/');
        if (slash > 0)
        {
          String ownerPath = cp.substring(0, slash);     // .../airRqsts
          Object ro = javax.baja.naming.BOrd.make(
            normalizeOrd(ownerPath)).resolve().get();
          if (ro instanceof javax.baja.sys.BComponent)
            owner = (javax.baja.sys.BComponent) ro;
        }
      }
      catch (Throwable ignore) {}

      // Fall back to the caller-supplied parent if the path derivation
      // failed (Direct-walk / ExtensionOnly already pass the true owner).
      if (owner == null) owner = parent;

      // Remove by the property's NAME off the owner, not by the Property
      // object itself: p belongs to the child's own parent context and a
      // re-resolved owner instance may reject a foreign Property handle.
      String pName = p.getName();
      javax.baja.sys.Property ownerProp = owner.getProperty(pName);
      if (ownerProp != null)
      {
        owner.remove(ownerProp);
        return "OK:propertyInParent";
      }
      javax.baja.sys.Slot os = owner.getSlot(pName);
      if (os != null && os.isProperty())
      {
        owner.remove((javax.baja.sys.Property) os);
        return "OK:propertyInParent(slot)";
      }
    }
  }
  catch (Throwable t)
  { writeToLog("  [rc] propertyInParent path failed: " + describeException(t)); }

  // Secondary: the caller-supplied parent (used for the Direct-walk and
  // ExtensionOnly cases where 'parent' is already the true owner). Try
  // by name off that parent.
  try
  {
    javax.baja.sys.Property prop = parent.getProperty(childName);
    if (prop != null)
    {
      parent.remove(prop);
      return "OK:propertyByName";
    }
  }
  catch (Throwable t)
  { writeToLog("  [rc] propertyByName failed: " + describeException(t)); }

  try
  {
    javax.baja.sys.Slot s = parent.getSlot(childName);
    if (s != null && s.isProperty())
    {
      parent.remove((javax.baja.sys.Property) s);
      return "OK:slotByName";
    }
  }
  catch (Throwable t)
  { writeToLog("  [rc] slotByName failed: " + describeException(t)); }

  // Reflection fallback: remove(String) by name off the supplied parent.
  try
  {
    if (parent.getSlot(childName) != null)
    {
      java.lang.reflect.Method m = parent.getClass().getMethod(
        "remove", new Class[]{ String.class });
      m.invoke(parent, new Object[]{ childName });
      return "OK:remove(name)";
    }
  }
  catch (Throwable t)
  { writeToLog("  [rc] reflect remove(String) failed: " + describeException(t)); }

  // Nothing matched a removable slot.
  try
  {
    if (parent.getSlot(childName) == null &&
        parent.getProperty(childName) == null)
      return "SKIP:not-found";
  }
  catch (Throwable ignore) {}

  return "ERROR:all remove strategies failed for slot '" + childName + "'";
}

// ----------------------------------------------------
// Link removal (ported from the pre-v3.0 ForceRemovePoints)
// ----------------------------------------------------
private void removeAllLinks(javax.baja.sys.BComponent c)
{
  try
  {
    javax.baja.sys.BLink[] inbound = c.getLinks();
    for (int i = 0; i < inbound.length; i++)
    {
      try
      {
        javax.baja.sys.BComponent linkParent =
          (javax.baja.sys.BComponent) inbound[i].getParent();
        javax.baja.sys.Property linkProp = inbound[i].getPropertyInParent();
        if (linkParent != null && linkProp != null)
          linkParent.remove(linkProp);
      }
      catch (Throwable ignore) {}
    }
  }
  catch (Throwable ignore) {}

  try
  {
    javax.baja.sys.SlotCursor cur = c.getSlots();
    while (cur.next())
    {
      javax.baja.sys.Slot s = cur.slot();
      if (!s.isProperty()) continue;
      Object v = c.get((javax.baja.sys.Property) s);
      if (v instanceof javax.baja.sys.BLink)
      {
        try { c.remove((javax.baja.sys.Property) s); } catch (Throwable ignore) {}
      }
    }
  }
  catch (Throwable ignore) {}
}

private String safeName(javax.baja.sys.BComponent c)
{
  try { return c.getSlotPath().toString(); }
  catch (Throwable t) { return "<unknown>"; }
}

// ----------------------------------------------------
// Protected targets (v3.0) - things this program will NEVER delete, no
// matter what mode, dry-run state, or filter matched them. Checked once
// per target in handleOneTarget(), before anything else.
// ----------------------------------------------------

// This program's own slot path, resolved once and cached (it can't
// change mid-run). 'this' is not statically a BComponent in a Program
// subclass (see getOrCreateBackupFolder()'s note), so getSlotPath() is
// called via reflection, same pattern as updateVersion()/
// updateQuickGuide().
private String myPathCache = null;
private boolean myPathResolved = false;

private String resolveMyPath()
{
  if (myPathResolved) return myPathCache;
  myPathResolved = true;
  try
  {
    java.lang.reflect.Method m = this.getClass().getMethod(
      "getSlotPath", new Class[]{});
    Object ord = m.invoke(this, new Object[]{});
    if (ord != null) myPathCache = ord.toString();
  }
  catch (Throwable ignore) {}
  return myPathCache;
}

// Non-null (with a reason) if targetPath must never be deleted:
//   - anywhere inside the station's Services tree (AlarmService,
//     HistoryService, etc. - core station plumbing, never a valid
//     removal target)
//   - this program itself, or any folder it lives inside. A folder
//     delete removes its WHOLE subtree, so an ancestor of this program
//     is exactly as dangerous as targeting the program directly - that
//     ancestor's subtree includes the running program, and deleting it
//     deletes the job that's still in the middle of running (this is
//     what "the program deleted itself mid-run" looks like from the
//     target's side).
// Deliberately does NOT protect siblings or unrelated descendants of
// those folders - only the exact folder objects on the path down to
// Services or to this program are off-limits.
private String protectedReason(String targetPath)
{
  if (targetPath == null) return null;

  if (targetPath.equals("/Services") || targetPath.startsWith("/Services/"))
    return "inside the station Services tree";

  String myPath = resolveMyPath();
  if (myPath != null &&
      (myPath.equals(targetPath) || myPath.startsWith(targetPath + "/")))
    return "this program, or a folder it lives inside " +
      "(deleting it would remove the running program)";

  return null;
}

// ----------------------------------------------------
// Core per-target handler
// Removes a single matched target (folder or component). Writes its own
// results-CSV row and log lines; updates counts in place.
// counts layout: [0]=Removed [1]=FoldersRemoved [2]=Skipped
//                [3]=Errors  [4]=DryRun
// ----------------------------------------------------
// Import-free folder test. The original tool relied on a bare
// "BFolder" resolved through a "baja | javax.baja.sys" import row, but
// the fully-qualified javax.baja.sys.BFolder class does not exist to
// the embedded compiler. Match on the type name instead: baja:Folder
// (and any subtype whose type name ends in "Folder"), which needs no
// import and covers palette folders, driver folders, point folders, etc.
private boolean isFolderComponent(javax.baja.sys.BComponent c)
{
  try
  {
    javax.baja.sys.Type t = c.getType();
    String moduleName = t.getModule().getModuleName();
    String typeName = t.getTypeName();
    if ("baja".equalsIgnoreCase(moduleName) && "Folder".equals(typeName))
      return true;
    if (typeName != null && typeName.endsWith("Folder"))
      return true;
    return false;
  }
  catch (Throwable t)
  {
    return false;
  }
}

// Is this component still mounted in the station? A BQL/CSV result set
// can contain both a folder AND items inside it; once the folder is
// deleted (whole subtree), those inner items no longer exist. Re-resolve
// the target's slot path - if it no longer resolves to a live component,
// it was already removed by an ancestor and we skip it silently.
private boolean stillMounted(javax.baja.sys.BComponent target)
{
  String p;
  try { p = target.getSlotPath().toString(); }
  catch (Throwable t) { return false; }   // no path -> detached/gone
  if (p == null || p.length() == 0) return false;
  try
  {
    Object o = javax.baja.naming.BOrd.make(normalizeOrd(p)).resolve().get();
    return (o instanceof javax.baja.sys.BComponent);
  }
  catch (Throwable t)
  {
    return false;   // cannot resolve -> already gone
  }
}

private void handleOneTarget(
  javax.baja.sys.BComponent target, String originMode, int[] counts)
{
  long t0 = System.nanoTime();
  String path = safeName(target);

  try
  {
    // Hard-protected targets: never removed, regardless of mode, dry
    // run, or filters. Checked first, before anything else below.
    String blocked = protectedReason(path);
    if (blocked != null)
    {
      counts[2]++;
      writeToLog("SKIP (PROTECTED - " + blocked + "): " + path);
      writeResultRow(target.getName(), path, null, null,
        "SKIPPED", "Protected target - " + blocked, originMode, "0.000");
      return;
    }

    // Nested-dedup guard: if an ancestor folder was already deleted this
    // run, this target no longer exists - skip quietly (not an error).
    if (!isDryRun() && !stillMounted(target))
    {
      writeToLog("SKIP (already removed by an ancestor this run): " + path);
      return;
    }

    boolean isFolder = isFolderComponent(target);

    // ---------------- FOLDER (removeFolders is always on) ----------------
    if (isFolder)
    {
      if (!matchesName(target)) return; // typeFilter intentionally ignored on folders

      // A folder CONTAINS its points as slots, and each point contains
      // its exts as slots - it is one nested tree. So we back up the
      // WHOLE folder in one Mark.copyTo (captures the entire subtree:
      // folder -> points -> exts) and delete it in one removeChild. No
      // per-child recursion is needed: deleting the container takes
      // everything beneath it with it, and reverse restores the whole
      // subtree in one copy-back.
      javax.baja.sys.BComponent parent = target.getParentComponent();
      if (parent == null)
      {
        counts[3]++;
        writeToLog("ERROR: folder has no parent component: " + path);
        writeResultRow(target.getName(), path, null, null,
          "ERROR", "No parent component", originMode, "0.000");
        return;
      }

      if (isDryRun())
      {
        int childCount = 0;
        try { childCount = target.getChildComponents().length; } catch (Throwable ignore) {}
        counts[4]++;
        writeToLog("[DRY] would delete folder (whole subtree, " + childCount +
          " direct child(ren)): " + path);
        writeResultRow(target.getName(), path, null, null,
          "DRYRUN", "Would delete folder + entire subtree", originMode, "0.000");
        return;
      }

      // Back up the whole folder subtree before delete. If backup fails,
      // do not delete.
      boolean folderBackedUp =
        backupComponent(target, parent, target.getName(), originMode);
      if (!folderBackedUp)
      {
        counts[2]++;
        writeToLog("SKIP folder (backup failed, not deleting): " + path);
        writeResultRow(target.getName(), path, null, null,
          "SKIPPED", "Backup failed - delete skipped to avoid data loss",
          originMode, "0.000");
        return;
      }

      try
      {
        String r = removeChild(parent, target);
        if (r.startsWith("OK:"))
        {
          String durStr = String.format(java.util.Locale.ROOT, "%.3f",
            (System.nanoTime() - t0) / 1000000.0);
          counts[1]++;
          writeToLog("DELETED folder (whole subtree): " + path + " (" +
            durStr + "ms) [" + r.substring(3) + "]");
          writeResultRow(target.getName(), path, null, null,
            "REMOVED", "Folder + subtree deleted", originMode, durStr);
        }
        else if (r.startsWith("SKIP:"))
        {
          counts[2]++;
          writeToLog("SKIP folder (" + r.substring(5) + "): " + path);
          writeResultRow(target.getName(), path, null, null,
            "SKIPPED", r.substring(5), originMode, "0.000");
        }
        else
        {
          counts[3]++;
          String reason = r.startsWith("ERROR:") ? r.substring(6) : r;
          writeToLog("ERROR deleting folder " + path + " : " + reason);
          writeResultRow(target.getName(), path, null, null,
            "ERROR", reason, originMode, "0.000");
        }
      }
      catch (Exception e)
      {
        counts[3]++;
        String reason = describeException(e);
        writeToLog("ERROR deleting folder " + path + " : " + reason);
        writeResultRow(target.getName(), path, null, null,
          "ERROR", reason, originMode, "0.000");
      }
      return;
    }

    // ---------------- NON-FOLDER: name/type filter only ----------------
    if (!matchesPointOrComponent(target))
    {
      counts[2]++;
      writeToLog("SKIP (name/type filter): " + path);
      writeResultRow(target.getName(), path, null, null,
        "SKIPPED", "Does not match nameFilter/typeFilter", originMode, "0.000");
      return;
    }

    removeComponent(target, path, originMode, counts, t0);
  }
  catch (Exception e)
  {
    counts[3]++;
    String reason = describeException(e);
    writeToLog("ERROR on " + path + " : " + reason);
    writeResultRow(target.getName(), path, null, null,
      "ERROR", reason, originMode, "0.000");
  }
}

// Delete the target component itself. Links are captured (backup) and
// then always removed. If the backup copy fails, the delete is SKIPPED
// so a component is never lost without a backup.
private void removeComponent(
  javax.baja.sys.BComponent target, String path, String originMode,
  int[] counts, long t0)
{
  if (isDryRun())
  {
    counts[4]++;
    writeToLog("[DRY] would delete component: " + path);
    writeResultRow(target.getName(), path, null, null,
      "DRYRUN", "Would delete component", originMode, "0.000");
    return;
  }

  javax.baja.sys.BComponent parent = target.getParentComponent();
  if (parent == null)
  {
    counts[3]++;
    writeToLog("ERROR: target has no parent component: " + path);
    writeResultRow(target.getName(), path, null, null,
      "ERROR", "No parent component", originMode, "0.000");
    return;
  }

  // Back up (copy + capture links) BEFORE removing anything. If the
  // backup fails, do not delete.
  boolean backedUp = backupComponent(target, parent, target.getName(), originMode);
  if (!backedUp)
  {
    counts[2]++;
    writeToLog("SKIP (backup failed, not deleting): " + path);
    writeResultRow(target.getName(), path, null, null,
      "SKIPPED", "Backup failed - delete skipped to avoid data loss",
      originMode, "0.000");
    return;
  }

  // removeLinks is always on now (links are already captured above).
  removeAllLinks(target);

  try
  {
    javax.baja.sys.Property enabled = target.getProperty("enabled");
    if (enabled != null)
      target.set(enabled, javax.baja.sys.BBoolean.FALSE, null);
  }
  catch (Throwable ignore) {}

  try
  {
    String r = removeChild(parent, target);
    if (r.startsWith("OK:"))
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      counts[0]++;
      writeToLog("DELETED: " + path + " (" + durStr + "ms) [" +
        r.substring(3) + "]");
      writeResultRow(target.getName(), path, null, null,
        "REMOVED", "", originMode, durStr);
    }
    else if (r.startsWith("SKIP:"))
    {
      counts[2]++;
      writeToLog("SKIP (" + r.substring(5) + "): " + path);
      writeResultRow(target.getName(), path, null, null,
        "SKIPPED", r.substring(5), originMode, "0.000");
    }
    else
    {
      counts[3]++;
      String reason = r.startsWith("ERROR:") ? r.substring(6) : r;
      writeToLog("ERROR deleting " + path + " : " + reason);
      writeResultRow(target.getName(), path, null, null,
        "ERROR", reason, originMode, "0.000");
    }
  }
  catch (Exception e)
  {
    counts[3]++;
    String reason = describeException(e);
    writeToLog("ERROR deleting " + path + " : " + reason);
    writeResultRow(target.getName(), path, null, null,
      "ERROR", reason, originMode, "0.000");
  }
}

// ----------------------------------------------------
// MODE: Direct - recursive walk from targetOrd
// ----------------------------------------------------
private void processChildrenDirect(javax.baja.sys.BComponent parent, int[] counts)
{
  javax.baja.sys.BComponent[] children;
  try { children = parent.getChildComponents(); }
  catch (Exception e)
  {
    counts[3]++;
    writeToLog("ERROR listing children of " + safeName(parent) +
      " : " + describeException(e));
    return;
  }

  for (int i = 0; i < children.length; i++)
  {
    if (isCancelled())
    {
      writeToLog("Direct walk CANCELLED under " + safeName(parent));
      return;
    }

    javax.baja.sys.BComponent child = children[i];
    try
    {
      boolean isFolder = isFolderComponent(child);

      if (isFolder)
      {
        // recurse + removeFolders are always on now.
        processChildrenDirect(child, counts);
        handleOneTarget(child, "Direct", counts);
      }
      else
      {
        // Pre-filter silently here so a full walk doesn't spam a SKIPPED
        // row for every non-matching property. handleOneTarget re-checks
        // the same filters.
        if (matchesName(child) && matchesType(child))
          handleOneTarget(child, "Direct", counts);
      }
    }
    catch (Exception e)
    {
      counts[3]++;
      writeToLog("ERROR on " + safeName(child) + " : " + describeException(e));
    }
  }
}

// Read the targetList multi-line String slot into a list of ord strings
// (one per line; blank lines and #-comments ignored). Returns empty if
// the slot is missing/blank.
private java.util.List readTargetListLines()
{
  java.util.List out = new java.util.ArrayList();
  String raw = null;
  try
  {
    Object v = get("targetList");
    if (v instanceof javax.baja.sys.BString)
      raw = ((javax.baja.sys.BString) v).getString();
    else if (v != null)
      raw = v.toString();
  }
  catch (Throwable ignore) {}

  if (raw == null) return out;
  String[] lines = raw.split("\\r?\\n");
  for (int i = 0; i < lines.length; i++)
  {
    String s = lines[i].trim();
    if (s.length() == 0 || s.startsWith("#")) continue;
    out.add(s);
  }
  return out;
}

private void executeDirect(long runStart) throws Exception
{
  writeToLog("Target mode: DIRECT" + (isDryRun() ? " [DRY RUN]" : ""));
  log.info("[ForceRemove] Target mode: DIRECT");

  // Build the target list: prefer the multi-line targetList slot; if it
  // is empty, fall back to the single targetOrd (back-compat).
  java.util.List targetStrs = readTargetListLines();
  boolean fromList = targetStrs.size() > 0;
  if (!fromList)
  {
    javax.baja.naming.BOrd rootOrd = getTargetOrd();
    if (rootOrd == null || rootOrd.isNull())
    {
      String msg = "[" + now() +
        "] ERROR: no targets - set targetList (one ord per line) or targetOrd";
      setStatus(msg); log.warning("[ForceRemove] " + msg); writeToLog(msg);
      return;
    }
    targetStrs.add(rootOrd.toString());
  }

  writeToLog("Direct targets: " + targetStrs.size() +
    (fromList ? " (from targetList)" : " (from targetOrd)") +
    "  nameFilter='" + getNameFilter() + "' typeFilter='" + getTypeFilter() +
    "' (recurse + removeFolders always on; each target deleted itself last)");

  int[] counts = new int[5];

  for (int t = 0; t < targetStrs.size(); t++)
  {
    if (isCancelled())
    {
      writeToLog("Direct run CANCELLED before target " + (t + 1) +
        " of " + targetStrs.size());
      break;
    }

    String ordStr = (String) targetStrs.get(t);
    setStatus("[" + now() + "] Direct: target " + (t + 1) + " of " +
      targetStrs.size() + " (" + ordStr + ")...");

    Object resolved;
    try { resolved = javax.baja.naming.BOrd.make(normalizeOrd(ordStr)).resolve().get(); }
    catch (Throwable e)
    {
      counts[3]++;
      writeToLog("ERROR: target cannot resolve: " + ordStr + " - " + describeException(e));
      writeResultRow("(target)", ordStr, null, null,
        "ERROR", "Cannot resolve: " + describeException(e), "Direct", "0.000");
      continue;
    }

    if (!(resolved instanceof javax.baja.sys.BComponent))
    {
      counts[3]++;
      writeToLog("ERROR: target is not a BComponent: " + ordStr);
      writeResultRow("(target)", ordStr, null, null,
        "ERROR", "Not a BComponent", "Direct", "0.000");
      continue;
    }

    javax.baja.sys.BComponent root = (javax.baja.sys.BComponent) resolved;
    writeToLog("Direct target root: " + root.getSlotPath());
    processChildrenDirect(root, counts);

    // Then delete the target itself: a single point is removed; a folder
    // is emptied by the walk above then removed here. Guard against a
    // parentless station root.
    try
    {
      javax.baja.sys.BComponent rootParent = root.getParentComponent();
      if (rootParent != null)
        handleOneTarget(root, "Direct", counts);
      else
        writeToLog("Target has no parent component - not deleting the root itself: "
          + root.getSlotPath());
    }
    catch (Exception e)
    {
      counts[3]++;
      writeToLog("ERROR deleting target itself: " + describeException(e));
    }
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "Direct complete - Removed:" + counts[0] +
    " FoldersRemoved:" + counts[1] + " Skipped:" + counts[2] +
    " Errors:" + counts[3] + " DryRun:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[ForceRemove] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts, totalMs);
}

// ----------------------------------------------------
// MODE: BQL - every row returned by targetOrd's BQL query
// ----------------------------------------------------
private void executeBQL(long runStart) throws Exception
{
  writeToLog("Target mode: BQL" + (isDryRun() ? " [DRY RUN]" : ""));
  log.info("[ForceRemove] Target mode: BQL");

  javax.baja.naming.BOrd tgtOrd = getTargetOrd();
  if (tgtOrd == null || tgtOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd (BQL) not set";
    setStatus(msg); writeToLog(msg); return;
  }

  Object bqlResult = tgtOrd.resolve().get();
  writeToLog("BQL result type: " + bqlResult.getClass().getName());

  java.util.List targets = new java.util.ArrayList();
  int[] counts = new int[5];

  try
  {
    java.lang.reflect.Method cursorMethod =
      bqlResult.getClass().getMethod("cursor");
    Object cursor = cursorMethod.invoke(bqlResult);

    java.lang.reflect.Method nextMethod = cursor.getClass().getMethod("next");
    java.lang.reflect.Method getMethod  = cursor.getClass().getMethod("get");
    java.lang.reflect.Method closeMethod = cursor.getClass().getMethod("close");

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
        try { targets.add(getMethod.invoke(cursor)); }
        catch (Exception e)
        {
          counts[3]++;
          writeToLog("BQL ERROR reading row " + rowNum + ": " + describeException(e));
        }
      }
    }
    finally { closeMethod.invoke(cursor); }
  }
  catch (Exception e)
  {
    String msg = "BQL CURSOR ERROR: " + e.getMessage();
    writeToLog(msg);
    setStatus("[" + now() + "] " + msg);
    return;
  }

  writeToLog("BQL targets captured: " + targets.size());

  // Sort shallowest-first so a folder is deleted (whole subtree) BEFORE
  // any of its own contents that the query also returned; the nested-
  // dedup guard in handleOneTarget then skips the now-gone children.
  sortTargetsShallowestFirst(targets);

  for (int i = 0; i < targets.size(); i++)
  {
    if (isCancelled())
    {
      writeToLog("BQL RUN CANCELLED before target " + (i + 1) +
        " of " + targets.size());
      break;
    }

    Object o = targets.get(i);
    setStatus("[" + now() + "] BQL: target " + (i + 1) + " of " + targets.size() + "...");

    if (!(o instanceof javax.baja.sys.BComponent))
    {
      counts[3]++;
      writeToLog("BQL ERROR: row " + (i + 1) + " is not a BComponent (" +
        (o == null ? "null" : o.getClass().getName()) + ")");
      writeResultRow("(row " + (i + 1) + ")", "", null, null,
        "ERROR", "BQL row is not a BComponent", "BQL", "0.000");
      continue;
    }

    handleOneTarget((javax.baja.sys.BComponent) o, "BQL", counts);
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String tail = isCancelled() ? " [CANCELLED]" : "";
  String summary = "BQL complete - Removed:" + counts[0] +
    " FoldersRemoved:" + counts[1] + " Skipped:" + counts[2] +
    " Errors:" + counts[3] + " DryRun:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[ForceRemove] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts, totalMs);
}

// ----------------------------------------------------
// MODE: CSV - targetOrd points to a 1-column CSV of ords
// ----------------------------------------------------
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
    br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      line = line.trim();
      if (line.length() == 0 || line.startsWith("#")) continue;
      count++;
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

private java.util.List validateCsvTargets(javax.baja.naming.BOrd csvOrd)
{
  java.util.List issues = new java.util.ArrayList();
  java.io.InputStream is = null;
  java.io.BufferedReader br = null;
  try
  {
    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    is = csvFile.getInputStream();
    br = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"));
    String line;
    int rowNum = 0;

    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      line = line.trim();
      if (line.length() == 0 || line.startsWith("#")) continue;

      String[] cols = parseCsvRow(line);
      if (cols.length < 1 || cols[0].length() == 0)
      {
        issues.add(new CsvIssue(rowNum, "", "Empty Ord column"));
        continue;
      }

      try
      {
        javax.baja.naming.BOrd.make(normalizeOrd(cols[0])).resolve().get();
      }
      catch (Exception e)
      {
        issues.add(new CsvIssue(rowNum, cols[0],
          "Ord does not exist / cannot resolve: " + e.getMessage()));
      }
    }
  }
  catch (Exception e)
  {
    issues.add(new CsvIssue(0, "", "CSV read error: " + e.getMessage()));
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (is != null) try { is.close(); } catch (Exception ignore) {}
  }
  return issues;
}

private void executeCSV(long runStart) throws Exception
{
  writeToLog("Target mode: CSV" + (isDryRun() ? " [DRY RUN]" : ""));
  log.info("[ForceRemove] Target mode: CSV");

  javax.baja.naming.BOrd csvOrd = getTargetOrd();
  if (csvOrd == null || csvOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: targetOrd (CSV file) not set";
    setStatus(msg); log.warning("[ForceRemove] " + msg); writeToLog(msg);
    return;
  }

  writeToLog("Using CSV: " + csvOrd.toString());

  setStatus("[" + now() + "] CSV: validating...");
  java.util.List issues = validateCsvTargets(csvOrd);
  if (issues.size() > 0)
  {
    writeToLog("CSV VALIDATION ISSUES (" + issues.size() +
      ") - see ERROR rows in results CSV for details:");
    for (int i = 0; i < issues.size(); i++)
    {
      CsvIssue issue = (CsvIssue) issues.get(i);
      writeToLog("  Row " + issue.rowNum + ": " + issue.reason);
    }
    writeToLog("Proceeding with valid rows...");
  }
  else
  {
    writeToLog("CSV validation passed - no errors found");
  }

  int totalRows = countCsvRows(csvOrd);
  writeToLog("CSV total data rows: " + totalRows);

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
      if (line.length() == 0 || line.startsWith("#")) continue;

      if (isCancelled())
      {
        writeToLog("CSV RUN CANCELLED before row " + rowNum);
        break;
      }

      dataRow++;
      setStatus("[" + now() + "] CSV: processing row " + dataRow +
        " of " + totalRows + "...");

      String[] cols = parseCsvRow(line);
      if (cols.length < 1 || cols[0].length() == 0)
      {
        counts[3]++;
        writeToLog("ERROR row " + rowNum + ": empty Ord column");
        writeResultRow("(row " + rowNum + ")", "", null, null,
          "ERROR", "Empty Ord column", "CSV", "0.000");
        continue;
      }

      String ordStr = cols[0];
      try
      {
        Object o = javax.baja.naming.BOrd.make(normalizeOrd(ordStr)).resolve().get();
        if (!(o instanceof javax.baja.sys.BComponent))
        {
          counts[3]++;
          String reason = "Ord resolved to a non-component (" +
            (o == null ? "null" : o.getClass().getSimpleName()) + "): " + ordStr;
          writeToLog("ERROR row " + rowNum + ": " + reason);
          writeResultRow("(row " + rowNum + ")", ordStr, null, null,
            "ERROR", reason, "CSV", "0.000");
          continue;
        }
        handleOneTarget((javax.baja.sys.BComponent) o, "CSV", counts);
      }
      catch (Exception e)
      {
        counts[3]++;
        String reason = "Ord cannot resolve: " + ordStr + " -- " + describeException(e);
        writeToLog("ERROR row " + rowNum + ": " + reason);
        writeResultRow("(row " + rowNum + ")", ordStr, null, null,
          "ERROR", reason, "CSV", "0.000");
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
  String summary = "CSV complete - Removed:" + counts[0] +
    " FoldersRemoved:" + counts[1] + " Skipped:" + counts[2] +
    " Errors:" + counts[3] + " DryRun:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
  setStatus("[" + now() + "] " + summary);
  log.info("[ForceRemove] " + summary);
  writeToLog(summary);
  writeResultsSummary(counts, totalMs);
}

// ----------------------------------------------------
// Lifecycle / Actions
// ----------------------------------------------------
public void onStart() throws Exception
{
  updateVersion();
  updateQuickGuide();
  setStatus("[" + now() + "] Ready");
  log.info("[ForceRemove] " + VERSION + " Service started - Ready");
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

// Restore the components removed by the most recent execute. Reads the
// reverse manifest, copies each backup from the holding folder back to
// its original owner, and replays captured links. Never backs up or
// deletes. See runReverse() and the reverse limits in the header.
public void onReverse() throws Exception
{
  dryRunActive = false;
  cancelRequested = false;

  long runStart = System.nanoTime();
  archiveLogFile();
  writeToLog(VERSION + " onReverse triggered");

  try
  {
    runReverse(runStart);
  }
  catch (Exception e)
  {
    String detail = "REVERSE ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ForceRemove] " + detail);
    writeToLog(detail);
  }
}

private void runJob() throws Exception
{
  long runStart = System.nanoTime();

  archiveLogFile();
  initResultsCsv();

  log.info("[ForceRemove] " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered");
  writeToLog(VERSION + " " + (isDryRun() ? "onDryRun" : "onExecute") + " triggered" +
    (isDryRun() ? " [DRY RUN]" : ""));

  int mode = getModeOrdinal();
  writeToLog("Operation mode: " + mode + " (raw: " + getOperationMode().toString() + ")");

  // Prepare the reverse snapshot folder + manifest (no-op on dryRun).
  // Prepare the reverse backup folder + manifest (no-op on dryRun).
  initBackups();

  try
  {
    if (mode == 0)      executeDirect(runStart);
    else if (mode == 1) executeBQL(runStart);
    else if (mode == 2) executeCSV(runStart);
    else
    {
      String msg = "[" + now() + "] ERROR: unknown operationMode " + mode;
      setStatus(msg); log.warning("[ForceRemove] " + msg); writeToLog(msg);
    }
  }
  catch (Exception e)
  {
    String detail = "ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ForceRemove] EXCEPTION - " + e.getMessage());
    writeToLog("EXCEPTION - " + e.getMessage());
  }
}

// Set the cancel flag. The currently running Direct/BQL/CSV loop will
// notice between items and exit cleanly; the in-flight item finishes
// first.
public void onCancel() throws Exception
{
  cancelRequested = true;
  String msg = "Cancel requested - run will stop after current item";
  setStatus("[" + now() + "] " + msg);
  log.info("[ForceRemove] " + msg);
  writeToLog(msg);
}

public void onPruneArchives() throws Exception
{
  String startMsg = "Manual prune requested (maxArchives=" + resolveMaxArchives() + ")";
  setStatus("[" + now() + "] " + startMsg);
  log.info("[ForceRemove] " + startMsg);
  writeToLog(startMsg);

  pruneArchives(resolveLogPath());
  pruneArchives(resolveResultsCsvPath());

  String done = "Prune complete (kept up to " + resolveMaxArchives() + " of each)";
  setStatus("[" + now() + "] " + done);
  log.info("[ForceRemove] " + done);
  writeToLog(done);
}

public void onCreateSampleCsv() throws Exception
{
  dryRunActive = false;
  cancelRequested = false;

  setStatus("[" + now() + "] Writing sample CSV file...");
  log.info("[ForceRemove] onCreateSampleCsv triggered");
  writeToLog(VERSION + " onCreateSampleCsv triggered");

  try
  {
    writeSampleCsv();
    String msg = "Sample CSV written. Edit it in place, then point the " +
      "program at it (operationMode=CSV) and execute.";
    setStatus("[" + now() + "] " + msg);
    writeToLog(msg);
    log.info("[ForceRemove] " + msg);
  }
  catch (Exception e)
  {
    String detail = "SAMPLE CSV ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ForceRemove] " + detail);
    writeToLog(detail);
  }
}

public void onStop() throws Exception
{
  log.info("[ForceRemove] Service stopped");
  writeToLog("Service stopped");
}