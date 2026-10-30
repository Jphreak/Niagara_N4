/*
================================================================================
Program: MonthlyVavHealth - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-30

Changes
-------
  v1.00  Initial release.

Purpose
-------
Reads the ZoneTempDeltaT sample CSVs your BQL export saves 3 times a day,
and once a month writes one report CSV per building. Emailing is done by
a Mailer program (see Emailing below).

For every VAV in the month's sample files, from the In Range column
(ZoneTempDelta's inRange, which already applies that VAV's band):
  Pass  = In Range   {ok}        (value "In Range" or "true")
  Fail  = Out of Range {ok}      (value "Out of Range" or "false")
  Skip  = status fault, down, stale, disabled or null, or a blank /
          unrecognised value
  A Data Status column, if present, is also checked: bad there = Skip.
  Samples = Pass + Fail + Skip.
  % Passes = Pass / (Pass + Fail)   } skips don't count against
  % Fails  = Fail / (Pass + Fail)   } the score
  % Skips  = Skip / Samples

Sample files
------------
  Folder:  samplesFolder (default file:^Csv_Logs/VAV_Samples)
  Used:    *.csv whose name contains fileMatch (default ZoneTempDeltaT).
           Names starting with "TEST" are ignored.
  Month:   from the date in the file name, e.g.
           Server_XXXX_VAV_ZoneTempDeltaT_20261028_0900.csv -> 2026-10.
           Files with no date in the name use their modified date.
  Columns: found by header name (Building, Equipment, In Range), so
           column order doesn't matter. Other columns (Delta, Band,
           temperatures) are ignored. A file with no column whose header
           contains "Range" is skipped and named in status.
           Expected export:
             Building,Equipment,Delta,Band,Zone Temperature,
             Effective Setpoint,In Range
             BXXXXX,VAV_201,-0.27,1.25,20.73,21.00,In Range {ok}

Report (one file per building)
------------------------------
  Live:     <reportFolder>/<reportName> <B#> <yyyy-MM>.csv
            e.g. Monthly VAV health BXXXXX 2026-10.csv
  Archived: <archiveFolder>/<yyyy>/<B#>/<same name>
            e.g. Archive/2026/BXXXXX/Monthly VAV health BXXXXX 2026-10.csv
  liveMonth shows which month the live files hold. Re-running the same
  month overwrites them. Running a different month archives them first,
  so reportFolder only ever holds one month.

  Building Score (% Passes),97.8      <- summary, read at a glance
  % Skips,3.2
                                       <- blank line
  Building Number,Equipment Name,Number of Samples,Number of Passes,
  Number of Fails,Number of Skips,% Passes,% Fails,% Skips
  BXXXXX,VAV_201,93,88,2,3,97.8,2.2,3.2
  ...
  BXXXXX,Building Score,<totals>,...  <- same totals as the summary

Daily samples and retention
---------------------------
  After Run Report Now writes a month's reports, that month's sample
  files (and any older ones still lying around) move from samplesFolder
  to <archiveFolder>/<yyyy>/Samples/<yyyy-MM>/. A run always reads the
  month's samples from both places, each file name once, so re-running
  an old month (reportMonth) still works and nothing is counted twice.
  maxArchives = months to keep (default 84 = 7 years, 0 = keep forever).
  After each run and each Archive Reports, archived reports and samples
  for months older than that are deleted, by the month in the name, and
  empty folders are removed. Live reports in reportFolder are never
  purged. Only this program's files are touched.

Emailing (Mailer program)
-------------------------
  This program sends nothing. When a real run (Run Report Now)
  has written the reports, reportsWritten is set to the time. Link
  reportsWritten -> Mailer's send action (Send Now). In the Mailer, one
  files line per building; the name ends in the month, so use a
  wildcard with latest:
    latest: file:^Csv_Logs/Monthly_Reports/Monthly VAV health BXXXXX *.csv => a@company.com; cc: b@company.com
    latest: file:^Csv_Logs/Monthly_Reports/Monthly VAV health BYYYYY *.csv => c@company.com
  (The space before * keeps BXXXXX from also matching BXXXXX1.)
  Leave the Mailer's subject / body blank for
  "Automated Report - Monthly VAV health BXXXXX 2026-10".
  While testing, point the Mailer's lines at your own address (or use
  its Dry Run) - every Run Report Now fires reportsWritten.

When it runs
------------
  Only when told to: link a trigger schedule (e.g. the 1st of each
  month, early morning) to Run Report Now (runReport). With reportMonth
  blank it reports the month just finished. Keep reportMonth blank for
  the trigger - if it's set, that month is reported instead.
  lastReported shows the month of the last real run.

Actions
-------
  Run Report Now  Report for reportMonth (yyyy-MM, blank = last month).
                  Writes the real files and updates reportsWritten.
                  If reportMonth was set, it is cleared afterwards and
                  the status says so - the next run is automatic again.
  Archive Reports Moves every live report to <archiveFolder>/<yyyy>/<B#>/,
                  sorts any loose archive files the same way, then
                  purges anything older than maxArchives months.
                  Optional - the next month's run archives the previous
                  month automatically. If a name is taken in the
                  archive, " (2)", " (3)"... is added.

Imports
-------
  None beyond the predefined ones (javax.baja.sys, javax.baja.status,
  java.util). The email-rt import is only needed on the Mailer.

Slot Sheet
----------
  Name           Type           Flags / Config
  -------------  -------------  -----------------------------------------
  samplesFolder  baja:Ord       default file:^Csv_Logs/VAV_Samples
  fileMatch      baja:String    default ZoneTempDeltaT
  reportFolder   baja:Ord       default file:^Csv_Logs/Monthly_Reports
  reportName     baja:String    default Monthly VAV health
  archiveFolder  baja:Ord       default
                                file:^Csv_Logs/Monthly_Reports/Archive
  maxArchives    baja:Integer   default 84 (months; 0 = keep forever),
                                facets min=0
  reportMonth    baja:String    (yyyy-MM, blank = auto)
  lastReported   baja:String    Read Only
  reportsWritten baja:AbsTime   Read Only  (link to Mailer's send action)
  liveMonth      baja:String    Read Only   (month in the live reports)
  status         baja:String    Read Only,
                                facets multiLine=true,fieldWidth=110
  version        baja:String    Read Only
  quickGuide     baja:String    Read Only,
                                facets multiLine=true,fieldWidth=75
  runReport      Action         Display Name "Run Report Now"
  archive        Action         Display Name "Archive Reports"
================================================================================
*/

private static final String VERSION = "v1.00";

private static final String DEFAULT_SAMPLES = "file:^Csv_Logs/VAV_Samples";
private static final String DEFAULT_REPORTS = "file:^Csv_Logs/Monthly_Reports";
private static final String DEFAULT_NAME    = "Monthly VAV health";
private static final String DEFAULT_ARCHIVE = "file:^Csv_Logs/Monthly_Reports/Archive";
private static final String DEFAULT_MATCH   = "ZoneTempDeltaT";
private static final int    DEFAULT_MAX_ARCHIVES = 84;   // months = 7 years

private static final String REPORT_HEADER =
  "Building Number,Equipment Name,Number of Samples,Number of Passes," +
  "Number of Fails,Number of Skips,% Passes,% Fails,% Skips";

private static final String QUICK_GUIDE =
  "Monthly VAV Health " + "v1.00\n" +
  "=====================================\n" +
  "\n" +
  "Reads the ZoneTempDeltaT sample CSVs in samplesFolder and, on the\n" +
  "Run Report Now (from your trigger schedule), writes one report\n" +
  "per building to reportFolder.\n" +
  "Emailing is done by a Mailer: link reportsWritten -> Mailer Send.\n" +
  "\n" +
  "From the In Range column (ZoneTempDelta inRange):\n" +
  "  Pass = In Range {ok}    Fail = Out of Range {ok}\n" +
  "  Skip = fault/down/stale/disabled/null status\n" +
  "Files without an In Range column are skipped.\n" +
  "% Passes / % Fails = of passes + fails (skips excluded);\n" +
  "% Skips = of all samples. Building Score = % Passes of the\n" +
  "building totals, also shown at the top of each report.\n" +
  "\n" +

  "Setup:\n" +
  "  1. Save each export to samplesFolder (File Recipient).\n" +
  "  2. Link a trigger schedule (e.g. the 1st, early morning)\n" +
  "     to Run Report Now. Keep reportMonth blank.\n" +
  "  3. Link reportsWritten to a Mailer's send action. In the\n" +
  "     Mailer, one files line per building with its recipients:\n" +
  "     latest: file:^<reportFolder>/<reportName> BXXXXX *.csv\n" +
  "       => a@x.com\n" +
  "\n" +
  "Live reports: '<reportName> <B#> <yyyy-MM>.csv'.\n" +
  "Archive: <archive>/<yyyy>/<B#>/ and <archive>/<yyyy>/Samples/\n" +
  "<yyyy-MM>/ (samples move there after their month is reported).\n" +
  "maxArchives: months kept (84 = 7 yrs, 0 = forever).\n" +
  "liveMonth = month they hold.\n" +
  "\n" +
  "Actions:\n" +
  "  Run Report Now   reportMonth (blank = last month), real files,\n" +
  "                   updates reportsWritten; clears reportMonth\n" +
  "                   after using it\n" +
  "  Archive Reports  move live reports to archiveFolder\n" +
  "                   (optional - the next month's run archives\n" +
  "                   the previous month automatically)\n";

public void onStart() throws Exception
{
  setVersion(VERSION);
  setQuickGuide(QUICK_GUIDE);
}

public void onExecute() throws Exception
{
  // Nothing: reports run from Run Report Now (link your trigger
  // schedule there).
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// Actions
// ----------------------------------------------------

public void onRunReport() throws Exception
{
  String raw = getReportMonth();
  boolean set = raw != null && raw.trim().length() > 0;
  String m = cleanMonth(raw);
  if (set && m == null)
  {
    setStatus("[" + now() + "] Not run: reportMonth '" + raw.trim() +
      "' isn't yyyy-MM (e.g. 2026-10). Fix or clear it.");
    return;
  }

  runFor(set ? m : monthOffset(-1));

  if (set)
  {
    setReportMonth("");
    String st = getStatus() == null ? "" : getStatus();
    if (st.length() > 0 && !st.endsWith("\n")) st += "\n";
    setStatus(st +
      ">>> reportMonth " + m + " was used and has been CLEARED.\n" +
      ">>> The next Run Report Now reports last month automatically.\n");
  }
}

public void onArchive() throws Exception
{
  StringBuffer log = new StringBuffer();
  log.append("[").append(now()).append("] Archive\n");
  archiveReports(log);
  purge(log);
  setStatus(log.toString());
}

// ----------------------------------------------------
// Report
// ----------------------------------------------------

private void runFor(String month)
{
  StringBuffer log = new StringBuffer();
  log.append("[").append(now()).append("] ")
     .append("Report ").append(month).append("\n");

  java.io.File src = resolveToFile(ordString("samplesFolder", DEFAULT_SAMPLES));
  java.io.File arch = resolveToFile(ordString("archiveFolder", DEFAULT_ARCHIVE));
  java.io.File archSamples = (arch == null) ? null : sampleArchiveDir(arch, month);
  boolean haveSrc = src != null && src.isDirectory();
  boolean haveArch = archSamples != null && archSamples.isDirectory();
  if (!haveSrc && !haveArch)
  {
    log.append("Samples folder not found: ")
       .append(ordString("samplesFolder", DEFAULT_SAMPLES));
    setStatus(log.toString());
    return;
  }

  String match = getFileMatch();
  if (match == null || match.trim().length() == 0) match = DEFAULT_MATCH;
  match = match.trim().toLowerCase();

  // building -> (equip -> int[]{samples, pass, fail, skip})
  java.util.TreeMap data = new java.util.TreeMap();
  int filesUsed = 0;
  int badFiles = 0;
  java.util.ArrayList noRange = new java.util.ArrayList();

  // This month's samples from samplesFolder and from the archive
  // (Archive/<yyyy>/Samples/<yyyy-MM>), each file name read once.
  java.util.ArrayList all = new java.util.ArrayList();
  if (haveSrc) addAll(all, src.listFiles());
  if (haveArch) addAll(all, archSamples.listFiles());
  java.util.HashSet seen = new java.util.HashSet();
  for (int i = 0; i < all.size(); i++)
  {
    java.io.File f = (java.io.File) all.get(i);
    String n = f.getName();
    String ln = n.toLowerCase();
    if (!f.isFile() || !ln.endsWith(".csv")) continue;
    if (ln.startsWith("test")) continue;
    if (ln.indexOf(match) < 0) continue;
    if (!month.equals(monthOfFile(f))) continue;
    if (!seen.add(ln)) continue;

    int rc = readSampleFile(f, data);
    if (rc > 0) filesUsed++;
    else if (rc < 0) noRange.add(n);
    else badFiles++;
  }

  StringBuffer skipped = new StringBuffer();
  if (badFiles > 0) skipped.append(badFiles).append(" unreadable\n");
  if (!noRange.isEmpty())
  {
    skipped.append(noRange.size()).append(" skipped, no In Range column:\n");
    for (int i = 0; i < noRange.size(); i++)
      skipped.append("  ").append(noRange.get(i)).append("\n");
  }

  if (filesUsed == 0)
  {
    log.append("No usable sample files for ").append(month)
       .append(" in samplesFolder or the archive\n").append(skipped);
    setStatus(log.toString());
    return;
  }
  log.append(filesUsed).append(" sample file(s) read\n").append(skipped);

  String repOrd = ordString("reportFolder", DEFAULT_REPORTS);
  java.io.File outDir = resolveToFile(repOrd);
  if (outDir == null)
  {
    log.append("Report folder refused: ").append(repOrd);
    setStatus(log.toString());
    return;
  }
  if (!outDir.exists()) outDir.mkdirs();

  String baseName = baseName();

  // Live files hold a different month: archive them before overwriting.
  String live = getLiveMonth();
  if (live != null && live.length() > 0 && !live.equals(month))
  {
    log.append("Live reports hold ").append(live).append(", archiving first:\n");
    archiveReports(log);
  }
  setLiveMonth(month);

  int written = 0;

  java.util.Iterator bi = data.keySet().iterator();
  while (bi.hasNext())
  {
    String bldg = (String) bi.next();
    java.util.Map equips = (java.util.Map) data.get(bldg);

    String fileName = baseName + " " + bldg + " " + month + ".csv";
    java.io.File out = new java.io.File(outDir, fileName);
    int[] tot = writeReport(out, bldg, equips);
    if (tot == null)
    {
      log.append(bldg).append(": WRITE FAILED ").append(fileName).append("\n");
      continue;
    }
    written++;
    log.append(bldg).append(": ").append(equips.size()).append(" equip, score ")
       .append(pct(tot[1], tot[1] + tot[2])).append("%, skips ")
       .append(pct(tot[3], tot[0])).append("% -> ").append(fileName).append("\n");
  }

  if (written > 0)
  {
    archiveSamples(month, log);
    purge(log);
    setLastReported(month);
    setReportsWritten(BAbsTime.now());
    log.append("reportsWritten updated (linked Mailer sends now)\n");
  }

  setStatus(log.toString());
}

// ----------------------------------------------------
// Archive
// ----------------------------------------------------

// Moves every live report in reportFolder to archiveFolder:
//   "<name> <B#> <yyyy-MM>.csv"  moved as is
//   "<name> <B#>.csv"            (undated, older layout) moved with the
//                                liveMonth added, if liveMonth is set
private void archiveReports(StringBuffer log)
{
  String month = getLiveMonth();
  month = month == null ? "" : month.trim();

  java.io.File dir = resolveToFile(ordString("reportFolder", DEFAULT_REPORTS));
  java.io.File arch = resolveToFile(ordString("archiveFolder", DEFAULT_ARCHIVE));
  if (dir == null || !dir.isDirectory())
  {
    log.append("Report folder not found\n");
    return;
  }
  if (arch == null)
  {
    log.append("Archive folder refused: ")
       .append(ordString("archiveFolder", DEFAULT_ARCHIVE)).append("\n");
    return;
  }
  if (!arch.exists()) arch.mkdirs();

  String prefix = baseName() + " ";
  // After the prefix: "<B#> <yyyy-MM>" (dated) or "<B#>" (undated).
  java.util.regex.Pattern dated =
    java.util.regex.Pattern.compile("^\\S+ (20[0-9]{2}-[01][0-9])$");
  java.util.regex.Pattern undated = java.util.regex.Pattern.compile("^\\S+$");

  int moved = 0;
  int failed = 0;
  int skipped = 0;
  java.io.File[] files = dir.listFiles();
  if (files == null) files = new java.io.File[0];
  for (int i = 0; i < files.length; i++)
  {
    java.io.File f = files[i];
    String n = f.getName();
    if (!f.isFile() || !n.startsWith(prefix) || !n.toLowerCase().endsWith(".csv")) continue;
    String mid = n.substring(prefix.length(), n.length() - 4);

    String stem;
    if (dated.matcher(mid).matches())
      stem = n.substring(0, n.length() - 4);
    else if (undated.matcher(mid).matches())
    {
      if (month.length() == 0)
      {
        skipped++;
        log.append("  skipped ").append(n).append(" (no month in name, liveMonth blank)\n");
        continue;
      }
      stem = n.substring(0, n.length() - 4) + " " + month;
    }
    else continue;

    java.io.File dest = reportArchiveDir(arch, stem);
    if (!dest.exists()) dest.mkdirs();
    java.io.File target = new java.io.File(dest, stem + ".csv");
    for (int k2 = 2; target.exists() && k2 < 100; k2++)
      target = new java.io.File(dest, stem + " (" + k2 + ").csv");

    if (moveFile(f, target))
    {
      moved++;
      log.append("  ").append(n).append(" -> ").append(relTo(arch, target)).append("\n");
    }
    else
    {
      failed++;
      log.append("  FAILED ").append(n).append("\n");
    }
  }

  log.append("Archived ").append(moved).append(" report(s)");
  if (failed > 0) log.append(", ").append(failed).append(" failed");
  if (skipped > 0) log.append(", ").append(skipped).append(" skipped");
  log.append("\n");

  sortLooseArchives(arch, log);

  if (failed == 0 && skipped == 0)
  {
    setLiveMonth("");
  }
}

// ----------------------------------------------------
// Archive layout and retention
//   <archiveFolder>/<yyyy>/<B#>/<reportName> <B#> <yyyy-MM>.csv
//   <archiveFolder>/<yyyy>/Samples/<yyyy-MM>/<sample file>
// ----------------------------------------------------

private void addAll(java.util.ArrayList list, java.io.File[] files)
{
  if (files == null) return;
  for (int i = 0; i < files.length; i++) list.add(files[i]);
}

// <arch>/<yyyy>/Samples/<yyyy-MM>
private java.io.File sampleArchiveDir(java.io.File arch, String month)
{
  return new java.io.File(new java.io.File(new java.io.File(arch,
    month.substring(0, 4)), "Samples"), month);
}

// Report stem "<name> <B#> <yyyy-MM>" -> <arch>/<yyyy>/<B#>
private java.io.File reportArchiveDir(java.io.File arch, String stem)
{
  String[] parts = stem.trim().split(" ");
  String month = parts[parts.length - 1];
  String bldg = parts.length >= 2 ? parts[parts.length - 2] : "Other";
  String year = month.length() >= 4 ? month.substring(0, 4) : "Other";
  return new java.io.File(new java.io.File(arch, year), bldg);
}

// Path of f relative to arch, with / separators.
private String relTo(java.io.File arch, java.io.File f)
{
  String a = arch.getPath();
  String p = f.getPath();
  if (p.startsWith(a)) p = p.substring(a.length());
  p = p.replace('\\', '/');
  while (p.startsWith("/")) p = p.substring(1);
  return arch.getName() + "/" + p;
}

// Report files sitting loose in the top of the archive (from older
// versions) -> <yyyy>/<B#>/.
private void sortLooseArchives(java.io.File arch, StringBuffer log)
{
  java.util.regex.Pattern dated = java.util.regex.Pattern.compile(
    "^" + java.util.regex.Pattern.quote(baseName() + " ") +
    "\\S+ 20[0-9]{2}-[01][0-9]( \\([0-9]+\\))?\\.csv$");
  java.io.File[] files = arch.listFiles();
  if (files == null) return;
  int moved = 0;
  for (int i = 0; i < files.length; i++)
  {
    java.io.File f = files[i];
    if (!f.isFile() || !dated.matcher(f.getName()).matches()) continue;
    String stem = f.getName().substring(0, f.getName().length() - 4)
      .replaceAll(" \\([0-9]+\\)$", "");
    java.io.File dest = reportArchiveDir(arch, stem);
    if (!dest.exists()) dest.mkdirs();
    java.io.File target = new java.io.File(dest, f.getName());
    if (target.exists()) continue;
    if (moveFile(f, target)) moved++;
  }
  if (moved > 0)
    log.append("Sorted ").append(moved).append(" loose archive file(s) into year/building folders\n");
}

// After month M is reported: move samplesFolder files for M and any
// earlier month into <arch>/<yyyy>/Samples/<yyyy-MM>/. A file already in
// the archive with the same name is left where it is (reading skips
// duplicates by name).
private void archiveSamples(String month, StringBuffer log)
{
  java.io.File src = resolveToFile(ordString("samplesFolder", DEFAULT_SAMPLES));
  java.io.File arch = resolveToFile(ordString("archiveFolder", DEFAULT_ARCHIVE));
  if (src == null || !src.isDirectory() || arch == null) return;

  String match = getFileMatch();
  if (match == null || match.trim().length() == 0) match = DEFAULT_MATCH;
  match = match.trim().toLowerCase();

  int moved = 0;
  int failed = 0;
  java.io.File[] files = src.listFiles();
  if (files == null) return;
  for (int i = 0; i < files.length; i++)
  {
    java.io.File f = files[i];
    String ln = f.getName().toLowerCase();
    if (!f.isFile() || !ln.endsWith(".csv") || ln.startsWith("test")) continue;
    if (ln.indexOf(match) < 0) continue;
    String m = monthOfFile(f);
    if (m.compareTo(month) > 0) continue;   // newer than the reported month

    java.io.File dest = sampleArchiveDir(arch, m);
    if (!dest.exists()) dest.mkdirs();
    java.io.File target = new java.io.File(dest, f.getName());
    if (target.exists()) continue;
    if (moveFile(f, target)) moved++;
    else failed++;
  }
  if (moved > 0 || failed > 0)
  {
    log.append("Archived ").append(moved).append(" sample file(s)");
    if (failed > 0) log.append(", ").append(failed).append(" failed");
    log.append("\n");
  }
}

// maxArchives months to keep (default 84 = 7 years). 0 = keep forever.
private int maxArchivesValue()
{
  int m = getMaxArchives();
  return m < 0 ? DEFAULT_MAX_ARCHIVES : m;
}

// Deletes archived reports and samples older than maxArchives months
// (by the month in the file / folder name), then removes empty folders.
// Keeps the current month and the (maxArchives - 1) months before it.
private void purge(StringBuffer log)
{
  int keep = maxArchivesValue();
  if (keep == 0) return;
  java.io.File arch = resolveToFile(ordString("archiveFolder", DEFAULT_ARCHIVE));
  if (arch == null || !arch.isDirectory()) return;

  String cutoff = monthOffset(-(keep - 1));   // oldest month kept
  java.util.regex.Pattern rep = java.util.regex.Pattern.compile(
    "(20[0-9]{2}-[01][0-9])( \\([0-9]+\\))?\\.csv$");
  int[] counts = new int[2];   // reports, samples
  purgeDir(arch, arch, cutoff, rep, counts);

  if (counts[0] > 0 || counts[1] > 0)
    log.append("Purged (older than ").append(cutoff).append("): ")
       .append(counts[0]).append(" report(s), ")
       .append(counts[1]).append(" sample file(s)\n");
}

private void purgeDir(java.io.File arch, java.io.File dir, String cutoff,
                      java.util.regex.Pattern rep, int[] counts)
{
  java.io.File[] files = dir.listFiles();
  if (files == null) return;
  for (int i = 0; i < files.length; i++)
  {
    java.io.File f = files[i];
    if (f.isDirectory())
    {
      purgeDir(arch, f, cutoff, rep, counts);
      String[] left = f.list();
      if (left != null && left.length == 0) f.delete();
      continue;
    }
    String n = f.getName();
    boolean isSample = "Samples".equals(f.getParentFile().getParentFile() == null
      ? "" : f.getParentFile().getParentFile().getName());
    String m;
    if (isSample) m = f.getParentFile().getName();          // .../Samples/<yyyy-MM>/
    else
    {
      java.util.regex.Matcher mm = rep.matcher(n);
      if (!mm.find()) continue;                             // not one of ours
      m = mm.group(1);
    }
    if (m.length() == 7 && m.compareTo(cutoff) < 0 && f.delete())
      counts[isSample ? 1 : 0]++;
  }
}

// Rename, or copy + delete if rename isn't possible.
private boolean moveFile(java.io.File from, java.io.File to)
{
  if (from.renameTo(to)) return true;
  java.io.InputStream in = null;
  java.io.OutputStream out = null;
  try
  {
    in = new java.io.FileInputStream(from);
    out = new java.io.FileOutputStream(to);
    byte[] buf = new byte[8192];
    int r;
    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
  }
  catch (Exception e)
  {
    return false;
  }
  finally
  {
    if (in != null) try { in.close(); } catch (Exception ignore) {}
    if (out != null) try { out.close(); } catch (Exception ignore) {}
  }
  return from.delete();
}

private String baseName()
{
  String b = getReportName();
  if (b == null || b.trim().length() == 0) b = DEFAULT_NAME;
  return b.trim();
}

// Adds one sample file's rows to data.
// Returns 1 = read, 0 = can't be read, -1 = no In Range column.
private int readSampleFile(java.io.File f, java.util.Map data)
{
  java.io.BufferedReader br = null;
  try
  {
    br = new java.io.BufferedReader(new java.io.InputStreamReader(
      new java.io.FileInputStream(f), "UTF-8"));

    String line = br.readLine();
    if (line == null) return 0;
    if (line.length() > 0 && line.charAt(0) == '﻿') line = line.substring(1);

    String[] h = splitCsv(line);
    int cB = findCol(h, "building", 0);
    int cE = findCol(h, "equip", 1);
    int cR = findCol(h, "range", -1);
    int cS = findCol(h, "status", -1);
    if (cR < 0) return -1;

    while ((line = br.readLine()) != null)
    {
      if (line.trim().length() == 0) continue;
      String[] r = joinBraces(splitCsv(line));
      String bldg  = cell(r, cB).toUpperCase();
      String equip = cell(r, cE);
      if (bldg.length() == 0 || equip.length() == 0) continue;

      java.util.Map equips = (java.util.Map) data.get(bldg);
      if (equips == null)
      {
        equips = new java.util.HashMap();
        data.put(bldg, equips);
      }
      int[] c = (int[]) equips.get(equip);
      if (c == null)
      {
        c = new int[4];
        equips.put(equip, c);
      }

      c[0]++;
      String rc = cell(r, cR);
      if (isBadStatus(rc + " " + cell(r, cS)))
      {
        c[3]++;
        continue;
      }
      int v = rangeValue(rc);
      if (v > 0) c[1]++;
      else if (v == 0) c[2]++;
      else c[3]++;
    }
    return 1;
  }
  catch (Exception e)
  {
    return 0;
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
  }
}

// In Range cell -> 1 pass, 0 fail, -1 unknown. The status part ({ok})
// is ignored here. "Out of Range" / "false" = fail, "In Range" / "true"
// = pass (checked in that order, case ignored).
private int rangeValue(String cell)
{
  String v = cell;
  int brace = v.indexOf('{');
  if (brace >= 0) v = v.substring(0, brace);
  v = v.trim().toLowerCase();
  if (v.indexOf("out of range") >= 0 || v.equals("false")) return 0;
  if (v.indexOf("in range") >= 0 || v.equals("true")) return 1;
  return -1;
}

private boolean isBadStatus(String s)
{
  String t = s.toLowerCase();
  return t.indexOf("fault") >= 0 || t.indexOf("down") >= 0
      || t.indexOf("stale") >= 0 || t.indexOf("disabled") >= 0
      || t.indexOf("null") >= 0;
}

// Writes the building report. Returns totals {samples, pass, fail, skip},
// or null if the write failed.
private int[] writeReport(java.io.File out, String bldg, java.util.Map equips)
{
  java.util.ArrayList names = new java.util.ArrayList(equips.keySet());
  sortNatural(names);

  int[] tot = new int[4];
  for (int i = 0; i < names.size(); i++)
  {
    int[] c = (int[]) equips.get(names.get(i));
    for (int k = 0; k < 4; k++) tot[k] += c[k];
  }

  java.io.BufferedWriter bw = null;
  try
  {
    bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
      new java.io.FileOutputStream(out, false), "UTF-8"));
    bw.write("Building Score (% Passes)," + pct(tot[1], tot[1] + tot[2]));
    bw.newLine();
    bw.write("% Skips," + pct(tot[3], tot[0]));
    bw.newLine();
    bw.newLine();
    bw.write(REPORT_HEADER);
    bw.newLine();
    for (int i = 0; i < names.size(); i++)
    {
      String eq = (String) names.get(i);
      bw.write(row(bldg, eq, (int[]) equips.get(eq)));
      bw.newLine();
    }
    bw.write(row(bldg, "Building Score", tot));
    bw.newLine();
    return tot;
  }
  catch (Exception e)
  {
    return null;
  }
  finally
  {
    if (bw != null) try { bw.close(); } catch (Exception ignore) {}
  }
}

private String row(String bldg, String name, int[] c)
{
  return csv(bldg) + "," + csv(name) + "," + c[0] + "," + c[1] + "," + c[2] + "," +
    c[3] + "," + pct(c[1], c[1] + c[2]) + "," + pct(c[2], c[1] + c[2]) + "," +
    pct(c[3], c[0]);
}

// ----------------------------------------------------
// Month helpers
// ----------------------------------------------------

// yyyy-MM, offset months from now (0 = this month, -1 = last month).
private String monthOffset(int offset)
{
  java.util.Calendar c = java.util.Calendar.getInstance();
  c.set(java.util.Calendar.DAY_OF_MONTH, 1);
  c.add(java.util.Calendar.MONTH, offset);
  return new java.text.SimpleDateFormat("yyyy-MM").format(c.getTime());
}

// Month from a yyyyMMdd date in the file name, else its modified date.
private String monthOfFile(java.io.File f)
{
  java.util.regex.Matcher m = java.util.regex.Pattern
    .compile("(?<![0-9])(20[0-9]{2})([01][0-9])([0-3][0-9])(?![0-9])")
    .matcher(f.getName());
  String found = null;
  while (m.find()) found = m.group(1) + "-" + m.group(2);
  if (found != null) return found;
  return new java.text.SimpleDateFormat("yyyy-MM").format(new java.util.Date(f.lastModified()));
}

// "2026-10" or "2026-1" -> "2026-10"/"2026-01"; null if blank or bad.
private String cleanMonth(String s)
{
  if (s == null) return null;
  java.util.regex.Matcher m = java.util.regex.Pattern
    .compile("^\\s*(20[0-9]{2})[-/]([0-9]{1,2})\\s*$").matcher(s);
  if (!m.find()) return null;
  int mo = Integer.parseInt(m.group(2));
  if (mo < 1 || mo > 12) return null;
  return m.group(1) + "-" + (mo < 10 ? "0" : "") + mo;
}

// ----------------------------------------------------
// CSV helpers
// ----------------------------------------------------

// Splits one CSV line, honouring "quoted, fields" and "" escapes.
private String[] splitCsv(String line)
{
  java.util.ArrayList out = new java.util.ArrayList();
  StringBuffer cur = new StringBuffer();
  boolean q = false;
  for (int i = 0; i < line.length(); i++)
  {
    char ch = line.charAt(i);
    if (q)
    {
      if (ch == '"')
      {
        if (i + 1 < line.length() && line.charAt(i + 1) == '"')
        {
          cur.append('"');
          i++;
        }
        else q = false;
      }
      else cur.append(ch);
    }
    else if (ch == '"') q = true;
    else if (ch == ',')
    {
      out.add(cur.toString());
      cur.setLength(0);
    }
    else cur.append(ch);
  }
  out.add(cur.toString());
  return (String[]) out.toArray(new String[out.size()]);
}

// Rejoins a status set split by an unquoted comma:
// "In Range {ok", "stale}" -> "In Range {ok,stale}".
private String[] joinBraces(String[] cells)
{
  java.util.ArrayList out = new java.util.ArrayList();
  for (int i = 0; i < cells.length; i++)
  {
    String c = cells[i];
    while (c.lastIndexOf('{') > c.lastIndexOf('}') && i + 1 < cells.length)
      c = c + "," + cells[++i];
    out.add(c);
  }
  return (String[]) out.toArray(new String[out.size()]);
}

private int findCol(String[] h, String key, int fallback)
{
  for (int i = 0; i < h.length; i++)
    if (h[i].toLowerCase().indexOf(key) >= 0) return i;
  return fallback;
}

private String cell(String[] r, int i)
{
  return (i >= 0 && i < r.length) ? r[i].trim() : "";
}

private String csv(String s)
{
  if (s == null) return "";
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0)
    return "\"" + s.replace("\"", "\"\"") + "\"";
  return s;
}

private String pct(int part, int whole)
{
  return whole > 0 ? fmt(100.0 * part / whole, 1) : "0.0";
}

private String fmt(double v, int dp)
{
  return String.format(java.util.Locale.US, "%." + dp + "f", v);
}

// Natural order: VAV_2 before VAV_10, "VAV_117 (2)" after "VAV_117".
private void sortNatural(java.util.ArrayList list)
{
  for (int i = 1; i < list.size(); i++)
  {
    String key = (String) list.get(i);
    int j = i - 1;
    while (j >= 0 && natCompare((String) list.get(j), key) > 0)
    {
      list.set(j + 1, list.get(j));
      j--;
    }
    list.set(j + 1, key);
  }
}

private int natCompare(String a, String b)
{
  int i = 0, j = 0;
  while (i < a.length() && j < b.length())
  {
    char ca = a.charAt(i), cb = b.charAt(j);
    if (Character.isDigit(ca) && Character.isDigit(cb))
    {
      int si = i, sj = j;
      while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
      while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
      long na = Long.parseLong(a.substring(si, Math.min(i, si + 18)));
      long nb = Long.parseLong(b.substring(sj, Math.min(j, sj + 18)));
      if (na != nb) return na < nb ? -1 : 1;
    }
    else
    {
      int d = Character.toLowerCase(ca) - Character.toLowerCase(cb);
      if (d != 0) return d;
      i++;
      j++;
    }
  }
  return (a.length() - i) - (b.length() - j);
}

// ----------------------------------------------------
// Paths
// ----------------------------------------------------

private String ordString(String slot, String def)
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get(slot);
    if (ord != null && !ord.isNull() && ord.toString().trim().length() > 0)
      return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return def;
}

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// From ComponentCopier / ForceRemove: "file:^x/y" -> <station home>/x/y.
// Paths outside the station home folder are refused.
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

  try
  {
    java.io.File home = javax.baja.sys.Sys.getStationHome().getCanonicalFile();
    java.io.File canon = f.getCanonicalFile();
    if (canon.equals(home) ||
        canon.getPath().startsWith(home.getPath() + java.io.File.separator))
      return canon;
  }
  catch (Exception ignore) {}
  return null;
}
