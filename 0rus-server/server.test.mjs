import assert from "node:assert/strict";
import test from "node:test";

import { once } from "node:events";

import { buildOpenAiRequest, createOrusServer, extractOutputText } from "./server.mjs";

test("la requête active vision, web, fichiers et mémoire", () => {
  const request = buildOpenAiRequest({
    imageBase64: "YWJjZA==",
    mimeType: "image/jpeg",
    previousResponseId: "resp_demo_123",
    guidance: "Méthode du commentaire composé"
  }, {
    OPENAI_MODEL: "gpt-5.6",
    OPENAI_VECTOR_STORE_ID: "vs_demo"
  });

  assert.equal(request.model, "gpt-5.6");
  assert.equal(request.previous_response_id, "resp_demo_123");
  assert.deepEqual(request.tools.map(tool => tool.type), ["file_search", "web_search"]);
  assert.match(request.input[0].content[1].image_url, /^data:image\/jpeg;base64,/);
  assert.equal(request.text.format.type, "json_schema");
});

test("un identifiant mémoire invalide n'est jamais envoyé", () => {
  const request = buildOpenAiRequest({
    imageBase64: "YWJjZA==",
    mimeType: "image/jpeg",
    previousResponseId: "mauvais identifiant",
    guidance: ""
  }, {});
  assert.equal(request.previous_response_id, undefined);
});

test("le texte structuré est extrait du message", () => {
  const text = extractOutputText({
    output: [{ type: "message", content: [{ type: "output_text", text: "{\"ok\":true}" }] }]
  });
  assert.equal(text, "{\"ok\":true}");
});

test("le point d'analyse authentifié renvoie la structure mobile", async () => {
  const fakeFetch = async () => new Response(JSON.stringify({
    id: "resp_test",
    output: [{
      type: "message",
      content: [{
        type: "output_text",
        text: JSON.stringify({
          overview: "Une question détectée",
          detected_count: 1,
          sections: [{
            label: "Question 1",
            question: "Définir un test.",
            kind: "définition",
            answer: "Une vérification reproductible.",
            spoken_answer: "Une vérification reproductible."
          }],
          sources_used: []
        })
      }]
    }]
  }), { status: 200, headers: { "content-type": "application/json" } });

  const server = createOrusServer({
    env: {
      OPENAI_API_KEY: "test-key",
      OPENAI_MODEL: "gpt-5.6",
      ORUS_APP_TOKEN: "test-token"
    },
    fetchImpl: fakeFetch
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  try {
    const address = server.address();
    const response = await fetch(`http://127.0.0.1:${address.port}/v1/analyze`, {
      method: "POST",
      headers: {
        "Authorization": "Bearer test-token",
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        image_base64: "A".repeat(64),
        mime_type: "image/jpeg"
      })
    });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.response_id, "resp_test");
    assert.equal(body.analysis.sections.length, 1);
  } finally {
    server.close();
    await once(server, "close");
  }
});
