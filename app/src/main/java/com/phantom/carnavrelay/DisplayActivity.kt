package com.phantom.carnavrelay

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class DisplayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            MainActivity.createIntent(this, BottomNavHelper.Tab.DISPLAY).apply {
                intent?.extras?.let { putExtras(it) }
            }
        )
        finish()
    }
}
