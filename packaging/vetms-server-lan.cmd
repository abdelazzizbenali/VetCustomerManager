@echo off
REM ---------------------------------------------------------------
REM  VetCustomerManager - clinic data server (LAN mode)
REM  Run this ONLY on the PC that is the clinic's database hub.
REM  All other PCs point Settings -> "Clinic server" at this machine's
REM  IP + port 9677 with the token from
REM  %APPDATA%\VetCustomerManager\server-info.json
REM ---------------------------------------------------------------
title VetCustomerManager Server (keep this window open)
echo Starting the clinic data server on ALL interfaces (LAN mode)...
echo Sister PCs connect to: http://THIS-PC-IP:9677
echo Keep this window open while the clinic is working.
echo.
set VETMS_BIND=0.0.0.0
"%~dp0vetms-server.exe" %*
