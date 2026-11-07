# Sensitive-image check: the model file

The feature (blur + admin warning for groups and channels) is built, but **the app contains no model file yet**, so it is OFF everywhere
until you add one. No model was committed: the only practical candidate is a third-party conversion (see "Provenance") and you should decide
on it yourself.

## Where the check runs (privacy)
- Only in **groups and channels**. Private chats and Saved Messages are refused in code before the model is even loaded
  (`NsfwLogic.scan` / `NsfwRuntime.scan`, with unit tests; a test also fails if any other file touches TensorFlow Lite).
- On the **receiving phone**, after the picture was downloaded and decrypted for display. The send path never waits for it.
- Nothing leaves the phone for scanning. Only a flag (a 0-100 strength bucket + message id) is written, and only for groups and channels.
- Each person can switch it off for themselves (Privacy and security), owners/admins can switch it off per group/channel.

## Chosen model
**Marqo/nsfw-image-detection-384** (Hugging Face), TensorFlow Lite conversion by `kishormainali/marqo-nsfw-384`.

| | |
|---|---|
| Licence | **Apache-2.0** (Hugging Face metadata `license:apache-2.0` and the model card front matter), free for commercial use |
| Base | fine-tune of `timm/vit_tiny_patch16_384.augreg_in21k_ft_in1k` (ViT-Tiny, 5.7 M parameters, Apache-2.0) |
| Training data | "proprietary dataset of 220,000 images" (100k NSFW / 100k SFW; photos, drawings, memes, AI images). The card states no licence restriction on the weights, but it does not say where the images came from. |
| Accuracy (model card) | 98.56 % on the authors' own 20,000-image test set. Their precision/recall curves are in the card. **Not measured by us; real-world false-positive rates will differ.** |
| File | `marqo-nsfw-384.tflite`, **22,594,864 bytes** (float32, not compressible) |
| sha256 | `16f4ff3d07f23b9f8a335e0dc813d329a63b4ff3c9b3e255fae4afc15be0620b` |
| Input | `float32 [1, 3, 384, 384]`, channel-first, values `(pixel/255 - 0.5) / 0.5` (-1..1) |
| Output | `float32 [1, 2]`, probabilities, **index 0 = NSFW, index 1 = SFW** (from the model's `config.json`) |
| Ops | 648 standard TFLite built-in ops (FULLY_CONNECTED, BATCH_MATMUL, GELU, SOFTMAX, CONV_2D, ...), no custom ops |
| Speed | ~3.2 GMACs per pass. On a laptop CPU (2 threads, XNNPACK) one pass took 65-95 ms. A low-end phone (Cortex-A53 class) is roughly 5-10x slower: expect **0.4-1 s per pass, two passes per picture** (whole picture + centre), once per picture, one picture at a time, after it is shown. This is an estimate from MACs and the desktop run, not a phone measurement. |

Checked here: the file loads in LiteRT 2.3.0 (Python), has the input/output above and runs on synthetic noise and a plain grey image
(crash/speed test only; no explicit images were downloaded or used). The Android runtime `org.tensorflow:tensorflow-lite:2.16.1` resolves
through the offline gradle mirror and the app compiles against it; running the model with that runtime **on a phone is untested**.

### Provenance (please read)
The official weights are PyTorch/safetensors only. The TFLite file comes from a third-party Hugging Face user. His page has no model card
(only `license: apache-2.0`). I verified the shape/ops/sha256 above but cannot prove the weights equal the official ones. Options:
1. Accept it and pin the hash (the loader accepts any file at the path; the sha256 above is what I tested).
2. Convert it yourself from the official `model.safetensors` (e.g. `ai-edge-torch`) and use your own file; keep input/output as above.

## Why not the others
- **Falconsai/nsfw_image_detection** (Apache-2.0): ViT-Base, ~86 M parameters, ~330 MB. Too large for a phone.
- **NudeNet**: AGPL, rejected as the brief says.
- **taufiqdp/mobilenetv4_conv_small...nsfw_classifier** (ONNX, Apache-2.0 tag, 10 MB): smaller and faster, but the model card is empty (no data, no accuracy). Cannot be judged; also ONNX would need the full ONNX Runtime (the project's one is a minimal custom build).
- **nostrbuild/nsfw-image-detection-large**: CC-BY-NC-SA, rejected (non-commercial).
- **GantMan nsfw_model / nsfwjs (MIT code), Yahoo open_nsfw**: GitHub was not reachable from my tools (only this repository was allowed), so I could not read their licences or weights terms. Not evaluated, not chosen.

## How to switch it on
1. Download the file (about 22.6 MB) and check the hash:
   ```
   curl -L -o marqo-nsfw-384.tflite https://huggingface.co/kishormainali/marqo-nsfw-384/resolve/main/marqo-nsfw-384.tflite
   sha256sum marqo-nsfw-384.tflite
   ```
2. Put it at `wetalks/app/src/main/assets/nsfw/model.tflite` and build. (`tflite` is excluded from compression in `build.gradle.kts`, so it is
   memory-mapped straight from the APK.) A copy in the app's private folder `files/nsfw/model.tflite` wins over the bundled one, which would
   allow a later download feature without a new APK.
3. APK size: **+22.6 MB** for the model in each ABI split (assets are shared by both splits) plus the TensorFlow Lite runtime `libtensorflowlite_jni.so`
   (measured in the AAR: 3.4 MB arm64-v8a, 2.2 MB armeabi-v7a, before APK compression; no APK was built here, so the final APK delta is not measured).
   Total expected: roughly +26 MB per ABI APK. If that is too much, ship the model as a later download into `files/nsfw/model.tflite`.
4. Thresholds are placeholders: Strict 0.70/0.45, Balanced 0.85/0.60, Lenient 0.95/0.80 (blur / flag-only). They are **not calibrated** on
   real pictures. Before release, test with your own safe test set (and your own consenting-adult/known-benign examples) and adjust
   `NsfwLevel` in `data/NsfwLogic.kt`.
