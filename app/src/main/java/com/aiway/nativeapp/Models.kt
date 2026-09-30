package com.aiway.nativeapp

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val text: String
)

data class GithubRepo(
    val fullName: String,
    val defaultBranch: String,
    val privateRepo: Boolean
)

data class GithubBranch(val name: String)
