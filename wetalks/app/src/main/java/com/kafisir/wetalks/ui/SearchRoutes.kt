package com.kafisir.wetalks.ui

import com.kafisir.wetalks.data.ChatRef

// Routes of the search and starred-messages screens. They live in their own file (same package as Route) so Nav.kt stays untouched.

/** Search across all chats (messages, chat and person names) with a link to "Find people". */
data object RSearch : Route

/** Starred messages: all of them, or only those of [ref]. */
data class RStarred(val ref: ChatRef? = null) : Route
