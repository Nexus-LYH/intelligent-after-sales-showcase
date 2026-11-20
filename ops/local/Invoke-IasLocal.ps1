[CmdletBinding(SupportsShouldProcess)]
param(
 [Parameter(Mandatory)][ValidateSet('Validate','Status','Readiness','Start','Stop','Initialize','Backup','Restore','ReviewRestore')][string]$Action,
 [Parameter(Mandatory)][string]$ConfigPath,
 [string]$TargetDirectory,
 [string]$RestorePrefix,
 [switch]$ExclusiveWindow
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalLifecycle.ps1')
$config=Read-IasLocalConfig $ConfigPath
switch($Action){
 Validate { Test-IasLocalConfig $config; Write-Output 'Configuration and artifact validation passed; no runtime actions executed.' }
 Status { Get-IasLocalStatus $config }
 Readiness { Test-IasLocalConfig $config; Test-IasReadiness $config }
 Start {
  Test-IasLocalConfig $config
  if(!$ExclusiveWindow){throw 'Exclusive runtime window required'}
  if($PSCmdlet.ShouldProcess('project localhost services','Start')){Start-IasLocal $config}
 }
 Stop {
  if(!$ExclusiveWindow){throw 'Exclusive runtime window required'}
  if($PSCmdlet.ShouldProcess('manifest-owned processes','Stop')){Stop-IasLocal $config}
 }
 Initialize {
  Test-IasLocalConfig $config
  if(!$ExclusiveWindow){throw 'Exclusive database window required'}
  if($PSCmdlet.ShouldProcess('independent MySQL 3307','Run pinned migration adapters')){
   Initialize-IasRelease $config
  }
 }
 Backup {
  if(!$ExclusiveWindow){throw 'Exclusive database and private-file window required'}
  if($PSCmdlet.ShouldProcess($TargetDirectory,'Create consistent stopped-service backup')){Invoke-IasBackup $config $TargetDirectory}
 }
 Restore {
  if(!$ExclusiveWindow){throw 'Exclusive database and private-file window required'}
  if($PSCmdlet.ShouldProcess($RestorePrefix,'Restore into new schemas and new private-file directory')){Invoke-IasRestore $config $TargetDirectory $RestorePrefix}
 }
 ReviewRestore {
  if(!$ExclusiveWindow){throw 'Exclusive isolated restore review window required'}
  if($PSCmdlet.ShouldProcess($RestorePrefix,'Validate isolated schema references, DEFINER, grants and files')){Test-IasRestoreReview $config $TargetDirectory $RestorePrefix}
 }
}
