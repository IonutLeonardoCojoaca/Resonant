# GenreArtistsFragment - Pantalla de Artistas por Género

## 📋 Descripción

Se ha implementado una pantalla moderna y reactiva para mostrar todos los artistas asociados a un género específico. La pantalla incluye:

- ✨ **Diseño moderno con gradiente dinámico** basado en los colores del género
- 🎨 **Efectos parallax** en el scroll para una experiencia fluida
- 📱 **Grid responsive** de 3 columnas para mostrar artistas
- ⚡ **Estados de carga, error y vacío** bien definidos
- 🔄 **Animaciones suaves** de entrada y transición
- 🎯 **Navegación con transiciones compartidas** hacia el perfil del artista

## 🏗️ Arquitectura Implementada

### 1. **API Service** (`ArtistService.kt`)
Se agregó el endpoint para obtener artistas por género:

```kotlin
@GET("api/Artist/GetByGenreId")
suspend fun getArtistsByGenreId(@Query("genreId") genreId: String): List<Artist>
```

### 2. **ViewModel** (`GenreArtistsViewModel.kt`)
ViewModel que gestiona:
- ✅ Carga de artistas por género
- ✅ Estados de loading/error
- ✅ Manejo de errores con mensajes descriptivos

### 3. **Fragment** (`GenreArtistsFragment.kt`)
Características principales:
- Header con gradiente dinámico del género
- Contador de artistas
- Grid de artistas 3x3
- Scroll parallax en la imagen de header
- TopBar que aparece/desaparece con fade según scroll
- Estados: Loading, Error, Empty, Success

### 4. **Layout** (`fragment_genre_artists.xml`)
Componentes del diseño:
- Header grande con gradiente personalizado
- Nombre del género en grande y bold
- Contador de artistas debajo del nombre
- RecyclerView en grid 3 columnas
- Indicador de carga circular
- Estado vacío con icono y mensaje
- TopBar con botón de retroceso

## 🚀 Navegación

### Desde ExploreFragment
Cuando el usuario hace clic en un género en la pantalla Explore:

```kotlin
genreAdapter = GenreAdapter(emptyList()) { selectedGenre ->
    val bundle = Bundle().apply {
        putString("genreId", selectedGenre.id)
        putString("genreName", selectedGenre.name)
        putString("genreGradientColors", selectedGenre.gradientColors)
    }
    findNavController().navigate(
        R.id.action_exploreFragment_to_genreArtistsFragment,
        bundle
    )
}
```

### Hacia ArtistFragment
Cuando el usuario hace clic en un artista:

```kotlin
artistsAdapter.onArtistClick = { artist, sharedImage ->
    val bundle = Bundle().apply {
        putString("artistId", artist.id)
        putString("artistName", artist.name)
        putString("artistImageUrl", artist.url)
        putString("artistImageTransitionName", sharedImage.transitionName)
    }
    val extras = FragmentNavigatorExtras(
        sharedImage to sharedImage.transitionName
    )
    findNavController().navigate(
        R.id.action_genreArtistsFragment_to_artistFragment,
        bundle,
        null,
        extras
    )
}
```

## 🎨 Características de Diseño UI/UX

### 1. **Header Dinámico**
- Gradiente personalizado según los colores del género (`gradientColors`)
- Nombre del género en 48sp bold
- Efecto parallax al hacer scroll (factor 0.3)
- Animación de entrada con scale y alpha

### 2. **TopBar Inteligente**
- Aparece con fade cuando haces scroll hacia abajo
- Muestra el nombre del género cuando el header desaparece
- Background con alpha progresivo (0 a 255)
- Botón de retroceso siempre visible

### 3. **Grid de Artistas**
- 3 columnas en GridLayoutManager
- Usa el `ArtistAdapter` existente en modo GRID
- Imágenes circulares de artistas
- Click con transición compartida hacia ArtistFragment

### 4. **Estados de la UI**

#### Loading
- CircularProgressIndicator centrado
- Color: `secondaryColorTheme`

#### Success
- Grid visible con artistas
- Contador actualizado: "1 artista" o "X artistas"

#### Empty
- Icono de usuario con alpha 0.3
- Mensaje: "No hay artistas en este género"

#### Error
- Mismo layout que Empty
- Mensaje de error descriptivo

## 📊 Flujo de Datos

```
Usuario selecciona género en ExploreFragment
    ↓
GenreArtistsFragment recibe genreId
    ↓
ViewModel llama a getArtistsByGenreId(genreId)
    ↓
API retorna List<Artist>
    ↓
ViewModel actualiza LiveData
    ↓
Fragment observa y actualiza UI
    ↓
Usuario hace click en artista
    ↓
Navegación a ArtistFragment con transición
```

## 🔧 Uso

Para navegar a esta pantalla desde cualquier Fragment:

```kotlin
val bundle = Bundle().apply {
    putString("genreId", "tu-genre-id-aqui")
    putString("genreName", "Rock")
    putString("genreGradientColors", "#FF47B3,#8A2387")
}
findNavController().navigate(
    R.id.action_xxx_to_genreArtistsFragment,
    bundle
)
```

**Nota:** Asegúrate de agregar la acción de navegación en `nav_graph.xml` desde tu fragment origen.

## ✅ Checklist de Implementación

- [x] Endpoint API agregado al `ArtistService`
- [x] ViewModel creado con estados
- [x] Fragment implementado con lógica completa
- [x] Layout XML diseñado
- [x] Navegación agregada al `nav_graph.xml`
- [x] ExploreFragment actualizado para navegar
- [x] Transiciones compartidas configuradas
- [x] Estados de error/loading/empty implementados
- [x] Animaciones de entrada configuradas
- [x] Scroll parallax implementado

## 🎯 Mejoras Futuras Opcionales

1. **Filtros y Ordenamiento**: Agregar opciones para ordenar artistas por nombre, popularidad, etc.
2. **Búsqueda**: Implementar búsqueda local dentro de los artistas del género
3. **Pull to Refresh**: Agregar SwipeRefreshLayout para recargar datos
4. **Paginación**: Implementar paginación si el número de artistas es muy grande
5. **Skeleton Loading**: Usar placeholders animados en lugar del indicador de carga

## 📝 Notas Técnicas

- El gradient background se aplica dinámicamente parseando el string `gradientColors` del modelo `Genre`
- Se usa el mismo `ArtistAdapter` que ya existe en el proyecto en modo `VIEW_TYPE_GRID`
- La navegación usa transiciones compartidas para una experiencia más fluida
- El TopBar usa el mismo sistema de fade que `ArtistFragment` para consistencia

---
**Desarrollado por**: Antigravity AI
**Fecha**: 2026-02-02
