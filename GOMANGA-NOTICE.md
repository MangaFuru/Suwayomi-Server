# GoManga — Suwayomi-Server source availability

This branch is the **public source-availability mirror** for the modified
Suwayomi-Server that the **GoManga** iOS app embeds. It is published to
satisfy **Mozilla Public License 2.0 (MPL-2.0) §3.2** — recipients of the
app (Executable Form) can obtain the corresponding Source Code Form of the
modified Covered Software here.

## What this is

- **Base:** upstream [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
  tag **`v1.1.1`** (commit `f40dcafb43bb654a8382cd79cff3a0b498168caa`).
- **GoManga changes:** the unified diffs in [`gomanga-patches/`](gomanga-patches/),
  applied on top of `v1.1.1` on this branch (in build order; some patches
  touch the same file and must be applied in sequence). They add a gated
  `slimMode` startup/route fast-path so the server boots fast and uses less
  memory inside the app's in-process JVM. All changes are modifications to
  existing server source files — no new standalone modules.

## Licensing

- Suwayomi-Server is licensed under the **Mozilla Public License 2.0**
  (see [`LICENSE`](LICENSE) and the per-file MPL headers).
- Portions adopted from **Mihon / Tachiyomi** (under
  `server/src/main/kotlin/eu/kanade/tachiyomi/…`) are licensed under the
  **Apache License 2.0**, Copyright 2015 Javier Tomás (see the in-tree
  license file under that path). The patches that touch those files keep
  those notices intact.
- The GoManga modifications above are themselves Covered Software under
  MPL-2.0 and are provided under the same terms.

## What is NOT here (and is not required to be)

The GoManga app's own build and packaging toolchain — the JVM runtime
(OpenJDK/OpenJ9) staging, the AAR/xcframework packaging, the embed scripts,
the seed database, and the release pipeline — is GoManga's own work, is not
Suwayomi-Server Covered Software, and is not part of this MPL source
obligation. Only the modified Suwayomi-Server source lives here.

## Reproducing this tree

```sh
git checkout f40dcafb43bb654a8382cd79cff3a0b498168caa   # upstream v1.1.1
for p in lazy-graphql-schema slim-server-setup gomanga-api-routes \
         extension-fast-install slim-javalin-setup image-chapter-fastpath \
         webview-proxy-fallback okhttp-concurrency source-url-no-db \
         catalog-cover-direct gomanga-default-ua; do
  git apply gomanga-patches/$p.patch
done
```
