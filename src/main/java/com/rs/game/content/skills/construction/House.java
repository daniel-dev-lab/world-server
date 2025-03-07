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
package com.rs.game.content.skills.construction;

import com.rs.cache.loaders.ObjectDefinitions;
import com.rs.cache.loaders.ObjectType;
import com.rs.cache.loaders.interfaces.IFEvents;
import com.rs.engine.dialogue.Conversation;
import com.rs.engine.dialogue.Dialogue;
import com.rs.engine.dialogue.statements.Statement;
import com.rs.game.World;
import com.rs.game.content.pets.Pets;
import com.rs.game.content.skills.construction.HouseConstants.*;
import com.rs.game.map.Chunk;
import com.rs.game.map.ChunkManager;
import com.rs.game.map.instance.Instance;
import com.rs.game.map.instance.InstancedChunk;
import com.rs.game.model.entity.ForceTalk;
import com.rs.game.model.entity.npc.NPC;
import com.rs.game.model.entity.player.Controller;
import com.rs.game.model.entity.player.Player;
import com.rs.game.model.entity.player.managers.InterfaceManager.Sub;
import com.rs.game.model.object.GameObject;
import com.rs.game.tasks.WorldTask;
import com.rs.game.tasks.WorldTasks;
import com.rs.lib.Constants;
import com.rs.lib.game.Animation;
import com.rs.lib.game.Item;
import com.rs.lib.game.Rights;
import com.rs.lib.game.Tile;
import com.rs.lib.util.Logger;
import com.rs.lib.util.MapUtils;
import com.rs.lib.util.MapUtils.Structure;
import com.rs.lib.util.Utils;
import com.rs.plugin.annotations.PluginEventHandler;
import com.rs.plugin.handlers.ButtonClickHandler;
import com.rs.plugin.handlers.ObjectClickHandler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

@PluginEventHandler
public class House {

	public static int LOGGED_OUT = 0, KICKED = 1, TELEPORTED = 2;
	private List<RoomReference> roomsR;

	private byte look;
	private POHLocation location;
	private boolean buildMode;
	private boolean arriveInPortal;
	private Servant servant;
	private byte paymentStage;

	private transient Player player;
	private transient boolean locked;
	private transient CopyOnWriteArrayList<NPC> pets;
	private transient CopyOnWriteArrayList<NPC> npcs;

	private transient List<Player> players;
	private transient Instance instance;
	private transient boolean loaded;
	private transient boolean challengeMode;
	private transient ServantNPC servantInstance;

	private PetHouse petHouse;

	private byte build;

	public void setLocation(POHLocation location) {
		this.location = location;
	}

	public POHLocation getLocation() {
		return location;
	}

	private boolean isOwnerInside() {
		return players.contains(player);
	}

	public static ObjectClickHandler handleHousePortals = new ObjectClickHandler(Arrays.stream(POHLocation.values()).map(loc -> loc.getObjectId()).toArray(), e -> {
		e.getPlayer().startConversation(new Dialogue().addOptions(ops -> {
			ops.add("Go to your house.", () -> {
				e.getPlayer().getHouse().setBuildMode(false);
				e.getPlayer().getHouse().enterMyHouse();
			});
			ops.add("Go to your house (building mode).", () -> {
				e.getPlayer().getHouse().kickGuests();
				e.getPlayer().getHouse().setBuildMode(true);
				e.getPlayer().getHouse().enterMyHouse();
			});
			ops.add("Go to a friend's house.", () -> {
				if (e.getPlayer().isIronMan()) {
					e.getPlayer().sendMessage("You cannot enter another player's house as an ironman.");
					return;
				}
				e.getPlayer().sendInputName("Enter name of the person who's house you'd like to join:", name -> House.enterHouse(e.getPlayer(), name));
			});
			ops.add("Nevermind.");
		}));
	});

	public static ButtonClickHandler handleHouseOptions = new ButtonClickHandler(398, e -> {
		if (e.getComponentId() == 19)
			e.getPlayer().getInterfaceManager().sendSubDefault(Sub.TAB_SETTINGS);
		else if (e.getComponentId() == 15 || e.getComponentId() == 1)
			e.getPlayer().getHouse().setBuildMode(e.getComponentId() == 15);
		else if (e.getComponentId() == 25 || e.getComponentId() == 26)
			e.getPlayer().getHouse().setArriveInPortal(e.getComponentId() == 25);
		else if (e.getComponentId() == 27)
			e.getPlayer().getHouse().expelGuests();
		else if (e.getComponentId() == 29)
			House.leaveHouse(e.getPlayer());
	});

	public static ButtonClickHandler handleCreateRoom = new ButtonClickHandler(402, e -> {
		if (e.getComponentId() >= 93 && e.getComponentId() <= 115)
			e.getPlayer().getHouse().createRoom(e.getComponentId() - 93);
	});

	public static ButtonClickHandler handleBuild = new ButtonClickHandler(new Object[] { 394, 396 }, e -> {
		if (e.getComponentId() == 11)
			e.getPlayer().getHouse().build(e.getSlotId());
	});

	public void expelGuests() {
		if (!isOwnerInside()) {
			player.sendMessage("You can only expel guests when you are in your own house.");
			return;
		}
		kickGuests();
	}

	public void kickGuests() {
		if ((players == null) || (players.size() <= 0))
			return;
		for (Player player : new ArrayList<>(players)) {
			if (isOwner(player))
				continue;
			leaveHouse(player, KICKED);
		}
	}

	public boolean isOwner(Player player) {
		return this.player.getUsername().equalsIgnoreCase(player.getUsername());
	}

	public void enterMyHouse() {
		joinHouse(player);
	}

	public void openRoomCreationMenu(GameObject door) {
		int roomX = player.getChunkX() - instance.getBaseChunkX(); // current room
		int roomY = player.getChunkY() - instance.getBaseChunkY(); // current room
		int xInChunk = player.getXInChunk();
		int yInChunk = player.getYInChunk();
		if (xInChunk == 7)
			roomX += 1;
		else if (xInChunk == 0)
			roomX -= 1;
		else if (yInChunk == 7)
			roomY += 1;
		else if (yInChunk == 0)
			roomY -= 1;
		openRoomCreationMenu(roomX, roomY, door.getPlane());
	}

	public void removeRoom() {
		int roomX = player.getChunkX() - instance.getBaseChunkX(); // current room
		int roomY = player.getChunkY() - instance.getBaseChunkY(); // current room
		RoomReference room = getRoom(roomX, roomY, player.getPlane());
		if (room == null)
			return;
		if (room.getZ() != 1) {
			player.simpleDialogue("You cannot remove a building that is supporting this room.");
			return;
		}

		RoomReference above = getRoom(roomX, roomY, 2);
		RoomReference below = getRoom(roomX, roomY, 0);

		RoomReference roomTo = above != null && above.getStaircaseSlot() != -1 ? above : below != null && below.getStaircaseSlot() != -1 ? below : null;
		if (roomTo == null) {
			player.simpleDialogue("These stairs do not lead anywhere.");
			return;
		}
		openRoomCreationMenu(roomTo.getX(), roomTo.getY(), roomTo.getZ());
	}

	/*
	 * door used to calculate where player facing to create
	 */
	public void openRoomCreationMenu(int roomX, int roomY, int plane) {
		if (!buildMode) {
			player.simpleDialogue("You can only do that in building mode.");
			return;
		}
		RoomReference room = getRoom(roomX, roomY, plane);
		if (room != null) {
			if (room.plane == 1 && getRoom(roomX, roomY, room.plane + 1) != null) {
				player.simpleDialogue("You can't remove a room that is supporting another room.");
				return;
			}
			if (room.room == Room.THRONE_ROOM && room.plane == 1) {
				RoomReference bellow = getRoom(roomX, roomY, room.plane - 1);
				if (bellow != null && bellow.room == Room.OUTBLIETTE) {
					player.simpleDialogue("You can't remove a throne room that is supporting a outbliette.");
					return;
				}
			}
			if ((room.room == Room.GARDEN || room.room == Room.FORMAL_GARDEN) && getPortalCount() < 2) {
				if (room == getPortalRoom()) {
					player.simpleDialogue("Your house must have at least one exit portal.");
					return;
				}
			}
			player.startConversation(new Dialogue().
				addOptions("Do you really want to remove the room?", ops -> {
					ops.add("Yes")
						.addOptions("You can't get anything back? Remove room?", conf -> {
							conf.add("Yes, get rid of my money already!", () -> player.getHouse().removeRoom(room));
							conf.add("No.");
						});
					ops.add("No");
				}));
		} else {
			if (roomX == 0 || roomY == 0 || roomX == 7 || roomY == 7) {
				player.simpleDialogue("You can't create a room here.");
				return;
			}
			if (plane == 2) {
				RoomReference r = getRoom(roomX, roomY, 1);
				if (r == null || (r.room == Room.GARDEN || r.room == Room.FORMAL_GARDEN || r.room == Room.MENAGERIE)) {
					player.simpleDialogue("You can't create a room here.");
					return;
				}

			}
			for (int index = 0; index < HouseConstants.Room.values().length - 2; index++) {
				Room refRoom = HouseConstants.Room.values()[index];
				if (player.getSkills().getLevel(Constants.CONSTRUCTION) >= refRoom.getLevel() && player.getInventory().hasCoins(refRoom.getPrice()))
					player.getPackets().setIFText(402, index + (refRoom == HouseConstants.Room.DUNGEON_STAIRS || refRoom == HouseConstants.Room.DUNGEON_PIT ? 69 : refRoom == HouseConstants.Room.TREASURE_ROOM ? 70 : 68), "<col=008000> " + refRoom.getPrice() + " coins");
			}
			player.getInterfaceManager().sendInterface(402);
			player.getTempAttribs().setO("CreationRoom", new int[] { roomX, roomY, plane });
			player.setCloseInterfacesEvent(() -> player.getTempAttribs().removeO("CreationRoom"));
		}
	}

	public void handleLever(Player player, GameObject object) {
		if (buildMode || player.isLocked())
			return;
		final int roomX = player.getChunkX() - instance.getBaseChunkX(); // current room
		final int roomY = player.getChunkY() - instance.getBaseChunkY(); // current room
		RoomReference room = getRoom(roomX, roomY, player.getPlane());
		int trap = room.getTrapObject();
		player.setNextAnimation(new Animation(9497));
		if (trap == -1 || trap == HObject.FLOORDECORATION.getId())
			return;
		player.lock(7);
		if (trap == HObject.TRAPDOOR.getId())
			player.sendOptionDialogue("What would you like to do?", ops -> {
				ops.add("Drop into oubliette", () -> dropPlayers(roomX, roomY, 13681, 13681, 13681, 13681));
				ops.add("Nothing.");
			});
		else if (trap == HObject.STEELCAGE.getId()) {
			trapPlayers(roomX, roomY, 13681, 13681, 13681, 13681);
			player.sendOptionDialogue("What would you like to do?", ops -> {
				ops.add("Release players", () -> releasePlayers(roomX, roomY, 13681, 13681, 13681, 13681));
				ops.add("Nothing.");
			});
		} else if (trap == HObject.LESSERMAGICCAGE.getId()) {
			trapPlayers(roomX, roomY, 13682);
			player.sendOptionDialogue("What would you like to do?", ops -> {
				ops.add("Release players", () -> releasePlayers(roomX, roomY, 13682));
				ops.add("Drop into oubliette", () -> dropPlayers(roomX, roomY, 13682));
			});
		} else if (trap == HObject.GREATERMAGICCAGE.getId()) {
			trapPlayers(roomX, roomY, 13683);
			player.sendOptionDialogue("What would you like to do?", ops -> {
				ops.add("Release players", () -> releasePlayers(roomX, roomY, 13683));
				ops.add("Drop into oubliette", () -> dropPlayers(roomX, roomY, 13683));
				ops.add("Kick from house", () -> kickTrapped(roomX, roomY, 13683));
			});
		}
	}

	public ArrayList<Player> getTrappedPlayers(int x, int y) {
		ArrayList<Player> list = new ArrayList<>();
		for (Player p : players)
			if (p != null && p.getControllerManager().getController() instanceof HouseController)
				if ((p.getX() >= x && p.getX() <= x + 1) && (p.getY() >= y && p.getY() <= y + 1))
					list.add(p);
		return list;
	}

	public void kickTrapped(int roomX, int roomY, int... trapIds) {
		int x = instance.getLocalX(roomX, 3);
		int y = instance.getLocalY(roomY, 3);
		for (final Player p : getTrappedPlayers(x, y)) {
			if (isOwner(p)) {
				p.setNextForceTalk(new ForceTalk("Trying to kick the house owner... Pfft.."));
				continue;
			}
			leaveHouse(p, KICKED);
		}
		releasePlayers(roomX, roomY, trapIds);
	}

	public void dropPlayers(int roomX, int roomY, int... trapIds) {
		RoomReference roomTo = getRoom(roomX, roomY, 0);
		if (roomTo == null || roomTo.getLadderTrapSlot() == -1) {
			releasePlayers(roomX, roomY, trapIds);
			return;
		}
		int x = instance.getLocalX(roomX, 3);
		int y = instance.getLocalY(roomY, 3);
		for (final Player p : getTrappedPlayers(x, y)) {
			p.lock(10);
			p.setNextAnimation(new Animation(1950));
			WorldTasks.schedule(new WorldTask() {
				@Override
				public void run() {
					p.setNextTile(Tile.of(p.getX(), p.getY(), 0));
					p.setNextAnimation(new Animation(3640));
				}
			}, 5);
		}
		releasePlayers(roomX, roomY, trapIds);
	}

	public void releasePlayers(int roomX, int roomY, int... trapIds) {
		int x = instance.getLocalX(roomX, 3);
		int y = instance.getLocalY(roomY, 3);
		World.removeObject(new GameObject(trapIds[0], ObjectType.SCENERY_INTERACT, 1, Tile.of(x, y, player.getPlane())));
		if (trapIds.length > 1)
			World.removeObject(new GameObject(trapIds[1], ObjectType.SCENERY_INTERACT, 0, Tile.of(x + 1, y, player.getPlane())));
		if (trapIds.length > 2)
			World.removeObject(new GameObject(trapIds[2], ObjectType.SCENERY_INTERACT, 2, Tile.of(x, y + 1, player.getPlane())));
		if (trapIds.length > 3)
			World.removeObject(new GameObject(trapIds[3], ObjectType.SCENERY_INTERACT, 3, Tile.of(x + 1, y + 1, player.getPlane())));
		World.removeObject(World.getObjectWithType(Tile.of(x - 1, y + 1, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x - 1, y, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x, y + 2, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x + 1, y + 2, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x, y - 1, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x + 1, y - 1, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x + 2, y, player.getPlane()), ObjectType.WALL_STRAIGHT));
		World.removeObject(World.getObjectWithType(Tile.of(x + 2, y + 1, player.getPlane()), ObjectType.WALL_STRAIGHT));
		for (Player p : getTrappedPlayers(x, y))
			p.resetWalkSteps();
	}

	public void trapPlayers(int roomX, int roomY, int... trapIds) {
		int x = instance.getLocalX(roomX, 3);
		int y = instance.getLocalY(roomY, 3);
		World.spawnObject(new GameObject(trapIds[0], ObjectType.SCENERY_INTERACT, 1, Tile.of(x, y, player.getPlane())));
		if (trapIds.length > 1)
			World.spawnObject(new GameObject(trapIds[1], ObjectType.SCENERY_INTERACT, 0, Tile.of(x + 1, y, player.getPlane())));
		if (trapIds.length > 2)
			World.spawnObject(new GameObject(trapIds[2], ObjectType.SCENERY_INTERACT, 2, Tile.of(x, y + 1, player.getPlane())));
		if (trapIds.length > 3)
			World.spawnObject(new GameObject(trapIds[3], ObjectType.SCENERY_INTERACT, 3, Tile.of(x + 1, y + 1, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 2, Tile.of(x - 1, y + 1, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 2, Tile.of(x - 1, y, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 3, Tile.of(x, y + 2, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 3, Tile.of(x + 1, y + 2, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 1, Tile.of(x, y - 1, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 1, Tile.of(x + 1, y - 1, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 0, Tile.of(x + 2, y, player.getPlane())));
		World.spawnObject(new GameObject(13150, ObjectType.WALL_STRAIGHT, 0, Tile.of(x + 2, y + 1, player.getPlane())));
		for (Player p : getTrappedPlayers(x, y))
			p.resetWalkSteps();
	}

	public void climbLadder(Player player, GameObject object, boolean up) {
		if (object == null || instance == null)
			return;
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		if (room.plane == (up ? 2 : 0)) {
			player.sendMessage("You are on the " + (up ? "highest" : "lowest") + " possible level so you cannot add a room " + (up ? "above" : "under") + " here.");
			return;
		}
		RoomReference roomTo = getRoom(roomX, roomY, room.plane + (up ? 1 : -1));
		if (roomTo == null) {
			if (buildMode) {
				player.startConversation(new Dialogue()
					.addOptions("This "+(up ? "ladder" : "trapdoor")+" does not lead anywhere. Do you want to build a room at the " + (up ? "top" : "bottom") + "?", ops -> {
						ops.add("Yes.").addOptions("Select a room", conf -> {
							conf.add((room.getZ() == 1 && !up) ? "Oubliette" : "Throne room", () -> {
								Room r = (room.getZ() == 1 && !up) ? Room.OUTBLIETTE : Room.THRONE_ROOM;
								Builds ladderTrap = (room.getZ() == 1 && !up) ? Builds.OUB_LADDER : Builds.TRAPDOOR;
								RoomReference newRoom = new RoomReference(r, room.getX(), room.getY(), room.getZ() + (up ? 1 : -1), room.getRotation());
								int slot = room.getLadderTrapSlot();
								if (slot != -1) {
									newRoom.addObject(ladderTrap, slot);
									player.getHouse().createRoom(newRoom);
								}
							});
							conf.add("Nevermind.");
						});
						ops.add("No.");
					}));
			} else
				player.sendMessage("This does not lead anywhere.");
			// start dialogue
			return;
		}
		if (roomTo.getLadderTrapSlot() == -1) {
			player.sendMessage("This does not lead anywhere.");
			return;
		}
		int xOff = 0;
		int yOff = 0;
		if (roomTo.getRotation() == 0) {
			yOff = 6;
			xOff = 2;
		} else if (roomTo.getRotation() == 1) {
			yOff = 6;
			xOff = 5;
		} else if (roomTo.getRotation() == 2) {
			yOff = 1;
			xOff = 5;
		} else if (roomTo.getRotation() == 3) {
			yOff = 1;
			xOff = 2;
		}
		player.ladder(Tile.of(instance.getLocalX(roomTo.getX(), xOff), instance.getLocalY(roomTo.getY(), yOff), player.getPlane() + (up ? 1 : -1)));
	}

	public Tile getCenterTile(RoomReference rRef) {
		if (instance == null || rRef == null)
			return null;
		return instance.getLocalTile(rRef.x * 8 + 3, rRef.y * 8 + 3);
	}

	public int getPaymentStage() {
		return paymentStage;
	}

	public void resetPaymentStage() {
		paymentStage = 0;
	}

	public void incrementPaymentStage() {
		paymentStage++;
	}

	public void climbStaircase(Player player, GameObject object, boolean up) {
		if (object == null || instance == null)
			return;
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		if (room.plane == (up ? 2 : 0)) {
			player.sendMessage("You are on the " + (up ? "highest" : "lowest") + " possible level so you cannot add a room " + (up ? "above" : "under") + " here.");
			return;
		}
		RoomReference roomTo = getRoom(roomX, roomY, room.plane + (up ? 1 : -1));
		if (roomTo == null) {
			if (buildMode) {
				player.startConversation(new Dialogue()
					.addOptions("These stairs do not lead anywhere. Do you want to build a room at the " + (up ? "top" : "bottom") + "?", ops -> {
						ops.add("Yes.").addOptions("Select a room", conf -> {
							conf.add("Skill hall", () -> {
								RoomReference newRoom = new RoomReference(up ? Room.HALL_SKILL_DOWN : Room.HALL_SKILL, room.getX(), room.getY(), room.getZ() + (up ? 1 : -1), room.getRotation());
								int slot = room.getStaircaseSlot();
								if (slot != -1) {
									newRoom.addObject(up ? Builds.STAIRCASE_DOWN : Builds.STAIRCASE, slot);
									player.getHouse().createRoom(newRoom);
								}
							});
							conf.add("Quest hall", () -> {
								RoomReference newRoom = new RoomReference(up ? Room.HALL_QUEST_DOWN : Room.HALL_QUEST, room.getX(), room.getY(), room.getZ() + (up ? 1 : -1), room.getRotation());
								int slot = room.getStaircaseSlot();
								if (slot != -1) {
									newRoom.addObject(up ? Builds.STAIRCASE_DOWN_1 : Builds.STAIRCASE_1, slot);
									player.getHouse().createRoom(newRoom);
								}
							});
							if (room.getZ() == 1 && !up)
								conf.add("Dungeon stairs room", () -> {
									RoomReference newRoom = new RoomReference(Room.DUNGEON_STAIRS, room.getX(), room.getY(), room.getZ() + (up ? 1 : -1), room.getRotation());
									int slot = room.getStaircaseSlot();
									if (slot != -1) {
										newRoom.addObject(Builds.STAIRCASE_2, slot);
										player.getHouse().createRoom(newRoom);
									}
								});
						});
						ops.add("No.");
					}));
			} else
				player.sendMessage("These stairs do not lead anywhere.");
			// start dialogue
			return;
		}
		if (roomTo.getStaircaseSlot() == -1) {
			player.sendMessage("These stairs do not lead anywhere.");
			return;
		}
		player.useStairs(-1, Tile.of(player.getX(), player.getY(), player.getPlane() + (up ? 1 : -1)), 0, 1);

	}

	public void removeRoom(RoomReference room) {
		if (roomsR.remove(room)) {
			refreshNumberOfRooms();
			refreshHouse();
		}
	}

	public void createRoom(int slot) {
		Room[] rooms = HouseConstants.Room.values();
		if (slot >= rooms.length)
			return;
		int[] position = player.getTempAttribs().getO("CreationRoom");
		player.closeInterfaces();
		if (position == null)
			return;
		Room room = rooms[slot];
		if ((room == Room.TREASURE_ROOM || room == Room.DUNGEON_CORRIDOR || room == Room.DUNGEON_JUNCTION || room == Room.DUNGEON_PIT || room == Room.DUNGEON_STAIRS) && position[2] != 0) {
			player.sendMessage("That room can only be built underground.");
			return;
		}
		if (room == Room.THRONE_ROOM)
			if (position[2] != 1) {
				player.sendMessage("This room cannot be built on a second level or underground.");
				return;
			}
		if (room == Room.OUTBLIETTE) {
			player.sendMessage("That room can only be built using a throne room trapdoor.");
			return;
		}
		if ((room == Room.GARDEN || room == Room.FORMAL_GARDEN || room == Room.MENAGERIE) && position[2] != 1) {
			player.sendMessage("That room can only be built on ground.");
			return;
		}
		if (room == Room.MENAGERIE && hasRoom(Room.MENAGERIE)) {
			player.sendMessage("You can only build one menagerie.");
			return;
		}
		if (room == Room.GAMES_ROOM && hasRoom(Room.GAMES_ROOM)) {
			player.sendMessage("You can only build one game room.");
			return;
		}
		if (room.getLevel() > player.getSkills().getLevel(Constants.CONSTRUCTION)) {
			player.sendMessage("You need a Construction level of " + room.getLevel() + " to build this room.");
			return;
		}
		if (player.getInventory().getCoins() < room.getPrice()) {
			player.sendMessage("You don't have enough coins to build this room.");
			return;
		}
		if (roomsR.size() >= getMaxQuantityRooms()) {
			player.sendMessage("You have reached the maxium quantity of rooms.");
			return;
		}

		final RoomReference roomRef = new RoomReference(room, position[0], position[1], position[2], 0);
		player.setFinishConversationEvent(() -> player.getHouse().previewRoom(roomRef, true));
		player.getHouse().previewRoom(roomRef, true);
		roomRef.setRotation((roomRef.getRotation() + 1) & 0x3);
		player.getHouse().previewRoom(roomRef, false);
		player.startConversation(new Conversation(player) {
			{
				addOptions("start", "What would you like to do?", ops -> {
					ops.add("Rotate clockwise", new Dialogue().addGotoStage("start", this).setFunc(() -> {
						player.getHouse().previewRoom(roomRef, true);
						roomRef.setRotation((roomRef.getRotation() + 1) & 0x3);
						player.getHouse().previewRoom(roomRef, false);
					}));
					ops.add("Rotate anticlockwise.", new Dialogue().addGotoStage("start", this).setFunc(() -> {
						player.getHouse().previewRoom(roomRef, true);
						roomRef.setRotation((roomRef.getRotation() - 1) & 0x3);
						player.getHouse().previewRoom(roomRef, false);
					}));
					ops.add("Build.", () -> player.getHouse().createRoom(roomRef));
					ops.empty("Cancel");
				});
			}
		});
	}

	public boolean hasRoom(Room room) {
		for (RoomReference r : roomsR)
			if (r.room == room)
				return true;
		return false;
	}

	private int getMaxQuantityRooms() {
		int consLvl = player.getSkills().getLevelForXp(Constants.CONSTRUCTION);
		int maxRoom = 40;
		if (consLvl >= 38) {
			maxRoom += (consLvl - 32) / 6;
			if (consLvl == 99)
				maxRoom++;
		}
		return maxRoom;
	}

	public void createRoom(RoomReference room) {
		if (!player.getInventory().hasCoins(room.room.getPrice())) {
			player.sendMessage("You don't have enough coins to build this room.");
			return;
		}
		player.getInventory().removeCoins(room.room.getPrice());
		player.getTempAttribs().setO("CRef", room);
		roomsR.add(room);
		refreshNumberOfRooms();
		refreshHouse();
		player.setCloseChatboxInterfaceEvent(null);
		player.setCloseInterfacesEvent(null);
	}

	public void openBuildInterface(GameObject object, final Builds build) {
		if (!buildMode) {
			player.simpleDialogue("You can only do that in building mode.");
			return;
		}
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		Item[] itemArray = new Item[build.getPieces().length];
		int requirementsValue = 0;
		for (int index = 0; index < build.getPieces().length; index++) {
			if ((build == Builds.PORTALS1 || build == Builds.PORTALS2 || build == Builds.PORTALS3) && index > 2)
				continue;
			HObject piece = build.getPieces()[index];
			itemArray[index] = new Item(piece.getItemId(), 1);
			if (hasRequirimentsToBuild(false, build, piece))
				requirementsValue += Math.pow(2, index + 1);
		}

		final int reqVal = requirementsValue;

		Dialogue buildD = new Dialogue()
			.addNext(new Statement() {
				@Override
				public void send(Player player) {
					player.getPackets().sendVarc(841, reqVal);
					player.getPackets().sendItems(398, itemArray);
					player.getPackets().setIFEvents(new IFEvents(1306, 55, -1, -1).enableContinueButton()); // exit
					// button
					for (int i = 0; i < itemArray.length; i++)
						player.getPackets().setIFEvents(new IFEvents(1306, 8 + 7 * i, 4, 4).enableContinueButton());
					// options
					player.getInterfaceManager().sendInterface(1306);
					player.getTempAttribs().setO("OpenedBuild", build);
					player.getTempAttribs().setO("OpenedBuildObject", object);
				}

				@Override
				public int getOptionId(int componentId) {
					return componentId == 55 ? Integer.MAX_VALUE : (componentId - 8) / 7;
				}

				@Override
				public void close(Player player) {
					player.closeInterfaces();
				}
			});

		for (int i = 0;i < itemArray.length;i++) {
			final int index = i;
			buildD.addNext(() -> player.getHouse().build(index));
		}
		player.startConversation(buildD);
	}

	private boolean hasRequirimentsToBuild(boolean warn, Builds build, HObject piece) {
		int level = player.getSkills().getLevel(Constants.CONSTRUCTION);
		if (!build.isWater() && player.getInventory().containsOneItem(9625))
			level += 3;
		if (level < piece.getLevel()) {
			if (warn)
				player.sendMessage("Your construction level is too low.");
			return false;
		}
		if (!player.hasRights(Rights.ADMIN)) {
			if (!player.getInventory().containsItems(piece.getRequirements(player))) {
				if (warn)
					player.sendMessage("You dont have the right materials.");
				return false;
			}
			if (build.isWater() ? !hasWaterCan() : (!player.getInventory().containsItem(HouseConstants.HAMMER, 1) || (!player.getInventory().containsItem(HouseConstants.SAW, 1) && !player.getInventory().containsOneItem(9625)))) {
				if (warn)
					player.sendMessage(build.isWater() ? "You will need a watering can with some water in it instead of hammer and saw to build plants." : "You will need a hammer and saw to build furniture.");
				return false;
			}
		}
		return true;
	}

	public void build(int slot) {
		final Builds build = player.getTempAttribs().getO("OpenedBuild");
		GameObject object = player.getTempAttribs().getO("OpenedBuildObject");
		if (build == null || object == null || build.getPieces().length <= slot)
			return;
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		final RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		final HObject piece = build.getPieces()[slot];
		if (!hasRequirimentsToBuild(true, build, piece))
			return;
		final ObjectReference oref = room.addObject(build, slot);
		player.closeInterfaces();
		player.lock();
		player.setNextAnimation(new Animation(build.isWater() ? 2293 : 3683));
		if (!player.hasRights(Rights.ADMIN))
			for (Item item : piece.getRequirements(player))
				player.getInventory().deleteItem(item);
		player.getTempAttribs().removeO("OpenedBuild");
		player.getTempAttribs().removeO("OpenedBuildObject");
		WorldTasks.schedule(new WorldTask() {
			@Override
			public void run() {
				player.getSkills().addXp(Constants.CONSTRUCTION, piece.getXP());
				if (build.isWater())
					player.getSkills().addXp(Constants.FARMING, piece.getXP());
				refreshObject(room, oref, false);
				player.unlock();
			}
		}, 0);
	}

	private void refreshObject(RoomReference rref, ObjectReference oref, boolean remove) {
		Chunk chunk = ChunkManager.getChunk(this.instance.getChunkId(rref.x, rref.y, rref.plane));
		for (int x = 0; x < 8; x++)
			for (int y = 0; y < 8; y++) {
				GameObject[] objects = chunk.getBaseObjects(Tile.of(chunk.getBaseX()+x, chunk.getBaseY()+y, rref.plane));
				if (objects != null)
					for (GameObject object : objects) {
						if (object == null)
							continue;
						int slot = oref.build.getIdSlot(object.getId());
						if (slot == -1)
							continue;
						if (remove)
							World.spawnObject(object);
						else {
							GameObject objectR = new GameObject(object);
							if (oref.getId(slot) == -1)
								World.spawnObject(new GameObject(-1, object.getType(), object.getRotation(), object.getTile()));
							else {
								objectR.setId(oref.getId(slot));
								World.spawnObject(objectR);
							}
						}
					}
			}
	}

	public boolean hasWaterCan() {
		for (int id = 5333; id <= 5340; id++)
			if (player.getInventory().containsOneItem(id))
				return true;
		return false;
	}

	public void setServantOrdinal(byte ordinal) {
		if (ordinal == -1) {
			removeServant();
			servant = null;
			refreshServantVarBit();
			return;
		}
		refreshServantVarBit();
		servant = HouseConstants.Servant.values()[ordinal];
	}

	public boolean hasServant() {
		return servant != null;
	}

	public void refreshServantVarBit() {
		int bit = servant == null ? 0 : ((servant.ordinal()*2)+1);
		if (servant != null && servant == Servant.DEMON_BUTLER)
			bit = 8;
		player.getVars().setVarBit(2190, bit);
	}

	public void openRemoveBuild(GameObject object) {
		if (!buildMode) {
			player.simpleDialogue("You can only do that in building mode.");
			return;
		}
		if (object.getId() == HouseConstants.HObject.EXIT_PORTAL.getId() && getPortalCount() <= 1) {
			player.simpleDialogue("Your house must have at least one exit portal.");
			return;
		}
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		ObjectReference ref = room.getObject(object);
		if (ref != null) {
			if (ref.build.toString().contains("STAIRCASE")) {
				if (object.getPlane() != 1) {
					RoomReference above = getRoom(roomX, roomY, 2);
					RoomReference below = getRoom(roomX, roomY, 0);
					if ((above != null && above.getStaircaseSlot() != -1) || (below != null && below.getStaircaseSlot() != -1))
						player.simpleDialogue("You cannot remove a building that is supporting this room.");
					return;
				}
			}
			player.sendOptionDialogue("Really remove it?", ops -> {
				ops.add("Yes.", () -> player.getHouse().removeBuild(object));
				ops.add("No.");
			});
		}
	}

	public void removeBuild(final GameObject object) {
		if (!buildMode) { // imagine u use settings to change while dialogue
			// open, cheater :p
			player.simpleDialogue("You can only do that in building mode.");
			return;
		}
		int roomX = object.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = object.getTile().getChunkY() - instance.getBaseChunkY();
		final RoomReference room = getRoom(roomX, roomY, object.getPlane());
		if (room == null)
			return;
		final ObjectReference oref = room.removeObject(object);
		if (oref == null)
			return;
		player.lock();
		player.setNextAnimation(new Animation(3685));
		WorldTasks.schedule(new WorldTask() {
			@Override
			public void run() {
				World.removeObject(object);
				refreshObject(room, oref, true);
				player.unlock();
			}
		});
	}

	public boolean isDoor(GameObject object) {
		return object.getDefinitions().getName().equalsIgnoreCase("Door hotspot");
	}

	public boolean isBuildMode() {
		return buildMode;
	}

	public boolean isDoorSpace(GameObject object) {
		return object.getDefinitions().getName().equalsIgnoreCase("Door space");
	}

	public void switchLock(Player player) {
		if (!isOwner(player)) {
			player.sendMessage("You can only lock your own house.");
			return;
		}
		locked = !locked;
		if (locked)
			player.simpleDialogue("Your house is now locked to visitors.");
		else if (buildMode)
			player.simpleDialogue("Visitors will be able to enter your house once you leave building mode.");
		else
			player.simpleDialogue("You have unlocked your house.");
	}

	public static void enterHouse(Player player, String username) {
		Player owner = World.getPlayerByDisplay(username); //TODO
		if (owner == null || !owner.isRunning() /*|| !player.getFriendsIgnores().onlineTo(owner)*/ || owner.getHouse() == null || owner.getHouse().locked) {
			player.sendMessage("That player is offline, or has privacy mode enabled.");
			return;
		}
		if (owner.getHouse().location == null || !player.withinDistance(owner.getHouse().location.getTile(), 16)) {
			player.sendMessage("That player's house is at " + Utils.formatPlayerNameForDisplay(owner.getHouse().location.name()).replace("Portal", "") + ".");
			return;
		}
		owner.getHouse().joinHouse(player);
	}

	public boolean joinHouse(final Player player) {
		if (!isOwner(player)) { // not owner
			if (!isOwnerInside() || !loaded) {
				player.sendMessage("That player is offline, or has privacy mode enabled.");
				return false;
			}
			if (buildMode) {
				player.sendMessage("The owner currently has build mode turned on.");
				return false;
			}
		}
		players.add(player);
		sendStartInterface(player);
		player.getControllerManager().startController(new HouseController(this));
		if (loaded) {
			teleportPlayer(player);
			WorldTasks.schedule(new WorldTask() {
				@Override
				public void run() {
					player.lock(1);
					player.getInterfaceManager().setDefaultTopInterface();
				}
			}, 4);
		} else {
			createHouse();
		}
		teleportServant();
		return true;
	}

	public static void leaveHouse(Player player) {
		Controller controller = player.getControllerManager().getController();
		if (controller == null || !(controller instanceof HouseController)) {
			player.sendMessage("You're not in a house.");
			return;
		}
		player.setCanPvp(false);
		player.removeHouseOnlyItems();
		player.lock(2);
		((HouseController) controller).getHouse().leaveHouse(player, KICKED);
	}

	/*
	 * 0 - logout, 1 kicked/tele outside outside, 2 tele somewhere else
	 */
	public void leaveHouse(Player player, int type) {
		player.setCanPvp(false);
		player.removeHouseOnlyItems();
		player.getControllerManager().removeControllerWithoutCheck();
		if (type == LOGGED_OUT)
			player.setTile(location.getTile());
		else if (type == KICKED)
			player.useStairs(-1, location.getTile(), 0, 1);
		if (players != null && players.contains(player))
			players.remove(player);
		if (players == null || players.size() == 0)
			destroyHouse();
		if (type != LOGGED_OUT)
			player.lock(2);
		if (player.getAppearance().getRenderEmote() != -1)
			player.getAppearance().setBAS(-1);
		if (isOwner(player) && servantInstance != null)
			servantInstance.setFollowing(false);
		player.getTempAttribs().setB("inBoxingArena", false);
		player.setCanPvp(false);
		player.setForceMultiArea(false);
	}

	private void removeServant() {
		if (servantInstance != null) {
			servantInstance.finish();
			servantInstance = null;
		}
	}

	private void addServant() {
		if (servantInstance == null && servant != null) {
			servantInstance = new ServantNPC(this);
		}
	}

	public Servant getServant() {
		return servant;
	}

	private void refreshServant() {
		removeServant();
		addServant();
	}

	public void callServant(boolean bellPull) {
		if (bellPull) {
			player.setNextAnimation(new Animation(3668));
			player.lock(2);
		}
		if (servantInstance == null)
			player.sendMessage("The house has no servant.");
		else {
			servantInstance.setFollowing(true);
			servantInstance.setNextTile(World.getFreeTile(player.getTile(), 1));
			servantInstance.setNextAnimation(new Animation(858));
			player.startConversation(new ServantHouseD(player, servantInstance, true));
		}
	}

	public ServantNPC getServantInstance() {
		return servantInstance;
	}

	/*
	 * refers to logout
	 */
	public void finish() {
		kickGuests();
		// no need to leave house for owner, controller does that itself
	}

	public void refreshHouse() {
		destroyHouse();
		loaded = false;
		sendStartInterface(player);
		createHouse();
	}

	public boolean isLoaded() {
		return loaded;
	}

	public void sendStartInterface(Player player) {
		player.lock();
		player.getInterfaceManager().setTopInterface(399, false);
		player.getMusicsManager().playSongAndUnlock(454);
		player.jingle(22);
	}

	public void teleportPlayer(Player player) {
		teleportPlayer(player, getPortalRoom());
	}

	public void teleportServant() {
		teleportServant(getPortalRoom());
	}

	public void teleportServant(RoomReference room) {
		if (room == null)
			return;

		servantInstance.resetWalkSteps();
		byte rotation = room.rotation;
		if (rotation == 0) {
			instance.teleportChunkLocal(servantInstance, room.x, 3, room.y - 1, 3, room.plane);
		} else {
			instance.teleportChunkLocal(servantInstance, room.x, 3, room.y, 3 + 1, room.plane);
		}
	}

	public void teleportPlayer(Player player, RoomReference room) {
		if (room == null)
			player.sendMessage("Error, tried teleporting to room that doesn't exist.");
		else {
			byte rotation = room.rotation;
			if (rotation == 0) {
				instance.teleportChunkLocal(player, room.x, 3, room.y - 1, 3, room.plane);
			} else {
				instance.teleportChunkLocal(player, room.x, 3, room.y, 3 + 1, room.plane);
			}
		}
	}

	public int getPortalCount() {
		int count = 0;
		for (RoomReference room : roomsR)
			if (room.room == HouseConstants.Room.GARDEN || room.room == HouseConstants.Room.FORMAL_GARDEN)
				for (ObjectReference o : room.objects)
					if (o.getPiece() == HouseConstants.HObject.EXIT_PORTAL || o.getPiece() == HouseConstants.HObject.EXITPORTAL)
						count++;
		return count;
	}

	public RoomReference getMenagerie() {
		for (RoomReference room : roomsR)
			if (room.room == HouseConstants.Room.MENAGERIE)
				for (ObjectReference o : room.objects)
					if (o.getPiece() == HouseConstants.HObject.OAKPETHOUSE || o.getPiece() == HouseConstants.HObject.TEAKPETHOUSE || o.getPiece() == HouseConstants.HObject.MAHOGANYPETHOUSE || o.getPiece() == HouseConstants.HObject.CONSECRATEDPETHOUSE || o.getPiece() == HouseConstants.HObject.DESECRATEDPETHOUSE || o.getPiece() == HouseConstants.HObject.NATURALPETHOUSE)
						return room;
		return null;
	}

	public RoomReference getPortalRoom() {
		for (RoomReference room : roomsR)
			if (room.room == HouseConstants.Room.GARDEN || room.room == HouseConstants.Room.FORMAL_GARDEN)
				for (ObjectReference o : room.objects)
					if (o.getPiece() == HouseConstants.HObject.EXIT_PORTAL || o.getPiece() == HouseConstants.HObject.EXITPORTAL)
						return room;
		return null;
	}

	public House() {
		buildMode = true;
		petHouse = new PetHouse();
		roomsR = new ArrayList<>();
		location = POHLocation.TAVERLY;
		addRoom(HouseConstants.Room.GARDEN, 3, 3, 0, 0);
		getRoom(3, 3, 0).addObject(Builds.CENTREPIECE, 0);
	}

	public boolean addRoom(HouseConstants.Room room, int x, int y, int plane, int rotation) {
		return roomsR.add(new RoomReference(room, x, y, plane, rotation));
	}

	/*
	 * temporary
	 */
	public void reset() {
		build = 1;
		buildMode = true;
		roomsR = new ArrayList<>();
		addRoom(HouseConstants.Room.GARDEN, 3, 3, 1, 0);
		getRoom(3, 3, 1).addObject(Builds.CENTREPIECE, 0);
	}

	public void init() {
		if (build == 0)
			reset();
		players = new ArrayList<>();
		refreshBuildMode();
		refreshArriveInPortal();
		refreshNumberOfRooms();
	}

	public void refreshNumberOfRooms() {
		player.getPackets().sendVarc(944, roomsR.size());
	}

	public void setArriveInPortal(boolean arriveInPortal) {
		this.arriveInPortal = arriveInPortal;
		refreshArriveInPortal();
	}

	public boolean arriveOutsideHouse() {
		return arriveInPortal;
	}

	public void refreshArriveInPortal() {
		player.getVars().setVarBit(6450, arriveInPortal ? 1 : 0);
	}

	public void toggleChallengeMode(Player player) {
		if (isOwner(player)) {
			if (!challengeMode)
				setChallengeMode(true);
			else
				setChallengeMode(false);
		} else
			player.sendMessage("Only the house owner can toggle challenge mode on or off.");

	}

	public void setBuildMode(boolean buildMode) {
		if (this.buildMode == buildMode)
			return;
		this.buildMode = buildMode;
		if (loaded) {
			expelGuests();
			if (isOwnerInside())
				refreshHouse();
		}
		refreshBuildMode();
	}

	public void refreshBuildMode() {
		player.getVars().setVarBit(2176, buildMode ? 1 : 0);
	}

	public RoomReference getRoom(int x, int y, int plane) {
		for (RoomReference room : roomsR)
			if (room.x == x && room.y == y && room.plane == plane)
				return room;
		return null;
	}

	public RoomReference getRoom(GameObject o) {
		int roomX = o.getTile().getChunkX() - instance.getBaseChunkX();
		int roomY = o.getTile().getChunkY() - instance.getBaseChunkY();
		return getRoom(roomX, roomY, o.getPlane());
	}

	public List<RoomReference> getRooms() {
		return roomsR;
	}

	public RoomReference getRoom(Room room) {
		for (RoomReference roomR : roomsR)
			if (room == roomR.getRoom())
				return roomR;
		return null;
	}

	public boolean isSky(int x, int y, int plane) {
		return buildMode && plane == 2 && getRoom((x / 8) - instance.getBaseChunkX(), (y / 8) - instance.getBaseChunkY(), plane) == null;
	}

	public void previewRoom(RoomReference reference, boolean remove) {
		if (!loaded) {
			Logger.debug(House.class, "previewRoom", "Preview cancelled.");
			return;
		}
		int boundX = instance.getLocalX(reference.x, 0);
		int boundY = instance.getLocalY(reference.y, 0);
		int realChunkX = reference.room.getChunkX();
		int realChunkY = reference.room.getChunkY();
		Chunk chunk = ChunkManager.getChunk(MapUtils.encode(Structure.CHUNK, reference.room.getChunkX(), reference.room.getChunkY(), look & 0x3), true);
		if (reference.plane == 0)
			for (int x = 0; x < 8; x++)
				for (int y = 0; y < 8; y++) {
					GameObject objectR = new GameObject(-1, ObjectType.SCENERY_INTERACT, reference.rotation, boundX + x, boundY + y, reference.plane);
					if (remove)
						World.removeObject(objectR);
					else
						World.spawnObject(objectR);
				}
		for (int x = 0; x < 8; x++)
			for (int y = 0; y < 8; y++) {
				GameObject[] objects = chunk.getBaseObjects(Tile.of((realChunkX << 3) + x, (realChunkY << 3) + y, look & 0x3));
				if (objects != null)
					for (GameObject object : objects) {
						if (object == null)
							continue;
						ObjectDefinitions defs = object.getDefinitions();
						if (reference.plane == 0 || defs.containsOption(4, "Build")) {
							GameObject objectR = new GameObject(object);
							int[] coords = InstancedChunk.transform(x, y, reference.rotation, defs.sizeX, defs.sizeY, object.getRotation());
							objectR.setTile(Tile.of(boundX + coords[0], boundY + coords[1], reference.plane));
							objectR.setRotation((object.getRotation() + reference.rotation) & 0x3);
							// just a preview. they're not realy there.
							if (remove)
								World.removeObject(objectR);
							else
								World.spawnObject(objectR);
						}
					}
			}
	}

	public void destroyHouse() {
		loaded = false;
		if (pets == null)
			pets = new CopyOnWriteArrayList<>();
		if (npcs == null)
			npcs = new CopyOnWriteArrayList<>();
		for (NPC npc : pets)
			if (npc != null) {
				npc.finish();
				pets.remove(npc);
			}
		for (NPC npc : npcs)
			if (npc != null) {
				npc.finish();
				npcs.remove(npc);
			}
		removeServant();
		npcs.clear();
		pets.clear();
		if (instance != null)
			instance.destroy();
	}

	private static final int[] DOOR_DIR_X = { -1, 0, 1, 1 };
	private static final int[] DOOR_DIR_Y = { 0, 1, 0, -1 };

	public void createHouse() {
		challengeMode = false;
		Object[][][][] data = new Object[4][8][8][];
		// sets rooms data
		for (RoomReference reference : roomsR)
			data[reference.plane][reference.x][reference.y] = new Object[] { reference.room.getChunkX(), reference.room.getChunkY(), reference.rotation, reference.room.isShowRoof() };
		// sets roof data
		if (!buildMode)
			for (int x = 1; x < 7; x++)
				skipY: for (int y = 1; y < 7; y++)
					for (int plane = 2; plane >= 1; plane--)
						if (data[plane][x][y] != null) {
							boolean hasRoof = (boolean) data[plane][x][y][3];
							if (hasRoof) {
								byte rotation = (byte) data[plane][x][y][2];
								// TODO find best Roof
								data[plane + 1][x][y] = new Object[] { HouseConstants.Roof.ROOF1.getChunkX(), HouseConstants.Roof.ROOF1.getChunkY(), rotation, true };
								continue skipY;
							}
						}
		if (instance != null && !instance.isDestroyed())
			instance.destroy();
		instance = Instance.of(getLocation().getTile(), 8, 8);
		instance.requestChunkBound().thenAccept(e -> {
			// builds data
			List<CompletableFuture<Boolean>> regionBuilding = new ObjectArrayList<>();
			for (int plane = 0; plane < data.length; plane++) {
				for (int x = 0; x < data[plane].length; x++) {
					for (int y = 0; y < data[plane][x].length; y++) {
						if (data[plane][x][y] != null)
							regionBuilding.add(instance.copyChunk(x, y, plane, (int) data[plane][x][y][0] + (look >= 4 ? 8 : 0), (int) data[plane][x][y][1], look & 0x3, (byte) data[plane][x][y][2]));
						else if ((x == 0 || x == 7 || y == 0 || y == 7) && plane == 1)
							regionBuilding.add(instance.copyChunk(x, y, plane, HouseConstants.BLACK[0], HouseConstants.BLACK[1], 0, 0));
						else if (plane == 1)
							regionBuilding.add(instance.copyChunk(x, y, plane, HouseConstants.LAND[0] + (look >= 4 ? 8 : 0), HouseConstants.LAND[1], look & 0x3, 0));
						else if (plane == 0)
							regionBuilding.add(instance.copyChunk(x, y, plane, HouseConstants.BLACK[0], HouseConstants.BLACK[1], 0, 0));
						else
							regionBuilding.add(instance.clearChunk(x, y, plane));
					}
				}
			}
			regionBuilding.forEach(CompletableFuture::join);
			for (int chunkId : this.instance.getChunkIds()) {
				Chunk chunk = ChunkManager.getChunk(chunkId, true);
				for (GameObject object : chunk.getSpawnedObjects())
					chunk.removeObject(object);
			}
			for (int chunkId : this.instance.getChunkIds()) {
				Chunk chunk = ChunkManager.getChunk(chunkId, true);
				for (GameObject object : chunk.getRemovedObjects().values())
					chunk.removeObject(object);
			}
			for (RoomReference reference : roomsR) {
				for (int x = 0; x < 8; x++)
					for (int y = 0; y < 8; y++) {
						GameObject[] objects = ChunkManager.getChunk(instance.getChunkId(reference.x, reference.y, reference.plane)).getBaseObjects(x, y);
						if (objects != null)
							skip: for (GameObject object : objects) {
								if (object == null)
									continue;
								if (object.getDefinitions().containsOption(4, "Build") || (reference.room == Room.MENAGERIE && object.getDefinitions().getName().contains("space"))) {
									if (isDoor(object)) {
										if (!buildMode && object.getPlane() == 2 && getRoom(((object.getX() / 8) - instance.getBaseChunkX()) + DOOR_DIR_X[object.getRotation()], ((object.getY() / 8) - instance.getBaseChunkY()) + DOOR_DIR_Y[object.getRotation()], object.getPlane()) == null) {
											GameObject objectR = new GameObject(object);
											objectR.setId(HouseConstants.WALL_IDS[look]);
											World.spawnObject(objectR);
											continue;
										}
									} else
										for (ObjectReference o : reference.objects) {
											int slot = o.build.getIdSlot(object.getId());
											if (slot != -1) {
												GameObject objectR = new GameObject(object);
												if (o.getId(slot) == -1)
													World.spawnObject(new GameObject(-1, object.getType(), object.getRotation(), object.getTile()));
												else if (!spawnNpcs(slot, o, object)) {
													objectR.setId(o.getId(slot));
													World.spawnObject(objectR);
												}
												continue skip;
											}
										}
									if (!buildMode)
										World.removeObject(object);
								} else if (object.getId() == HouseConstants.WINDOW_SPACE_ID) {
									object = new GameObject(object);
									object.setId(HouseConstants.WINDOW_IDS[look]);
									World.spawnObject(object);
								} else if (isDoorSpace(object))
									World.removeObject(object);
							}
					}
			}
			refreshServant();
			teleportPlayer(player);
			player.setForceNextMapLoadRefresh(true);
			player.loadMapRegions();
			player.lock(1);
			WorldTasks.schedule(3, () -> player.getInterfaceManager().setDefaultTopInterface());
			if (!buildMode)
				if (getMenagerie() != null)
					for (Item item : petHouse.getPets().array())
						if (item != null)
							addPet(item, false);
			if (player.getTempAttribs().getO("CRef") != null && player.getTempAttribs().getO("CRef") instanceof RoomReference toRoom) {
				player.getTempAttribs().removeO("CRef");
				teleportPlayer(player, toRoom);
			}
			loaded = true;
		});
	}

	public boolean containsAnyObject(int... ids) {
		for (int chunkId : this.instance.getChunkIds()) {
			Chunk chunk = ChunkManager.getChunk(chunkId, true);
			List<GameObject> spawnedObjects = chunk.getSpawnedObjects();
			for (GameObject wo : spawnedObjects)
				for (int id : ids)
					if (wo.getId() == id)
						return true;
		}
		return false;
	}

	public void removePet(Item item, boolean update) {
		if (update && !isOwnerInside())
			return;
		if (!buildMode)
			if (getMenagerie() != null) {
				Pets pet = Pets.forId(item.getId());
				if (pet == null)
					return;

				int npcId = 0;
				if (pet.getGrownItemId() == item.getId())
					npcId = pet.getGrownNpcId();
				else
					npcId = pet.getBabyNpcId();
				for (NPC npc : pets)
					if (npc != null && npc.getId() == npcId) {
						npc.finish();
						pets.remove(npc);
						break;
					}
			}
	}

	public void addPet(Item item, boolean update) {
		if (update && !isOwnerInside())
			return;
		if (!buildMode)
			if (getMenagerie() != null) {
				RoomReference men = getMenagerie();
				Tile spawn = Tile.of(instance.getLocalX(men.x, 3), instance.getLocalY(men.y, 3), men.plane);

				Pets pet = Pets.forId(item.getId());
				if (pet == null)
					return;

				NPC npc = new NPC(1, spawn);
				if (pet.getGrownItemId() == item.getId())
					npc.setNPC(pet.getGrownNpcId());
				else
					npc.setNPC(pet.getBabyNpcId());
				pets.add(npc);
				npc.setRandomWalk(true);
			}
	}

	public boolean spawnNpcs(int slot, ObjectReference oRef, GameObject object) {
		if (buildMode)
			return false;
		if (oRef.getId(slot) == HouseConstants.HObject.ROCNAR.getId() || oRef.build == Builds.PITGUARD || oRef.build == Builds.GUARDIAN || oRef.build == Builds.GUARD2 || oRef.build == Builds.GUARD3 || oRef.build == Builds.GUARD4 || oRef.build == Builds.GUARD5) {
			if (oRef.getId(slot) == HouseConstants.HObject.DEMON.getId()) {
				spawnNPC(3593, object);
				return true;
			}
			if (oRef.getId(slot) == HouseConstants.HObject.KALPHITESOLDIER.getId()) {
				spawnNPC(3589, object);
				return true;
			}
			if (oRef.getId(slot) == HouseConstants.HObject.TOKXIL.getId()) {
				spawnNPC(3592, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.DAGANNOTH.getId()) {
				spawnNPC(3591, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.STEELDRAGON.getId()) {
				spawnNPC(3590, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.SKELETON.getId()) {
				spawnNPC(3581, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.GUARDDOG.getId()) {
				spawnNPC(3582, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.HOBGOBLIN.getId()) {
				spawnNPC(3583, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.BABYREDDRAGON.getId()) {
				spawnNPC(3588, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.HUGESPIDER.getId()) {
				spawnNPC(3585, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.HELLHOUND.getId()) {
				spawnNPC(3586, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.TROLLGUARD.getId()) {
				spawnNPC(3584, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITDOG.getId()) {
				spawnNPC(11585, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITOGRE.getId()) {
				spawnNPC(11587, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITROCKPROTECTER.getId()) {
				spawnNPC(11589, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITSCABARITE.getId()) {
				spawnNPC(11591, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITBLACKDEMON.getId()) {
				spawnNPC(11593, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.PITIRONDRAGON.getId()) {
				spawnNPC(11595, object);
				return true;
			} else if (oRef.getId(slot) == HouseConstants.HObject.ROCNAR.getId()) {
				spawnNPC(3594, object);
				return true;
			}
			return false;
		}
		return false;
	}

	public void spawnNPC(int id, GameObject object) {
		NPC npc = new NPC(id, Tile.of(object.getX(), object.getY(), object.getPlane()));
		npcs.add(npc);
		npc.setRandomWalk(false);
		npc.setForceMultiArea(true);
		World.removeObject(object);
	}

	public boolean isWindow(int id) {
		return id == 13830;
	}

	public GameObject getWorldObjectForBuild(RoomReference reference, Builds build) {
		int boundX = instance.getLocalX(reference.x, 0);
		int boundY = instance.getLocalY(reference.y, 0);
		for (int x = -1; x < 8; x++)
			for (int y = -1; y < 8; y++)
				for (HObject piece : build.getPieces()) {
					GameObject object = World.getObjectWithId(Tile.of(boundX + x, boundY + y, reference.plane), piece.getId());
					if (object != null)
						return object;
				}
		return null;
	}

	public GameObject getWorldObject(RoomReference reference, int id) {
		int boundX = instance.getLocalX(reference.x, 0);
		int boundY = instance.getLocalY(reference.y, 0);
		for (int x = -1; x < 8; x++)
			for (int y = -1; y < 8; y++) {
				GameObject object = World.getObjectWithId(Tile.of(boundX + x, boundY + y, reference.plane), id);
				if (object != null)
					return object;
			}
		return null;
	}

	public static class ObjectReference {

		private int slot;
		private Builds build;

		public ObjectReference(Builds build, int slot) {
			this.build = build;
			this.slot = slot;
		}

		public HObject getPiece() {
			if (slot > build.getPieces().length - 1) {
				Logger.error(House.class, "getPiece", "Error getting peice for " + build.name());
				return build.getPieces()[0];
			}
			return build.getPieces()[slot];
		}

		public int getId() {
			if (slot > build.getPieces().length - 1) {
				Logger.error(House.class, "getId", "Error getting id for " + build.name());
				return build.getPieces()[0].getId();
			}
			return build.getPieces()[slot].getId();
		}

		public int getSlot() {
			return slot;
		}

		public void setSlot(int slot, GameObject object) {
			this.slot = slot;

			object.setId(build.getPieces()[slot].getId());
		}


		public int[] getIds() {
			if (slot > build.getPieces().length - 1) {
				Logger.error(House.class, "getIds", "Error getting ids for " + build.name());
				return build.getPieces()[0].getIds();
			}
			return build.getPieces()[slot].getIds();
		}

		public Builds getBuild() {
			return build;
		}

		public int getId(int slot2) {
			if (slot2 > getIds().length - 1) {
				Logger.error(House.class, "getId", "Error getting id2 for " + build.name());
				return getIds()[0];
			}
			return getIds()[slot2];
		}

	}

	public static class RoomReference {

		public RoomReference(HouseConstants.Room room, int x, int y, int plane, int rotation) {
			this.room = room;
			this.x = (byte) x;
			this.y = (byte) y;
			this.plane = (byte) plane;
			this.rotation = (byte) rotation;
			objects = new ArrayList<>();
		}

		public int getTrapObject() {
			for (ObjectReference object : objects)
				if (object.build.toString().contains("FLOOR"))
					return object.getPiece().getId();
			return -1;
		}

		private HouseConstants.Room room;
		private byte x, y, plane, rotation;
		private List<ObjectReference> objects;

		public int getLadderTrapSlot() {
			for (ObjectReference object : objects)
				if (object.build.toString().contains("OUB_LADDER") || object.build.toString().contains("TRAPDOOR"))
					return object.slot;
			return -1;
		}

		public int getStaircaseSlot() {
			for (ObjectReference object : objects)
				if (object.build.toString().contains("STAIRCASE"))
					return object.slot;
			return -1;
		}

		public boolean isStaircaseDown() {
			for (ObjectReference object : objects)
				if (object.build.toString().contains("STAIRCASE_DOWN"))
					return true;
			return false;
		}

		/*
		 * x,y inside the room chunk
		 */
		public ObjectReference addObject(Builds build, int slot) {
			ObjectReference ref = new ObjectReference(build, slot);
			objects.add(ref);
			return ref;
		}

		public ObjectReference getObject(GameObject object) {
			for (ObjectReference o : objects)
				for (int id : o.getIds())
					if (object.getId() == id)
						return o;
			return null;
		}

		public int getHObjectSlot(HObject hObject) {
			for (ObjectReference o : objects) {
				if (o == null)
					continue;
				if (hObject.getId() == o.getPiece().getId())
					return o.getSlot();
			}
			return -1;
		}

		public boolean containsHObject(HObject hObject) {
			return getHObjectSlot(hObject) != -1;
		}

		public boolean containsBuild(Builds build) {
			return getBuildSlot(build) != -1;
		}

		public int getBuildSlot(Builds build) {
			for (ObjectReference o : objects) {
				if (o == null)
					continue;
				if (o.getBuild() == build)
					return o.getSlot();
			}
			return -1;
		}

		public ObjectReference getBuild(Builds build) {
			for (ObjectReference o : objects) {
				if (o == null)
					continue;
				if (o.getBuild() == build)
					return o;
			}
			return null;
		}

		public ObjectReference removeObject(GameObject object) {
			ObjectReference r = getObject(object);
			if (r != null) {
				objects.remove(r);
				return r;
			}
			return null;
		}

		public void setRotation(int rotation) {
			this.rotation = (byte) rotation;
		}

		public byte getRotation() {
			return rotation;
		}

		public Room getRoom() {
			return room;
		}

		public int getZ() {
			return plane;
		}

		public int getX() {
			return x;
		}

		public int getY() {
			return y;
		}

	}

	public void changeLook(int look) {
		if (look > 6 || look < 0)
			return;
		this.look = (byte) look;
	}

	public void setPlayer(Player player) {
		this.player = player;
		if (petHouse == null)
			petHouse = new PetHouse();
		if (pets == null)
			pets = new CopyOnWriteArrayList<>();
		if (npcs == null)
			npcs = new CopyOnWriteArrayList<>();
		petHouse.setPlayer(player);
		refreshServantVarBit();
	}

	public Player getPlayer() {
		return player;
	}

	public List<Player> getPlayers() {
		return players;
	}

	public PetHouse getPetHouse() {
		return petHouse;
	}

	public void setPetHouse(PetHouse petHouse) {
		this.petHouse = petHouse;
	}

	public boolean isChallengeMode() {
		return challengeMode;
	}

	public void setChallengeMode(boolean challengeMode) {
		this.challengeMode = challengeMode;
		for (Player player : players)
			if (player != null && player.getControllerManager().getController() instanceof HouseController) {
				player.sendMessage("<col=FF0000>The owner has turned " + (challengeMode ? "on" : "off") + " PVP dungeon challenge mode.</col>");
				player.sendMessage("<col=FF0000>The dungeon is now " + (challengeMode ? "open" : "closed") + " to PVP combat.</col>");
			}
	}
}