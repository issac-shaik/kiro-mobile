package dev.kiromobile.app

import android.app.Application
import dev.kiromobile.chat.ChatActivity
import dev.kiromobile.notifications.PushSetup

class MainActivity : ChatActivity()
class MobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PushSetup.initialize(this,
            getString(R.string.firebase_app_id), getString(R.string.firebase_api_key),
            getString(R.string.firebase_project_id), getString(R.string.firebase_sender_id))
    }
}
