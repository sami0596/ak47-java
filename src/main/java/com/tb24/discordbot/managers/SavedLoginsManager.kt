package com.tb24.discordbot.managers

import com.rethinkdb.RethinkDB.r
import com.rethinkdb.net.Connection
import com.tb24.fn.model.account.DeviceAuth

class SavedLoginsManager(private val conn: Connection) {
	fun getAll(sessionId: String) =
		r.table("devices").get(sessionId).run(conn, Entry::class.java).first()?.devices ?: emptyList()

	fun get(sessionId: String, accountId: String) =
		getAll(sessionId).firstOrNull { it.accountId == accountId }

	fun put(sessionId: String, device: DeviceAuth): Boolean {
		val dbEntry = r.table("devices").get(sessionId).run(conn, Entry::class.java).first()
		val devices = dbEntry?.devices ?: mutableListOf()
		if (devices.firstOrNull { it.accountId == device.accountId } != null) {
			return false // already exists
		}
		devices.add(device)
		val newContents = Entry()
		newContents.id = sessionId
		newContents.devices = devices
		if (dbEntry != null) {
			r.table("devices").update(newContents)
		} else {
			r.table("devices").insert(newContents)
		}.run(conn)
		return true
	}

	fun remove(sessionId: String, accountId: String): Boolean {
		val dbEntry = r.table("devices").get(sessionId).run(conn, Entry::class.java).first()
		r.table("auto_claim").filter(mapOf("accountId" to accountId, "registrantId" to sessionId)).delete().run(conn)
		return if (dbEntry != null) {
			val filtered = dbEntry.devices!!.filter { it.accountId != accountId }
			if (filtered.isNotEmpty()) {
				r.table("devices").update(Entry().apply {
					id = sessionId
					devices = filtered.toMutableList()
				})
			} else {
				r.table("devices").get(sessionId).delete()
			}.run(conn)
			true
		} else {
			false
		}
	}

	class Entry {
		@JvmField var id: String? = null
		@JvmField var devices: MutableList<DeviceAuth>? = null
	}
}