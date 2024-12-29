package com.example.onlydms

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class TouchBlockingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        // Consume all touch events
        return true
    }
} 