package com.ahmed.assistant;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client réseau minimal : la clé OpenAI reste exclusivement sur le serveur. */
final class AiClient {

    interface Callback {
        void onSuccess(AiResponse response);

        void onError(String message);
    }

    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    void analyze(
            ContentResolver resolver,
            Uri imageUri,
            String serverUrl,
            String accessToken,
            String previousResponseId,
            String guidance,
            Callback callback) {
        executor.execute(() -> {
            try {
                byte[] image = readLimited(resolver.openInputStream(imageUri), MAX_IMAGE_BYTES);
                JSONObject request = new JSONObject();
                request.put("image_base64", Base64.encodeToString(image, Base64.NO_WRAP));
                request.put("mime_type", "image/jpeg");
                request.put("previous_response_id", previousResponseId == null ? "" : previousResponseId);
                request.put("guidance", guidance == null ? "" : guidance);

                JSONObject result = postJson(normalizeAnalyzeUrl(serverUrl), accessToken, request);
                callback.onSuccess(AiResponse.fromServerJson(result));
            } catch (Exception error) {
                String message = error.getMessage();
                callback.onError(message == null || message.isBlank()
                        ? error.getClass().getSimpleName()
                        : message);
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    static String normalizeAnalyzeUrl(String serverUrl) {
        String normalized = serverUrl == null ? "" : serverUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("https://")) {
            throw new IllegalArgumentException("L’adresse du serveur doit commencer par https://");
        }
        if (!normalized.endsWith("/v1/analyze")) {
            normalized += "/v1/analyze";
        }
        return normalized;
    }

    private static JSONObject postJson(String target, String token, JSONObject request)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(180_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }

            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String responseText = readText(stream, MAX_RESPONSE_BYTES);
            JSONObject response = responseText.isBlank()
                    ? new JSONObject()
                    : new JSONObject(responseText);
            if (status < 200 || status >= 300) {
                throw new IOException(response.optString("error", "Erreur serveur HTTP " + status));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readLimited(InputStream rawStream, int limit) throws IOException {
        if (rawStream == null) {
            throw new IOException("Photo introuvable");
        }
        try (InputStream input = new BufferedInputStream(rawStream);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) {
                    throw new IOException("La photo dépasse 12 Mo");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String readText(InputStream stream, int limit) throws IOException {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (text.length() + count > limit) {
                    throw new IOException("Réponse serveur trop volumineuse");
                }
                text.append(buffer, 0, count);
            }
            return text.toString();
        }
    }
}
