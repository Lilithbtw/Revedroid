package app.revedroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.revedroid.ui.RevedroidRoot
import app.revedroid.ui.navigation.AppNavHost
import app.revedroid.ui.share.SharedItems

/**
 * Shows up in other apps' share sheets. It runs the usual connect and sign-in
 * flow, asks for a folder, queues the uploads, and closes; the uploads carry on
 * in the background with a progress notification.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val items = SharedItems.from(this, intent)
        if (items.isEmpty()) {
            finish()
            return
        }
        setContent {
            RevedroidRoot(onLeave = ::finish) {
                AppNavHost(incoming = items, onIncomingHandled = ::finish)
            }
        }
    }
}
