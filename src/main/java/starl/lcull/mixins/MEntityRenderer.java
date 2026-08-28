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

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import starl.lcull.duck.ICache;
import starl.lcull.duck.IFrustum;

/**
 * @author Starlev
 * Entity render culling injected ahead of vanilla logic in {@code EntityRenderer#shouldRender}.
 *
 * <p>Mirrors the original LCull decision tree one-to-one: hard distance cap, near-camera display
 * exemption, far-display raw-box frustum test, and a {@link #CULL_MARGIN}-widened box test for
 * everything else. The frustum math itself is unchanged (see {@code MFrustum}); this mixin only
 * adds a per-entity cache of the off-screen decision so the {@code intersectAab} test is skipped
 * while neither the camera nor the entity has moved enough to change the result.</p>
 */
@Mixin(value = EntityRenderer.class, priority = 600)
public abstract class MEntityRenderer<T extends Entity> {


    /** Squared radius under which display entities always defer to vanilla (5 blocks, squared). */
    @Unique private static final double DISPLAY_CULL_RADIUS_SQ = 25.0;

    /** Safety margin (blocks) added around the culling box of non-display entities. */
    @Unique private static final double CULL_MARGIN            = 2.0;

    /**
     * Max ticks a cached decision is trusted without recomputation even if the input signature is
     * unchanged. Acts as a safety net for projection changes (FOV / viewport resize) the signature
     * does not capture, forcing a refresh within this window.
     */
    @Unique private static final long HYSTERESIS_TICKS = 4L;

    // Renderer-level culling box exists only since 1.21.2; before that vanilla reads the entity's own box.
    //? if <1.21.2 {
    /*private AABB lcull$box(T entity) {
        return entity.getBoundingBoxForCulling();
    }*/
    //?} else {
    @Shadow protected abstract AABB getBoundingBoxForCulling(T entity);

    private AABB lcull$box(T entity) {
        return this.getBoundingBoxForCulling(entity);
    }
    //?}

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void lcull$shouldRender(
        T entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (entity instanceof Player) return;

        double dx     = entity.getX() - camX;
        double dy     = entity.getY() - camY;
        double dz     = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;


        if (entity instanceof Display) {
            // Near-camera displays: transformed boxes cannot be trusted - vanilla decides.
            if (distSq < DISPLAY_CULL_RADIUS_SQ) {
                return;
            }

            // Far displays: raw box without margin (transforms already oversize it).
            if (!lcull$resolve(entity, frustum, camX, camY, camZ, true)) {
                cir.setReturnValue(false);
            }
            return;
        }

        if (!lcull$resolve(entity, frustum, camX, camY, camZ, false)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Returns the cached off-screen decision if the camera/entity input signature is unchanged and
     * the entry is still within the hysteresis window; otherwise runs the (potentially expensive)
     * frustum test, stores the result, and returns it.
     *
     * <p>{@code useVanilla} selects the test: vanilla {@code Frustum#isVisible} for far displays
     * (whose transformed boxes cannot use the margin-widened fast path), or LCull's allocation-free
     * {@code IFrustum#lcull$isVisible} with a {@link #CULL_MARGIN}-inflated box for everything
     * else. The box is widened inline to avoid allocating an intermediate AABB.</p>
     */
    @Unique
    private boolean lcull$resolve(
        T entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        boolean useVanilla
    ) {
        ICache cache = (ICache) entity;

        Minecraft mc = Minecraft.getInstance();
        long tick = mc.level != null ? mc.level.getGameTime() : -1L;

        IFrustum iFrustum = (IFrustum) (Object) frustum;
        long sig = lcull$sig(camX, camY, camZ, iFrustum.lcull$viewX(), iFrustum.lcull$viewY(), iFrustum.lcull$viewZ(), entity);

        if (tick >= 0 && sig == cache.lcull$getLastSig()
            && tick - cache.lcull$getLastTick() < HYSTERESIS_TICKS) {
            return cache.lcull$getCachedVisible();
        }

        boolean visible;
        if (useVanilla) {
            visible = frustum.isVisible(this.lcull$box(entity));
        } else {
            AABB box = this.lcull$box(entity);
            visible = iFrustum.lcull$isVisible(
                box.minX - CULL_MARGIN,
                box.minY - CULL_MARGIN,
                box.minZ - CULL_MARGIN,
                box.maxX + CULL_MARGIN,
                box.maxY + CULL_MARGIN,
                box.maxZ + CULL_MARGIN
            );
        }

        cache.lcull$setLastSig(sig);
        cache.lcull$setLastTick(tick);
        cache.lcull$setCachedVisible(visible);
        return visible;
    }

    /**
     * Cheap input signature for the off-screen test. Captures camera position and orientation (the
     * frustum's forward vector) plus the entity position, quantized so that small movements (below
     * the margin / sub-degree rotation) keep the same signature and reuse the cache. Collisions only
     * cause an unnecessary recompute, never a wrong decision, so a lossy mix is fine.
     */
    @Unique
    private static long lcull$sig(
        double camX,
        double camY,
        double camZ,
        float vx,
        float vy,
        float vz,
        Entity entity
    ) {
        int cx = (int) Math.floor(camX * 2.0);
        int cy = (int) Math.floor(camY * 2.0);
        int cz = (int) Math.floor(camZ * 2.0);
        int rx = (int) Math.floor(vx * 64.0);
        int ry = (int) Math.floor(vy * 64.0);
        int rz = (int) Math.floor(vz * 64.0);
        int ex = (int) Math.floor(entity.getX() * 2.0);
        int ey = (int) Math.floor(entity.getY() * 2.0);
        int ez = (int) Math.floor(entity.getZ() * 2.0);

        long s = (long) cx;
        s = Long.rotateLeft(s, 1) ^ (long) cy;
        s = Long.rotateLeft(s, 1) ^ (long) cz;
        s = Long.rotateLeft(s, 1) ^ (long) rx;
        s = Long.rotateLeft(s, 1) ^ (long) ry;
        s = Long.rotateLeft(s, 1) ^ (long) rz;
        s = Long.rotateLeft(s, 1) ^ (long) ex;
        s = Long.rotateLeft(s, 1) ^ (long) ey;
        s = Long.rotateLeft(s, 1) ^ (long) ez;
        return s;
    }
}
