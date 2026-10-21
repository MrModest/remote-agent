#!/bin/sh
# Signs the helpers inside the embedded Sparkle.framework with the app's
# Developer ID. Xcode signs the framework it embeds but not the code nested
# in it, and notarization wants every binary signed with Developer ID, the
# hardened runtime and a secure timestamp. Runs as a target's last build
# phase, after the framework is embedded and before Xcode signs the app.
set -eu

case "${EXPANDED_CODE_SIGN_IDENTITY_NAME:-${CODE_SIGN_IDENTITY:-}}" in
"Developer ID"*) ;;
*) exit 0 ;; # other builds don't update, and keep Sparkle's own signatures
esac

framework="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/Sparkle.framework"
sign() { codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --options runtime --timestamp "$@"; }
sign "$framework/Versions/B/XPCServices/Installer.xpc"
sign --preserve-metadata=entitlements "$framework/Versions/B/XPCServices/Downloader.xpc"
sign "$framework/Versions/B/Autoupdate"
sign "$framework/Versions/B/Updater.app"
sign "$framework"
