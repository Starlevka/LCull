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

package starl.lcull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if fabric {
import net.fabricmc.api.ModInitializer;
//?}
//? if neoforge {
/*import net.neoforged.fml.common.Mod;*/
//?}
//? if forge {
/*import net.minecraftforge.fml.common.Mod;*/
//?}

/**
 * Common mod entrypoint plus the three loader bootstrap adapters as static nested classes.
 *
 * <p>{@link #init()} holds everything loader-independent; each nested adapter is gated to its own
 * loader via {@code //? if} blocks on the {@code fabric}/{@code neoforge}/{@code forge} constants,
 * which replaces the old per-loader source exclusions. Loader APIs are mutually exclusive on the
 * classpath, so only the matching adapter is ever compiled.</p>
 *
 * <p>Manifest references:</p>
 * <ul>
 *   <li><b>Fabric</b> - entrypoint {@code starl.lcull.LCull$Fabric} in the generated
 *     {@code fabric.mod.json}</li>
 *   <li><b>NeoForge / Forge</b> - discovered through the {@code @Mod} annotation scan</li>
 * </ul>
 *
 * <p>{@link #VERSION} is filled in by Stonecutter from {@code mod.version} in
 * {@code stonecutter.properties.toml}; the literal below is only the unprocessed fallback.</p>
 *
 * @author Starlev
 */
public final class LCull {
    public static final String MOD_ID = "lcull";
    public static final String VERSION = /*$ mod_version */ "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private LCull() {
    }

    /** Single shared init point; safe to call from any loader thread during bootstrap. */
    public static void init() {
        LOGGER.info("LCull v" + VERSION + " Initialized!");
    }

    //? if fabric {
    /** Fabric adapter; declared as the {@code main} entrypoint in {@code fabric.mod.json}. */
    public static final class Fabric implements ModInitializer {

        @Override
        public void onInitialize() {
            LCull.init();
        }
    }
    //?}
    //? if neoforge {
    /*@Mod(LCull.MOD_ID)
    public static final class NeoForge {

        public NeoForge() {
            LCull.init();
        }
    }*/
    //?}
    //? if forge {
    /*@Mod(LCull.MOD_ID)
    public static final class Forge {

        public Forge() {
            LCull.init();
        }
    }*/
    //?}
}
