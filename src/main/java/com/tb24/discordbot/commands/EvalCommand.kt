package com.tb24.discordbot.commands

import com.google.gson.internal.reflect.ReflectionAccessor
import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.Rune
import com.tb24.uasset.AssetManager
import javax.script.ScriptEngineManager
import javax.script.ScriptException

class EvalCommand : BrigadierCommand("eval", "Evaluate an expression for debugging purposes.") {
	private val engine = ScriptEngineManager().getEngineByName("js").apply {
		put("provider", AssetManager.INSTANCE)
	}
	private val detailMessageField = Throwable::class.java.getDeclaredField("detailMessage").apply { ReflectionAccessor.getInstance().makeAccessible(this) }

	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.requires(Rune::isBotDev)
		.then(argument("code", greedyString())
			.executes { handle(it.source, getString(it, "code")) }
		)

	private fun handle(source: CommandSourceStack, code: String): Int {
		try {
			synchronized(engine) {
				engine.put("client", source.client)
				engine.put("source", source)
				engine.put("config", BotConfig.get())
				source.complete("```\n${engine.eval(code)}```", null)
			}
		} catch (e: Throwable) {
			throw SimpleCommandExceptionType(LiteralMessage("Execution failed```\n${if (e is ScriptException) detailMessageField.get(e) as String else e.toString()}```")).create()
		}
		return Command.SINGLE_SUCCESS
	}
}