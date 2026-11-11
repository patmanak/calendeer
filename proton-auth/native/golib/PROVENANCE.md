# Native build source provenance

The build and packaging utilities are adapted from Contako commit
`e1cc5a077a9cbc35ee7f9c57d0c93a0f9f43cf39`, directory
`app/native/golib/`: `build.sh`, `build.ps1`, `package-aar.py`, `test-build.py`,
`test-package-aar.py`, `go.mod`, `go.sum`, `dependencies.go` and `golib.pom`.
The original project is [Contako](https://github.com/patmanak/contako).
Its original contributions are GPL-3.0-or-later; the grant and GPLv3 text are
preserved in this repository's licensing materials. This attribution does not
relicense the Proton sources built by these utilities.

Calendeer adaptations change the local module/artifact identity and provenance
name, integrate the artifact behind the existing Proton adapter and retain the
source, API, module, architecture and 16 KiB layout checks. No cryptographic
algorithm or Proton protocol implementation is copied or patched here.

`go.mod` and `go.sum` identify the upstream Proton packages and generator sources.
The upstream Maven AAR is a checksum-pinned API reference only. Its JNI binaries
must never be copied into the generated artifact. The source-built replacement
omits the separate key-transparency bindings: their admission must remain
qualified against the selected Core version and actual Calendeer runtime tests.
It is not a byte-for-byte rebuild of Proton's larger published aggregate.

The generated AAR retains license/notice files for its linked modules and Go
runtime. Review its exact resolved inventory before redistribution; merely
finding a license file does not establish license compatibility or completeness.
Keep native and application qualification distinct from F-Droid inclusion.
