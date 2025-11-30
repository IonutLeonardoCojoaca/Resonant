package com.example.resonant.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.resonant.data.models.User

class UserViewModel(app: Application) : AndroidViewModel(app) {
    var user: User? = null
    val profileImageUpdated = androidx.lifecycle.MutableLiveData<Boolean>()
}