#!/usr/bin/env python3
"""Export a BERT-family sentence-embedding model to the TFLite files LocalDoc Finder loads.

Output (in --out/<model-id>/):
    model.tflite   int8 dynamic-range quantised transformer, batch 1, fixed sequence length
    vocab.txt      the WordPiece vocabulary

The graph takes (input_ids, attention_mask, token_type_ids) as int32 [1, L] and returns the per-token
hidden states [1, L, D]. Pooling (CLS for BGE, mean for MiniLM) and L2 normalisation happen in the app, so
the same loader works for every model. See app/.../engine/embedding/TfliteTextEmbedder.kt.

Run this on a computer, not on the phone. Requirements (Python 3.10-3.13):

    pip install tensorflow tf-keras "transformers<5" torch

Examples:

    python tools/export_embedding_model.py bge_small_en_v15 --out build/models
    python tools/export_embedding_model.py all_minilm_l6_v2 --out build/models --seq-len 128

Then either `adb push build/models/bge_small_en_v15 \
/sdcard/Android/data/com.aistudio.vectorsearch.dvmxqe/files/embedding_models/` or use
"Import model files" in the app's Embedding Models sheet, or copy the folder to
app/src/main/assets/models/ to bundle it in the APK.

Self-test with no download (random weights; checks the export wiring only, not model quality):

    python tools/export_embedding_model.py --self-test
"""
import argparse
import os
import sys
import tempfile

os.environ.setdefault("TF_USE_LEGACY_KERAS", "1")  # transformers' TF models need Keras 2 (tf-keras)

# model id (matches EmbeddingModelType.directory) -> (Hugging Face checkpoint, hidden size)
MODELS = {
    "bge_small_en_v15": ("BAAI/bge-small-en-v1.5", 384),
    "bge_base_en_v15": ("BAAI/bge-base-en-v1.5", 768),
    "all_minilm_l6_v2": ("sentence-transformers/all-MiniLM-L6-v2", 384),
}


def convert(tf_model, seq_len):
    """Wraps `tf_model` in a fixed-signature function and converts it to a quantised TFLite flatbuffer."""
    import tensorflow as tf

    @tf.function(input_signature=[
        tf.TensorSpec([1, seq_len], tf.int32, name="input_ids"),
        tf.TensorSpec([1, seq_len], tf.int32, name="attention_mask"),
        tf.TensorSpec([1, seq_len], tf.int32, name="token_type_ids"),
    ])
    def serve(input_ids, attention_mask, token_type_ids):
        out = tf_model(input_ids=input_ids, attention_mask=attention_mask,
                       token_type_ids=token_type_ids, training=False)
        return {"last_hidden_state": out.last_hidden_state}

    concrete = serve.get_concrete_function()
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], tf_model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]  # int8 weights, float activations
    return converter.convert(), serve


def verify(tflite_bytes, serve, seq_len, hidden):
    """Runs the TFLite model next to the original and checks the hidden states agree."""
    import numpy as np
    import tensorflow as tf

    interp = tf.lite.Interpreter(model_content=tflite_bytes)
    interp.allocate_tensors()
    print("TFLite inputs :")
    for d in interp.get_input_details():
        print("   ", d["name"], d["dtype"].__name__, list(d["shape"]))
    print("TFLite outputs:")
    for d in interp.get_output_details():
        print("   ", d["name"], d["dtype"].__name__, list(d["shape"]))

    rng = np.random.default_rng(0)
    ids = rng.integers(5, 900, size=(1, seq_len)).astype(np.int32)
    mask = np.ones((1, seq_len), np.int32)
    mask[0, seq_len // 2:] = 0
    types = np.zeros((1, seq_len), np.int32)
    feed = {"input_ids": ids, "attention_mask": mask, "token_type_ids": types}
    for d in interp.get_input_details():
        key = next(k for k in feed if k in d["name"])
        interp.set_tensor(d["index"], feed[key])
    interp.invoke()
    got = interp.get_tensor(interp.get_output_details()[0]["index"])
    ref = serve(tf.constant(ids), tf.constant(mask), tf.constant(types))["last_hidden_state"].numpy()
    assert got.shape == (1, seq_len, hidden), f"unexpected output shape {got.shape}"

    valid = mask[0] == 1
    a, b = got[0][valid], ref[0][valid]
    cos = (a * b).sum(-1) / (np.linalg.norm(a, axis=-1) * np.linalg.norm(b, axis=-1) + 1e-9)
    print(f"cosine(tflite, original) over real tokens: min={cos.min():.4f} mean={cos.mean():.4f}")
    assert cos.min() > 0.95, "quantised model drifted too far from the original"


def export(model_id, out_dir, seq_len):
    from huggingface_hub import hf_hub_download
    from transformers import TFAutoModel

    checkpoint, hidden = MODELS[model_id]
    print(f"Loading {checkpoint} ...")
    model = TFAutoModel.from_pretrained(checkpoint, from_pt=True)
    data, serve = convert(model, seq_len)
    verify(data, serve, seq_len, hidden)

    target = os.path.join(out_dir, model_id)
    os.makedirs(target, exist_ok=True)
    with open(os.path.join(target, "model.tflite"), "wb") as f:
        f.write(data)
    vocab_src = hf_hub_download(checkpoint, "vocab.txt")
    with open(vocab_src, "rb") as src, open(os.path.join(target, "vocab.txt"), "wb") as dst:
        dst.write(src.read())
    print(f"Wrote {target}/model.tflite ({len(data) / 1e6:.1f} MB) and vocab.txt")


def self_test():
    """Exports a tiny randomly-initialised BERT to prove the signature, names and quantisation work."""
    from transformers import BertConfig, TFBertModel

    seq_len, hidden = 32, 64
    config = BertConfig(vocab_size=1000, hidden_size=hidden, num_hidden_layers=2, num_attention_heads=2,
                        intermediate_size=128, max_position_embeddings=64)
    model = TFBertModel(config)
    data, serve = convert(model, seq_len)
    verify(data, serve, seq_len, hidden)
    out = os.path.join(tempfile.gettempdir(), "tiny_bert.tflite")
    with open(out, "wb") as f:
        f.write(data)
    print(f"SELF-TEST OK ({len(data) / 1e3:.0f} KB) -> {out}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model", nargs="?", choices=sorted(MODELS), help="model id to export")
    parser.add_argument("--out", default="build/models", help="output directory")
    parser.add_argument("--seq-len", type=int, default=256, help="fixed sequence length baked into the graph")
    parser.add_argument("--self-test", action="store_true", help="export a tiny random model, no downloads")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    elif args.model:
        export(args.model, args.out, args.seq_len)
    else:
        parser.error("give a model id or --self-test")
        sys.exit(2)
