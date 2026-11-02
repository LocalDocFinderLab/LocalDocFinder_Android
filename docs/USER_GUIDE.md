# LocalDoc Finder — User Guide

> **Version:** 2.0.x · **Platform:** Android 9 (API 28) and later · **Last updated:** November 2026

LocalDoc Finder searches the inside of the documents, PDFs and photos on your phone, and lets you ask an AI
questions about them. It runs entirely on your device: there's no account, no cloud service and no API key, and
your files, searches and answers are never uploaded.

---

## Contents

1. [Overview](#1-overview)
2. [System requirements](#2-system-requirements)
3. [Installation](#3-installation)
4. [First launch and permissions](#4-first-launch-and-permissions)
5. [Quick start](#5-quick-start)
6. [Adding documents](#6-adding-documents)
7. [Indexing](#7-indexing)
8. [Searching](#8-searching)
9. [Working with results](#9-working-with-results)
10. [Chat with your files](#10-chat-with-your-files)
11. [Search models](#11-search-models)
12. [Organising your library](#12-organising-your-library)
13. [Home-screen search](#13-home-screen-search)
14. [Privacy](#14-privacy)
15. [Updates](#15-updates)
16. [Menu reference](#16-menu-reference)
17. [Developer mode and the Dev build](#17-developer-mode-and-the-dev-build)
18. [Troubleshooting](#18-troubleshooting)
19. [FAQ](#19-faq)
20. [Limits](#20-limits)
21. [Glossary](#21-glossary)

---

## 1. Overview

| What you can do | How |
|---|---|
| Find a file by its contents, even when you've forgotten its name | Type words or a question into the search bar |
| Find a photo of a receipt, whiteboard or screenshot by the text in it | Pictures are read with on-device text recognition (OCR) |
| Find documents that are about something, even if they use other words | "Similar meaning" search with an on-device embedding model |
| Get a written answer drawn from your documents, with sources | **Chat with your files** (on-device language model) |
| Keep the index up to date automatically | Auto-scan checks for new and changed files every 15 minutes |
| Spot copies of the same document | **Find Duplicates** |

### Supported file types

| Category | Extensions | Notes |
|---|---|---|
| PDF | `.pdf` | Text PDFs are read directly. Scanned PDFs (no text layer) are read with OCR. |
| Word | `.docx`, `.doc` | Includes Word 97–2003. Table cells are separated by `\|`. |
| Excel | `.xlsx`, `.xls` | Read sheet by sheet, one line per row (for example `Rent \| 1200`), including formula results. |
| PowerPoint | `.pptx`, `.ppt` | Slide text, text boxes and speaker notes, in slide order. |
| Text | `.txt`, `.md`, `.markdown`, `.text`, `.rst`, `.rtf` | |
| Code and data | `.json`, `.csv`, `.tsv`, `.html`, `.htm`, `.xml`, `.log` | |
| Images | `.jpg`, `.jpeg`, `.png`, `.webp`, `.heic`, `.heif`, `.dng`, `.bmp`, `.tif`, `.tiff` | Text in the picture plus the photo's details (camera, date, location). |
| Chat backups | SMS Backup & Restore `.xml`, JSON chat exports, WhatsApp `.txt` exports | Imported separately; see [§12.4](#124-sms-and-chat-backups). |

A file with the wrong extension is detected from its contents. For example, a `.doc` that is really RTF, or an `.xls`
that a bank exported as an HTML table or CSV, is still read correctly.

---

## 2. System requirements

| | Minimum | Recommended |
|---|---|---|
| Android | 9 (API 28) | 12 or later |
| RAM | 3 GB | 6 GB or more for the larger chat models |
| Free storage | ~150 MB for the app and index | Add ~0.6 GB for the default chat model, and up to 2 GB for the larger ones |
| Processor | ARM 64-bit, ARM 32-bit or x86-64 | ARM 64-bit (almost every phone made since about 2017) |
| Network | None to search | Wi-Fi for the one-time model downloads |

---

## 3. Installation

LocalDoc Finder is distributed as an APK on the project's
[GitHub Releases page](https://github.com/LocalDocFinderLab/LocalDocFinder_Android/releases).

1. Open the latest release on your phone.
2. Download the APK that matches your phone:

   | File | Use it for |
   |---|---|
   | `LocalDocFinder-<version>-arm64-v8a.apk` | **Almost all phones. Pick this one if you're not sure.** |
   | `LocalDocFinder-<version>-armeabi-v7a.apk` | Older 32-bit phones |
   | `LocalDocFinder-<version>-x86_64.apk` | Emulators and Chromebooks |
   | `LocalDocFinder-<version>-universal.apk` | Any device (the largest download) |
   | `LocalDocFinder-<version>-dev-universal.apk` | The Dev build for testing ([§17](#17-developer-mode-and-the-dev-build)) |

3. Open the downloaded file. If Android asks, allow **Install unknown apps** for your browser or file manager.
4. Tap **Install**.

> **Verifying a download (optional).** Each release includes `SHA256SUMS.txt`. Compare the APK's SHA-256 checksum
> with the matching line in that file.

**Upgrading:** new versions install over the old one and keep your index. The one exception is moving from a build
signed with a different key, such as a version older than 1.2.2. Android then asks you to uninstall the old version
first, and doing that clears the index.

---

## 4. First launch and permissions

On first launch, one screen asks for every permission the app needs at once:

| Permission | Why it's needed |
|---|---|
| **All files access** (Android 11+) or **Storage** (Android 9–10) | To read the documents you want to search. The app can't work without it. |
| **Photos / media** (Android 13+) | To read the text in pictures. |
| **Notifications** (Android 13+) | To show indexing progress and model downloads, with a **Stop** button. |

On Android 11 and later, tapping **Allow access to my files** opens a system settings screen. Turn on **Allow access to
manage all files** for LocalDoc Finder, then go back to the app.

If you decline, the screen explains that the app needs file access and offers **Try again** and
**Open app settings**. You can withdraw any permission later in Android Settings.

---

## 5. Quick start

1. **Grant file access** on the welcome screen.
2. Open the menu (☰) and tap **Scan My Phone**. To start smaller, tap **Choose a Folder** instead.
3. Wait for the **Local Search Index** card to finish. You can keep using your phone, or leave the app, while it runs.
4. Type into the search bar, for example `electricity bill march` or `when does my lease end`.
5. Tap a result to read the matching passage, then tap **Open File** to open the document itself.
6. To ask a question instead, tap the **chat** button in the top bar.

> **Tip:** For a large first scan, turn on **Full speed** in the indexing card and plug the phone in.

---

## 6. Adding documents

All of these options are in the menu (☰), under **Add Documents**.

| Option | What it does | Best for |
|---|---|---|
| **Scan My Phone** | Finds PDFs, documents and photos across your storage. System files, app data and caches are skipped automatically. | First-time setup |
| **Choose a Folder** | Indexes one folder through the Android folder picker and keeps it up to date. | A "Documents" or "Work" folder |
| **Pick Files** | Adds one or more files you choose. | A few specific documents |
| **Added Folders & Files** | Lists everything you added by hand, with its status (*Pending*, *Indexed* or *Failed*). From here you can **Add files**, **Add folder**, **Retry** unfinished items, or remove items. | Checking and retrying imports |

> **Why can't I pick the Downloads folder?** Android's folder picker blocks the root of Downloads and the `Android`
> folder. **Scan My Phone** covers both.

---

## 7. Indexing

Indexing reads each file once and stores searchable text and meaning vectors (see [Glossary](#21-glossary)) in a
private database on your phone. Searches then use that database, so they're fast and work offline.

### 7.1 The Local Search Index card

The card at the top of the main screen shows:

- **Files Indexed** and **Vector Chunks**: how much of your library is searchable.
- **Indexing *n*/*m*** with a percentage and the file being read, while a run is active.
- **Stop indexing** ends the current run. Nothing then runs in the background until you start indexing again.
- **Auto-scan for new files**: when on, the app checks Downloads and your added folders every 15 minutes and indexes
  only new or changed files.
- **Full speed**: see [§7.2](#72-indexing-speed).

Progress also appears in a notification, which has a **Stop** button. Indexing keeps going when you leave the app.

### 7.2 Indexing speed

The app adjusts its speed automatically so your phone stays responsive and the battery lasts:

| Situation | Speed |
|---|---|
| Charging, screen off | **Turbo**: all cores, several files at once |
| Charging and in use, or on battery | Standard or light, depending on use |
| Phone is hot | Slowed until it cools down (thermal guard) |
| **Full speed** switch on | Turbo regardless of battery and screen, until you switch it off (only heat and pausing slow it) |
| **Gaming Mode (Pause)** on | Paused |

When indexing is slowed, a bar shows the current speed (for example `40% speed`) and the reason.

**Gaming Mode** is in the menu under **Performance**. It pauses indexing completely while you play or do other heavy
work. Tap **Resume** on the banner to continue.

### 7.3 What happens to each file

1. **Text extraction.** PDFs are read with PDFBox. Office files are read by built-in readers. Pictures and scanned
   PDFs are read with on-device text recognition (up to 50 pages per scanned PDF).
2. **Chunking.** Text is split into passages of about 1,000 characters. Each passage remembers its page number, so
   results can say "p. 12".
3. **Embedding.** Each passage is turned into a meaning vector by the active search model ([§11](#11-search-models)).

### 7.4 Skipped files

If a file times out, or indexing is interrupted while the file is being read (the app is closed or the phone runs
low on memory), it gets one more try. If it fails a second time, it's **skipped**. A file that crashes the reader is
skipped straight away. Either way, indexing carries on with all your other files.

- A banner shows ***n* skipped**. Tap **View Skipped Files** to see the list and why each file was skipped.
- Tap **Retry** on a file, or **Retry Skipped Files**, to put files back in the queue.

### 7.5 After an update or a model change

- Some updates teach the app to read more. Version 1.2 added picture OCR and old Office formats. Files that need
  reading again are picked up automatically on the next full scan (**Scan My Phone**).
- If you change the search model, a dialog asks **Re-index with *model*?**. Tap **Re-index now**. Until you do, older
  passages can still be found by keyword, but not by meaning.

---

## 8. Searching

### 8.1 Basic search

Type into the search bar. Results update as you type, and **suggestion chips** under the bar offer your past searches
and matching file names.

You can type keywords (`invoice acme`), part of a file name (`tax report 2023`), or a question
(`how much was the deposit`). Question words such as *how*, *why* and *when* aren't required to match.

Search is forgiving:

- **Whole words:** `art` doesn't match "start".
- **Plurals:** `invoices` finds "invoice".
- **Partial words:** the word you're still typing matches as a prefix.
- **Typos:** small typos are forgiven (`recipt` → receipt).
- **File names:** names are split on `_ - .`, capital letters and numbers, so `Q3_TaxReport2023.pdf` matches
  `tax report 2023`.

### 8.2 How results are ranked

There's one result per file, showing its best-matching passage. Results come in this order:

1. **File name** matches: an exact name, a name starting with your words, or all your words in the name.
2. **Text** matches: files containing *all* your words before files containing *some*. The exact phrase ranks above
   scattered words, and words close together rank above words far apart.
3. **Similar meaning** matches: files about the same topic in different words. These are shown only when they clearly
   stand out, so unrelated files don't fill the list.

Each result card shows **why it matched**:

| Label | Meaning |
|---|---|
| *File name* | Your words are in the file name. |
| *Exact phrase* | The exact phrase appears in the text. |
| *All words* | Every word appears in the text. |
| *Some words* | Some of the words appear in the text. |
| *Text in image* | Found in text recognised inside a picture or scan. |
| *Photo details* | Found in the photo's details (camera, date, location). |
| *Similar meaning* | No exact words, but the passage is about the same thing. |

The card also shows the PDF page and how many places in the file matched. Your search words are **highlighted** in
the excerpt.

### 8.3 Search operators

Operators are optional and can be combined.

| Operator | Example | Effect |
|---|---|---|
| `"…"` | `"notice period"` | Exact phrase |
| `-word` | `contract -draft` | Leave out files containing the word |
| `name:word` | `name:invoice` | The word must be in the file name |
| `ext:type` | `ext:pdf lease` | Only files with this extension |
| `type:image` | `type:image receipt` | Only pictures |
| `ext:pdf` on its own | `ext:pdf` | Lists every PDF, newest first |

Example: `"annual leave" name:policy -2021 ext:pdf`

### 8.4 Filters and sorting

Under the search bar:

- **Type:** limit results to a format (PDF, Office, Text, Images…).
- **Date:** *Today*, *Past 7d*, *Past 30d*, *Past 90d*, or **Custom Range…**.
- **Sort:** *Relevance* (default), *Date Modified* (newest first) or *File Size* (largest first).
- **Label chips:** show only documents with a label ([§12.1](#121-labels-tags)).

The **Filters & Sort** button in the top bar opens the full sheet, with tag, format, date range and sort options. A badge
shows how many filters are active. Use **Reset All** to clear them all.

If your search matched documents but every one was hidden by a filter, the app says so and offers **Clear Filters**.

---

## 9. Working with results

### 9.1 The document reader

Tap a result to open the reader. It shows:

- The **matching passages**, with your search words highlighted, and their page numbers.
- **Copy** and **Share**. Sharing sends the passage with the file name and page.
- **Tags**: add or remove labels for this file.
- **Similar documents**: the closest other files, each with a similarity score. Tap one to open it.
- **Chat** button: ask questions about this file only ([§10.3](#103-asking-about-one-file)).
- **Open File**: opens the original document in your usual app, such as a PDF viewer or Word.

### 9.2 Selecting several results

**Long-press** a result to start multi-select. Tap more results, or use **Select All**, then tap the delete icon to
remove them from the index.

> Removing documents from the index **doesn't delete the files** from your phone. They become unsearchable until they
> are indexed again.

---

## 10. Chat with your files

Ask questions in plain language and get an answer written by an AI model that runs **on your phone**, based only on
your indexed documents.

### 10.1 Getting started

- Open it with the **chat** button in the top bar, or from the menu: **Chat with your files**.
- On first install, the default model (**Qwen2.5 0.5B Instruct**, about 550 MB) downloads in the background, over
  Wi-Fi only. The chat screen and the notification both show progress. From either one you can
  **Use mobile data** or **Cancel**.
- Once the model is ready, type a question, for example *"What is the cancellation fee in my gym contract?"*

### 10.2 Answers and sources

The app searches your documents, gives the best passages to the model, and streams the answer back. Every answer lists
its **Sources**, numbered: the files and PDF pages it used. Tap a source to open that document.

> The model can make mistakes. Check important facts against the sources.

### 10.3 Asking about one file

In a document's reader, tap the **chat** button. The chat then shows **Only this file: *name***, and answers use that
file alone. This works well for requests such as *"summarise this"* or *"list the deadlines"*.

### 10.4 Choosing a chat model

Tap the model chip at the top of the chat to open **Chat models**:

| Model | Size | Good for |
|---|---|---|
| **Qwen2.5 0.5B Instruct** (default) | ~550 MB | Fast answers on most phones |
| Qwen2.5 1.5B Instruct | ~1.6 GB | Better answers; needs 6 GB+ RAM |
| DeepSeek-R1 Distill Qwen 1.5B | ~1.8 GB | Step-by-step reasoning; its "thinking" is shown folded |
| TinyLlama 1.1B Chat | ~1.1 GB | Llama-style chat |
| SmolLM 135M Instruct | ~160 MB | Older or low-memory phones |

On this sheet you can **Download**, **Use**, **Cancel** or delete models. **Download on mobile data** controls
whether downloads wait for Wi-Fi. Downloads resume after a lost connection and continue while the app is closed. All
models are open models with open licences (Apache-2.0 or MIT).

---

## 11. Search models

The search model turns text into meaning vectors for *Similar meaning* search. It's separate from the chat model.

Open it from the menu: **Indexing & Search Model** → **Change**.

| Model | Download size | Quality | Speed |
|---|---|---|---|
| **BGE Small v1.5** (default) | ~34 MB | Best for its size | Fast |
| BGE Base v1.5 | ~110 MB | Highest | About 3× slower |
| MiniLM L6 v2 | ~23 MB | Good | Fastest |
| Built-in lightweight | none | Matches words, not meaning | Instant |

- **Download & Install** fetches the model from Hugging Face.
- **Import model files** installs a model you already have (`model.tflite` and `vocab.txt`).
- The sheet shows which model is **Running now**. A model you chose but haven't downloaded yet shows as
  *Selected · not installed*, and the current model keeps working until the new one arrives.
- After switching models, accept **Re-index now** ([§7.5](#75-after-an-update-or-a-model-change)).

---

## 12. Organising your library

### 12.1 Labels (tags)

These labels are built in: **Work, Personal, Finance, Legal, Medical, Education, Receipts, Travel, Research**. You can
add your own, for example `ProjectX`.

- **Add a label:** open a document → tags → pick a quick category, or type a new one.
- **Remove a label:** tap × on it.
- **Filter by label:** tap a label chip above the results.

Labels are kept when a file is indexed again.

### 12.2 Find Duplicates

Menu → **Find Duplicates** lists files whose indexed content is identical or almost identical, even if they have
different names. Tap a file to open it. Nothing is deleted from this screen; remove copies in your file manager.

### 12.3 Clearing the index

Menu → **Clear Document Index** → **Clear All** removes every indexed passage. Your files aren't touched. Run
**Scan My Phone** to rebuild the index.

### 12.4 SMS and chat backups

Menu → **SMS & Chat Backups** imports message backups so conversations become searchable:

- **SMS Backup & Restore** `.xml`
- JSON chat exports
- **WhatsApp** "Export chat" `.txt` files

Tap **Select Backup File** to import one. The switch on that screen controls whether chats are included in search
results.

---

## 13. Home-screen search

| Feature | How to use it |
|---|---|
| **App shortcut** | Long-press the app icon → **Search** opens straight to the search bar. |
| **Widget** | Add the LocalDoc Finder widget to your home screen. It shows how many files are indexed and indexing progress, and opens search when tapped. |
| **Glossy Search Overlay** | Menu → **Glossy Search Overlay** opens a floating, see-through search bar over your current screen. Use its customiser to choose *See-Through*, *Translucent* or *Opaque*, or set an exact opacity. |

---

## 14. Privacy

**Your files, searches, questions and answers never leave your phone.** All reading, indexing, search and chat happen
on the device.

The app connects to the internet for only two things, both download-only:

| Purpose | Server |
|---|---|
| Search and chat model downloads | `huggingface.co`, `hf.co` |
| App updates | `github.com`, `githubusercontent.com` |

This rule is built into the app's code. Every network request must be an HTTPS download from one of those servers.
Anything else is refused and counted.

**Privacy report** (menu) shows what the app has downloaded, how many requests it made, and confirms that it uploaded
nothing. The report also compares the app's own count with Android's traffic counter for the app, so a library sending
data on its own would show up as a mismatch.

Text recognition uses a model built into the app. Pictures are never uploaded.

---

## 15. Updates

The app checks GitHub for a new release about once a day. When one is available:

- A banner and the menu item **Check for Updates (OTA)** show **New v*x.y.z* ready**.
- Tap it to read the release notes, then download and install. The app picks the APK made for your phone's processor.
- Android may ask you to allow **Install unknown apps** for LocalDoc Finder the first time.

After an update, **What's New** appears once. You can reopen it from the menu at any time.

---

## 16. Menu reference

| Section | Item | Description |
|---|---|---|
| Indexing & Search Model | **Change** | Choose, download or import the search model ([§11](#11-search-models)) |
| Sort Search Results | **Sort Results By** | Relevance · Date Modified · File Size |
| Add Documents | **Scan My Phone** · **Choose a Folder** · **Pick Files** · **Added Folders & Files** | [§6](#6-adding-documents) |
| — | **Chat with your files** | [§10](#10-chat-with-your-files) |
| — | **Find Duplicates** | [§12.2](#122-find-duplicates) |
| — | **Privacy report** | [§14](#14-privacy) |
| Performance | **Hardware & Thermal HUD** | Live CPU, memory and temperature, plus a *This device* card (processor, GPU, NPU/TPU) |
| Performance | **Gaming Mode (Pause)** | Pause all indexing |
| Private Data Sources | **SMS & Chat Backups** | [§12.4](#124-sms-and-chat-backups) |
| Settings | **What's New** | Release notes for this version |
| Settings | **Glossy Search Overlay** | [§13](#13-home-screen-search) |
| Settings | **Check for Updates (OTA)** | [§15](#15-updates) |
| Settings | **Dark Theme / Light Theme** | Switch the colour scheme |
| Settings | **Developer Mode** | Show advanced options ([§17](#17-developer-mode-and-the-dev-build)) |
| Settings | **Clear Document Index** | [§12.3](#123-clearing-the-index) |

---

## 17. Developer mode and the Dev build

### Developer mode (all builds)

Turn it on in the menu: **Developer Mode**. It's off by default and adds:

- **Search engine modes:** *Hybrid (RRF)* (the recommended default), *Vector (KNN)* for meaning only, and
  *Keyword (FTS)* for exact words, part numbers and code.
- **Hybrid ranking weights** and **match confidence** charts in Filters & Sort.
- **Extra indexing sources:** Index Downloads Folder, Index Android Folder, Scan Entire Storage,
  **Index by File Types** (choose PDF, Office, Text, Code/Data or Images), plus a picker-based folder and file option.
- **Pixel Tensor & TPU Settings:** thermal pacing, thread pinning and benchmarks.
- A model similarity tester in the search-model sheet.

### Dev build

The Dev build (`…-dev-universal.apk`, app name **LocalDoc Dev**) installs **next to** the regular app and has its own
separate index. It adds:

- A **Developer console**: every network request with byte counts, app events and system log (with copy and share), and
  live app state.
- **Sample data tools:** load a sample knowledge base, generate 100 test files, check the monitored folder.
- A **DEV** badge in the menu header. It updates itself from Dev releases only.

---

## 18. Troubleshooting

| Problem | What to try |
|---|---|
| **No results for a file I know is there** | Check that it's indexed (**Added Folders & Files**, or search `name:<part of its name>`). Check that no filter is active (the badge on Filters & Sort). Check that it isn't in **View Skipped Files**. |
| **A scanned PDF or photo isn't found by its text** | Run **Scan My Phone** once so it's read again with OCR. Text recognition reads Latin-script languages only. |
| **"Similar meaning" results are missing or poor** | Open **Indexing & Search Model**. If it says *Selected · not installed*, download the model. If many passages need re-indexing, tap **Re-index now**. |
| **Indexing is slow** | Plug in the phone and turn the screen off (Turbo), or turn on **Full speed**. Check that **Gaming Mode** isn't on and the phone isn't hot. |
| **Indexing stopped** | You may have tapped **Stop** in the card or the notification. Start a scan again. Make sure battery optimisation isn't blocking the app (Android Settings → Apps → LocalDoc Finder → Battery → *Unrestricted*). |
| **Files show as skipped** | Open **View Skipped Files** to see why. Password-protected and damaged files can't be read. Use **Retry** for anything else. |
| **The chat model won't download** | Downloads wait for Wi-Fi unless **Download on mobile data** is on. Check free storage space. Tap **Try again**, or pick a smaller model. |
| **Chat answers are slow or the app closes during chat** | Switch to a smaller model, such as Qwen2.5 0.5B or SmolLM 135M, and close other apps. |
| **"Open File" says no app can open it** | Install an app for that file type, such as a PDF reader or office suite. |
| **"App not installed" when updating** | The new APK is signed with a different key ([§3](#3-installation)). Uninstall the old version, then install the new one. This clears the index. |
| **Can't pick Downloads in the folder picker** | Android blocks it. Use **Scan My Phone** instead. |

---

## 19. FAQ

**Does it work offline?**
Yes. Search and chat work completely offline. The internet is only needed to download models and updates.

**Does it upload my documents to train AI?**
No. Nothing is uploaded. See the **Privacy report** in the menu.

**Does indexing drain the battery?**
On battery, the app indexes gently. It runs at full speed only while charging with the screen off, or when you turn on
**Full speed**.

**How much space does the index take?**
Usually a small fraction of your documents' size. Chat models take the most space, between 160 MB and 1.8 GB each, and
can be deleted in **Chat models**.

**Does deleting from the index delete my files?**
No. **Clear Document Index** and multi-select delete only affect the app's search database.

**Can I use both the regular app and the Dev build?**
Yes. They install side by side and keep separate indexes.

---

## 20. Limits

- Password-protected Office files and pre-1997 formats (Word 6, Excel 95) are skipped.
- Text recognition reads Latin-script languages only. Scanned PDFs are read up to 50 pages.
- Very large documents are indexed up to about 5,000 passages, roughly 1,250 pages.
- Dates in old `.xls` spreadsheets appear as Excel date numbers.
- Chat answers come from a small on-device model. They are less detailed than large cloud models and can be wrong;
  always check the sources.

---

## 21. Glossary

| Term | Meaning |
|---|---|
| **Index** | The app's private, on-device database of the text and meaning vectors of your files. |
| **Chunk / passage** | A piece of a document, about 1,000 characters, that is searched and shown as an excerpt. |
| **Embedding / meaning vector** | A list of numbers that represents what a passage is about. It's used to find *Similar meaning* results. |
| **Search model** | The small model that creates embeddings (for example BGE Small). |
| **Chat model** | The language model that writes answers in **Chat with your files** (for example Qwen2.5). |
| **OCR** | Optical character recognition: reading text from pictures and scans. |
| **Hybrid search** | Combining keyword matches with meaning matches into one ranked list. |
| **Skipped (quarantined) file** | A file that failed twice while indexing and is left out until you retry it. |

---

*For build instructions, architecture and developer documentation, see the [README](../README.md). Release notes
for each version are in [`release-notes/`](../release-notes/).*
