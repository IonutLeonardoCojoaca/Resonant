package com.example.spomusicapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Intent
import android.util.Log
import android.widget.Button
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.ClearCredentialException
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth

class ActivitySongList : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var songAdapter: SongAdapter

    lateinit var seekBar: SeekBar
    lateinit var currentTimeText: TextView
    lateinit var totalTimeText: TextView

    private lateinit var playPauseButton: ImageButton
    private lateinit var previousSongButton: ImageButton
    private lateinit var nextSongButton: ImageButton

    private var isPlaying = false
    private var updateSeekBarRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    private val songRepository = SongRepository()

    private lateinit var signOutButton: Button
    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_song_list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        recyclerView = findViewById(R.id.main)
        recyclerView.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter()
        recyclerView.adapter = songAdapter

        seekBar = findViewById(R.id.seekBar)

        // Buttons
        playPauseButton = findViewById(R.id.playPauseButton)
        previousSongButton = findViewById(R.id.previousSongButton)
        nextSongButton = findViewById(R.id.nextSongButton)

        // Time
        currentTimeText = findViewById(R.id.currentTimeText)
        totalTimeText = findViewById(R.id.totalTimeText)

        auth = Firebase.auth
        credentialManager = CredentialManager.create(baseContext)
        signOutButton = findViewById<Button>(R.id.signOutButton)

        playPauseButton.setOnClickListener {
            if (isPlaying) {
                MediaPlayerManager.pause()
            } else {
                MediaPlayerManager.resume()

                // 🛠️ Solución asegurada:
                val duration = MediaPlayerManager.getDuration()
                if (duration > 0) {
                    seekBar.max = duration
                    totalTimeText.text = formatTime(duration)
                    startSeekBarUpdater()
                } else {
                    // 🧠 Si todavía no ha devuelto duración, esperar un poco
                    handler.postDelayed({
                        val newDuration = MediaPlayerManager.getDuration()
                        seekBar.max = newDuration
                        totalTimeText.text = formatTime(newDuration)
                        startSeekBarUpdater()
                    }, 200) // esperar 200 ms
                }
            }
            isPlaying = !isPlaying
            updatePlayPauseButton(isPlaying)
        }

        previousSongButton.setOnClickListener {
            // Aquí no necesitas el callback, se maneja automáticamente dentro del MediaPlayerManager
            PlaybackManager.playPrevious(this@ActivitySongList)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        nextSongButton.setOnClickListener {
            // Aquí tampoco necesitas el callback
            PlaybackManager.playNext(this@ActivitySongList)
            isPlaying = true
            updatePlayPauseButton(isPlaying)
        }

        songAdapter.onItemClick = { song ->
            val index = songAdapter.currentList.indexOf(song) // 🔥 encontramos el índice en la lista
            if (index != -1) {
                startSeekBarUpdater()
                PlaybackManager.playSongAt(this, index)
                isPlaying = true
                updatePlayPauseButton(isPlaying)
                NotificationManagerHelper.createNotificationChannel(this)
                NotificationManagerHelper.updateNotification(this)
            }
        }

        lifecycleScope.launch {
            val songs = songRepository.fetchSongs()
            songs?.let {
                songAdapter.submitList(it)
                PlaybackManager.setSongs(it)
            } ?: run {
                Toast.makeText(this@ActivitySongList, "Error al obtener las canciones", Toast.LENGTH_SHORT).show()
            }
        }

        signOutButton.setOnClickListener {
            signOut()
        }


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) { // El mismo número que pusiste en requestPermissions
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso concedido
                Toast.makeText(this, "Permiso de notificaciones concedido", Toast.LENGTH_SHORT).show()
            } else {
                // Permiso denegado
                Toast.makeText(this, "No se podrán mostrar notificaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePlayPauseButton(isPlaying: Boolean) {
        if (isPlaying) {
            playPauseButton.setImageResource(R.drawable.pause)
        } else {
            playPauseButton.setImageResource(R.drawable.play)
        }
    }

    fun startSeekBarUpdater() {
        // Establecer la duración total de la canción en el SeekBar y en el TextView
        val duration = MediaPlayerManager.getDuration()  // Obtén la duración total de la canción
        seekBar.max = duration
        totalTimeText.text = formatTime(duration) // Actualiza el TextView con la duración

        // Iniciar la actualización de la SeekBar y el tiempo actual
        updateSeekBarRunnable = object : Runnable {
            override fun run() {
                val currentPos = MediaPlayerManager.getCurrentPosition()
                seekBar.progress = currentPos
                currentTimeText.text = formatTime(currentPos)
                handler.postDelayed(this, 1000)  // Actualiza cada segundo
            }
        }
        handler.post(updateSeekBarRunnable!!)

        // Configurar el listener para el SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MediaPlayerManager.seekTo(progress)  // Si el usuario mueve el SeekBar, actualizar la posición
                    currentTimeText.text = formatTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000) / 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        updateSeekBarRunnable?.let { handler.removeCallbacks(it) }
        MediaPlayerManager.stop()
    }

    private fun signOut() {
        auth.signOut()
        lifecycleScope.launch {
            try {
                val clearRequest = ClearCredentialStateRequest()
                credentialManager.clearCredentialState(clearRequest)
                intent = Intent(applicationContext, MainActivity::class.java)
                startActivity(intent)
                finish()
            } catch (e: ClearCredentialException) {
                Log.i("ErrorSingOut", "Couldn't clear user credentials: ${e.localizedMessage}")
            }
        }
    }

}
