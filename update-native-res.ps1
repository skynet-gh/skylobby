
$jar = ".\target\skylobby.jar"

if (!(Test-Path -Path $jar))
{
  .\build-jar.ps1
}

$agent = "clojure-native-image-agent"
$agentjar = "$agent.jar"

if (!(Test-Path -Path $agentjar))
{
  $url = "https://github.com/skynet-gh/$agent/releases/download/v0.2.0%2Bfix-noclass%2Bcustom-ignore/$agentjar"
  Write-Host "Downloading from $url to $agentjar"
  Invoke-WebRequest $url -OutFile $agentjar
}

$config = "native-res\windows\META-INF\native-image\skylobby"

& "$env:GRAALVM_HOME\bin\java" `
-javaagent:clojure-native-image-agent.jar=initialize-class=skylobby.main,output-dir=$config,ignore-file=agent-ignore.txt `
-agentlib:native-image-agent=config-merge-dir=$config,config-write-period-secs=5 `
-jar $jar $args
