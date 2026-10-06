# LocalDoc Finder

Offline document search for Android: semantic (vector) + keyword (SQLite FTS) hybrid search over your PDFs,
documents, chats and contacts. **Everything runs on the device** — no cloud AI, no API keys, no accounts, and
no document text or search query is ever sent anywhere.

## Run locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio)

1. Open Android Studio, choose **Open** and select this directory.
2. Let Android Studio sync the Gradle project.
3. Run the app on an emulator or a physical device.

No `.env` file or secrets are needed.

## How indexing works

```
PDF / DOCX / TXT / …            TextExtractionService          DocumentParser            Room (`documents` + FTS4)
────────────────────►  pages ─► (PDFBox-Android) ─► clean ─► page-aware chunks ─► embed ─► text · vector · page · model
```

### 1. Text extraction (`engine/extraction/`)

`TextExtractionService` turns a PDF into clean per-page text:

1. **PDFBox-Android** (the Android port of Apache PDFBox — the same engine Apache Tika uses for PDFs; Tika itself
   cannot run on Android because it needs `java.awt` / `javax.xml.stream`). Reading order is preserved, paragraph
   breaks are kept, and the PDF's title/author metadata is read.
2. If PDFBox rejects the file, the built-in stream scanner / `PdfRenderer` extractor is used instead.
3. A PDF with no text layer (a pure scan) is indexed by file name so it can still be found by title.

`PageAwareChunker` then splits the pages into ~1000-character chunks (short pages are merged, long ones split on
paragraph/sentence boundaries) and each chunk remembers its source page(s) in the `documents.metadata` column —
the document detail sheet shows "Section 4 · p. 12".

### 2. Embeddings (`engine/embedding/`)

`TfliteTextEmbedder` is a local TensorFlow Lite sentence-embedding pipeline:

`text → WordPieceTokenizer → TFLite transformer → pooling (CLS or mean) → L2-normalised vector`

* `WordPieceTokenizer` reproduces Hugging Face's `BertTokenizer` id-for-id (verified by a differential test).
* Inputs are matched by tensor name, `int32`/`int64` are both accepted, fixed or dynamic sequence length is
  handled, and the output may be per-token hidden states or an already-pooled vector.
* It honours the indexing power policy (batch size, pacing, CPU threads) and the pause button.
* If a model is missing or fails to load, the app falls back to the built-in lightweight embedder and tags the
  vectors accordingly, so indexing never stops.

Every stored vector is tagged with the model that produced it (`metadata = model=bge_small_en_v15;page=3`).
Vectors from different models are not comparable, so search only scores chunks whose tag matches the model in
use; older chunks stay keyword-searchable and the model sheet shows how many need a **Re-index**.

### Models

| Model | Dim | Size (INT8) | MTEB retrieval | Licence | Notes |
|---|---|---|---|---|---|
| **BGE Small v1.5** (default) | 384 | ≈34 MB | 51.7 | MIT | best quality for its size |
| BGE Base v1.5 | 768 | ≈110 MB | 53.3 | MIT | highest quality, ~3× slower |
| MiniLM L6 v2 | 384 | ≈23 MB | 42.0 | Apache-2.0 | fastest, smallest |
| Built-in lightweight | 384 | — | — | — | no files needed; matches words, not meaning |

No model from Google is used, and the app never downloads models itself. Model files are two files per model,
`model.tflite` + `vocab.txt`, which arrive in one of three ways:

1. **In the app:** *Indexing & Embedding Models* sheet → **Import model files** → pick both files.
2. **adb:** `adb push <model-dir> /sdcard/Android/data/com.aistudio.vectorsearch.dvmxqe/files/embedding_models/`
3. **Bundled:** copy the folder to `app/src/main/assets/models/<model-id>/` before building.

Create the files on a computer with the export script (needs Python, TensorFlow and the Hugging Face checkpoint):

```bash
pip install tensorflow tf-keras "transformers<5" torch
python tools/export_embedding_model.py bge_small_en_v15 --out build/models
python tools/export_embedding_model.py --self-test      # wiring check, no download
```

The script bakes in batch 1 and a fixed sequence length (256 by default), applies int8 dynamic-range quantisation,
and checks the TFLite output against the original model before writing the files.

## Tests

`./gradlew testDebugUnitTest` runs the JVM/Robolectric tests, including the tokenizer-vs-Hugging-Face differential
test (`WordPieceTokenizerTest`), pooling, page-aware chunking and the model manager fallback/import behaviour.
