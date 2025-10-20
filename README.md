# Resonant - App de Streaming de Música (Cliente Android) 🎵

Aplicación de streaming de música nativa para Android, desarrollada 100% en Kotlin. Esta app funciona como el cliente de un sistema full-stack, consumiendo una API backend (.NET) para gestionar usuarios, música y playlists.

*(Este repositorio contiene únicamente el código del cliente Android)*

---

## 📸 Capturas de Pantalla
<img width="350" alt="Screenshot_20251020_131433" src="https://github.com/user-attachments/assets/85ad4bb2-faa4-499f-bf2d-f528e77289c0" /> | <img width="350" alt="Screenshot_20251020_131514" src="https://github.com/user-attachments/assets/a58e7f2c-b269-46ad-96d8-639425f8347a" /> | <img width="350" alt="Screenshot_20251020_131548" src="https://github.com/user-attachments/assets/88c12ddc-bb73-4d31-99a1-72fe1641e7e1" /> |

---

## 🛠️ Stack Tecnológico

* **Lenguaje:** Kotlin (100%)
* **Arquitectura:** MVVM (Model-View-ViewModel)
* **Asincronía:** Coroutines de Kotlin para gestionar hilos y llamadas de red.
* **Networking:** Retrofit2 y OkHttp para consumir la API REST del backend.
* **Autenticación:** Google Sign-In (OAuth 2.0) para un login seguro.
* **Ciclo de Vida:** ViewModel y LiveData para gestionar el estado de la UI.
* **Navegación:** Android Navigation Component.
* **Gestión de dependencias:** Gradle.

---

## ✨ Funcionalidades Clave

* ✅ **Autenticación de Usuario:** Login de usuarios seguro y simple a través de Google Sign-In.
* 🎵 **Exploración de Catálogo:** Navega por artistas, álbumes y canciones.
* 🎧 **Reproductor de Música:** Funcionalidad de reproducción de audio.
* 📝 **Gestión de Playlists:** Creación y gestión de playlists personales (públicas y privadas).
* 🌐 **Consumo de API:** Comunicación eficiente con el backend para obtener y enviar datos en tiempo real.
