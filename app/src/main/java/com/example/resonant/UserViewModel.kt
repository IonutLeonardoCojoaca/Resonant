package com.example.resonant

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class UserViewModel(app: Application) : AndroidViewModel(app) {
    var user: User? = null
}