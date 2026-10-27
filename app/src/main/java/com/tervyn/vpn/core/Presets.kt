package com.tervyn.vpn.core

/** One-tap groups of domains for commonly blocked services. */
data class Preset(val name: String, val domains: List<String>)

val PRESETS = listOf(
    Preset("YouTube", listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com", "youtubei.googleapis.com")),
    Preset("Instagram", listOf("instagram.com", "cdninstagram.com", "fbcdn.net")),
    Preset("Facebook", listOf("facebook.com", "fb.com", "fbcdn.net", "messenger.com", "facebook.net")),
    Preset("X / Twitter", listOf("x.com", "twitter.com", "twimg.com", "t.co")),
    Preset("Discord", listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media", "discordcdn.com")),
    Preset("Telegram", listOf("telegram.org", "t.me", "telegram.me", "telesco.pe")),
    Preset("WhatsApp", listOf("whatsapp.com", "whatsapp.net", "wa.me")),
    Preset("ChatGPT", listOf("chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com")),
    Preset("Claude", listOf("claude.ai", "anthropic.com")),
    Preset("LinkedIn", listOf("linkedin.com", "licdn.com")),
    Preset("Spotify", listOf("spotify.com", "scdn.co", "spotifycdn.com")),
    Preset("Netflix", listOf("netflix.com", "nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net")),
    Preset("Reddit", listOf("reddit.com", "redd.it", "redditmedia.com", "redditstatic.com")),
    Preset("TikTok", listOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "ttwstatic.com", "byteoversea.com")),
    Preset("Medium", listOf("medium.com")),
    Preset("SoundCloud", listOf("soundcloud.com", "sndcdn.com")),
)
