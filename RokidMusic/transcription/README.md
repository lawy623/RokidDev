# RokidMusic Transcription Pipeline

This folder contains the image/PDF-to-tab transcription system. The goal is not
to guess a full score in one opaque step. Each stage produces inspectable data
and debug images so mistakes can be traced and corrected.

## Target Pipeline

```text
image/pdf
  -> page image normalization
  -> tab staff/system detection
  -> measure boundary detection
  -> symbol/OCR detection
  -> coordinate mapping
  -> compact transcription
  -> tab-score JSON
  -> Rokid renderer visual QA
```

## Intermediate Model

The transcription system should keep coordinates until the final JSON export.
This avoids guessing string numbers by eye.

```json
{
  "source": "data/raw/zhendeaini.jpg",
  "imageSize": [1133, 779],
  "systems": [
    {
      "id": "sys-1",
      "bbox": [35, 294, 1078, 66],
      "stringLines": [
        { "string": 1, "y": 307.0 },
        { "string": 2, "y": 320.0 }
      ],
      "barlines": [35.0, 277.0, 520.0, 821.0, 1113.0]
    }
  ]
}
```

Notes and symbols should later attach their source boxes:

```json
{
  "type": "note-number",
  "rawText": "12",
  "bbox": [177, 305, 18, 14],
  "center": [186, 312],
  "string": 3,
  "measure": 1,
  "confidence": 0.86
}
```

## Current Stage

`detect_tab_structure.py` implements the first reliable layer:

- detect horizontal tab string lines
- group lines into six-line systems
- detect vertical barline candidates per system
- output a JSON structure file
- output a debug PNG overlay

Run:

```bash
python3 RokidMusic/transcription/detect_tab_structure.py RokidMusic/data/raw/zhendeaini.jpg \
  --out RokidMusic/data/tmp/zhendeaini.structure.json \
  --debug RokidMusic/data/tmp/zhendeaini.structure.debug.png
```

Outputs:

- `RokidMusic/data/tmp/zhendeaini.structure.json`
- `RokidMusic/data/tmp/zhendeaini.structure.debug.png`

The next layer should detect note-number bounding boxes and map their center Y
to the nearest `stringLines[*].y`.

## Coordinate Mapping

After structure detection, any recognized symbol can be mapped with:

```bash
python3 RokidMusic/transcription/map_symbols.py \
  RokidMusic/data/tmp/zhendeaini.structure.json \
  185,321 218,308 242,308
```

Example output for the first measure:

```json
[
  { "x": 185.0, "y": 321.0, "systemId": "sys-1", "string": 3, "measureInSystem": 1 },
  { "x": 218.0, "y": 308.0, "systemId": "sys-1", "string": 2, "measureInSystem": 1 },
  { "x": 242.0, "y": 308.0, "systemId": "sys-1", "string": 2, "measureInSystem": 1 }
]
```

This is the important correction over manual transcription: `string` is derived
from image geometry, not counted by eye. The next OCR stage should preserve each
number's bbox so this mapper can assign strings and measures deterministically.
