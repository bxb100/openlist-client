# FongMi Media3 dependency

This local Maven repository contains the Media3 modules used by OpenList. They are built from
[`FongMi/media`](https://github.com/FongMi/media) commit
`3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d` on branch `release-1.11.0-fongmi`.

The fork adds the ASF extractor, WMA/WMV/VC-1 format mappings, and FFmpeg audio/video renderers.
The application deliberately resolves `androidx.media3` from `repository/` before Google Maven so
the modified common, extractor, decoder, ExoPlayer, HLS, session, and data-source modules stay on
one binary-compatible revision. Unmodified Media3 UI Compose artifacts still come from Google
Maven and link against this fork's compatible `media3-common` artifact.

The FFmpeg renderer was assembled with Android NDK `30.0.15729638` from the fork's checked-in ARM
native libraries. Only `armeabi-v7a` and `arm64-v8a` are provided, so the app intentionally limits
its APK ABIs to those two architectures. Artifact hashes and native provenance are recorded in
`media-lock.properties` and inside the decoder AAR's `classes.jar` at
`META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties`.

The fork's FFmpeg libraries dynamically require `libc++_shared.so`, but its decoder AAR does not
package that runtime. `runtime-libs/` therefore contains the matching NDK 30 C++ runtime for both
supported ABIs, and the app includes it as a JNI source directory. Its hashes are locked alongside
the Media3 AARs so playback cannot silently depend on an unrelated transitive native library.
The checked-in runtime copies are processed with that NDK's `llvm-strip --strip-unneeded`; this
removes build-only DWARF and static symbol tables while preserving the dynamic exports and BuildID.
`NOTICE.libcxx` is copied from the same NDK sysroot. The checked-in FFmpeg libraries themselves
were produced with the fork's NDK 29/API 24 toolchain; the JNI wrapper and C++ runtime were built
with NDK 30, and both toolchain versions are recorded in the lock file.

Important licensing note: the checked-in FongMi FFmpeg binaries report GPL version 3 or later and
were configured with `--enable-gpl --enable-version3`. Do not distribute a closed-source build
without completing a GPLv3 and third-party-license compliance review and providing corresponding
source as required. The upstream AAR does not itself contain a complete FFmpeg license/BOM/source
offer, so this repository is a functional playback integration, not proof of release compliance.
The Media3 Java sources are Apache-2.0; source JARs are kept beside each AAR.
