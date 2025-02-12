package com.tb24.discordbot.commands

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.arguments.StringArgumentType.getString
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.tb24.discordbot.BotConfig
import com.tb24.discordbot.HttpException
import com.tb24.discordbot.util.exec
import com.tb24.discordbot.util.format
import com.tb24.fn.model.account.DeviceAuth
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.ChannelType
import net.dv8tion.jda.api.exceptions.ErrorResponseException

class DeviceAuthCommand : BrigadierCommand("devices", "Device auth operation commands.", arrayOf("device")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::list)
		.then(literal("list").executes(::list))
		.then(literal("create").executes(::create))
		.then(literal("remove")
			.then(argument("device ID", greedyString())
				.executes {
					val deviceId = getString(it, "device ID")
					if (deviceId.length != 32) {
						throw SimpleCommandExceptionType(LiteralMessage("The device ID should be a 32 character hexadecimal string")).create()
					}
					delete(it.source, deviceId)
				}
			)
		)
}

class SaveLoginCommand : BrigadierCommand("savelogin", "Saves the current account to the bot, for easy login.") {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes(::create)
}

class DeleteSavedLoginCommand : BrigadierCommand("deletesavedlogin", "Removes the current account from the bot.", arrayOf("removesavedlogin")) {
	override fun getNode(dispatcher: CommandDispatcher<CommandSourceStack>): LiteralArgumentBuilder<CommandSourceStack> = newRootNode()
		.executes {
			val source = it.source
			source.ensureSession()
			val user = source.api.currentLoggedIn
			val dbDevice = source.client.savedLoginsManager.get(source.session.id, user.id)
				?: throw SimpleCommandExceptionType(LiteralMessage("You don't have a saved login for this account (${user.displayName}).")).create()
			delete(it.source, dbDevice.deviceId)
		}
}

private fun list(c: CommandContext<CommandSourceStack>): Int {
	val source = c.source
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	source.loading("Getting account's saved logins")
	val response = source.api.accountService.getDeviceAuths(user.id).exec().body()!!
	val dbDevice = source.client.savedLoginsManager.get(sessionId, user.id)
	if (response.isEmpty()) {
		source.complete("No entries")
		return Command.SINGLE_SUCCESS
	}
	for (partitioned in response.toList().chunked(6)) { // TODO use pagination instead
		source.complete(partitioned.joinToString("\n") { item ->
			val line1: String
			val line2: String
			val line3: String
			var title = "**" + item.deviceId + "**"
			var platformVersion = item.deviceInfo?.os ?: "Unknown"
			var userAgent = item.userAgent
			if (item.deviceInfo != null) {
				title = "**" + (if (item.deviceInfo.type == item.deviceInfo.model) item.deviceInfo.model else (item.deviceInfo.type + ' ' + item.deviceInfo.model)) + "** \u00b7 ||" + item.deviceId + "||"
			}
			line1 = title
			line2 = "Added: " + item.created.render() + '\n' + "Last login: " + (item.lastAccess?.render() ?: "Never used this device to authenticate")
			try {
				val versions = userAgent.split(" ")
				val client = versions[0].split("/")
				userAgent = if (client[0] == "Fortnite") {
					val fortnite = client[1].split("-")
					client[0] + ' ' + fortnite[1] + " (" + fortnite[3] + ')'
				} else {
					client[0] + ' ' + client[1]
				}
				if (versions.size > 1) {
					val platform = versions[1].split("/")
					platformVersion = platform[0] + ' ' + platform[1]
				}
			} catch (ignored: Exception) {
			}
			line3 = (if (dbDevice?.deviceId == item.deviceId) "THIS DEVICE \u00b7 " else "") + platformVersion + " \u00b7 " + userAgent
			"${line1}\n${line2}\n${line3}\n"
		})
	}
	return Command.SINGLE_SUCCESS
}

private fun DeviceAuth.LocationIpDate.render() = "%s, %s (%s)".format(dateTime.format(), ipAddress, location)

private fun create(c: CommandContext<CommandSourceStack>): Int {
	val source = c.source
	val inDMs = source.isFromType(ChannelType.PRIVATE)
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	val dbDevices = source.client.savedLoginsManager.getAll(sessionId)
	val dbDevice = dbDevices.firstOrNull { it.accountId == user.id }
	if (dbDevice != null) {
		throw SimpleCommandExceptionType(LiteralMessage("You already registered a device auth of this account.")).create()
	}
	val limit = source.getSavedAccountsLimit()
	if (dbDevices.size >= limit) {
		if (dbDevices.isEmpty() && limit == 0) {
			val quotaSettings = BotConfig.get().deviceAuthQuota
			source.ensurePremium("Your Discord account must be older than %,d days in order to have %,d complimentary saved logins.\nGet %,d saved logins regardless of account age".format(
				quotaSettings.minAccountAgeInDaysForComplimentary,
				quotaSettings.maxForComplimentary,
				quotaSettings.maxForPremium
			))
			check(false) // We should never reach this point
		}
		throw SimpleCommandExceptionType(LiteralMessage("Maximum number of saved logins (%,d) has been reached.".format(limit))).create()
	}
	if (!BotConfig.get().allowUsersToCreateDeviceAuth) {
		throw SimpleCommandExceptionType(LiteralMessage("The current instance of the bot does not allow saving logins.")).create()
	}
	source.loading("Creating device auth")
	val response = source.api.accountService.createDeviceAuth(user.id, null).exec().body()!!
	source.client.savedLoginsManager.put(sessionId, DeviceAuth().apply {
		accountId = response.accountId
		deviceId = response.deviceId
		secret = response.secret
		clientId = source.api.userToken.client_id
	})
	val embed = source.createEmbed().setColor(BrigadierCommand.COLOR_SUCCESS)
		.setTitle("✅ Device auth created and registered to ${source.client.discord.selfUser.name}")
		.setFooter("Use ${source.prefix}deletesavedlogin to reverse the process")
	if (inDMs) {
		source.complete(null, embed.populateDeviceAuthDetails(response).build())
	} else {
		try {
			val detailsMessage = source.author.openPrivateChannel()
				.flatMap { it.sendMessageEmbeds(EmbedBuilder(embed).populateDeviceAuthDetails(response).build()) }
				.complete()
			source.complete(null, embed.setDescription("[Check your DMs for details.](%s)".format(detailsMessage.jumpUrl)).build())
		} catch (e: ErrorResponseException) {
            source.complete(null, embed.setDescription("We couldn't DM you the details.").build())
        }
	}
	return Command.SINGLE_SUCCESS
}

private fun EmbedBuilder.populateDeviceAuthDetails(deviceAuth: DeviceAuth) =
	this.addField("Account ID", deviceAuth.accountId, false)
		.addField("Device ID", deviceAuth.deviceId, false)
		.addField("Secret (Do not share!)", "||" + deviceAuth.secret + "||", false)

private fun delete(source: CommandSourceStack, deviceId: String): Int {
	source.ensureSession()
	val sessionId = source.session.id
	val user = source.api.currentLoggedIn
	val dbDevice = source.client.savedLoginsManager.get(sessionId, user.id)
	source.loading("Deleting device auth")
	try {
		source.api.accountService.deleteDeviceAuth(user.id, deviceId).exec()
		val msgs = mutableListOf<String>()
		if (dbDevice != null && dbDevice.deviceId == deviceId) {
			source.client.savedLoginsManager.remove(sessionId, user.id)
			msgs.add("Unregistered device auth.")
		}
		msgs.add("Deleted device auth from the account successfully.")
		source.complete(null, source.createEmbed().setDescription(msgs.joinToString("\n")).build())
	} catch (e: HttpException) {
		if (dbDevice?.deviceId == deviceId && e.epicError.errorCode == "errors.com.epicgames.account.device_auth.not_found") {
			source.client.savedLoginsManager.remove(sessionId, user.id)
			throw SimpleCommandExceptionType(LiteralMessage("Your saved login is no longer valid.")).create()
		} else {
			throw e
		}
	}
	return Command.SINGLE_SUCCESS
}