#!/bin/sh
exec /usr/bin/java \
  -Dglass.gtk.uiScale=1.1 \
  -server \
  -Xmx2g \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+DoEscapeAnalysis \
  -XX:+UseCompressedOops \
  -jar '/usr/share/java/skylobby/skylobby.jar' \
  "$@"
