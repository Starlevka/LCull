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
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
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
 * Fast-path replacements for the client {@link Frustum}, plus the duck implementation backing
 * {@link IFrustum}.
 *
 * <p>Changes over vanilla:</p>
 * <ul>
 *   <li>{@code calculateFrustum} keeps the exact vanilla math (clip = projection x modelView,
 *     forward vector transformed by the transpose) but reuses a cached forward vector instead of
 *     allocating a fresh {@link Vector4f} every frame.</li>
 *   <li>{@code offsetToFullyIncludeCameraCube} keeps vanilla's contract (shift the camera origin
 *     backwards along the view vector until the camera-aligned cube sits fully inside the
 *     frustum, mutating the shared instance in place and returning {@code this}) but bounds the
 *     search: vanilla steps a flat 4.0 blocks per iteration with no distance cap and no iteration
 *     limit, while this version uses a decaying step and hard limits so the loop always
 *     terminates.</li>
 * </ul>
 *
 * @author Starlev
 */
@Mixin(Frustum.class)
public abstract class MFrustum implements IFrustum {

    /** Shared JOML intersection tester; plane data is rebuilt by {@code calculateFrustum}. */
    @Shadow @Final private FrustumIntersection intersection;

    /** Clip-space matrix (projection x modelView) the tester is built from. */
    @Shadow private Matrix4f matrix;

    /**
     * Camera-forward direction in world space. Vanilla allocates a new vector per
     * {@code calculateFrustum} call; here it is created lazily once and reused every frame.
     */
    @Shadow private @Nullable Vector4f viewVector;

    /**
     * Camera position. Only used to translate tested boxes relative to the frustum planes -
     * and mutated by {@code offsetToFullyIncludeCameraCube} (both here and in vanilla).
     */
    @Shadow private double camX;
    @Shadow private double camY;
    @Shadow private double camZ;

    /**
     * Rebuilds the frustum planes with vanilla math while skipping the per-call allocation.
     *
     * <p>Version split: up to 1.21.x the target takes two concrete {@link Matrix4f}s; since 26.1
     * the model-view parameter is typed as the read-only {@link Matrix4fc} interface, which
     * changes the target method descriptor, so the handler signature has to match per version.
     * The body is identical in both branches ({@code mul} accepts the interface type).</p>
     */
    //? if <26.1 {
    @Inject(method = "calculateFrustum", at = @At("HEAD"), cancellable = true)
    private void lcull$calculateFrustum(
        Matrix4f modelView,
        Matrix4f projection,
        CallbackInfo ci
    ) {
        projection.mul(modelView, this.matrix);
        this.intersection.set(this.matrix);
        if (this.viewVector == null) this.viewVector = new Vector4f();
        this.viewVector.set(0.0F, 0.0F, 1.0F, 0.0F);
        this.matrix.transformTranspose(this.viewVector);
        ci.cancel();
    }
    //?} else {
    /*@Inject(method = "calculateFrustum", at = @At("HEAD"), cancellable = true)
    private void lcull$calculateFrustum(
        Matrix4fc modelView,
        Matrix4f projection,
        CallbackInfo ci
    ) {
        projection.mul(modelView, this.matrix);
        this.intersection.set(this.matrix);
        if (this.viewVector == null) this.viewVector = new Vector4f();
        this.viewVector.set(0.0F, 0.0F, 1.0F, 0.0F);
        this.matrix.transformTranspose(this.viewVector);
        ci.cancel();
    }*/
    //?}

    /**
     * Bounded replacement for vanilla's camera-cube expansion ("aggressive" or reversed mode).
     *
     * <p>Vanilla walks the camera origin backwards along the view direction until the
     * camera-aligned cube of edge {@code cubeSize} is fully inside the frustum - fixed 4.0-block
     * steps, unbounded distance, unbounded iterations. This version performs the same search with
     * a decaying step (starts at 2.0, x0.75 each iteration, floor 0.5) capped at
     * {@code min(cubeSize * 2, 64)} total travel and 32 iterations, guaranteeing termination.</p>
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
            // Camera-aligned cube of edge `cubeSize`, snapped to the section grid around the camera.
            double camX1 = Math.floor(this.camX / cubeSize) * cubeSize;
            double camY1 = Math.floor(this.camY / cubeSize) * cubeSize;
            double camZ1 = Math.floor(this.camZ / cubeSize) * cubeSize;
            double camX2 = Math.ceil (this.camX / cubeSize) * cubeSize;
            double camY2 = Math.ceil (this.camY / cubeSize) * cubeSize;
            double camZ2 = Math.ceil (this.camZ / cubeSize) * cubeSize;

            double step             = 2.0;
            double totalOffset      = 0.0;
            double maxOffset        = Math.min(cubeSize * 2, 64.0);
            double viewVectorLength = Math.sqrt(
                this.viewVector.x() * this.viewVector.x() +
                this.viewVector.y() * this.viewVector.y() +
                this.viewVector.z() * this.viewVector.z()
            );

            for (int i = 0; i < 32; i++) {
                int r = this.intersection.intersectAab(
                    (float) (camX1 - this.camX),
                    (float) (camY1 - this.camY),
                    (float) (camZ1 - this.camZ),
                    (float) (camX2 - this.camX),
                    (float) (camY2 - this.camY),
                    (float) (camZ2 - this.camZ)
                );
                // -2 == fully inside: done. Anything else keeps pushing backwards.
                if (r == -2) break;

                if (totalOffset >= maxOffset) break;

                double offsetMagnitude = viewVectorLength * step;

                // Clamp the final step so total travel never exceeds maxOffset.
                if (totalOffset + offsetMagnitude > maxOffset) {
                    step = Math.max(
                        (maxOffset - totalOffset) / offsetMagnitude,
                        0.5
                    );
                }

                // Step the origin backwards along the view direction (vanilla mutates too).
                this.camX -= this.viewVector.x() * step;
                this.camY -= this.viewVector.y() * step;
                this.camZ -= this.viewVector.z() * step;
                totalOffset += step;
                step = Math.max(step * 0.75, 0.5);
            }
            cir.setReturnValue((Frustum) (Object) this);
        }
    }

    /**
     * Allocation-free visibility test consumed by {@code MEntityRenderer}.
     *
     * <p>Delegates straight to the shared JOML tester with camera-relative coordinates.
     * Result codes: {@code -2} fully inside, {@code -1} intersecting, {@code -3} outside.
     * Everything except OUTSIDE counts as visible, matching vanilla semantics.</p>
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
        int result = this.intersection.intersectAab(
            (float) (minX - this.camX),
            (float) (minY - this.camY),
            (float) (minZ - this.camZ),
            (float) (maxX - this.camX),
            (float) (maxY - this.camY),
            (float) (maxZ - this.camZ)
        );
        return result == -2 || result == -1;
    }
}
