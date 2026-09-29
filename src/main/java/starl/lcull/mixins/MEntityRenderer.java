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
//? if >=1.19.4 {
import net.minecraft.world.entity.Display;
//?}
//? if >=1.21 {
import net.minecraft.world.entity.Leashable;
//?}
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
 * @author Starlev
 * Entity render culling injected ahead of vanilla logic in {@code EntityRenderer#shouldRender}.
 *
 * <p>Pure frustum culling: every entity that survives the vanilla opt-outs is decided by the
 * camera frustum and nothing else - no occlusion graph, no section bookkeeping. Vanilla's
 * contract is preserved, only narrowed: the distance check, the "never cull me" opt-out and the
 * leash escape all still run, and LCull only ever adds rejections on top. The math itself lives
 * in {@code MFrustum}.</p>
 *
 * <p>The tree is ordered cheapest-reject-first, and the distance check is deliberately hoisted
 * above the box lookup: at a few thousand entities the frustum test is the expensive part, and
 * vanilla's squared-distance test rejects most of the scene before a culling box is ever read.</p>
 */
@Mixin(value = EntityRenderer.class, priority = 2100)
public abstract class MEntityRenderer<T extends Entity> {

    /** Squared radius under which display entities always defer to vanilla (5 blocks, squared). */
    @Unique private static final double DISPLAY_CULL_RADIUS_SQ = 25.0;

    /** Safety margin (blocks) added around the culling box of non-display entities. */
    @Unique private static final double CULL_MARGIN            = 1.0;

    /** Largest margin the speed-based growth may reach. */
    @Unique private static final double CULL_MARGIN_MAX        = 2.5;

    /** Margin (blocks) added per block/tick of movement. */
    @Unique private static final double CULL_MARGIN_PER_SPEED  = 0.2;

    /** Below this squared speed the entity counts as stationary. */
    @Unique private static final double MOVE_SPEED_SQ_MIN      = 0.25;

    /** Margin for far display entities; their transformed boxes are already oversized. */
    @Unique private static final double DISPLAY_MARGIN         = 1.0;

    /** Half-extent of the fallback box used when a renderer reports a broken culling box. */
    @Unique private static final double BROKEN_BOX_RADIUS      = 2.0;

    // Renderer-level culling box exists only since 1.21.2; before that vanilla reads the entity's own box.
    // Since 26.3 the box lookup needs the frame's partialTicks (kept in lcull$partialTicks below).
    //? if <1.21.2 {
    /*private AABB lcull$box(T entity) {
        return entity.getBoundingBoxForCulling();
    }*/
    //?} else {
    //? if >=26.3 {
    /*@Shadow protected abstract AABB getBoundingBoxForCulling(T entity, float partialTicks);

    private AABB lcull$box(T entity) {
        return this.getBoundingBoxForCulling(entity, this.lcull$partialTicks);
    }*/
    //?} else {
    @Shadow protected abstract AABB getBoundingBoxForCulling(T entity);

    private AABB lcull$box(T entity) {
        return this.getBoundingBoxForCulling(entity);
    }
    //?}
    //?}

    // Vanilla's "always render me" opt-out. Fishing hooks, the ender dragon, lightning bolts and
    // noCulling displays all use it, and vanilla consults it before any culling math - so LCull has
    // to consult it too or those entities pop. The hook moved from the public Entity#noCulling
    // field to this renderer method in 1.21.2.
    //? if >=1.21.2 {
    @Shadow protected abstract boolean affectedByCulling(T entity);

    @Unique
    private boolean lcull$unaffectedByCulling(T entity) {
        return !this.affectedByCulling(entity);
    }
    //? } else {
    /*@Unique
    private boolean lcull$unaffectedByCulling(T entity) {
        return entity.noCulling;
    }*/
    //?}

    // Stores partialTicks for lcull$box (26.3+ culling box takes it as a parameter).
    //? if >=26.3 {
    /*@Unique private float lcull$partialTicks;*/
    //?}

    //? if >=26.3 {
    /*@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void lcull$shouldRender(
        T entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        float partialTicks,
        CallbackInfoReturnable<Boolean> cir
    ) {
        this.lcull$partialTicks = partialTicks;
        lcull$decide(entity, frustum, camX, camY, camZ, cir);
    }*/
    //?} else {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void lcull$shouldRender(
        T entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        lcull$decide(entity, frustum, camX, camY, camZ, cir);
    }
    //?}

    /**
     * Full culling decision; every version's handler funnels here.
     *
     * <p>The call order deliberately mirrors vanilla's own {@code EntityRenderer#shouldRender}:
     * distance rejection first, then the "never cull me" opt-out, then the frustum. Profiling a
     * 10k-entity scene showed that order matters more than the arithmetic — {@code shouldRender}
     * accounted for 66% of this path and {@code affectedByCulling} for another 18%, and calling
     * the latter before the former meant paying a megamorphic virtual call for entities that were
     * about to be discarded anyway.</p>
     *
     * <p>Reordering is safe because vanilla itself checks {@code !entity.shouldRender(...)} before
     * {@code affectedByCulling}, so cancelling on the distance check produces the same verdict for
     * an opt-out entity as letting vanilla run would: {@code false}.</p>
     */
    @Unique
    private void lcull$decide(
        T entity,
        Frustum frustum,
        double camX,
        double camY,
        double camZ,
        CallbackInfoReturnable<Boolean> cir
    ) {
        // Players are never skipped; the check is one class test, so it stays first.
        if (entity instanceof Player) return;

        // Vanilla's own distance rejection, before anything else we add. It is the cheapest test
        // available and it discards most of a distant scene before a culling box is ever read.
        if (!entity.shouldRender(camX, camY, camZ)) {
            cir.setReturnValue(false);
            return;
        }

        // Vanilla renders these unconditionally. Returning without cancelling hands the entity
        // back to it.
        if (this.lcull$unaffectedByCulling(entity)) return;

        //? if >=1.19.4 {
        if (entity instanceof Display) {
            double dx = entity.getX() - camX;
            double dy = entity.getY() - camY;
            double dz = entity.getZ() - camZ;
            // Near-camera displays: transformed boxes cannot be trusted - vanilla decides.
            if (dx * dx + dy * dy + dz * dz < DISPLAY_CULL_RADIUS_SQ) return;

            // Far displays: raw box with a fixed 1.0 margin, because a display's transform has
            // already oversized it. Deliberately not routed through the speed-based margin below:
            // a moving text display can carry a large delta, and growing its margin with speed
            // would widen exactly the case that already over-draws.
            cir.setReturnValue(lcull$frustumVisible(entity, frustum, DISPLAY_MARGIN));
            return;
        }
        //?}

        cir.setReturnValue(
            lcull$frustumVisible(entity, frustum, lcull$effectiveMargin(entity))
        );
    }

    /**
     * The whole frustum decision: read the culling box, guard against renderers that report a
     * broken one, test it against the camera frustum, and honour the leash escape.
     */
    @Unique
    private boolean lcull$frustumVisible(T entity, Frustum frustum, double margin) {
        AABB box = this.lcull$box(entity);
        if (box.hasNaN() || box.getSize() == 0.0D) {
            return lcull$isVisibleAt(entity, frustum, BROKEN_BOX_RADIUS);
        }
        return lcull$isVisible(frustum, box, margin)
            || lcull$leashHolderVisible(entity, box, frustum);
    }

    /** Box test, widened inline by {@code margin} without allocating. */
    @Unique
    private static boolean lcull$isVisible(Frustum frustum, AABB box, double margin) {
        return lcull$isVisible(
            frustum,
            box.minX - margin, box.minY - margin, box.minZ - margin,
            box.maxX + margin, box.maxY + margin, box.maxZ + margin
        );
    }

    /** Raw coordinate form, so a union can be tested without materialising an AABB for it. */
    @Unique
    private static boolean lcull$isVisible(
        Frustum frustum,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        IFrustum iFrustum = (IFrustum) (Object) frustum;
        return iFrustum.lcull$isVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Fallback for renderers that report a NaN or zero-size culling box. */
    @Unique
    private static boolean lcull$isVisibleAt(Entity entity, Frustum frustum, double radius) {
        IFrustum iFrustum = (IFrustum) (Object) frustum;
        double x = entity.getX();
        double y = entity.getY();
        double z = entity.getZ();
        return iFrustum.lcull$isVisible(x - radius, y - radius, z - radius,
                                        x + radius, y + radius, z + radius);
    }

    /**
     * Vanilla keeps a leashed entity visible while its holder is, so the rope does not visually
     * detach; LCull has to repeat that escape before dropping the entity.
     *
     * <p>Both of vanilla's tests are kept: the holder's own box, and the union of the two. The
     * union is not redundant - two boxes on opposite sides of a plane can straddle it while
     * neither intersects - and collapsing it to the union alone would silently admit entities
     * vanilla drops. It is computed with {@code Math.min}/{@code Math.max} into a test call rather
     * than materialised as an AABB.</p>
     *
     * <p>One deviation from vanilla: it asks the <em>holder's renderer</em> for its culling box.
     * Every renderer a leash holder can have (mobs, players) returns the entity's own box - the
     * sole override that differs is {@code LivingEntityRenderer}'s dragon-head inflation - so
     * reading it directly is equivalent here and avoids exposing a protected hook on another
     * renderer.</p>
     */
    //? if >=1.21 {
    @Unique
    private static boolean lcull$leashHolderVisible(Entity entity, AABB box, Frustum frustum) {
        if (!(entity instanceof Leashable leashable)) return false;
        Entity holder = leashable.getLeashHolder();
        if (holder == null) return false;

        AABB holderBox = holder.getBoundingBox();
        if (lcull$isVisible(frustum, holderBox, 0.0D)) return true;

        return lcull$isVisible(
            frustum,
            Math.min(box.minX, holderBox.minX),
            Math.min(box.minY, holderBox.minY),
            Math.min(box.minZ, holderBox.minZ),
            Math.max(box.maxX, holderBox.maxX),
            Math.max(box.maxY, holderBox.maxY),
            Math.max(box.maxZ, holderBox.maxZ)
        );
    }
    //?} else {
    /*@Unique
    private static boolean lcull$leashHolderVisible(Entity entity, AABB box, Frustum frustum) {
        return false; // net.minecraft.world.entity.Leashable does not exist before 1.21
    }*/
    //?}

    /**
     * Margin grows with movement speed so a fast entity does not pop mid-stride, and is capped so a
     * projectile cannot exempt itself from culling by moving fast.
     */
    @Unique
    private static double lcull$effectiveMargin(Entity entity) {
        double lenSqr = entity.getDeltaMovement().lengthSqr();
        if (lenSqr <= MOVE_SPEED_SQ_MIN) return CULL_MARGIN;

        double margin = CULL_MARGIN + Math.sqrt(lenSqr) * CULL_MARGIN_PER_SPEED;
        return margin > CULL_MARGIN_MAX ? CULL_MARGIN_MAX : margin;
    }
}
