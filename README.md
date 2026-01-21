# innovision

Android starter for detecting post-its, running OCR, and surfacing extracted text.

## What's included
- CameraX preview + analyzer loop.
- ML Kit Text Recognition for on-device OCR.
- Kotlin + ViewBinding setup.

## Handwriting note
ML Kit text recognition works best for printed text. For handwriting accuracy, consider:
- Cloud Vision (higher accuracy, requires network).
- Custom fine-tuned OCR model hosted on your backend.

## Local setup
If the Gradle wrapper jar is missing, regenerate it with an installed Gradle:

```bash
gradle wrapper
```

## Next steps
- Add a post-it detector (TFLite model) and crop detected regions before OCR.
- Build export pipeline to Neo4j (JSON -> API -> Neo4j).
