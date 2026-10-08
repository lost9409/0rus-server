import { readFile, readdir, stat } from "node:fs/promises";
import { basename, extname, join, resolve } from "node:path";

const SUPPORTED_EXTENSIONS = new Set([
  ".c", ".cpp", ".cs", ".css", ".doc", ".docx", ".go", ".html", ".java",
  ".js", ".json", ".md", ".pdf", ".php", ".pptx", ".py", ".rb", ".sh",
  ".tex", ".ts", ".txt"
]);

const apiKey = String(process.env.OPENAI_API_KEY || "").trim();
if (!apiKey) {
  throw new Error("Définissez OPENAI_API_KEY avant de lancer l’import");
}

const sourceDirectory = resolve(process.argv[2] || "documents");
if (!(await stat(sourceDirectory).catch(() => null))?.isDirectory()) {
  throw new Error(`Dossier introuvable : ${sourceDirectory}`);
}

async function collectFiles(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await collectFiles(path));
    } else if (entry.isFile() && SUPPORTED_EXTENSIONS.has(extname(entry.name).toLowerCase())) {
      files.push(path);
    }
  }
  return files;
}

async function openAi(path, options = {}) {
  const response = await fetch(`https://api.openai.com/v1${path}`, {
    ...options,
    headers: {
      "Authorization": `Bearer ${apiKey}`,
      ...(options.headers || {})
    }
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    throw new Error(body?.error?.message || `Erreur OpenAI HTTP ${response.status}`);
  }
  return body;
}

async function uploadFile(path) {
  const bytes = await readFile(path);
  const form = new FormData();
  form.append("purpose", "assistants");
  form.append("file", new Blob([bytes]), basename(path));
  return openAi("/files", { method: "POST", body: form });
}

const files = await collectFiles(sourceDirectory);
if (files.length === 0) {
  throw new Error("Aucun fichier compatible trouvé dans le dossier");
}

let vectorStoreId = String(process.env.OPENAI_VECTOR_STORE_ID || "").trim();
if (!vectorStoreId) {
  const vectorStore = await openAi("/vector_stores", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: "0rus — documents utilisateur" })
  });
  vectorStoreId = vectorStore.id;
  process.stdout.write(`Nouvelle base documentaire : ${vectorStoreId}\n`);
}

for (const [index, path] of files.entries()) {
  process.stdout.write(`[${index + 1}/${files.length}] ${basename(path)} : envoi…\n`);
  const uploaded = await uploadFile(path);
  await openAi(`/vector_stores/${encodeURIComponent(vectorStoreId)}/files`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ file_id: uploaded.id })
  });
}

process.stdout.write("Indexation lancée. Attendez que les fichiers soient marqués completed dans le tableau de bord OpenAI.\n");
process.stdout.write(`Ajoutez ceci au serveur : OPENAI_VECTOR_STORE_ID=${vectorStoreId}\n`);
