package com.sah

import android.content.Context

data class PlayerProfile(
    val nickname: String = "",
    val difficultyLevel: Int = AdaptiveDifficulty.DEFAULT_LEVEL,
)

object NicknamePolicy {
    const val MAX_LENGTH = 24

    fun normalize(value: String): String = value.trim().take(MAX_LENGTH)
}

class PlayerProfileStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): PlayerProfile = PlayerProfile(
        nickname = NicknamePolicy.normalize(preferences.getString(KEY_NICKNAME, "") ?: ""),
        difficultyLevel = AdaptiveDifficulty.clamp(
            preferences.getInt(KEY_DIFFICULTY, AdaptiveDifficulty.DEFAULT_LEVEL),
        ),
    )

    fun saveNickname(rawNickname: String): PlayerProfile {
        val profile = load().copy(nickname = NicknamePolicy.normalize(rawNickname))
        save(profile)
        return profile
    }

    fun save(profile: PlayerProfile) {
        preferences.edit()
            .putString(KEY_NICKNAME, NicknamePolicy.normalize(profile.nickname))
            .putInt(KEY_DIFFICULTY, AdaptiveDifficulty.clamp(profile.difficultyLevel))
            .apply()
    }

    private companion object {
        const val NAME = "sah.profile"
        const val KEY_NICKNAME = "nickname"
        const val KEY_DIFFICULTY = "difficulty"
    }
}
