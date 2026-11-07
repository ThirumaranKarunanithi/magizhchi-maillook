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
