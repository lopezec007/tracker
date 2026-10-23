.PHONY: build test run clean dist install android android-test android-release android-install

# Git description baked into the binary as the build id (see `tracker -version`);
# the semantic version itself lives in main.go.
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS  = -X main.build=$(VERSION)

build:
	go build -ldflags "$(LDFLAGS)" -o tracker .

test:
	go vet ./... && go test ./...

run: build
	./tracker -addr 127.0.0.1:8081 -db dev.db

# Static linux/amd64 binary for deployment (the SQLite driver is pure Go, so
# no cgo is needed and the result runs on any x86-64 Linux, including Debian 13).
dist:
	mkdir -p dist
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w $(LDFLAGS)" -o dist/tracker .
	@echo "built dist/tracker ($(VERSION))"

# Install as a systemd service on this machine (needs root). Builds the binary
# if Go is available; otherwise pass a prebuilt one: make install BIN=dist/tracker
install:
	./deploy/install.sh $(BIN)

# Android app (android/): needs a JDK and the Android SDK, see android/SETUP.md.
# Non-login shells may not have sourced /etc/profile.d/android.sh, so fall
# back to the standard location.
export ANDROID_HOME ?= /opt/android-sdk

android:
	cd android && ./gradlew assembleDebug
	@echo "built android/app/build/outputs/apk/debug/app-debug.apk"

android-test:
	cd android && ./gradlew test

# Signed release APK (android/keystore.properties must exist) copied to dist/.
android-release:
	cd android && ./gradlew assembleRelease
	mkdir -p dist
	cp android/app/build/outputs/apk/release/app-release.apk dist/tracker-$(VERSION).apk
	@echo "built dist/tracker-$(VERSION).apk"

# Install the debug build on the phone on adb.
android-install:
	cd android && ./gradlew installDebug

clean:
	rm -f tracker dev.db dev.db-*
	rm -rf dist
