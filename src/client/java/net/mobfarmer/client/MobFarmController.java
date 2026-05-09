package net.mobfarmer.client;

import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class MobFarmController {
	private MobFarmController() {}

	private static final double ITEM_SNAPSHOT_RADIUS = 5.0;
	private static final double ITEM_DROP_DETECT_RADIUS = 8.0;

	/** When non-null, we keep killing this entity type until /farmer stop. */
	private static net.minecraft.world.entity.EntityType<?> huntType;

	private static UUID targetId;
	private static Boolean allowBreakBefore;

	/** Last known position of the mob we were chasing (for drop detection). */
	private static Vec3 lastMobPos;

	/** Item entities near the mob the previous tick (baseline for drop diff). */
	private static Set<UUID> lastItemUuidsNearMob = Collections.emptySet();

	/** Specific {@link ItemEntity} UUIDs to collect (from our kills only). */
	private static final Set<UUID> pendingItemPickups = new LinkedHashSet<>();

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
		prunePendingPickups(level);

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
			if (slot >= 0 && slot != player.getInventory().getSelectedSlot()) {
				player.getInventory().setSelectedSlot(slot);
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

	private static void prunePendingPickups(ClientLevel level) {
		pendingItemPickups.removeIf(id -> {
			Entity e = level.getEntity(id);
			return e == null || !e.isAlive();
		});
	}

	private static void refreshBaritoneFollow() {
		try {
			var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
			var follow = baritone.getFollowProcess();
			if (!pendingItemPickups.isEmpty()) {
				Set<UUID> ids = Set.copyOf(pendingItemPickups);
				follow.follow(e -> e.isAlive() && e instanceof ItemEntity && ids.contains(e.getUUID()));
				return;
			}
			if (targetId != null) {
				UUID id = targetId;
				follow.follow(e -> e.isAlive() && e.getUUID().equals(id));
				return;
			}
			follow.cancel();
		} catch (Throwable ignored) {
		}
	}

	private static LivingEntity resolveHuntTarget(Minecraft mc, Player player) {
		if (targetId != null) {
			Entity e = mc.level.getEntity(targetId);
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
			Entity e = mc.level.getEntity(targetId);
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

	private static LivingEntity findNearest(Player player, net.minecraft.world.entity.EntityType<?> type, UUID exclude) {
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

	private static net.minecraft.world.entity.EntityType<?> resolveEntityType(String raw) {
		String s = raw.trim();
		if (!s.contains(":")) {
			s = "minecraft:" + s;
		}
		Identifier id = Identifier.tryParse(s);
		if (id == null) {
			return null;
		}
		return BuiltInRegistries.ENTITY_TYPE.get(id).map(net.minecraft.core.Holder::value).orElse(null);
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
