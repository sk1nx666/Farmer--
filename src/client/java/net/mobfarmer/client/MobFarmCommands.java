package net.mobfarmer.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

final class MobFarmCommands {
	private MobFarmCommands() {}

	static void register(com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(
				literal("farmer")
						.then(literal("farm").then(argument("mob", StringArgumentType.greedyString()).executes(ctx -> {
							String mob = StringArgumentType.getString(ctx, "mob");
							var source = ctx.getSource();
							MobFarmController.start(
									source.getPlayer(), mob, msg -> source.sendFeedback(Component.literal(msg)));
							return 1;
						})))
						.then(literal("stop").executes(ctx -> {
							MobFarmController.stop(msg -> ctx.getSource().sendFeedback(Component.literal(msg)));
							return 1;
						})));
	}
}
