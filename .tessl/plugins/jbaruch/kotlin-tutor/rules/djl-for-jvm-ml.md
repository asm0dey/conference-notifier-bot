---
alwaysApply: true
---

# DJL for JVM ML Inference

## DJL Is the JVM ML Default

- For on-JVM model inference — image classification, embeddings, NLP — use **[DJL](https://djl.ai/)** (`ai.djl:api` + an engine artifact)
- DJL is the JetBrains/Amazon-stewarded Java/Kotlin native ML runtime. It abstracts over PyTorch, TensorFlow, ONNX Runtime, MXNet behind a single `Predictor<I, O>` API.
- Don't shell out to Python (`subprocess.exec("python infer.py")`), don't bundle a separate Python venv, don't hand-write JNI bindings.

## Engine Choice

- **PyTorch engine** (`ai.djl.pytorch:pytorch-engine`) — broadest model zoo coverage, default pick for HuggingFace-style models
- **ONNX Runtime engine** (`ai.djl.onnxruntime:onnxruntime-engine`) — smaller footprint, pick when your model is already exported to ONNX (vision classifiers, FER+)
- **TensorFlow engine** — for TF SavedModel artifacts; rare outside legacy
- Use the **BOM** (`platform("ai.djl:bom:0.36.0")`) so all DJL artifacts align on a single version

## Idiomatic Use

- Load the model once at startup, hold a `ZooModel<I, O>` for the process lifetime: model loading is slow, prediction is cheap
- `model.newPredictor()` per worker thread / coroutine — `Predictor` is not thread-safe
- Always close: `predictor.close()` then `model.close()` on shutdown (or use `.use { ... }`)
- For HuggingFace image models, write a small `Translator<I, O>` — DJL provides `ImageClassificationTranslator` as a starting point; copy and adapt for ViT/FER+ models

## Caching

- DJL caches downloaded weights under `~/.djl.ai/cache/`. First run on a new model pulls hundreds of MB.
- **Pre-warm the cache** before any environment without reliable network (CI, conference Wi-Fi, demo machine). Just run any `newPredictor()` once.

## Anti-patterns

- ❌ Embedding a Python interpreter (Jython, GraalPy, PyO3) just to call PyTorch — DJL gives you PyTorch directly
- ❌ Calling `model.newPredictor()` per request — the `Predictor` allocation is non-trivial; pool one per worker
- ❌ Hardcoding a model URL in code without a fallback path — versioned model artifacts move
- ❌ Mixing engines in the same project (PyTorch + ONNX + TF together) unless you genuinely need all three — each one pulls hundreds of MB of native libs
