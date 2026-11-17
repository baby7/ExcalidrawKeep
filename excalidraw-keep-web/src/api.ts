// 后端接口封装。约定：
//  - 场景（elements/appState）只有一次 PUT，附件（图片）走内容寻址的单独 PUT；
//  - 保存必须带 baseVersion，冲突时后端回 409 + currentVersion，前端据此合并重试。

import type { Theme } from "./theme";

export interface DrawingSummary {
  id: string;
  name: string;
  preview: string | null;
  version: number;
  updatedAt: string;
}

export interface SceneResponse {
  id: string;
  name: string;
  version: number;
  elements: unknown[];
  appState: Record<string, unknown>;
  /** fileId -> data:image/png;base64,... 后端已把附件内联进来 */
  files: Record<string, string>;
  createdAt: string;
  updatedAt: string;
}

export interface PageResponse<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** 全局设置（目前只有主题），存在后端的 app_setting 表里。 */
export interface SettingsResponse {
  theme: Theme;
}

export class ApiError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

/** 409：别人（另一个标签页/窗口）先保存了，需要合并后重试。 */
export class ConflictError extends ApiError {
  currentVersion: number;

  constructor(message: string, currentVersion: number) {
    super(message, 409, "VERSION_CONFLICT");
    this.name = "ConflictError";
    this.currentVersion = currentVersion;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const isBinary = init.body instanceof Blob;
  if (init.body !== undefined && !isBinary) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });
  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  let payload: any = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok) {
    const message =
      (payload && payload.message) || `请求失败（HTTP ${response.status}）`;
    const code = payload && payload.code;
    if (
      response.status === 409 &&
      payload &&
      typeof payload.currentVersion === "number"
    ) {
      throw new ConflictError(message, payload.currentVersion);
    }
    throw new ApiError(message, response.status, code);
  }

  return payload as T;
}

export const apiClient = {
  listDrawings(q: string, page = 0, size = 24): Promise<PageResponse<DrawingSummary>> {
    const query = `q=${encodeURIComponent(q)}&page=${page}&size=${size}`;
    return request(`/api/drawings?${query}`);
  },

  createDrawing(name: string): Promise<DrawingSummary> {
    return request(`/api/drawings`, {
      method: "POST",
      body: JSON.stringify({ name }),
    });
  },

  getScene(id: string): Promise<SceneResponse> {
    return request(`/api/drawings/${encodeURIComponent(id)}`);
  },

  saveScene(
    id: string,
    baseVersion: number,
    elements: unknown[],
    appState: Record<string, unknown>,
  ): Promise<SceneResponse> {
    return request(`/api/drawings/${encodeURIComponent(id)}/scene`, {
      method: "PUT",
      body: JSON.stringify({ baseVersion, elements, appState }),
    });
  },

  rename(id: string, name: string): Promise<DrawingSummary> {
    return request(`/api/drawings/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    });
  },

  savePreview(id: string, preview: string | null): Promise<void> {
    return request(`/api/drawings/${encodeURIComponent(id)}/preview`, {
      method: "PUT",
      body: JSON.stringify({ preview }),
    });
  },

  deleteDrawing(id: string): Promise<void> {
    return request(`/api/drawings/${encodeURIComponent(id)}`, {
      method: "DELETE",
    });
  },

  getSettings(): Promise<SettingsResponse> {
    return request(`/api/settings`);
  },

  updateSettings(theme: Theme): Promise<SettingsResponse> {
    return request(`/api/settings`, {
      method: "PUT",
      body: JSON.stringify({ theme }),
    });
  },

  /** fileId 是 Excalidraw 用内容算出来的哈希，所以这个 PUT 天然幂等。 */
  uploadFile(
    drawingId: string,
    fileId: string,
    blob: Blob,
    mimeType: string,
  ): Promise<void> {
    return request(
      `/api/drawings/${encodeURIComponent(drawingId)}/files/${encodeURIComponent(fileId)}`,
      {
        method: "PUT",
        headers: { "Content-Type": mimeType || "application/octet-stream" },
        body: blob,
      },
    );
  },
};
