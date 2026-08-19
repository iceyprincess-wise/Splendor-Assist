package com.assistant.adapter.smartassist

import android.os.Process
import java.net.Socket

class LowLatencyNetworkThread(
    private val targetHost:String,
    private val port:Int
):Thread(){

    private var connectionSocket:Socket?=null

    override fun run(){

        Process.setThreadPriority(
            Process.THREAD_PRIORITY_URGENT_AUDIO
        )

        try{

            connectionSocket=
                Socket(
                    targetHost,
                    port
                ).apply{

                    tcpNoDelay=true

                    receiveBufferSize=8192
                    sendBufferSize=8192

                    soTimeout=2000
                }

        }catch(_:Exception){
        }
    }
}
