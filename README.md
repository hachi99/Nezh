# Nezh

**Tu música. Tus reglas.**

Nezh es un reproductor de música independiente para Android, creado alrededor de una idea sencilla: tu música debe pertenecerte.

Diseñado para quienes prefieren tener su propia colección musical, organizarla a su manera y escucharla sin distracciones innecesarias.

Sin suscripciones de streaming. Sin algoritmos que decidan qué deberías escuchar. Solo tu música, a tu manera.

## Acerca del proyecto

Nezh es un reproductor de música local enfocado en bibliotecas personales, control del usuario y una experiencia de reproducción sencilla.

En lugar de analizar todo el almacenamiento del dispositivo, Nezh permite seleccionar exactamente qué carpetas formarán parte de la biblioteca musical.

De esta manera, la aplicación mantiene el control en manos del usuario y evita mezclar música con archivos de audio de otras aplicaciones, como grabaciones de voz, descargas o mensajes.

El objetivo es ofrecer un reproductor musical para Android confiable, ligero y moderno.

## Principios del proyecto

- **Tu música, tus reglas.** Tú decides qué contenido forma parte de tu biblioteca.
- **Enfoque local.** Tu música está almacenada en tu dispositivo y disponible para reproducirse sin depender de servicios de streaming.
- **Privacidad.** Sin seguimiento ni recopilación innecesaria de datos.
- **Simplicidad.** Una experiencia centrada en escuchar música, sin funciones innecesarias.
- **Control.** Tus carpetas, tu música y tus hábitos de escucha.

## Características

### Implementadas

- Reproducción de música local.
- Administración de biblioteca mediante selección de carpetas.
- Selección de múltiples carpetas musicales mediante Android Storage Access Framework (SAF).
- Exploración recursiva de carpetas y subcarpetas.
- Persistencia de permisos de acceso a las carpetas seleccionadas.
- Reproducción en segundo plano.
- Controles de reproducción mediante Android MediaSession.
- Cola de reproducción en memoria.
- Exclusión de directorios `.nomedia`.
- Prevención de entradas duplicadas en la biblioteca.
- Manejo básico de carpetas y archivos inaccesibles.

### En desarrollo y planeadas

- Mejor compatibilidad con diferentes formatos de audio.
- Mejoras en la extracción y presentación de metadatos.
- Optimización del análisis de bibliotecas musicales.
- Mejoras en la estabilidad de reproducción.
- Organización alfabética de canciones con separadores por letra.
- Opciones adicionales de organización de la biblioteca.

Las características y prioridades pueden cambiar conforme avance el desarrollo.

## Tecnologías utilizadas

Nezh está siendo desarrollado para Android utilizando:

- **Kotlin:** lenguaje principal de programación.
- **Jetpack Compose:** construcción de la interfaz de usuario.
- **Android Media3 / ExoPlayer:** motor de reproducción de audio.
- **MediaSessionService:** reproducción en segundo plano y controles multimedia del sistema.
- **Storage Access Framework (SAF):** acceso controlado por el usuario a las carpetas musicales.
- **Kotlin Coroutines y Flow:** operaciones asíncronas y gestión reactiva del estado.

## Formatos de audio

La compatibilidad con diferentes formatos es uno de los aspectos que se encuentran en desarrollo.

Formatos contemplados:

| Prioridad | Formatos |
|---|---|
| Esenciales | MP3, FLAC, M4A / AAC |
| Adicionales | WAV, OGG Vorbis, Opus |

La compatibilidad real depende de los decodificadores disponibles en Android y del dispositivo utilizado.

## Estado del proyecto

🚧 **Desarrollo temprano — Etapa MVP**

Nezh se encuentra actualmente en desarrollo activo.

Las funciones principales de reproducción y administración de bibliotecas mediante carpetas ya están implementadas. Sin embargo, todavía se están trabajando aspectos como la compatibilidad de formatos, los metadatos, el rendimiento y la estabilidad de reproducción.

Es posible encontrar cambios frecuentes, características incompletas y errores.

## Filosofía

La música es personal.

Nezh nace de la idea de que escuchar música no debería requerir suscripciones, recomendaciones, cuentas o algoritmos.

Tú eliges tu música. Tú decides dónde almacenarla. Tú decides cómo escucharla.

**Tu música. Tus reglas.**

---

*Nezh es un proyecto independiente y no está afiliado a ningún servicio de streaming musical ni fabricante de hardware.*