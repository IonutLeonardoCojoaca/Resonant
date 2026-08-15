# AGENTS.md

Fuente de verdad común para cualquier agente de programación (Claude Code, Codex, u otros) que trabaje en Resonant. Todo lo que contiene está verificado directamente en el repositorio — no es aspiracional.

## Precedencia

1. El código existente de la feature que se está tocando es la referencia principal.
2. Este documento define valores por defecto, no una razón para reescribir código que ya funciona.
3. Los requisitos explícitos de la tarea o del usuario tienen prioridad sobre cualquier regla de aquí.
4. Ante la duda, preferir el cambio más pequeño que resuelva la tarea.

## Resumen del proyecto

App Android nativa en Kotlin, módulo único `:app`. Cliente de streaming de música con un asistente conversacional ("Aria"), reproducción vía Media3/ExoPlayer, backend propio consumido por Retrofit.

## Stack y versiones

- Kotlin 2.0.21, AGP 8.9.1, Gradle Wrapper 8.11.1
- compileSdk 36, minSdk 33, targetSdk 35
- Hilt 2.51.1 (KSP), Room 2.8.4 (kapt), Media3 1.10.1
- Retrofit 2.9.0 + OkHttp 4.12.0 + Gson
- Sin Compose. UI 100% XML + ViewBinding.
- Sin ktlint/detekt/editorconfig configurado — solo Android Lint por defecto.

## Estructura de paquetes

- `managers/` — capa tipo repositorio del código legacy (envuelven Retrofit, algunos con caché en memoria).
- `ui/{viewmodels,adapters,fragments,activities,bottomsheets,dialogs,customviews,components,views}` — capa de presentación legacy (MVVM manual, LiveData).
- `playback/` — cola, estado de reproducción, persistencia, integración con Media3.
- `services/` — `MusicPlaybackService` (MediaSessionService) y el servicio de descargas.
- `data/{network,network/services,local,local/dao,local/entities,models}` — Retrofit, Room, DTOs/modelos.
- `feature/collabfinder/{data,domain,ui,di}` — única feature con Clean Architecture + Hilt.
- `aria/`, `utils/`, `workers/` — soporte transversal.

## Coexistencia legacy vs. arquitectura nueva

La mayoría del código (incluido `aria/`) sigue el patrón legacy: `Manager` (objeto singleton o clase instanciada por pantalla) + `ViewModel`/`AndroidViewModel` + `LiveData`, con DI manual (`ViewModelProvider(this)` / `ViewModelProvider(requireActivity())`, `SomeManager(context)`).

La única excepción verificada es `feature/collabfinder/*`, que usa Hilt (`@HiltViewModel`, `@Inject constructor`, módulos `@Module @InstallIn`) y capas `data/domain/ui/di` al estilo Clean Architecture.

## Cómo decidir qué patrón seguir

- Si el archivo que se toca vive bajo `feature/collabfinder/` → seguir Hilt + Clean Architecture.
- Si vive en cualquier otro sitio (incluido `aria/`) → seguir el patrón legacy Manager+LiveData ya presente.
- No mezclar los dos patrones dentro de la misma feature salvo que el objetivo explícito de la tarea sea migrarla.

## Reglas para arreglar bugs

- Investigar la causa raíz antes de tocar código — no aplicar el primer parche que "hace desaparecer" el síntoma.
- Cambios pequeños y enfocados en el problema reportado.
- No aprovechar para hacer refactors no relacionados, aunque se vea código mejorable al lado.
- Reutilizar soluciones que ya existen correctamente en otra parte del proyecto (p. ej. el patrón de caché con expiración ya usado en varios `Manager`, `ListAdapter`+`DiffUtil` con payloads en `SongAdapter`, `viewLifecycleOwner.lifecycleScope` + `repeatOnLifecycle`) en vez de inventar uno nuevo.

## Reglas para cambios de rendimiento

- No modernizar código adyacente al que se está optimizando.
- Verificar el problema leyendo el código real (call sites, hilos, timing) — no asumir dónde está el cuello de botella.
- Conservar el comportamiento observable exacto; un cambio de rendimiento no debe alterar qué hace la app, solo cómo de rápido/en qué hilo lo hace.
- Compilar y correr los tests siempre. Para cambios en `playback/` o `services/MusicPlaybackService.kt`, compilar y testear **no es suficiente** — ver la sección Media3/Playback.

## Lifecycle y coroutines

- En Fragments, para colectar `Flow`/`StateFlow` usar siempre `viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }` — nunca `lifecycleScope` a secas para algo atado a la vista (fuga/colector duplicado al recrear la vista). Referencia ya correcta en `HomeFragment`, `AlbumFragment`, `SongFragment`.
- `MusicPlaybackService.serviceScope` es `Dispatchers.IO`. Todo lo que toca `ExoPlayer`, `PlaybackStateRepository` o `PlaybackQueue` salta explícitamente a `Dispatchers.Main` primero (`withContext(Dispatchers.Main)`) — es una convención estricta en ese archivo, no opcional.
- Evitar `GlobalScope`. No se usa en ningún sitio del proyecto.

## RecyclerView

- `ListAdapter` + `DiffUtil` es la preferencia para adapters **nuevos**.
- No migrar un adapter existente a `ListAdapter`/`DiffUtil` solo porque se está tocando por otro motivo (p. ej. `AlbumAdapter`/`ArtistAdapter` siguen usando `RecyclerView.Adapter` plano con `notifyDataSetChanged` — no arreglarlo de paso salvo que sea el objetivo de la tarea).
- Listeners de click/long-click: crearlos una vez en `init` resolviendo la posición vía `bindingAdapterPosition`, no dentro de `bind()`.
- Preferir `notifyItemChanged(index, payload)` dirigido sobre `notifyDataSetChanged()` cuando solo cambia un subconjunto conocido de items.

## Networking

- `ApiClient` (objeto singleton) expone los servicios Retrofit; construye el `Retrofit` una sola vez con double-checked locking.
- `NetworkClientProvider` posee los recursos OkHttp compartidos: `mediaClient()` (sin interceptor de auth — para URLs firmadas de object storage) y `apiClient(...)` (derivado de `mediaClient` vía `newBuilder()`, con `AuthInterceptor` + `TokenAuthenticator`). No crear `OkHttpClient()` nuevos sueltos; derivar de `ApiClient.getMediaHttpClient()` si se necesita un cliente para descargas fuera del flujo de Retrofit.
- `SessionManager.getInstance(context)` es un singleton — no instanciar `SessionManager(context, baseUrl)` directamente.
- Patrón de caché en memoria con expiración (ver `ArtistManager`, `FavoritesViewModel`, `HomeViewModel`): mapa por id + timestamp + constante de duración; es el patrón a replicar si una pantalla necesita evitar refetch.

## Room

- Uso único: descargas offline (`DownloadedSongDao` / `AppDatabase`, versión 5 actualmente).
- `SELECT *` no está prohibido — es aceptable cuando el caller necesita la mayoría de las columnas. Evitarlo cuando el caller solo necesita unas pocas (p. ej. solo el `songId`), especialmente en queries frecuentes o que corren en un `Flow` colectado por varias pantallas (ver `getAllSongIdsByUser` frente a `getAllByUser`).
- Preferir queries batched (`WHERE x IN (:ids)`) sobre lanzar una query por elemento dentro de un `forEach`.
- Envolver escrituras multi-paso relacionadas en un método `@Transaction` del DAO.
- Nunca `fallbackToDestructiveMigration()`. Toda subida de `version` en `AppDatabase` necesita su `Migration` explícita.

## Media3 / Playback

- `MusicPlaybackService` asume acceso **Main-thread-only** a `ExoPlayer`, `PlaybackStateRepository` y `PlaybackQueue`. Cualquier código nuevo que los toque debe ejecutarse en Main, siguiendo el mismo patrón que el resto del archivo.
- `PlaybackStateStore` es la referencia de cómo mover trabajo caro (serialización, I/O) fuera de Main sin romper el orden: snapshot inmutable construido síncronamente en el hilo llamante + número de secuencia monotónico que descarta escrituras obsoletas.
- Zonas de máximo cuidado: `MusicPlaybackService`, `TransitionManager`, el crossfade, la restauración de cola (`restorePlaybackState`) y la persistencia de estado de reproducción. Cambios aquí deben ser mínimos y justificados, no reescrituras oportunistas.
- **Compilar y pasar los tests unitarios no es suficiente para dar por bueno un cambio de comportamiento en playback.** El agente debe indicar explícitamente qué escenarios manuales hay que probar en dispositivo/emulador antes de considerar el cambio terminado (p. ej. skips rápidos repetidos, crossfade completo, restaurar sesión tras matar el proceso, pausar/reanudar, cambiar de red a mitad de canción).

## Testing

- JUnit4 + `kotlinx-coroutines-test`. **Sin Mockito ni MockK** — el proyecto usa fakes/fixtures escritos a mano (ver `PlaybackFixture` en `AriaPlaybackActionHandlerTest`). Seguir ese patrón, no introducir una librería de mocking.
- Tests instrumentados (`androidTest`) usan Espresso + `fragment-testing`.

## Comandos Gradle de verificación

- `./gradlew.bat compileDebugKotlin` — compilación.
- `./gradlew.bat testDebugUnitTest` — tests unitarios.
- Siempre a través del Gradle Wrapper del proyecto, nunca un `gradle` global.

## Dependencias y versiones que no se tocan sin pedirlo

Gradle Wrapper, AGP, Kotlin, `compileSdk`/`minSdk`/`targetSdk`, y las versiones fijadas en `gradle/libs.versions.toml` o directamente en `app/build.gradle.kts`. No añadir dependencias nuevas salvo que la tarea las requiera explícitamente.

## Reglas Git

No hacer commit, push, rebase, amend, ni ninguna otra operación Git salvo petición explícita del usuario. Nunca force-push ni saltar hooks (`--no-verify`).

## Archivos sensibles/generados

No modificar salvo petición explícita — sin asumir que todos contienen secretos, pero cambiarlos sin pedir puede romper el build, la firma de la app o la configuración de Firebase:
- `app/google-services.json`, `debug.keystore`, `local.properties`.
- `build/`, `.gradle/`, y cualquier código generado (`R`, `BuildConfig`, clases de Safe Args).

## Reglas explícitas

- No modernizar código legacy automáticamente.
- No migrar `LiveData` a `Flow` solo por ser más moderno.
- No migrar XML/ViewBinding a Compose.
- No introducir Hilt/Clean Architecture en una feature legacy durante un bugfix pequeño.
