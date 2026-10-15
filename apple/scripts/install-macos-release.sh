#!/bin/sh
# Builds the Release macOS client and installs it in /Applications.
# The app's version is the latest v* tag (v0.7.0 becomes 0.7.0).
# Signing stays the local team's, from apple/Local.xcconfig, so the app
# keeps the pairing tokens already in the login keychain.
set -eu

PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export PATH

root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
cd "$root/apple"

tag=$(git -C "$root" tag -l 'v[0-9]*' --sort=-v:refname | sed -n '1p')
if [ -z "$tag" ]; then
	echo "error: no v* tag" >&2
	exit 1
fi
version=${tag#v}
echo "Building Remote Agent $version"

# Quitting an app that is not running would launch it.
if [ "$(osascript -e 'application id "dev.remote-agent.app" is running')" = true ]; then
	osascript -e 'tell application id "dev.remote-agent.app" to quit'
	i=0
	while pgrep -f '/Applications/Remote Agent.app/Contents/MacOS/Remote Agent' >/dev/null; do
		i=$((i + 1))
		if [ "$i" -gt 20 ]; then
			echo "error: Remote Agent is still running" >&2
			exit 1
		fi
		sleep 0.5
	done
fi

xcodegen
xcodebuild -project RemoteAgent.xcodeproj -scheme RemoteAgent-macOS \
	-configuration Release -destination 'platform=macOS' -derivedDataPath build \
	-skipPackagePluginValidation MARKETING_VERSION="$version" build

app="build/Build/Products/Release/Remote Agent.app"
codesign --verify --deep --strict "$app"
rm -rf "/Applications/Remote Agent.app"
ditto "$app" "/Applications/Remote Agent.app"
codesign --verify --deep --strict "/Applications/Remote Agent.app"

installed=$(plutil -extract CFBundleShortVersionString raw "/Applications/Remote Agent.app/Contents/Info.plist")
echo "Installed /Applications/Remote Agent.app ($installed)"
