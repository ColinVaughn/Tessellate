#!/usr/bin/env bash
# Kill any running dev server.
#
# `pkill -f` does not work here: these are java.exe and Git Bash cannot match their command
# lines, so every "cleanup" silently leaves servers alive. Killing by listening port is also
# insufficient - a server still booting has not bound its ports yet, will grab them right after
# the check, steal the world lock, and then answer RCON from a stale world while the real server
# stalls. Match on the command line via PowerShell instead, then confirm the ports are free.

set -u

echo "killing dev servers by command line..."
powershell.exe -NoProfile -Command '
$procs = Get-CimInstance Win32_Process |
  Where-Object { $_.CommandLine -match "forgeserverdev|bootstraplauncher|net.minecraft.server" }
if ($procs) {
  foreach ($p in $procs) {
    Write-Output ("killing pid " + $p.ProcessId)
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
  }
} else {
  Write-Output "no matching processes"
}
' 2>&1 | tr -d '\r'

sleep 2

echo "checking ports 25565 / 25585..."
powershell.exe -NoProfile -Command '
foreach ($port in 25565, 25585) {
  $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if ($conn) {
    Write-Output ("port " + $port + " STILL LISTENING (pid " + $conn.OwningProcess + ")")
  } else {
    Write-Output ("port " + $port + " free")
  }
}
' 2>&1 | tr -d '\r'
