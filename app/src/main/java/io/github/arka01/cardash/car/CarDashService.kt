package io.github.arka01.cardash.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Android Auto starts this when you open Car Dash on the car screen. */
class CarDashService : CarAppService() {
    // A personal app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = CarDashSession()
}

/** One Android Auto connection: reading starts with it and, if it started reading, stops with it. */
class CarDashSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = CarControls.carGone()
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = DashGridScreen(carContext)
}
