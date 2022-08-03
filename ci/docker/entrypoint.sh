#!/bin/bash
set -euo pipefail

# Add local zenbuilder user, either use LOCAL_USER_ID:LOCAL_GRP_ID
# if set via environment or fallback to 9001:9001
USER_ID="${LOCAL_USER_ID:-9001}"
GRP_ID="${LOCAL_GRP_ID:-9001}"
if [ "$USER_ID" != "0" ]; then
    export USERNAME=zenbuilder
    getent group "$GRP_ID" &> /dev/null || groupadd -g "$GRP_ID" "$USERNAME"
    id -u "$USERNAME" &> /dev/null || useradd --shell /bin/bash -u "$USER_ID" -g "$GRP_ID" -o -c "" -m "$USERNAME"
    CURRENT_UID="$(id -u "$USERNAME")"
    CURRENT_GID="$(id -g "$USERNAME")"
    export HOME=/home/"$USERNAME"
    if [ "$USER_ID" != "$CURRENT_UID" ] || [ "$GRP_ID" != "$CURRENT_GID" ]; then
        echo "WARNING: User with differing UID ${CURRENT_UID}/GID ${CURRENT_GID} already exists, most likely this container was started before with a different UID/GID. Re-create it to change UID/GID."
    fi
else
    export USERNAME=root
    export HOME=/root
    CURRENT_UID="$USER_ID"
    CURRENT_GID="$GRP_ID"
    echo "WARNING: Starting container processes as root. This has some security implications and goes against docker best practice."
fi

# Installing sbt(scala build tool)
echo "" && echo "=== Installing scala build tool ===" && echo ""
apt-get update
apt-get install apt-transport-https curl gnupg -yqq
echo "deb [signed-by=/etc/apt/trusted.gpg.d/scalasbt-release.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list
echo "deb [signed-by=/etc/apt/trusted.gpg.d/scalasbt-release.gpg] https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --no-default-keyring --keyring gnupg-ring:/etc/apt/trusted.gpg.d/scalasbt-release.gpg --import
chmod 644 /etc/apt/trusted.gpg.d/scalasbt-release.gpg
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends sbt

sbt_version=$(dpkg -s sbt | grep Version | cut -d ' ' -f2)

# Print information
echo "" && echo "=== Environment Info ===" && echo ""
java --version
echo "sbt(scala build tool) version: ${sbt_version}"
echo
lscpu
echo
free -h
echo
echo "Username: $USERNAME, HOME: $HOME, UID: $CURRENT_UID, GID: $CURRENT_GID"

if [ "$USERNAME" = "root" ]; then
  exec /build/ci/docker/entrypoint_setup_gpg.sh "$@"
else
  exec gosu "$USERNAME" /build/ci/docker/entrypoint_setup_gpg.sh "$@"
fi

