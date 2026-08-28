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

import net.minecraft.client.multiplayer.ClientChunkCache;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * @author Starlev
 * Silences the benign "Ignoring chunk since it's not in the view range" log spam emitted by
 * {@link ClientChunkCache} when chunk data packets arrive for chunks outside the client's view
 * distance. The warning is purely cosmetic - the chunk is still ignored exactly as before - but it
 * floods the log on busy servers, so we drop just that one message.
 */
@Mixin(ClientChunkCache.class)
public abstract class MClientChunkCache {

    @Unique private static final boolean LCULL$SILENCE_VIEW_RANGE_LOG = true;

    @Redirect(
        method = "replaceBiomes",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
            remap = false
        ),
        require = 0
    )
    private void lcull$silenceViewRangeLogBiomes(Logger logger, String message, Object p0, Object p1) {
        if (!LCULL$SILENCE_VIEW_RANGE_LOG
            || !"Ignoring chunk since it's not in the view range: {}, {}".equals(message)) {
            logger.warn(message, p0, p1);
        }
    }

    @Redirect(
        method = "replaceWithPacketData",
        at = @At(
            value = "INVOKE",
            target = "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
            remap = false
        ),
        require = 0
    )
    private void lcull$silenceViewRangeLogPacket(Logger logger, String message, Object p0, Object p1) {
        if (!LCULL$SILENCE_VIEW_RANGE_LOG
            || !"Ignoring chunk since it's not in the view range: {}, {}".equals(message)) {
            logger.warn(message, p0, p1);
        }
    }
}
