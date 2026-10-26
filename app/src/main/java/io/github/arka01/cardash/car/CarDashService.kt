package io.github.arka01.cardash.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/** Android Auto starts this when you open Car Dash on the car screen. */
class CarDashService : CarAppService() {
    // A personal sideloaded app: accept any Android Auto host.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = CarDashSession()
}

class CarDashSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = SensorListScreen(carContext)
}
