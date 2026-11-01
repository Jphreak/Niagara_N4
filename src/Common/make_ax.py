"""Derive Common_AX.java from Common_N4.java - only the AX-required differences.

  python3 make_ax.py      (run after every change to Common_N4.java, then sync)
"""
import os, re
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'Common_N4.java')).read()

def sub(old, new, count=1):
    global src
    assert src.count(old) == count, (src.count(old), old[:70])
    src = src.replace(old, new)

sub("COMMON N4 - standard helper block for Niagara N4 Program Objects",
    "COMMON AX - standard helper block for Niagara AX (3.5-3.8) Program Objects")
sub("""One copy of the code every Program Object uses for the same job. Paste the""",
    """Generated from Common_N4.java by make_ax.py - same helpers, AX rules:
Java 1.4 (no generics, for-each, autoboxing, StringBuilder, String.format,
getSimpleName, anonymous classes), javax.baja.log.Log, reflective station
home, 2-argument BComponent.add().
One copy of the code every Program Object uses for the same job. Paste the""")
sub("""  private static final java.util.logging.Logger log =
    java.util.logging.Logger.getLogger("<Tool>");    (Files tier)""",
    """  private static final javax.baja.log.Log log =
    javax.baja.log.Log.getLog("<Tool>");             (Files tier)""")
# describeException
sub("""  StringBuilder sb = new StringBuilder();
  String cls = t.getClass().getSimpleName();""",
    """  StringBuffer sb = new StringBuffer();
  String cls = simpleName(t);""")
sub("""    sb.append(" [cause: ").append(cause.getClass().getSimpleName());""",
    """    sb.append(" [cause: ").append(simpleName(cause));""")
sub("""// Reflection-based setter for the version slot""",
    """// Class name without the package (Class.getSimpleName() is Java 1.5+).
private String simpleName(Object o)
{
  if (o == null) return "null";
  String n = o.getClass().getName();
  int dot = n.lastIndexOf('.');
  if (dot >= 0) n = n.substring(dot + 1);
  int dollar = n.lastIndexOf('$');
  if (dollar >= 0 && dollar < n.length() - 1) n = n.substring(dollar + 1);
  return n;
}

// Reflection-based setter for the version slot""")
# station home
sub("""  java.io.File f = new java.io.File(p);
  if (stationRelative || !f.isAbsolute())
    f = new java.io.File(javax.baja.sys.Sys.getStationHome(), p);

  return sandboxToStationHome(f, pathOrOrd);
}""",
"""  java.io.File home = stationHome();
  if (home == null)
  {
    String msg = "PATH ERROR: station home folder not found - cannot use '" +
      pathOrOrd + "'";
    lastPathError = msg;
    setStatus("[" + now() + "] " + msg);
    log.warning("[" + LOG_TAG + "] " + msg);
    return null;
  }

  java.io.File f = new java.io.File(p);
  if (stationRelative || !f.isAbsolute())
    f = new java.io.File(home, p);

  return sandboxToStationHome(f, home, pathOrOrd);
}

// Station home folder. AX 3.5-3.7 have no Sys.getStationHome(): fall back
// to <baja home>/stations/<station name>.
private java.io.File stationHome()
{
  try
  {
    java.lang.reflect.Method m = javax.baja.sys.Sys.class.getMethod(
      "getStationHome", new Class[0]);
    Object o = m.invoke(null, new Object[0]);
    if (o instanceof java.io.File) return (java.io.File) o;
  }
  catch (Throwable ignore) {}

  try
  {
    java.lang.reflect.Method mh = javax.baja.sys.Sys.class.getMethod(
      "getBajaHome", new Class[0]);
    Object home = mh.invoke(null, new Object[0]);
    Object station = javax.baja.sys.Sys.getStation();
    java.lang.reflect.Method mn = station.getClass().getMethod(
      "getStationName", new Class[0]);
    Object name = mn.invoke(station, new Object[0]);
    if (home instanceof java.io.File && name != null)
      return new java.io.File(
        new java.io.File((java.io.File) home, "stations"), name.toString());
  }
  catch (Throwable ignore) {}

  return null;
}""")
sub("""private java.io.File sandboxToStationHome(java.io.File f, String original)
{
  String msg;
  try
  {
    java.io.File home = javax.baja.sys.Sys.getStationHome().getCanonicalFile();""",
"""private java.io.File sandboxToStationHome(
  java.io.File f, java.io.File stationHomeDir, String original)
{
  String msg;
  try
  {
    java.io.File home = stationHomeDir.getCanonicalFile();""")
# logging API
sub("""  log.info("[" + LOG_TAG + "] " + message);""", """  log.message("[" + LOG_TAG + "] " + message);""")
sub("""  log.severe("[" + LOG_TAG + "] " + message);""", """  log.error("[" + LOG_TAG + "] " + message);""")
# csv quote doubling
sub("""    return '"' + s.replace("\\"", "\\"\\"") + '"';
  return s;
}""", """    return '"' + doubleQuotes(s) + '"';
  return s;
}

// s with every " doubled (String.replace(CharSequence...) is Java 1.5+).
private String doubleQuotes(String s)
{
  StringBuffer sb = new StringBuffer();
  for (int i = 0; i < s.length(); i++)
  {
    char c = s.charAt(i);
    if (c == '"') sb.append('"');
    sb.append(c);
  }
  return sb.toString();
}""")
# 2-arg add
sub("""  tgtComp.add(linkName, newLink, null);""", """  tgtComp.add(linkName, newLink);""")
sub("""    Object o = javax.baja.naming.BOrd.make(normalizeOrd(path)).resolve().get();""",
    """    Object o = resolveOrd(javax.baja.naming.BOrd.make(normalizeOrd(path)));""")
sub("""// ================================================================ LINKS =====""",
    """// Resolves an ord against the station (AX ords need a base).
private javax.baja.sys.BObject resolveOrd(javax.baja.naming.BOrd ord)
  throws Exception
{
  return ord.resolve(javax.baja.sys.Sys.getStation()).get();
}

// ================================================================ LINKS =====""")
open(os.path.join(HERE, 'Common_AX.java'), 'w').write(src)

# AX rule check
bad = []
code = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)
for pat, why in [(r'StringBuilder', 'StringBuilder'), (r'String\.format', 'String.format'),
                 (r'getSimpleName', 'getSimpleName'), (r'for\s*\([^;)]*:', 'for-each'),
                 (r'new\s+[\w.]+\s*\([^)]*\)\s*\{', 'anonymous class'), (r'<\w+>', 'generics'),
                 (r'@Override', '@Override'), (r'\.isEmpty\(\)', 'String.isEmpty (1.6)')]:
    for m in re.finditer(pat, code): bad.append(why)
print("AX rule violations:", sorted(set(bad)) or "none")
