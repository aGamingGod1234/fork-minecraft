package dev.agaminggod.arenaagents.server.perception;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

/** Exercises the production inventory tracker against mutable Minecraft inventory and menu objects. */
public final class ServerObservationInventorySnapshotVerification {
	private ServerObservationInventorySnapshotVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		try (ComponentBindings components = new ComponentBindings()) {
			return verifyMutations(components);
		}
	}

	private static int verifyMutations(ComponentBindings components) {
		Fixture fixture = fixture();
		var snapshot = ServerObservationCollector.InventorySnapshot.capture(fixture.player());
		assertFalse(snapshot.matchesAndUpdate(fixture.player()), "unchanged inventory remains stable");

		int assertions = 1;
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> fixture.inventory().setSelectedSlot(1), "selected slot");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> fixture.inventory().setItem(0, components.stack(Items.STONE, 2)), "inventory item identity");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> fixture.inventory().getItem(0).setCount(3), "inventory item count");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> fixture.inventory().setItem(0, components.stack(Items.DIRT, 3)),
				"inventory identity between registered items");

		ItemStack pickaxe = components.damageableStack(Items.IRON_PICKAXE);
		fixture.inventory().setItem(1, pickaxe);
		assertTrue(snapshot.matchesAndUpdate(fixture.player()), "new damageable stack is observed");
		assertions += 1;
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> pickaxe.setDamageValue(1), "inventory item damage");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> pickaxe.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Renamed tool")), "inventory visible component");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> fixture.equipment().set(EquipmentSlot.HEAD, components.damageableStack(Items.IRON_HELMET)), "equipment");

		TestMenu menu = new TestMenu(65);
		fixture.player().containerMenu = menu;
		assertFalse(snapshot.hasSameShape(fixture.player()), "opening a menu changes snapshot shape");
		snapshot = ServerObservationCollector.InventorySnapshot.capture(fixture.player());
		assertions += 1;
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> menu.setCarried(components.stack(Items.DIAMOND, 2)), "carried stack");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> menu.getCarried().setCount(3), "carried stack count");
		ItemStack carriedPickaxe = components.damageableStack(Items.IRON_PICKAXE);
		menu.setCarried(carriedPickaxe);
		assertTrue(snapshot.matchesAndUpdate(fixture.player()), "carried item identity is observed");
		assertions += 1;
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> carriedPickaxe.setDamageValue(2), "carried stack damage");
		assertions += verifyMutation(snapshot, fixture.player(),
				() -> menu.setSlot(0, components.stack(Items.DIRT, 4)), "bounded menu slot");

		menu.setSlot(64, components.stack(Items.GOLD_INGOT, 1));
		assertTrue(snapshot.matchesAndUpdate(fixture.player()), "menu slots beyond the old 64-slot bound are observed");
		assertions += 1;
		fixture.player().containerMenu = null;
		assertFalse(snapshot.hasSameShape(fixture.player()), "closing a menu changes snapshot shape");
		return assertions + 1;
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " server inventory snapshot assertions");
	}

	private static int verifyMutation(
			ServerObservationCollector.InventorySnapshot snapshot,
			ServerPlayer player,
			Runnable mutation,
			String label
	) {
		mutation.run();
		assertTrue(snapshot.matchesAndUpdate(player), label + " mutation is observed");
		assertFalse(snapshot.matchesAndUpdate(player), label + " is stable after the snapshot update");
		return 2;
	}

	private static Fixture fixture() {
		try {
			Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
			ServerPlayer player = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
			EntityEquipment equipment = new EntityEquipment();
			Inventory inventory = new Inventory(null, equipment);
			setField(unsafe, player, Player.class, "inventory", inventory);
			setField(unsafe, player, LivingEntity.class, "equipment", equipment);
			return new Fixture(player, inventory, equipment);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not create inventory fixture", exception);
		}
	}

	private static void setField(sun.misc.Unsafe unsafe, Object target, Class<?> owner, String name, Object value)
			throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
	}

	private record Fixture(ServerPlayer player, Inventory inventory, EntityEquipment equipment) {
	}

	private static final class ComponentBindings implements AutoCloseable {
		private final IdentityHashMap<Holder.Reference<Item>, DataComponentMap> originals = new IdentityHashMap<>();

		private ItemStack stack(Item item, int count) {
			bind(item, 64, 0);
			return new ItemStack(item, count);
		}

		private ItemStack damageableStack(Item item) {
			bind(item, 1, 250);
			return new ItemStack(item);
		}

		private void bind(Item item, int maxStackSize, int maxDamage) {
			Holder.Reference<Item> holder = item.builtInRegistryHolder();
			if (originals.containsKey(holder)) return;
			DataComponentMap original = holder.areComponentsBound() ? holder.components() : null;
			originals.put(holder, original);
			DataComponentMap.Builder components = DataComponentMap.builder();
			if (original != null) components.addAll(original);
			components.set(DataComponents.MAX_STACK_SIZE, maxStackSize);
			if (maxDamage > 0) components.set(DataComponents.MAX_DAMAGE, maxDamage);
			holder.bindComponents(components.build());
		}

		@Override
		public void close() {
			try {
				Field components = Holder.Reference.class.getDeclaredField("components");
				components.setAccessible(true);
				for (Map.Entry<Holder.Reference<Item>, DataComponentMap> entry : originals.entrySet()) {
					if (entry.getValue() == null) components.set(entry.getKey(), null);
					else entry.getKey().bindComponents(entry.getValue());
				}
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("could not restore temporary item components", exception);
			}
		}
	}

	private static final class TestMenu extends AbstractContainerMenu {
		private final SimpleContainer items;

		private TestMenu(int slotCount) {
			super(null, 1);
			items = new SimpleContainer(slotCount);
			for (int slot = 0; slot < slotCount; slot++) addSlot(new Slot(items, slot, 0, 0));
		}

		private void setSlot(int slot, ItemStack stack) {
			items.setItem(slot, stack);
		}

		@Override
		public ItemStack quickMoveStack(Player player, int slot) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}
}
