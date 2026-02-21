# 🔧 Fix: Scroll y Centrado en TopChartsFragment

## 🐛 Problemas Reportados

### 1. Scroll no se reseteaba al cambiar de filtro
**Síntoma**: Al cambiar de "Top Diario" a "Top Semanal", la lista se quedaba en la posición scrolleada del período anterior, dejando canciones arriba y abajo.

**Causa**: Se intentaba hacer `scrollToPosition(0)` y `setExpanded(true)` ANTES de que el RecyclerView tuviera los nuevos datos cargados.

**Solución**:
- Creado flag `shouldResetScroll` que se activa al pulsar un botón de filtro
- El reset se ejecuta en el callback de `submitList()` del observer, cuando el DiffUtil ha terminado de actualizar el RecyclerView
- Se hace scroll a posición 0 y se expande el AppBarLayout con animación

```kotlin
// Observer actualizado
viewModel.songs.observe(viewLifecycleOwner) { songs ->
    songAdapter.submitList(songs) {
        // Callback después de que DiffUtil termina
        if (shouldResetScroll) {
            rvSongs.scrollToPosition(0)
            appBarLayout.setExpanded(true, true)
            shouldResetScroll = false
        }
    }
}

// En el click
btnDaily.setOnClickListener {
    shouldResetScroll = true // Se ejecutará después de cargar datos
    viewModel.loadChartData(false, 0)
}
```

### 2. Título del Toolbar no estaba centrado
**Síntoma**: El texto "Top Diario" en el Toolbar se salía por la derecha de la pantalla.

**Causa**: Cuando pones múltiples elementos en un `Toolbar`, Android no calcula correctamente el centrado del `TextView` con `layout_gravity="center"`.

**Solución**:
- Envuelto el contenido del Toolbar en un `FrameLayout`
- El `TextView` ahora ocupa `match_parent` de ancho
- Usamos `android:gravity="center"` para centrar el texto dentro del TextView
- Agregamos `paddingStart="48dp"` y `paddingEnd="48dp"` para compensar el espacio del botón "Atrás"
- Agregamos `app:contentInsetStart="0dp"` al Toolbar

```xml
<Toolbar app:contentInsetStart="0dp">
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">
        
        <ImageButton ... /> <!-- Botón atrás a la izquierda -->
        
        <TextView
            android:layout_width="match_parent"
            android:gravity="center"
            android:paddingStart="48dp"
            android:paddingEnd="48dp" /> <!-- Centrado real -->
            
    </FrameLayout>
</Toolbar>
```

---

## ✅ Resultado

1. **Scroll**: Al cambiar de período, la lista automáticamente vuelve al inicio (posición 0) y el header se expande con animación suave.

2. **Centrado**: El título en el Toolbar ahora está perfectamente centrado visualmente, sin importar el tamaño del texto.

Ambos issues resueltos! 🚀
