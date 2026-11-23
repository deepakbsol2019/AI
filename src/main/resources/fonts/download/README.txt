Noto Sans Regular and Bold, bundled unmodified from the Noto project:
https://github.com/notofonts/noto-fonts/tree/main/hinted/ttf/NotoSans
License: SIL Open Font License 1.1; see OFL.txt in this directory.

The download front-matter renderer verifies glyph coverage before rendering.
Latin, Greek and Cyrillic metadata are supported and covered by PDF tests.
Complex-script shaping (including Indic and RTL text), CJK and emoji are not
supported by this template/font configuration. Unsupported metadata causes
HTTP 422 instead of producing missing glyph boxes. Original topic PDF pages
are merged without re-rendering and do not have this front-matter limitation.
