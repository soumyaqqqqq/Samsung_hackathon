package com.friday.node.data.local

data class EventEntity(
    val messageId: String,
    val type: String,
    val payload: String,
    val timestamp: Long
)
