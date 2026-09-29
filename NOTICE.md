# Licensing and third-party notices

## Code

The source code is licensed under the **GNU Affero General Public License v3.0 only**
(`AGPL-3.0-only`); see [`LICENSE`](LICENSE). Copyright (C) 2026 Itay Polack-Gadassi.

As permitted by section 7(e) of the AGPL, no rights in the "Backlog.fyi" name or logo are granted;
see [`TRADEMARKS.md`](TRADEMARKS.md).

## Artwork

The mascots are licensed under
[**Creative Commons Attribution 4.0 International**](https://creativecommons.org/licenses/by/4.0/)
(`CC-BY-4.0`); the full text is in [`LICENSES/CC-BY-4.0.txt`](LICENSES/CC-BY-4.0.txt). This covers:

- the board mascots and the balloon faces: `tasker-frontend/src/assets/balloon/`,
  `mr_roboto*.webp`, `pineapple*.webp`, `stationery_holder*.webp`, and the source image
  `tools/Balloon.png`.

You may share and adapt them, including commercially, as long as you give credit. A suitable credit
is: "Artwork from Backlog.fyi (github.com/Itaypk/Taskboard), by Itay Polack-Gadassi, CC BY 4.0".

Caveat: these images were generated with image-generation models (Google's "Nano Banana" and
OpenAI's ChatGPT image generation). The copyright status of AI-generated output differs between
jurisdictions and may be limited or nonexistent. The license grants whatever rights the licensor
holds, and the artwork comes without warranty.

## Branding

The logo and app icons are **not** under the licenses above. Copyright (C) 2026 Itay Polack-Gadassi,
all rights reserved, except that you may use the files unmodified as part of a self-hosted
deployment of the software, as described in [`TRADEMARKS.md`](TRADEMARKS.md). They are:

- `tasker-frontend/public/`: `favicon.ico`, `favicon-*.png`, `apple-touch-icon.png`, `icon-*.png`,
  `og-image.png`;
- `tasker-frontend/src/assets/branding/`.

## Third-party components

- **Fonts**: Fraunces, IBM Plex Mono and Plus Jakarta Sans, under the
  [SIL Open Font License 1.1](https://openfontlicense.org/), vendored from Google Fonts into
  `tasker-frontend/src/assets/fonts/` (see `tools/refresh-google-fonts.sh`). The license texts and
  copyright notices are kept in [`LICENSES/`](LICENSES/) rather than next to the font files, because
  the refresh script recreates that directory from scratch:
  [Fraunces](LICENSES/OFL-Fraunces.txt), [IBM Plex Mono](LICENSES/OFL-IBMPlexMono.txt),
  [Plus Jakarta Sans](LICENSES/OFL-PlusJakartaSans.txt). They are not covered by the licenses above.
- **Disposable email domain list**: a snapshot of
  [disposable/disposable-email-domains](https://github.com/disposable/disposable-email-domains)
  (MIT License), in `src/main/resources/email/disposable-domains.txt.gz`.
- **Dependencies**: Gradle and npm dependencies keep their own licenses.
