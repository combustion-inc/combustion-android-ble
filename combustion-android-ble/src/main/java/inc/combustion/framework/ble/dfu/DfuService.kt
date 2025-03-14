package inc.combustion.framework.ble.dfu

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService

class DfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity>? {
        return notifyActivity
    }

    companion object {
        lateinit var notifyActivity: Class<out Activity>

        fun isNotifyActivitySet(): Boolean =
            ::notifyActivity.isInitialized
    }
}