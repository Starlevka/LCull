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
 * while both the current frustum state and the quantized entity position remain unchanged.</p>
 */
@Mixin(value = EntityRenderer.class, priority = 600)
public abstract class MEntityRenderer<T extends Entity> {


    /** Squared radius under which display entities always defer to vanilla (5 blocks, squared). */
    @Unique private static final double DISPLAY_CULL_RADIUS_SQ = 25.0;

    /** Safety margin (blocks) added around the culling box of non-display entities. */
    @Unique private static final double CULL_MARGIN            = 2.0;

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
            if (!lcull$resolve(entity, frustum, true)) {
                cir.setReturnValue(false);
            }
            return;
        }

        if (!lcull$resolve(entity, frustum, false)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Returns the cached off-screen decision if both the entity-position signature and the current
     * frustum signature still match; otherwise runs the (potentially expensive) frustum test,
     * stores the result, and returns it.
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
        boolean useVanilla
    ) {
        ICache cache = (ICache) entity;
        IFrustum iFrustum = (IFrustum) (Object) frustum;
        long frustumSig = iFrustum.lcull$frustumSig();
        long entitySig  = lcull$entitySig(entity);

        if (frustumSig == cache.lcull$getLastFrustumSig()
        &&  entitySig  == cache.lcull$getLastEntitySig()) {
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

        cache.lcull$setLastFrustumSig(frustumSig);
        cache.lcull$setLastEntitySig(entitySig);
        cache.lcull$setCachedVisible(visible);
        return visible;
    }

    /**
     * Cheap entity-side signature for the off-screen test. Frustum state is handled separately by
     * {@code MFrustum}; here we only quantize the entity position so small movements still reuse the
     * cache. Collisions only cause an unnecessary recompute, never a wrong decision, so a lossy mix
     * is fine.
     */
    @Unique
    private static long lcull$entitySig(Entity entity) {
        int ex = (int) Math.floor(entity.getX() * 2.0);
        int ey = (int) Math.floor(entity.getY() * 2.0);
        int ez = (int) Math.floor(entity.getZ() * 2.0);

        long s = (long) ex;
        s = Long.rotateLeft(s, 1) ^ (long) ey;
        s = Long.rotateLeft(s, 1) ^ (long) ez;
        return s;
    }
}
