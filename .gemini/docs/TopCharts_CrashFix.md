# 🐛 Fix: Crash en TopChartsFragment

## 🚨 El Problema
La aplicación crasheaba al abrir la pantalla de **Éxitos** o **Tendencias**.

**Causa:** `TopChartsFragment.kt` definía `rootLayout` como `ConstraintLayout`, pero en la actualización de UI reciente cambiamos el layout raíz a `CoordinatorLayout`.

```kotlin
// Código que causaba el crash (ClassCastException)
private lateinit var rootLayout: ConstraintLayout 
// ...
rootLayout = view.findViewById(R.id.rootLayout) // <-- El ID ahora pertenece a un CoordinatorLayout
```

## ✅ Solución
Se actualizó el tipo de la variable en el Fragmento:

```kotlin
// Código corregido
private lateinit var rootLayout: androidx.coordinatorlayout.widget.CoordinatorLayout
```

Esta corrección alinea el código Kotlin con el nuevo diseño XML, permitiendo que la vista se infle y se asigne correctamente sin errores de tipo.

Ahora la pantalla debería abrirse correctamente mostrando el nuevo diseño con *collapsing header*.
