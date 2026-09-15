import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  resolve: {
    alias: {
      "scala-core": fileURLToPath(new URL("./scalajs/main.js", import.meta.url))
    }
  },
  build: {
    target: "es2022",
    sourcemap: true
  }
});
