# mago-3d-terrainer runtime

The terrain engine is pinned to the official `v1.14.2-release` binary:

- File: `mago-3d-terrainer-1.14.2-release.jar`
- Source: https://github.com/Gaia3D/mago-3d-terrainer/releases/tag/v1.14.2-release
- License: Mozilla Public License 2.0
- Size: `228024960` bytes
- SHA-256: `c9f8c7a5c1e28b645c3d58ae7d6674839fa65fa3c34aba75006f31ef2f6d3ac1`

The Bootstrap default path is configured by `gis.terrain.mago.jar-path`. The
module-local default is relative to `ocean-gis`; deployments may override either
with `GIS_TERRAIN_MAGO_JAR_PATH` without changing the application.
The JAR is tracked with Git LFS because it exceeds GitHub's regular file-size
limit. Run `git lfs pull` after cloning if LFS objects were not fetched.

When upgrading the engine, replace the versioned JAR, update this file and the
two default path declarations, then verify the new asset digest against the
official GitHub Release metadata.
