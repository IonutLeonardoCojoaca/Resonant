# 🎵 Resonant - Android Streaming Client

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![MVVM](https://img.shields.io/badge/Architecture-MVVM-blue?style=for-the-badge)
![Retrofit](https://img.shields.io/badge/Network-Retrofit2-orange?style=for-the-badge)

> **Cliente nativo Android de alto rendimiento para el ecosistema de streaming Resonant.**

Esta aplicación ha sido desarrollada 100% en **Kotlin** siguiendo los principios de **Clean Architecture** y el patrón **MVVM**. Funciona como la interfaz de usuario de una arquitectura de microservicios híbrida, consumiendo una API RESTful en .NET 8 y realizando streaming de audio desde un Object Storage (MinIO).

---

## 📸 Galería de la Interfaz

Un recorrido visual por la experiencia de usuario, desde el descubrimiento de música hasta la reproducción detallada.

<table align="center" style="border-collapse: collapse; border: none;">
  <tr>
    <td align="center" style="border: none;">
      <img src="https://github.com/user-attachments/assets/9d4fd213-cb97-4ad3-8788-6635b061a458" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Inicio: Artistas y Álbumes Sugeridos</b></sub>
    </td>
    <td align="center" style="border: none;">
      <img src="https://github.com/user-attachments/assets/34755d3c-371a-460f-9665-0daf66ae31c0" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Búsqueda Categorizada</b></sub>
    </td>
    <td align="center" style="border: none;">
      <img src="https://github.com/user-attachments/assets/dda6006e-6b8b-4bfa-a02d-eda15df25ca4" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Biblioteca de Favoritos</b></sub>
    </td>
  </tr>
  
  <tr>
    <td align="center" style="border: none;" colspan="1.5">
      <img src="https://github.com/user-attachments/assets/af7710c8-6938-4817-82a8-2c0ceebe9479" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Perfil Inmersivo & Top Tracks</b></sub>
    </td>
    <td align="center" style="border: none;" colspan="1.5">
      <img src="https://github.com/user-attachments/assets/7468c18c-e049-4686-b01c-7f032fa287fa" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Novedades & Discografía</b></sub>
    </td>
     <td style="border: none;"></td>
  </tr>

  <tr>
    <td align="center" style="border: none;" colspan="1.5">
      <img src="https://github.com/user-attachments/assets/388cd93d-a10d-4987-843e-e69de8e97772" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Reproductor Streaming</b></sub>
    </td>
    <td align="center" style="border: none;" colspan="1.5">
      <img src="https://github.com/user-attachments/assets/9058394b-9878-413f-b7b6-4f65c49e8f1e" width="240" style="border-radius: 10px; box-shadow: 0px 4px 10px rgba(0,0,0,0.2);">
      <br><sub><b>Metadatos & Estadísticas</b></sub>
    </td>
     <td style="border: none;"></td>
  </tr>
</table>

---

## 🛠️ Stack Tecnológico

Este cliente Android está construido para ser robusto, escalable y mantenible.

### Arquitectura & Core
* **Lenguaje:** Kotlin (100%).
* **Patrón de Diseño:** MVVM (Model-View-ViewModel) para separar la lógica de negocio de la UI.
* **Inyección de Dependencias:** Gestión optimizada de dependencias.
* **Asincronía:** **Kotlin Coroutines** y **Flow** para operaciones no bloqueantes y reactivas.

### Networking & Datos
* **API Client:** **Retrofit2** + **OkHttp** (con interceptores para gestión de Tokens JWT).
* **Serialización:** Gestión eficiente de JSON.
* **Imágenes:** Carga asíncrona y caché de portadas de álbumes.

### UI & UX
* **Diseño:** Implementación fiel de **Dark Mode** y paleta de colores coherente.
* **Navegación:** Android Navigation Component (Single Activity Architecture).
* **Gestión de Estado:** LiveData y ViewModel para reactividad en tiempo real.

---

## 🚀 Funcionalidades Clave

* 🔐 **Seguridad:** Autenticación robusta e integración con backend mediante Tokens JWT.
* 🎧 **Streaming de Audio:** Reproducción de alta fidelidad consumiendo recursos desde un servidor **MinIO (S3 Compatible)**.
* 📊 **Analytics:** Visualización de contadores de reproducción (Backend tracking).
* 📂 **Gestión de Biblioteca:**
    * Sistema de "Me Gusta" con persistencia inmediata.
    * Exploración profunda por Artista (Top Tracks + Álbumes).
    * Búsqueda global filtrada por entidad.

---

## 🔗 Contexto del Sistema (Backend)

> *Este repositorio contiene únicamente el código fuente del cliente Android.*

Esta aplicación es parte del proyecto **Resonant**, una arquitectura Full Stack diseñada por mí que incluye:
* **Backend:** API Monolítica en **.NET 8 / C#**.
* **Infraestructura:** Despliegue contenerizado con **Docker** y **Nginx**.
* **Almacenamiento:** **MinIO** para gestión distribuida de blobs (archivos .mp3 y portadas).
* **Data Science:** Script de **Python** para análisis de patrones de escucha.

---

## 👤 Autor

**Ionut Leonardo Cojoaca**
*Ingeniero de Software Full Stack (.NET & Mobile)*

[![LinkedIn](https://img.shields.io/badge/LinkedIn-0077B5?style=for-the-badge&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/ionut-leonardo-cojoaca/)
[![GitHub](https://img.shields.io/badge/GitHub-100000?style=for-the-badge&logo=github&logoColor=white)](https://github.com/lonutLeonardoCojoaca)
