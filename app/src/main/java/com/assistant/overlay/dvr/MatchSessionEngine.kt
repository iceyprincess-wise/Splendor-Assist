package com.assistant.overlay.dvr

object MatchSessionEngine {

    private var inMatch=false

    private var lastActivity=0L

    private const val END_TIMEOUT_MS=30000L

    fun onGameplayFrame(){

        lastActivity=
            System.currentTimeMillis()

        if(!inMatch){

            inMatch=true

            DvrSessionCoordinator.beginSession()
        }
    }

    fun heartbeat(){

        if(
            inMatch &&
            System.currentTimeMillis()-lastActivity>
            END_TIMEOUT_MS
        ){

            inMatch=false

            DvrSessionCoordinator.finishSession()
        }
    }

    fun active()=inMatch
}
