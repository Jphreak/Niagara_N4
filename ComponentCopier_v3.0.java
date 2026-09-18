/*
================================================================================
Program: Component Copier (Direct / BQL / CSV) - Niagara N4.15
Author:  F. Lacroix
Version: v3.0
Date:    2026-09-18

Changes
-------
  pre-   Original Component Copier, v1.0 through v2.09. Multi-
  v3.0   destination component copy (Direct / BQL / CSV modes).
         Inspired by Giantsbane's "Photocopier" (https://ddc-talk.com/)
         - this program expands that original concept with multi-mode
         destinations, dry-run support, sample-CSV generation, and an
         integrated link-creation phase. Grew across ten releases:
         results CSV archived with a timestamp every run alongside the
         log (v1.01), Verify action added (v1.02), createSampleCsv
         changed from a property toggle to an Action (v1.03), links-only
         mode added (v1.04), maxArchives + auto-pruning / Cancel action /
         pruneArchives action / UTF-8 + quote-aware CSV parsing (v2.00),
         reverse-mode link/delete ordering fixed and the link phase
         gained live row progress (v2.01), link phase CSV error
         reporting matured over v2.02-v2.05 so every problem row gets
         its own results-CSV row with a never-blank reason
         (describeException()) and the slot shown next to the ord
         (ordWithSlot()), the link phase given its own linkerLogPath /
         linkerResultsCsvPath (v2.06), multiple sources added with a
         recursion guard against a destination nested inside its own
         source (v2.07), componentToCopy and additionalSources merged
         into one multi-line componentSource slot (v2.08), and a
         cosmetic rename to "Component Copier" (v2.09). The earlier
         releases are collapsed into this "pre-v3.0" line.
  v3.0   Synced to LinkCreator's multi-source model; LinkCreator's
         buildLinkName() aligned with this program's so a link created
         by either program's link phase gets the identical name for the
         same source/target/slot inputs. Notable points:
           - Folder-source copy fixed: a folder source used to be
             exploded into its direct children, each copied in its own
             separate Mark.copyTo() call, so the destination mirrored
             the source's contents with no wrapper folder. That broke
             keepAllLinks - Niagara's link-remap only sees the subtree
             inside ONE copyTo() call, so a link from one child to a
             sibling child kept pointing at the ORIGINAL source
             component after copy instead of its new copy (links
             weren't relative to the copy). Every source (folder,
             point, extension, anything) is now copied WHOLE as a
             single unit in one copyTo() call by default, regardless
             of type, so its full subtree - and every internal link in
             it - moves and remaps together. A folder source lands at
             the destination as itself (dst/<folderName>/...) rather
             than flattened into its children.
           - FLATTEN mode: a trailing "*" on a componentSource line
             (".../TESTING_1/*") requests the pre-v3.0 behaviour for
             that one source - the folder's direct children are copied
             individually into the destination with no wrapper folder
             (dst/<subfolder>/point, dst/point). Since Niagara can't
             remap links across separate copyTo() calls,
             processInternalLinks() captures every link internal to
             the folder's subtree BEFORE/AFTER the child copies (walks
             getLinks() on every descendant) and recreates each one at
             the destination through the SAME processLinkRow() the
             linksCsvPath phase uses, so it is fully
             verify/dryRun/reverse-aware and logs to the normal linker
             log/results CSV. Reverse ordering matches the linksCsvPath
             phase: links removed first, then the flattened components.
           - Changes section condensed to the pre-/v3.0 format used by
             ForceRemove / LinkCreator.

Inspiration
-----------
Original concept "Photocopier" by Giantsbane, https://ddc-talk.com/. This
program expands that original idea with multi-mode destinations, dry-run
support, sample-CSV generation, and an integrated link-creation phase.

Purpose
-------
Copy one or more source components into one or many destination containers
(every source is copied to every destination), with an optional second pass
that creates links to the freshly copied components from a separate links
CSV. The copy phase logs to logFilePath / resultsCsvPath; the link phase
logs to its own linkerLogPath / linkerResultsCsvPath. Every action is also
echoed to the Application Director.

Modes
-----
  Direct  Copy source(s) to a single destination Ord
  BQL     Iterate a BQL query - each result row is a destination
  CSV     One destination Ord per row, read from a CSV file

  Special case: if componentSource and copyTo are both unset but
  linksCsvPath is configured, the program runs in links-only mode -
  skipping the copy phase entirely and processing only the link CSV.

Actions
-------
  execute           Run the program in the configured destination mode
  dryRun            Simulate the run without copying, deleting, or linking
  verify            Audit only - check whether the source component
                    already exists at each destination (and, if
                    linksCsvPath is set, whether the listed links
                    exist). Makes no changes.
  createSampleCsv   Write two starter CSV files (copy destinations +
                    links) at sampleCsvPath so the user can edit them
                    in place rather than guessing the format.
  cancel            Stop the current run after the in-flight row
                    finishes. Writes a RUN CANCELLED summary.
  pruneArchives     Apply the maxArchives limit to the log and
                    results CSV archive folders right now, without
                    running a copy/verify pass.

Key features
------------
  - keepAllLinks toggle preserves incoming links on the copied component.
    Every source (folder, point, extension, anything) copies as one
    atomic unit by default, so links between components inside a copied
    folder stay correctly relative to the new copy.
  - FLATTEN mode: a trailing "*" on a componentSource line copies that
    folder's CONTENTS into the destination instead of the folder itself
    (no wrapper folder, matching pre-v3.0 behaviour), and recreates the
    folder's internal links at the destination afterward.
  - deleteComponent toggle removes the source-named component from each
    destination instead of copying
  - Optional post-copy link phase - if linksCsvPath is set, the program
    reads a 5-column links CSV (same format as LinkCreator) and creates
    the listed links after the copy phase finishes. The link phase writes
    its own log + results CSV (linkerLogPath / linkerResultsCsvPath) and
    lists every link row with a Status and a Message reason.
  - createSampleCsv (action) writes two starter CSVs (copy + links) so
    the user can edit them in place rather than guessing the format
  - Auto-archives the active logs AND results CSVs (both copy and linker)
    to timestamped copies on every run. Archive timestamp reflects the
    run trigger time. Old archives beyond maxArchives are auto-pruned.
  - Cancel action stops long-running BQL / CSV passes cleanly.
  - quickGuide String slot displays the on-station user help next to
    the configuration slots.

Outputs
-------
  status (string)        live timestamped progress and final summary
  logFilePath            copy-phase log of every operation
  resultsCsvPath         per-copy CSV: timestamp, names, status, etc.
  linkerLogPath          link-phase log of every operation
  linkerResultsCsvPath   per-link CSV: timestamp, names, slot, status,
                         message, link mode, link name, duration
  Application Director   info / warning / severe lines for ops staff

Quick start
-----------
  1) Set componentSource (one source ORD per line - the first
     non-blank line is the primary source, add more lines for
     additional sources) and copyTo
  2) Set destinationMode (Direct / BQL / CSV)
  3) (Optional) Run Create Sample CSV to get starter CSV files
  4) (Optional) Set linksCsvPath for a post-copy link phase
  5) Right-click  >  Actions  >  dryRun   to preview
  6) Right-click  >  Actions  >  verify   to audit existing state
  7) Right-click  >  Actions  >  execute  to commit
  8) Inspect the log files and results CSVs for full details
================================================================================
*/

private static final java.util.logging.Logger log =
  java.util.logging.Logger.getLogger("ComponentCopier");

private static final String VERSION = "v3.0";

// On-station user help -- written into the read-only quickGuide slot
// during onStart() so it shows up at the bottom of the property sheet.
private static final String QUICK_GUIDE =
  "Component Copier " + "v3.0\n" +
  "=====================================\n" +
  "\n" +
  "Modes (destinationMode):\n" +
  "  Direct  copy source(s) -> one destination\n" +
  "  BQL     copy source(s) -> each BQL row\n" +
  "  CSV     copy source(s) -> each destination ord listed in CSV\n" +
  "\n" +
  "Sources: componentSource holds one source ORD per line. The\n" +
  "first non-blank line is the primary source; further non-blank\n" +
  "lines are extra sources (lines starting with # are ignored).\n" +
  "Every source is copied to every destination.\n" +
  "\n" +
  "Folder sources: by default a folder source is copied WHOLE, as a\n" +
  "single unit - same as any other source type (point, extension,\n" +
  "etc). The destination gets the folder itself with its full subtree\n" +
  "intact (dst/<folderName>/...), and links between components inside\n" +
  "that subtree stay correctly relative to the new copy. Pointing at\n" +
  "e.g. .../TESTING_1 copies TESTING_1 itself into the destination.\n" +
  "\n" +
  "FLATTEN mode: add a trailing \"*\" to a source line, e.g.\n" +
  ".../TESTING_1/*, to instead copy that folder's CONTENTS - each\n" +
  "direct child copied individually into the destination, no wrapper\n" +
  "folder (dst/<subfolder>/point, dst/point). The folder's internal\n" +
  "links (between its own children/descendants) are automatically\n" +
  "recreated at the destination afterward - see the linker log for\n" +
  "detail. Only applies to a folder source; \"*\" on a non-folder\n" +
  "source is ignored.\n" +
  "\n" +
  "Actions:\n" +
  "  Execute           - run the configured mode\n" +
  "  Dry Run           - preview without changes\n" +
  "  Verify            - audit only: check whether the source\n" +
  "                      component already exists at each\n" +
  "                      destination (and whether links from\n" +
  "                      linksCsvPath are present, if set).\n" +
  "                      Makes no changes.\n" +
  "  Create Sample CSV - write starter CSV files (copy + links)\n" +
  "                      to sampleCsvPath. Edit them in place.\n" +
  "  Cancel Run        - stop the current run cleanly after the\n" +
  "                      in-flight row finishes.\n" +
  "  Prune Old Archives- apply the maxArchives limit right now\n" +
  "                      without running a copy/verify pass.\n" +
  "\n" +
  "Copy CSV format (one column, header row required):\n" +
  "  DestinationOrd\n" +
  "\n" +
  "Links CSV format (5 cols, same as LinkCreator):\n" +
  "  BOrd1, Slot1, Direction, BOrd2, Slot2\n" +
  "\n" +
  "Outputs:\n" +
  "  Copy phase  -> logFilePath / resultsCsvPath\n" +
  "  Link phase  -> linkerLogPath / linkerResultsCsvPath\n" +
  "  Link problem rows are listed in the linker results CSV with a\n" +
  "  reason in the Message column and the slot shown next to the ord.\n" +
  "\n" +
  "Steps:\n" +
  "  1) Set componentSource (one ORD per line) and copyTo\n" +
  "  2) Choose destinationMode (Direct/BQL/CSV)\n" +
  "  3) (optional) Create Sample CSV to get starter files,\n" +
  "     then edit them and point copyTo / linksCsvPath at them\n" +
  "  4) dryRun first to preview\n" +
  "  5) verify  to audit what is already in place\n" +
  "  6) execute to commit\n" +
  "  7) check the log files and results CSVs for details\n" +
  "\n" +
  "Tips:\n" +
  "  - componentSource: one source ORD per line. Each source x\n" +
  "    each destination is attempted; a destination that is the\n" +
  "    source itself, or is nested inside the source, is always\n" +
  "    skipped (prevents recursive copies).\n" +
  "  - keepAllLinks=true preserves links on copy. Every source\n" +
  "    copies as one atomic unit by default, so links between\n" +
  "    components inside a copied folder stay relative to the new\n" +
  "    copy. Add \"*\" to a folder source line to flatten its\n" +
  "    contents instead (no wrapper folder) - internal links are\n" +
  "    recreated at the destination afterward either way.\n" +
  "  - deleteComponent=true (Reverse Changes) UNDOES a previous\n" +
  "    run: the link phase removes the listed links FIRST, then\n" +
  "    the copy phase removes the source-named components from\n" +
  "    each destination. dryRun previews reverse-mode too.\n" +
  "  - verify ignores deleteComponent and dryRun toggles -\n" +
  "    it only audits whether the named component exists.\n" +
  "  - Links-only mode: leave componentSource and copyTo\n" +
  "    unset and configure linksCsvPath. The copy phase is\n" +
  "    skipped and only the link CSV is processed (creates in\n" +
  "    normal mode, removes in reverse mode). Useful when\n" +
  "    wiring up (or unwiring) pre-existing components.\n" +
  "  - maxArchives caps how many timestamped log/CSV archives\n" +
  "    are kept (default 10). 0 = keep all.";

private String now()
{
  return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    .format(new java.util.Date());
}

// ----------------------------------------------------
// Exception describer (v2.06)
// Build a human-readable reason from an exception that is NEVER empty
// or "null". Niagara resolve failures sometimes carry a null detail
// message, which used to leave the linker CSV Message column blank. We
// fall back to the exception's simple class name, and append the
// underlying cause when there is one (the cause is usually where the
// real reason lives -- e.g. a wrapper exception around "slot not found").
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

// Format an ord + slot for the linker results CSV as "<ord> [<slot>]"
// so the problem rows show which slot was involved, not just the
// component ord. Falls back to just the ord when the slot is blank. (v2.06)
private String ordWithSlot(String ord, String slot)
{
  String o = (ord == null) ? "" : ord;
  if (slot == null || slot.trim().length() == 0) return o;
  return o + " [" + slot.trim() + "]";
}

// Format an elapsed nanoTime delta as milliseconds with 3 decimals. (v2.06)
private String durMs(long t0)
{
  return String.format(java.util.Locale.ROOT, "%.3f",
    (System.nanoTime() - t0) / 1000000.0);
}

// ----------------------------------------------------
// Cancellation flag
// ----------------------------------------------------
// Set by onCancel(); checked between rows in the BQL and CSV loops.
// volatile because the cancel action and the running job may execute
// on different threads in some Niagara configurations.
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
  return "file:^logs/ComponentCopier.log";
}

// Linker-phase log path (v2.06). Separate from the copy-phase log.
private String resolveLinkerLogPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("linkerLogPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/ComponentCopier_linker.log";
}

// Build a timestamped archive path from the given active file path.
// Inserts "_yyyy-MM-dd_HH-mm-ss" before the file extension, where the
// timestamp is the current run's trigger time (i.e. when this archive
// operation runs). This is more reliable than reading the file's
// creation time -- on Windows, file tunneling can make the "creation"
// time stick to a stale value across renames, causing repeated archives
// to collide on the same filename.
//   file:^logs/ComponentCopier.log
//     -> file:^logs/ComponentCopier_2026-04-27_12-23-25.log
//   file:^logs/ComponentCopier_results.csv
//     -> file:^logs/ComponentCopier_results_2026-04-27_12-23-25.csv
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
  return "file:^logs/ComponentCopier_results.csv";
}

// Linker-phase results CSV path (v2.06). Separate from the copy-phase
// results CSV so copy rows and link rows never interleave.
private String resolveLinkerResultsCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord =
      (javax.baja.naming.BOrd) get("linkerResultsCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/ComponentCopier_linker_results.csv";
}

// Set by onDryRun() to make isDryRun() report true for the duration of
// that one invocation. onExecute() resets it on entry.
private boolean dryRunActive = false;

private boolean isDryRun()
{
  return dryRunActive;
}

// Set by onVerify() to make isVerify() report true for the duration of
// that one invocation. onExecute() and onDryRun() both reset it on entry.
private boolean verifyActive = false;

private boolean isVerify()
{
  return verifyActive;
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

private String resolveSampleCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("sampleCsvPath");
    if (ord != null && !ord.isNull()) return ord.toString().trim();
  }
  catch (Exception ignore) {}
  return "file:^logs/ComponentCopier_SAMPLE.csv";
}

private String resolveLinksCsvPath()
{
  try
  {
    javax.baja.naming.BOrd ord = (javax.baja.naming.BOrd) get("linksCsvPath");
    if (ord != null && !ord.isNull())
    {
      String s = ord.toString().trim();
      if (s.length() > 0) return s;
    }
  }
  catch (Exception ignore) {}
  return "";
}

// ----------------------------------------------------
// Sources (v2.08 - componentSource merges what were componentToCopy
// and additionalSources into one multi-line slot)
// ----------------------------------------------------
// Reads the componentSource slot (baja:String, one ORD per line).
private String resolveComponentSource()
{
  try
  {
    Object val = get("componentSource");
    if (val != null)
    {
      String s = val.toString().trim();
      if (s.length() > 0) return s;
    }
  }
  catch (Exception ignore) {}
  return "";
}

// Builds the effective list of source ORD strings for this run: each
// non-blank, non-comment ("#"), non-duplicate line of componentSource,
// in order. The first line in the list is the primary source.
private java.util.List getSourceOrdStrings()
{
  java.util.List out = new java.util.ArrayList();
  java.util.Set seen = new java.util.HashSet();

  String raw = resolveComponentSource();
  if (raw.length() > 0)
  {
    String[] lines = raw.split("[\\r\\n,]+");
    for (int i = 0; i < lines.length; i++)
    {
      String s = lines[i].trim();
      if (s.length() == 0 || s.startsWith("#")) continue;
      if (seen.add(s)) out.add(s);
    }
  }

  return out;
}

// A trailing "*" on a componentSource line (".../TESTING_1/*" or
// ".../TESTING_1*") requests FLATTEN mode for that one source: copy the
// folder's CONTENTS into the destination (no wrapper folder for the
// folder itself), same as pre-v3.0 behaviour. Every other source line
// (no "*") copies the source WHOLE, as a single unit - see
// expandSource(). Has no effect on a non-folder source.
private boolean isFlattenMarker(String rawOrdStr)
{
  return rawOrdStr != null && rawOrdStr.trim().endsWith("*");
}

// Strips a trailing flatten marker ("*" or "/*") off a raw
// componentSource line, returning the plain ORD string to resolve.
private String stripFlattenMarker(String rawOrdStr)
{
  String s = rawOrdStr.trim();
  if (!s.endsWith("*")) return s;
  s = s.substring(0, s.length() - 1);
  if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
  return s;
}

// Resolves a source ORD string to a BComponent, logging (and
// returning null) if it cannot be resolved to one. Used by the
// multi-source loop in each execute* method.
private javax.baja.sys.BComponent resolveSourceComponent(String ordStr)
{
  try
  {
    javax.baja.naming.BOrd ord = javax.baja.naming.BOrd.make(ordStr);
    Object resolved = ord.resolve().get();
    if (resolved instanceof javax.baja.sys.BComponent)
      return (javax.baja.sys.BComponent) resolved;
    String msg = "SOURCE ERROR: " + ordStr +
      " did not resolve to a component (" +
      resolved.getClass().getName() + ")";
    setStatus("[" + now() + "] " + msg);
    log.warning("[ComponentCopier] " + msg);
    writeToLog(msg);
  }
  catch (Exception e)
  {
    String msg = "SOURCE ERROR: could not resolve '" + ordStr + "' - " +
      describeException(e);
    setStatus("[" + now() + "] " + msg);
    log.warning("[ComponentCopier] " + msg);
    writeToLog(msg);
  }
  return null;
}

// Import-free folder test (matches ForceRemove's): baja:Folder or any
// type whose name ends in "Folder". Needs no extra import.
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

// Expand a resolved source into the list of components to copy.
//
// Default (flatten=false): the source is copied WHOLE, as a single unit,
// via one Mark.copyTo() call - regardless of type (folder, point,
// extension, etc.). The list is [src]. Because the whole subtree travels
// in one copyTo() call, Niagara's own link-remap correctly relativizes
// links between components inside a copied folder - this is what fixes
// keepAllLinks for folder sources.
//
// flatten=true (a trailing "*" on the source line - see
// isFlattenMarker()) restores the pre-v3.0 behaviour for a folder
// source: the list is its DIRECT children, each copied whole in its own
// copyTo() call, so the destination mirrors the folder's CONTENTS with
// NO wrapper folder (dst/<subfolder>/point, dst/point). Since each child
// is copied separately, Niagara's link-remap can't see across those
// calls, so links between siblings inside the folder are NOT
// automatically preserved by copyTo() - the caller (see
// processInternalLinks()) must capture and recreate them afterward.
// flatten has no effect on a non-folder source - it always copies whole.
private java.util.List expandSource(
  javax.baja.sys.BComponent src, boolean flatten)
{
  java.util.List out = new java.util.ArrayList();
  if (src == null) return out;

  if (!flatten || !isFolderComponent(src))
  {
    out.add(src);
    return out;
  }

  javax.baja.sys.BComponent[] kids;
  try { kids = src.getChildComponents(); }
  catch (Throwable t)
  {
    writeToLog("EXPAND ERROR listing " + safeName(src) + ": " +
      describeException(t));
    return out;
  }
  for (int i = 0; i < kids.length; i++)
    out.add(kids[i]);   // each direct child copied whole (subtree rides along)
  return out;
}

private String safeName(javax.baja.sys.BComponent c)
{
  try { return c.getSlotPath().toString(); }
  catch (Throwable t) { return "<unknown>"; }
}

// ======================================================
// Internal-link recreation for FLATTEN-mode folder sources (v3.0)
// ======================================================
// A FLATTEN-mode folder source (expandSource(src, true)) copies each
// direct child in its own separate Mark.copyTo() call, so Niagara's
// link-remap never sees the folder's children together and can't
// relativize a link between two siblings. This block captures every
// link INTERNAL to the source folder's subtree (both ends under the
// folder) and recreates the equivalent link at the destination via the
// SAME processLinkRow() the linksCsvPath phase uses - so it is fully
// verify/dryRun/reverse aware and logs to the normal linker log/results
// CSV alongside any configured linksCsvPath links.

// Reflection helper for BLink introspection (ported from ForceRemove's
// reverse-support captureLinks()). Calls a no-arg method, returns null
// on any failure.
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

// One internal link, captured as full slot paths + slot names under the
// SOURCE folder (not yet remapped to the destination).
private static class InternalLink
{
  String srcPath, srcSlot, tgtPath, tgtSlot;
  InternalLink(String srcPath, String srcSlot, String tgtPath, String tgtSlot)
  {
    this.srcPath = srcPath; this.srcSlot = srcSlot;
    this.tgtPath = tgtPath; this.tgtSlot = tgtSlot;
  }
}

// Recursively collect every component in comp's subtree, comp included.
private void collectSubtree(
  javax.baja.sys.BComponent comp, java.util.List out)
{
  out.add(comp);
  javax.baja.sys.BComponent[] kids;
  try { kids = comp.getChildComponents(); }
  catch (Throwable t) { return; }
  for (int i = 0; i < kids.length; i++)
    collectSubtree(kids[i], out);
}

// Walk folder's whole subtree and capture every link whose source AND
// target are both inside that subtree (an "internal" link). A BLink is
// a child slot on its TARGET component, so getLinks() on each subtree
// member returns that member's inbound links.
private java.util.List captureInternalLinks(javax.baja.sys.BComponent folder)
{
  java.util.List out = new java.util.ArrayList();
  String folderPath;
  try { folderPath = folder.getSlotPath().toString(); }
  catch (Throwable t) { return out; }
  String folderPrefix = folderPath + "/";

  java.util.List subtree = new java.util.ArrayList();
  collectSubtree(folder, subtree);

  for (int i = 0; i < subtree.size(); i++)
  {
    javax.baja.sys.BComponent tgtOwner =
      (javax.baja.sys.BComponent) subtree.get(i);

    javax.baja.sys.BLink[] links;
    try { links = tgtOwner.getLinks(); }
    catch (Throwable t) { continue; }

    for (int j = 0; j < links.length; j++)
    {
      javax.baja.sys.BLink lk = links[j];
      try
      {
        String targetSlot = strOf(invoke0(lk, "getTargetSlotName"));
        String sourceSlot = strOf(invoke0(lk, "getSourceSlotName"));
        if (sourceSlot.length() == 0 || targetSlot.length() == 0) continue;

        javax.baja.sys.BComponent srcComp = null;
        try
        {
          Object srcOrdObj = invoke0(lk, "getSourceOrd");
          if (srcOrdObj instanceof javax.baja.naming.BOrd)
          {
            Object so = ((javax.baja.naming.BOrd) srcOrdObj)
              .resolve(tgtOwner, null).get();
            if (so instanceof javax.baja.sys.BComponent)
              srcComp = (javax.baja.sys.BComponent) so;
          }
        }
        catch (Throwable ignore) {}
        if (srcComp == null) continue;

        String tgtPath = tgtOwner.getSlotPath().toString();
        String srcPath = srcComp.getSlotPath().toString();

        // Internal only: both ends inside the folder subtree (or on the
        // folder itself, in the unlikely case a link touches it directly).
        boolean srcInside = srcPath.equals(folderPath) || srcPath.startsWith(folderPrefix);
        boolean tgtInside = tgtPath.equals(folderPath) || tgtPath.startsWith(folderPrefix);
        if (!srcInside || !tgtInside) continue;

        out.add(new InternalLink(srcPath, sourceSlot, tgtPath, targetSlot));
      }
      catch (Throwable t)
      {
        writeToLog("INTERNAL LINK CAPTURE ERROR under " + folderPath + ": " +
          describeException(t));
      }
    }
  }

  return out;
}

// Capture folder's internal links, remap each endpoint from under the
// source folder to under the destination (string substitution -
// copyTo() preserves each child's relative structure and name, so a
// path under folderPath maps 1:1 to the same relative path under dst),
// resolve both remapped endpoints at the destination, and recreate the
// link via the same processLinkRow() the linksCsvPath phase uses. An
// endpoint that fails to resolve at the destination (e.g. a SKIPPED
// copy) is logged and that one link is skipped; the rest still run.
private void processInternalLinks(
  javax.baja.sys.BComponent folder, javax.baja.sys.BComponent dst)
{
  String folderPath, dstPath;
  try
  {
    folderPath = folder.getSlotPath().toString();
    dstPath = dst.getSlotPath().toString();
  }
  catch (Throwable t)
  {
    writeToLog("INTERNAL LINKS ERROR: could not read source/destination path - " +
      describeException(t));
    return;
  }

  java.util.List links = captureInternalLinks(folder);
  if (links.isEmpty()) return;

  writeToLinkerLog("Internal links captured under '" + folderPath +
    "': " + links.size() + " - remapping to '" + dstPath + "'" +
    (isDeleteMode() && !isVerify() ? " (reverse)" : ""));

  for (int i = 0; i < links.size(); i++)
  {
    InternalLink lk = (InternalLink) links.get(i);

    String newSrcPath = dstPath + lk.srcPath.substring(folderPath.length());
    String newTgtPath = dstPath + lk.tgtPath.substring(folderPath.length());

    javax.baja.sys.BComponent srcComp = null;
    javax.baja.sys.BComponent tgtComp = null;
    try
    {
      Object so = javax.baja.naming.BOrd.make(normalizeOrd(newSrcPath)).resolve().get();
      if (so instanceof javax.baja.sys.BComponent) srcComp = (javax.baja.sys.BComponent) so;
    }
    catch (Throwable ignore) {}
    try
    {
      Object to = javax.baja.naming.BOrd.make(normalizeOrd(newTgtPath)).resolve().get();
      if (to instanceof javax.baja.sys.BComponent) tgtComp = (javax.baja.sys.BComponent) to;
    }
    catch (Throwable ignore) {}

    if (srcComp == null || tgtComp == null)
    {
      writeToLinkerLog("INTERNAL LINK SKIPPED: could not resolve at destination - " +
        newSrcPath + "[" + lk.srcSlot + "] -> " + newTgtPath + "[" + lk.tgtSlot + "]");
      continue;
    }

    processLinkRow(srcComp, lk.srcSlot, tgtComp, lk.tgtSlot);
  }
}

// Read the maxArchives slot. Returns 10 if the slot is missing or
// unreadable. 0 (or negative) means "keep all archives, never prune".
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
// updateVersion() -- the slot is a baja:String so we try a String
// setter first, then a BString setter as a fallback.
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
// File path resolution
// Resolves a Niagara ORD-style path ("file:^logs/foo.log") or a plain
// relative/absolute path into a real java.io.File rooted at station home.
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
    log.warning("[ComponentCopier] " + msg);
    return null;
  }

  String msg = "PATH SANDBOX: refusing '" + original +
    "' - resolves outside the station home folder";
  setStatus("[" + now() + "] " + msg);
  log.warning("[ComponentCopier] " + msg);
  return null;
}

// ----------------------------------------------------
// Single unified file append method
// Appends a line to the file, creating the file (and parent folder)
// if they don't already exist.
// ----------------------------------------------------
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

// Linker-phase writers (v2.06).
private void writeToLinkerLog(String message)
{
  appendLine(resolveLinkerLogPath(), "[" + now() + "] " + message);
}

private void writeToLinkerResults(String line)
{
  appendLine(resolveLinkerResultsCsvPath(), line);
}

// Truncate (or create empty) the file, making the parent folder if needed.
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

// Generic archive helper: rename the active file to a timestamped
// copy, falling back to byte-copy + truncate if rename fails.
// Used for both the active log and the results CSV so they share
// identical archive semantics.
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

// ----------------------------------------------------
// Archive pruning
// After each archive operation, scan the parent folder for archives
// matching the active file's stem + 19-char timestamp + extension and
// delete the oldest beyond maxArchives. Names sort lexicographically
// in chronological order because of the yyyy-MM-dd_HH-mm-ss format.
// ----------------------------------------------------
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

// Linker-phase archive helpers (v2.06).
private void archiveLinkerLogFile()
{
  archiveFile(resolveLinkerLogPath(), "LINKER-LOG");
  pruneArchives(resolveLinkerLogPath());
}

private void archiveLinkerResultsCsv()
{
  archiveFile(resolveLinkerResultsCsvPath(), "LINKER-RESULTS-CSV");
  pruneArchives(resolveLinkerResultsCsvPath());
}

// ----------------------------------------------------
// Results CSV
// ----------------------------------------------------
private void initResultsCsv()
{
  // Archive the previous run's CSV (if any) to a timestamped copy
  // before we wipe the file and write a fresh header. Same pattern
  // as archiveLogFile() so log + CSV histories stay in lockstep.
  archiveResultsCsv();
  clearFile(resolveResultsCsvPath());
  writeToResults(
    "Timestamp,SourceName,SourceSlotPath," +
    "DestinationName,DestinationSlotPath," +
    "Status,Message,Mode,KeepAllLinks,DurationMs");
}

// Linker results CSV init (v2.06). Archives the previous run's linker
// CSV, then writes a fresh header. Column layout mirrors LinkCreator's
// results CSV so the two programs read alike.
private void initLinkerResultsCsv()
{
  archiveLinkerResultsCsv();
  clearFile(resolveLinkerResultsCsvPath());
  writeToLinkerResults(
    "Timestamp,SourceName,SourceSlotPath," +
    "TargetName,TargetSlotPath," +
    "Status,Message,LinkMode,LinkName,DurationMs");
}

// Compact summary row: "Total,<summary text>" -- two fields, no
// padding commas. The leading blank line keeps the visual gap
// between per-copy rows and the summary when opened in Excel.
private void writeResultsSummary(
  int copied, int skipped, int failed, int dryrun,
  int deleted, long totalMs)
{
  appendLine(resolveResultsCsvPath(), "");
  if (isVerify())
  {
    // In verify mode the slots are reused as Found/Missing/Errors.
    writeToResults("Total,Found:" + copied +
      " Missing:" + skipped +
      " Errors:" + failed +
      " TotalTime:" + totalMs + "ms" +
      (isCancelled() ? " [CANCELLED]" : ""));
    return;
  }
  writeToResults("Total,Copied:" + copied +
    " Skipped:" + skipped +
    " Failed:" + failed +
    " DryRun:" + dryrun +
    " Deleted:" + deleted +
    " TotalTime:" + totalMs + "ms" +
    (isCancelled() ? " [CANCELLED]" : ""));
}

// Linker results summary row (v2.06). Per-mode counts that match the
// detail rows written during the link phase.
private void writeLinkerSummary(
  int linked, int removed, int skipped, int errors, int dryrun,
  int found, int missing)
{
  appendLine(resolveLinkerResultsCsvPath(), "");
  String tail = isCancelled() ? " [CANCELLED]" : "";
  if (isVerify())
    writeToLinkerResults("Total,Found:" + found +
      " Missing:" + missing +
      " Errors:" + errors + tail);
  else if (isDeleteMode())
    writeToLinkerResults("Total,Removed:" + removed +
      " Skipped:" + skipped +
      " Errors:" + errors +
      " DryRun:" + dryrun + tail);
  else
    writeToLinkerResults("Total,Linked:" + linked +
      " Skipped:" + skipped +
      " Errors:" + errors +
      " DryRun:" + dryrun + tail);
}

private String csvEscape(String s)
{
  if (s == null) s = "";
  if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ||
      s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
    return '"' + s.replace("\"", "\"\"") + '"';
  return s;
}

// Write a single row to the linker results CSV (v2.06). The slot is
// folded into the SourceSlotPath / TargetSlotPath columns via
// ordWithSlot() so every row shows ord AND slot.
private void writeLinkerRow(
  String srcName, String srcOrdOrPath, String srcSlot,
  String tgtName, String tgtOrdOrPath, String tgtSlot,
  String status, String message, String linkMode,
  String linkName, String durStr)
{
  writeToLinkerResults(
    csvEscape(now()) + "," +
    csvEscape(srcName) + "," +
    csvEscape(ordWithSlot(srcOrdOrPath, srcSlot)) + "," +
    csvEscape(tgtName) + "," +
    csvEscape(ordWithSlot(tgtOrdOrPath, tgtSlot)) + "," +
    csvEscape(status) + "," +
    csvEscape(message) + "," +
    csvEscape(linkMode) + "," +
    csvEscape(linkName) + "," +
    durStr);
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
  java.io.InputStream is = null;
  java.io.BufferedReader br = null;
  try
  {
    javax.baja.file.BIFile csvFile =
      (javax.baja.file.BIFile) csvOrd.resolve().get();
    is = csvFile.getInputStream();
    br = new java.io.BufferedReader(
      new java.io.InputStreamReader(is, "UTF-8"));
    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      if (!line.trim().isEmpty()) count++;
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

// Same as countCsvRows but takes a java.io.File directly. Used by the
// link phase, which reads its CSV via resolveToFile() instead of a
// BOrd resolution. Lets the link loop pre-count data rows so the
// status field can show "Link row X of Y..." while it runs.
private int countLinksCsvRows(java.io.File linksFile)
{
  int count = 0;
  java.io.FileInputStream fis = null;
  java.io.BufferedReader br = null;
  try
  {
    fis = new java.io.FileInputStream(linksFile);
    br = new java.io.BufferedReader(
      new java.io.InputStreamReader(fis, "UTF-8"));
    String line;
    int rowNum = 0;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue;
      if (!line.trim().isEmpty()) count++;
    }
  }
  catch (Exception ignore) {}
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (fis != null) try { fis.close(); } catch (Exception ignore) {}
  }
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

// Quote-aware CSV row parser. Splits a line into fields on commas
// but treats commas inside double-quoted fields as literal. Doubled
// quotes ("") inside a quoted field are unescaped to a single ".
// Used by the 5-column links CSV reader so slot names that contain
// commas (when properly quoted) parse correctly. Plain unquoted
// CSVs are handled the same as before.
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

private int[] countResult(int[] counts, String result)
{
  // counts[0]=copied, [1]=skipped, [2]=failed, [3]=dryrun, [4]=deleted
  // In verify mode the same array is reused as:
  //   counts[0]=found, [1]=missing, [2]=errors
  if (result.equals("COPIED"))       counts[0]++;
  else if (result.equals("SKIPPED")) counts[1]++;
  else if (result.equals("FAILED"))  counts[2]++;
  else if (result.equals("DRYRUN"))  counts[3]++;
  else if (result.equals("DELETED")) counts[4]++;
  else if (result.equals("FOUND"))   counts[0]++;
  else if (result.equals("MISSING")) counts[1]++;
  else if (result.equals("ERROR"))   counts[2]++;
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
      boolean dry = isDryRun();
      String detail = (dry ? "DRYRUN (delete): would skip - "
                           : "SKIPPED (delete): ") +
        srcName + " not found at destination " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.warning("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        (dry ? "DRYRUN,Would skip - component not found at destination"
             : "SKIPPED,Component not found at destination") + "," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
      return dry ? "DRYRUN" : "SKIPPED";
    }

    // Dry run in delete mode
    if (isDryRun())
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DRYRUN (delete): would remove " + srcName +
        " from " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.info("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "DRYRUN,Would delete," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "DRYRUN";
    }

    // Perform delete
    try
    {
      dst.remove(srcName);
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "DELETED: " + srcName + " from " + dstName +
        " (" + durStr + "ms)";
      setStatus("[" + now() + "] " + detail);
      log.info("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "DELETED,," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "DELETED";
    }
    catch (Exception e)
    {
      String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
      String detail = "FAILED (delete): " + srcName +
        " from " + dstName + " - " + e.getMessage();
      setStatus("[" + now() + "] " + detail);
      log.severe("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "FAILED," + csvEscape(e.getMessage()) + "," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "FAILED";
    }
  }

  // ----------------------------------------------------
  // COPY MODE - skip checks
  // ----------------------------------------------------

  // 1. Source and destination are the same component
  if (srcPath.equals(dstPath))
  {
    boolean dry = isDryRun();
    String detail = (dry ? "DRYRUN: would skip - source and destination are the same"
                         : "SKIPPED: source and destination are the same") +
      " - " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - source and destination are the same"
           : "SKIPPED,Source and destination are the same") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // 1b. Destination is nested inside the source (destination slot path
  //     starts with "<source slot path>/"). Copying a container to a
  //     location underneath itself lands the copy inside its own
  //     source tree; if anything later re-scans that tree (a broad
  //     BQL query in particular) it can find the fresh copy and copy
  //     it again, snowballing into thousands of nested folders. (v2.07)
  if (dstPath.startsWith(srcPath + "/"))
  {
    boolean dry = isDryRun();
    String detail = (dry ? "DRYRUN: would skip - "
                         : "SKIPPED: ") +
      "destination " + dstName + " is inside source " + srcName +
      " (would create a recursive copy)";
    setStatus("[" + now() + "] " + detail);
    log.warning("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - destination is inside source (recursive)"
           : "SKIPPED,Destination is inside source (recursive)") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // 2. Destination already contains a component with the same name
  if (dst.getSlot(srcName) != null)
  {
    boolean dry = isDryRun();
    String detail = (dry ? "DRYRUN: would skip - "
                         : "SKIPPED: ") +
      dstName + " already contains a component named " + srcName;
    setStatus("[" + now() + "] " + detail);
    log.warning("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - component already exists at destination"
           : "SKIPPED,Component already exists at destination") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // 3. Destination slot path is empty
  if (dstPath == null || dstPath.trim().isEmpty())
  {
    boolean dry = isDryRun();
    String detail = dry ? "DRYRUN: would skip - destination is not a valid container"
                        : "SKIPPED: destination is not a valid container";
    setStatus("[" + now() + "] " + detail);
    log.warning("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      (dry ? "DRYRUN,Would skip - destination is not a valid container"
           : "SKIPPED,Destination is not a valid container") + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + ",0.000");
    return dry ? "DRYRUN" : "SKIPPED";
  }

  // ----------------------------------------------------
  // COPY MODE - dry run
  // ----------------------------------------------------
  if (isDryRun())
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "DRYRUN: would copy " + srcName + " -> " + dstName;
    setStatus("[" + now() + "] " + detail);
    log.info("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "DRYRUN,Would copy," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
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

    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "COPIED: " + srcName + " -> " + dstName +
      " (" + durStr + "ms)";
    setStatus("[" + now() + "] " + detail);
    log.info("[ComponentCopier] SUCCESS - " + detail);
    writeToLog("SUCCESS - " + detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "COPIED,," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "COPIED";
  }
  catch (Exception e)
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
        (System.nanoTime() - t0) / 1000000.0);
    String detail = "FAILED: " + srcName + " -> " + dstName +
      " - " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "FAILED," + csvEscape(e.getMessage()) + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "FAILED";
  }
}

// ----------------------------------------------------
// Core processor - verify (audit-only)
// Checks whether a component with the source's name already exists
// at the destination. Makes no changes. Returns:
//   FOUND   -- a child slot named srcName exists at dst
//   MISSING -- no such child slot at dst
//   ERROR   -- something went wrong while inspecting the pair
// ----------------------------------------------------
private String processVerify(
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
    writeToLog("VERIFY ERROR: could not resolve component names - " +
      e.getMessage());
    return "ERROR";
  }

  long t0 = System.nanoTime();

  try
  {
    boolean exists = (dst.getSlot(srcName) != null);
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
      (System.nanoTime() - t0) / 1000000.0);

    if (exists)
    {
      String detail = "VERIFY OK: " + srcName + " exists at " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.info("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "FOUND,Component exists at destination," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "FOUND";
    }
    else
    {
      String detail = "VERIFY MISSING: " + srcName + " not found at " + dstName;
      setStatus("[" + now() + "] " + detail);
      log.warning("[ComponentCopier] " + detail);
      writeToLog(detail);
      writeToResults(
        csvEscape(now()) + "," +
        csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
        csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
        "MISSING,Component not found at destination," +
        csvEscape(mode) + "," +
        csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
        durStr);
      return "MISSING";
    }
  }
  catch (Exception e)
  {
    String durStr = String.format(java.util.Locale.ROOT, "%.3f",
      (System.nanoTime() - t0) / 1000000.0);
    String detail = "VERIFY ERROR: " + srcName + " at " + dstName +
      " - " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ComponentCopier] " + detail);
    writeToLog(detail);
    writeToResults(
      csvEscape(now()) + "," +
      csvEscape(srcName) + "," + csvEscape(srcPath) + "," +
      csvEscape(dstName) + "," + csvEscape(dstPath) + "," +
      "ERROR," + csvEscape(e.getMessage()) + "," +
      csvEscape(mode) + "," +
      csvEscape(Boolean.toString(isKeepAllLinks())) + "," +
      durStr);
    return "ERROR";
  }
}

// Build a one-line summary appropriate for the current run mode.
// Verify runs report Found/Missing/Errors. Copy/dry-run/delete runs
// keep the original Copied/Skipped/Failed/DryRun/Deleted layout.
private String buildSummary(
  String modeLabel, int[] counts, long totalMs)
{
  String tail = (isCancelled() ? " [CANCELLED]" : "");
  if (isVerify())
  {
    return modeLabel + " complete - Found:" + counts[0] +
      " Missing:" + counts[1] + " Errors:" + counts[2] +
      " TotalTime:" + totalMs + "ms" + tail;
  }
  return modeLabel + " complete - Copied:" + counts[0] +
    " Skipped:" + counts[1] + " Failed:" + counts[2] +
    " DryRun:" + counts[3] + " Deleted:" + counts[4] +
    " TotalTime:" + totalMs + "ms" + tail;
}

// ----------------------------------------------------
// Sample CSVs - emit two self-documenting starter files:
// one for copy-mode destinations, one for the post-copy links CSV.
// ----------------------------------------------------
private String deriveLinksSamplePath(String copySamplePath)
{
  if (copySamplePath == null) return "";
  int slashIdx = Math.max(
    copySamplePath.lastIndexOf('/'), copySamplePath.lastIndexOf('\\'));
  int dotIdx = copySamplePath.lastIndexOf('.');
  if (dotIdx > slashIdx)
    return copySamplePath.substring(0, dotIdx) + "_links" +
      copySamplePath.substring(dotIdx);
  return copySamplePath + "_links";
}

private void writeSampleCsvs()
{
  // ---- Copy-mode sample (one column: destination ORDs) ----
  String copyPath = resolveSampleCsvPath();
  clearFile(copyPath);
  appendLine(copyPath,
    "DestinationOrd (one ORD per row; container that will receive the copy)");
  appendLine(copyPath, "station:|slot:/Drivers/Site1/Floor1/Zone1");
  appendLine(copyPath, "station:|slot:/Drivers/Site1/Floor1/Zone2");
  appendLine(copyPath, "slot:/Drivers/Site1/Floor2/Zone1");
  appendLine(copyPath, "slot:/Drivers/Site2/Floor1/Lobby");
  writeToLog("Sample copy CSV written to: " + copyPath);

  // ---- Links sample (5 columns, same format as LinkCreator) ----
  String linksPath = deriveLinksSamplePath(copyPath);
  clearFile(linksPath);
  appendLine(linksPath,
    "BOrd1 (source if Direction is '>'),Slot1," +
    "Direction (> sends 1->2; < sends 2->1)," +
    "BOrd2 (target if Direction is '>'),Slot2");
  appendLine(linksPath,
    "station:|slot:/Drivers/Sensors/TempSensor1,Out,>," +
    "station:|slot:/Drivers/Site1/Floor1/Zone1/CopiedComponent,TempIn");
  appendLine(linksPath,
    "station:|slot:/Drivers/Sensors/TempSensor2,Out,>," +
    "station:|slot:/Drivers/Site1/Floor1/Zone2/CopiedComponent,TempIn");
  appendLine(linksPath,
    "slot:/Drivers/Site1/Floor1/Zone1/CopiedComponent,Output,<," +
    "slot:/Drivers/Site1/Floor1/Zone1/Damper,Command");
  writeToLog("Sample links CSV written to: " + linksPath);

  log.info("[ComponentCopier] Sample CSVs written: " +
    copyPath + " and " + linksPath);
}

// ----------------------------------------------------
// Link creation (post-copy) - same format as LinkCreator CSV
// 5 cols: BOrd1, Slot1, Direction (> or <), BOrd2, Slot2
// ----------------------------------------------------
private String buildLinkName(
  javax.baja.sys.BComponent srcComp, String srcSlotStr, String tgtSlotStr)
{
  String srcPath = srcComp.getSlotPath().toString();
  int pathHash = Math.abs(srcPath.hashCode()) % 10000;
  return "link_" + srcComp.getName() + "_" + srcSlotStr +
    "_to_" + tgtSlotStr + "_" + pathHash;
}

// Single link result: LINKED / SKIPPED / ERROR / DRYRUN / FOUND / MISSING / REMOVED
// (FOUND / MISSING are verify-mode outcomes and never mutate the station.)
// (REMOVED is a reverse-mode outcome -- the link was deleted from the target.)
// v2.06: each outcome now writes a row to the linker results CSV with a
// reason in the Message column, the slot folded into the slot-path
// columns, and a duration. Link-time failures use describeException().
private String processLinkRow(
  javax.baja.sys.BComponent srcComp, String srcSlotStr,
  javax.baja.sys.BComponent tgtComp, String tgtSlotStr)
{
  long t0 = System.nanoTime();
  String linkMode = isVerify()    ? "Verify"
                  : isDeleteMode() ? "Reverse"
                                   : "Create";

  String srcName = "";
  String srcPath = "";
  String tgtName = "";
  String tgtPath = "";
  String linkName = "";

  try
  {
    srcName  = srcComp.getName().toString();
    srcPath  = srcComp.getSlotPath().toString();
    tgtName  = tgtComp.getName().toString();
    tgtPath  = tgtComp.getSlotPath().toString();
    linkName = buildLinkName(srcComp, srcSlotStr, tgtSlotStr);

    // ---- VERIFY: audit only ----
    if (isVerify())
    {
      boolean exists = (tgtComp.getSlot(linkName) != null);
      String durStr = durMs(t0);
      if (exists)
      {
        writeToLinkerLog("LINK VERIFY OK: " + srcName + "[" + srcSlotStr +
          "] -> " + tgtName + "[" + tgtSlotStr + "] EXISTS");
        writeLinkerRow(srcName, srcPath, srcSlotStr,
          tgtName, tgtPath, tgtSlotStr,
          "FOUND", "Link exists", linkMode, linkName, durStr);
        return "FOUND";
      }
      writeToLinkerLog("LINK VERIFY MISSING: " + srcName + "[" + srcSlotStr +
        "] -> " + tgtName + "[" + tgtSlotStr + "] NOT FOUND");
      writeLinkerRow(srcName, srcPath, srcSlotStr,
        tgtName, tgtPath, tgtSlotStr,
        "MISSING", "Link not found", linkMode, linkName, durStr);
      return "MISSING";
    }

    // ---- REVERSE (deleteComponent=true): remove the listed link ----
    if (isDeleteMode())
    {
      if (tgtComp.getSlot(linkName) == null)
      {
        boolean dry = isDryRun();
        String durStr = durMs(t0);
        writeToLinkerLog((dry ? "LINK DRYRUN (reverse): would skip - link not found --> "
                              : "LINK SKIPPED (reverse): link not found --> ") +
          srcName + "[" + srcSlotStr + "] -> " +
          tgtName + "[" + tgtSlotStr + "]");
        writeLinkerRow(srcName, srcPath, srcSlotStr,
          tgtName, tgtPath, tgtSlotStr,
          dry ? "DRYRUN" : "SKIPPED",
          dry ? "Would skip - link not found" : "Link not found",
          linkMode, linkName, durStr);
        return dry ? "DRYRUN" : "SKIPPED";
      }

      if (isDryRun())
      {
        String durStr = durMs(t0);
        writeToLinkerLog("LINK DRYRUN (reverse): would remove --> " +
          srcName + "[" + srcSlotStr + "] -> " +
          tgtName + "[" + tgtSlotStr + "]");
        writeLinkerRow(srcName, srcPath, srcSlotStr,
          tgtName, tgtPath, tgtSlotStr,
          "DRYRUN", "Would remove link", linkMode, linkName, durStr);
        return "DRYRUN";
      }

      tgtComp.remove(linkName);
      String durStr = durMs(t0);
      writeToLinkerLog("LINK REMOVED: " + srcName + "[" + srcSlotStr +
        "] -> " + tgtName + "[" + tgtSlotStr + "]");
      writeLinkerRow(srcName, srcPath, srcSlotStr,
        tgtName, tgtPath, tgtSlotStr,
        "REMOVED", "", linkMode, linkName, durStr);
      return "REMOVED";
    }

    // ---- CREATE (default) ----
    if (tgtComp.getSlot(linkName) != null)
    {
      boolean dry = isDryRun();
      String durStr = durMs(t0);
      writeToLinkerLog((dry ? "LINK DRYRUN: would skip - already exists --> "
                            : "LINK SKIPPED: already exists --> ") +
        srcName + "[" + srcSlotStr + "] -> " +
        tgtName + "[" + tgtSlotStr + "]");
      writeLinkerRow(srcName, srcPath, srcSlotStr,
        tgtName, tgtPath, tgtSlotStr,
        dry ? "DRYRUN" : "SKIPPED",
        dry ? "Would skip - link already exists" : "Link already exists",
        linkMode, linkName, durStr);
      return dry ? "DRYRUN" : "SKIPPED";
    }

    if (isDryRun())
    {
      String durStr = durMs(t0);
      writeToLinkerLog("LINK DRYRUN: would link --> " +
        srcName + "[" + srcSlotStr + "] -> " +
        tgtName + "[" + tgtSlotStr + "]");
      writeLinkerRow(srcName, srcPath, srcSlotStr,
        tgtName, tgtPath, tgtSlotStr,
        "DRYRUN", "Would create link", linkMode, linkName, durStr);
      return "DRYRUN";
    }

    String srcHandle = srcComp.getHandle().toString();
    javax.baja.naming.BOrd srcHandleOrd =
      javax.baja.naming.BOrd.make("h:" + srcHandle);
    javax.baja.sys.BLink newLink =
      new javax.baja.sys.BLink(srcHandleOrd, srcSlotStr, tgtSlotStr, true);
    tgtComp.add(linkName, newLink, null);

    String durStr = durMs(t0);
    writeToLinkerLog("LINKED: " + srcName + "[" + srcSlotStr +
      "] -> " + tgtName + "[" + tgtSlotStr + "]");
    writeLinkerRow(srcName, srcPath, srcSlotStr,
      tgtName, tgtPath, tgtSlotStr,
      "LINKED", "", linkMode, linkName, durStr);
    return "LINKED";
  }
  catch (Exception e)
  {
    String reason = describeException(e);
    String durStr = durMs(t0);
    writeToLinkerLog("LINK ERROR: " + reason);
    writeLinkerRow(srcName, srcPath, srcSlotStr,
      tgtName, tgtPath, tgtSlotStr,
      "ERROR", reason, linkMode, linkName, durStr);
    return "ERROR";
  }
}

private void executeLinksCsv(String linksCsvPath)
{
  String phaseLabel = isVerify()    ? "Verifying"
                    : isDeleteMode() ? "Reversing"
                                     : "Processing";
  String linkModeLabel = isVerify()    ? "Verify"
                       : isDeleteMode() ? "Reverse"
                                        : "Create";

  // v2.06: the link phase writes to its OWN log + results CSV. Archive
  // the previous run's linker files and start a fresh linker results CSV
  // with a header before any rows are written.
  archiveLinkerLogFile();
  initLinkerResultsCsv();

  // Leave a pointer in the main (copy-phase) log so an operator looking
  // there knows where the link detail went.
  writeToLog("Link phase running - details in linker log (" +
    resolveLinkerLogPath() + ") and linker results CSV (" +
    resolveLinkerResultsCsvPath() + ")");

  writeToLinkerLog("--- " + phaseLabel + " links CSV: " + linksCsvPath +
    (isVerify()    ? " [VERIFY]"  : "") +
    (isDeleteMode() && !isVerify() ? " [REVERSE]" : "") + " ---");
  setStatus("[" + now() + "] " + phaseLabel + " links CSV...");
  log.info("[ComponentCopier] " + phaseLabel +
    " links CSV: " + linksCsvPath);

  java.io.File linksFile = resolveToFile(linksCsvPath);
  if (linksFile == null || !linksFile.exists())
  {
    writeToLinkerLog("LINKS CSV NOT FOUND: " + linksCsvPath +
      " - skipping link phase");
    writeToLog("LINKS CSV NOT FOUND: " + linksCsvPath +
      " - skipping link phase");
    return;
  }

  // Pre-count data rows so the live status field can show "row X of Y"
  // while the loop runs. Costs one extra pass over the file but
  // matches the UX of the copy-mode CSV phase.
  int totalRows = countLinksCsvRows(linksFile);
  writeToLinkerLog("Links CSV total data rows: " + totalRows);

  int linked = 0, skipped = 0, errors = 0, dryrun = 0;
  int found = 0, missing = 0;
  int removed = 0;
  int rowNum = 0;
  int dataRow = 0;

  java.io.BufferedReader br = null;
  java.io.FileInputStream fis = null;
  try
  {
    fis = new java.io.FileInputStream(linksFile);
    br = new java.io.BufferedReader(
      new java.io.InputStreamReader(fis, "UTF-8"));
    String line;
    while ((line = br.readLine()) != null)
    {
      rowNum++;
      if (rowNum == 1) continue; // skip header

      line = line.trim();
      if (line.isEmpty()) continue;

      // Cancellation checkpoint
      if (isCancelled())
      {
        writeToLinkerLog("LINK PHASE CANCELLED at row " + rowNum);
        break;
      }

      // Live progress update: shows operators which row is in flight.
      // Verb matches the phase ("Verifying" / "Reversing" / "Linking")
      // so the status field self-documents which mode is running.
      dataRow++;
      String verb = isVerify()    ? "Verifying"
                  : isDeleteMode() ? "Reversing"
                                   : "Linking";
      setStatus("[" + now() + "] " + verb + " links: row " +
        dataRow + " of " + totalRows + "...");

      // Quote-aware split so commas inside quoted slot names parse correctly
      String[] cols = parseCsvRow(line);
      if (cols.length < 5)
      {
        writeToLinkerLog("LINK row " + rowNum + " SKIPPED: not enough columns");
        writeLinkerRow("(row " + rowNum + ")", "", "", "", "", "",
          "SKIPPED",
          "Not enough columns (need 5, got " + cols.length + ")",
          linkModeLabel, "", "0.000");
        skipped++;
        continue;
      }

      String bord1Str  = cols[0];
      String slot1Str  = cols[1];
      String direction = cols[2];
      String bord2Str  = cols[3];
      String slot2Str  = cols[4];

      // v2.06: resolve each BOrd in its own block so the ERROR row can
      // name exactly which ord failed, give a real reason, and show the
      // slot next to the ord.
      javax.baja.sys.BComponent comp1 = null;
      javax.baja.sys.BComponent comp2 = null;

      // --- Resolve BOrd1 ---
      try
      {
        Object o1 =
          javax.baja.naming.BOrd.make(normalizeOrd(bord1Str)).resolve().get();
        if (!(o1 instanceof javax.baja.sys.BComponent))
        {
          String reason = "BOrd1 resolved to a non-component (" +
            (o1 == null ? "null" : o1.getClass().getSimpleName()) +
            "): " + bord1Str;
          writeToLinkerLog("LINK row " + rowNum + " ERROR: " + reason);
          writeLinkerRow("(row " + rowNum + ")", bord1Str, slot1Str,
            "", bord2Str, slot2Str, "ERROR", reason,
            linkModeLabel, "", "0.000");
          errors++;
          continue;
        }
        comp1 = (javax.baja.sys.BComponent) o1;
      }
      catch (Exception e)
      {
        String reason = "BOrd1 cannot resolve: " + bord1Str +
          " -- " + describeException(e);
        writeToLinkerLog("LINK row " + rowNum + " ERROR: " + reason);
        writeLinkerRow("(row " + rowNum + ")", bord1Str, slot1Str,
          "", bord2Str, slot2Str, "ERROR", reason,
          linkModeLabel, "", "0.000");
        errors++;
        continue;
      }

      // --- Resolve BOrd2 ---
      try
      {
        Object o2 =
          javax.baja.naming.BOrd.make(normalizeOrd(bord2Str)).resolve().get();
        if (!(o2 instanceof javax.baja.sys.BComponent))
        {
          String reason = "BOrd2 resolved to a non-component (" +
            (o2 == null ? "null" : o2.getClass().getSimpleName()) +
            "): " + bord2Str;
          writeToLinkerLog("LINK row " + rowNum + " ERROR: " + reason);
          writeLinkerRow("(row " + rowNum + ")", bord1Str, slot1Str,
            "", bord2Str, slot2Str, "ERROR", reason,
            linkModeLabel, "", "0.000");
          errors++;
          continue;
        }
        comp2 = (javax.baja.sys.BComponent) o2;
      }
      catch (Exception e)
      {
        String reason = "BOrd2 cannot resolve: " + bord2Str +
          " -- " + describeException(e);
        writeToLinkerLog("LINK row " + rowNum + " ERROR: " + reason);
        writeLinkerRow("(row " + rowNum + ")", bord1Str, slot1Str,
          "", bord2Str, slot2Str, "ERROR", reason,
          linkModeLabel, "", "0.000");
        errors++;
        continue;
      }

      // --- Both resolved: dispatch by direction ---
      String result;
      if (direction.equals(">"))
        result = processLinkRow(comp1, slot1Str, comp2, slot2Str);
      else if (direction.equals("<"))
        result = processLinkRow(comp2, slot2Str, comp1, slot1Str);
      else
      {
        writeToLinkerLog("LINK row " + rowNum +
          " SKIPPED: unknown direction '" + direction + "'");
        writeLinkerRow("(row " + rowNum + ")", bord1Str, slot1Str,
          "", bord2Str, slot2Str, "SKIPPED",
          "Invalid direction '" + direction + "' (expected > or <)",
          linkModeLabel, "", "0.000");
        skipped++;
        continue;
      }

      if (result.equals("LINKED"))       linked++;
      else if (result.equals("REMOVED"))  removed++;
      else if (result.equals("SKIPPED")) skipped++;
      else if (result.equals("DRYRUN"))  dryrun++;
      else if (result.equals("FOUND"))   found++;
      else if (result.equals("MISSING")) missing++;
      else                               errors++;
    }
  }
  catch (Exception e)
  {
    writeToLinkerLog("LINKS CSV READ ERROR: " + describeException(e));
  }
  finally
  {
    if (br != null) try { br.close(); } catch (Exception ignore) {}
    if (fis != null) try { fis.close(); } catch (Exception ignore) {}
  }

  String tail = isCancelled() ? " [CANCELLED]" : "";
  String linkSummary;
  if (isVerify())
  {
    linkSummary = "Links verify complete - Found:" + found +
      " Missing:" + missing + " Errors:" + errors + tail;
  }
  else if (isDeleteMode())
  {
    // Reverse-mode link phase: report removals, not creations. This is
    // what fixes the misleading "Errors:N" summary that used to show up
    // when reverse-mode tried to create links to already-deleted
    // components.
    linkSummary = "Links phase complete (reverse) - Removed:" + removed +
      " Skipped:" + skipped + " Errors:" + errors +
      " DryRun:" + dryrun + tail;
  }
  else
  {
    linkSummary = "Links phase complete - Linked:" + linked +
      " Skipped:" + skipped + " Errors:" + errors +
      " DryRun:" + dryrun + tail;
  }
  writeToLinkerLog(linkSummary);
  writeLinkerSummary(linked, removed, skipped, errors, dryrun, found, missing);
  setStatus("[" + now() + "] " + linkSummary);
  log.info("[ComponentCopier] " + linkSummary);
  writeToLog(linkSummary);
}

// ----------------------------------------------------
// MODE: Direct
// ----------------------------------------------------
private void executeDirect(long runStart) throws Exception
{
  writeToLog("Mode: DIRECT" +
    (isVerify()  ? " [VERIFY]"  : "") +
    (isDryRun()  ? " [DRY RUN]" : "") +
    (isDeleteMode() && !isVerify() ? " [DELETE]" : ""));
  log.info("[ComponentCopier] Mode: DIRECT");

  javax.baja.naming.BOrd dstOrd = getCopyTo();
  if (dstOrd == null || dstOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: copyTo not set";
    setStatus(msg); log.warning("[ComponentCopier] " + msg);
    writeToLog(msg); return;
  }

  java.util.List sourceOrds = getSourceOrdStrings();
  if (sourceOrds.isEmpty())
  {
    String msg = "[" + now() + "] ERROR: componentSource not set";
    setStatus(msg); log.warning("[ComponentCopier] " + msg);
    writeToLog(msg); return;
  }

  javax.baja.sys.BComponent dst =
    (javax.baja.sys.BComponent) dstOrd.resolve().get();

  writeToLog("Sources: " + sourceOrds.size() +
    " | Destination: " + dst.getName());

  int[] counts = new int[5];
  for (int s = 0; s < sourceOrds.size(); s++)
  {
    String srcOrdStrRaw = (String) sourceOrds.get(s);
    boolean flatten = isFlattenMarker(srcOrdStrRaw);
    String srcOrdStr = flatten ? stripFlattenMarker(srcOrdStrRaw) : srcOrdStrRaw;
    javax.baja.sys.BComponent src = resolveSourceComponent(srcOrdStr);
    if (src == null) { counts[2]++; continue; }

    boolean flattenFolder = flatten && isFolderComponent(src);
    java.util.List toCopy = expandSource(src, flatten);
    if (isFolderComponent(src))
      writeToLog("Source '" + srcOrdStr + "' is a folder - " +
        (flattenFolder
          ? "flattening into its contents (internal links recreated after copy)"
          : "copying as a single unit (full subtree, internal links preserved)"));

    boolean reverse = isDeleteMode() && !isVerify();
    if (flattenFolder && reverse) processInternalLinks(src, dst);

    for (int c = 0; c < toCopy.size(); c++)
    {
      javax.baja.sys.BComponent item = (javax.baja.sys.BComponent) toCopy.get(c);
      setStatus("[" + now() + "] Processing source " + (s + 1) +
        " of " + sourceOrds.size() + " (" + item.getName() + ")...");

      String result = isVerify()
        ? processVerify(item, dst, "Direct")
        : processCopy(item, dst, "Direct");
      countResult(counts, result);
    }

    if (flattenFolder && !reverse) processInternalLinks(src, dst);
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = buildSummary("Direct", counts, totalMs);
  setStatus("[" + now() + "] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

// ----------------------------------------------------
// MODE: BQL
// ----------------------------------------------------
private void executeBQL(long runStart) throws Exception
{
  writeToLog("Mode: BQL" +
    (isVerify()  ? " [VERIFY]"  : "") +
    (isDryRun()  ? " [DRY RUN]" : "") +
    (isDeleteMode() && !isVerify() ? " [DELETE]" : ""));
  log.info("[ComponentCopier] Mode: BQL");

  javax.baja.naming.BOrd dstOrd = getCopyTo();
  if (dstOrd == null || dstOrd.isNull())
  {
    String msg = "[" + now() + "] ERROR: copyTo (BQL) not set";
    setStatus(msg); writeToLog(msg); return;
  }

  java.util.List sourceOrds = getSourceOrdStrings();
  if (sourceOrds.isEmpty())
  {
    String msg = "[" + now() + "] ERROR: componentSource not set";
    setStatus(msg); writeToLog(msg); return;
  }

  Object bqlResult = dstOrd.resolve().get();
  writeToLog("BQL result type: " + bqlResult.getClass().getName());

  // ----------------------------------------------------
  // Materialize the destination list BEFORE any copying starts.
  // (v2.07) Multiple sources need to run against the same
  // destination set, and a BQL cursor can only be walked once -- so
  // it is walked exactly once here, into an in-memory list. This
  // also means a destination created by THIS run can never be
  // re-matched and copied again later in the same run, whatever the
  // query. (See also the nested-destination guard in processCopy.)
  // ----------------------------------------------------
  java.util.List destinations = new java.util.ArrayList();
  int[] counts = new int[5];

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

    int rowNum = 0;
    try
    {
      while (((Boolean) nextMethod.invoke(cursor)).booleanValue())
      {
        rowNum++;

        if (isCancelled())
        {
          writeToLog("BQL destination read CANCELLED before row " + rowNum);
          break;
        }

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

          destinations.add(rowObj);
        }
        catch (Exception e)
        {
          counts[2]++;
          writeToLog("BQL ERROR reading row " + rowNum + ": " +
            describeException(e));
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

  writeToLog("BQL destinations captured: " + destinations.size() +
    " | Sources: " + sourceOrds.size());

  int totalPairs = destinations.size() * sourceOrds.size();
  int pairNum = 0;

  outerBql:
  for (int s = 0; s < sourceOrds.size(); s++)
  {
    String srcOrdStrRaw = (String) sourceOrds.get(s);
    boolean flatten = isFlattenMarker(srcOrdStrRaw);
    String srcOrdStr = flatten ? stripFlattenMarker(srcOrdStrRaw) : srcOrdStrRaw;
    javax.baja.sys.BComponent src = resolveSourceComponent(srcOrdStr);
    if (src == null) { counts[2] += destinations.size(); continue; }

    boolean flattenFolder = flatten && isFolderComponent(src);
    java.util.List toCopy = expandSource(src, flatten);
    if (isFolderComponent(src))
      writeToLog("Source '" + srcOrdStr + "' is a folder - " +
        (flattenFolder
          ? "flattening into its contents (internal links recreated after copy)"
          : "copying as a single unit (full subtree, internal links preserved)"));

    boolean reverse = isDeleteMode() && !isVerify();

    for (int d = 0; d < destinations.size(); d++)
    {
      pairNum++;

      if (isCancelled())
      {
        writeToLog("BQL RUN CANCELLED before pair " + pairNum +
          " of " + totalPairs);
        break outerBql;
      }

      javax.baja.sys.BComponent dst =
        (javax.baja.sys.BComponent) destinations.get(d);

      if (flattenFolder && reverse) processInternalLinks(src, dst);

      for (int c = 0; c < toCopy.size(); c++)
      {
        javax.baja.sys.BComponent item =
          (javax.baja.sys.BComponent) toCopy.get(c);
        setStatus("[" + now() + "] BQL: source " + (s + 1) + " of " +
          sourceOrds.size() + " (" + item.getName() + "), destination " +
          (d + 1) + " of " + destinations.size() + "...");
        writeToLog("BQL " + item.getName() + " -> " + dst.getName());

        try
        {
          String result = isVerify()
            ? processVerify(item, dst, "BQL")
            : processCopy(item, dst, "BQL");
          countResult(counts, result);
        }
        catch (Exception e)
        {
          counts[2]++;
          writeToLog("BQL ERROR on pair " + pairNum + ": " +
            describeException(e));
        }
      }

      if (flattenFolder && !reverse) processInternalLinks(src, dst);
    }
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = buildSummary("BQL", counts, totalMs);
  setStatus("[" + now() + "] " + summary);
  log.info("[ComponentCopier] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

// ----------------------------------------------------
// MODE: CSV
// ----------------------------------------------------
private void executeCSV(long runStart) throws Exception
{
  writeToLog("Mode: CSV" +
    (isVerify()  ? " [VERIFY]"  : "") +
    (isDryRun()  ? " [DRY RUN]" : "") +
    (isDeleteMode() && !isVerify() ? " [DELETE]" : ""));
  log.info("[ComponentCopier] Mode: CSV");

  javax.baja.naming.BOrd csvOrd = resolveCsvOrd();
  if (csvOrd == null)
  {
    String msg = "[" + now() + "] ERROR: No CSV file found in copyTo";
    setStatus(msg); log.warning("[ComponentCopier] " + msg);
    writeToLog(msg); return;
  }

  writeToLog("Using CSV: " + csvOrd.toString());

  java.util.List sourceOrds = getSourceOrdStrings();
  if (sourceOrds.isEmpty())
  {
    String msg = "[" + now() + "] ERROR: componentSource not set";
    setStatus(msg); writeToLog(msg); return;
  }

  int totalRows = countCsvRows(csvOrd);
  writeToLog("CSV total data rows: " + totalRows);

  // ----------------------------------------------------
  // Materialize the destination list from the CSV BEFORE any copying
  // starts (v2.07) -- same reasoning as BQL mode: multiple sources
  // need to run against the same destination set, and freezing the
  // list up front means a component copied by this run can never be
  // matched by a later row of the same CSV read.
  // ----------------------------------------------------
  java.util.List destinations = new java.util.ArrayList();
  int[] counts = new int[5];

  javax.baja.file.BIFile csvFile =
    (javax.baja.file.BIFile) csvOrd.resolve().get();
  java.io.InputStream is = csvFile.getInputStream();
  java.io.BufferedReader br = new java.io.BufferedReader(
    new java.io.InputStreamReader(is, "UTF-8"));

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

      // Cancellation checkpoint
      if (isCancelled())
      {
        writeToLog("CSV destination read CANCELLED before row " + rowNum);
        break;
      }

      dataRow++;
      setStatus("[" + now() + "] CSV: reading destination row " +
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

        destinations.add(resolved);
      }
      catch (Exception e)
      {
        counts[2]++;
        writeToLog("ERROR reading CSV row " + rowNum + ": " +
          describeException(e));
        log.warning("[ComponentCopier] CSV row " +
          rowNum + " error: " + e.getMessage());
      }
    }
  }
  finally
  {
    br.close();
    is.close();
  }

  writeToLog("CSV destinations captured: " + destinations.size() +
    " | Sources: " + sourceOrds.size());

  int totalPairs = destinations.size() * sourceOrds.size();
  int pairNum = 0;

  outerCsv:
  for (int s = 0; s < sourceOrds.size(); s++)
  {
    String srcOrdStrRaw = (String) sourceOrds.get(s);
    boolean flatten = isFlattenMarker(srcOrdStrRaw);
    String srcOrdStr = flatten ? stripFlattenMarker(srcOrdStrRaw) : srcOrdStrRaw;
    javax.baja.sys.BComponent src = resolveSourceComponent(srcOrdStr);
    if (src == null) { counts[2] += destinations.size(); continue; }

    boolean flattenFolder = flatten && isFolderComponent(src);
    java.util.List toCopy = expandSource(src, flatten);
    if (isFolderComponent(src))
      writeToLog("Source '" + srcOrdStr + "' is a folder - " +
        (flattenFolder
          ? "flattening into its contents (internal links recreated after copy)"
          : "copying as a single unit (full subtree, internal links preserved)"));

    boolean reverse = isDeleteMode() && !isVerify();

    for (int d = 0; d < destinations.size(); d++)
    {
      pairNum++;

      if (isCancelled())
      {
        writeToLog("CSV RUN CANCELLED before pair " + pairNum +
          " of " + totalPairs);
        break outerCsv;
      }

      javax.baja.sys.BComponent dst =
        (javax.baja.sys.BComponent) destinations.get(d);

      if (flattenFolder && reverse) processInternalLinks(src, dst);

      for (int c = 0; c < toCopy.size(); c++)
      {
        javax.baja.sys.BComponent item =
          (javax.baja.sys.BComponent) toCopy.get(c);
        setStatus("[" + now() + "] CSV: source " + (s + 1) + " of " +
          sourceOrds.size() + " (" + item.getName() + "), destination " +
          (d + 1) + " of " + destinations.size() + "...");
        writeToLog("CSV " + item.getName() + " -> " + dst.getName());

        try
        {
          String result = isVerify()
            ? processVerify(item, dst, "CSV")
            : processCopy(item, dst, "CSV");
          countResult(counts, result);
        }
        catch (Exception e)
        {
          counts[2]++;
          writeToLog("CSV ERROR on pair " + pairNum + ": " +
            describeException(e));
        }
      }

      if (flattenFolder && !reverse) processInternalLinks(src, dst);
    }
  }

  long totalMs = (System.nanoTime() - runStart) / 1000000L;
  String summary = buildSummary("CSV", counts, totalMs);
  setStatus("[" + now() + "] " + summary);
  log.info("[ComponentCopier] " + summary);
  writeToLog(summary);
  writeResultsSummary(
    counts[0], counts[1], counts[2], counts[3], counts[4], totalMs);
}

public void onStart() throws Exception
{
  updateVersion();
  updateQuickGuide();
  setStatus("[" + now() + "] Ready");
  log.info("[ComponentCopier] " + VERSION +
    " Service started - Ready");
  writeToLog(VERSION + " Service started - Ready");
}

public void onExecute() throws Exception
{
  dryRunActive = false;
  verifyActive = false;
  cancelRequested = false;
  runJob();
}

public void onDryRun() throws Exception
{
  dryRunActive = true;
  verifyActive = false;
  cancelRequested = false;
  try { runJob(); }
  finally { dryRunActive = false; }
}

public void onVerify() throws Exception
{
  // Verify is audit-only: it must never copy, delete, or create links,
  // so we make sure dry-run is off (verify uses its own no-mutation
  // path, not the dry-run preview path) and verifyActive is set for
  // the duration of this invocation.
  dryRunActive = false;
  verifyActive = true;
  cancelRequested = false;
  try { runJob(); }
  finally { verifyActive = false; }
}

// Set the cancel flag. The currently running BQL / CSV row loop will
// notice between rows and exit cleanly; the in-flight row finishes
// first to avoid leaving a half-copied component.
public void onCancel() throws Exception
{
  cancelRequested = true;
  String msg = "Cancel requested - run will stop after current row";
  setStatus("[" + now() + "] " + msg);
  log.info("[ComponentCopier] " + msg);
  writeToLog(msg);
}

// Manual archive prune. Useful right after lowering maxArchives,
// without having to wait for the next execute / dryRun / verify run.
// v2.06: also prunes the linker log + linker results CSV archives.
public void onPruneArchives() throws Exception
{
  String startMsg = "Manual prune requested (maxArchives=" +
    resolveMaxArchives() + ")";
  setStatus("[" + now() + "] " + startMsg);
  log.info("[ComponentCopier] " + startMsg);
  writeToLog(startMsg);

  pruneArchives(resolveLogPath());
  pruneArchives(resolveResultsCsvPath());
  pruneArchives(resolveLinkerLogPath());
  pruneArchives(resolveLinkerResultsCsvPath());

  String done = "Prune complete (kept up to " +
    resolveMaxArchives() + " of each)";
  setStatus("[" + now() + "] " + done);
  log.info("[ComponentCopier] " + done);
  writeToLog(done);
}

// Write the two starter CSVs (copy destinations + links) to the path
// defined by sampleCsvPath. Pure side-effect on disk -- no copy, no
// delete, no link work, no archiving of the active log/results.
public void onCreateSampleCsv() throws Exception
{
  dryRunActive = false;
  verifyActive = false;
  cancelRequested = false;

  setStatus("[" + now() + "] Writing sample CSV files...");
  log.info("[ComponentCopier] onCreateSampleCsv triggered");
  writeToLog(VERSION + " onCreateSampleCsv triggered");

  try
  {
    writeSampleCsvs();
    String msg = "Sample CSVs written. Edit them in place, then point " +
      "copyTo (and optionally linksCsvPath) at the edited files.";
    setStatus("[" + now() + "] " + msg);
    writeToLog(msg);
    log.info("[ComponentCopier] " + msg);
  }
  catch (Exception e)
  {
    String detail = "SAMPLE CSV ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ComponentCopier] " + detail);
    writeToLog(detail);
  }
}

private void runJob() throws Exception
{
  long runStart = System.nanoTime();

  archiveLogFile();
  initResultsCsv();

  String trigger = isVerify() ? "onVerify"
                  : isDryRun() ? "onDryRun"
                               : "onExecute";
  log.info("[ComponentCopier] " + trigger + " triggered");
  writeToLog(VERSION + " " + trigger + " triggered" +
    (isVerify()  ? " [VERIFY]"   : "") +
    (isDryRun()  ? " [DRY RUN]"  : "") +
    (isDeleteMode() && !isVerify() ? " [DELETE MODE]" : ""));

  // ----------------------------------------------------
  // Links-only detection
  // ----------------------------------------------------
  // If componentSource and copyTo are both unset but linksCsvPath
  // is configured, skip the mode-execute phase entirely and run
  // only the link phase. This lets the program be used as a
  // links-only tool against pre-existing components, without the
  // mode-execute methods logging spurious "componentSource not set"
  // errors before the link phase runs.
  String linksPath = resolveLinksCsvPath();
  boolean linksConfigured = (linksPath != null && linksPath.length() > 0);

  boolean srcEmpty = true;
  boolean dstEmpty = true;
  try
  {
    srcEmpty = getSourceOrdStrings().isEmpty();
    javax.baja.naming.BOrd dstOrd = getCopyTo();
    dstEmpty = (dstOrd == null || dstOrd.isNull());
  }
  catch (Exception ignore) {}

  boolean linksOnly = srcEmpty && dstEmpty && linksConfigured;

  // Pre-compute mode and log it only when a copy phase will run.
  // In links-only mode the destinationMode setting is irrelevant.
  int mode = -1;
  if (!linksOnly)
  {
    mode = getModeOrdinal();
    writeToLog("Operation mode: " + mode +
      " (raw: " + getDestinationMode().toString() + ")");
  }
  else
  {
    String msg = "Links-only mode - componentSource and copyTo are " +
      "both unset; running link phase only";
    setStatus("[" + now() + "] " + msg);
    log.info("[ComponentCopier] " + msg);
    writeToLog(msg);
  }

  try
  {
    // ----------------------------------------------------
    // Phase ordering
    // ----------------------------------------------------
    // Normal (create) mode  : copy phase first, then link phase
    //   -- copies must exist before links can target them.
    // Reverse (delete) mode : link phase FIRST, then component delete
    //   -- links must be removed while their target components still
    //      exist, otherwise the link-name lookups fail and we get
    //      spurious "Errors:N" in the link-phase summary.
    // Verify mode           : same as normal (verify is read-only).
    // Links-only mode       : copy phase is skipped entirely; the link
    //                         phase still respects deleteMode for the
    //                         create-vs-remove decision.

    boolean reverse = isDeleteMode() && !isVerify();

    // ---- REVERSE MODE: link phase first ----
    if (reverse && linksConfigured && !isCancelled())
      executeLinksCsv(linksPath);

    // ---- Copy / verify / delete phase (skipped in links-only mode) ----
    if (!linksOnly && !isCancelled())
    {
      if (mode == 0)      executeDirect(runStart);
      else if (mode == 1) executeBQL(runStart);
      else if (mode == 2) executeCSV(runStart);
      else
      {
        String msg = "[" + now() + "] ERROR: unknown destinationMode " + mode;
        setStatus(msg);
        log.warning("[ComponentCopier] " + msg);
        writeToLog(msg);
      }
    }

    // ---- NORMAL / VERIFY MODE: link phase last ----
    if (!reverse && linksConfigured && !isCancelled())
      executeLinksCsv(linksPath);
    else if (linksConfigured && isCancelled())
      writeToLog("LINK PHASE SKIPPED - run was cancelled");
  }
  catch (Exception e)
  {
    String detail = "ERROR: " + e.getMessage();
    setStatus("[" + now() + "] " + detail);
    log.severe("[ComponentCopier] EXCEPTION - " + e.getMessage());
    writeToLog("EXCEPTION - " + e.getMessage());
  }
}

public void onStop() throws Exception
{
  log.info("[ComponentCopier] Service stopped");
  writeToLog("Service stopped");
}
