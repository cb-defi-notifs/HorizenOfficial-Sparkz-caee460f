#!/bin/bash

set -eo pipefail
set -x

retval=0

if [[ "${DEV_RELEASE}" == "true" ]]; then
  echo "" && echo "=== Publishing development release on Sonatype Nexus repository. Timestamp is: $(date '+%a %b %d %H:%M:%S %Z %Y') ===" && echo ""
  sbt -ivy ./.ivy2 -sbt-dir ./.sbt +publish
elif [[ "${RC_RELEASE}" == "true" ]]; then
  echo "" && echo "=== Publishing RC release on Maven repository. Timestamp is: $(date '+%Y-%m-%d %H:%M') ===" && echo ""
  sbt -ivy ./.ivy2 -sbt-dir ./.sbt +publishSigned sonatypeBundleRelease
elif [[ "${PROD_RELEASE}" == "true" ]]; then
  echo "" && echo "=== Publishing production release on Maven repository. Timestamp is: $(date '+%Y-%m-%d %H:%M') ===" && echo ""
  sbt -ivy ./.ivy2 -sbt-dir ./.sbt +publishSigned sonatypeBundleRelease
else
  echo "" && echo "=== Not going to publish!!! The build did not match either DEV, RC or PROD release requirements ===" && echo ""
fi

exit "$retval"