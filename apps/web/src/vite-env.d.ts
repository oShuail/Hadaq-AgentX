/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_AGENTS_URL?: string;
  readonly VITE_FOCUS_MODEL_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
