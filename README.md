# innovision

Android starter for detecting post-its, running OCR, and surfacing extracted text.

## What's included
- CameraX preview + analyzer loop.
- ML Kit Text Recognition for on-device OCR.
- Simple color-based post-it detection with overlay boxes.
- Kotlin + ViewBinding setup.

## Handwriting note
ML Kit text recognition works best for printed text. For handwriting accuracy, consider:
- Cloud Vision (higher accuracy, requires network).
- Custom fine-tuned OCR model hosted on your backend.

## Post-it detection note
The current detector is a lightweight HSV color heuristic that looks for common
sticky note colors (yellow, pink, blue, green) and draws bounding boxes.
For production accuracy, replace this with a trained detector (e.g., TFLite YOLO)
that can handle varied lighting and whiteboard reflections.

## Local setup
If the Gradle wrapper jar is missing, regenerate it with an installed Gradle:

```bash
gradle wrapper
```

## Next steps
- Replace the heuristic detector with a trained model and crop detected regions before OCR.
- Add a post-it detector (TFLite model) and crop detected regions before OCR.
- Build export pipeline to Neo4j (JSON -> API -> Neo4j).
