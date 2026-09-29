/*
 * This file is part of LCull (https://github.com/Starlevka/LCull)
 * Copyright (C) 2026 Starlev (a.k.a. Starlevka) and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package starl.lcull.mixins;

import net.minecraft.client.renderer.culling.Frustum;
//? if >=1.20 {
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
//?} else {
/*import com.mojang.math.Vector4f;*/
//?}
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import starl.lcull.duck.IFrustum;

/**
 * @author Starlev
 * Fast-path replacements for the client {@link Frustum}, plus the duck implementation backing
 * {@link IFrustum}.
 *
 * <p>Changes over vanilla:</p>
 * <ul>
 *   <li>{@code offsetToFullyIncludeCameraCube} keeps vanilla's contract (shift the camera origin
 *     backwards along the view vector until the camera-aligned cube sits fully inside the
 *     frustum, mutating the shared instance in place and returning {@code this}) but bounds the
 *     search: vanilla steps a flat {@link Frustum#OFFSET_STEP} blocks per iteration with no
 *     distance cap and no iteration limit, while this version starts from that same step, grows it
 *     geometrically, and enforces a distance and iteration cap so the loop always terminates.</li>
 *   <li>{@code isVisible(AABB)} uses the boolean {@code testAab} instead of routing through the
 *     three-state {@code intersectAab} and then collapsing the result. Same verdict, fewer values
 *     to carry. This is the shape vanilla itself already uses on 1.20.1 and 1.21.1.</li>
 *   <li>The entity path, {@code lcull$isVisible}, uses a pre-folded plane test that removes the
 *     eighteen per-axis sign branches JOML's {@code testAab} evaluates per entity.</li>
 * </ul>
 *
 * <p>Version split: {@code >=1.20} reuses the shared JOML {@code FrustumIntersection} and the
 * pre-folded test. {@code <1.20} has no JOML and no {@code Frustum#matrix}: it keeps
 * {@code com.mojang.math} vectors and shadowing its own {@code cubeInFrustum} /
 * {@code cubeCompletelyInFrustum} plane walkers, which is exactly what it did before the pre-fold
 * existed. Extending the pre-fold there is possible - 1.19.2 keeps its planes in a
 * {@code private final Vector4f[] frustumData} field, already normalised, so they could simply be
 * copied out - but it is deliberately not done: there is no 1.19.2 class on the benchmark's
 * classpath, so the plane sourcing and the sign convention would ship unverified, and
 * unverified geometry is how entities start disappearing. {@code isVisible} is also left alone
 * below 1.20, because there vanilla already calls the boolean {@code cubeInFrustum} directly.</p>
 */
@Mixin(value = Frustum.class, priority = 2100)
public abstract class MFrustum implements IFrustum {

    // ── Constants shared by every branch ────────────────────────────────────────────────────

    /** Number of clipping planes. */
    @Unique private static final int PLANE_COUNT = 6;

    /** Cube half-extent buckets, in blocks. Index 0 is the tightest. */
    @Unique private static final int BUCKET_COUNT = 8;

    /**
     * Upper bound on camera-cube expansion steps. The search grows its step geometrically, so it
     * reaches the distance cap long before this even at a pathological FOV.
     */
    @Unique private static final int MAX_CUBE_ITERATIONS = 12;

    /**
     * Factor the search step grows by after each iteration. Growing - rather than shrinking, as an
     * earlier revision did - is the right direction here: this loop exists to deliberately
     * <em>over</em>-include geometry near the camera, so a coarser step errs toward rendering a
     * little too much rather than popping, and it reaches the distance cap in a handful of plane
     * tests instead of walking it 4 blocks at a time.
     */
    @Unique private static final double CUBE_STEP_GROWTH = 1.6;

    /** How far the camera may retreat at most, as a multiple of the cube edge. */
    @Unique private static final int CUBE_MAX_OFFSET_FACTOR = 8;

    /**
     * Camera position. Only used to translate tested boxes relative to the frustum planes -
     * and mutated by {@code offsetToFullyIncludeCameraCube} (both here and in vanilla).
     */
    @Shadow private double camX;
    @Shadow private double camY;
    @Shadow private double camZ;

    // ── Version-gated shadows ──────────────────────────────────────────────────────────────

    //? if >=1.20 {
    /** Shared JOML intersection tester; plane data is rebuilt by {@code calculateFrustum}. */
    @Shadow @Final private FrustumIntersection intersection;

    /** The combined view-projection matrix, read once per frustum rebuild to re-derive the planes. */
    @Shadow @Final private Matrix4f matrix;

    /**
     * Camera-forward direction in world space (JOML). Vanilla allocates a new vector per
     * {@code calculateFrustum} call; the aggressive cube walk reads it every iteration.
     */
    @Shadow private @Nullable Vector4f viewVector;
    //?} else {
    /*@Shadow private @Nullable Vector4f viewVector;

    @Shadow private boolean cubeInFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        throw new AssertionError();
    }

    @Shadow private boolean cubeCompletelyInFrustum(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        throw new AssertionError();
    }*/
    //?}

    // ── Pre-folded plane test (1.20+) ─────────────────────────────────────────────────────
    //
    // JOML's testAab picks, for every plane and every axis, the min or the max corner:
    //     n.x * (n.x < 0 ? minX : maxX) + ... >= -n.w
    // That is three sign choices per plane, eighteen per entity, and they depend only on the
    // plane, never on the entity. Re-resolving them once per frustum and folding the box radius
    // into each plane's w term collapses the test to a branchless dot product:
    //     n.x * cx + n.y * cy + n.z * cz >= -(w + |n.x|h + |n.y|h + |n.z|h)
    //
    // The planes are re-derived from Frustum#matrix with the Gribb-Hartmann row combinations
    // rather than read out of JOML's private `planes` array, so no JOML member is ever touched.
    // The derivation, including JOML's per-plane invsqrt normalisation, was verified to be
    // bit-identical to what JOML itself computes over 24 camera states; see scripts/bench.
    //
    // A cube half-extent is quantised into BUCKET_COUNT buckets, which is what lets the radius be
    // folded in once per bucket. The rounding is conservative: the cube contains the real box, so
    // the test can only ever admit more, never reject something JOML would keep.

    //? if >=1.20 {
    // Allocated lazily rather than in a field initialiser, because calculateFrustum runs from
    // Frustum's own constructor and the mixin's field-init placement must not be load-bearing.
    @Unique private float[] lcull$nx;
    @Unique private float[] lcull$ny;
    @Unique private float[] lcull$nz;
    @Unique private float[] lcull$nw;

    /** {@code bias[bucket * PLANE_COUNT + plane]}: the "at least partly inside" test. */
    @Unique private float[] lcull$bias;

    /** The same table for the "fully inside" test, where the radius is added rather than added to. */
    @Unique private float[] lcull$biasInner;

    /**
     * False until the first successful rebuild, and permanently false if the matrix is ever
     * unavailable, in which case every call falls back to {@code intersection.testAab}.
     */
    @Unique private boolean lcull$planesReady;

    /**
     * Rebuilds the pre-folded plane tables. Measured at about 200 ns, once per frustum rebuild,
     * which is negligible even against a 10k-entity pass.
     *
     * <p>The handler declares no parameters on purpose: {@code calculateFrustum}'s signature
     * differs between versions ({@code Matrix4f, Matrix4f} on 1.21.11 against
     * {@code Matrix4fc, Matrix4f} on 26.x), and a parameterless handler matches all of them.</p>
     *
     * <p>No {@code priority} is set, and none is needed. Nothing else injects into this private
     * method - EntityCulling has no Frustum mixin at all, and Sodium's FrustumMixin is a pure
     * shadow/accessor with no injects - and priority orders injects rather than making them
     * cheaper, so the default 1000 is correct.</p>
     *
     * <p>{@code @Inject} rather than {@code @Overwrite} on purpose: an overwrite would have to
     * restate vanilla's entire frustum assembly and would need a separate signature per version
     * family, in exchange for saving one call boundary per frame.</p>
     */
    @Inject(method = "calculateFrustum", at = @At("TAIL"))
    private void lcull$onCalculateFrustum(CallbackInfo ci) {
        lcull$rebuildPlanes();
    }

    /**
     * Rebuilds the tables on a <em>copied</em> frustum.
     *
     * <p>This is load-bearing, not a nicety. Vanilla's copy path never calls
     * {@code calculateFrustum} - 1.19.2 does a {@code System.arraycopy} of
     * {@code frustumData}, 1.20.1 through 1.21.11 assign {@code intersection} and {@code matrix}
     * field by field, and 26.1+ delegates to {@code set(Frustum)} - so the
     * {@code calculateFrustum} inject above does not fire for a copy. Without this one,
     * {@link #lcull$planesReady} stays {@code false} on every copy and
     * {@link #lcull$aggressiveCameraCube} silently falls back to {@code intersectAab}, making
     * {@link #lcull$preFoldInside} dead code.
     *
     * <p>That matters because the copies are exactly what the camera-cube walk runs on:
     * {@code LevelRenderer#offsetFrustum} is {@code new Frustum(frustum)} followed by
     * {@code offsetToFullyIncludeCameraCube(8)}, as is the frustum the particle engine is handed.
     * The entity path is unaffected either way - it uses the original frustum from
     * {@code prepareCullFrustum}, which does go through {@code calculateFrustum}.
     *
     * <p><b>Why 26.1+ targets {@code set} and not the copy constructor.</b> The copy constructor
     * delegates to {@code set}, so hooking both would rebuild every copy twice per frame - the one
     * thing the pre-fold's cost argument forbids. {@code set} is the only method of its name on
     * {@code Frustum}, and its descriptor is stable, so one handler covers it. It also covers the
     * one caller the copy constructor cannot: {@code Camera#extractRenderState} pushes the
     * camera's frustum into the persistent {@code CameraRenderState#cullFrustum} with
     * {@code cullFrustum.set(cullFrustum)} every frame, on an object constructed once from
     * identity matrices. Vanilla never queries visibility with that object today
     * ({@code LevelRenderer} hands it to {@code addMainPass}, which ignores it), so the planes
     * there are stale rather than wrong - but a mod calling {@code isVisible} on it would get an
     * answer derived from an identity matrix, which is how entities start disappearing.
     *
     * <p>{@code matrix} is populated at TAIL on both paths (26.1+ reaches it through the very
     * {@code set} call being hooked), so the derivation reads the same matrix the copy inherited
     * and lands on the same planes the source has.
     */
    //? if >=26.1 {
    /*@Inject(method = "set(Lnet/minecraft/client/renderer/culling/Frustum;)V", at = @At("TAIL"))
    private void lcull$onSet(CallbackInfo ci) {
        lcull$rebuildPlanes();
    }*/
    //?} else {
    @Inject(method = "<init>(Lnet/minecraft/client/renderer/culling/Frustum;)V", at = @At("TAIL"))
    private void lcull$onCopied(Frustum source, CallbackInfo ci) {
        lcull$rebuildPlanes();
    }
    //?}

    /** Shared derivation body; see {@link #lcull$onCalculateFrustum} for the notes on it. */
    @Unique
    private void lcull$rebuildPlanes() {
        Matrix4f m = this.matrix;
        if (m == null) {
            this.lcull$planesReady = false;
            return;
        }

        if (this.lcull$nx == null) {
            this.lcull$nx = new float[PLANE_COUNT];
            this.lcull$ny = new float[PLANE_COUNT];
            this.lcull$nz = new float[PLANE_COUNT];
            this.lcull$nw = new float[PLANE_COUNT];
            this.lcull$bias = new float[BUCKET_COUNT * PLANE_COUNT];
            this.lcull$biasInner = new float[BUCKET_COUNT * PLANE_COUNT];
        }

        // JOML names matrix elements mRC = column R, row C, so combining row 3 with row K reads
        // m_c3 +- m_cK for column c. These are exactly the combinations JOML's own set(Matrix4fc)
        // performs, which is what makes the result bit-identical rather than merely equivalent.
        float[] nx = this.lcull$nx;
        float[] ny = this.lcull$ny;
        float[] nz = this.lcull$nz;
        float[] nw = this.lcull$nw;

        lcull$storePlane(nx, ny, nz, nw, 0, m.m03() + m.m00(), m.m13() + m.m10(), m.m23() + m.m20(), m.m33() + m.m30());
        lcull$storePlane(nx, ny, nz, nw, 1, m.m03() - m.m00(), m.m13() - m.m10(), m.m23() - m.m20(), m.m33() - m.m30());
        lcull$storePlane(nx, ny, nz, nw, 2, m.m03() + m.m01(), m.m13() + m.m11(), m.m23() + m.m21(), m.m33() + m.m31());
        lcull$storePlane(nx, ny, nz, nw, 3, m.m03() - m.m01(), m.m13() - m.m11(), m.m23() - m.m21(), m.m33() - m.m31());
        lcull$storePlane(nx, ny, nz, nw, 4, m.m03() + m.m02(), m.m13() + m.m12(), m.m23() + m.m22(), m.m33() + m.m32());
        lcull$storePlane(nx, ny, nz, nw, 5, m.m03() - m.m02(), m.m13() - m.m12(), m.m23() - m.m22(), m.m33() - m.m32());

        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            double h = lcull$halfExtent(bucket);
            int base = bucket * PLANE_COUNT;
            for (int i = 0; i < PLANE_COUNT; i++) {
                double reach = Math.abs(nx[i]) * h + Math.abs(ny[i]) * h + Math.abs(nz[i]) * h;
                // Outside test uses the corner farthest along each normal, so the radius is
                // subtracted from the plane distance; the inside test uses the nearest corner, so
                // it is added. Same shape, two tables.
                this.lcull$bias[base + i] = (float) -(nw[i] + reach);
                this.lcull$biasInner[base + i] = (float) -(nw[i] - reach);
            }
        }

        this.lcull$planesReady = true;
    }

    /**
     * Stores one plane and normalises it, matching JOML. Skipping the normalisation would still be
     * geometrically valid, since the test is invariant under a positive rescale, but the rounding
     * of the dot products would then differ from JOML's and boxes sitting exactly on a plane could
     * flip sides. Six invsqrt calls per frame removes the question entirely.
     */
    @Unique
    private static void lcull$storePlane(
        float[] nx, float[] ny, float[] nz, float[] nw,
        int i, float x, float y, float z, float w
    ) {
        float s = org.joml.Math.invsqrt(x * x + y * y + z * z);
        nx[i] = x * s;
        ny[i] = y * s;
        nz[i] = z * s;
        nw[i] = w * s;
    }

    /** Half-extent covered by bucket {@code index}, in blocks. */
    @Unique
    private static double lcull$halfExtent(int index) {
        return switch (index) {
            case 0 -> 0.5D;
            case 1 -> 1.0D;
            case 2 -> 1.5D;
            case 3 -> 2.0D;
            case 4 -> 3.0D;
            case 5 -> 4.0D;
            case 6 -> 6.0D;
            default -> 8.0D;
        };
    }

    /**
     * Tightest bucket that covers {@code halfExtent}. Written as a compare chain rather than a
     * lookup table so it needs no static state, which a mixin class cannot rely on having its
     * initialiser run.
     */
    @Unique
    private static int lcull$bucket(float halfExtent) {
        if (halfExtent <= 0.5F) return 0;
        if (halfExtent <= 1.0F) return 1;
        if (halfExtent <= 1.5F) return 2;
        if (halfExtent <= 2.0F) return 3;
        if (halfExtent <= 3.0F) return 4;
        if (halfExtent <= 4.0F) return 5;
        if (halfExtent <= 6.0F) return 6;
        return 7;
    }

    /**
     * The pre-folded test. Static, and passed its arrays explicitly so the JIT sees plain locals
     * rather than field loads through {@code this} inside the loop.
     *
     * <p>Deliberately has no early exit. Rejecting on the first failing plane looks cheaper, but it
     * measured worse than evaluating all six and ANDing the results: which plane rejects is
     * effectively random, so every early exit is a mispredict.</p>
     */
    @Unique
    private static boolean lcull$preFoldVisible(
        float[] nx, float[] ny, float[] nz, float[] bias,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        double camX, double camY, double camZ
    ) {
        float cx = (float) ((minX + maxX) * 0.5D - camX);
        float cy = (float) ((minY + maxY) * 0.5D - camY);
        float cz = (float) ((minZ + maxZ) * 0.5D - camZ);

        double halfX = (maxX - minX) * 0.5D;
        double halfY = (maxY - minY) * 0.5D;
        double halfZ = (maxZ - minZ) * 0.5D;
        int base = lcull$bucket((float) Math.max(halfX, Math.max(halfY, halfZ))) * PLANE_COUNT;

        boolean inside = true;
        for (int i = 0; i < PLANE_COUNT; i++) {
            inside &= nx[i] * cx + ny[i] * cy + nz[i] * cz >= bias[base + i];
        }
        return inside;
    }

    /**
     * The "fully inside" variant used by the camera-cube walk, where the binding corner is the one
     * nearest each plane rather than the farthest.
     */
    @Unique
    private static boolean lcull$preFoldInside(
        float[] nx, float[] ny, float[] nz, float[] biasInner,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ,
        double camX, double camY, double camZ
    ) {
        float cx = (float) ((minX + maxX) * 0.5D - camX);
        float cy = (float) ((minY + maxY) * 0.5D - camY);
        float cz = (float) ((minZ + maxZ) * 0.5D - camZ);

        double halfX = (maxX - minX) * 0.5D;
        double halfY = (maxY - minY) * 0.5D;
        double halfZ = (maxZ - minZ) * 0.5D;
        int base = lcull$bucket((float) Math.max(halfX, Math.max(halfY, halfZ))) * PLANE_COUNT;

        boolean inside = true;
        for (int i = 0; i < PLANE_COUNT; i++) {
            inside &= nx[i] * cx + ny[i] * cy + nz[i] * cz >= biasInner[base + i];
        }
        return inside;
    }
    //?}

    /**
     * Bounded replacement for vanilla's camera-cube expansion ("aggressive" or reversed mode).
     *
     * <p>Vanilla walks the camera origin backwards along the view direction until the
     * camera-aligned cube of edge {@code cubeSize} is fully inside the frustum - a fixed
     * {@link Frustum#OFFSET_STEP}-block step, unbounded distance, unbounded iterations. This
     * version starts at that same 4.0-block step so the near-camera behaviour matches vanilla
     * exactly, then grows it by {@link #CUBE_STEP_GROWTH} per iteration, capping total travel at
     * {@code cubeSize * 8} and iterations at {@link #MAX_CUBE_ITERATIONS}. That guarantees
     * termination even at very low FOV; measured over a sweep of field-of-view values, covering the
     * cap costs about 6 plane tests instead of vanilla's 16 to 25.</p>
     *
     * <p>Like vanilla, the shared {@code camX/Y/Z} fields are mutated in place and {@code this}
     * is returned, so every later visibility query this frame sees the widened volume.</p>
     */
    @Inject(
        method = "offsetToFullyIncludeCameraCube",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lcull$aggressiveCameraCube(
        int cubeSize,
        CallbackInfoReturnable<Frustum> cir
    ) {
        if (cubeSize > 0 && this.viewVector != null) {
            double camX1 = Math.floor(this.camX / cubeSize) * cubeSize;
            double camY1 = Math.floor(this.camY / cubeSize) * cubeSize;
            double camZ1 = Math.floor(this.camZ / cubeSize) * cubeSize;
            double camX2 = Math.ceil (this.camX / cubeSize) * cubeSize;
            double camY2 = Math.ceil (this.camY / cubeSize) * cubeSize;
            double camZ2 = Math.ceil (this.camZ / cubeSize) * cubeSize;

            double step        = Frustum.OFFSET_STEP;
            double totalOffset = 0.0;
            double maxOffset   = cubeSize * (double) CUBE_MAX_OFFSET_FACTOR;

            for (int i = 0; i < MAX_CUBE_ITERATIONS; i++) {
                //? if >=1.20 {
                if (this.lcull$planesReady) {
                    // The cube is axis aligned with a known edge, so it lands in a fixed bucket and
                    // the pre-folded test replaces the per-iteration corner walk entirely.
                    if (lcull$preFoldInside(
                        this.lcull$nx, this.lcull$ny, this.lcull$nz, this.lcull$biasInner,
                        camX1, camY1, camZ1, camX2, camY2, camZ2,
                        this.camX, this.camY, this.camZ
                    )) {
                        break;
                    }
                } else if (this.intersection.intersectAab(
                    (float) (camX1 - this.camX),
                    (float) (camY1 - this.camY),
                    (float) (camZ1 - this.camZ),
                    (float) (camX2 - this.camX),
                    (float) (camY2 - this.camY),
                    (float) (camZ2 - this.camZ)
                ) == FrustumIntersection.INSIDE) {
                    break;
                }
                //?} else {
                /*if (this.cubeCompletelyInFrustum(
                    (float) (camX1 - this.camX),
                    (float) (camY1 - this.camY),
                    (float) (camZ1 - this.camZ),
                    (float) (camX2 - this.camX),
                    (float) (camY2 - this.camY),
                    (float) (camZ2 - this.camZ)
                )) break;*/
                //?}
                if (totalOffset >= maxOffset) break;

                if (totalOffset + step > maxOffset) {
                    step = maxOffset - totalOffset;
                }

                this.camX -= this.viewVector.x() * step;
                this.camY -= this.viewVector.y() * step;
                this.camZ -= this.viewVector.z() * step;
                totalOffset += step;
                step        *= CUBE_STEP_GROWTH;
            }
            cir.setReturnValue((Frustum) (Object) this);
        }
    }

    /**
     * Allocation-free visibility test consumed by {@code MEntityRenderer}.
     *
     * <p>Uses the pre-folded planes when they are available, and otherwise falls back to the
     * version's own walker: JOML's {@code testAab} on {@code >=1.20}, vanilla's private
     * {@code cubeInFrustum} below that. Both return {@code true} for fully inside and for
     * intersecting, matching vanilla {@code isVisible}, which treats everything not fully outside
     * as visible.</p>
     */
    @Override
    public boolean lcull$isVisible(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        //? if >=1.20 {
        if (this.lcull$planesReady) {
            return lcull$preFoldVisible(
                this.lcull$nx, this.lcull$ny, this.lcull$nz, this.lcull$bias,
                minX, minY, minZ, maxX, maxY, maxZ,
                this.camX, this.camY, this.camZ
            );
        }
        return this.intersection.testAab(
            (float) (minX - this.camX),
            (float) (minY - this.camY),
            (float) (minZ - this.camZ),
            (float) (maxX - this.camX),
            (float) (maxY - this.camY),
            (float) (maxZ - this.camZ)
        );
        //?} else {
        /*return this.cubeInFrustum(
            (float) (minX - this.camX),
            (float) (minY - this.camY),
            (float) (minZ - this.camZ),
            (float) (maxX - this.camX),
            (float) (maxY - this.camY),
            (float) (maxZ - this.camZ)
        );*/
        //?}
    }

    /**
     * Short-circuits vanilla's {@code isVisible(AABB)} to the boolean JOML test.
     *
     * <p>Vanilla asks {@code cubeInFrustum} for a three-state {@code intersectAab} result and then
     * collapses it with {@code i == -2 || i == -1}. {@code intersectAab} only ever returns
     * {@code INSIDE} (-2), {@code INTERSECT} (-1) or a plane index {@code 0..5} - never
     * {@code OUTSIDE} (-3) - so that condition is exactly {@code testAab}. Verified against
     * JOML 1.10.5 by observing the return values over a whole scene, and against the upstream
     * source, which states the same thing.</p>
     *
     * <p>Deliberately left on {@code testAab} rather than the pre-folded test: this method also
     * serves block entities, particles and anything else that asks the frustum a question, and
     * making those decisions slightly more permissive is not LCull's call.</p>
     *
     * <p>Only applied on {@code >=1.20}. On 1.20.1 and 1.21.1 vanilla already calls the boolean
     * {@code cubeInFrustum} directly and needs no help; below 1.20 there is no JOML at all.</p>
     */
    //? if >=1.20 {
    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void lcull$fastIsVisible(AABB box, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(this.intersection.testAab(
            (float) (box.minX - this.camX),
            (float) (box.minY - this.camY),
            (float) (box.minZ - this.camZ),
            (float) (box.maxX - this.camX),
            (float) (box.maxY - this.camY),
            (float) (box.maxZ - this.camZ)
        ));
    }
    //?}
}
