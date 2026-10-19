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
3. A PDF with no text layer (a scan or photographed pages) is rendered page by page and read with on-device
   text recognition (first 12 pages). If no text is found it is still indexed by file name.

**Office files** (`engine/office/`, plain Kotlin, no Apache POI):

| Format | How it is read |
|---|---|
| `.docx`, `.pptx` | Open XML text runs (slides in numeric order) |
| `.xlsx` | Shared strings + sheet XML, one line per row: `Sheet: Expenses` / `Rent \| 1200` |
| `.doc` | Word 97–2003 piece table (`LegacyWordExtractor`); field codes dropped, table cells separated by `\|` |
| `.xls` | Excel 97–2003 BIFF8 records (`LegacyExcelExtractor`), incl. formula results; same layout as `.xlsx` |
| `.ppt` | PowerPoint 97–2003 text atoms (`LegacyPowerPointExtractor`): slides, text boxes, notes |

`.doc`/`.xls`/`.ppt` share a small OLE2 container reader (`CompoundFile`). The real format is detected from the
file's first bytes, so a `.doc` that is really RTF, an `.xls` that is really an HTML table or CSV export, and
renamed Open XML files are all read correctly. Encrypted files and pre-97 formats are skipped.

**Pictures** (photos of receipts, screenshots, whiteboards) are read with ML Kit's bundled text recognizer
(`ImageTextRecognizer`): the model ships inside the APK, runs offline and no pixels leave the phone. The indexed
text is the recognized text followed by the photo's details (camera, date, GPS), so a search can tell
"written in the picture" from "in the photo's details". Pictures and scanned PDFs indexed by an older version are
read again on the next full indexing run.

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

No embedding model from Google is used (the only Google model is the bundled ML Kit OCR model, see above), and the app never downloads models itself. Model files are two files per model,
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

## How search ranks results

`HybridSearchEngine` gathers candidates three ways — SQLite FTS (chunks with *all* the words first, then *any*),
every indexed **file name** (with typo tolerance), and the closest chunks by **vector** similarity — and
`engine/search/SearchRanker` ranks them, one result per file:

1. **File name** matches first: exact name, name starting with the query, all words in the name. Names are split
   on `_ - .`, camelCase and digits, so `Q3_TaxReport2023.pdf` matches "tax report 2023".
2. **Text** matches next: whole words only ("art" doesn't match "start"), plurals folded, the word being typed
   matches as a prefix, small typos forgiven ("recipt" → receipt). All words beat some words; the exact phrase
   beats scattered words; BM25 breaks ties.
3. **Similar meaning** (vector) matches are shown only when they stand out from the rest of the collection
   (about two standard deviations above the average similarity for that query), so unrelated files don't fill
   the list. Word matches always rank ahead of meaning-only matches of similar strength.

Each result says why it matched — *File name*, *Exact phrase*, *All words*, *Some words*, *Text in image*,
*Photo details* or *Similar meaning* — plus how many places in the file matched and the PDF page.

Optional search operators: `"exact phrase"`, `-word` (exclude), `name:word` (must be in the file name),
`ext:pdf` / `type:image` (file type; `ext:pdf` on its own lists all PDFs, newest first).

## Tests

`./gradlew testDebugUnitTest` runs the JVM/Robolectric tests, including the search ranking rules
(`SearchRankerTest`), the Office readers against LibreOffice-written `.doc`/`.xls`/`.ppt`/`.rtf` files
(`OfficeExtractorsTest`), the tokenizer-vs-Hugging-Face differential
test (`WordPieceTokenizerTest`), pooling, page-aware chunking and the model manager fallback/import behaviour.

## Importing documents, labels and search highlighting

* **Imported Documents** (menu): files and folders picked with the system picker are stored in Room
  (`document_paths`) with their status. `PdfBoxIndexWorker` (WorkManager) extracts their text with PDFBox and adds
  it to the FTS4 index in the background; failed or pending paths can be retried from the same sheet.
* **Labels:** Work, Personal, Finance, Legal, Medical, Education, Receipts, Travel and Research are always available,
  plus any tag you create (`engine/DocumentLabels.kt`). Tap a label chip above the results to filter by it.
* **Highlighting:** words from the search query are shown in bold with a background colour inside each excerpt
  (`engine/KeywordHighlighter.kt`).

## Releases

Pushing a tag such as `v1.1.0` runs `.github/workflows/android-release.yml`, which builds the APK and publishes a
GitHub release with the APK attached, using `release-notes/<tag>.md` as the release text. The in-app updater reads
those notes. Add `KEYSTORE_BASE64`, `STORE_PASSWORD` and `KEY_PASSWORD` (key alias `upload`) as repository secrets to
sign with your own key so each release installs over the previous one; without them a debug key is generated per build.
