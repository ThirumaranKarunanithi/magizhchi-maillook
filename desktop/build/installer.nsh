; MailLook keeps running in the tray (close hides the window, and it starts with Windows), so the
; installer's usual "please close the app" step can't stop it. Close it first, so an install or an
; update runs without a "cannot be closed, click Retry" box.

!macro customInit
  nsExec::Exec 'taskkill /F /T /IM "Magizhchi MailLook.exe"'
  Sleep 800
!macroend

!macro customUnInit
  nsExec::Exec 'taskkill /F /T /IM "Magizhchi MailLook.exe"'
  Sleep 500
!macroend

; List MailLook as a mail app Windows can choose for mailto: links (Settings > Default apps).
; This only offers it: the mail app the person uses now stays theirs until they pick MailLook.
!macro customInstall
  WriteRegStr HKCU "Software\Classes\MagizhchiMailLook.mailto" "" "Mail link (Magizhchi MailLook)"
  WriteRegStr HKCU "Software\Classes\MagizhchiMailLook.mailto" "URL Protocol" ""
  WriteRegStr HKCU "Software\Classes\MagizhchiMailLook.mailto\DefaultIcon" "" "$INSTDIR\${APP_EXECUTABLE_FILENAME},0"
  WriteRegStr HKCU "Software\Classes\MagizhchiMailLook.mailto\shell\open\command" "" '"$INSTDIR\${APP_EXECUTABLE_FILENAME}" "%1"'
  WriteRegStr HKCU "Software\Clients\Mail\Magizhchi MailLook" "" "Magizhchi MailLook"
  WriteRegStr HKCU "Software\Clients\Mail\Magizhchi MailLook\Capabilities" "ApplicationName" "Magizhchi MailLook"
  WriteRegStr HKCU "Software\Clients\Mail\Magizhchi MailLook\Capabilities" "ApplicationDescription" "One calm inbox for every mailbox you have."
  WriteRegStr HKCU "Software\Clients\Mail\Magizhchi MailLook\Capabilities\URLAssociations" "mailto" "MagizhchiMailLook.mailto"
  WriteRegStr HKCU "Software\RegisteredApplications" "Magizhchi MailLook" "Software\Clients\Mail\Magizhchi MailLook\Capabilities"
!macroend

!macro customUnInstall
  DeleteRegValue HKCU "Software\RegisteredApplications" "Magizhchi MailLook"
  DeleteRegKey HKCU "Software\Clients\Mail\Magizhchi MailLook"
  DeleteRegKey HKCU "Software\Classes\MagizhchiMailLook.mailto"
!macroend
