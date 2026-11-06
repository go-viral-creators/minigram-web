package com.kafisir.wetalks.data

enum class Kind { DM, GROUP, CHANNEL }

/** Points at one conversation. DM id = sorted "uidA_uidB", group/channel id = push key. */
data class ChatRef(val id: String, val kind: Kind, val peer: String? = null)

data class User(
    val uid: String = "",
    val name: String = "",
    val username: String = "",
    val about: String = "",
    val photo: String = "",        // base64 JPEG thumbnail, may be empty
    val pk: String = "",           // X25519 public key (base64)
    val online: Boolean = false,
    val lastSeen: Long = 0,
    val showLastSeen: Boolean = true,
    val readReceipts: Boolean = true, val webProfile: Boolean = true,
)

data class Group(
    val id: String = "", val name: String = "", val about: String = "", val photo: String = "",
    val owner: String = "", val kv: Int = 1, val members: Map<String, String> = emptyMap(), val perms: Map<String, Boolean> = emptyMap(), val slow: Int = 0,
)

data class Channel(
    val id: String = "", val name: String = "", val handle: String = "", val about: String = "", val photo: String = "",
    val owner: String = "", val admins: Set<String> = emptySet(), val subCount: Long = 0,
    val lastTs: Long = 0, val lastPreview: String = "", val perms: Map<String, Boolean> = emptyMap(), val slow: Int = 0, val priv: Boolean = false,
)

data class ChatItem(
    val ref: ChatRef, val lastTs: Long, val lastBody: String, val lastType: String, val lastFrom: String,
    val unread: Long, val pinned: Boolean, val muted: Boolean,
)

data class Msg(
    val id: String, val from: String, val ts: Long, val type: String,      // text | link | image | gif | voice | sticker
    val text: String, val mediaId: String? = null, val dur: Int = 0,
    val rid: String? = null, val deleted: Boolean = false, val edited: Boolean = false,
    val reactions: Map<String, String> = emptyMap(), val unreadable: Boolean = false, val pending: Boolean = false,
    val wave: List<Int> = emptyList(),
    val link: LinkPreview? = null, val mime: String = "", val w: Int = 0, val h: Int = 0, val fwd: String? = null,
)

/** Preview card fetched by the sender and sent inside the encrypted message. */
data class LinkPreview(val url: String, val title: String, val desc: String, val thumb: String)
