/*
 *  Copyright (C) <2022> <XiaoMoMi>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.momirealms.customfishing.api.manager;

import net.momirealms.customfishing.api.mechanic.loot.Loot;
import net.momirealms.customfishing.api.mechanic.entity.EntityLibrary;
import org.bukkit.Location;

public interface EntityManager {
    boolean registerEntityLibrary(EntityLibrary entityLibrary);

    boolean unregisterEntityLibrary(String lib);

    boolean unregisterEntityLibrary(EntityLibrary entityLibrary);

    void summonEntity(Location hookLocation, Location playerLocation, Loot loot);
}