#!/bin/bash

set -eo pipefail
retval=0

if [[ "${TRAVIS_TAG}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?(-SNAPSHOT){1}[0-9]*$ ]]; then
  echo "" && echo "=== Publishing development release on Sonatype Nexus repository. Timestamp is: $(date '+%a %b %d %H:%M:%S %Z %Y') ===" && echo ""
  sbt -ivy ./.ivy2 -sbt-dir ./.sbt +publish
elif [[ "${TRAVIS_TAG}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]]; then
  echo "" && echo "=== Publishing production release on Maven repository. Timestamp is: $(date '+%Y-%m-%d %H:%M') ===" && echo ""
  sbt -ivy ./.ivy2 -sbt-dir ./.sbt +publishSigned sonatypeBundleRelease
else
  echo "" && echo "=== Not going to publish!!! Release tag = ${TRAVIS_TAG} did not match either DEV or PROD format requirements ===" && echo ""
fi

exit "$retval"