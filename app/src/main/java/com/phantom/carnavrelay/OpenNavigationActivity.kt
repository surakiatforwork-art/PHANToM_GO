package com.phantom.carnavrelay

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.phantom.carnavrelay.PhantomLog

class OpenNavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val EXTRA_URL = "url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "▶️ OpenNavigationActivity.onCreate()")
        
        // Get URL from intent
        val url = intent.getStringExtra(EXTRA_URL)
        
        if (url.isNullOrEmpty()) {
            Log.e(TAG, "❌ No URL provided in intent")
            Toast.makeText(this, "No navigation URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val prefsManager = PrefsManager(this)
        val openBehavior = prefsManager.getDisplayOpenBehavior()
        val previewUrl = NavLinkUtils.toDisplayOpenUrl(url, prefsManager.getDisplayNavMode(), openBehavior)
        Log.d(TAG, "📍 Opening navigation URL: $previewUrl")
        PhantomLog.i("NAV openAttempt rawUrl=$url (OpenNavigationActivity)")
        
        try {
            // Create intent to open Google Maps
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl)).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                // Try to open Google Maps specifically
                setPackage("com.google.android.apps.maps")
            }
            
            startActivity(mapsIntent)
            Log.d(TAG, "✅ Successfully launched Google Maps")
            PhantomLog.i("NAV openSuccess via Google Maps (OpenNavigationActivity)")
            finish()
            
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "⚠️ Google Maps not found, opening in browser")
            try {
                // Fallback to any app that can handle the URL
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(browserIntent)
                Log.d(TAG, "✅ Successfully launched browser")
                PhantomLog.w("NAV openFallback success (OpenNavigationActivity)")
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "💥 Failed to open any app for URL: $url", e2)
                PhantomLog.e("NAV openFail (OpenNavigationActivity): ${e2.message}", e2)
                Toast.makeText(this, "Cannot open navigation URL", Toast.LENGTH_LONG).show()
                finish()
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error opening navigation URL: $url", e)
            PhantomLog.e("NAV openError (OpenNavigationActivity): ${e.message}", e)
            Toast.makeText(this, "Error opening navigation", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
