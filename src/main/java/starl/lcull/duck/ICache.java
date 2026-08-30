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

package starl.lcull.duck;

/**
 * @author Starlev
 * Per-entity cache backing the off-screen frustum result, implemented by
 * {@code MEntityCullCache} and consumed by {@code MEntityRenderer}.
 *
 * <p>LCull keeps its allocation-free frustum math in {@code MFrustum}; this interface only
 * stores the last computed visibility decision together with two cheap signatures: one for the
 * current frustum state (camera position/orientation/projection) and one for the entity position.
 * When both still match, the cached decision is reused and the {@code intersectAab} test is
 * skipped entirely.</p>
 */
public interface ICache {

    /** Last cached visibility decision for this entity. */
    boolean lcull$getCachedVisible();

    /** Stores the last cached visibility decision. */
    void lcull$setCachedVisible(boolean visible);

    /** Signature of the entity position the cached decision was computed under. */
    long lcull$getLastEntitySig();

    /** Stores the entity-position signature the cached decision was computed under. */
    void lcull$setLastEntitySig(long sig);

    /** Signature of the frustum state the cached decision was computed under. */
    long lcull$getLastFrustumSig();

    /** Stores the frustum-state signature the cached decision was computed under. */
    void lcull$setLastFrustumSig(long sig);
}
