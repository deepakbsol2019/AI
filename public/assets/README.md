# Website backgrounds

## Active laptop/library background

`Library knowledge streams into the laptop.png` is the user-selected background, served locally and copied unchanged into the production build. The shared CSS URL encodes its spaces. The same image is used by the page and native modal backdrop; modal content stays white.

The responsive CSS uses centered `cover` positioning to preserve the image aspect ratio. Neutral white overlays improve readability. On narrow screens, cover positioning naturally crops the outer edges. The earlier `Flowering-Gurukul-by-the-River.png` asset is retained.

Configure the shared background URL and position in the `:root, dialog::backdrop` rule at the start of `src/styles.css`.

## Retained library photograph

`library-interior.jpg` is a local 1280 × 960 JPEG thumbnail (approximately 360 KiB) of **SteacieLibrary.jpg**, a photograph of the Steacie Science and Engineering Library at York University, taken on 12 November 2008 by **Raysonho @ Open Grid Scheduler / Scalable Grid Engine**.

- Source and license declaration: https://commons.wikimedia.org/wiki/File:SteacieLibrary.jpg
- Downloaded image: https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/SteacieLibrary.jpg/1280px-SteacieLibrary.jpg
- License: **CC0 1.0 Universal Public Domain Dedication**, https://creativecommons.org/publicdomain/zero/1.0/
- No image edits other than Wikimedia's thumbnail resize. The application uses a CSS color overlay, without image or backdrop blur.

The image is served locally, with no runtime hotlink. To replace it, update `--library-background-image` at the start of `src/styles.css` to the URL of an asset in `public/`.
