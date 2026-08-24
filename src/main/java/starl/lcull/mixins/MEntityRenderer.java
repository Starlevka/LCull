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
import starl.lcull.duck.IFrustum;

/**
 * Entity render culling injected ahead of vanilla logic in {@code EntityRenderer#shouldRender}.
 *
 * <p>Mirrors the original LCull decision tree one-to-one: hard distance cap, near-camera display
 * exemption, far-display raw-box frustum test, and a {@link #CULL_MARGIN}-widened box test for
 * everything else.</p>
 *
 * @author Starlev
 */
@Mixin(value = EntityRenderer.class, priority = 600)
public abstract class MEntityRenderer<T extends Entity> {

    /** Hard visibility cap: 512 blocks, squared. Beyond it an entity is culled outright. */
    @Unique private static final double MAX_DIST_SQ            = 262144.0;

    /** Squared radius under which display entities always defer to vanilla (5 blocks, squared). */
    @Unique private static final double DISPLAY_CULL_RADIUS_SQ = 25.0;

    /** Safety margin (blocks) added around the culling box of non-display entities. */
    @Unique private static final double CULL_MARGIN            = 3.0;

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

        // Hard cap: past 512 blocks nothing survives culling, margins stop mattering.
        if (distSq > MAX_DIST_SQ) {
            cir.setReturnValue(false);
            return;
        }

        AABB aabb = this.lcull$box(entity);

        if (entity instanceof Display) {
            // Near-camera displays: transformed boxes cannot be trusted - vanilla decides.
            if (distSq < DISPLAY_CULL_RADIUS_SQ) {
                return;
            }

            // Far displays: raw box without margin (transforms already oversize it).
            if (frustum.isVisible(aabb)) return;
            cir.setReturnValue(false);
            return;
        }

        AABB expanded = aabb.inflate(CULL_MARGIN);

        IFrustum iFrustum = (IFrustum) (Object) frustum;
        if (
            !iFrustum.lcull$isVisible(
                expanded.minX,
                expanded.minY,
                expanded.minZ,
                expanded.maxX,
                expanded.maxY,
                expanded.maxZ
            )
        ) {
            cir.setReturnValue(false);
        }
    }
}
