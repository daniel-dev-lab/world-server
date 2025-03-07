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
package com.rs.engine.quest;

import com.rs.game.model.entity.player.Player;
import com.rs.lib.util.Utils;

import java.util.ArrayList;
import java.util.List;

public abstract class QuestOutline {
	public final Quest getQuest() {
		return getClass().getAnnotation(QuestHandler.class).value();
	}
	public abstract int getCompletedStage();
	public abstract List<String> getJournalLines(Player player, int stage);
	public abstract void complete(Player player);
	public abstract String getStartLocationDescription();
	public abstract String getRequiredItemsString();
	public abstract String getCombatInformationString();
	public abstract String getRewardsString();
	public void updateStage(Player player, int stage) {

	}

	public final void sendQuestCompleteInterface(Player player, int itemId) {
		int jingleNum = Utils.random(0, 4);
		if(jingleNum == 3)
			jingleNum = 318;
		else
			jingleNum+=152;
		player.jingle(jingleNum);

		player.getPackets().sendVarcString(359, getRewardsString());
		player.getInterfaceManager().sendInterface(1244);
		player.getPackets().setIFItem(1244, 24, itemId, 1);
		player.getPackets().setIFText(1244, 25, "You have completed "+getQuest().getDefs().name+"!");
	}
}
