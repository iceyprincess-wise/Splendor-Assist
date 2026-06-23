package com.assistant.overlay.interceptor

object GoalkeeperBiasRegistry {

    @Volatile
    var currentBias: KeeperBias =
        KeeperBias.HOLD_CENTER
}
