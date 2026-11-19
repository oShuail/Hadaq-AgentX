/// <reference types="vite/client" />

declare const CESIUM_BASE_URL: string;

interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_AGENTS_URL?: string;
  readonly VITE_CESIUM_ION_TOKEN?: string;
  readonly VITE_FOCUS_OSM_ID?: string;
  readonly VITE_FOCUS_MODEL_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
