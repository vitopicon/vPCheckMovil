# vPCheck Móvil

Versión para móviles Android de vPCheck. Hace el mismo recorrido que la versión de ordenador, en 16 pasos:

1. Identificación
2. Sistema y memoria
3. Batería
4. Red y conectividad
5. Rendimiento
6. Pantalla
7. Pantalla táctil
8. Sensores
9. Altavoces y vibración
10. Micrófono
11. Cámaras
12. Botones
13. Carga y conexiones
14. Cuentas y bloqueos
15. Estado estético
16. Informe

Al terminar da el mismo veredicto y el mismo grado A/B/C que la versión de ordenador. Genera el informe completo, el informe corto para el cliente y una fila en `Resumen_auditorias.csv`. Los archivos se guardan en **Descargas/vPCheck** del móvil.

## Contenido

```
app/src/main/assets/index.html          ← la app de pruebas (todo el diseño y la lógica)
app/src/main/java/.../MainActivity.java ← envoltorio Android y acceso a batería, botones, sensores…
.github/workflows/compilar-apk.yml      ← compila el APK automáticamente en GitHub
```

## Cómo conseguir el APK

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio en GitHub (puede ser privado) y sube **todo** el contenido de esta carpeta, incluida la carpeta oculta `.github`.
2. Ve a la pestaña **Actions**. La compilación empieza sola; si no arranca, abre «Compilar APK» y pulsa **Run workflow**.
3. Cuando termine (unos 4 minutos), abre la ejecución y descarga **vPCheck-Movil-apk**. Dentro está `vPCheck-Movil.apk`.

### Opción B: Android Studio
1. Abre Android Studio y elige *File › Open* con esta carpeta.
2. Espera a que termine «Gradle sync».
3. Elige *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
4. El APK queda en `app/build/outputs/apk/debug/`.

## Instalar en los móviles
Pasa el APK al móvil (por cable, por correo o con Drive) y ábrelo. Android pedirá permiso para «instalar apps desconocidas»: acéptalo para esa vez.

La primera vez que se usan la cámara, el micrófono y la ubicación, la app pide permiso. Hay que aceptarlos para que esas pruebas funcionen.

## Ajustes
- **Umbrales:** batería, temperatura, RAM mínima, etc. Están en el bloque `CFG` al principio del `<script>` de `index.html`, igual que en el script de ordenador.
- **Informe corto:** se configura desde la propia app, con el botón «Configurar» de la pantalla de inicio.

## Qué hace la app instalada y no la versión web
`index.html` también funciona suelto en Chrome, si se sirve por HTTPS (por ejemplo, desde GitHub Pages). Pero solo la app instalada puede hacer esto:

- Leer la salud estimada de la batería, sus ciclos (Android 14 o superior) y su temperatura.
- Detectar los botones de volumen y el de encendido.
- Probar el auricular de llamadas.
- Probar los sensores de proximidad y de luz.
- Encender la linterna.
- Saber si hay SIM y si están activados el Bluetooth y el NFC.
- Guardar los informes directamente en Descargas/vPCheck.

## Limitaciones
- **Salud de la batería:** Android no da el porcentaje oficial a las apps. vPCheck pide al operario el dato de Ajustes y, si no lo hay, muestra una estimación marcada como «(est.)».
- **Test de RAM:** prueba solo una parte de la memoria (unos 100–380 MB), porque una app no puede acceder a toda.
- **IMEI:** Android no deja leerlo a las apps normales, así que se escribe a mano (*#06#).
- **Firma:** el APK va firmado con una clave de pruebas. Sirve para instalarlo en vuestros móviles, pero no para publicarlo en Google Play.
