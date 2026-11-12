# Theme image assets

All images in this directory are owned by the project, except the optional vehicle cut-out
described below. The photographic scenes (`bg*.jpg`) are original artwork.

`truck.png` — the vehicle used in the centre of the skin — is **not included** because its
rights are not cleared for redistribution.

## What happens without the file

The skin probes `assets/truck.png` at startup:

- **file present:** it draws the image at 312 px wide in the centre of the dial;
- **file absent:** it draws a dashed placeholder with `PON TU FOTO AQUÍ` and the filename,
  format and size, so the empty centre is explained instead of looking broken.

Everything else (speed, rings, cards, modes) works in both cases.

## Adding your own vehicle photo

| Spec | Value |
|---|---|
| File name | `truck.png` (exact) |
| Format | PNG, 8-bit RGBA with transparent background |
| Recommended size | 800×406 px (2:1 or similar; the skin scales it to 312 px wide) |
| View | front 3/4, vehicle facing the viewer, wheels at the bottom edge |
| Framing | crop tight to the vehicle, centred, no scene behind it |
| Edges | clean alpha; no white halo, no drop shadow (the skin draws its own glow) |
| Rights | you must own it or have permission to redistribute it |

Drop the file in this directory, rebuild (`SDK=/opt/android-sdk ./app/build.sh`) or refresh the
preview, and the placeholder disappears.

A photo that is merely visible on a manufacturer site or social media is not licensed for
reuse, and attribution alone does not grant that right.
