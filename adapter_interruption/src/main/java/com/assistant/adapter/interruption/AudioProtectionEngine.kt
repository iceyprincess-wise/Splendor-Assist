package com.assistant.adapter.interruption

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

object AudioProtectionEngine {

    private var focusRequest: AudioFocusRequest? = null

    fun protect(context: Context): Boolean {

        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE)
                    as AudioManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            if (focusRequest == null) {
                focusRequest =
                    AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    )
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(
                                    AudioAttributes.USAGE_GAME
                                )
                                .build()
                        )
                        .build()
            }

            audioManager.requestAudioFocus(
                focusRequest!!
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    fun release(context: Context) {

        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE)
                    as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }

        } else {

            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
