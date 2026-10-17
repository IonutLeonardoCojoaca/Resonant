# Handoff: motor de transiciones v2 (estilo Spotify Mix) — depuración en Android

Para: el agente que trabaja en Windows con Android Studio sobre este repo.
Fecha: 2026-10-17. Autor: el agente del servidor (mini PC), que tiene el backend, los workers de render y la BD.

Lee también `AGENTS.md` (reglas del repo). Este documento solo cubre el motor de transiciones v2.

---

## 1. Qué buscamos y en qué punto estamos

Las transiciones entre canciones de una Playmix tienen que sonar como Spotify Mix: mezclas largas, a compás, con
el bajo intercambiado entre A y B, sin huecos ni picos de volumen. Además, lo que se dibuja en el editor tiene que
ser exactamente lo que suena.

- **Servidor: desplegado y verificado.** Las 30 transiciones de las Playmix están en "Automática" v2. Su clip
  renderizado está medido: en 28 de 30, el nivel no cae más de 7 dB durante la mezcla.
- **Android: código escrito en el mini PC, pero nunca compilado allí** (prohibido compilar en el mini PC). Tu
  trabajo es compilar, pasar los tests, probar en dispositivo o emulador, y arreglar lo que falle.

Lo que el usuario ve hoy (versión anterior a los arreglos de abajo):
- La Automática guardada suena bien: usa el clip del servidor.
- Al cambiar compases, preset o un carril y escucharlo, la mezcla no coincide con el visualizador o no llega a
  hacerse. A veces A termina y B entra segundos después, sin mezcla.
- Ruido blanco en el momento en que fallaba una mezcla.

## 2. Reglas para ti

- Compila con `gradlew.bat compileDebugKotlin` y pasa los tests con `gradlew.bat testDebugUnitTest`.
- **No toques el backend ni la BD.** Son producción y viven en el mini PC. Si crees que algo del servidor está
  mal, escríbelo en la sección 9 (o en un archivo nuevo junto a este) para que lo revise el agente del servidor.
- Cambios en `playback/` y `MusicPlaybackService`: compilar y pasar tests no basta. Prueba los escenarios de la
  sección 7 en dispositivo o emulador.
- Sin Mockito ni MockK: fakes escritos a mano (ver `BusMixerTest`, `CrossfadeEditorViewModelV2Test`).
- No hagas commit ni push salvo que el usuario lo pida.

## 3. Cómo suena una transición de Playmix (orden de decisión)

`MusicPlaybackService.calculateTriggerPosition()` y `startTransition` eligen el plan para la pareja A → B, en este
orden:

1. **Escucha del editor sin guardar.** `TransitionPreview.documentFor(A, B)` → `previewPlanFor`. El documento que
   está en pantalla se mezcla en el móvil con el motor de salida única. No hace falta guardar.
2. **Documento de Studio sin clip listo.** `studioPlanFor(transition)`: hay `mixDocument` y
   `render.status != ready`, así que se mezcla en el móvil igual que en el punto 1.
3. **Clip renderizado listo.** `RenderedTransition.planFor(transition)`: se reproduce A, se empalma con el clip FLAC
   del servidor `[preroll A | mezcla | postroll B]` (fundido de 30 ms) y se pasa a B en el postroll.
4. **Legado:** puntos y crossfade antiguos, o "blend automático" (`TransitionPlanner`).

El disparo ocurre `TRANSITION_LEAD_MS` antes de `doc.a.startMs`. En el clip renderizado, `PREPARE_LEAD_MS` (6 s)
antes.

Las rutas 1 y 2 (mezcla en el móvil) son las que fallaban. La ruta 3 funciona.

## 4. Mezcla en el móvil: motor de salida única

Hay un solo `AudioTrack` float. Los ExoPlayers de A y de B no tienen salida propia: escriben su PCM en el motor.

| Pieza | Archivo | Qué hace |
|---|---|---|
| `MixOutputEngine` | `playback/engine/MixOutputEngine.kt` | Dueño del AudioTrack. Hilo `URGENT_AUDIO`, bloques de 448 frames a 44,1 kHz. Contiene `MixSession` y `BusSource`. |
| `BusRenderersFactory` | `playback/engine/BusAudioOutput.kt` | `DefaultAudioSink` en 16 bits con cadena `[]`, `SilenceSkipping`, `Sonic@44.1k` (Sonic cambia la velocidad de B y remuestrea). Lo envuelve un `ForwardingAudioSink`. |
| `BusAudioOutput` | ídem | `AudioOutput` de Media3 que entrega el PCM a una `BusSource` y calcula la posición de medio (µs de la canción) de cada frame. |
| `BusSource` | `MixOutputEngine.kt` | Búfer circular por player (`RING_FRAMES` ≈ 0,5 s), con anclas (frame → posición de medio). |
| `BusMixer` | `playback/engine/BusMixer.kt` | Lógica pura del bloque: suma las fuentes y, si hay `MixSession`, aplica `MixDsp` a la muestra exacta. Limitador a −1 dBFS. |
| `MixSession` | `MixOutputEngine.kt` | Estados WAITING → RUNNING → DONE/FAILED. En WAITING espera a que A llegue a `doc.a.startMs` (`framesUntilStart`) y a que B tenga audio (`bReady`). |
| `MixDsp` | `playback/dsp/MixDsp.kt` | DSP del documento v2 por muestra, sin asignaciones de memoria. Mismo contrato que el render Python (tests de paridad `MixDspTest` con `resources/mix_v2_dsp`). |
| `TransitionManager` | `managers/TransitionManager.kt` | `beginEngineRun` / `tickEngineRun` / `completeEngineRun` / `failEngineRun` / `cancelEngineRun`. Prepara B con un seek a `doc.b.startMs` y lanza la sesión. |
| `TransitionEngineFlag` | `playback/engine/TransitionEngineFlag.kt` | Motor activo si `GET api/capabilities` → `features.transitionEngineV2` (preferencias; se aplica al reiniciar el servicio). |

## 5. Documento de mezcla v2 (contrato)

Cabecera de petición `X-Mix-Doc-Version: 2`, la pone `ClientCompatibilityInterceptor` si el flag está activo. Sin
ella, el backend sirve v1.

```json
{ "version": 2, "durationMs": 7619, "bars": 4, "beatSync": true,
  "a": { "startMs": 159201 }, "b": { "startMs": 20, "offsetMs": 0 },
  "preset": { "code": "automatica", "version": 2 },
  "selection": { "volume": "overlap", "eq": "bass-swap-center", "filter": "lpf-in-out", "fx": "none", "loop": "none" },
  "lanes": { "a.volume": [{ "t": 0, "v": 1, "curve": "hold" }], "b.eq.low": [ ... ], ... },
  "loop": null, "fx": null }
```

- Tiempos `t` en ms de la línea de tiempo (0 = `a.startMs`).
- B suena a partir de `b.offsetMs`, desde `b.startMs`, a velocidad `bpmA/bpmB` si hay `beatSync`.
- **Carriles:**
  - `a|b.volume` 0..1, con ganancia `sin(πv/2)` y compensación `1/sqrt(max(1, gA²+gB²))`.
  - `a|b.eq.low|mid|high` −40..+6 dB: low shelf a 250 Hz, peaking a 1 kHz con Q 0,7, high shelf a 4 kHz.
  - `a|b.filter` −1..+1, filtro bipolar de 24 dB/oct:
    - lado negativo, paso bajo: `20000·(120/20000)^|v|` Hz;
    - lado positivo, paso alto: `20·(8000/20)^v` Hz.
  - `a.noise` 0..1.
  - Suavizado de 10 ms en todos los carriles; limitador suave por encima de −1 dBFS.
- Contrato completo en el repo infra: `docs/transitions-v2-contract.md` y
  `openspec/changes/spotify-style-transition-engine/`.
- **Generador:** `playback/MixComposer.kt` es el port exacto de `MixComposer.cs` del backend. Su paridad se
  comprueba con `MixComposerTest` y los fixtures de `src/test/resources/mix-v2/` (regenerados hoy en el backend y
  copiados aquí).

**Regla de Automática** (cambiada hoy, ya en producción):
- La ventana termina antes del final del audio de A, así que A suena durante toda la mezcla.
- El centro es el intercambio de graves:
  - en A, el último compás en el que aún tiene bajo (sin pasar de su fin menos media ventana);
  - en B, el compás donde entra su bajo.
- El servidor detecta la entrada y la salida del bajo con las ondas de 3 bandas (`BassProfile.cs`). El port de
  Android no tiene esa información y usa los cue points: es solo el respaldo sin conexión.
- Los filtros de entrada de B empiezan a profundidad 0,45, no a fondo.

**Endpoints que usa el editor:**
- `GET`/`PUT api/playmix/{id}/transitions/{tId}/studio`: documento, revisión y `render.status`
  (`pending | rendering | ready | failed`).
- `GET api/v1/transition-presets`: presets `mix-v2` con su `selection`.
- `GET api/v1/transition-presets/lane-options`: catálogo de opciones por carril.
- Tras un `PUT`, el servidor renderiza el clip en unos 6–10 s.

## 6. Arreglos de hoy (escritos en el mini PC, sin compilar) — lo que tienes que validar

### 6.1 Posición de medio con el desplazamiento del stream (causa principal)
**Síntoma en el log:**
```
MixSession I Mezcla tardía: se desplaza 999999047 ms (compases enteros de A)
MixSession I Mezcla tardía: se desplaza 1904 ms ...   (x8, cada compás)
MixSession I Mezcla en marcha (15238 ms, desplazada 1000012380 ms)
```
**Causa.** `AudioOutput.write(buffer, count, presentationTimeUs)` recibe la marca del *buffer de entrada* en tiempo
de renderer: posición de la canción + el desplazamiento del stream, que es 10¹² µs en el primer item. Se trataba
como posición de la canción. El desplazamiento solo se comunica al sink con
`AudioSink.setOutputStreamOffsetUs` (Media3 1.10.1, lo llama `MediaCodecAudioRenderer`).

**Arreglo** (`BusAudioOutput.kt`):
- `BusRenderersFactory` envuelve el `DefaultAudioSink` en un `ForwardingAudioSink` que guarda en `BusHandle`:
  - `streamOffsetUs`, en `setOutputStreamOffsetUs`;
  - `startMediaUs` = marca del primer `handleBuffer` tras cada `flush`, menos el desplazamiento. Es el mismo
    origen que usa `DefaultAudioSink` para su reloj.
- `BusAudioOutput.anchor()` fija `baseUs` en el primer `write` tras un flush y cuenta frames
  (`baseUs + framesSinceBase·10⁶/44100`). Solo se reancla si la marca de Media3 se aleja más de 200 ms (cambio de
  item en el mismo player, o B a otra velocidad).

**Qué comprobar:**
1. Que `setOutputStreamOffsetUs` llega de verdad antes del primer buffer. Si no, añade un log temporal en el
   override.
2. Que `BusSource.nextMediaTimeUs()` de A coincide con `player.currentPosition` en ±30 ms. Para medirlo, loguea las
   dos cosas en `TransitionManager.tickEngineRun`.
3. El error que introduce el retardo de Sonic al remuestrear 48 kHz → 44,1 kHz (los MP3 del emulador salen a
   48 kHz, como se ve en el log).

### 6.2 Retraso de milisegundos tratado como "tarde" (`MixOutputEngine.kt`, `MixSession.framesUntilStart`)
- La posición de A se conoce por bloques, así que al llegar al inicio salía unos frames negativa y la mezcla se
  movía un compás entero, una y otra vez.
- Ahora hasta `LATE_TOLERANCE_MS` = 60 ms cuenta como a tiempo.
- Más de `MAX_LATE_MS` = 60 s marca `incoherent`, y `BusMixer` hace fallar la sesión en vez de esperar.

Tests en `BusMixerTest`:
- `aFewMillisecondsLateStillStartsNow`;
- `lateStartWaitsForTheNextBarOfA` (actualizado: 100 ms tarde con compás de 200 ms);
- `anIncoherentPositionFailsInsteadOfWaiting`.

### 6.3 B suelta al fallar (posible origen del ruido)
`MixSession.fail()` marca `b.retired = true`. Antes, entre el fallo y la liberación de B en el hilo principal, el
motor mezclaba B como fuente normal.

### 6.4 La transición no se rearmaba tras un fallo (`MusicPlaybackService`)
- `handleTransitionFailed` fija `transitionSuppressedForSongId`, y volver a reproducir o retroceder la misma
  canción no lo limpiaba.
- Ahora, en el runnable del seekbar, si `position < trigger − 1 s`, se limpia.

### 6.5 La Playmix que suena no veía lo guardado en el editor (`MusicPlaybackService` + `CrossfadeEditorFragment`)
- `playmixTransitions` solo se cargaba al iniciar la cola. Tras guardar, se seguía usando el documento y el clip
  viejos: el visualizador mostraba una cosa y sonaba otra.
- Nueva acción `ACTION_PLAYMIX_TRANSITIONS_CHANGED` (`EXTRA_PLAYMIX_ID`). El Fragment la envía al guardar y cuando
  `renderStatus` pasa a `ready` (el ViewModel consulta el estado cada 3 s tras guardar). El servicio recarga si esa
  Playmix es la cola activa.

### 6.6 Editor (hecho antes, también sin probar a fondo)
- Diseño antiguo intacto: cabecera A, visualizador, tiempos de salida y entrada, tarjeta B.
- La onda es `ui/views/MixEditorView.kt`:
  - 3 bandas y rejilla;
  - carriles con color;
  - línea de reproducción roja (`setPlayhead`) y tiempos;
  - arrastre con imán.
- Controles nuevos en `ui/fragments/MixEditorV2Panel.kt` + `res/layout/view_mix_v2_controls.xml` y
  `item_mix_v2_lane.xml`: estado del render, presets, carriles, ventana ±1 compás, beatmatching (`SwitchMaterial`
  estilado), Camelot.
- Lógica pura en `ui/viewmodels/MixEditorV2.kt`; integración en `CrossfadeEditorViewModel` (`v2*`,
  `v2PreviewRequest`, `v2Dirty`, `renderStatus`).
- Vista previa de la Playmix: `ui/bottomsheets/WaveformPreviewBottomSheet.kt`. Con un documento v2 usa
  `MixEditorView` de solo lectura.
- Etiqueta de la tarjeta de transición: `ui/adapters/PlaymixTransitionLabels.kt` ("MEZCLA · Automática · 4
  compases …").

## 7. Cómo verificar en dispositivo o emulador

Filtro de logcat:
```
adb logcat -s TransitionManager MixSession MixOutputEngine BusAudioOutput MusicService TransitionTrigger PlayerListener
```

Secuencia sana (escucha del editor, documento sin guardar):
```
MusicService      🏁 TRIGGER by SeekBar: Pos(...) >= Trigger(...)
TransitionManager 🎚️ Mezcla en el motor preview: A → B (A=<a.startMs>ms, B=<b.startMs>ms, <dur>ms, v2, sync=true)
MixSession        Mezcla en marcha (<dur> ms, desplazada 0 ms)     ← desplazada 0, sin "Mezcla tardía"
TransitionManager 🎉 Mezcla completada en el motor (preview): B
```

**Escenarios** (pareja de prueba: I Love It (feat. Charli xcx) → Levels - Radio Edit, transición
`72100330-b82b-4fcf-a685-b523f8d38080`):

1. **Editor:** cambia los compases (4 → 8), pulsa ▶ sin guardar. Debe salir "desplazada 0 ms" y la línea roja debe
   cruzar la ventana a la vez que se oye la mezcla.
2. **Editor:** cambia el preset y un carril (por ejemplo, Filtro → "Filtro paso alto de salida") y pulsa ▶. Lo que
   suena debe seguir las curvas.
3. **Guardar con la Playmix sonando:** en el log debe salir `🔄 Transiciones de la Playmix … cambiadas en el
   editor: recargando`.
   - Mientras el estado dice "renderizando", la transición se mezcla en el móvil (ruta 2).
   - Al pasar a "renderizada", el clip del servidor (ruta 3).
   - Las dos deben sonar igual.
4. **Fallo y rearme:** fuerza un fallo (por ejemplo, salta lejos durante la espera), retrocede antes del disparo y
   deja sonar. La mezcla debe volver a intentarse.
5. **Regresión:**
   - saltos rápidos repetidos;
   - pausar y reanudar durante la mezcla;
   - matar el proceso y restaurar la sesión;
   - cambiar de red a mitad de canción;
   - Playmix de principio a fin.
6. **Ruido blanco:** si aparece, apunta la transición, el segundo y la ruta (log `Mezcla en el motor …` frente a
   reproducción del clip), y el log completo de ese momento.

## 8. Hipótesis abiertas por si algo sigue mal

- **Encoder delay de MP3.** `DefaultAudioSink` recorta el retardo del codificador (~25 ms). Si A llega
  sistemáticamente 20–30 ms tarde o pronto, el origen `startMediaUs` puede necesitar ese ajuste.
- **B con `beatSync`.** Va a `bpmA/bpmB` vía Sonic, así que su posición de medio contada por frames deriva y se
  reancla cada ~10 s. B no se usa para temporizar; solo hay que vigilar que no provoque underruns.
- **B sin audio a tiempo.** Si B no está lista al llegar al inicio, `postpone` retrasa la mezcla un compás (hasta
  8 s). En el log no aparece como "tardía". Si pasa a menudo, B se prepara demasiado tarde: mira
  `TransitionManager` (player precargado) y `RenderedTransition.PREPARE_LEAD_MS`.
- **Ruido blanco.** Ningún documento actual tiene `a.noise` ni filtros a fondo, así que no sale de los carriles.
  Candidatos:
  - B suelta tras un fallo (ya arreglado);
  - underrun del AudioTrack;
  - una sesión que lee una fuente ya liberada.
  Si se reproduce, revisa `BusMixer.renderSession` y `MixOutputEngine.remove`.

## 9. Notas para el agente del servidor (rellenar si hace falta)

### 2026-10-17 — Los clips renderizados nunca llegan al móvil (ruta 3 rota)

- Las URLs firmadas de los clips (`https://resonantapp.ddns.net/playmix-renders/<transitionId>/<n>.flac?X-Amz-…`)
  devuelven el `index.html` de la web de Resonant (`<!doctype html>… <title>Resonant</title>`), no el FLAC.
  Comprobado en la caché del emulador: todos los clips pedidos hoy (`72100330-…` 1, 2, 7, 23 y 26; `21ec6031-…` 15)
  son ese HTML de 790 bytes. Parece que el proxy de `resonantapp.ddns.net` no enruta `/playmix-renders/` a MinIO y la
  SPA responde a cualquier ruta.
- Efecto en el móvil: el clip falla al prepararse y se caía al crossfade antiguo (`beginLegacyPlaymix`), que ignora el
  documento v2. Eso es lo que el usuario oía como "mezcla del servidor": no cohesionaba.
- Ya cambiado en Android: si el clip falla, un documento v2 se mezcla en el motor del móvil (`beginClipFallback` en
  `TransitionManager`). Hace falta arreglar el enrutado del proxy para que la ruta 3 funcione de verdad.
