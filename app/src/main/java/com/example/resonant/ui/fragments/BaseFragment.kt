package com.example.resonant.ui.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

open class BaseFragment(layoutRes: Int) : Fragment(layoutRes) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.alpha = 0f
        view.animate()
            .alpha(1f)
            .setDuration(220)
            .setStartDelay(10)
            .start()
    }
}