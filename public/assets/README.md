# Website backgrounds

## Active Gurukul background

`Flowering-Gurukul-by-the-River.png` is the exact original image supplied by the user for this website. It is served locally and copied unchanged into the production build. No replacement artwork, cropping of the source file, recoloring, filter, or compression has been applied. The same URL is used by the homepage and native modal backdrop; modal content stays pure white.

The responsive CSS uses centered `cover` positioning to preserve the image aspect ratio and keep the central banyan tree, guru, and students in view. Neutral white overlays improve readability without adding a yellow tint. On narrow screens, cover positioning naturally crops the outer edges.

Configure the shared background URL and position in the `:root, dialog::backdrop` rule at the start of `src/styles.css`.

## Retained library photograph

`library-interior.jpg` is a local 1280 × 960 JPEG thumbnail (approximately 360 KiB) of **SteacieLibrary.jpg**, a photograph of the Steacie Science and Engineering Library at York University, taken on 12 November 2008 by **Raysonho @ Open Grid Scheduler / Scalable Grid Engine**.

- Source and license declaration: https://commons.wikimedia.org/wiki/File:SteacieLibrary.jpg
- Downloaded image: https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3b/SteacieLibrary.jpg/1280px-SteacieLibrary.jpg
- License: **CC0 1.0 Universal Public Domain Dedication**, https://creativecommons.org/publicdomain/zero/1.0/
- No image edits other than Wikimedia's thumbnail resize. The application uses a CSS color overlay, without image or backdrop blur.

The image is served locally, with no runtime hotlink. To replace it, update `--library-background-image` at the start of `src/styles.css` to the URL of an asset in `public/`.
