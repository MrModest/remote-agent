#!/bin/bash
# Uploads the iPhone and iPad app to TestFlight. It archives the app unsigned,
# then exports it to App Store Connect, which signs it with the team's Apple
# Distribution certificate and App Store profile. Xcode makes them the first
# time (-allowProvisioningUpdates), through the Apple ID in Xcode › Settings ›
# Accounts, and the team is the one in Local.xcconfig.
#
#   apple/scripts/testflight.sh 0.11.0          # or 0.11.0-beta.1
#
# The build number is the release workflow's: 0.11.0-beta.1 → 110001, 0.11.0
# → 110099. App Store Connect takes each build number once, and versions only
# as numbers, so a beta goes up as 0.11.0.
set -euo pipefail

[[ ${1:-} =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-[A-Za-z]*\.?([0-9]+))?$ ]] || {
	echo "usage: $0 <version>, such as 0.11.0 or 0.11.0-beta.1" >&2
	exit 2
}
m=("${BASH_REMATCH[@]}")
version=${m[1]}.${m[2]}.${m[3]}
build=$((m[1] * 1000000 + m[2] * 10000 + m[3] * 100 + ${m[5]:-99}))

cd "$(dirname "$0")/.."
xcodegen --quiet
team=$(xcodebuild -project RemoteAgent.xcodeproj -scheme RemoteAgent-iOS -showBuildSettings 2>/dev/null | sed -n 's/^ *DEVELOPMENT_TEAM = //p' | head -1)
[ -n "$team" ] || { echo "error: no DEVELOPMENT_TEAM; put yours in apple/Local.xcconfig" >&2; exit 1; }

out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
xcodebuild -project RemoteAgent.xcodeproj -scheme RemoteAgent-iOS -configuration Release \
	-destination 'generic/platform=iOS' -derivedDataPath build -skipPackagePluginValidation -quiet \
	-archivePath "$out/RemoteAgent.xcarchive" CODE_SIGNING_ALLOWED=NO \
	MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build" archive

cat > "$out/options.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>upload</string>
	<key>teamID</key>
	<string>$team</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
</dict>
</plist>
EOF
xcodebuild -exportArchive -archivePath "$out/RemoteAgent.xcarchive" -exportOptionsPlist "$out/options.plist" \
	-exportPath "$out/export" -allowProvisioningUpdates
echo "Uploaded $version ($build). It shows up in App Store Connect › TestFlight once Apple has processed it."
