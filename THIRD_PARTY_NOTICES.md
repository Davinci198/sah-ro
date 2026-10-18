# Third-party notices

As of v1.2.1 the application bundles no third-party code and no third-party
artwork.

## Removed in v1.2.1

Two previously bundled items were removed:

- The DragonBones Android runtime (`app/libs/dragonbones-release.aar`, MIT) and
  the `app/src/main/assets/chess/queen_*` mascot assets it rendered. Besides
  carrying a native library, the runtime deadlocked the main thread: it called
  `GLSurfaceView.onPause()` from the GL thread, which then waited for the main
  thread while the main thread waited for the GL thread in
  `surfaceDestroyed()`. Opening any screen over `MainActivity` (Lecții,
  Puzzle-uri) produced an ANR and the process was killed.
- The queen mascot itself, which kept an OpenGL thread rendering in the
  background.

The app now depends only on libraries fetched by Gradle in
`app/build.gradle.kts`, listed in this file when non-empty.
