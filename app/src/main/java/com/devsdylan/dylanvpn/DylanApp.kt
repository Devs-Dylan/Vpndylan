package com.devsdylan.dylanvpn

import android.app.Application
import com.devsdylan.dylanvpn.data.Account
import com.devsdylan.dylanvpn.data.Repository
import com.devsdylan.dylanvpn.data.SecureStore
import com.devsdylan.dylanvpn.data.Updater
import com.devsdylan.dylanvpn.data.Usage
import com.devsdylan.dylanvpn.data.Store

class DylanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        SecureStore.init(this)
        Account.init(this)
        Usage.init(this)
        Updater.init(this)
        Repository.appContext = applicationContext
        if (Account.session.value != null) {
            if (Store.settings.value.autoSync) Repository.syncAsync()
            Thread { Updater.check() }.start()
        }
    }
}
