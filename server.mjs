import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { pathToFileURL } from "node:url";

const MAX_BODY_BYTES = 26 * 1024 * 1024;
const RATE_WINDOW_MS = 10 * 60 * 1000;
const RATE_MAX_REQUESTS = 20;
const rateBuckets = new Map();

export const RESPONSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["overview", "detected_count", "sections", "sources_used"],
  properties: {
    overview: { type: "string" },
    detected_count: { type: "integer" },
    sections: {
      type: "array",
      minItems: 1,
      items: {
        type: "object",
        additionalProperties: false,
        required: ["label", "question", "kind", "answer", "spoken_answer"],
        properties: {
          label: { type: "string" },
          question: { type: "string" },
          kind: { type: "string" },
          answer: { type: "string" },
          spoken_answer: { type: "string" }
        }
      }
    },
    sources_used: {
      type: "array",
      items: { type: "string" }
    }
  }
};

const BASE_INSTRUCTIONS = `Tu es 0rus, un assistant d'étude visuel francophone, utilisé uniquement dans des situations où la capture et l'assistance sont autorisées.

Tu peux recevoir une image unique ou une SÉQUENCE ORDONNÉE d'images provenant d'un scan vidéo. Dans une séquence, plusieurs images peuvent se chevaucher, montrer des parties successives d'une même feuille, plusieurs feuilles, ou un texte de référence suivi de questions. Commence par reconstruire mentalement le document dans l'ordre des images et utilise le texte de contexte pour répondre aux questions qui s'y rapportent. Ne traite pas chaque image comme un document indépendant.

Détecte TOUTES les questions, sous-questions et consignes visibles, puis réponds dans leur ordre. Ne fusionne pas des questions distinctes. Un passage de texte, un tableau, un graphique ou une illustration peut être du CONTEXTE à analyser : conserve-le comme source de compréhension même s'il ne contient pas lui-même de point d'interrogation.

Règles de réponse :
- Adapte la longueur à la demande : une définition simple doit rester courte ; une analyse de texte, une démonstration ou une question méthodologique doit être développée avec la structure attendue.
- Pour un QCM, indique clairement la ou les lettres/réponses correctes et justifie brièvement. Vérifie si plusieurs choix sont possibles.
- Respecte les négations et formulations comme « sauf », « incorrect », « ne…pas ».
- Si une question renvoie à « ce texte », « le document », « l'auteur », un tableau ou un graphique vu dans une autre image de la séquence, utilise ce contexte avant de répondre.
- Quand deux images se chevauchent, n'invente pas de répétition : fusionne mentalement les passages communs et garde la suite nouvelle.
- Si les documents de cours disponibles contiennent la méthode ou la réponse, privilégie-les. Utilise le web seulement pour compléter une information absente ou actuelle.
- N'invente jamais un texte illisible. Explique précisément quelle zone doit être rescannée.
- Réponds en français, sauf si la question exige une autre langue.
- "answer" est la réponse complète affichée. "spoken_answer" est une version naturelle à écouter, sans URL ni mise en forme.
- "sources_used" contient les titres de fichiers réellement consultés et/ou les URL web réellement utilisées. Laisse la liste vide si aucun outil n'a été utilisé.`;

export function buildOpenAiRequest({ imageBase64, mimeType, images, previousResponseId, guidance }, env = process.env) {
  const tools = [];
  const vectorStoreId = String(env.OPENAI_VECTOR_STORE_ID || "").trim();
  if (vectorStoreId) {
    tools.push({
      type: "file_search",
      vector_store_ids: [vectorStoreId],
      max_num_results: 8
    });
  }
  tools.push({ type: "web_search", search_context_size: "medium" });

  const normalizedImages = Array.isArray(images) && images.length > 0
    ? images
    : [{ imageBase64, mimeType }];
  const scanMode = normalizedImages.length > 1;
  const visualContent = normalizedImages.map((image, index) => ({
    type: "input_image",
    image_url: `data:${image.mimeType || "image/jpeg"};base64,${image.imageBase64 || ""}`,
    detail: "high"
  }));

  const personalGuidance = String(guidance || "").trim();
  const request = {
    model: String(env.OPENAI_MODEL || "gpt-5.6").trim(),
    reasoning: { effort: "medium" },
    instructions: personalGuidance
      ? `${BASE_INSTRUCTIONS}\n\nConsigne personnelle de l'utilisateur :\n${personalGuidance.slice(0, 4000)}`
      : BASE_INSTRUCTIONS,
    input: [{
      role: "user",
      content: [
        {
          type: "input_text",
          text: scanMode
            ? `Voici ${normalizedImages.length} vues successives d'un même scan, dans l'ordre chronologique. Reconstitue le contenu à travers les chevauchements, lis d'abord les textes/documents de référence, puis identifie toutes les questions et réponds en utilisant le contexte pertinent.`
            : "Lis cette capture. Identifie les textes de contexte, toutes les questions et réponds selon leurs consignes."
        },
        ...visualContent
      ]
    }],
    tools,
    tool_choice: "auto",
    max_output_tokens: 6000,
    store: true,
    text: {
      format: {
        type: "json_schema",
        name: "orus_visual_answer",
        strict: true,
        schema: RESPONSE_SCHEMA
      }
    }
  };

  if (typeof previousResponseId === "string" && /^resp_[A-Za-z0-9_-]+$/.test(previousResponseId)) {
    request.previous_response_id = previousResponseId;
  }
  return request;
}

export function extractOutputText(response) {
  for (const item of response?.output || []) {
    if (item?.type !== "message") continue;
    for (const content of item.content || []) {
      if (content?.type === "output_text" && typeof content.text === "string") {
        return content.text;
      }
      if (content?.type === "refusal") {
        throw new Error(content.refusal || "La requête a été refusée");
      }
    }
  }
  throw new Error("OpenAI n'a renvoyé aucun texte exploitable");
}

function validToken(provided, expected) {
  if (!provided || !expected) return false;
  const left = Buffer.from(provided);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function clientMayProceed(address) {
  const now = Date.now();
  const bucket = rateBuckets.get(address);
  if (!bucket || now - bucket.startedAt >= RATE_WINDOW_MS) {
    rateBuckets.set(address, { startedAt: now, count: 1 });
    return true;
  }
  bucket.count += 1;
  return bucket.count <= RATE_MAX_REQUESTS;
}

async function readJson(request) {
  let total = 0;
  const chunks = [];
  for await (const chunk of request) {
    total += chunk.length;
    if (total > MAX_BODY_BYTES) {
      const error = new Error("Image trop volumineuse");
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    const error = new Error("Corps JSON invalide");
    error.statusCode = 400;
    throw error;
  }
}

function validatePayload(body) {
  const incoming = Array.isArray(body.images) && body.images.length > 0
    ? body.images
    : [{ image_base64: body.image_base64, mime_type: body.mime_type }];

  if (incoming.length < 1 || incoming.length > 10) {
    const error = new Error("Le scan doit contenir entre 1 et 10 images");
    error.statusCode = 400;
    throw error;
  }

  let totalBase64Length = 0;
  const images = incoming.map((item, index) => {
    const imageBase64 = typeof item?.image_base64 === "string" ? item.image_base64 : "";
    totalBase64Length += imageBase64.length;
    if (imageBase64.length < 32 || imageBase64.length > 6 * 1024 * 1024
        || !/^[A-Za-z0-9+/=]+$/.test(imageBase64)) {
      const error = new Error(`Image ${index + 1} absente ou invalide`);
      error.statusCode = 400;
      throw error;
    }
    return {
      imageBase64,
      mimeType: item?.mime_type === "image/png" ? "image/png" : "image/jpeg"
    };
  });

  if (totalBase64Length > 20 * 1024 * 1024) {
    const error = new Error("Le scan est trop volumineux");
    error.statusCode = 413;
    throw error;
  }

  return {
    images,
    imageBase64: images[0].imageBase64,
    mimeType: images[0].mimeType,
    previousResponseId: String(body.previous_response_id || ""),
    guidance: String(body.guidance || "")
  };
}

function sendJson(response, status, payload) {
  const data = Buffer.from(JSON.stringify(payload));
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": data.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(data);
}

async function callOpenAi(payload, env, fetchImpl) {
  const apiKey = String(env.OPENAI_API_KEY || "").trim();
  if (!apiKey) {
    const error = new Error("OPENAI_API_KEY n'est pas configurée sur le serveur");
    error.statusCode = 503;
    throw error;
  }
  const baseUrl = String(env.OPENAI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
  const upstream = await fetchImpl(`${baseUrl}/responses`, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${apiKey}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify(buildOpenAiRequest(payload, env)),
    signal: AbortSignal.timeout(170_000)
  });
  const text = await upstream.text();
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    body = {};
  }
  if (!upstream.ok) {
    const message = body?.error?.message || `Erreur OpenAI HTTP ${upstream.status}`;
    const error = new Error(message);
    error.statusCode = upstream.status >= 500 ? 502 : 400;
    throw error;
  }
  const outputText = extractOutputText(body);
  let analysis;
  try {
    analysis = JSON.parse(outputText);
  } catch {
    const error = new Error("Réponse structurée OpenAI invalide");
    error.statusCode = 502;
    throw error;
  }
  return { response_id: body.id || "", analysis };
}

export function createOrusServer({ env = process.env, fetchImpl = fetch } = {}) {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url || "/", "http://localhost");
      if (request.method === "GET" && url.pathname === "/health") {
        sendJson(response, 200, {
          ok: true,
          service: "0rus-server",
          model: String(env.OPENAI_MODEL || "gpt-5.6"),
          documents: Boolean(env.OPENAI_VECTOR_STORE_ID)
        });
        return;
      }
      if (request.method !== "POST" || url.pathname !== "/v1/analyze") {
        sendJson(response, 404, { error: "Route inconnue" });
        return;
      }

      const authorization = String(request.headers.authorization || "");
      const providedToken = authorization.startsWith("Bearer ")
        ? authorization.slice(7).trim()
        : "";
      if (!validToken(providedToken, String(env.ORUS_APP_TOKEN || ""))) {
        sendJson(response, 401, { error: "Jeton 0rus invalide" });
        return;
      }
      if (!clientMayProceed(request.socket.remoteAddress || "unknown")) {
        sendJson(response, 429, { error: "Trop de requêtes, réessayez dans quelques minutes" });
        return;
      }

      const payload = validatePayload(await readJson(request));
      const result = await callOpenAi(payload, env, fetchImpl);
      sendJson(response, 200, result);
    } catch (error) {
      const status = Number.isInteger(error.statusCode) ? error.statusCode : 500;
      const safeStatus = status >= 400 && status <= 599 ? status : 500;
      sendJson(response, safeStatus, {
        error: error instanceof Error ? error.message : "Erreur serveur"
      });
    }
  });
}

const launchedDirectly = process.argv[1]
  && import.meta.url === pathToFileURL(process.argv[1]).href;

if (launchedDirectly) {
  const port = Number.parseInt(process.env.PORT || "8787", 10);
  createOrusServer().listen(port, "0.0.0.0", () => {
    process.stdout.write(`0rus-server écoute sur le port ${port}\n`);
  });
}
