# Changelog

## [1.1.0] - 2026-09-29

Everything below is relative to 1.0.0.

### Performance
- New pre-folded plane test on the entity path. The six frustum planes are re-derived once per frustum rebuild from `Frustum#matrix` (Gribb-Hartmann row combinations plus JOML's own `invsqrt` normalisation, and no JOML member is ever touched), and the box radius is folded into each plane's `w`, collapsing `testAab` and its 18 per-entity min/max sign choices into a branchless dot product. **-26% (25.38 to 18.69 ns/entity)** on the 10k-entity JMH scene, camera rotating, 3 forks. A per-plane early exit was measured and rejected at 21.5 against 17.0 ns in isolation, because which plane rejects is effectively random, so every early exit is a mispredict. (1.19.3+) **REWORK**
- Cull decision order now mirrors vanilla's own, running players, then the `Entity#shouldRender` distance test, then the "never cull me" opt-out, then near-camera `Display`s, then the margin, then the frustum. Hoisting the distance check above the opt-out dropped the opt-out from 18.1% to 1.2% of the cull path. (all versions) **REWORK**
- `Frustum#isVisible` uses `testAab` instead of `intersectAab(...) == -2` for **-8%**, and it also serves the block entities, particles and chunk sections that share that method. (1.19.3+)
- `offsetToFullyIncludeCameraCube` is now bounded. It starts at vanilla's own 4.0 step so near-camera behaviour is unchanged, then grows that step x1.6 per iteration, caps total travel at `cubeSize * 8` and caps iterations at 12, where vanilla steps a flat 4.0 forever. That is about 6 plane tests against the 16 vanilla needs at 70 degrees FOV and the 185 it needs at 10 degrees. An earlier revision decayed the step instead (x0.75, floor 1.0) to land precisely near the camera, which was backwards for this loop whose whole purpose is to over-include near the camera, and it cost ~47 `intersectAab` calls against vanilla's 16. (all versions) **REWORK**
- Frustum copies rebuild their planes too. Vanilla's `Frustum(Frustum)` constructor never calls `calculateFrustum` and copies `intersection`, `matrix` and the camera fields straight across, so without a second inject at the copy-constructor TAIL the pre-folded planes were never built on any copy. That matters because copies are exactly what the camera-cube walk and the particle engine receive, `LevelRenderer#offsetFrustum` being `new Frustum(frustum)` followed by `offsetToFullyIncludeCameraCube(8)`. Costs about 200 ns per rebuild, or 0.022 ns per entity over a 10k pass. (all versions) **CRITICAL**
- The camera-cube walk now runs the pre-folded "fully inside" test instead of `intersectAab`, so the widened volume feeding `LevelRenderer#applyFrustum` no longer allocates a fresh `AABB` per plane test. (1.19.3+)
- Two candidates were measured and rejected. A bounding sphere before the AABB test over-includes, so it rarely rejects and only adds work. A quantised sqrt-free margin came out **+14% worse** because the threshold chain's unpredictable exit mispredicts more than the square root costs. (all versions)
- `testAab` is kept in plain `isVisible(AABB)` even though the pre-folded test is cheaper, because that method also serves block entities, particles and anything else that asks the frustum a question, and making those decisions slightly more permissive is not LCull's call. (1.19.3+)

### Bug Fixes
- Forge refmap fix. `lcull.refmap.json` is now wired into the processed mixin config, because production Forge remaps member names to SRG and without a `"refmap"` key in the config every `@Inject` failed on an obfuscated target. (1.19.2, 1.20.1 Forge) **CRITICAL**
- Forge `loaderVersion` was hardcoded `[47,)`, which cannot load on 1.19, so a 1.19.2 artifact could never be enabled. It is now major-based, `[43,)` for 1.19.x and `[47,)` for 1.20.x. (1.19.2, 1.20.1 Forge)
- The generated manifest now honours `deps.minecraft_min` instead of always using the Stonecutter version as the lower bound, so the 1.19.2 jars declare `[1.19,1.19.2]`. (1.19.3+)
- Fixed 26.3 API drift. 26.3 added a trailing `float partialTicks` to `shouldRender` and moved it into `getBoundingBoxForCulling(T, float)`, so the injector carried the old signature. The `>=26.3` handler stores partial ticks in a `@Unique` field consumed by the box read. (26.3)
- Fixed 1.21.2 API drift. `EntityRenderer#affectedByCulling(T)` only exists on 1.21.2+, so below that the "never cull me" opt-out now reads the public `Entity#noCulling` field. Without the gate, the ender dragon, lightning bolts and `noCulling` displays were culled. (1.21.2-) **CRITICAL**
- Fixed 1.19.2 API drift. `Display` only exists on 1.19.4+, and so does `Entity#level()`, where `lcull$level` falls back to `getCommandSenderWorld()` below that. (1.19.2, 1.19.3)
- `MClientChunkCache` kept both redirects on 1.19.2, where `replaceBiomes` does not exist. Only the `replaceWithPacketData` redirect is kept there now, still `require = 0`. (1.19.2)
- Fixed the Forge 1.20.1 launch. The one hand-written per-version source is the `pack.mcmeta` declaring format 15 under `versions/1.20.1-forge`, and `Loader.FabricLike.excludedResources` filters it out of Fabric jars while `NeoForgeLike` and `Forge` filter the foreign manifests, so no jar ships two pack formats or a foreign `mods.toml`.
- The pre-folded plane tables are now refreshed through `Frustum#set(Frustum)` on 26.1 and newer, where the copy constructor delegates to it, and through the copy constructor below that. Only the copy constructor was hooked before, which left the persistent `CameraRenderState#cullFrustum` object holding the planes derived from the identity matrices it was constructed with, so any mod asking that object for visibility would have been answered from a degenerate frustum. Vanilla never asks it, so nothing was broken in practice, but the state was reachable. Hooking both would have been worse, since the copy constructor reaches `set` on 26.1 and every copy would then be rebuilt twice per frame. (26.1+)
- Removed the `WARNING! LCULL COULD PRODUCE NEGATIVE PERFORMANCE!` startup line, which printed on every launch and referred to a measurement that in fact produced the shipped margin numbers.

### Removed
- The per-entity cull cache is gone. `ICache` (duck) and `MEntity` (mixin on `net.minecraft.world.entity.Entity`) are dropped from `lcull.mixins.json5`. The cache stored a last-decision boolean plus an entity-position and a frustum signature per entity, and the validation costs more than the test it guarded, since a frustum signature is 24 float compares plus `camX/Y/Z` against a cached test of 18 multiplies, 6 adds and 6 compares, and a miss is every frame the camera moved at all. It only paid when the camera was perfectly still, in which case the whole cull path is already a rounding error in the frame. It also put a field on `Entity`, the hottest class in the game. (all versions) **REWORK**
- The section-visibility mirror is gone. `MLevelRenderer` and `ILevelRenderer` short-circuited the cull path on one hash lookup of a copied `LevelRenderer#visibleSections`, but vanilla already gates entities on section visibility itself through `extractVisibleEntities` and `isSectionCompiledAndVisible`, so it duplicated an existing check at 16-block granularity, and the section path only existed on 1.21 through 26.1, silently degrading to a no-op elsewhere. (all versions)
- `LevelRendererAccessor` removed earlier for the same reason, a hook surface with no real consumer.
- The `lcull.ct` and `accesstransformer.ct` templates were removed as unused. LCull still ships no access widener and no access transformer, since all mixin targets are already reachable and `@Shadow` covers private fields.

### Changed
- New supported versions **1.19.2** (Fabric + Forge) and **26.3** (Fabric + NeoForge), 20 variants in total, with `stonecutter.properties.toml`, `settings.gradle.kts`, the per-version Java toolchain (Java 17 below 1.20.2, 21 for 1.21.x, 25 for 26.x), the JOML versus Mojang-math split and the version-gated mixin signatures. (1.19.2, 26.3) **NEW**
- Speed-scaled cull margin, `1.0` at rest and `+0.2 * |deltaMovement|` above 0.5 blocks/s, hard-capped at `2.5` so a fast projectile cannot exempt itself by moving fast. This costs **+7.4%** more drawn entities than vanilla at rest and up to **+26.5%** for fast movers, and every survivor costs a render state, a model build and a draw call, which dwarfs the ~20 ns test that rejected its neighbour. It is the deliberate trade for no mid-stride pop-in, and lowering it remains the single highest-value FPS lever available. (all versions)
- Renderers reporting a NaN or zero-size culling box now fall back to a fixed box of radius 2.0 around the entity, matching vanilla's own fallback, instead of testing a degenerate AABB. (all versions)
- The leash escape is preserved on 1.21+, where vanilla keeps a leashed entity visible while its holder is so the rope does not visually detach, and both of vanilla's tests are kept because the union is not redundant, two boxes on opposite sides of a plane being able to straddle it while neither intersects. Below 1.21 the check is a stub, because `net.minecraft.world.entity.Leashable` does not exist there and vanilla had no equivalent escape. (1.21+)
- Near-camera `Display`s within 5 blocks defer to vanilla, since their transformed boxes cannot be trusted, and use a flat `1.0` margin beyond that. (1.19.4+)
- `MFrustum` splits on the 1.19.3 JOML transition. 1.19.2 predates JOML, so it shadows vanilla's private `cubeInFrustum` and `cubeCompletelyInFrustum` plane walkers, which allocate up to 48 `Vector4f` per call, and keeps vanilla's frustum assembly. The pre-fold is deliberately not attempted on 1.19.2, because although its planes are readable, no 1.19.2 class is on the benchmark classpath, so the plane sourcing and sign convention would ship unverified. (1.19.2)
- Mixin priority raised from 600 to 2100, so LCull's decision runs before default-priority mixins from other mods. (all versions)
- `offsetToFullyIncludeCameraCube` is now an `@Inject` at HEAD with `cir.setReturnValue` and cancellable, rather than an overwrite, which would have had to restate vanilla's whole frustum assembly and need a separate signature per version family. `calculateFrustum` is likewise not overwritten. (all versions)
- `neoforged.moddev` 2.0.141 to 2.0.147, with NeoForge for 26.3 pinned to 26.3.0.16-beta.
- `neoforged.moddev.legacyforge` now builds both Forge variants, pinned to 1.19.2-43.2.0 and 1.20.1-47.4.10.
- Fabric below 1.21.11 and NeoForge below 1.21.11 get a `compileOnly` JSpecify 1.0.0 dependency, and Forge gets it unconditionally plus the mixin annotation processor.
- Full LGPL-3.0-only metadata, with the license key emitted into `fabric.mod.json` and both `mods.toml` variants, the full `LICENSE` copied into every jar root, and the header block maintained by the root `licenseHeaders` task, which now auto-runs before each variant's `compileJava`. SPDX is deliberately `LGPL-3.0-only`, version 3 exactly and no "or later".
- README gained the platform badges, the project icon and the AI-assistance note.
- LICENSE reformatted to the FSF's verbatim LGPL-3.0 text. The shipped terms were correct, the formatting was not the FSF's own.

## [1.0.0] - 2026-08-28

First public release, 16 variants on Modrinth (1.20.1 Fabric and Forge, plus 1.21 through 26.2 on Fabric and NeoForge).

### Performance
- Entity render culling injected at the head of `EntityRenderer#shouldRender`, cancellable, and composed by priority rather than by redirecting vanilla's call sites, so it plays with shader and other mods instead of fighting them.
- `offsetToFullyIncludeCameraCube` replaced with a stepped search instead of vanilla's flat unbounded walk.
- `Frustum#isVisible` improved over vanilla's `intersectAab`.

### Changed
- `Display` entities near the camera are exempt from culling, since their transformed boxes cannot be trusted.
- Benign "Ignoring chunk since it's not in the view range" log spam silenced by a `require = 0` redirect. The chunk is still ignored exactly as before, but the log no longer floods on busy servers.
