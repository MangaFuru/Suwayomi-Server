# MangaFuru / GoManga downstream notice

This branch is the public source mirror for the Suwayomi-Server modifications
maintained by MangaFuru for the embedded GoManga iOS runtime. It is not an
upstream Suwayomi release and does not represent the Suwayomi organization.

## Exact base

- Upstream repository: <https://github.com/Suwayomi/Suwayomi-Server>
- Upstream tag: `v2.3.2243`
- Upstream commit: `1d583ca4a718646e17a4c62d23fe8e5edccaf774`
- Downstream branch: `gomanga-slim-v2.3.2243`

## Downstream scope

The GoManga source changes include:

- a smaller embedded-server surface with direct manga, chapter, page, image,
  extension, and built-in Komga endpoints;
- startup and low-memory runtime changes for an embedded mobile process;
- cookie persistence, Cloudflare handling, and cleartext-request policy used by
  installed sources;
- dex2jar and bytecode-repair compatibility for R8-optimized extensions;
- a built-in Komga provider and lifecycle API; and
- Android compatibility shims plus the deliberate removal of ICU/Graal-backed
  paths from the GoManga lite runtime.

The source is published for review and license compliance. GoManga's iOS
packaging, launcher, and release automation live separately and are not part of
this repository.

## Reproducible source patch

The two maintained input patches are preserved in [`gomanga-patches/`](gomanga-patches/):

- `gomanga-slim-v2.3.2243.patch`
- `gomanga-komga-builtin-v2.3.2243.patch`

`gomanga-complete-v2.3.2243.patch` is generated from the final branch and also
captures the Android compatibility changes applied by the source-build pipeline.
It recreates the modified source tree from the exact upstream commit:

```sh
git clone https://github.com/MangaFuru/Suwayomi-Server.git
cd Suwayomi-Server
git show origin/gomanga-slim-v2.3.2243:gomanga-patches/gomanga-complete-v2.3.2243.patch > /tmp/gomanga-complete-v2.3.2243.patch
git switch --detach 1d583ca4a718646e17a4c62d23fe8e5edccaf774
git apply /tmp/gomanga-complete-v2.3.2243.patch
./gradlew :server:shadowJar --no-daemon
```

The GoManga production packaging step additionally compiles and overlays
`server/src/main/java/com/googlecode/dex2jar/ir/ts/NewTransformer.java` into the
fat jar because the dex2jar dependency otherwise wins the duplicate-class merge.
This affects packaging order, not the source patch above.

## Verification receipt

Before publication, the downstream source tree was rebuilt from a clean archive
of the exact upstream commit. `./gradlew :server:shadowJar --no-daemon` completed
successfully after both maintained patches and the compatibility overlays were
applied.

## Licensing and provenance

Suwayomi-Server remains under the repository's Mozilla Public License 2.0. The
built-in Komga provider is adapted from `keiyoushi/extensions-source` revision
`9f2565708438fbbcbe86d5c0f120571b0de0bbc7` under Apache-2.0; its source retains
the provenance and license notice. See the repository `LICENSE` and source-file
headers for the applicable terms.
