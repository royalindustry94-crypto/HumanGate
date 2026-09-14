import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Routes /api/* to the FastAPI service so the frontend never hardcodes an
// absolute backend URL. FastAPI routes are /health, /workspaces, ... — strip
// the /api prefix.
const apiProxy = {
  "/api": {
    target: "http://localhost:8000",
    changeOrigin: true,
    rewrite: (path: string) => path.replace(/^\/api/, ""),
  },
};

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: apiProxy,
  },
  // `vite preview` serves the production build. The Replit preview entry
  // (scripts/run_ops_preview_replit.sh) uses it instead of the dev server, so
  // it needs the same proxy — `server.proxy` does not apply to preview.
  preview: {
    port: 5173,
    proxy: apiProxy,
  },
});
