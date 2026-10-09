package bj.phonepilote.app

import android.app.Application
import bj.phonepilote.app.data.Repo
import bj.phonepilote.app.push.Push

class PhonePiloteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Push.init(this)
        Repo.init(this)
    }
}
