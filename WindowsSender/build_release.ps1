$ErrorActionPreference = "Stop"
& (Join-Path $PSScriptRoot '..\Build-MirrorShare.ps1') -Target Windows
