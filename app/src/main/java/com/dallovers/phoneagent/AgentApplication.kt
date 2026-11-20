package com.dallovers.phoneagent

import android.app.Application

class AgentApplication : Application() {
    lateinit var controller: AgentController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = AgentController(this)
    }
}
