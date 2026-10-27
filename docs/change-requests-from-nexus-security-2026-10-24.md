# Change requests for nexus_launcher, from nexus: security

**Written 2026-10-24**, against nexus_launcher `97d2937f90`, from the security assessment in nexus
(`docs/security-assessment-2026-10-24.md`).

## S1. Cleartext traffic is allowed everywhere — open

**What.** `AndroidManifest-common.xml` sets `android:usesCleartextTraffic="true"` on the
application. Replace it with a network security config: cleartext off by default, and a
`domain-config` that permits it only for the hosts that need it (the emulator's `10.0.2.2` and the
box's LAN address for a debug build, and nothing in release).

**Why.** In normal use the launcher talks to `https://serverpod.brynje.net`. Cleartext is for the
LAN and the emulator, but as set it also lets any library in the app, and any redirect, fall back
to plain HTTP on any network, with the person's session key in the `Authorization` header.

## S2. The dependency repositories are not narrowed or verified — open

**What.**
1. `settings.gradle` adds `maven { url 'https://jitpack.io' }` with no content filter. Restrict it
   to the group IDs that come from there (`content { includeGroup … }`), and put it last.
2. Turn on Gradle dependency verification (`gradle/verification-metadata.xml`, checksums, and
   signatures where there are any), so a changed artifact fails the build.

**Why.** An unfiltered JitPack can serve any group ID, and anyone can publish anything there under
a GitHub-derived name; without verification nothing notices when a resolved artifact changes. The
launcher is the home screen and holds the same session key as the app, so a poisoned build
dependency is a way into everything the phone knows.

## Order

S1, then S2.
