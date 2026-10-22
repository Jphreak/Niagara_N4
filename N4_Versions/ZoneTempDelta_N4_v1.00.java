/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.00
Date:    2026-10-22

Changes
-------
  v1.00  Initial release.

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
pointStatus flags a bad input. Runs on change of either input, and once
on start. No logging.

Outputs
-------
  Both inputs ok     -> out         = difference, status ok
                        pointStatus = false, status ok
  Either input bad   -> out         = 0, status null
                        pointStatus = true, status fault
  Bad = fault, down, stale, disabled, null, or slot missing/unlinked.
  A flag set alongside good flags still counts (overridden + stale = bad).
  Overridden and alarm/unackedAlarm flags alone are NOT treated as bad.

  Both outputs are rewritten as whole new values on every run, so a
  copied block never keeps the source block's state.

Imports
-------
  javax.baja.status.*

Slot Sheet
----------
  Name         Type                 Flags / Config
  -----------  -------------------  ------------------------------
  zoneTemp     baja:StatusNumeric   Summary, Execute On Change,
                                    default status null
  zoneTempSp   baja:StatusNumeric   Summary, Execute On Change,
                                    default status null
  out          baja:StatusNumeric   Summary, Read Only
  pointStatus  baja:StatusBoolean   Summary, Read Only,
                                    facets trueText=" " falseText="Ok"
  version      baja:String          Read Only
================================================================================
*/

private static final String VERSION = "v1.00";

public void onStart() throws Exception
{
  setVersion(VERSION);
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
