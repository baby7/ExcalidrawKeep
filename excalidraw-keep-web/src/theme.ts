export type Theme = "light" | "dark";

/** 只是首屏缓存：真正的偏好在数据库里（app_setting 表）。 */
const STORAGE_KEY = "excalidraw-keep-theme";

/**
 * 把主题写到 <html data-theme>：样式表里的 CSS 变量和 Excalidraw 之外的界面
 * 都靠这个属性切换；顺带把缓存更新掉，下次打开页面不用等接口就能先渲染。
 */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  try {
    window.localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // 隐私模式/禁用 localStorage：只影响首屏不闪，不影响功能
  }
}

export function readCachedTheme(): Theme {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === "dark" ? "dark" : "light";
  } catch {
    return "light";
  }
}
