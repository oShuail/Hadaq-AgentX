import { mkdir, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import { defineConfig, type Plugin } from "vite";

const here = dirname(fileURLToPath(import.meta.url));
const SITE_DIR = resolve(here, "public/site");

/**
 * Dev-only helper for the site data bake (http://localhost:5173/bake.html, see tools/site_data/README.md).
 * The bake page runs in the browser (it needs canvas to stitch imagery) and POSTs each finished file here,
 * and this writes it into public/site/. Only file names like "terrain_core.bin" are accepted, only from
 * this machine, and it does not exist in production builds.
 */
function siteBake(): Plugin {
  return {
    name: "hadaq-site-bake",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/__bake/", async (req, res) => {
        const local = ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(req.socket.remoteAddress ?? "");
        const name = (req.url ?? "").replace(/^\//, "").split("?")[0];
        if (req.method !== "POST" || !local || !/^[a-z0-9_]+\.(json|bin|jpg|png)$/.test(name)) {
          res.statusCode = 400;
          res.end("bad request");
          return;
        }
        const chunks: Buffer[] = [];
        let size = 0;
        for await (const chunk of req) {
          size += (chunk as Buffer).length;
          if (size > 64 * 1024 * 1024) {
            res.statusCode = 413;
            res.end("too large");
            return;
          }
          chunks.push(chunk as Buffer);
        }
        await mkdir(SITE_DIR, { recursive: true });
        await writeFile(resolve(SITE_DIR, name), Buffer.concat(chunks));
        res.end(`wrote public/site/${name} (${size} bytes)`);
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), siteBake()],
  build: {
    rollupOptions: { input: { main: resolve(here, "index.html") } }, // bake.html is a dev tool, not part of the app
    chunkSizeWarningLimit: 1500, // three.js
  },
  server: {
    port: 5173,
    host: true,
    fs: { allow: ["../.."] }, // the app imports contracts/building_b01.json from the repo root
    // Microsoft building footprints have no CORS headers, so the bake page reaches them through the dev server.
    proxy: {
      "/__proxy/msb": {
        target: "https://minedbuildings.z5.web.core.windows.net",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/__proxy\/msb/, ""),
      },
    },
  },
});
