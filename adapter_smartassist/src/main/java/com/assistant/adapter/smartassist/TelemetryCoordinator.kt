package com.assistant.adapter.smartassist

import com.assistant.diagnostic.RuntimeLogger

object TelemetryCoordinator {

    private var networkThread: LowLatencyNetworkThread? = null

    fun initializeTransport(host:String,port:Int){
        if(networkThread==null){
            networkThread = LowLatencyNetworkThread(host,port)
            networkThread?.start()
        }
    }


    fun updatePlayerMotion(
        velocity: Float,
        opponentDistance: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                playerVelocity = velocity,
                opponentDistance = opponentDistance
            )
        )

        RuntimeLogger.telemetry("playerVelocity=$velocity opponentDistance=$opponentDistance")
    }

    fun updateBallMotion(
        x: Float,
        y: Float,
        velocityX: Float,
        velocityY: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                ballX = x,
                ballY = y,
                ballVelocityX = velocityX,
                ballVelocityY = velocityY
            )
        )

        RuntimeLogger.telemetry("ball=($x,$y) velocity=($velocityX,$velocityY)")
    }

    fun updateGoalkeeperPosition(
        x: Float,
        y: Float
    ) {
        val current = TelemetryRepository.current()

        TelemetryRepository.update(
            current.copy(
                goalkeeperX = x,
                goalkeeperY = y,
                goalkeeperConfidence =
                    if (x != 0f || y != 0f) 1f else 0f
            )
        )

        RuntimeLogger.telemetry("goalkeeper=($x,$y)")
    }
}
