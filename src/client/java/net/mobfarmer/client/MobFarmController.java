package net.mobfarmer.client;

import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class MobFarmController {
	private MobFarmController() {}

	private static final double ITEM_SNAPSHOT_RADIUS = 5.0;
	private static final double ITEM_DROP_DETECT_RADIUS = 8.0;
	/** Used when {@link ClientLevel#getEntity(java.util.UUID)} is absent (early 1.21.x). */
	private static final double ENTITY_BY_UUID_RADIUS = 256.0;

	private static EntityType<?> huntType;
	private static UUID targetId;
	private static Boolean allowBreakBefore;
	private static Vec3 lastMobPos;
	private static Set<UUID> lastItemUuidsNearMob = Collections.emptySet();
	private static final Set<UUID> pendingItemPickups = new LinkedHashSet<>();
	private static Integer followRadiusBeforeLoot;
	private static Double followOffsetDistanceBeforeLoot;

	public static void start(Player player, String mobArg, Consumer<String> onMessage) {
		abortSessionSilently();
		var type = resolveEntityType(mobArg);
		if (type == null) {
			onMessage.accept("Unknown mob: " + mobArg + " (try minecraft:zombie, creeper, ...).");
			return;
		}
		LivingEntity nearest = findNearest(player, type, null);
		if (nearest == null) {
			onMessage.accept("No " + type.getDescription().getString() + " found nearby.");
			return;
		}
		allowBreakBefore = BaritoneAPI.getSettings().allowBreak.value;
		BaritoneAPI.getSettings().allowBreak.value = false;

		huntType = type;
		targetId = nearest.getUUID();
		lastMobPos = nearest.position();
		lastItemUuidsNearMob = Collections.emptySet();
		refreshBaritoneFollow();
		onMessage.accept("Farming " + type.getDescription().getString() + " — use /farmer stop to cancel.");
	}

	public static void stop(Consumer<String> onMessage) {
		boolean hadSession = huntType != null;
		abortSessionSilently();
		if (hadSession) {
			onMessage.accept("Mob farming stopped.");
		}
	}

	private static void abortSessionSilently() {
		restoreBaritoneFollowTweaks();
		restoreBaritoneBreakSetting();
		huntType = null;
		targetId = null;
		lastMobPos = null;
		lastItemUuidsNearMob = Collections.emptySet();
		pendingItemPickups.clear();
		try {
			BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().cancel();
		} catch (Throwable ignored) {
		}
	}

	public static void tick(Minecraft mc) {
		if (huntType == null || mc.player == null || mc.level == null) {
			return;
		}
		Player player = mc.player;
		ClientLevel level = mc.level;
		prunePendingPickups(level, player.position());

		if (!pendingItemPickups.isEmpty()) {
			refreshBaritoneFollow();
			return;
		}

		LivingEntity living = resolveHuntTarget(mc, player);
		if (!pendingItemPickups.isEmpty()) {
			refreshBaritoneFollow();
			return;
		}
		if (living == null) {
			refreshBaritoneFollow();
			return;
		}

		lastMobPos = living.position();
		lastItemUuidsNearMob = snapshotItemUuids(level, lastMobPos, ITEM_SNAPSHOT_RADIUS);

		refreshBaritoneFollow();

		double reach = player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		if (player.distanceTo(living) <= reach + 0.25) {
			int slot = bestWeaponHotbarSlot(player);
			if (slot >= 0 && slot != inventorySelectedSlot(player)) {
				inventorySetSelectedSlot(player, slot);
			}
			player.lookAt(Anchor.EYES, living.getEyePosition(1f));
			if (mc.gameMode != null && player.getAttackStrengthScale(0f) >= 0.92f) {
				mc.gameMode.attack(player, living);
				player.swing(InteractionHand.MAIN_HAND);
			}
		}
	}

	private static void registerDropsFromKill(ClientLevel level, Vec3 deathPos, Set<UUID> itemsBefore) {
		if (deathPos == null || itemsBefore == null) {
			return;
		}
		Set<UUID> after = snapshotItemUuids(level, deathPos, ITEM_DROP_DETECT_RADIUS);
		for (UUID id : after) {
			if (!itemsBefore.contains(id)) {
				pendingItemPickups.add(id);
			}
		}
	}

	private static Set<UUID> snapshotItemUuids(ClientLevel level, Vec3 center, double radius) {
		AABB box = new AABB(
				center.x - radius,
				center.y - radius,
				center.z - radius,
				center.x + radius,
				center.y + radius,
				center.z + radius);
		Set<UUID> out = new HashSet<>();
		for (ItemEntity ent : level.getEntitiesOfClass(ItemEntity.class, box, Entity::isAlive)) {
			out.add(ent.getUUID());
		}
		return out;
	}

	private static void prunePendingPickups(ClientLevel level, Vec3 nearPlayer) {
		Vec3 c = nearPlayer == null ? Vec3.ZERO : nearPlayer;
		double r = ENTITY_BY_UUID_RADIUS;
		AABB box = new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
		pendingItemPickups.removeIf(uuid -> {
			Entity e = entityByUuidInBox(level, uuid, box);
			return e == null || !e.isAlive();
		});
	}

	private static Entity entityByUuidInBox(ClientLevel level, UUID uuid, AABB box) {
		for (Entity e : level.getEntitiesOfClass(Entity.class, box, Entity::isAlive)) {
			if (e.getUUID().equals(uuid)) {
				return e;
			}
		}
		return null;
	}

	private static Entity entityByUuidNear(ClientLevel level, UUID uuid, Vec3 center) {
		try {
			var m = level.getClass().getMethod("getEntity", UUID.class);
			Object found = m.invoke(level, uuid);
			if (found instanceof Entity e && e.isAlive()) {
				return e;
			}
		} catch (NoSuchMethodException ignored) {
		} catch (ReflectiveOperationException ignored) {
		}
		if (center == null) {
			center = Vec3.ZERO;
		}
		double r = ENTITY_BY_UUID_RADIUS;
		AABB box = new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
		return entityByUuidInBox(level, uuid, box);
	}

	private static void refreshBaritoneFollow() {
		try {
			var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			var follow = baritone.getFollowProcess();
			if (!pendingItemPickups.isEmpty()) {
				applyBaritoneLootFollowSettings();
				Set<UUID> ids = Set.copyOf(pendingItemPickups);
				follow.follow(e -> e.isAlive() && e instanceof ItemEntity && ids.contains(e.getUUID()));
				return;
			}
			restoreBaritoneFollowTweaks();
			if (targetId != null) {
				UUID id = targetId;
				follow.follow(e -> e.isAlive() && e.getUUID().equals(id));
				return;
			}
			follow.cancel();
		} catch (Throwable ignored) {
		}
	}

	private static void applyBaritoneLootFollowSettings() {
		var settings = BaritoneAPI.getSettings();
		if (followRadiusBeforeLoot == null) {
			followRadiusBeforeLoot = settings.followRadius.value;
		}
		if (followOffsetDistanceBeforeLoot == null) {
			followOffsetDistanceBeforeLoot = settings.followOffsetDistance.value;
		}
		settings.followRadius.value = 0;
		settings.followOffsetDistance.value = 0.0;
	}

	private static void restoreBaritoneFollowTweaks() {
		var settings = BaritoneAPI.getSettings();
		if (followRadiusBeforeLoot != null) {
			settings.followRadius.value = followRadiusBeforeLoot;
			followRadiusBeforeLoot = null;
		}
		if (followOffsetDistanceBeforeLoot != null) {
			settings.followOffsetDistance.value = followOffsetDistanceBeforeLoot;
			followOffsetDistanceBeforeLoot = null;
		}
	}

	private static LivingEntity resolveHuntTarget(Minecraft mc, Player player) {
		Vec3 hint = lastMobPos != null ? lastMobPos : player.position();
		if (targetId != null) {
			Entity e = entityByUuidNear(mc.level, targetId, hint);
			if (e instanceof LivingEntity le && le.isAlive()) {
				return le;
			}
			registerDropsFromKill(mc.level, lastMobPos, lastItemUuidsNearMob);
			targetId = null;
		}
		if (!pendingItemPickups.isEmpty()) {
			return null;
		}
		acquireNextTarget(player);
		if (targetId != null) {
			Entity e = entityByUuidNear(mc.level, targetId, player.position());
			if (e instanceof LivingEntity le && le.isAlive()) {
				return le;
			}
		}
		return null;
	}

	private static void restoreBaritoneBreakSetting() {
		if (allowBreakBefore != null) {
			BaritoneAPI.getSettings().allowBreak.value = allowBreakBefore;
			allowBreakBefore = null;
		}
	}

	private static void acquireNextTarget(Player player) {
		if (huntType == null) {
			return;
		}
		LivingEntity next = findNearest(player, huntType, targetId);
		if (next == null) {
			targetId = null;
			try {
				BaritoneAPI.getProvider().getPrimaryBaritone().getFollowProcess().cancel();
			} catch (Throwable ignored) {
			}
			return;
		}
		targetId = next.getUUID();
		refreshBaritoneFollow();
	}

	private static LivingEntity findNearest(Player player, EntityType<?> type, UUID exclude) {
		AABB box = player.getBoundingBox().inflate(192.0);
		var nearby = player.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive() && e.getType() == type && (exclude == null || !e.getUUID().equals(exclude)));
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity candidate : nearby) {
			double d = candidate.distanceToSqr(player);
			if (d < bestD) {
				bestD = d;
				best = candidate;
			}
		}
		return best;
	}

	private static EntityType<?> resolveEntityType(String raw) {
		String s = raw.trim();
		if (!s.contains(":")) {
			s = "minecraft:" + s;
		}
		for (ResourceKey<EntityType<?>> key : BuiltInRegistries.ENTITY_TYPE.registryKeySet()) {
			try {
				if (resourceKeyAsString(key).equals(s)) {
					return entityTypeForKey(key);
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return null;
	}

	private static String resourceKeyAsString(ResourceKey<?> key) throws ReflectiveOperationException {
		try {
			var m = key.getClass().getMethod("location");
			return m.invoke(key).toString();
		} catch (NoSuchMethodException e) {
			var m = key.getClass().getMethod("identifier");
			return m.invoke(key).toString();
		}
	}

	@SuppressWarnings("unchecked")
	private static EntityType<?> entityTypeForKey(ResourceKey<EntityType<?>> key) {
		var reg = BuiltInRegistries.ENTITY_TYPE;
		try {
			var m = reg.getClass().getMethod("getHolder", ResourceKey.class);
			Optional<?> opt = (Optional<?>) m.invoke(reg, key);
			EntityType<?> from = holderOptionalToType(opt);
			if (from != null) {
				return from;
			}
		} catch (NoSuchMethodException ignored) {
		} catch (ReflectiveOperationException ignored) {
		}
		try {
			Object v = reg.getClass().getMethod("get", ResourceKey.class).invoke(reg, key);
			if (v instanceof Optional<?> o) {
				return holderOptionalToType(o);
			}
			if (v instanceof EntityType<?> et) {
				return et;
			}
		} catch (NoSuchMethodException ignored) {
		} catch (ReflectiveOperationException ignored) {
		}
		return null;
	}

	private static EntityType<?> holderOptionalToType(Optional<?> opt) {
		if (opt == null || opt.isEmpty()) {
			return null;
		}
		Object inner = opt.get();
		if (inner instanceof EntityType<?> et) {
			return et;
		}
		try {
			var vm = inner.getClass().getMethod("value");
			return (EntityType<?>) vm.invoke(inner);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	private static int inventorySelectedSlot(Player player) {
		var inv = player.getInventory();
		try {
			return (Integer) inv.getClass().getMethod("getSelectedSlot").invoke(inv);
		} catch (NoSuchMethodException e) {
			try {
				var f = inv.getClass().getDeclaredField("selected");
				f.setAccessible(true);
				return f.getInt(inv);
			} catch (ReflectiveOperationException e2) {
				return 0;
			}
		} catch (ReflectiveOperationException e) {
			return 0;
		}
	}

	private static void inventorySetSelectedSlot(Player player, int slot) {
		var inv = player.getInventory();
		try {
			inv.getClass().getMethod("setSelectedSlot", int.class).invoke(inv, slot);
			return;
		} catch (NoSuchMethodException e) {
			try {
				var f = inv.getClass().getDeclaredField("selected");
				f.setAccessible(true);
				f.setInt(inv, slot);
			} catch (ReflectiveOperationException ignored) {
			}
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static int bestWeaponHotbarSlot(Player player) {
		boolean hasSword = false;
		for (int i = 0; i < 9; i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (!s.isEmpty() && s.is(ItemTags.SWORDS)) {
				hasSword = true;
				break;
			}
		}

		int best = -1;
		float bestScore = -1f;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (hasSword && stack.is(ItemTags.AXES)) {
				continue;
			}
			float score = baseAttackDamage(stack);
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	private static float baseAttackDamage(ItemStack stack) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		float sum = 0f;
		for (ItemAttributeModifiers.Entry e : mods.modifiers()) {
			if (e.attribute().is(Attributes.ATTACK_DAMAGE)) {
				sum += (float) e.modifier().amount();
			}
		}
		if (sum <= 0f) {
			return 0.25f;
		}
		return sum;
	}
}
