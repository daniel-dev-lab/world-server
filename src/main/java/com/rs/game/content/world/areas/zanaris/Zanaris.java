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
package com.rs.game.content.world.areas.zanaris;

import com.rs.engine.dialogue.Conversation;
import com.rs.engine.dialogue.Dialogue;
import com.rs.engine.dialogue.HeadE;
import com.rs.engine.dialogue.Options;
import com.rs.game.content.skills.agility.Agility;
import com.rs.game.content.transportation.FairyRings;
import com.rs.game.content.world.AgilityShortcuts;
import com.rs.lib.game.Tile;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ItemOnObjectHandler;
import com.rs.plugin.handlers.NPCClickHandler;
import com.rs.plugin.handlers.ObjectClickHandler;
import com.rs.utils.shop.ShopsHandler;

@PluginEventHandler
public class Zanaris {

	public static ItemOnObjectHandler handleEnterBlackDragonPlane = new ItemOnObjectHandler(new Object[] { 12093 }, new Object[] { 2138 }, e -> {
		e.getPlayer().getInventory().deleteItem(2138, 1);
		FairyRings.sendTeleport(e.getPlayer(), Tile.of(1565, 4356, 0));
	});

	public static ItemOnObjectHandler handleDownBabyBlackDragons = new ItemOnObjectHandler(new Object[] { 12253 }, new Object[] { 954 }, e -> {
		e.getPlayer().useLadder(Tile.of(1544, 4381, 0));
	});

	public static ObjectClickHandler handleUpBabyBlackDragons = new ObjectClickHandler(new Object[] { 12255 }, e -> {
		e.getPlayer().useLadder(Tile.of(1561, 4380, 0));
	});

	public static ObjectClickHandler handleExitBlackDragonPlane = new ObjectClickHandler(new Object[] { 12260 }, e -> {
		e.getPlayer().setNextTile(Tile.of(2453, 4476, 0));
	});

	public static ObjectClickHandler handleCosmicAltarShortcuts = new ObjectClickHandler(new Object[] { 12127 }, e -> {
		if (!Agility.hasLevel(e.getPlayer(), e.getObject().getTile().isAt(2400, 4403) ? 46 : 66))
			return;
		AgilityShortcuts.sidestep(e.getPlayer(), e.getPlayer().transform(0, e.getPlayer().getY() > e.getObject().getY() ? -2 : 2, 0));
	});


	public static NPCClickHandler handleLunderwin = new NPCClickHandler(new Object[] { 565 }, e -> {
		int cabbageCount = e.getPlayer().getInventory().getAmountOf(1965);
		int option = e.getOpNum();
		if (option == 1)
			e.getPlayer().startConversation(new Conversation(e.getPlayer()) {
				{
					addNPC(e.getNPCId(), HeadE.HAPPY_TALKING, "Buying cabbage am I, not have such thing where I from. Will pay money much handsome for " +
							"wondrous object, cabbage you called. Say I 100 gold coins each fair price to be giving yes?");
					if (cabbageCount <= 0) {
						addPlayer(HeadE.NERVOUS, "Alas, I have no cabbages either...");
						addNPC(e.getNPCId(), HeadE.FRUSTRATED, "Pity be that, I want badly do.");
					} else
						addOptions(new Options() {
							@Override
							public void create() {
								option("Yes, I'll sell you all my cabbages", () -> {
									e.getPlayer().getInventory().deleteItem(1965, cabbageCount);
									e.getPlayer().getInventory().addCoins(cabbageCount * 100);
								});
								option("No, I will keep my cabbages", new Dialogue()
										.addPlayer(HeadE.CALM_TALK, "Yes, I'll sell you all my cabbages"));
							}
						});
					create();
				}
			});
		if (option == 3)
			ShopsHandler.openShop(e.getPlayer(), "zanaris_general_store");
	});
}
