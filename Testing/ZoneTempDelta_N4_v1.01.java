/*
================================================================================
Program: ZoneTempDelta - Niagara N4.15
Author:  F. Lacroix
Version: v1.01
Date:    2026-10-22

Changes
-------
  v1.00  Initial release.
  v1.01  Added status output (StatusBoolean). Fault if either input
         is not ok; ok otherwise.

Purpose
-------
out = zoneTemp - zoneTempSp
  Positive -> zone is above setpoint.
  Negative -> zone is below setpoint.
Runs on change of either input. No logging.

Outputs
-------
  Both inputs ok     -> out    = difference, status ok
                        status = false, status ok
  Either input bad   -> out    = 0, status null
                        status = true, status fault
  Bad = fault, down, stale, disabled, null, or slot missing/unlinked.
  Overridden and alarm flags on an input are NOT treated as bad.

Imports
-------
  javax.baja.status.*

Slot Sheet
----------
  Name        Type                 Flags / Config
  ----------  -------------------  ------------------------------
  zoneTemp    baja:StatusNumeric   Summary, Execute On Change
  zoneTempSp  baja:StatusNumeric   Summary, Execute On Change
  out         baja:StatusNumeric   Summary, Read Only
  status      baja:StatusBoolean   Summary, Read Only
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
  BStatusBoolean st = getStatus();

  boolean bad = zt == null || sp == null
             || !zt.getStatus().isValid()
             || !sp.getStatus().isValid();

  if (bad)
  {
    o.setValue(0);
    o.setStatus(BStatus.nullStatus);
    st.setValue(true);
    st.setStatus(BStatus.fault);
    return;
  }

  o.setValue(zt.getValue() - sp.getValue());
  o.setStatus(BStatus.ok);
  st.setValue(false);
  st.setStatus(BStatus.ok);
}

public void onStop() throws Exception
{
}
