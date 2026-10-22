/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.03
Date:    2026-10-22

Changes
-------
  v1.00  Initial release.
  v1.01  Added status output (StatusBoolean). Fault if either input
         is not ok; ok otherwise.
  v1.02  Renamed status output to pointStatus.
  v1.03  Self-clear: both outputs are rewritten as whole new values on
         every run (no in-place edits, no early return), and on start.
         A copied block no longer keeps the source block's fault.

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
Runs on change of either input, and once on start. No logging.

Outputs
-------
  Both inputs ok     -> out         = difference, status ok
                        pointStatus = false, status ok
  Either input bad   -> out         = 0, status null
                        pointStatus = true, status fault
  Bad = fault, down, stale, disabled, null, or slot missing/unlinked.
  Overridden and alarm/unackedAlarm flags are NOT treated as bad.

Imports
-------
  javax.baja.status.*

Slot Sheet
----------
  Name         Type                 Flags / Config
  -----------  -------------------  ------------------------------
  zoneTemp     baja:StatusNumeric   Summary, Execute On Change
  zoneTempSp   baja:StatusNumeric   Summary, Execute On Change
  out          baja:StatusNumeric   Summary, Read Only
  pointStatus  baja:StatusBoolean   Summary, Read Only
================================================================================
*/

public void onStart() throws Exception
{
  onExecute();
}

public void onExecute() throws Exception
{
  BStatusNumeric zt = getZoneTemp();
  BStatusNumeric sp = getZoneTempSp();

  boolean bad = zt == null || sp == null
             || !zt.getStatus().isValid()
             || !sp.getStatus().isValid();

  if (bad)
  {
    setOut(new BStatusNumeric(0, BStatus.nullStatus));
    setPointStatus(new BStatusBoolean(true, BStatus.fault));
  }
  else
  {
    setOut(new BStatusNumeric(zt.getValue() - sp.getValue(), BStatus.ok));
    setPointStatus(new BStatusBoolean(false, BStatus.ok));
  }
}

public void onStop() throws Exception
{
}
