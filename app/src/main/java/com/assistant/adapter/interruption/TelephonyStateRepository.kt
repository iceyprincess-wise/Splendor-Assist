package com.assistant.adapter.interruption

object TelephonyStateRepository {

    @Volatile
    var activeCall: Boolean = false
}
