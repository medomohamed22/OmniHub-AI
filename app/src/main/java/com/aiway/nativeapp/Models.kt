package com.aiway.nativeapp

object Ids {
    private val last = java.util.concurrent.atomic.AtomicLong(0)
    fun next(): Long {
        while (true) {
            val now = System.currentTimeMillis()
            val prev = last.get()
            val n = if (now > prev) now else prev + 1
            if (last.compareAndSet(prev, n)) return n
        }
    }
}

data class ChatMessage(
    val id: Long = Ids.next(),
    val role: String,
    val text: String
)

data class GithubRepo(
    val fullName: String,
    val defaultBranch: String,
    val privateRepo: Boolean
)

data class GithubBranch(val name: String)
