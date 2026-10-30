/*
================================================================================
Program: Mailer - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-30

Changes
-------
  v1.00  Initial release.

Purpose
-------
Sends one email through the station's EmailService with any number of
files from the station's Files attached. Use it on its own (fill in the
slots, click Send Now, or link a trigger to the send action), or let
another program drive it: set to / subject / body / files, then invoke
send.

Files (one per line, # = comment)
---------------------------------
  file:^Csv_Logs/Report.csv              one file
  ^Csv_Logs/Report.csv                   same ("file:" optional)
  file:^Csv_Logs/Monthly_Reports/*.csv   wildcard (* and ?) in the name
  file:^Csv_Logs/Monthly_Reports         a folder = every file in it
                                         (not sub-folders)
  Paths are from the top of the station's Files (station home):
  file:^BXXXXX*.csv only looks in that top folder, not sub-folders -
  use file:^SomeFolder/BXXXXX*.csv for files inside SomeFolder.
  Paths must be inside the station home folder. Leave files blank to
  send the body only (to 'to').

Recipients per file
-------------------
  Add "=> addresses" to a line to send that line's file(s) to those
  addresses instead of 'to'. Prefix an address with cc: or bcc: (no
  prefix = To):
    file:^Csv_Logs/Report BXXXXX.csv => a@company.com; cc: b@company.com
    file:^Csv_Logs/Report BYYYYY.csv => c@company.com; bcc: d@company.com
    file:^Csv_Logs/Summary.csv                   (no => : goes to 'to')
  Each line is one email to that line's To / Cc / Bcc. Lines with
  exactly the same recipients are combined into one email holding all
  their files. A line with only Cc addresses sends to them as To. The
  cc and bcc slots are added to every email. subject and body are the
  same on all.
  Nothing is sent if any line matches no file or has no To or Cc
  recipient (Bcc alone isn't enough).

Sender
------
  sender is a drop-down of the outgoing accounts configured under the
  EmailService, plus Default (the service's first outgoing account).
  The list is built on station start and by Refresh Accounts. Pick one
  and it stays picked; if that account is later removed, the send fails
  and status lists what is available.
  fromAddress sets the From address (blank = the account's default).

Recipients
----------
  to, cc, bcc: one or more addresses separated by ; or ,
  to is the default for file lines without =>.
  cc and bcc are added to every email that goes out.

Status
------
  First line starts with SENT, FAILED or DRY RUN so another program can
  check the result. Below it, one block per email: recipients, files,
  and sent / FAILED. FAILED on line 1 means at least one email failed
  (the others may have gone out - see the blocks). "SENT" means handed to the outgoing account's queue;
  if a mail never arrives, check that account's numberSent and
  lastDiscardCause.

Actions
-------
  Send Now          send the email
  Dry Run           show each email that would go out (recipients,
                    files, sizes); nothing is sent
  Refresh Accounts  rebuild the sender drop-down

Imports
-------
  Module email-rt, package javax.baja.email   (add on the Imports tab)
  Plus the predefined ones (javax.baja.sys, javax.baja.status, java.util).

Slot Sheet
----------
  Name             Type              Flags / Config
  ---------------  ----------------  ---------------------------------------
  files            baja:String       facets multiLine=true,fieldWidth=100
  to               baja:String
  cc               baja:String
  bcc              baja:String
  subject          baja:String
  body             baja:String       facets multiLine=true,fieldWidth=100
  sender           baja:DynamicEnum  (list filled in by the program)
  fromAddress      baja:String
  status           baja:String       Read Only,
                                     facets multiLine=true,fieldWidth=110
  lastSent         baja:String       Read Only
  version          baja:String       Read Only
  quickGuide       baja:String       Read Only,
                                     facets multiLine=true,fieldWidth=75
  send             Action            Display Name "Send Now"
  dryRun           Action            Display Name "Dry Run - No Send"
  refreshAccounts  Action            Display Name "Refresh Accounts"
================================================================================
*/

private static final String VERSION = "v1.00";
private static final String DEFAULT_TAG = "Default";

private static final String QUICK_GUIDE =
  "Mailer " + "v1.00\n" +
  "=====================================\n" +
  "\n" +
  "Emails files from the station's Files through the EmailService.\n" +
  "\n" +
  "files, one per line:\n" +
  "  file:^Csv_Logs/Report.csv             one file\n" +
  "  file:^Csv_Logs/Monthly_Reports/*.csv  wildcard\n" +
  "  file:^Csv_Logs/Monthly_Reports        whole folder\n" +
  "Per-file recipients: add => addresses\n" +
  "  file:^Csv_Logs/Report BXXXXX.csv => a@x.com; cc: b@x.com\n" +
  "  prefixes cc: / bcc: (none = To). No => : goes to 'to'.\n" +
  "  One email per line; same recipients = one email.\n" +
  "\n" +
  "to / cc / bcc: addresses separated by ; or ,\n" +
  "cc and bcc slots go on every email.\n" +
  "sender:  outgoing account (Default = first one)\n" +
  "fromAddress: blank = account default\n" +
  "\n" +
  "Actions:\n" +
  "  Send Now          send it\n" +
  "  Dry Run           show what would be sent, send nothing\n" +
  "  Refresh Accounts  rebuild the sender list\n" +
  "\n" +
  "status line 1 starts with SENT / FAILED / DRY RUN.\n";

public void onStart() throws Exception
{
  setVersion(VERSION);
  setQuickGuide(QUICK_GUIDE);
  refreshSenderList();
}

public void onExecute() throws Exception
{
}

public void onStop() throws Exception
{
}

// ----------------------------------------------------
// Actions
// ----------------------------------------------------

public void onSend() throws Exception
{
  setStatus(doSend(false));
}

public void onDryRun() throws Exception
{
  setStatus(doSend(true));
}

public void onRefreshAccounts() throws Exception
{
  setStatus("[" + now() + "] Accounts: " + refreshSenderList());
}

// ----------------------------------------------------
// Send
// ----------------------------------------------------

// Each files line is one email to that line's recipients (=> list, or
// 'to' if none). Lines with exactly the same recipients are combined
// into one email holding all their files. The cc / bcc slots are added
// to every email. Returns the status text; line 1 starts with SENT,
// FAILED or DRY RUN. Nothing is sent if any line matches no file or has
// no To / Cc recipient.
private String doSend(boolean dry)
{
  String stamp = "[" + now() + "]";

  BEmailService svc = service();
  if (svc == null) return "FAILED " + stamp + " EmailService not found in this station";

  BOutgoingAccount acct = pickAccount(svc);
  if (acct == null)
    return "FAILED " + stamp + " sender '" + senderTag() + "' not found; available: " +
      accountNames(svc) + " (try Refresh Accounts)";

  java.util.ArrayList ccAll = addresses(getCc());
  java.util.ArrayList bccAll = addresses(getBcc());

  // recipients key -> {To, Cc, Bcc}; recipients key -> files
  java.util.LinkedHashMap groups = new java.util.LinkedHashMap();
  java.util.HashMap groupFiles = new java.util.HashMap();
  java.util.ArrayList problems = new java.util.ArrayList();

  String text = getFiles();
  String[] lines = text == null ? new String[0] : text.split("\\r?\\n");
  for (int i = 0; i < lines.length; i++)
  {
    String line = lines[i].trim();
    if (line.length() == 0 || line.startsWith("#")) continue;

    String spec = line;
    String rcpt = null;
    int arrow = line.indexOf("=>");
    if (arrow >= 0)
    {
      spec = line.substring(0, arrow).trim();
      rcpt = line.substring(arrow + 2).trim();
    }
    if (rcpt == null || rcpt.length() == 0) rcpt = getTo();

    java.util.ArrayList[] r = parseRecipients(rcpt);
    if (r[0].isEmpty() && r[1].isEmpty())
    {
      problems.add("no To or Cc recipient for: " + spec +
        " (add => addresses or fill in to)");
      continue;
    }

    java.util.ArrayList found = resolveSpec(spec);
    if (found.isEmpty())
    {
      problems.add("not found: " + spec);
      continue;
    }

    String key = recipientKey(r);
    java.util.ArrayList fl = (java.util.ArrayList) groupFiles.get(key);
    if (fl == null)
    {
      fl = new java.util.ArrayList();
      groups.put(key, r);
      groupFiles.put(key, fl);
    }
    for (int f = 0; f < found.size(); f++)
      if (!fl.contains(found.get(f))) fl.add(found.get(f));
  }

  if (!problems.isEmpty())
    return "FAILED " + stamp + " nothing sent\n" + join(problems, "\n");

  // No file lines at all: one body-only email to 'to'.
  if (groups.isEmpty())
  {
    java.util.ArrayList[] r = parseRecipients(getTo());
    if (r[0].isEmpty() && r[1].isEmpty())
      return "FAILED " + stamp + " no files and no valid address in 'to'";
    groups.put("", r);
    groupFiles.put("", new java.util.ArrayList());
  }

  StringBuffer detail = new StringBuffer();
  detail.append("Account: ").append(acct.getName());
  if (!ccAll.isEmpty()) detail.append("  Cc (all): ").append(join(ccAll, "; "));
  if (!bccAll.isEmpty()) detail.append("  Bcc (all): ").append(join(bccAll, "; "));
  detail.append("\nSubject: ").append(getSubject()).append("\n");

  int sent = 0;
  int failed = 0;
  int n = 0;
  java.util.Iterator gi = groups.keySet().iterator();
  while (gi.hasNext())
  {
    String key = (String) gi.next();
    java.util.ArrayList[] r = (java.util.ArrayList[]) groups.get(key);
    java.util.ArrayList fl = (java.util.ArrayList) groupFiles.get(key);
    n++;

    // A line with only Cc addresses: they become the To line.
    java.util.ArrayList to = r[0].isEmpty() ? r[1] : r[0];
    java.util.ArrayList lineCc = r[0].isEmpty() ? new java.util.ArrayList() : r[1];
    java.util.ArrayList cc = merge(lineCc, ccAll, to);
    java.util.ArrayList bcc = merge(r[2], bccAll, merge(to, cc, null));

    detail.append("Email ").append(n).append(" To: ").append(join(to, "; "));
    if (!cc.isEmpty()) detail.append("  Cc: ").append(join(cc, "; "));
    if (!bcc.isEmpty()) detail.append("  Bcc: ").append(join(bcc, "; "));
    detail.append("\n");
    long total = 0;
    for (int i = 0; i < fl.size(); i++)
    {
      java.io.File f = (java.io.File) fl.get(i);
      total += f.length();
      detail.append("  ").append(relPath(f)).append(" (").append(kb(f.length())).append(")\n");
    }
    detail.append("  ").append(fl.size()).append(" file(s), ").append(kb(total));

    if (dry)
    {
      detail.append("\n");
      continue;
    }

    String err = sendOne(acct, to, cc, bcc, fl);
    if (err == null)
    {
      sent++;
      detail.append(" - sent\n");
    }
    else
    {
      failed++;
      detail.append(" - FAILED: ").append(err).append("\n");
    }
  }

  if (dry)
    return "DRY RUN " + stamp + " nothing sent, " + n + " email(s) would go out\n" + detail;

  if (sent > 0) setLastSent(now());
  if (failed == 0)
    return "SENT " + stamp + " " + sent + " email(s)\n" + detail;
  return "FAILED " + stamp + " " + failed + " of " + n + " email(s) failed, " +
    sent + " sent\n" + detail;
}

// Same To/Cc/Bcc sets (order and case ignored) -> same key.
private String recipientKey(java.util.ArrayList[] r)
{
  StringBuffer b = new StringBuffer();
  for (int k = 0; k < 3; k++)
  {
    java.util.ArrayList l = new java.util.ArrayList();
    for (int i = 0; i < r[k].size(); i++) l.add(((String) r[k].get(i)).toLowerCase());
    java.util.Collections.sort(l);
    b.append(k).append(':').append(join(l, ",")).append('|');
  }
  return b.toString();
}

// Sends one email. Null on success, else the reason.
private String sendOne(BOutgoingAccount acct, java.util.ArrayList to,
                       java.util.ArrayList cc, java.util.ArrayList bcc,
                       java.util.ArrayList files)
{
  try
  {
    BEmail email = new BEmail();
    email.setTo(addressList(join(to, ";")));
    if (!cc.isEmpty()) email.setCc(addressList(join(cc, ";")));
    if (!bcc.isEmpty()) email.setBcc(addressList(join(bcc, ";")));
    String from = getFromAddress();
    if (from != null && from.trim().length() > 0)
      email.setFrom(BEmailAddress.make(from.trim()));
    email.setSubject(getSubject() == null ? "" : getSubject());
    email.setDate(BAbsTime.now());

    BTextPart text = new BTextPart();
    text.setText(getBody() == null ? "" : getBody());
    email.setBody(text);

    for (int i = 0; i < files.size(); i++)
    {
      java.io.File f = (java.io.File) files.get(i);
      byte[] bytes = readBytes(f);
      if (bytes == null) return "can't read " + f.getName();
      BBlobPart part = new BBlobPart();
      part.setBlob(BBlob.make(bytes));
      part.setFileName(f.getName());
      part.setMimeType(mimeOf(f.getName()));
      email.getAttachments().add("attachment?", part);
    }

    acct.send(email);
    return null;
  }
  catch (Throwable t)
  {
    return t.toString();
  }
}

// "a@x.com; cc: b@x.com; bcc: c@x.com" -> {To, Cc, Bcc}. No prefix =
// To. An address listed twice keeps its strongest role (To > Cc > Bcc).
// Only addresses that parse are kept.
private java.util.ArrayList[] parseRecipients(String s)
{
  java.util.ArrayList[] r = { new java.util.ArrayList(),
    new java.util.ArrayList(), new java.util.ArrayList() };
  if (s == null) return r;
  java.util.LinkedHashMap best = new java.util.LinkedHashMap();  // lower -> {addr, role}
  String[] parts = s.split("[;,]");
  for (int i = 0; i < parts.length; i++)
  {
    String a = parts[i].trim();
    int role = 0;
    String low = a.toLowerCase();
    if (low.startsWith("bcc:"))     { role = 2; a = a.substring(4).trim(); }
    else if (low.startsWith("cc:")) { role = 1; a = a.substring(3).trim(); }
    else if (low.startsWith("to:")) { role = 0; a = a.substring(3).trim(); }
    if (a.length() == 0) continue;
    try
    {
      BEmailAddress.make(a);
    }
    catch (Throwable t)
    {
      continue;
    }
    Object[] had = (Object[]) best.get(a.toLowerCase());
    if (had == null || role < ((Integer) had[1]).intValue())
      best.put(a.toLowerCase(), new Object[]{ a, new Integer(role) });
  }
  java.util.Iterator it = best.values().iterator();
  while (it.hasNext())
  {
    Object[] e = (Object[]) it.next();
    r[((Integer) e[1]).intValue()].add(e[0]);
  }
  return r;
}

// a + b (in that order), skipping any already in a, in b twice, or in
// exclude. Case ignored.
private java.util.ArrayList merge(java.util.ArrayList a, java.util.ArrayList b,
                                  java.util.ArrayList exclude)
{
  java.util.ArrayList out = new java.util.ArrayList();
  java.util.HashSet seen = new java.util.HashSet();
  if (exclude != null)
    for (int i = 0; i < exclude.size(); i++) seen.add(((String) exclude.get(i)).toLowerCase());
  java.util.ArrayList[] src = { a, b };
  for (int s = 0; s < 2; s++)
    for (int i = 0; i < src[s].size(); i++)
    {
      String v = (String) src[s].get(i);
      if (seen.add(v.toLowerCase())) out.add(v);
    }
  return out;
}

// "a@x.com; b@x.com" -> ["a@x.com", "b@x.com"], only ones that parse.
private java.util.ArrayList addresses(String s)
{
  java.util.ArrayList out = new java.util.ArrayList();
  if (s == null) return out;
  String[] parts = s.split("[;,]");
  for (int i = 0; i < parts.length; i++)
  {
    String a = parts[i].trim();
    if (a.length() == 0) continue;
    try
    {
      BEmailAddress.make(a);
      out.add(a);
    }
    catch (Throwable ignore) {}
  }
  return out;
}

// ----------------------------------------------------
// Sender drop-down
// ----------------------------------------------------

private BEmailService service()
{
  try
  {
    return (BEmailService) Sys.getService(BEmailService.TYPE);
  }
  catch (Throwable t)
  {
    return null;
  }
}

// Rebuilds sender's range from the outgoing accounts, keeping the current
// pick if that account still exists. Returns the account list.
private String refreshSenderList()
{
  java.util.ArrayList tags = new java.util.ArrayList();
  tags.add(DEFAULT_TAG);

  BEmailService svc = service();
  if (svc != null)
  {
    javax.baja.sys.BComponent[] kids = svc.getChildComponents();
    for (int i = 0; i < kids.length; i++)
      if (kids[i] instanceof BOutgoingAccount) tags.add(kids[i].getName());
  }

  int[] ords = new int[tags.size()];
  String[] names = new String[tags.size()];
  for (int i = 0; i < tags.size(); i++)
  {
    ords[i] = i;
    names[i] = (String) tags.get(i);
  }

  String cur = senderTag();
  int sel = 0;
  for (int i = 0; i < names.length; i++)
    if (names[i].equals(cur)) sel = i;

  try
  {
    setSender(BDynamicEnum.make(sel, BEnumRange.make(ords, names)));
  }
  catch (Throwable ignore) {}

  if (svc == null) return "EmailService not found";
  return names.length > 1 ? join(tags, ", ") : "no outgoing accounts configured";
}

// Tag currently picked in sender ("Default" if nothing usable).
private String senderTag()
{
  try
  {
    BDynamicEnum e = getSender();
    if (e != null)
    {
      String t = e.getTag();
      if (t != null && t.length() > 0) return t;
    }
  }
  catch (Throwable ignore) {}
  return DEFAULT_TAG;
}

private BOutgoingAccount pickAccount(BEmailService svc)
{
  String want = senderTag();
  if (want.equals(DEFAULT_TAG)) return svc.getDefaultOutgoingAccount();

  javax.baja.sys.BComponent[] kids = svc.getChildComponents();
  for (int i = 0; i < kids.length; i++)
    if (kids[i] instanceof BOutgoingAccount && want.equals(kids[i].getName()))
      return (BOutgoingAccount) kids[i];
  return null;
}

private String accountNames(BEmailService svc)
{
  java.util.ArrayList n = new java.util.ArrayList();
  javax.baja.sys.BComponent[] kids = svc.getChildComponents();
  for (int i = 0; i < kids.length; i++)
    if (kids[i] instanceof BOutgoingAccount) n.add(kids[i].getName());
  return n.isEmpty() ? "(none)" : join(n, ", ");
}

// ----------------------------------------------------
// Files
// ----------------------------------------------------

// One file spec -> matching files, sorted by name. Empty if nothing
// matches. Spec forms: a file, a name with * / ? wildcards, or a folder.
private java.util.ArrayList resolveSpec(String spec)
{
  java.util.ArrayList found = new java.util.ArrayList();
  String p = stripScheme(spec);
  int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
  String name = slash >= 0 ? p.substring(slash + 1) : p;

  if (name.indexOf('*') >= 0 || name.indexOf('?') >= 0)
  {
    java.io.File dir = resolveToFile(slash >= 0 ? p.substring(0, slash) : "");
    java.util.regex.Pattern g = glob(name);
    java.io.File[] ls = (dir != null && dir.isDirectory()) ? dir.listFiles() : null;
    if (ls != null)
      for (int k = 0; k < ls.length; k++)
        if (ls[k].isFile() && g.matcher(ls[k].getName()).matches()) found.add(ls[k]);
  }
  else
  {
    java.io.File f = resolveToFile(p);
    if (f != null && f.isFile()) found.add(f);
    else if (f != null && f.isDirectory())
    {
      java.io.File[] ls = f.listFiles();
      if (ls != null)
        for (int k = 0; k < ls.length; k++)
          if (ls[k].isFile()) found.add(ls[k]);
    }
  }
  sortByName(found);
  return found;
}

// File -> path from the station home folder, with / separators.
private String relPath(java.io.File f)
{
  try
  {
    String home = javax.baja.sys.Sys.getStationHome().getCanonicalPath();
    String p = f.getCanonicalPath();
    if (p.startsWith(home)) p = p.substring(home.length());
    p = p.replace('\\', '/');
    while (p.startsWith("/")) p = p.substring(1);
    return p;
  }
  catch (Exception e)
  {
    return f.getName();
  }
}

// "local:|file:^a/b", "file:^a/b", "^a/b" or "a/b" -> "a/b"
// (station-relative).
private String stripScheme(String s)
{
  String p = s.trim();
  int bar = p.lastIndexOf('|');
  if (bar >= 0) p = p.substring(bar + 1).trim();   // local:|file:^... -> file:^...
  if (p.startsWith("file:")) p = p.substring(5);
  if (p.startsWith("^")) p = p.substring(1);
  while (p.startsWith("/") || p.startsWith("\\")) p = p.substring(1);
  return p;
}

// "*.csv" -> regex, case-insensitive.
private java.util.regex.Pattern glob(String g)
{
  StringBuffer b = new StringBuffer();
  for (int i = 0; i < g.length(); i++)
  {
    char c = g.charAt(i);
    if (c == '*') b.append(".*");
    else if (c == '?') b.append('.');
    else b.append(java.util.regex.Pattern.quote(String.valueOf(c)));
  }
  return java.util.regex.Pattern.compile(b.toString(),
    java.util.regex.Pattern.CASE_INSENSITIVE);
}

private void sortByName(java.util.ArrayList list)
{
  for (int i = 1; i < list.size(); i++)
  {
    Object key = list.get(i);
    String kn = ((java.io.File) key).getName().toLowerCase();
    int j = i - 1;
    while (j >= 0 && ((java.io.File) list.get(j)).getName().toLowerCase().compareTo(kn) > 0)
    {
      list.set(j + 1, list.get(j));
      j--;
    }
    list.set(j + 1, key);
  }
}

private String mimeOf(String name)
{
  String n = name.toLowerCase();
  if (n.endsWith(".csv"))  return "text/csv";
  if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
  if (n.endsWith(".htm") || n.endsWith(".html")) return "text/html";
  if (n.endsWith(".pdf"))  return "application/pdf";
  if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  if (n.endsWith(".xls"))  return "application/vnd.ms-excel";
  if (n.endsWith(".json")) return "application/json";
  if (n.endsWith(".xml"))  return "application/xml";
  if (n.endsWith(".png"))  return "image/png";
  if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
  if (n.endsWith(".zip"))  return "application/zip";
  return "application/octet-stream";
}

private byte[] readBytes(java.io.File f)
{
  java.io.InputStream in = null;
  try
  {
    in = new java.io.FileInputStream(f);
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int r;
    while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
    return bo.toByteArray();
  }
  catch (Exception e)
  {
    return null;
  }
  finally
  {
    if (in != null) try { in.close(); } catch (Exception ignore) {}
  }
}

// Station-relative path -> file under station home. Paths outside the
// station home folder are refused (null).
private java.io.File resolveToFile(String rel)
{
  try
  {
    java.io.File home = javax.baja.sys.Sys.getStationHome().getCanonicalFile();
    java.io.File f = new java.io.File(rel);
    if (!f.isAbsolute()) f = new java.io.File(home, rel);
    java.io.File canon = f.getCanonicalFile();
    if (canon.equals(home) ||
        canon.getPath().startsWith(home.getPath() + java.io.File.separator))
      return canon;
  }
  catch (Exception ignore) {}
  return null;
}

// ----------------------------------------------------
// Helpers
// ----------------------------------------------------

// "a@x.com; b@x.com" -> address list. Null if no address parses.
private BEmailAddressList addressList(String s)
{
  if (s == null) return null;
  String[] parts = s.split("[;,]");
  java.util.ArrayList list = new java.util.ArrayList();
  for (int i = 0; i < parts.length; i++)
  {
    String a = parts[i].trim();
    if (a.length() == 0) continue;
    try
    {
      list.add(BEmailAddress.make(a));
    }
    catch (Throwable ignore) {}
  }
  if (list.isEmpty()) return null;
  return BEmailAddressList.make(
    (BEmailAddress[]) list.toArray(new BEmailAddress[list.size()]));
}

private String clean(String s)
{
  return s == null ? "" : s.trim();
}

private String join(java.util.List l, String sep)
{
  StringBuffer b = new StringBuffer();
  for (int i = 0; i < l.size(); i++)
  {
    if (i > 0) b.append(sep);
    b.append(String.valueOf(l.get(i)));
  }
  return b.toString();
}

private String kb(long bytes)
{
  if (bytes < 1024) return bytes + " B";
  return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
}

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}
