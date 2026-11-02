#!/bin/bash
# Fails if the APK asks for any permission beyond location.
#
#   scripts/check-permissions.sh app/build/outputs/apk/debug/app-debug.apk
#
# The About screen promises TPS has no network permission, and manifest merging
# means any dependency could add one silently. So CI holds every build to an
# allowlist rather than a denylist: location, plus the signature-level
# permission androidx.core declares for the app's own unexported receivers.
set -euo pipefail
APK=${1:?usage: check-permissions.sh <apk>}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f | sort -V | tail -n 1)"
[ -x "$AAPT2" ] || { echo "aapt2 not found under $SDK/build-tools" >&2; exit 2; }

PACKAGE="$("$AAPT2" dump packagename "$APK")"
USED="$("$AAPT2" dump permissions "$APK" \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u)"
ALLOWED="$(printf '%s\n' \
    android.permission.ACCESS_COARSE_LOCATION \
    android.permission.ACCESS_FINE_LOCATION \
    "$PACKAGE.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" | sort -u)"

echo "Permissions requested by $PACKAGE:"
echo "$USED" | sed 's/^/  /'
EXTRA="$(comm -23 <(echo "$USED") <(echo "$ALLOWED"))"
if [ -n "$EXTRA" ]; then
    echo "::error::The APK requests permissions outside the allowlist:"
    echo "$EXTRA" | sed 's/^/  /'
    exit 1
fi
echo "OK: location only, no network."
