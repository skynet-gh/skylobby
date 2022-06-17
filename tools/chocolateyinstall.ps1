## Add specific files as shortcuts to the desktop
## - https://docs.chocolatey.org/en-us/create/functions/install-chocolateyshortcut
Install-ChocolateyShortcut `
  -shortcutFilePath "C:\ProgramData\Microsoft\Windows\Start Menu\Programs\Chocolatey\skylobby.lnk" `
  -targetPath "C:\Program Files\Eclipse Adoptium\jre-17.0.3.7-hotspot\bin\javaw.exe" `
  -arguments "-XX:+UseZGC -Xmx2g -XX:+ExitOnOutOfMemoryError -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -jar C:\ProgramData\chocolatey\lib\skylobby\skylobby.jar" `
  -iconLocation "C:\ProgramData\chocolatey\lib\skylobby\skylobby.ico" `
  -description "skylobby"
