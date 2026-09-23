import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig(({ command }) => ({
  test: { include: ["src/**/*.test.ts"] },
  resolve: {
    dedupe: ["react", "react-dom"],
    alias: {
      "scala-core": fileURLToPath(
        new URL(`./scalajs/${command === "build" ? "full" : "fast"}/main.js`, import.meta.url)
      )
    }
  },
  worker: { format: "es" },
  build: {
    target: "es2022",
    sourcemap: true
  }
}));
