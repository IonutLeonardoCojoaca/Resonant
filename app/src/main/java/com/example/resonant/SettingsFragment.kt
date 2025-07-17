package com.example.resonant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    lateinit var auth: FirebaseAuth
    lateinit var credentialManager: CredentialManager
    private lateinit var signOutButton: RelativeLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        auth = Firebase.auth
        credentialManager = CredentialManager.create(requireContext())
        signOutButton = view.findViewById(R.id.signOutButton)

        signOutButton.setOnClickListener {
            signOut()
        }

        return view
    }

    private fun signOut() {
        auth.signOut()
        lifecycleScope.launch {
            try {
                val clearRequest = ClearCredentialStateRequest()
                credentialManager.clearCredentialState(clearRequest)
                val intent = Intent(requireContext(), LoginActivity::class.java)
                MediaPlayerManager.stop()
                startActivity(intent)
                requireActivity().finish()
            } catch (e: ClearCredentialException) {
                Log.i("ErrorSingOut", "Couldn't clear user credentials: ${e.localizedMessage}")
            }
        }
    }


}