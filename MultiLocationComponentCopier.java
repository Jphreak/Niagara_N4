/*
================================================================================
Program: Multi-Location Component Copier (Direct / BQL / CSV) — Clean Build (Niagara N4.15)
Author: F. Lacroix — 12/18/2025
Version Tag: v5.0

History / Notes:
  • v1.0  — Inspiration from "Photocopier" by Matt Beatty (23/10/2023)
  • v2.0  — Added Input Source as CSV
  • v3.0  — Added All Links Toggle
  • v4.0  — Added Testing Mode
  • v5.0  — Added Output Actions to CSV logging and Console; startup banner (mode-aware)
            Polished: CSV preflight, dynamic banner (mode/source/results), improved SKIPPED reasons, self-tests

QUICK GUIDE
--------------------------------------
Purpose: Copy one source component into one or more destination containers and log results to a CSV and console.
Modes:
 • Direct — Copy to a single destination Ord (container component).
 • BQL    — Iterate a BITable; a chosen column supplies destination values.
 • CSV    — Read a CSV file; first column supplies destination values.
Required slots:
 • componentToCopy (BOrd), copyTo (BOrd), destinationMode (BStatusEnum), keepAllLinks (boolean/BBoolean)
 • Dry Run? (boolean) — simulate the copy; still logs results.
 • ResultsCsvDestination/ResultsToCsvDestination (BOrd) — must point to a .csv path (e.g., file:/Results/XXXXXXXXX.csv)
High-level flow:
 1) Choose mode (or auto-detect from copyTo).
 2) Prepare results CSV; write a header.
 3) Resolve each destination; copy (or dry-run); log rows.
 4) Write a summary line.
================================================================================
*/


private static final String NL = System.lineSeparator();
private static final String VERSION = "v5.0";

/** ACTION ENTRY POINT *//** Entry action: selects mode (Direct/BQL/CSV) and invokes the appropriate runner. */

public void onExecuteCopy() {
  int mode = getDestModeOrdinal();
  if (mode == -1) mode = autoDetectMode();
  switch (mode) {
    case 1: try { new CopyTask("BQL").submit(); } catch (Exception ignore) {} return;
    case 2: try { new CopyTask("CSV").submit(); } catch (Exception ignore) {} return;
    case 0: default: break;
  }
  final String srcName = getSourceNameSafe();
  final java.util.Map<String,Object> cfg = readOptions();
  final BOrd targetCsvOrd = computeResultsCsvTarget(cfg);
  preflightCsvWritable(targetCsvOrd);
  appBannerStart("Direct", srcName, targetCsvOrd != null ? targetCsvOrd.toString() : null);
  appInfo("Starting Direct copy");
  java.io.Writer results = null;
  try {
    final BOrd srcOrd = getComponentToCopy();
    final BOrd dstOrd = getCopyTo();
    if (srcOrd == null) throw new IllegalArgumentException("componentToCopy is not set.");
    if (dstOrd == null) throw new IllegalArgumentException("copyTo is not set for direct copy.");
    final BObject srcObj = srcOrd.get();
    final BObject dstObj = dstOrd.get();
    if (srcObj == null) throw new IllegalArgumentException("componentToCopy Ord did not resolve to an object: " + srcOrd);
    if (dstObj == null) throw new IllegalArgumentException("copyTo Ord did not resolve to an object: " + dstOrd);
    final BComponent src = srcObj.asComponent();
    final BComponent dst = dstObj.asComponent();
    if (src == null) throw new IllegalArgumentException("componentToCopy did not resolve to a component.");
    if (dst == null) throw new IllegalArgumentException("copyTo did not resolve to a component (must be a container component).");
    results = openResultsCsvWriter(targetCsvOrd);
    if (results != null) writeCsvHeader(results);
    final BComponent params = makeParamsWithKeepLinks();
    final boolean keepLinks = ((BBoolean) params.get("keepAllLinks")).getBoolean();
    final boolean dryRun = resolveDryRun(cfg);
    final long t0 = System.nanoTime(); final String ts = tsHuman();
    try {
      if (!dryRun) { final Mark mark = new Mark(src); mark.copyTo(dst, params, null); }
      final long durMs = (System.nanoTime() - t0) / 1_000_000L;
      if (results != null) writeCsvRow(results, 1, ts, src.getName().toString(), src.getSlotPath().toString(), destStrToCsv(dst), dst.getSlotPath().toString(), dryRun ? "DRYRUN" : "COPIED", "", keepLinks, durMs);
    } catch (Exception copyEx) {
      final long durMs = (System.nanoTime() - t0) / 1_000_000L;
      if (results != null) writeCsvRow(results, 1, ts, src.getName().toString(), src.getSlotPath().toString(), destStrToCsv(dst), dst.getSlotPath().toString(), "FAILED", safe(copyEx.getMessage()), keepLinks, durMs);
      appError("Direct copy failed", copyEx);
    }
    if (results != null) writeSummary(results, dryRun ? 0 : 1, 0, 0, dryRun ? 1 : 0);
    appInfo("Direct copy completed: copied " + (dryRun ? 0 : 1) + ", dryRun=" + dryRun + ", results=" + (targetCsvOrd != null ? targetCsvOrd.toString() : "(none)"));
  } catch (Exception e) { appError("Direct copy failed", e); }
  finally { try { if (results != null) results.close(); } catch (Exception ignore) {} }
}/** Maps destinationMode to an ordinal: 0=Direct, 1=BQL, 2=CSV, -1=auto-detect. */



private int getDestModeOrdinal() { final BStatusEnum m = getDestinationMode(); final String s = (m != null) ? m.toString() : "Direct"; final String tag = (s != null) ? s.trim() : "Direct"; if ("BQL".equalsIgnoreCase(tag)) return 1; if ("CSV".equalsIgnoreCase(tag)) return 2; if ("Direct".equalsIgnoreCase(tag)) return 0; return -1; }/** Auto-detects mode from copyTo: table=>BQL, file-like=>CSV, otherwise Direct. */

private int autoDetectMode() { try { final BObject obj = getCopyTo().resolve().get(); if (obj instanceof BITable) return 1; try { if (obj.getClass().getMethod("openInputStream") != null) return 2; } catch (Throwable ignore) {} try { if (obj.getClass().getMethod("getInputStream") != null) return 2; } catch (Throwable ignore) {} return 0; } catch (Exception ignore) { return 0; } }/** Returns a human-readable timestamp for logging (yyyy-MM-dd HH:mm:ss). */


private String tsHuman() { try { return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()); } catch (Throwable t) { return Long.toString(System.currentTimeMillis()); } }
private String tsCompact() { try { return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()); } catch (Throwable t) { return Long.toString(System.currentTimeMillis()); } }
private String tsDate() { try { return new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date()); } catch (Throwable t) { return Long.toString(System.currentTimeMillis()); } }

// ===== Options & CSV target =====/** Collects run-time options (dryRun, bqlCol, resultsDest) via flexible getter names. */

private java.util.Map<String,Object> readOptions() {
  java.util.Map<String,Object> m = new java.util.HashMap<String,Object>();
  m.put("dryRun", optBool(new String[]{"getDryRun","isDryRun","getIsDryRun"}, Boolean.FALSE));
  m.put("bqlCol", optInt(new String[]{"getBqlDestinationColumn","getDestinationColumn","getBqlColumn"}, 0));
  m.put("resultsDest", optOrd(new String[]{"getResultsCsvDestination","getResultsToCsvDestination","getResultsCsvDest","getCsvResultsDestination"}));
  return m;
}/** Determines Dry Run from options or by scanning methods like getDryRun/isDryRun. */



private boolean resolveDryRun(final java.util.Map<String,Object> cfg) { if (asBool(cfg.get("dryRun"))) return true; try { java.lang.reflect.Method[] methods = this.getClass().getMethods(); for (int i = 0; i < methods.length; i++) { java.lang.reflect.Method m = methods[i]; String name = m.getName().toLowerCase(); if (m.getParameterTypes().length == 0 && name.indexOf("dry") >= 0 && name.indexOf("run") >= 0) { Class<?> rt = m.getReturnType(); Object v = m.invoke(this); if (rt == Boolean.TYPE || rt == Boolean.class) return ((Boolean) v).booleanValue(); if (v instanceof BBoolean) return ((BBoolean) v).getBoolean(); } } } catch (Throwable ignore) {} return false; }/** Validates ResultsCsvDestination: must end with .csv; throws if invalid. */


private BOrd computeResultsCsvTarget(final java.util.Map<String,Object> cfg) {
  final BOrd provided = (BOrd) cfg.get("resultsDest");
  if (provided == null) throw new IllegalArgumentException("resultsCsvDestination is required and must be an explicit .csv Ord");
  final String p = provided.toString();
  if (p == null || !p.toLowerCase().endsWith(".csv")) throw new IllegalArgumentException("resultsCsvDestination must point to a .csv file (e.g., file:/logs/YourResults.csv)");
  return provided;
}


private String ensureNoTrailingSlash(String s) { return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s; }
private String addDateStem(String base, String date) { return base.toLowerCase().endsWith(".csv") ? (trimExt(base) + "_" + date + ".csv") : (base + "_" + date + ".csv"); }
private String addTimestampStem(String base, String ts) { return base.toLowerCase().endsWith(".csv") ? (trimExt(base) + "_" + ts + ".csv") : (base + "_" + ts + ".csv"); }
private String trimExt(String base) { int i = base.toLowerCase().lastIndexOf(".csv"); return (i > 0) ? base.substring(0, i) : base; }/** Opens a UTF-8 Writer for the results CSV using reflection; may return null. */


private java.io.Writer openResultsCsvWriter(final BOrd csvOrd) { if (csvOrd == null) return null; try { final BObject newObj = csvOrd.resolve().get(); if (newObj == null) return null; try { java.lang.reflect.Method m = newObj.getClass().getMethod("openOutputStream", new Class[]{boolean.class}); java.io.OutputStream os = (java.io.OutputStream) m.invoke(newObj, Boolean.TRUE); if (os != null) return new java.io.BufferedWriter(new java.io.OutputStreamWriter(os, "UTF-8")); } catch (Throwable ignore) {} try { java.lang.reflect.Method m = newObj.getClass().getMethod("getOutputStream", new Class[]{boolean.class}); java.io.OutputStream os = (java.io.OutputStream) m.invoke(newObj, Boolean.TRUE); if (os != null) return new java.io.BufferedWriter(new java.io.OutputStreamWriter(os, "UTF-8")); } catch (Throwable ignore) {} try { java.lang.reflect.Method m = newObj.getClass().getMethod("openOutputStream", new Class[0]); java.io.OutputStream os = (java.io.OutputStream) m.invoke(newObj, new Object[0]); if (os != null) return new java.io.BufferedWriter(new java.io.OutputStreamWriter(os, "UTF-8")); } catch (Throwable ignore) {} try { java.lang.reflect.Method m = newObj.getClass().getMethod("getOutputStream", new Class[0]); java.io.OutputStream os = (java.io.OutputStream) m.invoke(newObj, new Object[0]); if (os != null) return new java.io.BufferedWriter(new java.io.OutputStreamWriter(os, "UTF-8")); } catch (Throwable ignore) {} } catch (Throwable outer) {} return null; }

// ===== CSV Row Writing (Timestamp first + version tag) =====/** Writes the CSV header row including program VERSION. */

private void writeCsvHeader(final java.io.Writer w) throws java.io.IOException {
  w.write("Row(" + VERSION + "),Timestamp,SourceName,SourceSlotPath,Destination,DestSlotPath,Status,Message,KeepAllLinks,CopyDurationMs" + NL);
  w.flush();
}/** Writes one results row: COPIED/DRYRUN/SKIPPED/FAILED with message and timing. */


private void writeCsvRow(final java.io.Writer w, final int row, final String ts, final String srcName, final String srcSlot, final String dest, final String destSlot, final String status, final String msg, final boolean keepLinks, final long durMs) throws java.io.IOException {
  w.write(row + "," + csv(ts) + "," + csv(srcName) + "," + csv(srcSlot) + "," + csv(dest) + "," + csv(destSlot) + "," + csv(status) + "," + csv(msg) + "," + csv(Boolean.toString(keepLinks)) + "," + csv(Long.toString(durMs)) + NL);
  w.flush();
}/** Appends summary line with totals: copied, skipped, failed, dryrun. */


private void writeSummary(final java.io.Writer w, final int copied, final int skipped, final int failed, final int dryrun) throws java.io.IOException {
  w.write(System.lineSeparator());
  w.write("Total(" + VERSION + "),,Copied:" + copied + ",Skipped:" + skipped + ",Failed:" + failed + ",DryRun:" + dryrun + ",," + NL);
  w.flush();
}/** Escapes commas/quotes/newlines for safe CSV output; uses double-quote wrapping when needed. */



private String csv(final String s) { final String v = (s == null) ? "" : s; final boolean q = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0; return q ? ('"' + v.replace("\"", "\"\"") + '"') : v; }
private String safe(final String s) { return (s == null) ? "" : s; }/** Console info logger. */



private void appInfo(final String msg) { try { System.out.println("[MultiLocationCopier] " + msg); } catch (Throwable ignore) {} }/** Console error logger including exception message. */

private void appError(final String msg, final Throwable t) { try { System.err.println("[MultiLocationCopier] ERROR: " + msg + (t != null ? " :: " + safe(t.getMessage()) : "")); } catch (Throwable ignore) {} }
private transient boolean bannerPrinted = false;
private String getSourceNameSafe() { try { BOrd o = getComponentToCopy(); if (o != null) { BObject bo = o.get(); BComponent c = (bo != null) ? bo.asComponent() : null; if (c != null) return c.getName().toString(); } } catch (Throwable ignore) {} return "(unknown)"; }/** Preflights the results CSV Ord to ensure it is writable; opens/closes an OutputStream. */

private void preflightCsvWritable(final BOrd csvOrd) { if (csvOrd == null) throw new IllegalArgumentException("resultsCsvDestination .csv is required"); try { final BObject obj = csvOrd.resolve().get(); if (obj == null) throw new IllegalArgumentException("resultsCsvDestination did not resolve"); java.io.OutputStream os = null; try { java.lang.reflect.Method m = obj.getClass().getMethod("openOutputStream", new Class[]{boolean.class}); os = (java.io.OutputStream) m.invoke(obj, Boolean.TRUE); } catch (Throwable ignore) {} if (os == null) { try { java.lang.reflect.Method m = obj.getClass().getMethod("getOutputStream", new Class[]{boolean.class}); os = (java.io.OutputStream) m.invoke(obj, Boolean.TRUE); } catch (Throwable ignore) {} } if (os == null) { try { java.lang.reflect.Method m = obj.getClass().getMethod("openOutputStream", new Class[0]); os = (java.io.OutputStream) m.invoke(obj, new Object[0]); } catch (Throwable ignore) {} } if (os == null) { try { java.lang.reflect.Method m = obj.getClass().getMethod("getOutputStream", new Class[0]); os = (java.io.OutputStream) m.invoke(obj, new Object[0]); } catch (Throwable ignore) {} } if (os == null) throw new IllegalStateException("resultsCsvDestination is not writable"); try { os.close(); } catch (Throwable ignore) {} appInfo("Results CSV ready: " + csvOrd.toString()); } catch (Throwable t) { throw new IllegalStateException("Results CSV not ready: " + safe(t.getMessage())); } }/** Console info logger. *//** Prints a one-time banner showing mode, source name, and results path. */


private void appBannerStart(final String mode, final String srcName, final String resultsPath) { if (!bannerPrinted) { appInfo("v5.0 — F. Lacroix 12/18/2025 — Direct/BQL/CSV"); appInfo("Mode: " + mode + " | Source: " + srcName + (resultsPath != null ? " | Results: " + resultsPath : "")); bannerPrinted = true; } }
// ===== Copy params =====/** Builds copy parameters (keepAllLinks) for Mark.copyTo(). *//** Coerces keepAllLinks to BBoolean from various return types. */


private BComponent makeParamsWithKeepLinks() { final BComponent params = new BComponent(); final BBoolean keepLinksVal = coerceKeepAllLinks(); params.add("keepAllLinks", keepLinksVal); return params; }/** Coerces keepAllLinks to BBoolean from various return types. */

private BBoolean coerceKeepAllLinks() { try { final java.lang.reflect.Method m = this.getClass().getMethod("getKeepAllLinks"); final Class<?> ret = m.getReturnType(); final Object v = m.invoke(this); if (BBoolean.class.isAssignableFrom(ret)) return (BBoolean) v; if (boolean.class.equals(ret)) return BBoolean.make(((Boolean) v).booleanValue()); if (v instanceof BBoolean) return (BBoolean) v; if (v instanceof Boolean) return BBoolean.make(((Boolean) v).booleanValue()); } catch (Throwable ignore) {} return BBoolean.FALSE; }

// ===== BRunnableJob task for BQL/CSV =====/** Background job used for BQL/CSV runs; keeps the UI responsive while logging results. */

public class CopyTask implements Runnable { private final BRunnableJob job; private final String modeLabel; public CopyTask(String modeLabel) { this.modeLabel = (modeLabel != null) ? modeLabel : "Table"; this.job = new BRunnableJob(this); } public void submit() { job.submit(null); }
  public void run() {
    java.io.Writer results = null; TableCursor cursor = null; java.io.InputStream in = null; java.io.BufferedReader br = null; 
BOrd targetCsvOrd = null;
int copied = 0, skipped = 0, failed = 0, dryrunCnt = 0; 
    try {
      final BOrd srcOrd = getComponentToCopy(); if (srcOrd == null) throw new IllegalArgumentException("componentToCopy is not set.");
      final BObject srcObj = srcOrd.get(); if (srcObj == null) throw new IllegalArgumentException("componentToCopy Ord did not resolve: " + srcOrd);
      final BComponent src = srcObj.asComponent(); if (src == null) throw new IllegalArgumentException("componentToCopy did not resolve to a component.");
      final String srcName = src.getName().toString(); final String srcSlot = src.getSlotPath().toString();
      final java.util.Map<String,Object> cfg = readOptions(); final boolean keepLinks = ((BBoolean) makeParamsWithKeepLinks().get("keepAllLinks")).getBoolean(); final boolean dryRun = resolveDryRun(cfg);
      targetCsvOrd = computeResultsCsvTarget(cfg); preflightCsvWritable(targetCsvOrd);
        results = openResultsCsvWriter(targetCsvOrd);
        if (results != null) writeCsvHeader(results);
      appBannerStart(modeLabel, src.getName().toString(), (targetCsvOrd != null ? targetCsvOrd.toString() : null));
      appInfo("Starting " + modeLabel + " job");
      final BObject targetObj = getCopyTo().resolve().get(); if (targetObj == null) throw new IllegalArgumentException("copyTo did not resolve to any object.");
      final BComponent params = makeParamsWithKeepLinks(); final Mark mark = new Mark(src);

      if (targetObj instanceof BITable) {
        final BITable table = (BITable) targetObj; final Column[] columns = table.getColumns().list(); if (columns == null || columns.length == 0) throw new IllegalStateException("Table has no columns; expected at least one for destination.");
        final int colIdx = Math.max(0, asInt(cfg.get("bqlCol"))); final Column col = columns[Math.min(colIdx, columns.length - 1)];
        cursor = table.cursor(); int rowIndex = 0; while (cursor.next()) {
          rowIndex++; final Object cellObj = cursor.cell(col); final BOrd destOrd = toDestinationOrdVerbose(cellObj);
          final String ts = tsHuman();
          if (destOrd == null) { skipped++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, "", "", "SKIPPED", "destination could not be derived (null/empty or unsupported cell type)", keepLinks, 0L); continue; }
          final BObject dstObj = destOrd.get(); final BComponent dst = (dstObj != null) ? dstObj.asComponent() : null; final String destStr = (destOrd != null) ? destOrd.toString() : ""; final String destSlot = (dst != null) ? dst.getSlotPath().toString() : "";
          if (dst == null) { skipped++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStr, "", "SKIPPED", "destination not resolvable to component (ORD resolves to non-component or null)", keepLinks, 0L); continue; }
          final long t0 = System.nanoTime(); try { if (!dryRun) mark.copyTo(dst, params, null); final long durMs = (System.nanoTime() - t0) / 1_000_000L; if (dryRun) dryrunCnt++; else copied++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStrToCsv(dst), destSlot, dryRun ? "DRYRUN" : "COPIED", "", keepLinks, durMs); }
          catch (Exception copyEx) { failed++; final long durMs = (System.nanoTime() - t0) / 1_000_000L; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStrToCsv(dst), destSlot, "FAILED", safe(copyEx.getMessage()), keepLinks, durMs); }
        }
        if (results != null) writeSummary(results, copied, skipped, failed, dryrunCnt); return;
      }

      try {
        java.lang.reflect.Method mOpen = null; try { mOpen = targetObj.getClass().getMethod("openInputStream"); } catch (Throwable ignore) {}
        if (mOpen == null) { try { mOpen = targetObj.getClass().getMethod("getInputStream"); } catch (Throwable ignore) {} }
        if (mOpen != null) {
          in = (java.io.InputStream) mOpen.invoke(targetObj); if (in == null) { if (results != null) writeCsvRow(results, 0, tsHuman(), srcName, srcSlot, "", "", "SKIPPED", "InputStream method returned null", keepLinks, 0L); return; }
          br = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8")); String line; int rowIndex = 0; while ((line = br.readLine()) != null) {
            rowIndex++; final String first = firstCsvField(line); final String ts = tsHuman(); if (rowIndex == 1 && "destination".equalsIgnoreCase(first)) { if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, first, "", "SKIPPED", "Header row", keepLinks, 0L); continue; }
            if (first == null || first.isEmpty()) { skipped++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, "", "", "SKIPPED", "Empty first column (no destination provided)", keepLinks, 0L); continue; }
            final BOrd destOrd = buildDestinationOrd(first); final BObject dstObj = (destOrd != null) ? destOrd.get() : null; final BComponent dst = (dstObj != null) ? dstObj.asComponent() : null; final String destStr = (destOrd != null) ? destOrd.toString() : ""; final String destSlot = (dst != null) ? dst.getSlotPath().toString() : "";
            if (dst == null) { skipped++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStr, "", "SKIPPED", "destination not resolvable to component (ORD resolves to non-component or null)", keepLinks, 0L); continue; }
            final long t0 = System.nanoTime(); try { if (!dryRun) mark.copyTo(dst, params, null); final long durMs = (System.nanoTime() - t0) / 1_000_000L; if (dryRun) dryrunCnt++; else copied++; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStrToCsv(dst), destSlot, dryRun ? "DRYRUN" : "COPIED", "", keepLinks, durMs); }
            catch (Exception copyEx) { failed++; final long durMs = (System.nanoTime() - t0) / 1_000_000L; if (results != null) writeCsvRow(results, rowIndex, ts, srcName, srcSlot, destStrToCsv(dst), destSlot, "FAILED", safe(copyEx.getMessage()), keepLinks, durMs); }
          }
          if (results != null) writeSummary(results, copied, skipped, failed, dryrunCnt); return;
        }
        if (results != null) writeCsvRow(results, 0, tsHuman(), srcName, srcSlot, "", "", "SKIPPED", "copyTo not table or file-like", keepLinks, 0L); if (results != null) writeSummary(results, copied, skipped, failed, dryrunCnt); return;
      } catch (Throwable t) { if (results != null) writeCsvRow(results, 0, tsHuman(), srcName, srcSlot, "", "", "FAILED", safe(t.getMessage()), keepLinks, 0L); if (results != null) writeSummary(results, copied, skipped, failed, dryrunCnt); return; }

    } catch (Exception e) { /* silent */ }
    finally { try { if (cursor != null) cursor.close();     appInfo("Job completed: copied=" + copied + ", skipped=" + skipped + ", failed=" + failed + ", dryRun=" + dryrunCnt + ", results=" + (targetCsvOrd != null ? targetCsvOrd.toString() : "(none)") );
} catch (Exception ignore) {} try { if (br != null) br.close(); } catch (Exception ignore) {} try { if (results != null) results.close(); } catch (Exception ignore) {} }
  }
}

// ========================= Helpers used by inner class =========================/** Returns the first CSV field (handles quoted values and escaped quotes). */

private String firstCsvField(final String line) { if (line == null) return null; final String s = line.trim(); if (s.isEmpty()) return ""; if (s.charAt(0) == '"') { StringBuilder sb = new StringBuilder(); boolean inQuote = true; for (int i = 1; i < s.length(); i++) { char c = s.charAt(i); if (inQuote) { if (c == '"') { if (i + 1 < s.length() && s.charAt(i + 1) == '"') { sb.append('"'); i++; } else { inQuote = false; } } else { sb.append(c); } } else { if (c == ',') break; } } return sb.toString().trim(); } int comma = s.indexOf(','); return (comma >= 0) ? s.substring(0, comma).trim() : s; }/** Converts a table cell value to a destination BOrd; supports strings, BOrd, components. */

private BOrd toDestinationOrdVerbose(Object cellObj) { if (cellObj == null) return null; if (cellObj instanceof BOrd) return (BOrd) cellObj; if (cellObj instanceof BValue) { if (cellObj instanceof BString) { final String s = ((BString) cellObj).getString(); final String cleaned = stripQuotes(s); return (cleaned == null || cleaned.isEmpty()) ? null : buildDestinationOrd(cleaned); } } if (cellObj instanceof BObject) { final BComponent c = ((BObject) cellObj).asComponent(); if (c != null) return BOrd.make("station:|slot:" + c.getSlotPath().toString()); } final String cleaned = stripQuotes(cellObj.toString()); if (cleaned == null || cleaned.isEmpty()) return null; return buildDestinationOrd(cleaned); }/** Normalizes destination string to a full BOrd (station:|slot:/path or similar). */

private BOrd buildDestinationOrd(final String cellValue) { final String v = (cellValue == null) ? "" : cellValue.trim(); if (v.isEmpty()) return null; if (v.startsWith("station:")) return BOrd.make(v); if (v.startsWith("slot:/")) return BOrd.make("station:|" + v); if (v.startsWith("/")) return BOrd.make("station:|slot:" + v); return BOrd.make("station:|slot:/" + v); }/** Removes surrounding quotes from a string; supports escaped quotes. */

private String stripQuotes(String s) { if (s == null) return null; s = s.trim(); if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') { return s.substring(1, s.length() - 1).trim(); } return s; }

// ===== Reflection utilities =====
private boolean asBool(Object v) { return (v instanceof Boolean) ? ((Boolean) v).booleanValue() : false; }
private int asInt(Object v) { return (v instanceof Number) ? ((Number) v).intValue() : 0; }
private String asStr(Object v) { return (v == null) ? null : v.toString(); }

private Boolean optBool(final String[] getters, final Boolean defVal) { for (int i = 0; i < getters.length; i++) { try { java.lang.reflect.Method m = this.getClass().getMethod(getters[i]); Object v = m.invoke(this); if (v instanceof Boolean) return (Boolean) v; if (v instanceof BBoolean) return ((BBoolean) v).getBoolean() ? Boolean.TRUE : Boolean.FALSE; } catch (Throwable ignore) {} } return defVal; }
private Integer optInt(final String[] getters, final Integer defVal) { for (int i = 0; i < getters.length; i++) { try { java.lang.reflect.Method m = this.getClass().getMethod(getters[i]); Object v = m.invoke(this); if (v instanceof Number) return ((Number) v).intValue(); if (v instanceof BInteger) return ((BInteger) v).getInt(); } catch (Throwable ignore) {} } return defVal; }
private BOrd optOrd(final String[] getters) { for (int i = 0; i < getters.length; i++) { try { java.lang.reflect.Method m = this.getClass().getMethod(getters[i]); Object v = m.invoke(this); if (v instanceof BOrd) return (BOrd) v; } catch (Throwable ignore) {} } return null; }
private String optString(final String[] getters, final String defVal) { for (int i = 0; i < getters.length; i++) { try { java.lang.reflect.Method m = this.getClass().getMethod(getters[i]); Object v = m.invoke(this); if (v != null) return v.toString(); } catch (Throwable ignore) {} } return defVal; }

// ===== Destination helper =====/** Creates a CSV-safe destination description for logging. */

private String destStrToCsv(final BComponent dst) { try { return (dst != null) ? ("station:|slot:" + dst.getSlotPath().toString()) : ""; } catch (Throwable ignore) { return ""; } }


