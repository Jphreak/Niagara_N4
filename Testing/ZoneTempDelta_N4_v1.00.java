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
Runs on change of either input. No logging.

Status
------
  Both inputs valid  -> out = difference, status ok
  Either input bad   -> out = 0, status null
  (null, fault, down, stale, disabled or unlinked/missing all count as bad)

Imports
-------
  javax.baja.status.*

Slot Sheet
----------
  Name        Type                Flags / Config
  ----------  ------------------  ------------------------------
  zoneTemp    baja:StatusNumeric  Summary, Execute On Change
  zoneTempSp  baja:StatusNumeric  Summary, Execute On Change
  out         baja:StatusNumeric  Summary, Read Only
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
  BStatusNumeric o  = getOut();

  if (zt == null || sp == null
      || !zt.getStatus().isValid()
      || !sp.getStatus().isValid())
  {
    o.setValue(0);
    o.setStatus(BStatus.nullStatus);
    return;
  }

  o.setValue(zt.getValue() - sp.getValue());
  o.setStatus(BStatus.ok);
}

public void onStop() throws Exception
{
}
