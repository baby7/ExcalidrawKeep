// 把 @excalidraw/excalidraw 自带的字体复制到 public/fonts。
// 0.18.0 起资源目录是 dist/prod（生产）与 dist/dev（开发），不再是旧的 excalidraw-assets。
// 配合 index.html 里的 window.EXCALIDRAW_ASSET_PATH = "/"，
// 浏览器会去 /fonts/... 取字体，从而不必依赖外部 CDN（国内网络/CDN 更稳）。
import { cpSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const webRoot = dirname(here);
const packageRoot = join(webRoot, "node_modules", "@excalidraw", "excalidraw", "dist");
const target = join(webRoot, "public", "fonts");

function findFontsDir() {
  for (const variant of ["prod", "dev"]) {
    const candidate = join(packageRoot, variant, "fonts");
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

const source = findFontsDir();
if (!source) {
  console.warn(
    "[copy-fonts] 没找到 Excalidraw 的字体目录，先执行 npm install 再运行本脚本。",
  );
  process.exit(0);
}

mkdirSync(target, { recursive: true });
cpSync(source, target, { recursive: true });
console.log(
  `[copy-fonts] ${source} -> ${target}（${readdirSync(target).length} 个文件）`,
);
