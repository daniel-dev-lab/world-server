package com.rs.game.content.minigames.trawler;

import com.rs.game.model.entity.player.Controller;
import com.rs.lib.game.Tile;

public class FishingTrawlerLobbyController extends Controller {

	@Override
	public void start() {

	}

	public boolean login() {
		leaveLobby();
		player.setNextTile(Tile.of(2676, 3170, 0));
		return true;
	}

	@Override
	public boolean logout() {
		leaveLobby();
		player.setLocation(Tile.of(2676, 3170, 0));
		return true;
	}

	@Override
	public void magicTeleported(int type) {
		leaveLobby();
	}

	public void leaveLobby() {
		FishingTrawler.getInstance().removeLobbyPlayer(player);
	}
}
