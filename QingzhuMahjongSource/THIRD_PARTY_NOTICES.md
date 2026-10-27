# Third-Party Notices

## Mahjong algorithm

`src/main/java/cn/qingzhu/vendor/Shanten.java` is a Java translation of
`mahjong/shanten.py` from MahjongRepository/mahjong 2.0.0.

Copyright (c) 2017 mahjong Python library contributors. MIT License.
The full license is included at `licenses/mahjong-MIT.txt`.
Upstream source: https://github.com/MahjongRepository/mahjong/tree/v2.0.0

The translation preserves the original recursive search, special-hand logic,
and four-copy handling. The language, method names, and helper layout were
adapted for Java. The 14,636 fixtures under `src/test/resources/oracle.tsv`
were generated using the unmodified upstream Shanten and Agari implementations.
The original Python library is not required by the delivered game.

## Bundled Java runtime

The Windows distribution includes a jlink runtime created from Eclipse Temurin
OpenJDK 21.0.12.1+1 (Eclipse Adoptium), Windows x64. The runtime is distributed
under GPL version 2 with the Classpath Exception and the other notices shipped
by OpenJDK. Applicable license texts are preserved in `runtime/legal/`.

Vendor downloads and source-code information:
https://adoptium.net/temurin/releases/
https://github.com/adoptium/temurin21-binaries
https://github.com/adoptium/jdk21u

The application uses Java standard library APIs and does not modify OpenJDK.
The build script invokes the vendor's javac, jar, jlink, and jpackage tools.

## Artwork and sound

Tile face and back PNGs are from **lietxia/mahjong_graphic**:
https://github.com/lietxia/mahjong_graphic

Pinned upstream commit: `3e275804ff58325306710bef3a7406860444bc6a`.
Only 34 standard tile faces (1-9 man/pin/sou and seven honors) and the portrait
back `b_02.png` are included, from the default style. No red fives, flowers,
seasons, bonus tiles, riichi sticks or extra tile faces are bundled.
The PNGs are unmodified; the renderer resizes them while preserving aspect ratio.
The full upstream M+ permission statement is in `licenses/mahjong-graphic-LICENSE.txt`.
The upstream README credits some designs to SyaoranHinata/I.Mahjong, derived
from GL-MahjongTile. Per-file provenance and SHA-256 hashes are in
`src/main/resources/assets/tiles/manifest.json` inside `source.zip` (also in the JAR).

The app icon and short synthesized sound are original assets from the
Qingzhu Mahjong project. All artwork is bundled as static resources in the JAR.
No Python, Qt, PySide, or third-party font files are included in this Java build.
The game uses Windows system fonts.
