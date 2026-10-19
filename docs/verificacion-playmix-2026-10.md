Eres el agente de Android en Windows (Android Studio) del proyecto Resonant. Lee primero `AGENTS.md`. En el mini PC (Linux) se ha escrito, **sin compilar**, el código de 4 cambios de Playmix. Lo escribieron 4 agentes en paralelo sobre los mismos ficheros: son unos 110 ficheros tocados, entre nuevos y editados. Los planes completos (proposal, design, specs y tasks) viven solo en el mini PC (`openspec/changes/<cambio>/`, fuera de git). Este documento resume lo necesario; si te falta algún detalle, pídemelo.

Tu trabajo:
1. Que compile.
2. Que pasen los tests.
3. Revisar las zonas de riesgo.
4. Darme la lista de pruebas en dispositivo con lo que has visto.

**No cambies el comportamiento diseñado.** Si algo no cuadra, explícamelo antes de rediseñarlo. Nada de commits ni push salvo que te lo pida.

## 1. Compilar y tests

- `./gradlew.bat compileDebugKotlin` y luego `./gradlew.bat testDebugUnitTest`.
- Arregla los errores de compilación con el cambio mínimo. Lo más probable es que fallen:
  - `PlaymixSongTransitionAdapter`: una `var isReadOnly` con el mismo nombre que un parámetro del constructor.
  - `SavedFragment`: el cambio a `ConcatAdapter(list)` (sección "Me gustan" de Playmix).
  - `PlaymixSortBottomSheet`: el código de `Chip`/`ChipGroup`.
  - Cualquier código que construya por posición `RenderedTransition.Plan`, `PlaymixRenderDTO`, `MixDocumentDTO` o `MixEditorV2State`, que ganaron campos nuevos al final con valor por defecto.
  - `RenderedTransition.planFor(...)`: tiene parámetros por defecto que llaman a `OfflineClips.localUriFor(...)` y `CapabilitiesManager.isPlaymixExtendedSyncEnabled()`. En tests JVM puede fallar si alguno necesita `Context`. En ese caso, que los tests pasen los parámetros explícitos; no cambies la lógica.
  - Dos agentes tocaron el mismo sitio en `MusicPlaybackService.kt`, `TransitionManager.kt`, `PlaymixDetailFragment.kt`, `PlaymixOptionsBottomSheet` (y su XML) y `PlaymixModels.kt`. Busca declaraciones duplicadas, imports que falten y llaves descolocadas.
- Room pasa a **v7** (`MIGRATION_6_7`, tablas `offline_playmix`, `offline_playmix_clip` y `offline_playlist`). Comprueba que el esquema de las entidades coincide con el SQL de la migración. Si no coincide, Room fallará al abrir la base de datos en el dispositivo.

## 2. Revisión de código (antes de probar)

Lee con calma estas zonas, que son las de máximo cuidado según `AGENTS.md`:
- **`MusicPlaybackService`:**
  - `seekTo`: cuatro cambios conviven ahí: límite de la barra, cancelación de la preparación, cancelación de la rampa de tempo y relevo.
  - `restorePlaybackState`: límite del seek y recarga de mezclas.
  - `prepareForManualQueueMutation` y `finishQueueMutation`: índice de mezclas de la cola.
  - `calculateTriggerPosition`, `handleTransitionTrigger` y `resolvePlaymixPlan`.
  - `publishPosition`.
- **`TransitionManager`:**
  - las ramas `extendedSync` (planned, engine, rendered) y `cancelPreparation()`;
  - el clip local (`localUri`) frente a la URL firmada.
- Todo acceso a ExoPlayer, `PlaybackStateRepository` o `PlaybackQueue` debe ir en Main.

## 3. Qué hace cada cambio y qué probar en el dispositivo

**Primero, sin regresiones.** Antes de nada, con el backend tal cual (los flags del servidor están apagados) y en Playmix existentes, confirma que todo suena y se comporta como antes:
- cola normal con crossfade;
- Playmix con clip renderizado;
- Playmix con el render pendiente (motor en directo);
- saltos rápidos repetidos;
- pausar y reanudar;
- matar el proceso y restaurar;
- Unisound y Live.

Cualquier diferencia ahí es un bug.

### A. `playmix-mix-bounded-seekbar`: barra acotada a la mezcla (solo Android, activo siempre)
En una Playmix, la barra de una canción con mezcla de salida termina en el punto de cambio a la siguiente (p. ej. 2:45 en vez de 3:18).
- **Fin de la barra:** llega a "2:45 / 2:45" y la interfaz pasa a la siguiente canción con su barra.
- **Arrastrar al final:** salta a ~4–8 s antes del cambio (el límite es el disparo de la mezcla − 500 ms) y la mezcla suena completa. Pruébalo con clip, con render pendiente y con una transición clásica.
- **Seek a mitad:** igual que antes.
- **Seek atrás durante la preparación** (los ~6 s antes del clip): la barra sigue avanzando y la mezcla vuelve a sonar completa.
- **Seek con la siguiente canción ya audible:** como antes.
- **Notificación, pantalla de bloqueo, Bluetooth y Android Auto:** duración acotada y seek limitado. Si Android Auto muestra la duración completa, anótalo: lee la ventana del `Timeline`.
- **Sin límite:** última canción, repetir una, playlist normal, Unisound/Live.
- Las letras sin sincronizar avanzan como antes.
- **Restaurar una sesión guardada cerca del final:** empieza en el límite.
- Logs: `📏 Límites Playmix…`, `📏 Seek acotado…`.

### B. `collection-queue-offline`: cola y descargas de playlists y Playmix (activo siempre)
**Cola:**
- "Reproducir a continuación" y "Añadir a la cola" en playlists (propias y ajenas) y en Playmix: se insertan como bloque en orden.
- Una Playmix en la cola suena con **sus mezclas dentro del bloque** y con el crossfade del usuario en los bordes.
- Escuchando una Playmix, añadir, quitar o mover una canción **conserva las mezclas** de las parejas que siguen juntas. Antes se perdían todas.
- Matar el proceso a mitad de una Playmix (y a mitad de una cola con un bloque de Playmix) y reanudar: las mezclas vuelven.
- En Unisound/Live no deja añadir.

**Descargas:**
- Descargar o quitar la descarga de una playlist y de una Playmix, con su progreso.
- En **modo avión**:
  - la Playmix descargada suena entera con sus clips, que salen del caché permanente con el URI `resonant-clip://…`;
  - las transiciones inteligentes entre canciones descargadas funcionan, porque ahora se guarda el análisis.
- Quitar la descarga de una playlist que comparte canciones con un álbum descargado: el álbum sigue completo.
- "Eliminar todo" en Descargas.
- La pantalla de Descargas muestra secciones de Playlists, Playmix y Álbumes con espacio y estado.
- Sincronización: `CollectionSyncWorker` cada 12 h con Wi-Fi, y también al abrir con red.
- **Requiere el backend desplegado:** la detección de cambios de Playmix (`contentVersion`). Sin él, la sincronización de Playmix no detecta cambios.
- Room v7 sobre una instalación v6 con descargas existentes: no se pierde nada.

### C. `playmix-extended-beatmatch`: beatmatching ampliado (necesita el backend desplegado y el flag `playmixExtendedSync` activo)
Con el flag apagado, todo debe ser idéntico a antes. Con el flag activo, en Playmix, parejas con estirado entre 6 y 12 % (p. ej. Mi Gente 105 → Gasolina 96,77; `bRate` 1,085047):
- B suena al tempo de A durante la mezcla.
- Al terminar, B vuelve a su tempo con una rampa suave de ~6 s (`TempoReturn`).
- Pruébalo con clip y con render pendiente.
- Saltar, buscar o pausar durante la rampa: lo siguiente suena a 1,0×.
- Entrar en Live/Unisound justo después: 1,0×.
- La siguiente transición de esa canción suena igual que siempre.
- Una transición ±6 % existente suena idéntica.
- Danza Kuduro 130 → Mi Gente 105 sigue sin beatmatch.
- El editor de Playmix permite activar la sincronía en ese rango.
- La app envía siempre la cabecera `X-Mix-Extended-Sync: 1`.

### D. `playmix-sharing-discovery`: compartir y descubrir (necesita el backend desplegado y el flag `playmixSharing` activo)
- **Crear:** una Playmix nueva sale Privada.
- **Visibilidad:** Privada / Con enlace / Pública desde las opciones.
- **Compartir:**
  - el enlace `https://resonantapp.ddns.net/playmix/{id}`, abierto con otra cuenta, abre la Playmix en solo lectura;
  - el enlace `…/playmix/{id}/transition/{tid}` la abre con la transición destacada;
  - una privada abierta por otra cuenta muestra "Esta Playmix no está disponible".
- **Me gusta:** con contador, y la sección "Me gustan" en la biblioteca.
  - Fallo conocido: si el usuario no tiene Playmix propias pero sí con me gusta, sale "Aún no tienes PlayMix" encima.
- **Copiar a mi biblioteca y duplicar:** la copia suena con sus clips sin esperar renders.
- **Ordenar** (BPM, tonalidad, armónico, sonoridad, título, artista, aleatorio): la vista previa y el resultado aplicado coinciden.
- **Fusionar** 2–5 Playmix.
- **Playmix públicas:** listado desde Explorar.
- **Otra cuenta:** sin edición ni editor de transiciones.

## 4. Qué devolverme
- Lista de errores de compilación y cómo los arreglaste: fichero y cambio.
- Resultado de los tests.
- Por cada escenario: OK / falla, con logs (`adb logcat`) si falla.
- Cualquier sitio donde dos cambios se pisen o donde el código no haga lo que dice el plan.
