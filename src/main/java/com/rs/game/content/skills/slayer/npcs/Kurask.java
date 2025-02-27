// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Copyright (C) 2021 Trenton Kress
//  This file is part of project: Darkan
//
package com.rs.game.content.skills.slayer.npcs;

import com.rs.game.content.combat.AmmoType;
import com.rs.game.content.combat.RangedWeapon;
import com.rs.game.model.entity.Hit;
import com.rs.game.model.entity.npc.NPC;
import com.rs.game.model.entity.player.Player;
import com.rs.lib.game.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.NPCInstanceHandler;

@PluginEventHandler
public class Kurask extends NPC {

	public Kurask(int id, Tile tile) {
		super(id, tile);
	}

	@Override
	public void handlePreHit(Hit hit) {
		if (hit.getSource() instanceof Player) {
			Player player = (Player) hit.getSource();
			RangedWeapon weapon = RangedWeapon.forId(player.getEquipment().getWeaponId());
			AmmoType ammo = AmmoType.forId(player.getEquipment().getAmmoId());
			if (!(player.getEquipment().getWeaponId() == 13290 || player.getEquipment().getWeaponId() == 4158) && !(weapon != null && weapon.getAmmos() != null && ammo != null && (ammo == AmmoType.BROAD_ARROW || ammo == AmmoType.BROAD_TIPPED_BOLTS)))
				hit.setDamage(0);
		}
		super.handlePreHit(hit);
	}

	public static NPCInstanceHandler toFunc = new NPCInstanceHandler(new Object[] { 1608, 1609 }, (npcId, tile) -> new Kurask(npcId, tile));
}
