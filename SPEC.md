# Lecture-to-Notes: Specification (v0.1)

An Android app that records or imports audio, transcribes it on the device with Whisper, and turns the transcript into study material: a summary, flashcards, a quiz and a timestamped outline.

## 1. Decisions so far

| Topic | Decision |
|---|---|
| Platform | Android only (arm64-v8a), Kotlin + Jetpack Compose, minSdk 26 |
| Transcription | whisper.cpp over JNI, English-only (`.en`) models, fully on-device |
| Notes engine | Hybrid: an on-device LLM by default, with the cloud LLM as an opt-in, higher-quality option |
| Study outputs | Structured summary, flashcards, practice quiz, timestamped outline |
| Recording lengths | Mixed: voice memos up to 3-hour lectures |
| Language | English only |
| Video | Record with the camera app, or import/share video files. Only the audio track is kept |
| Cloud API keys | The user supplies their own key. There is no backend server |
| Speaker labels | Yes. On-device speaker diarization, and speakers can be renamed |
| Slides/PDFs | Optional attachment for each recording, used to improve transcription and notes |

## 2. User flow

1. **First launch: device check.** The app profiles the phone, runs a short benchmark and recommends a Whisper model and a local LLM (see §4). The user accepts the recommendation or chooses a different model, and the app downloads it.
2. **Capture.** The user records audio inside the app, records a video with the phone's camera app, or imports or shares an audio or video file (m4a, mp3, wav, ogg, opus, mp4, mkv, webm, 3gp, mov). Optionally, they attach slides or a PDF (§7).
3. **Transcribe.** Transcription runs in the background with a progress notification. Each segment keeps its timestamps. Speaker labelling then runs, and the user can rename "Speaker 1" to "Teacher" and so on (§6).
4. **Generate notes.** The user chooses **Local** or **Cloud** for each recording. Local is the default, and Cloud needs the user to confirm.
5. **Study.** The app has a notes view, flashcard review with spaced repetition, a quiz mode and an outline. Tapping a timestamp jumps to that point in the audio.
6. **Export.** Markdown, PDF, or Anki (CSV/TSV deck).

## 3. Audio capture and import

- `AudioRecord` captures **16 kHz mono 16-bit PCM**, which is Whisper's native input, and writes it to WAV. This avoids resampling later.
- Recording runs as a **foreground service** (`FOREGROUND_SERVICE_MICROPHONE`) so a 3-hour lecture survives screen-off and app switching.
- Pause and resume are supported, and audio is flushed to disk every few seconds so a crash can't lose the recording.
- Imported files are decoded with `MediaExtractor` + `MediaCodec`, resampled to 16 kHz mono and cached.
- **Video:** the audio track is copied out without re-encoding (`MediaExtractor` → `MediaMuxer`: AAC/AMR into .m4a, Opus/Vorbis into .webm). This takes seconds, and the video itself is never copied into app storage. Codecs the muxer can't handle fall back to copying the whole file. A video without sound shows a clear error. Videos recorded through the camera button are written to the app cache and deleted once their audio has been extracted.
- Storage estimate: about 1.9 MB/min of WAV (about 115 MB per hour). An option compresses the audio to Opus after transcription to save space.

## 4. Device profiling and model recommendation

### 4.1 Signals collected (built in M2)
- Total RAM, SoC, core count, fast cores, and free storage (`DeviceInfo`).
- **Speed test** (`DeviceCheck`): downloads Tiny if needed, transcribes a bundled 30 s clip of dense speech (`assets/bench30.wav`, the JFK sample repeated), first as a warm-up and then a timed run, and reads whisper.cpp's encode/decode timings. The result is saved, and can be re-run from Settings → Speech models.

### 4.2 Whisper model table (English-only, quantized)

| Model | Size | Min. RAM | Encode / decode cost vs Tiny | Measured per 30 s on a Snapdragon 8s Gen 4 (app open, greedy) |
|---|---|---|---|---|
| `tiny.en` q5_1 | 32 MB | 2 GB | 1× / 1× | ~2 s |
| `base.en` q5_1 | 60 MB | 3 GB | 2.2× / 1.3× | ~3.5 s |
| `small.en` q5_1 | 190 MB | 6 GB | 8.5× / 1.8× | ~8.6 s (matches the app's own measurement) |
| `medium.en` q5_0 | 539 MB | 8 GB | 23× / 2.5× | ~13.5–15 s |

### 4.3 Recommendation rule
1. Keep only models that fit in RAM and in under 50% of free storage.
2. With a speed test: estimate minutes per hour of lecture = (Tiny encode × encode ratio + Tiny decode × decode ratio) × 120 windows.
3. Recommend the **most accurate model that does a 1-hour lecture in ≤ 20 minutes with the app open**. Without a speed test, use a memory-based guess that never picks Medium.
4. The Models screen shows "1-hour lecture ≈ N min with the app open" for each model, and a note that some phones (e.g. vivo) limit background apps to slow cores (measured 2–3× slower).

### 4.4 Local LLM recommendation (for notes)

| RAM | Recommendation |
|---|---|
| < 6 GB | No local LLM. Use the cloud, or a summary-only fallback |
| 6–8 GB | ~1–2B model, Q4 (e.g. Gemma 3 1B / Qwen 2.5 1.5B class) |
| ≥ 8 GB | ~3–4B model, Q4_K_M (e.g. Gemma 3 4B / Llama 3.2 3B class) |

The exact models are configured in a remote-updatable `models.json` so they can be swapped without an app update.

## 5. Transcription pipeline

- whisper.cpp is built with NEON for arm64 and uses as many threads as there are big cores.
- **Chunking:** audio is transcribed in **~2-minute chunks**, each cut at the quietest half-second near the target, so words aren't split and no overlap or de-duplication is needed. Each chunk is a resumable checkpoint. Whisper handles its own 30-second windows inside a chunk.
- **No carry-over prompt between chunks.** Tested on the device, it made Whisper skip or misplace speech that repeated the prompt's words. The `initial_prompt` slot is kept for the slide vocabulary list (M5), which must be re-tested then.
- **Threads = fast cores among the CPUs the app is *currently* allowed to use** (`sched_getaffinity`), re-checked every chunk. Measured on a vivo I2405 (Snapdragon 8s Gen 4): in the background the phone confines the app to cores {0,1,5,6}, or {0–2,5,6}. Oversubscribing (6 threads on 5 cores) was **~14× slower** (408 s vs 30 s for 60 s of audio). If the usable CPUs drop mid-run, the native abort callback stops Whisper within 0.5 s and the chunk is retried with fewer threads.
- **Foreground vs background:** with the app on screen it gets all 8 cores (about 3.5× real time with Small). In the background it is limited to 2 fast cores (about 1× real time). A battery-optimisation exemption did not change this. While a lecture is transcribing, its page keeps the screen on and suggests keeping it open.
- **Import pipelining:** decoding runs concurrently with transcription. The first chunk starts once about 2¾ minutes of audio have been decoded (12 s after sharing a 7-minute video, instead of about 40 s). Decode speed is limited by per-frame IPC to the system codec (about 18k AAC frames per 7 minutes).
- Output: a list of `Segment { startMs, endMs, text }`, stored in Room and shown live as it arrives.
- The transcript can be edited, so the user can fix names and terms before generating notes.
- **Vocabulary hint:** if slides or a PDF are attached, key terms pulled from them are passed to Whisper as its `initial_prompt` (up to about 200 tokens). This helps it spell technical terms correctly.

## 6. Speaker labelling (diarization), built in M3

- **Engine:** sherpa-onnx v1.13.8 (Apache-2.0), fully on-device: pyannote segmentation 3.0 (int8, 1.5 MB) + 3D-Speaker CAM++ English embeddings (30 MB). Both are downloaded on first use and size-checked. Native libraries: `libsherpa-onnx-jni.so` + `libonnxruntime.so` (arm64 and x86_64).
- **Long recordings in 10-minute pieces** (`Diarizer`): an 80-minute lecture is ~300 MB of float samples, too much for a phone's heap. Each piece is diarized on its own (automatic speaker count, clustering threshold 0.5). Each local speaker gets a voice embedding from up to 30 s of their longest turns. `SpeakerLinker` links local speakers to global ones by cosine similarity to running-mean centroids (threshold 0.5; two local speakers of a piece never merge).
- **Assignment:** each transcript segment gets the speaker with the most overlap. Voices under 3 s in total are dropped. Speakers are numbered by talk time; the top one is named "Lecturer" (a suggestion), the others "Speaker N". Rename them, or move the lecturer flag, by tapping a speaker chip.
- **When:** on demand via "Label speakers" (an 80-minute lecture took ~13 min with the app open and drove the test phone to thermal status 4), or automatically after transcription of recordings ≥ 2 min if switched on in Settings (off by default), or via "Label speakers" on the Transcript tab. It shares a heavy-work lock with transcription, so the two never compete for the CPU.
- **Clustering threshold 0.9** (sherpa default 0.5): on a real 80-min classroom recording, 0.5 split the lecturer into 15 "speakers" (8 in the first 10 min); 0.9 gave 2 voices in the first 10 min and was stable up to 1.2. Whether the second voice is students was not yet confirmed.
- **Correcting by ear:** the app says plainly that labels can split or mix people. Tapping a speaker plays up to 5 voice samples (their longest lines, ≤ 8 s each), and offers rename, "this is the lecturer", and **Same person as… → Merge** (`SpeakerEdits.merge`: lines move over, talk time is combined, speakers are renumbered).
- **Notes:** with 2+ speakers, transcript lines are tagged `(Lecturer)` / `(Speaker 2)` where the speaker changes. The prompt warns the labels can be wrong and asks for a **Class Q&A** section (`classQA`: question, asked by, answer, timestamp), shown in the Notes tab and the export.

## 7. Slides and PDF attachments (built in M5)

- **A lecture doesn't need a recording.** Importing a PDF, a PowerPoint (.pptx), photos or a .txt/.md file from the home screen (or sharing one to the app) creates a **slides-only lecture** (`RecordingStatus.DOCUMENT`, no audio). Slides can also be added to any recording from its **Slides** tab.
- **Text extraction, all on-device** (`AttachmentWorker`):
  - PDF: the text layer via PdfBox-Android. Pages with under 20 characters of text (scans) are rendered with `PdfRenderer` and read with ML Kit OCR (bundled Latin model, offline).
  - PPTX: the `<a:t>` runs of each `slideN.xml` in order, plus speaker notes from `notesSlideN.xml`.
  - Images: ML Kit OCR. Text files are split into ~250-word pages.
  - Tested on-device: a text PDF (4/4 exact), a scanned PDF (2/2 via OCR, one letter wrong), a PPTX (4/4 including notes).
- **Slides tab:** attachments with progress and remove; each page rendered (PDF/image) with its extracted text. Slides are numbered 1..n across all of a lecture's files; notes use the same numbers.
- **Notes from slides only:** the prompt says there's no recording, asks the model to turn terse fragments into clear notes, and requires `"at": "Slide N"`. Large decks are split by slides like transcripts. Note items carry a `page` alongside `atMs`, and "Slide N" chips open that slide.
- **Recording + slides:** the transcript is the main source. For each part, `SlidePicker` adds the most relevant slides (TF-IDF word overlap, up to ~700 tokens; ~4k in one-pass mode) as reference for correct terms, formulas and what was shown. Points only on slides are cited as "Slide N".
- **Not done yet:** using slide terms as a Whisper vocabulary hint (it needs testing, since a sentence-style prompt previously hurt transcription), and searching inside slide text.

## 8. Notes generation

### 8.1 Strategy (built in M4)
- **Trigger:** notes are made only when the user taps **Generate notes**. They choose **Brief / Standard (exam-focused) / Detailed** each time, and are shown how much text will be sent and to which provider before anything is sent.
- **Input:** the transcript, one `[mm:ss] text` line per segment, so every note item can cite its moment. Timestamps outside the lecture are dropped.
- **One pass when it fits:** if `transcript + prompt + reply` fits the model's context (for example Claude, or long-context models), the whole lecture goes in one request. This gives the best coherence.
- **Split when it doesn't:** parts of at most ~4k tokens (about 15 minutes of speech), sized to the model's context and to the provider's **learned** per-request limit. The limit is parsed from 413 / "Limit X, Requested Y" errors, since free tiers such as Groq's cap tokens per minute. Each part returns the same JSON shape. Parts are merged in code (concatenated sections; key terms, flashcards and questions deduplicated and spread evenly), plus one small request for the overall title and overview.
- **Resilience:** 429s wait for `retry-after`, and the app paces itself from the `x-ratelimit-*` headers. 5xx and network errors are retried. A reply cut off at `max_tokens` splits that part in two. Invalid JSON gets one retry with a "JSON only" reminder. Finished parts are saved (`partialJson`), so a retry with the same settings resumes. Previous notes stay until new ones succeed.
- Unit-tested with a fake provider (`NotesGeneratorTest`: one-pass, split under a 6k limit, resume, JSON repair, rate-limit wait, timestamp validation).

### 8.2 Output schema (JSON, validated; the app retries once if parsing fails)
```json
{
  "title": "string",
  "summary": [{ "heading": "string", "bullets": ["string"] }],
  "keyTerms": [{ "term": "string", "definition": "string" }],
  "classQA": [{ "question": "string", "askedBy": "string", "answer": "string", "sourceMs": 0 }],
  "outline": [{ "startMs": 0, "title": "string", "points": ["string"] }],
  "flashcards": [{ "front": "string", "back": "string", "sourceMs": 0 }],
  "quiz": [{
    "type": "mcq | short",
    "question": "string",
    "options": ["string"],
    "answer": "string",
    "explanation": "string",
    "sourceMs": 0
  }]
}
```

### 8.3 Engines
- **Local:** llama.cpp over JNI with GGUF models and grammar-constrained JSON output. Runs as a foreground job. Expect a few minutes for a 1-hour lecture on a flagship phone.
- **Cloud (bring your own key):** a pluggable `NotesProvider` interface with two implementations:
  1. **Claude:** the Anthropic Messages API (`claude-sonnet-5-5` for quality, `claude-haiku-4-5` for cheaper runs). It's paid and gives the best notes.
  2. **Open models, OpenAI-compatible:** one generic client for any `/v1/chat/completions` endpoint. It serves free or cheap hosted open-weight models such as Llama, Qwen, Mistral, DeepSeek and Gemma. There are built-in presets (base URL + key), and the user can add others:
     - **Groq** (has a free tier)
     - **OpenRouter** (has a set of `:free` models)
     - **Cerebras** (has a free tier)
     - **Custom URL:** any other compatible server, such as Together, or Ollama / LM Studio / llama.cpp-server running on the user's own PC over Wi-Fi (free and private)
  - **Model choice:** the model list is fetched live from the provider's `/v1/models` endpoint rather than hard-coded, because free models change often. The app suggests an instruction-tuned model with at least 8k context.
  - **Free-tier handling:** requests are rate limited (tokens and requests per minute, from the response headers). Map sections are paced to fit those limits, and a clear "rate limited, resuming in 40 s" status is shown. If a model doesn't reliably return JSON, the app validates the output and repairs it with one retry.
  - **Privacy note:** some free tiers may log or train on the data sent to them. The app shows a one-time warning for each provider, and Local or self-hosted is recommended for sensitive recordings.
  - The user pastes their own API key in Settings. A **Test key** button makes a minimal request to check it.
  - The key is encrypted with an Android Keystore key and is excluded from backups (`android:allowBackup` rules / `dataExtractionRules`).
  - The app calls the provider directly from the phone. There is no backend server and no telemetry.
  - Before each cloud run, the app shows an **estimated token count and cost**, calculated from the transcript 
  and slide length and a price table that the user can edit.
  - Errors are handled clearly: an invalid key, rate limiting (retry with backoff), and loss of connection partway through (finished map sections are kept, so only the remaining ones are re-sent).
  - Only text is sent (the transcript and, optionally, slide text or the PDF). Audio is never sent.
- Settings: default engine, detail level (brief/standard/detailed), and the number of flashcards and quiz questions.

## 9. Study features

- **Notes view:** a rendered summary, key terms and an outline. Every item with a timestamp can be tapped to jump the audio player there.
- **Flashcards (built in M7):** cards from a lecture's notes go into a review deck (`review_cards`). Regenerating notes keeps the schedule of unchanged questions.
  - **Choose what to study:** Study opens a lecture picker with per-lecture due/new/total counts and subject quick-select chips. It is pre-selected from the home filter, or from lectures with cards due. A lecture's Notes tab → *Review flashcards* goes straight into that lecture.
  - **Due cards:** SM-2-style scheduling (Again 1m / Hard 6m / Good 1d / Easy 4d for new cards; intervals then grow by the card's ease, which has a floor of 1.3, with a 365-day cap). 20 new cards a day. Cards due within 20 minutes come back in the same session.
  - **All cards (exam revision):** every card in the selected lectures, shuffled, with Missed/Got it buttons. Does not change the schedule.
- **Quiz (built in M7):** one question at a time with instant feedback, explanation and a "hear it in the lecture" link. Short answers are self-graded. Attempts are saved (`quiz_attempts`), and the Notes tab shows the last and best score.
- **Search (built):** across titles, transcripts and notes. Notes hits show the matching line.
- **Organisation (built in M1):**
  - **Subjects** (courses) have a name and colour. Filter chips on the home screen show All, each subject with its count, and Unfiled.
  - While a subject is selected, new recordings, videos and imports are filed under it automatically.
  - Lectures are grouped by date (Today, Yesterday, Earlier this week, then by month). A colour strip shows each lecture's subject.
  - Long-press starts multi-select, to move lectures to a subject (or create one) or delete several at once.
  - **Search** covers titles and every transcript line. Results show the matching line with its timestamp, and tapping one opens the lecture cued to that moment.
  - The lecture page has a subject chip for refiling. Deleting a subject keeps its lectures and marks them Unfiled.

## 10. Data model (Room)

`Recording(id, title, subjectId?, audioPath, durationMs, createdAt, status, …)`
`Segment(id, recordingId, startMs, endMs, text, speakerId?)`
`Speaker(id, recordingId, clusterIndex, displayName, isLecturer)`
`Attachment(id, recordingId, type[pdf|pptx|image], path, createdAt)`
`AttachmentPage(id, attachmentId, pageNo, text, alignedStartMs?)`
`Notes(id, recordingId, engine, modelName, json, createdAt)`
`Flashcard(id, recordingId, front, back, sourceMs, ease, intervalDays, dueAt)`
`QuizAttempt(id, recordingId, score, takenAt)`
`Subject(id, name, colorIndex, createdAt)`

## 11. Non-functional requirements

- **Privacy:** audio never leaves the device. Cloud use is opt-in for each recording, and the app shows exactly what will be sent.
- **Offline:** the full flow (record, transcribe, local notes, study) works without internet once the models are downloaded.
- **Battery and thermals:** long jobs pause when the phone gets too hot (`PowerManager` thermal status) or the battery is below 15% and not charging.
- **Accessibility:** dynamic text size, TalkBack labels, and a dark theme.

## 11b. Polish (built in M8)

- **Release build:** arm64-only (x86_64 stays in debug builds for the emulator): **28.5 MB** vs 75 MB debug. R8 rules for sherpa-onnx (JNI reads fields by name) and PdfBox's optional JPEG-2000 decoder. Signed with a release key kept outside the repo (`~/.android/lecturenotes-release.jks`, password in git-ignored `keystore.properties`). A `releaseTest` variant (R8 code, separate app id) was smoke-tested on the phone: launch, PDF text layer, OCR, Whisper speed test, transcription, compression, speaker labelling on compressed audio, slide search.
- **Battery and heat:** transcription and speaker labelling wait while the battery is low (WorkManager `batteryNotLow`; transcription is no longer expedited, which WorkManager forbids with that constraint). Between chunks/pieces they pause at `THERMAL_STATUS_SEVERE` and resume at `LIGHT`, showing "Paused: letting the phone cool down".
- **Audio compression:** after transcription the WAV is re-encoded to AAC 32 kbps .m4a (~115 → ~14 MB per hour; measured 960 KB → 124 KB), verified by duration before the WAV is deleted. Speaker labelling decodes to a temporary WAV; re-transcribing goes back through the import path. A Settings switch (on by default) and a one-off "Free up ~X MB" for older recordings.
- **Exports:** Save as PDF (A4, headings/bullets/explanations/terms/Q&A/flashcards/quiz with answers), Markdown, and Anki text import (tab-separated with `#deck`/`#notetype` headers).
- **Search:** also matches slide/PDF text ("Slides: …").
- **Model estimates recalibrated:** the in-app clip's decode is unrealistically cheap, so per-model time = Tiny encode × (encode ratio + decode ratio), with ratios from whisper-cli greedy runs. Small ≈ 15 min/hour (app measured ~17). Medium's RAM requirement is lowered to 6 GB.
- **Not done:** tags (subjects cover organisation), Whisper vocabulary hints from slides.

## 12. Milestones

1. **M1: Capture and transcribe.** Recording service, import, whisper.cpp JNI, chunked background transcription, transcript view.
2. **M2: Device check.** Profiler, benchmark, recommendation screen, model downloader.
3. **M3: Speaker labels.** sherpa-onnx diarization, segment-to-speaker assignment, rename and Lecturer UI.
4. **M4: Notes (cloud).** Bring-your-own-key settings and key test, Claude provider, OpenAI-compatible provider with Groq/OpenRouter/Cerebras/custom presets, rate-limit pacing, map-reduce, JSON schema, cost estimate, notes view.
5. **M5: Slides and PDFs.** Import, text extraction and OCR, slide alignment, Whisper vocabulary hint, adding slide text to notes prompts.
6. **M6: Notes (local).** llama.cpp JNI, local LLM recommendation, grammar-constrained output.
7. **M7: Study.** Flashcards with SM-2, quiz mode, Class Q&A view, timestamp playback, search.
8. **M8: Polish.** Exports (Markdown/PDF), tags, thermal handling, Opus compression.

## 13. Later versions

- Anki export: a CSV/TSV first, and a `.apkg` package later.
- Native providers for OpenAI and Gemini, if the OpenAI-compatible path isn't enough.
