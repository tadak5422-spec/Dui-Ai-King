#!/bin/sh
# Lightweight local build for a phone/Termux.
# Edit whatever you want in this copy, then run:  sh build-phone.sh
# First run needs internet to fetch Gradle dependencies; after that:  sh build-phone.sh --offline
set -e
cd "$(dirname "$0")"

# Lower memory than the 4 GB defaults in gradle.properties, and compile Kotlin in-process
# so the phone does not need two big JVM daemons at once.
exec ./gradlew assembleDebug \
  -Pphone=true \
  -Pchroot=false \
  -Dorg.gradle.jvmargs="-Xmx1280m -XX:MaxMetaspaceSize=512m -XX:+UseG1GC -Dfile.encoding=UTF-8" \
  -Dkotlin.daemon.jvmargs="-Xmx1024m -XX:+UseG1GC" \
  -Dkotlin.compiler.execution.strategy=in-process \
  --max-workers=2 \
  "$@"
