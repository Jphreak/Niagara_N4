/*
================================================================================
COMMON N4 - standard helper block for Niagara N4 Program Objects
Version: c1  (2026-10-31)
================================================================================
One copy of the code every Program Object uses for the same job. Paste the
sections a program needs; never write a second version of any of these.
Change a helper HERE, then run sync_common.py so every program gets it.

Tiers - which sections a program carries
----------------------------------------
  Core    every program        now, describeException, updateVersion,
                               updateQuickGuide, ordSlot, intSlot
  Files   tools that write     resolveToFile, sandboxToStationHome,
          files (action-       resolveOutputFile, writeTextFile, pruneRunFiles,
                               appendLine, clearFile,
          driven)              archive / prune (pruneArchiveFiles),
                               resolveLogPath, writeToLog, logInfo/Warn/Error,
                               reportInfo/Warn/Error (status + both logs)
  CSV     tools that read or   csvEscape, looksNumeric, parseCsvRow
          write CSV
  Reverse tools that change    reverseRecordPath, beginReverseRecord,
          the station          recordReverse, readReverseRecord,
                               consumeReverseRecord
  Ord     tools that take      normalizeOrd, escapeSlotNames (+ helpers),
          ords from the user   compAt
  Links   tools that link or   undoReverseRow, preLinkCheck, missingSlot,
          copy components      linkHealth,
                               removeDeadLink, createLink, buildLinkName,
                               flagConstant, slotFlags, callNoArg, invoke0,
                               strOf

  Logic blocks that run every engine cycle (ZoneTempDelta, ApiClient...)
  carry Core only: no file writes per cycle.

What the program itself must define
-----------------------------------
  private static final String LOG_TAG = "<Tool>";   log tag + default file names
  private static final String VERSION = "vX.YY";
  private static final String QUICK_GUIDE = "...";
  private static final java.util.logging.Logger log =
    java.util.logging.Logger.getLogger("<Tool>");    (Files tier)
  slots: version, quickGuide (baja:String, Read Only), status (baja:String)
         Files tier: logFilePath (baja:Ord), maxArchives (baja:Integer, 10)

Conventions
-----------
  status       "[yyyy-MM-dd HH:mm:ss] message"   (setStatus("[" + now() + "] " + ...))
  log file     "[yyyy-MM-dd HH:mm:ss] message"   via writeToLog / logInfo...
  App Director "[<LOG_TAG>] message"             via logInfo / logWarn / logError
  files        always through resolveToFile: station-home only, refusals say why
               (status + log, and lastPathError for the caller)
  archives     <name>_yyyy-MM-dd_HH-mm-ss.<ext>, pruned to maxArchives
               (0 = keep all, default 10)
  CSV          csvEscape on every field (quotes + spreadsheet formula guard)
  dry run      an action (dryRun), never a Boolean slot
  reverse      from a record of what was actually changed, written by the
               live run, consumed (renamed _reversed_<ts>) by reverse
================================================================================
*/

// =============================================================== FIELDS =====
// (the sync tool adds these if missing)

// Reason the last resolveToFile() refused a path (null = it didn't refuse).
private String lastPathError = null;

// True once this run has started its reverse record (see recordReverse).
private boolean reverseRecordOpen = false;

// ================================================================= CORE =====

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

// ================================================================ FILES =====

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
private void pruneRunFiles(java.io.File written)
{
  if (written == null) return;
  String n = written.getName();
  java.util.regex.Matcher m = java.util.regex.Pattern
    .compile("^(.+)_\\d{4}-\\d\\d-\\d\\d_\\d\\d-\\d\\d-\\d\\d(\\.[^.]*)?$").matcher(n);
  if (!m.matches()) return;
  pruneArchiveFiles(written.getParentFile(), m.group(1),
                    m.group(2) == null ? "" : m.group(2));
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

// ================================================================== CSV =====

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

// ============================================================== REVERSE =====
// The live run records what it actually changed; reverse undoes exactly
// that and consumes the record. One record per program instance, holding
// the most recent live run that changed something.
//   logs/<LOG_TAG>_<programName>_reverse.csv

private String reverseRecordPath()
{
  String nm = "program";
  try
  {
    javax.baja.sys.BComponent me = (javax.baja.sys.BComponent) getComponent();
    if (me != null && me.getName() != null) nm = me.getName();
  }
  catch (Throwable ignore) {}
  nm = nm.replaceAll("[^A-Za-z0-9_$\\-]", "_");
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

// ================================================================== ORD =====
// Slot-name escaping: names can be typed as Workbench shows them
// ("VAV X-01" -> "VAV$20X$2d01"); only the slot: part of an ord is touched.

// ----------------------------------------------------
// Helpers
// ----------------------------------------------------
private String normalizeOrd(String ordStr)
{
  if (ordStr == null) return ordStr;
  ordStr = escapeSlotNames(ordStr.trim());
  if (ordStr.equals("/") || ordStr.equals("slot:/")) return "station:|slot:/";
  if (ordStr.startsWith("slot:/") && !ordStr.startsWith("station:|"))
    return "station:|" + ordStr;
  return ordStr;
}

// Escape every slot name in the slot: part(s) of an ord. Other ord
// parts (station:, bql:, file:, h:, ...) are returned unchanged.
private String escapeSlotNames(String ord)
{
  if (ord == null || ord.indexOf("slot:") < 0) return ord;
  StringBuffer out = new StringBuffer();
  int start = 0;
  int n = ord.length();
  for (int i = 0; i <= n; i++)
  {
    if (i == n || ord.charAt(i) == '|')
    {
      String part = ord.substring(start, i);
      if (part.startsWith("slot:"))
        out.append("slot:").append(escapeSlotPath(part.substring(5)));
      else
        out.append(part);
      if (i < n) out.append('|');
      start = i + 1;
    }
  }
  return out.toString();
}

// Escape each '/'-separated name in a slot path, keeping the slashes.
private String escapeSlotPath(String path)
{
  StringBuffer out = new StringBuffer();
  int start = 0;
  int n = path.length();
  for (int i = 0; i <= n; i++)
  {
    if (i == n || path.charAt(i) == '/')
    {
      out.append(escapeSlotName(path.substring(start, i)));
      if (i < n) out.append('/');
      start = i + 1;
    }
  }
  return out.toString();
}

// Escape one slot name. Letters always pass; digits and '_' pass except
// as the first character; a valid existing escape ($xx / $uxxxx) is
// copied through; everything else becomes $xx (or $uxxxx above 0xff).
private String escapeSlotName(String name)
{
  if (name.length() == 0) return name;
  StringBuffer sb = new StringBuffer();
  int n = name.length();
  int i = 0;
  while (i < n)
  {
    char c = name.charAt(i);
    boolean first = (i == 0);

    if (c == '$')
    {
      int len = escapeLengthAt(name, i);
      if (len > 0)
      {
        sb.append(name.substring(i, i + len));
        i += len;
        continue;
      }
    }

    boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    boolean digit  = (c >= '0' && c <= '9');
    if (letter || (!first && (digit || c == '_')))
      sb.append(c);
    else if (c <= 0xff)
      sb.append('$').append(hex(c, 2));
    else
      sb.append("$u").append(hex(c, 4));
    i++;
  }
  return sb.toString();
}

// Length of a valid escape starting at name[i] ('$'): 6 for $uxxxx,
// 3 for $xx, 0 if what follows isn't a valid escape.
private int escapeLengthAt(String name, int i)
{
  int n = name.length();
  if (i + 5 < n && name.charAt(i + 1) == 'u' &&
      isHex(name.charAt(i + 2)) && isHex(name.charAt(i + 3)) &&
      isHex(name.charAt(i + 4)) && isHex(name.charAt(i + 5)))
    return 6;
  if (i + 2 < n && isHex(name.charAt(i + 1)) && isHex(name.charAt(i + 2)))
    return 3;
  return 0;
}

private boolean isHex(char c)
{
  return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
         (c >= 'A' && c <= 'F');
}

// Lowercase hex of c, zero-padded to width digits.
private String hex(char c, int width)
{
  String h = Integer.toHexString(c);
  StringBuffer sb = new StringBuffer();
  for (int i = h.length(); i < width; i++) sb.append('0');
  return sb.append(h).toString();
}

// Slot path or ord -> the component there (null if it doesn't resolve to
// a component). Accepts "/a/b", "slot:/a/b", "station:|slot:/a/b"; names
// are escaped as normalizeOrd() does.
private javax.baja.sys.BComponent compAt(String path)
{
  if (path == null) return null;
  if (path.startsWith("/")) path = "slot:" + path;
  try
  {
    Object o = javax.baja.naming.BOrd.make(normalizeOrd(path)).resolve().get();
    if (o instanceof javax.baja.sys.BComponent) return (javax.baja.sys.BComponent) o;
  }
  catch (Throwable ignore) {}
  return null;
}

// ================================================================ LINKS =====

// Undoes one record row. Returns REMOVED, or SKIPPED (why) / FAILED (why):
// an item that was changed or replaced since the run is skipped, never
// removed. Row layouts (paths are slot paths):
//   LINK, <target>, <link name>, <source>, <source slot>, <target slot>
//   COMP, <parent>, <child name>, <copied from>, <type>
private String undoReverseRow(String[] r)
{
  if (r == null || r.length < 3) return "SKIPPED (bad record row)";
  String kind = r[0];
  String name = r[2];
  javax.baja.sys.BComponent owner = compAt(r[1]);
  if (owner == null) return "SKIPPED (" + r[1] + " not found)";
  Object v = null;
  try { v = owner.get(name); } catch (Throwable ignore) {}

  if ("LINK".equals(kind))
  {
    if (!(v instanceof javax.baja.sys.BLink))
      return "SKIPPED (link " + name + " not found)";
    String ss = strOf(callNoArg(v, "getSourceSlotName"));
    String ts = strOf(callNoArg(v, "getTargetSlotName"));
    if (r.length >= 6 && (!ss.equals(r[4]) || !ts.equals(r[5])))
      return "SKIPPED (link " + name + " changed since the run)";
  }
  else if ("COMP".equals(kind))
  {
    if (!(v instanceof javax.baja.sys.BComponent))
      return "SKIPPED (" + name + " not found)";
    String type = String.valueOf(((javax.baja.sys.BComponent) v).getType());
    if (r.length >= 5 && r[4].length() > 0 && !type.equals(r[4]))
      return "SKIPPED (" + name + " was replaced since the run: " + type + ")";
  }
  else
  {
    return "SKIPPED (unknown record row " + kind + ")";
  }

  try
  {
    owner.remove(name);
  }
  catch (Throwable t)
  {
    return "FAILED (" + describeException(t) + ")";
  }
  return "REMOVED";
}

// Both slots must exist before a link is added: add() accepts a misspelled
// slot and leaves a dead link. Null = OK, else the reason.
private String preLinkCheck(
  javax.baja.sys.BComponent src, String srcSlot,
  javax.baja.sys.BComponent tgt, String tgtSlot)
{
  String r = missingSlot(src, srcSlot, "Source");
  if (r != null) return r;
  return missingSlot(tgt, tgtSlot, "Target");
}

private String missingSlot(
  javax.baja.sys.BComponent comp, String slotName, String role)
{
  String compName = "?";
  try { compName = comp.getName(); } catch (Throwable ignore) {}
  try
  {
    if (comp.getSlot(slotName) != null) return null;
  }
  catch (Throwable t)
  {
    return role + " slot '" + slotName + "' on " + compName +
      " could not be checked: " + describeException(t);
  }
  return role + " slot '" + slotName + "' not found on " + compName;
}

// Reads a link back after add(). Null = working, else why it isn't.
private String linkHealth(javax.baja.sys.BComponent tgt, String linkName)
{
  javax.baja.sys.Slot s = null;
  try { s = tgt.getSlot(linkName); } catch (Throwable ignore) {}
  if (s == null)
    return "the framework did not keep the link (slot type refused or " +
      "source did not resolve)";

  Object v = null;
  try { v = tgt.get(linkName); } catch (Throwable ignore) {}
  if (!(v instanceof javax.baja.sys.BLink))
    return "slot '" + linkName + "' is not a link";

  int fault = flagConstant("FAULT");
  if (fault != 0 && (slotFlags(tgt, s) & fault) != 0)
    return "the link is fault-flagged (e.g. the station's licensed link " +
      "count is reached)";

  Object active = callNoArg(v, "isActive");
  if (active instanceof Boolean && !((Boolean) active).booleanValue())
    return "the link is not active (source ord or slot did not resolve)";

  return null;
}

private void removeDeadLink(javax.baja.sys.BComponent tgt, String linkName)
{
  try { if (tgt.getSlot(linkName) != null) tgt.remove(linkName); }
  catch (Throwable ignore) {}
}

// Adds src.srcSlot -> tgt.tgtSlot as link slot linkName on tgt (source by
// handle ord), then reads it back. Null = linked; else the reason, and the
// dead link has been removed. Run preLinkCheck first.
private String createLink(
  javax.baja.sys.BComponent srcComp, String srcSlot,
  javax.baja.sys.BComponent tgtComp, String tgtSlot, String linkName)
{
  javax.baja.naming.BOrd srcHandleOrd =
    javax.baja.naming.BOrd.make("h:" + srcComp.getHandle().toString());
  javax.baja.sys.BLink newLink =
    new javax.baja.sys.BLink(srcHandleOrd, srcSlot, tgtSlot, true);
  tgtComp.add(linkName, newLink, null);
  String health = linkHealth(tgtComp, linkName);
  if (health != null) removeDeadLink(tgtComp, linkName);
  return health;
}

// The one link-name formula every tool uses, so a link made by one tool is
// recognized by the others: link_<source>_<srcSlot>_to_<tgtSlot>_<4-digit
// hash of the source's slot path>.
private String buildLinkName(
  javax.baja.sys.BComponent srcComp, String srcSlotStr, String tgtSlotStr)
{
  String srcPath = srcComp.getSlotPath().toString();
  int pathHash = Math.abs(srcPath.hashCode()) % 10000;
  return "link_" + srcComp.getName() + "_" + srcSlotStr +
    "_to_" + tgtSlotStr + "_" + pathHash;
}

private int flagConstant(String name)
{
  try
  {
    Class fc = Class.forName("javax.baja.sys.Flags");
    return fc.getField(name).getInt(null);
  }
  catch (Throwable t) { return 0; }
}

private int slotFlags(javax.baja.sys.BComponent comp, javax.baja.sys.Slot s)
{
  try
  {
    java.lang.reflect.Method m = comp.getClass().getMethod(
      "getFlags", new Class[]{ javax.baja.sys.Slot.class });
    Object r = m.invoke(comp, new Object[]{ s });
    if (r instanceof Integer) return ((Integer) r).intValue();
  }
  catch (Throwable ignore) {}
  return 0;
}

private Object callNoArg(Object target, String method)
{
  try
  {
    java.lang.reflect.Method m =
      target.getClass().getMethod(method, new Class[0]);
    return m.invoke(target, new Object[0]);
  }
  catch (Throwable t) { return null; }
}

private Object invoke0(Object target, String method)
{
  return callNoArg(target, method);
}

private String strOf(Object o)
{
  if (o == null) return "";
  try { return o.toString(); } catch (Throwable t) { return ""; }
}
