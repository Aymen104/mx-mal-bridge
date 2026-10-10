package com.bridge.mx;

import android.content.Context;

import com.google.mediapipe.tasks.genai.llminference.LlmInference;

import java.io.File;
import java.util.List;

/**
 * On-device grounded match fallback backed by a small local LLM.
 *
 * <p>Runs a MediaPipe LLM Inference {@code .task} bundle (default:
 * Qwen2.5-0.5B-Instruct, ~521 MB) entirely on the phone. The model file is not
 * bundled in the APK; it is downloaded (or side-loaded) to
 * {@code <externalFilesDir>/llm/model.task}. When it is absent the brain simply
 * reports not-ready and the deterministic matcher keeps working.
 *
 * <p>Inference is CPU-only and serialised (MediaPipe forbids concurrent
 * generation from one engine), so callers should invoke {@link #pick} off the
 * main thread.
 */
public class LlmBrain implements MatchBrain {

    private static final String TAG = "LlmBrain";
    private static final String MODEL_NAME = "model.task";
    private static final long MIN_MODEL_BYTES = 8L * 1024 * 1024;

    private static LlmBrain shared;

    private final Context ctx;
    private LlmInference llm;
    private String loadError;
    private volatile boolean loading;

    public static synchronized LlmBrain get(Context c) {
        if (shared == null) shared = new LlmBrain(c.getApplicationContext());
        return shared;
    }

    /** Canonical on-device model location. */
    public static File modelFile(Context c) {
        File dir = new File(c.getExternalFilesDir(null), "llm");
        return new File(dir, MODEL_NAME);
    }

    public static boolean hasModel(Context c) {
        File f = modelFile(c);
        return f.isFile() && f.length() >= MIN_MODEL_BYTES;
    }

    private LlmBrain(Context c) {
        this.ctx = c;
    }

    @Override
    public boolean ready() {
        return llm != null;
    }

    @Override
    public String status() {
        if (llm != null) return "ready";
        if (!hasModel(ctx)) return "no model";
        if (loadError != null) return "error";
        if (loading) return "loading";
        return "idle";
    }

    /** Load the model if present. Blocking; safe to call from a worker thread. */
    public synchronized boolean ensureLoaded() {
        if (llm != null) return true;
        File f = modelFile(ctx);
        if (!hasModel(ctx)) {
            loadError = "model missing";
            return false;
        }
        loading = true;
        try {
            LlmInference.LlmInferenceOptions opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.getAbsolutePath())
                    .setMaxTokens(512)
                    .setMaxTopK(1)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build();
            llm = LlmInference.createFromOptions(ctx, opts);
            loadError = null;
            Report.info("ai", "LLM loaded from " + f.getAbsolutePath()
                    + " (" + (f.length() / (1024 * 1024)) + " MB)");
            return true;
        } catch (Throwable t) {
            loadError = String.valueOf(t.getMessage());
            Report.err("ai", "LLM load failed", t);
            return false;
        } finally {
            loading = false;
        }
    }

    @Override
    public int pick(String want, int ep, List<Candidate> cands) {
        if (cands == null || cands.isEmpty()) return -1;
        if (!ensureLoaded()) return -1;
        try {
            String prompt = Grounding.buildPrompt(want, ep, cands);
            long t0 = System.currentTimeMillis();
            String out = llm.generateResponse(prompt);
            int idx = Grounding.parseChoice(out, cands.size());
            Report.info("ai", "pick '" + want + "' -> " + idx
                    + " (" + (System.currentTimeMillis() - t0) + " ms) raw="
                    + (out == null ? "null" : out.trim()));
            return idx;
        } catch (Throwable t) {
            Report.err("ai", "inference failed for '" + want + "'", t);
            return -1;
        }
    }
}
