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
 * Duck interface over the patched client {@code Frustum}, implemented by
 * {@code MFrustum} and consumed by {@code MEntityRenderer}.
 *
 * <p>Exposes LCull's allocation-free visibility test: raw camera-relative coordinates straight
 * to the shared JOML tester, skipping vanilla's wrapper overhead.</p>
 *
 * @author Starlev
 */
public interface IFrustum {

    /**
     * Vanilla visibility semantics over the shared JOML tester: everything except fully-outside
     * counts as visible - {@code result == -2 || result == -1}.
     */
    boolean lcull$isVisible(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    );
}
