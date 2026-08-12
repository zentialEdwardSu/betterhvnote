#!/bin/sh
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
CLASSPATH=
for f in /c/Users/edsu/.gradle/wrapper/dists/gradle-8.14-bin/*.jar; do
  CLASSPATH="$CLASSPATH:$f"
done
export CLASSPATH
exec "/c/Program Files/Microsoft/jdk-17.0.20.8-hotspot/bin/java.exe" org.gradle.launcher.GradleMain "$@"
