package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tb24.fn.util.Formatters

class PingCommand : BrigadierCommand("ping", "Returns latency and API ping.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val message = it.source.loading("Pinging")
			it.source.complete("🏓 Pong!\nLatency is ${Formatters.num.format(message.timeCreated.toInstant().toEpochMilli() - it.source.message.timeCreated.toInstant().toEpochMilli())}ms\nAPI Latency is ${Formatters.num.format(message.jda.gatewayPing)}ms")
			Command.SINGLE_SUCCESS
		}
}