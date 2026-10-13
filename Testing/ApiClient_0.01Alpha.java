/*
 * =====================================================================================
 *  ApiClient v0.01Alpha -  HTTP/REST JSON client Program Object  (Niagara 4.15.2)
 * =====================================================================================
 *  One draggable component per API request, built
 *  entirely from the property sheet. GET / POST / PUT / DELETE / PATCH* / HEAD,
 *  Basic or Bearer auth, custom headers, request body, built-in JSON parser,
 *  up to 10 path-selected outputs, and request chaining through var1..var4.
 *
 *  Paste the code below into the Program editor. Imports, getters and setters are
 *  NOT included (the editor generates them) - add the imports and slots listed here.
 *
 *  ---------------------------------------------------------------- IMPORTS ----------
 *    javax.baja.sys.*
 *    javax.baja.status.*
 *    java.io.*
 *    java.net.*
 *    java.nio.charset.*
 *    java.text.*
 *    java.util.*
 *    java.util.regex.*
 *    java.util.zip.*
 *
 *  ---------------------------------------------------------------- SLOT SHEET -------
 *  Name              Type                 Default   Flags        Notes
 *  ----------------  -------------------  --------  -----------  ----------------------
 *  tick              (as your other periodic objects; link from CurrentTime, Execute On Change)
 *  enabled           baja:Boolean         true
 *  pollSecs          baja:Integer         300                    min 5
 *  method            baja:String          GET                    GET POST PUT DELETE PATCH HEAD
 *  url               baja:String                                 tokens allowed
 *  headers           baja:String                                 "Name: value|Name2: value2"
 *  body              baja:String                                 tokens allowed
 *  authType          baja:String          none                   none | basic | bearer
 *  username          baja:String                                 basic only
 *  secret            baja:String                                 basic password / bearer token
 *  var1..var4        baja:StatusString                           linkable chain inputs
 *  chainOnVarChange  baja:Boolean         true                   fire immediately when a var changes
 *  timeoutSecs       baja:Integer         15                     1..120 (connect and read each)
 *  numChannels       baja:Integer         10                     0..10 channels evaluated
 *  stripUnits        baja:Boolean         true                   "17.4° C" -> 17.4 on numN
 *  discover          baja:Boolean         false                  lists every JSON path in 'discovered'
 *  maxBodyChars      baja:Integer         4000                   0 = don't write 'response'
 *  async             baja:Boolean         true                   run request off the engine thread
 *  path1..path10     baja:String                                 JSON path per channel
 *  num1..num10       baja:StatusNumeric             r, t         numeric value of pathN
 *  str1..str10       baja:StatusString              r, t         text value of pathN
 *  httpStatus        baja:StatusNumeric             r, t
 *  ok                baja:StatusBoolean             r, t
 *  error             baja:StatusString              r, t
 *  response          baja:StatusString              r, t         raw body (truncated)
 *  discovered        baja:StatusString              r, t
 *  lastOk            baja:AbsTime                   r
 *  lastRun           baja:AbsTime                   r            NOT transient (poll timer state)
 *  lastVars          baja:String                    r, h         NOT transient (chain state)
 *  busy              baja:Boolean         false     r, t, h      transient so a restart can't lock it
 *  run               Action (void)                               manual fire, ignores poll timer
 *
 *  (r = readonly, t = transient, h = hidden.  Transient on outputs keeps big strings out of the bog.)
 *
 *  ---------------------------------------------------------------- TOKENS -----------
 *  Usable in url, headers, body, username, secret:
 *    {var1}..{var4}    value of the linked varN input
 *    {epochMs}         current time, epoch milliseconds
 *    {epochS}          current time, epoch seconds
 *    {isoNow}          current time UTC, yyyy-MM-ddTHH:mm:ssZ
 *  Append |url to URL-encode, e.g.  ?q={var1|url}
 *  A request that references {varN} waits (error = "Waiting for varN") until varN
 *  is non-empty and not null/fault/down.
 *
 *  ---------------------------------------------------------------- PATH SYNTAX ------
 *    $                              whole document (or whole body if not JSON)
 *    data.temp                      object member
 *    items[0].name / items[-1]      array index (negative = from end)
 *    Result[SensorID=123].Reading   first array element whose SensorID == 123
 *    Result[?(@.SensorID==123)]     same, JSONPath filter style also accepted
 *    ['key.with.dots']              bracket key
 *    items.#   or  items[#]         array length / object member count
 *  numN: number, true/false -> 1/0, numeric string, or first number in the string when
 *  stripUnits = true. Not numeric -> null status. strN: text; objects/arrays as compact JSON.
 *  Path not found or JSON null -> both outputs null status.
 *  Request failure -> last values kept with stale status, ok = false, error set.
 *
 *  ---------------------------------------------------------------- NOTES ------------
 *  * PATCH is sent as POST with X-HTTP-Method-Override: PATCH (HttpURLConnection limit).
 *  * HTTPS: approve the remote cert in Certificate Management > Allowed Hosts on first use.
 *  * username/secret are baja:String and are stored in plain text in the bog.
 *  * Responses over 2 MB are rejected.
 * =====================================================================================
 */

// ------------------------------------------------------------------ constants -------
private static final int     MAX_CH             = 10;
private static final int     MAX_RESP_BYTES     = 2 * 1024 * 1024;
private static final int     MAX_STR_CHARS      = 4096;
private static final int     MAX_DISCOVER_LINES = 300;
private static final Object  JNULL              = new Object();
private static final Object  MISSING            = new Object();
private static final Pattern TOKEN     = Pattern.compile("\\{(var[1-4]|epochMs|epochS|isoNow)(\\|url)?\\}");
private static final Pattern NUMBER    = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");
private static final Pattern HDR_SPLIT = Pattern.compile("\\r?\\n|\\|");
private static final Pattern INT_IDX   = Pattern.compile("-?\\d+");
private static final Pattern SAFE_KEY  = Pattern.compile("[A-Za-z0-9_\\-]+");

// ------------------------------------------------------------------ entry points ----
public void onStart() throws Exception
{
  setBusy(false);
}

public void onExecute() throws Exception
{
  trigger(false);
}

public void doRun() throws Exception
{
  trigger(true);
}

// ------------------------------------------------------------------ scheduling ------
// Runs on the engine thread: validates config, snapshots everything the request needs,
// then hands off to perform() on a worker thread (or inline if threads are refused).
private void trigger(boolean force)
{
  if (!getEnabled() && !force) return;

  final long now = System.currentTimeMillis();
  final int timeoutSecs = clamp(getTimeoutSecs(), 1, 120);

  // in-flight guard, with expiry in case a worker died
  if (getBusy())
  {
    long since = now - getLastRun().getMillis();
    if (since < (timeoutSecs * 2L + 10L) * 1000L) return;
    setBusy(false);
  }

  // chain inputs
  final BStatusString[] vs = { getVar1(), getVar2(), getVar3(), getVar4() };
  final String[] vars = new String[4];
  StringBuilder snap = new StringBuilder();
  for (int i = 0; i < 4; i++)
  {
    vars[i] = (vs[i] == null) ? "" : nz(vs[i].getValue());
    snap.append(vars[i]).append('|');
  }
  final String varHash = Integer.toHexString(snap.toString().hashCode());

  if (!force)
  {
    boolean due = now - getLastRun().getMillis() >= Math.max(5, getPollSecs()) * 1000L;
    boolean varsChanged = getChainOnVarChange() && !varHash.equals(nz(getLastVars()));
    if (!due && !varsChanged) return;
  }

  // referenced vars must be usable
  String template = nz(getUrl()) + "\n" + nz(getHeaders()) + "\n" + nz(getBody()) + "\n"
                  + nz(getUsername()) + "\n" + nz(getSecret());
  for (int i = 0; i < 4; i++)
  {
    if (template.indexOf("{var" + (i + 1)) >= 0 && !usable(vs[i]))
    {
      setErr("Waiting for var" + (i + 1));
      return;
    }
  }

  // channel slots
  final BComponent comp = getComponent();
  final int n = clamp(getNumChannels(), 0, MAX_CH);
  final String[] paths = new String[n];
  for (int i = 1; i <= n; i++)
  {
    if (comp.getSlot("path" + i) == null || comp.getSlot("num" + i) == null || comp.getSlot("str" + i) == null)
    {
      setErr("Missing slot path" + i + "/num" + i + "/str" + i + " (numChannels=" + n + ")");
      return;
    }
    paths[i - 1] = sval(comp.get("path" + i));
  }

  // method
  String m = nz(getMethod()).trim().toUpperCase();
  final String method = m.isEmpty() ? "GET" : m;
  if (!method.matches("GET|POST|PUT|DELETE|PATCH|HEAD"))
  {
    setErr("Unsupported method: " + method);
    return;
  }

  // url / headers / body
  final String url = subst(nz(getUrl()).trim(), vars);
  if (!url.matches("(?i)https?://.+"))
  {
    setErr("url must start with http:// or https://");
    return;
  }
  final String headers = subst(nz(getHeaders()), vars);
  final String body    = subst(nz(getBody()), vars);

  // auth
  String at = nz(getAuthType()).trim().toLowerCase();
  String a = null;
  if (at.equals("basic"))
  {
    String cred = subst(nz(getUsername()), vars) + ":" + subst(nz(getSecret()), vars);
    a = "Basic " + java.util.Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
  }
  else if (at.equals("bearer"))
  {
    a = "Bearer " + subst(nz(getSecret()), vars).trim();
  }
  else if (!at.isEmpty() && !at.equals("none"))
  {
    setErr("authType must be none, basic or bearer");
    return;
  }
  final String auth = a;

  final int     timeoutMs = timeoutSecs * 1000;
  final boolean strip     = getStripUnits();
  final boolean discover  = getDiscover();
  final int     maxChars  = Math.max(0, getMaxBodyChars());

  // commit state before launching
  setBusy(true);
  setLastRun(BAbsTime.now());
  if (!varHash.equals(nz(getLastVars()))) setLastVars(varHash);

  Runnable job = new Runnable()
  {
    public void run()
    {
      perform(comp, method, url, headers, body, auth, timeoutMs, paths, strip, discover, maxChars);
    }
  };

  if (getAsync())
  {
    try
    {
      Thread t = new Thread(job, "ApiClient:" + comp.getName());
      t.setDaemon(true);
      t.start();
      return;
    }
    catch (Throwable e)
    {
      // thread creation refused - fall through and run inline
    }
  }
  job.run();
}

// ------------------------------------------------------------------ request ---------
// Worker side. Only touches the component through set(), never through Program fields.
private static void perform(BComponent c, String method, String url, String headers, String body,
                            String auth, int timeoutMs, String[] paths, boolean strip,
                            boolean discover, int maxChars)
{
  int n = paths.length;
  try
  {
    int[] code = new int[1];
    String text = http(method, url, headers, body, auth, timeoutMs, code);

    put(c, "httpStatus", new BStatusNumeric(code[0], BStatus.ok));
    if (maxChars > 0) put(c, "response", new BStatusString(trunc(text, maxChars), BStatus.ok));

    if (code[0] < 200 || code[0] > 299)
    {
      fail(c, n, "HTTP " + code[0] + ": " + trunc(oneLine(text), 200));
      return;
    }

    Object root;
    String t = text.trim();
    if (t.startsWith("{") || t.startsWith("["))
    {
      try { root = parseJson(t); }
      catch (Exception e)
      {
        fail(c, n, "JSON parse error: " + e.getMessage());
        return;
      }
    }
    else
    {
      root = t;   // non-JSON body: reachable with path "$"
    }

    for (int i = 0; i < n; i++) writeChannel(c, i + 1, root, paths[i], strip);

    if (discover)
    {
      StringBuilder sb = new StringBuilder();
      int[] lines = { 0 };
      flatten(root, "", sb, lines);
      if (lines[0] >= MAX_DISCOVER_LINES)
        sb.append("... truncated at ").append(MAX_DISCOVER_LINES).append(" lines");
      put(c, "discovered", new BStatusString(sb.toString(), BStatus.ok));
    }

    put(c, "ok", new BStatusBoolean(true, BStatus.ok));
    put(c, "error", new BStatusString("", BStatus.ok));
    put(c, "lastOk", BAbsTime.now());
  }
  catch (Throwable e)
  {
    put(c, "httpStatus", new BStatusNumeric(0, BStatus.fault));
    fail(c, n, describe(e));
  }
  finally
  {
    put(c, "busy", BBoolean.make(false));
  }
}

private static String http(String method, String url, String headers, String body,
                           String auth, int timeoutMs, int[] code) throws Exception
{
  HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
  try
  {
    con.setConnectTimeout(timeoutMs);
    con.setReadTimeout(timeoutMs);
    con.setUseCaches(false);
    con.setInstanceFollowRedirects(true);

    String m = method;
    if (m.equals("PATCH"))
    {
      m = "POST";
      con.setRequestProperty("X-HTTP-Method-Override", "PATCH");
    }
    con.setRequestMethod(m);
    con.setRequestProperty("Accept", "application/json, */*;q=0.8");
    con.setRequestProperty("Accept-Encoding", "gzip");
    con.setRequestProperty("User-Agent", "Niagara-ApiClient/0.01Alpha");
    if (auth != null) con.setRequestProperty("Authorization", auth);

    // user headers last so they can override the defaults above
    boolean hasCt = false;
    String[] lines = HDR_SPLIT.split(headers);
    for (int i = 0; i < lines.length; i++)
    {
      String line = lines[i];
      int k = line.indexOf(':');
      if (k <= 0) continue;
      String name = line.substring(0, k).trim();
      String val  = line.substring(k + 1).trim();
      if (name.isEmpty()) continue;
      if (name.equalsIgnoreCase("Content-Type")) hasCt = true;
      con.setRequestProperty(name, val);
    }

    boolean send = body.length() > 0 && (m.equals("POST") || m.equals("PUT"));
    if (send)
    {
      if (!hasCt) con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      con.setDoOutput(true);
      con.setFixedLengthStreamingMode(out.length);
      OutputStream os = con.getOutputStream();
      try { os.write(out); }
      finally { os.close(); }
    }

    code[0] = con.getResponseCode();
    InputStream in = (code[0] >= 400) ? con.getErrorStream() : con.getInputStream();
    if (in == null) return "";
    try
    {
      if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int r;
      while ((r = in.read(b)) != -1)
      {
        if (buf.size() + r > MAX_RESP_BYTES)
          throw new IOException("Response larger than " + (MAX_RESP_BYTES / 1024) + " KB");
        buf.write(b, 0, r);
      }
      return new String(buf.toByteArray(), charsetOf(con.getContentType()));
    }
    finally
    {
      in.close();
    }
  }
  finally
  {
    con.disconnect();
  }
}

// ------------------------------------------------------------------ outputs ---------
private static void writeChannel(BComponent c, int i, Object root, String path, boolean strip)
{
  String sn = "num" + i;
  String ss = "str" + i;
  Object v = (path == null || path.trim().isEmpty()) ? MISSING : select(root, path.trim());

  if (v == MISSING || v == JNULL)
  {
    put(c, sn, new BStatusNumeric(0, BStatus.nullStatus));
    put(c, ss, new BStatusString("", BStatus.nullStatus));
    return;
  }

  put(c, ss, new BStatusString(trunc(asText(v), MAX_STR_CHARS), BStatus.ok));
  double d = asNumber(v, strip);
  put(c, sn, Double.isNaN(d) ? new BStatusNumeric(0, BStatus.nullStatus)
                             : new BStatusNumeric(d, BStatus.ok));
}

private static void fail(BComponent c, int n, String msg)
{
  put(c, "ok", new BStatusBoolean(false, BStatus.ok));
  put(c, "error", new BStatusString(msg, BStatus.ok));
  for (int i = 1; i <= n; i++)
  {
    BValue v = c.get("num" + i);
    if (v instanceof BStatusNumeric && !((BStatusNumeric) v).getStatus().isNull())
      put(c, "num" + i, new BStatusNumeric(((BStatusNumeric) v).getValue(), BStatus.stale));
    v = c.get("str" + i);
    if (v instanceof BStatusString && !((BStatusString) v).getStatus().isNull())
      put(c, "str" + i, new BStatusString(((BStatusString) v).getValue(), BStatus.stale));
  }
}

private static void put(BComponent c, String slot, BValue v)
{
  try { c.set(slot, v); }
  catch (Throwable ignore) { }
}

private void setErr(String msg)
{
  BStatusString cur = getError();
  if (cur == null || !msg.equals(cur.getValue())) setError(new BStatusString(msg, BStatus.ok));
  BStatusBoolean ok = getOk();
  if (ok == null || ok.getValue()) setOk(new BStatusBoolean(false, BStatus.ok));
}

// ------------------------------------------------------------------ path select -----
private static Object select(Object root, String path)
{
  try
  {
    String p = path;
    if (p.startsWith("$")) p = p.substring(1);
    Object cur = root;
    int i = 0;
    int len = p.length();
    while (i < len)
    {
      if (cur == MISSING || cur == JNULL) return MISSING;
      char ch = p.charAt(i);
      if (ch == '.') { i++; continue; }
      if (ch == '[')
      {
        int end = closeBracket(p, i);
        if (end < 0) return MISSING;
        cur = applyBracket(cur, p.substring(i + 1, end).trim());
        i = end + 1;
      }
      else
      {
        int j = i;
        while (j < len && p.charAt(j) != '.' && p.charAt(j) != '[') j++;
        String key = p.substring(i, j).trim();
        cur = key.equals("#") ? count(cur) : child(cur, key);
        i = j;
      }
    }
    return cur;
  }
  catch (Throwable e)
  {
    return MISSING;
  }
}

private static int closeBracket(String p, int open)
{
  char q = 0;
  for (int k = open + 1; k < p.length(); k++)
  {
    char ch = p.charAt(k);
    if (q != 0) { if (ch == q) q = 0; }
    else if (ch == '\'' || ch == '"') q = ch;
    else if (ch == ']') return k;
  }
  return -1;
}

private static Object applyBracket(Object cur, String in)
{
  if (in.equals("#")) return count(cur);
  if (isQuoted(in)) return child(cur, in.substring(1, in.length() - 1));
  if (INT_IDX.matcher(in).matches()) return index(cur, Integer.parseInt(in));

  String f = in;
  if (f.startsWith("?(") && f.endsWith(")")) f = f.substring(2, f.length() - 1).trim();
  int eq = f.indexOf('=');
  if (eq > 0)
  {
    String key = f.substring(0, eq).trim();
    String val = f.substring(eq + 1).trim();
    if (val.startsWith("=")) val = val.substring(1).trim();     // '=='
    if (key.startsWith("@.")) key = key.substring(2);
    if (isQuoted(val)) val = val.substring(1, val.length() - 1);
    if (!(cur instanceof List)) return MISSING;
    List<?> list = (List<?>) cur;
    for (int i = 0; i < list.size(); i++)
    {
      Object el = list.get(i);
      Object kv = child(el, key);
      if (kv != MISSING && kv != JNULL && asText(kv).equals(val)) return el;
    }
    return MISSING;
  }
  return child(cur, in);
}

private static boolean isQuoted(String s)
{
  return s.length() >= 2
      && ((s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'')
       || (s.charAt(0) == '"'  && s.charAt(s.length() - 1) == '"'));
}

private static Object child(Object cur, String key)
{
  if (cur instanceof Map)
  {
    Map<?, ?> m = (Map<?, ?>) cur;
    return m.containsKey(key) ? m.get(key) : MISSING;
  }
  if (cur instanceof List && INT_IDX.matcher(key).matches()) return index(cur, Integer.parseInt(key));
  return MISSING;
}

private static Object index(Object cur, int i)
{
  if (!(cur instanceof List)) return MISSING;
  List<?> l = (List<?>) cur;
  int k = (i < 0) ? l.size() + i : i;
  return (k >= 0 && k < l.size()) ? l.get(k) : MISSING;
}

private static Object count(Object cur)
{
  if (cur instanceof List) return Double.valueOf(((List<?>) cur).size());
  if (cur instanceof Map)  return Double.valueOf(((Map<?, ?>) cur).size());
  return MISSING;
}

// ------------------------------------------------------------------ conversions -----
private static String asText(Object v)
{
  if (v instanceof String)  return (String) v;
  if (v instanceof Double)  return fmt(((Double) v).doubleValue());
  if (v instanceof Boolean) return v.toString();
  if (v == JNULL)           return "null";
  StringBuilder sb = new StringBuilder();
  toJson(v, sb);
  return sb.toString();
}

private static double asNumber(Object v, boolean strip)
{
  if (v instanceof Double)  return ((Double) v).doubleValue();
  if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? 1.0 : 0.0;
  if (!(v instanceof String)) return Double.NaN;
  String s = ((String) v).trim();
  if (s.equalsIgnoreCase("true"))  return 1.0;
  if (s.equalsIgnoreCase("false")) return 0.0;
  try { return Double.parseDouble(s); }
  catch (NumberFormatException e) { }
  if (strip)
  {
    Matcher m = NUMBER.matcher(s);
    if (m.find())
    {
      try { return Double.parseDouble(m.group()); }
      catch (NumberFormatException e) { }
    }
  }
  return Double.NaN;
}

private static String fmt(double d)
{
  if (!Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
  return Double.toString(d);
}

private static void toJson(Object v, StringBuilder sb)
{
  if (v instanceof Map)
  {
    sb.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet())
    {
      if (!first) sb.append(',');
      first = false;
      quote(String.valueOf(e.getKey()), sb);
      sb.append(':');
      toJson(e.getValue(), sb);
    }
    sb.append('}');
  }
  else if (v instanceof List)
  {
    sb.append('[');
    List<?> l = (List<?>) v;
    for (int i = 0; i < l.size(); i++)
    {
      if (i > 0) sb.append(',');
      toJson(l.get(i), sb);
    }
    sb.append(']');
  }
  else if (v instanceof String)  quote((String) v, sb);
  else if (v instanceof Double)  sb.append(fmt(((Double) v).doubleValue()));
  else if (v instanceof Boolean) sb.append(v.toString());
  else                           sb.append("null");
}

private static void quote(String s, StringBuilder sb)
{
  sb.append('"');
  for (int i = 0; i < s.length(); i++)
  {
    char ch = s.charAt(i);
    switch (ch)
    {
      case '"':  sb.append("\\\""); break;
      case '\\': sb.append("\\\\"); break;
      case '\n': sb.append("\\n");  break;
      case '\r': sb.append("\\r");  break;
      case '\t': sb.append("\\t");  break;
      default:
        if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
        else sb.append(ch);
    }
  }
  sb.append('"');
}

// ------------------------------------------------------------------ discovery -------
@SuppressWarnings("unchecked")
private static void flatten(Object v, String path, StringBuilder sb, int[] lines)
{
  if (lines[0] >= MAX_DISCOVER_LINES) return;
  if (v instanceof Map)
  {
    Map<String, Object> m = (Map<String, Object>) v;
    if (m.isEmpty()) { line(sb, lines, path, "{}"); return; }
    for (Map.Entry<String, Object> e : m.entrySet())
    {
      String k = e.getKey();
      String kp = SAFE_KEY.matcher(k).matches()
                ? (path.isEmpty() ? k : path + "." + k)
                : path + "['" + k + "']";
      flatten(e.getValue(), kp, sb, lines);
      if (lines[0] >= MAX_DISCOVER_LINES) return;
    }
  }
  else if (v instanceof List)
  {
    List<Object> l = (List<Object>) v;
    if (l.isEmpty()) { line(sb, lines, path, "[]"); return; }
    for (int i = 0; i < l.size(); i++)
    {
      flatten(l.get(i), path + "[" + i + "]", sb, lines);
      if (lines[0] >= MAX_DISCOVER_LINES) return;
    }
  }
  else
  {
    line(sb, lines, path, trunc(asText(v), 80));
  }
}

private static void line(StringBuilder sb, int[] lines, String path, String val)
{
  sb.append(path.isEmpty() ? "$" : path).append(" = ").append(val).append('\n');
  lines[0]++;
}

// ------------------------------------------------------------------ JSON parser -----
private static Object parseJson(String s)
{
  int[] p = { 0 };
  Object v = jValue(s, p);
  jWs(s, p);
  if (p[0] < s.length()) throw new IllegalArgumentException("unexpected data at char " + p[0]);
  return v;
}

private static void jWs(String s, int[] p)
{
  while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
}

private static char jPeek(String s, int[] p)
{
  jWs(s, p);
  if (p[0] >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
  return s.charAt(p[0]);
}

private static Object jValue(String s, int[] p)
{
  char c = jPeek(s, p);
  if (c == '{') return jObject(s, p);
  if (c == '[') return jArray(s, p);
  if (c == '"') return jString(s, p);
  if (c == 't') { jLit(s, p, "true");  return Boolean.TRUE; }
  if (c == 'f') { jLit(s, p, "false"); return Boolean.FALSE; }
  if (c == 'n') { jLit(s, p, "null");  return JNULL; }
  return jNumber(s, p);
}

private static Map<String, Object> jObject(String s, int[] p)
{
  Map<String, Object> m = new LinkedHashMap<String, Object>();
  p[0]++;                                             // {
  if (jPeek(s, p) == '}') { p[0]++; return m; }
  while (true)
  {
    if (jPeek(s, p) != '"') throw new IllegalArgumentException("expected key at char " + p[0]);
    String k = jString(s, p);
    if (jPeek(s, p) != ':') throw new IllegalArgumentException("expected ':' at char " + p[0]);
    p[0]++;
    m.put(k, jValue(s, p));
    char c = jPeek(s, p);
    p[0]++;
    if (c == '}') return m;
    if (c != ',') throw new IllegalArgumentException("expected ',' or '}' at char " + (p[0] - 1));
  }
}

private static List<Object> jArray(String s, int[] p)
{
  List<Object> l = new ArrayList<Object>();
  p[0]++;                                             // [
  if (jPeek(s, p) == ']') { p[0]++; return l; }
  while (true)
  {
    l.add(jValue(s, p));
    char c = jPeek(s, p);
    p[0]++;
    if (c == ']') return l;
    if (c != ',') throw new IllegalArgumentException("expected ',' or ']' at char " + (p[0] - 1));
  }
}

private static String jString(String s, int[] p)
{
  StringBuilder sb = new StringBuilder();
  p[0]++;                                             // opening quote
  while (p[0] < s.length())
  {
    char c = s.charAt(p[0]++);
    if (c == '"') return sb.toString();
    if (c != '\\') { sb.append(c); continue; }
    if (p[0] >= s.length()) break;
    char e = s.charAt(p[0]++);
    switch (e)
    {
      case 'n': sb.append('\n'); break;
      case 't': sb.append('\t'); break;
      case 'r': sb.append('\r'); break;
      case 'b': sb.append('\b'); break;
      case 'f': sb.append('\f'); break;
      case 'u':
        if (p[0] + 4 > s.length()) throw new IllegalArgumentException("bad \\u escape");
        sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
        p[0] += 4;
        break;
      default:  sb.append(e);                         // \" \\ \/
    }
  }
  throw new IllegalArgumentException("unterminated string");
}

private static Double jNumber(String s, int[] p)
{
  int start = p[0];
  while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
  if (start == p[0]) throw new IllegalArgumentException("unexpected '" + s.charAt(start) + "' at char " + start);
  return Double.valueOf(s.substring(start, p[0]));
}

private static void jLit(String s, int[] p, String lit)
{
  if (!s.startsWith(lit, p[0])) throw new IllegalArgumentException("bad literal at char " + p[0]);
  p[0] += lit.length();
}

// ------------------------------------------------------------------ helpers ---------
private static String subst(String src, String[] vars)
{
  if (src == null || src.isEmpty()) return "";
  Matcher m = TOKEN.matcher(src);
  StringBuffer sb = new StringBuffer();
  while (m.find())
  {
    String name = m.group(1);
    String val;
    if (name.startsWith("var"))        val = vars[name.charAt(3) - '1'];
    else if (name.equals("epochMs"))   val = Long.toString(System.currentTimeMillis());
    else if (name.equals("epochS"))    val = Long.toString(System.currentTimeMillis() / 1000L);
    else
    {
      SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
      f.setTimeZone(TimeZone.getTimeZone("UTC"));
      val = f.format(new java.util.Date());
    }
    if (m.group(2) != null)
    {
      try { val = URLEncoder.encode(val, "UTF-8"); }
      catch (Exception ignore) { }
    }
    m.appendReplacement(sb, Matcher.quoteReplacement(val));
  }
  m.appendTail(sb);
  return sb.toString();
}

private static boolean usable(BStatusString v)
{
  if (v == null) return false;
  BStatus st = v.getStatus();
  if (st.isNull() || st.isFault() || st.isDown()) return false;
  return nz(v.getValue()).trim().length() > 0;
}

private static Charset charsetOf(String ct)
{
  if (ct != null)
  {
    int k = ct.toLowerCase().indexOf("charset=");
    if (k >= 0)
    {
      String cs = ct.substring(k + 8).replace("\"", "").trim();
      int e = cs.indexOf(';');
      if (e >= 0) cs = cs.substring(0, e).trim();
      try { return Charset.forName(cs); }
      catch (Exception ignore) { }
    }
  }
  return StandardCharsets.UTF_8;
}

private static String describe(Throwable e)
{
  String cn  = e.getClass().getName();
  String msg = (e.getMessage() == null) ? "" : e.getMessage();
  if (cn.endsWith("UnknownHostException"))   return "DNS: unknown host " + msg;
  if (cn.endsWith("SocketTimeoutException")) return "Timeout: " + msg;
  if (cn.endsWith("ConnectException"))       return "Connect failed: " + msg;
  if (cn.contains("SSL") || cn.contains("Certificate"))
    return "TLS: " + msg + " (check Certificate Management > Allowed Hosts)";
  return e.getClass().getSimpleName() + ": " + msg;
}

private static String sval(BValue v)
{
  if (v == null) return "";
  if (v instanceof BString) return ((BString) v).getString();
  return v.toString();
}

private static String nz(String s)
{
  return (s == null) ? "" : s;
}

private static String trunc(String s, int max)
{
  if (s == null) return "";
  return (s.length() > max) ? s.substring(0, max) + "..." : s;
}

private static String oneLine(String s)
{
  return nz(s).replace('\r', ' ').replace('\n', ' ');
}

private static int clamp(int v, int lo, int hi)
{
  return (v < lo) ? lo : (v > hi ? hi : v);
}
