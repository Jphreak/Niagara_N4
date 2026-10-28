/*
================================================================================
Program: MonthlyVavHealth - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-28

Changes
-------
  v1.00  Initial release.

Purpose
-------
Reads the ZoneTempDeltaT sample CSVs your BQL export saves 3 times a day,
and once a month writes one report CSV per building and emails each one
to that building's list.

For every VAV in the month's sample files:
  Pass  = Data Status ok and |Delta| <= band (default 2.0)
  Fail  = Data Status ok and |Delta| >  band
  Skip  = Data Status fault, down, stale, disabled or null, or no Delta
  Samples = Pass + Fail + Skip. All three % columns are of Samples.

Sample files
------------
  Folder:  samplesFolder (default file:^Csv_Logs/VAV_Samples)
  Used:    *.csv whose name contains fileMatch (default ZoneTempDeltaT).
           Names starting with "TEST" are ignored.
  Month:   from the date in the file name, e.g.
           Server_XXXX_VAV_ZoneTempDeltaT_20261028_0900.csv -> 2026-10.
           Files with no date in the name use their modified date.
  Columns: found by header name (Building, Equipment, Delta, Status), so
           column order doesn't matter. Expected export:
             Building,Equipment,Delta,Zone Temperature,Effective Setpoint,
             Data Status
             BXXXXX,VAV_201,-0.27,20.73,21.00,{ok}

Report (one file per building)
------------------------------
  <reportFolder>/<reportName> <B#> <yyyy-MM>.csv
  e.g. Monthly VAV health BXXXXX 2026-10.csv   (overwritten if re-run)

  Building Number,Equipment Name,Number of Samples,Number of Passes,
  Number of Fails,Number of Skips,% Passes,% Fails,% Skips
  BXXXXX,VAV_201,93,90,2,1,96.8,2.2,1.1
  ...
  BXXXXX,Building Score,<totals>,...  <- % Passes here is the
                                         building score

Email
-----
  One email per building, its report attached. Recipients from
  emailList, one line per building (edit any time, no recompile):
    BXXXXX: someone@company.com; other@company.com
    BYYYYY: another@company.com
  Lines starting with # are ignored. A building with no line is written
  but not emailed (noted in status).
  Sent through the station's EmailService (outgoing account must be set
  up). The email module is used by name at run time, so no email
  import is needed. If a send fails, status shows why plus what the
  email module exposes.

When it runs
------------
  Automatically: first run on or after runHour (default 6) on the 1st,
  for the month just finished. Link a CurrentTime block to tick. If the
  station is down on the 1st it catches up on the next run.
  lastReported shows the last month done automatically.

Actions
-------
  Run Report Now  Report for reportMonth (yyyy-MM, blank = last month).
                  Writes the real files and emails the building lists.
  Test Report     Same month rules, but writes "TEST <name>" files and
                  emails only testEmail (if set). Blank reportMonth here
                  = this month so far, so it works with today's files.

Imports
-------
  None beyond the predefined ones (javax.baja.sys, javax.baja.status,
  java.util).

Slot Sheet
----------
  Name           Type           Flags / Config
  -------------  -------------  -----------------------------------------
  samplesFolder  baja:Ord       default file:^Csv_Logs/VAV_Samples
  fileMatch      baja:String    default ZoneTempDeltaT
  reportFolder   baja:Ord       default file:^Csv_Logs/Monthly_Reports
  reportName     baja:String    default Monthly VAV health
  band           baja:Double    default 2.0
  emailList      baja:String    facets multiLine=true,fieldWidth=75
  emailSubject   baja:String    default Monthly VAV health
  testEmail      baja:String
  reportMonth    baja:String    (yyyy-MM, blank = auto)
  runHour        baja:Integer   default 6, facets min=0,max=23
  tick           baja:AbsTime   Execute On Change (link from CurrentTime)
  lastReported   baja:String    Read Only
  status         baja:String    Read Only,
                                facets multiLine=true,fieldWidth=110
  version        baja:String    Read Only
  quickGuide     baja:String    Read Only,
                                facets multiLine=true,fieldWidth=75
  runReport      Action         Display Name "Run Report Now"
  testReport     Action         Display Name "Test Report"
================================================================================
*/

private static final String VERSION = "v1.00";

private static final String DEFAULT_SAMPLES = "file:^Csv_Logs/VAV_Samples";
private static final String DEFAULT_REPORTS = "file:^Csv_Logs/Monthly_Reports";
private static final String DEFAULT_NAME    = "Monthly VAV health";
private static final String DEFAULT_MATCH   = "ZoneTempDeltaT";
private static final double DEFAULT_BAND    = 2.0;

private static final String REPORT_HEADER =
  "Building Number,Equipment Name,Number of Samples,Number of Passes," +
  "Number of Fails,Number of Skips,% Passes,% Fails,% Skips";

private static final String QUICK_GUIDE =
  "Monthly VAV Health " + "v1.00\n" +
  "=====================================\n" +
  "\n" +
  "Reads the ZoneTempDeltaT sample CSVs in samplesFolder and, on the\n" +
  "1st of each month, writes one report per building to reportFolder\n" +
  "and emails it to that building's list.\n" +
  "\n" +
  "Pass = status ok and |Delta| <= band\n" +
  "Fail = status ok and |Delta| >  band\n" +
  "Skip = status fault/down/stale/disabled/null\n" +
  "Building Score row = total passes / total samples.\n" +
  "\n" +
  "emailList, one line per building:\n" +
  "  BXXXXX: someone@company.com; other@company.com\n" +
  "\n" +
  "Setup:\n" +
  "  1. Save each export to samplesFolder (File Recipient).\n" +
  "  2. Fill in emailList.\n" +
  "  3. Link a CurrentTime block to tick.\n" +
  "\n" +
  "Actions:\n" +
  "  Run Report Now  reportMonth (blank = last month), real files\n" +
  "                  + building emails\n" +
  "  Test Report     'TEST ' files, emails testEmail only\n" +
  "                  (blank reportMonth = this month so far)\n";

public void onStart() throws Exception
{
  setVersion(VERSION);
  setQuickGuide(QUICK_GUIDE);
}

public void onExecute() throws Exception
{
  java.util.Calendar c = java.util.Calendar.getInstance();
  int day  = c.get(java.util.Calendar.DAY_OF_MONTH);
  int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
  if (day == 1 && hour < runHourValue()) return;

  String target = monthOffset(-1);
  String done = getLastReported();
  if (done != null && done.equals(target)) return;

  // Mark first so a slow or failing run isn't retried every tick.
  setLastReported(target);
  runFor(target, false);
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// Actions
// ----------------------------------------------------

public void onRunReport() throws Exception
{
  String m = cleanMonth(getReportMonth());
  runFor(m != null ? m : monthOffset(-1), false);
}

public void onTestReport() throws Exception
{
  String m = cleanMonth(getReportMonth());
  runFor(m != null ? m : monthOffset(0), true);
}

// ----------------------------------------------------
// Report
// ----------------------------------------------------

private void runFor(String month, boolean test)
{
  StringBuffer log = new StringBuffer();
  log.append("[").append(now()).append("] ")
     .append(test ? "TEST report " : "Report ").append(month).append("\n");

  java.io.File src = resolveToFile(ordString("samplesFolder", DEFAULT_SAMPLES));
  if (src == null || !src.isDirectory())
  {
    log.append("Samples folder not found: ")
       .append(ordString("samplesFolder", DEFAULT_SAMPLES));
    setStatus(log.toString());
    return;
  }

  String match = getFileMatch();
  if (match == null || match.trim().length() == 0) match = DEFAULT_MATCH;
  match = match.trim().toLowerCase();
  double band = bandValue();

  // building -> (equip -> int[]{samples, pass, fail, skip})
  java.util.TreeMap data = new java.util.TreeMap();
  int filesUsed = 0;
  int badFiles = 0;

  java.io.File[] files = src.listFiles();
  if (files == null) files = new java.io.File[0];
  for (int i = 0; i < files.length; i++)
  {
    java.io.File f = files[i];
    String n = f.getName();
    String ln = n.toLowerCase();
    if (!f.isFile() || !ln.endsWith(".csv")) continue;
    if (ln.startsWith("test")) continue;
    if (ln.indexOf(match) < 0) continue;
    if (!month.equals(monthOfFile(f))) continue;

    if (readSampleFile(f, band, data)) filesUsed++;
    else badFiles++;
  }

  if (filesUsed == 0)
  {
    log.append("No sample files for ").append(month).append(" in ")
       .append(src.getName()).append(" (").append(badFiles)
       .append(" unreadable)");
    setStatus(log.toString());
    return;
  }
  log.append(filesUsed).append(" sample file(s) read");
  if (badFiles > 0) log.append(", ").append(badFiles).append(" unreadable");
  log.append("\n");

  String repOrd = ordString("reportFolder", DEFAULT_REPORTS);
  java.io.File outDir = resolveToFile(repOrd);
  if (outDir == null)
  {
    log.append("Report folder refused: ").append(repOrd);
    setStatus(log.toString());
    return;
  }
  if (!outDir.exists()) outDir.mkdirs();

  String baseName = getReportName();
  if (baseName == null || baseName.trim().length() == 0) baseName = DEFAULT_NAME;
  baseName = baseName.trim();

  java.util.Map lists = parseEmailList(getEmailList());
  String subjectBase = getEmailSubject();
  if (subjectBase == null || subjectBase.trim().length() == 0) subjectBase = DEFAULT_NAME;
  String testTo = getTestEmail();
  if (testTo != null) testTo = testTo.trim();

  java.util.Iterator bi = data.keySet().iterator();
  while (bi.hasNext())
  {
    String bldg = (String) bi.next();
    java.util.Map equips = (java.util.Map) data.get(bldg);

    String fileName = (test ? "TEST " : "") + baseName + " " + bldg + " " + month + ".csv";
    java.io.File out = new java.io.File(outDir, fileName);
    int[] tot = writeReport(out, bldg, equips);
    if (tot == null)
    {
      log.append(bldg).append(": WRITE FAILED ").append(fileName).append("\n");
      continue;
    }
    log.append(bldg).append(": ").append(equips.size()).append(" equip, score ")
       .append(pct(tot[1], tot[0])).append("% -> ").append(fileName);

    String to = test ? testTo : (String) lists.get(bldg.toUpperCase());
    if (to == null || to.length() == 0)
    {
      log.append(test ? " (no testEmail, not sent)" : " (no email list, not sent)").append("\n");
      continue;
    }

    String subject = (test ? "TEST " : "") + subjectBase.trim() + " " + bldg + " " + month;
    String body =
      "Monthly VAV health for " + bldg + ", " + month + ".\n\n" +
      "Building score: " + pct(tot[1], tot[0]) + "% of samples in range " +
      "(|delta| <= " + fmt(band, 1) + ").\n" +
      "Samples " + tot[0] + ", passes " + tot[1] + ", fails " + tot[2] +
      ", skips " + tot[3] + ".\n\n" +
      "Report attached: " + fileName + "\n";
    String attachOrd = joinOrd(repOrd, fileName);

    String err = sendEmail(to, subject, body, attachOrd);
    if (err == null) log.append(" emailed ").append(to).append("\n");
    else log.append(" EMAIL FAILED: ").append(err).append("\n");
  }

  setStatus(log.toString());
}

// Adds one sample file's rows to data. False if the file can't be read.
private boolean readSampleFile(java.io.File f, double band, java.util.Map data)
{
  java.io.BufferedReader br = null;
  try
  {
    br = new java.io.BufferedReader(new java.io.InputStreamReader(
      new java.io.FileInputStream(f), "UTF-8"));

    String line = br.readLine();
    if (line == null) return false;
    if (line.length() > 0 && line.charAt(0) == '﻿') line = line.substring(1);

    String[] h = splitCsv(line);
    int cB = findCol(h, "building", 0);
    int cE = findCol(h, "equip", 1);
    int cD = findCol(h, "delta", 2);
    int cS = findCol(h, "status", 5);

    while ((line = br.readLine()) != null)
    {
      if (line.trim().length() == 0) continue;
      String[] r = splitCsv(line);
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
      if (isBadStatus(cell(r, cS)))
      {
        c[3]++;
        continue;
      }
      double d;
      try
      {
        d = Double.parseDouble(cell(r, cD));
      }
      catch (NumberFormatException e)
      {
        c[3]++;
        continue;
      }
      if (Math.abs(d) <= band + 1e-9) c[1]++;
      else c[2]++;
    }
    return true;
  }
  catch (Exception e)
  {
    return false;
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
  }
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
  java.io.BufferedWriter bw = null;
  try
  {
    bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
      new java.io.FileOutputStream(out, false), "UTF-8"));
    bw.write(REPORT_HEADER);
    bw.newLine();
    for (int i = 0; i < names.size(); i++)
    {
      String eq = (String) names.get(i);
      int[] c = (int[]) equips.get(eq);
      for (int k = 0; k < 4; k++) tot[k] += c[k];
      bw.write(row(bldg, eq, c));
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
    c[3] + "," + pct(c[1], c[0]) + "," + pct(c[2], c[0]) + "," + pct(c[3], c[0]);
}

// ----------------------------------------------------
// Email (email module looked up by name at run time)
// Returns null on success, else the reason.
// ----------------------------------------------------

private String sendEmail(String to, String subject, String body, String attachOrd)
{
  javax.baja.sys.BComponent svc;
  try
  {
    svc = (javax.baja.sys.BComponent)
      javax.baja.sys.Sys.getService(javax.baja.sys.Sys.getType("email:EmailService"));
  }
  catch (Throwable t)
  {
    return "EmailService not found (" + t + ")";
  }

  // Message object
  javax.baja.sys.BComplex msg = null;
  String msgType = null;
  String[] msgTypes = { "email:Email", "email:EmailMessage" };
  for (int i = 0; i < msgTypes.length && msg == null; i++)
  {
    try
    {
      Object o = javax.baja.sys.Sys.getType(msgTypes[i]).getInstance();
      if (o instanceof javax.baja.sys.BComplex)
      {
        msg = (javax.baja.sys.BComplex) o;
        msgType = msgTypes[i];
      }
    }
    catch (Throwable ignore) {}
  }
  if (msg == null)
    return "no email message type (tried email:Email, email:EmailMessage); " +
      "EmailService methods: " + methodNames(svc);

  if (!setText(msg, new String[]{ "to" }, to))
    return "can't set 'to' on " + msgType + "; slots: " + slotNames(msg);
  setText(msg, new String[]{ "subject" }, subject);
  setText(msg, new String[]{ "body", "text", "message" }, body);

  String aerr = addAttachment(msg, attachOrd);
  if (aerr != null) return aerr + "; " + msgType + " slots: " + slotNames(msg);

  // Send: a send method on the service, a send action, or an outgoing
  // account under the service.
  try
  {
    if (callSend(svc, msg)) return null;

    String[] acts = { "sendEmail", "send", "sendMessage" };
    for (int i = 0; i < acts.length; i++)
    {
      javax.baja.sys.Action a = svc.getAction(acts[i]);
      if (a != null && msg instanceof javax.baja.sys.BValue)
      {
        svc.invoke(a, (javax.baja.sys.BValue) msg);
        return null;
      }
    }

    javax.baja.sys.BComponent[] kids = svc.getChildComponents();
    for (int i = 0; i < kids.length; i++)
      if (callSend(kids[i], msg)) return null;
  }
  catch (java.lang.reflect.InvocationTargetException ite)
  {
    Throwable c = ite.getCause();
    return "send failed: " + (c != null ? c.toString() : ite.toString());
  }
  catch (Throwable t)
  {
    return "send failed: " + t;
  }

  return "no send method found; EmailService methods: " + methodNames(svc) +
    "; actions: " + actionNames(svc);
}

// Calls target.send/sendMessage/sendEmail(msg) if such a public method
// takes the message's type. True if one was called.
private boolean callSend(Object target, Object msg) throws Exception
{
  String[] names = { "sendMessage", "send", "sendEmail" };
  java.lang.reflect.Method[] ms = target.getClass().getMethods();
  for (int n = 0; n < names.length; n++)
  {
    for (int i = 0; i < ms.length; i++)
    {
      Class[] p = ms[i].getParameterTypes();
      if (ms[i].getName().equals(names[n]) && p.length == 1 && p[0].isInstance(msg))
      {
        ms[i].invoke(target, new Object[]{ msg });
        return true;
      }
    }
  }
  return false;
}

// Sets the first matching slot from text. Simple slots (String, address
// lists, etc.) are decoded from the text, so the exact type doesn't
// matter. A complex slot (e.g. a body part) is searched one level down.
private boolean setText(javax.baja.sys.BComplex c, String[] names, String text)
{
  for (int i = 0; i < names.length; i++)
  {
    javax.baja.sys.Property p = c.getProperty(names[i]);
    if (p == null) continue;
    try
    {
      javax.baja.sys.BValue cur = c.get(p);
      if (cur instanceof javax.baja.sys.BSimple)
      {
        javax.baja.sys.BObject nv =
          ((javax.baja.sys.BSimple) cur).decodeFromString(text);
        c.set(p, (javax.baja.sys.BValue) nv);
        return true;
      }
      if (cur instanceof javax.baja.sys.BComplex &&
          setText((javax.baja.sys.BComplex) cur,
            new String[]{ "text", "value", "body", "content" }, text))
        return true;
    }
    catch (Throwable ignore) {}
  }
  return false;
}

// Adds the file as an attachment. Null on success, else the reason.
private String addAttachment(javax.baja.sys.BComplex msg, String ordStr)
{
  javax.baja.naming.BOrd ord = javax.baja.naming.BOrd.make(ordStr);
  String[] ordSlots = { "attachment", "file", "source", "ord", "fileOrd" };

  // 1) An attachment object added under the message (or its
  //    "attachments" container).
  String[] attTypes = { "email:EmailAttachment", "email:FileAttachment",
                        "email:OrdAttachment", "email:Attachment" };
  for (int t = 0; t < attTypes.length; t++)
  {
    javax.baja.sys.BComplex att;
    try
    {
      Object o = javax.baja.sys.Sys.getType(attTypes[t]).getInstance();
      if (!(o instanceof javax.baja.sys.BComplex)) continue;
      att = (javax.baja.sys.BComplex) o;
    }
    catch (Throwable ignore)
    {
      continue;
    }
    if (!setOrd(att, ordSlots, ord)) continue;

    try
    {
      javax.baja.sys.BValue holder = null;
      javax.baja.sys.Property ap = msg.getProperty("attachments");
      if (ap != null) holder = msg.get(ap);
      if (!(holder instanceof javax.baja.sys.BComponent) &&
          msg instanceof javax.baja.sys.BComponent)
        holder = (javax.baja.sys.BValue) msg;
      if (holder instanceof javax.baja.sys.BComponent)
      {
        ((javax.baja.sys.BComponent) holder).add("attachment?", (javax.baja.sys.BValue) att);
        return null;
      }
    }
    catch (Throwable ignore) {}
  }

  // 2) An ord slot directly on the message.
  if (setOrd(msg, ordSlots, ord)) return null;

  return "can't attach file (tried email:EmailAttachment, FileAttachment, " +
    "OrdAttachment, Attachment)";
}

private boolean setOrd(javax.baja.sys.BComplex c, String[] names, javax.baja.naming.BOrd ord)
{
  for (int i = 0; i < names.length; i++)
  {
    javax.baja.sys.Property p = c.getProperty(names[i]);
    if (p == null) continue;
    try
    {
      if (c.get(p) instanceof javax.baja.naming.BOrd)
      {
        c.set(p, ord);
        return true;
      }
    }
    catch (Throwable ignore) {}
  }
  return false;
}

private String slotNames(javax.baja.sys.BComplex c)
{
  StringBuffer b = new StringBuffer();
  try
  {
    javax.baja.sys.Property[] ps = c.getPropertiesArray();
    for (int i = 0; i < ps.length; i++)
    {
      if (b.length() > 0) b.append(", ");
      javax.baja.sys.BValue v = c.get(ps[i]);
      b.append(ps[i].getName()).append(":")
       .append(v != null ? v.getType().toString() : "null");
    }
  }
  catch (Throwable t)
  {
    b.append("? ").append(t);
  }
  return b.toString();
}

private String methodNames(Object o)
{
  java.util.TreeSet s = new java.util.TreeSet();
  java.lang.reflect.Method[] ms = o.getClass().getMethods();
  for (int i = 0; i < ms.length; i++)
  {
    String n = ms[i].getName();
    if (n.toLowerCase().indexOf("send") >= 0 || n.toLowerCase().indexOf("mail") >= 0)
    {
      Class[] p = ms[i].getParameterTypes();
      StringBuffer sig = new StringBuffer(n).append("(");
      for (int k = 0; k < p.length; k++)
      {
        if (k > 0) sig.append(",");
        sig.append(p[k].getSimpleName());
      }
      s.add(sig.append(")").toString());
    }
  }
  return s.isEmpty() ? "(none with send/mail in the name)" : s.toString();
}

private String actionNames(javax.baja.sys.BComponent c)
{
  StringBuffer b = new StringBuffer();
  try
  {
    javax.baja.sys.Action[] as = c.getActionsArray();
    for (int i = 0; i < as.length; i++)
    {
      if (b.length() > 0) b.append(", ");
      b.append(as[i].getName());
    }
  }
  catch (Throwable t)
  {
    b.append("? ").append(t);
  }
  return b.length() == 0 ? "(none)" : b.toString();
}

// "BXXXXX: a@x.com; b@x.com" per line -> {BXXXXX -> "a@x.com;b@x.com"}
private java.util.Map parseEmailList(String text)
{
  java.util.HashMap m = new java.util.HashMap();
  if (text == null) return m;
  String[] lines = text.split("\\r?\\n");
  for (int i = 0; i < lines.length; i++)
  {
    String l = lines[i].trim();
    if (l.length() == 0 || l.startsWith("#")) continue;
    int c = l.indexOf(':');
    if (c <= 0) continue;
    String b = l.substring(0, c).trim().toUpperCase();
    String[] addrs = l.substring(c + 1).split("[;,]");
    StringBuffer to = new StringBuffer();
    for (int k = 0; k < addrs.length; k++)
    {
      String a = addrs[k].trim();
      if (a.length() == 0) continue;
      if (to.length() > 0) to.append(";");
      to.append(a);
    }
    if (to.length() > 0) m.put(b, to.toString());
  }
  return m;
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

private int runHourValue()
{
  int h = getRunHour();
  return (h >= 0 && h <= 23) ? h : 6;
}

private double bandValue()
{
  double b = getBand();
  return (b > 0) ? b : DEFAULT_BAND;
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

private String joinOrd(String folderOrd, String name)
{
  String f = folderOrd;
  while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
  return f + "/" + name;
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
