package dev.agaminggod.arenaagents.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum ActionType {
	MOVE_TO("move_to"),
	CONTROL("control"),
	CONTROL_SEQUENCE("control_sequence"),
	LOOK_AT("look_at"),
	ATTACK("attack"),
	SELECT_ITEM("select_item"),
	USE_ITEM("use_item"),
	BREAK_BLOCK("break_block"),
	PLACE_BLOCK("place_block"),
	BUILD_SEQUENCE("build_sequence"),
	CHAT("chat"),
	WAIT("wait"),
	SET_DOOR("set_door"),
	PICK_UP_ITEM("pick_up_item"),
	DROP_ITEM("drop_item"),
	NAVIGATE_TO("navigate_to"),
	FIGHT_TARGET("fight_target"),
	FLEE_FROM("flee_from"),
	FOLLOW_ENTITY("follow_entity"),
	TRANSFER_CONTAINER("transfer_container"),
	CRAFT_INVENTORY("craft_inventory"),
	CRAFT_TABLE("craft_table"),
	FURNACE_TRANSACTION("furnace_transaction"),
	EQUIP_ITEM("equip_item"),
	SELECT_TOOL("select_tool"),
	BLOCK_WITH_SHIELD("block_with_shield"),
	USE_RANGED("use_ranged"),
	INTERACT_BLOCK("interact_block"),
	INTERACT_ENTITY("interact_entity"),
	DISMOUNT("dismount"),
	START_FALL_FLYING("start_fall_flying"),
	WAKE_UP("wake_up"),
	SET_FLIGHT("set_flight"),
	WRITE_SIGN("write_sign"),
	EDIT_BOOK("edit_book"),
	MENU_CLICK("menu_click"),
	MENU_CLOSE("menu_close"),
	BEACON_EFFECTS("beacon_effects"),
	MENU_TRANSFER("menu_transfer"),
	MENU_BUTTON("menu_button"),
	ANVIL_RENAME("anvil_rename"),
	RESPAWN("respawn"),
	COMPLETE_GOAL("complete_goal");

	private static final Map<String, ActionType> BY_WIRE_NAME = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(ActionType::wireName, Function.identity()));

	private final String wireName;

	ActionType(String wireName) {
		this.wireName = wireName;
	}

	public String wireName() {
		return wireName;
	}

	public static Optional<ActionType> fromWireName(String wireName) {
		if (wireName == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(BY_WIRE_NAME.get(wireName));
	}
}
