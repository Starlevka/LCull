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

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import starl.lcull.duck.ICache;

/**
 * @author Starlev
 * Attaches the per-entity off-screen frustum cache ({@link ICache}) to every
 * {@link Entity}. The fields are tiny (three longs + a flag) and only consulted on the render
 * cull path, so the memory footprint is negligible even with thousands of entities.
 */
@Mixin(Entity.class)
public abstract class MEntity implements ICache {

    @Unique private boolean lcull$cachedVisible;
    @Unique private long lcull$lastSig  = -1L;
    @Unique private long lcull$lastTick = -1L;

    @Override
    public boolean lcull$getCachedVisible() {
        return this.lcull$cachedVisible;
    }

    @Override
    public void lcull$setCachedVisible(boolean visible) {
        this.lcull$cachedVisible = visible;
    }

    @Override
    public long lcull$getLastSig() {
        return this.lcull$lastSig;
    }

    @Override
    public void lcull$setLastSig(long sig) {
        this.lcull$lastSig = sig;
    }

    @Override
    public long lcull$getLastTick() {
        return this.lcull$lastTick;
    }

    @Override
    public void lcull$setLastTick(long tick) {
        this.lcull$lastTick = tick;
    }
}
