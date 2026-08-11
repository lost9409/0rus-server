package com.ahmed.assistant;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Réponse structurée renvoyée par le serveur privé 0rus. */
final class AiResponse {

    static final class Section {
        final String label;
        final String question;
        final String kind;
        final String answer;
        final String spokenAnswer;

        Section(String label, String question, String kind, String answer, String spokenAnswer) {
            this.label = label;
            this.question = question;
            this.kind = kind;
            this.answer = answer;
            this.spokenAnswer = spokenAnswer;
        }

        String displayText() {
            StringBuilder text = new StringBuilder(label);
            if (!kind.isBlank()) {
                text.append(" · ").append(kind.toUpperCase(Locale.FRANCE));
            }
            if (!question.isBlank()) {
                text.append("\n").append(question);
            }
            text.append("\n\n").append(answer);
            return text.toString();
        }
    }

    final String responseId;
    final String overview;
    final List<Section> sections;
    final List<String> sourcesUsed;

    private AiResponse(
            String responseId,
            String overview,
            List<Section> sections,
            List<String> sourcesUsed) {
        this.responseId = responseId;
        this.overview = overview;
        this.sections = Collections.unmodifiableList(sections);
        this.sourcesUsed = Collections.unmodifiableList(sourcesUsed);
    }

    static AiResponse fromServerJson(JSONObject root) throws JSONException {
        JSONObject analysis = root.getJSONObject("analysis");
        JSONArray sectionArray = analysis.getJSONArray("sections");
        List<Section> sections = new ArrayList<>();
        for (int index = 0; index < sectionArray.length(); index++) {
            JSONObject section = sectionArray.getJSONObject(index);
            sections.add(new Section(
                    section.optString("label", "Question " + (index + 1)),
                    section.optString("question"),
                    section.optString("kind"),
                    section.optString("answer"),
                    section.optString("spoken_answer", section.optString("answer"))));
        }
        if (sections.isEmpty()) {
            throw new JSONException("Le serveur n'a renvoyé aucune réponse");
        }

        List<String> sources = new ArrayList<>();
        JSONArray sourceArray = analysis.optJSONArray("sources_used");
        if (sourceArray != null) {
            for (int index = 0; index < sourceArray.length(); index++) {
                String source = sourceArray.optString(index).trim();
                if (!source.isEmpty()) {
                    sources.add(source);
                }
            }
        }

        return new AiResponse(
                root.optString("response_id"),
                analysis.optString("overview"),
                sections,
                sources);
    }
}
