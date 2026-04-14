
package com.example.matrixclock

import android.app.Activity
import android.os.*
import android.view.*

class MainActivity : Activity() {

    private lateinit var view: MatrixView
    private var downTime = 0L
    private var upTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = MatrixView(this)
        setContentView(view)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (downTime == 0L) downTime = now
                if (now - downTime > 1000) finish()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (upTime == 0L) upTime = now
                if (now - upTime > 1000) finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) downTime = 0
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) upTime = 0
        return super.onKeyUp(keyCode, event)
    }
}
