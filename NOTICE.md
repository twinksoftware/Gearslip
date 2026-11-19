# License

Gearslip is licensed under the GNU Affero General Public License v3.0. The full text is in `LICENSE`.

You may use, run, and modify Gearslip freely. If you distribute a modified version, or let others use one over a network, you must publish your changes under the same license. The obligation follows the code even when you hand out no binary, which suits software that runs as a background service.

## Commercial licensing

If the AGPL's terms don't work for you, Twink Software LLC, which owns Gearslip, sells a separate commercial license. The usual case is a company that wants to use Gearslip privately without publishing its changes. Terms, including any limits on modification, are set case by case. Contact us on Matrix; the address is on the [Seb3thehacker GitHub profile](https://github.com/Seb3thehacker).

## Music: a front-end for external apps

Gearslip no longer streams music itself. It is a Nothing-styled front-end that browses and controls
external media apps — Vivi Music and Echo Music — through Android's MediaBrowser/MediaController
APIs. The first-party InnerTube catalog/search client, metadata cleaner and bounded-range
`ChunkedDataSource` were ported in and later removed; the attribution history below is kept for
accuracy.

## Fonts: Doto (OFL-1.1) © The Doto Project Authors

The Nothing skin's display type is [Doto](https://github.com/oliverlalan/Doto), a variable dot-matrix font by The Doto Project Authors, bundled under the SIL Open Font License 1.1. The font file lives in `gearslip/src/main/res/font/doto.ttf`.

Copyright 2024 The Doto Project Authors (https://github.com/oliverlalan/Doto)

This Font Software is licensed under the SIL Open Font License, Version 1.1.

---

SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007

PREAMBLE

The goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation efforts of academic and linguistic communities, and to provide a free and open framework in which fonts may be shared and improved in partnership with others.

The OFL allows the licensed fonts to be used, studied, modified and redistributed freely as long as they are not sold by themselves. The fonts, including any derivative works, can be bundled, embedded, redistributed and/or sold with any software provided that any reserved names are not used by derivative works. The fonts and derivatives, however, cannot be released under any other type of license. The requirement for fonts to remain under this license does not apply to any document created using the fonts or their derivatives.

DEFINITIONS

"Font Software" refers to the set of files released by the Copyright Holder(s) under this license and clearly marked as such. This may include source files, build scripts and documentation.

"Reserved Font Name" refers to any names specified as such after the copyright statement(s).

"Original Version" refers to the collection of Font Software components as distributed by the Copyright Holder(s).

"Modified Version" refers to any derivative made by adding to, deleting, or substituting -- in part or in whole -- any of the components of the Original Version, by changing formats or by porting the Font Software to a new environment.

"Author" refers to any designer, engineer, programmer, technical writer or other person who contributed to the Font Software.

PERMISSION & CONDITIONS

Permission is hereby granted, free of charge, to any person obtaining a copy of the Font Software, to use, study, copy, merge, embed, modify, redistribute, and sell modified and unmodified copies of the Font Software, subject to the following conditions:

1) Neither the Font Software nor any of its individual components, in Original or Modified Versions, may be sold by itself.

2) Original or Modified Versions of the Font Software may be bundled, redistributed and/or sold with any software, provided that each copy contains the above copyright notice and this license. These can be included either as stand-alone text files, human-readable headers or in the appropriate machine-readable metadata fields within text or binary files as long as those fields can be easily viewed by the user.

3) No Modified Version of the Font Software may use the Reserved Font Name(s) unless explicit written permission is granted by the corresponding Copyright Holder. This restriction only applies to the primary font name as presented to the users.

4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font Software shall not be used to promote, endorse or advertise any Modified Version, except to acknowledge the contribution(s) of the Copyright Holder(s) and the Author(s) or with their explicit written permission.

5) The Font Software, modified or unmodified, in part or in whole, must be distributed entirely under this license, and must not be distributed under any other license. The requirement for fonts to remain under this license does not apply to any document created using the Font Software.

TERMINATION

This license becomes null and void if any of the above conditions are not met.

DISCLAIMER

THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM OTHER DEALINGS IN THE FONT SOFTWARE.

## Nothing icon glyph pipeline (AGPL-3.0)

The Nothing skin's monochrome app-icon pipeline in `gearslip/src/main/java/app/seb3thehacker/gearslip/car/theme/NothingIcons.kt` is ported from [linuxct/nothing-icongen](https://github.com/linuxct/nothing-icongen), "PortableMonochrome.kt" (AGPL-3.0, © linuxct). Gearslip is also AGPL-3.0, so the port is license-compatible and remains under AGPL-3.0; the file header retains the source link and copyright.

## Third-party code

### Dot-matrix player art (MIT / contribution)

`gearslip/.../car/theme/DotMatrixArt.kt` adapts the image-to-dots "Value Aliasing" approach and palette from [udaign/matrices](https://github.com/udaign/matrices) (Matrices for Nothing Community), and the per-dot `f(row, col, t) → {opacity, scale}` loading pulse from [lportals/dot_matrix_loader](https://github.com/lportals/dot_matrix_loader) (MIT). Both are reimplemented for Compose Canvas; no source files were copied. The concept mock is `docs/player-mock.html`.

### Echo Music (GPL-3.0) — ported, then removed

[Echo Music](https://github.com/EchoMusicApp/Echo-Music) — GPL-3.0. Its InnerTube catalog/search client, metadata text cleaner and bounded-range `ChunkedDataSource` were ported into Gearslip (into the `catalog` module and `gearslip/.../music/playback`, with the original source headers retained in the copied files); upstream also credits Metrolist, ViMusic, ArchiveTune, SimpMusic and BravePipe. That code has since been removed: Gearslip is now a Nothing-styled front-end that drives Echo Music and Vivi Music as external apps via MediaBrowser/MediaController, and no Echo-derived source remains in the tree. Gearslip is AGPL-3.0, which is GPL-3.0-compatible, so the ported code was under AGPL-3.0 while it existed.
