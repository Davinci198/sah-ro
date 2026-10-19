# Third-party notices

As of v1.2.2 the application bundles no third-party code. The bundled piece
artwork is described below.

## Piece artwork (v1.2.2)

`app/src/main/res/drawable-nodpi/piece_{w,b}{p,n,b,r,q,k}.png` — the 12 monster
chess pieces shown on the board. They are cropped from a reference screenshot of
the original Checkmate app (`~/9remote-uploads/sah-board-ref/ref.png`), matted to
transparency with ImageMagick (flood fill from the cell corners), and resized to
200×200. Provenance: derivative of the original Checkmate board rendering; all
credit for the underlying designs remains with the original authors. If you
redistribute this app, confirm you are comfortable bundling artwork derived from
the original before shipping.

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
