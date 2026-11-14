# Representative group label

`group-label.svg` is a synthetic Code 128 label with the exact requested pair:

- Upper: `8996603430263612700`
- Lower: `8996603430263612799`

Short quantity/date/box fields are distractors. This is a generated illustration, not a user photograph or evidence of ML Kit recognition quality.

`GroupLabelScannerTest` supplies representative barcode/text corner observations for this layout, rotates them through 0°, 15°, 90°, 180°, 270° and −25°, applies shear, reverses the values without reversing their geometry, and tests missing/conflicting text, single numbers and temporal freezing. Tests inject observations at the ML Kit boundary; they do not execute optical decoding of this SVG or measure physical camera accuracy.

`app/src/androidTest/assets/group-label.png` is the 1600×1080 raster rendering used by `GroupLabelImageTest` with the actual bundled ML Kit models on Android. It checks exact upright decoding, safe fallback on rotated images, and manual review of a cropped single-row label. Optical recognition here is limited to this synthetic sample; it does not validate real label print quality, lighting or camera hardware.
