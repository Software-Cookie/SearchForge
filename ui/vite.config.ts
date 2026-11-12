/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, /api and /actuator go to Spring Boot (same origin in production,
// so no CORS is ever configured). Override the target with SF_API if 8080 is taken.
const api = process.env.SF_API ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": api, "/actuator": api },
  },
  build: {
    outDir: "dist",
    sourcemap: false,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});
