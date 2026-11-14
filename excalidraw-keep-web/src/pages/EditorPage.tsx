import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Excalidraw, exportToBlob, hashElementsVersion, reconcileElements } from "@excalidraw/excalidraw";
import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";
import {
  apiClient,
  ConflictError,
  type SceneResponse,
} from "../api";

type SaveStatus = "saved" | "dirty" | "saving" | "error";

/** 只持久化这些 appState 字段：滚动位置、选中元素之类的高频字段没必要存。 */
const PERSISTED_APP_STATE_KEYS = [
  "viewBackgroundColor",
  "gridSize",
  "gridModeEnabled",
  "currentItemStrokeColor",
  "currentItemBackgroundColor",
  "currentItemFontFamily",
  "currentItemFontSize",
] as const;

function pickAppState(appState: Record<string, unknown>): Record<string, unknown> {
  const picked: Record<string, unknown> = {};
  for (const key of PERSISTED_APP_STATE_KEYS) {
    const value = appState[key];
    if (value !== undefined && value !== null && typeof value !== "object") {
      picked[key] = value;
    }
  }
  return picked;
}

/**
 * 「会被保存的内容」的指纹：元素 versionNonce 的 hash + 元素个数 + 白名单 appState。
 * Excalidraw 的 onChange 在滚动、缩放、选中、悬停等操作时也会触发，
 * 那些变化不在这份指纹里，所以不会把状态一直钉在「有未保存的改动」，
 * 也不会每秒往数据库写一个新版本。
 */
function sceneSignature(
  elements: readonly any[],
  appState: Record<string, unknown>,
): string {
  return `${hashElementsVersion(elements as any)}:${elements.length}|${JSON.stringify(
    pickAppState(appState),
  )}`;
}

function dataUrlToBlob(dataURL: string): Promise<Blob> {
  return fetch(dataURL).then((response) => response.blob());
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error("读取缩略图失败"));
    reader.readAsDataURL(blob);
  });
}

/** 后端把附件内联成 dataURL；Excalidraw 的 initialData 要的是 BinaryFiles 结构。 */
function filesToBinaryFiles(
  files: Record<string, string>,
): Record<string, { id: string; mimeType: string; dataURL: string; created: number }> {
  const result: Record<string, { id: string; mimeType: string; dataURL: string; created: number }> = {};
  const now = Date.now();
  for (const [fileId, dataURL] of Object.entries(files)) {
    if (!dataURL.startsWith("data:")) continue;
    const header = dataURL.slice(5, dataURL.indexOf(","));
    const mimeType = header.split(";")[0] || "application/octet-stream";
    result[fileId] = { id: fileId, mimeType, dataURL, created: now };
  }
  return result;
}

function formatTime(iso?: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(
    date.getMinutes(),
  )}`;
}

export default function EditorPage() {
  const { id = "" } = useParams();
  const [initialData, setInitialData] = useState<Record<string, unknown> | null>(null);
  const [loadError, setLoadError] = useState("");
  const [title, setTitle] = useState("未命名画布");
  const [status, setStatus] = useState<SaveStatus>("saved");
  const [errorText, setErrorText] = useState("");
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null);

  const apiRef = useRef<ExcalidrawImperativeAPI | null>(null);
  /** 服务端的场景版本号，保存时作为 baseVersion 发出去 */
  const versionRef = useRef(0);
  /** 最近一次「已经写进数据库」的内容指纹，用来判断是否真的有改动 */
  const savedSigRef = useRef("");
  /** 已经上传过的附件，避免重复 PUT */
  const uploadedRef = useRef<Set<string>>(new Set());
  /** 最近一次的编辑内容（onChange 给的东西） */
  const latestRef = useRef<{ elements: any[]; appState: any; files: any }>({
    elements: [],
    appState: {},
    files: {},
  });
  const saveTimerRef = useRef<number | null>(null);
  const thumbTimerRef = useRef<number | null>(null);
  const savingRef = useRef(false);
  const pendingRef = useRef(false);
  const dirtyRef = useRef(false);
  /** 用户真的动过画布之前，不因为 Excalidraw 的初始化 onChange 就去保存 */
  const interactedRef = useRef(false);
  /** 回滚等程序化改动期间，忽略 onChange */
  const suppressUntilRef = useRef(0);

  const scheduleThumbnail = useCallback(() => {
    if (thumbTimerRef.current) {
      window.clearTimeout(thumbTimerRef.current);
    }
    thumbTimerRef.current = window.setTimeout(async () => {
      thumbTimerRef.current = null;
      const excalidrawApi = apiRef.current;
      if (!excalidrawApi) return;
      const elements = excalidrawApi.getSceneElements();
      if (!elements || elements.length === 0) return;
      try {
        // 0.18.0 发布包导出的是 exportToBlob（exportScene 只在仓库 master 上）
        const blob = await exportToBlob({
          elements: elements as any,
          appState: pickAppState(excalidrawApi.getAppState() as any) as any,
          files: (excalidrawApi.getFiles() ?? null) as any,
          mimeType: "image/png",
          maxWidthOrHeight: 480,
          exportPadding: 8,
        });
        const dataURL = await blobToDataUrl(blob as Blob);
        await apiClient.savePreview(id, dataURL);
      } catch (error) {
        // 缩略图失败不该影响保存本身
        console.warn("[ExcalidrawKeep] 缩略图生成失败", error);
      }
    }, 1200);
  }, [id]);

  const uploadNewFiles = useCallback(
    async (files: Record<string, any>) => {
      for (const [fileId, file] of Object.entries(files)) {
        if (uploadedRef.current.has(fileId)) continue;
        const dataURL: string = file?.dataURL ?? "";
        if (!dataURL.startsWith("data:")) continue;
        const blob = await dataUrlToBlob(dataURL);
        await apiClient.uploadFile(id, fileId, blob, file.mimeType ?? blob.type);
        uploadedRef.current.add(fileId);
      }
    },
    [id],
  );

  const doSave = useCallback(async () => {
    if (!id) return;
    const { elements, appState, files } = latestRef.current;
    if (sceneSignature(elements, appState ?? {}) === savedSigRef.current) {
      // 内容与库里一致（滚动、选中、悬停之类不算改动），不发请求
      dirtyRef.current = false;
      setStatus((prev) => (prev === "saving" ? prev : "saved"));
      return;
    }
    if (savingRef.current) {
      pendingRef.current = true;
      return;
    }
    savingRef.current = true;
    setStatus("saving");
    setErrorText("");
    try {
      await uploadNewFiles(files ?? {});

      let elementsToSave = elements;
      let attempt = 0;
      for (;;) {
        try {
          const result: SceneResponse = await apiClient.saveScene(
            id,
            versionRef.current,
            elementsToSave,
            pickAppState(appState ?? {}),
          );
          versionRef.current = result.version;
          savedSigRef.current = sceneSignature(
            elementsToSave,
            appState ?? {},
          );
          dirtyRef.current = false;
          setStatus("saved");
          setLastSavedAt(new Date());
          scheduleThumbnail();
          break;
        } catch (error) {
          // 409：另一个标签页先保存了，先看远端是不是已经是我们这份内容
          if (error instanceof ConflictError && attempt < 4) {
            attempt += 1;
            const remote = await apiClient.getScene(id);
            const remoteSig = sceneSignature(
              remote.elements as any[],
              remote.appState as any,
            );
            if (
              remoteSig ===
              sceneSignature(elementsToSave, appState ?? {})
            ) {
              // 远端内容与本地一致（比如上次请求其实成功但响应丢了），直接认账
              versionRef.current = remote.version;
              savedSigRef.current = remoteSig;
              Object.keys(remote.files).forEach((fileId) =>
                uploadedRef.current.add(fileId),
              );
              dirtyRef.current = false;
              setStatus("saved");
              setLastSavedAt(new Date());
              return;
            }
            const merged = reconcileElements(
              elementsToSave as any,
              remote.elements as any,
              pickAppState(appState ?? {}) as any,
            );
            Object.keys(remote.files).forEach((fileId) => uploadedRef.current.add(fileId));
            apiRef.current?.updateScene({ elements: merged as any });
            apiRef.current?.addFiles(Object.values(filesToBinaryFiles(remote.files)) as any);
            elementsToSave = merged as any;
            versionRef.current = remote.version;
            continue;
          }
          throw error;
        }
      }
    } catch (error) {
      setStatus("error");
      setErrorText(error instanceof Error ? error.message : "保存失败");
    } finally {
      savingRef.current = false;
      if (pendingRef.current) {
        // 保存期间画布又变了：再等一小段（内容没变的话上面的指纹判断会直接跳过），
        // 避免一边拖拽一边每个网络往返都写一个新版本
        pendingRef.current = false;
        if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current);
        saveTimerRef.current = window.setTimeout(() => {
          saveTimerRef.current = null;
          void doSaveRef.current();
        }, 800);
      }
    }
  }, [id, scheduleThumbnail, uploadNewFiles]);

  const doSaveRef = useRef(doSave);
  useEffect(() => {
    doSaveRef.current = doSave;
  }, [doSave]);

  const scheduleSave = useCallback((delay = 1000) => {
    setStatus((prev) => (prev === "saving" ? prev : "dirty"));
    if (saveTimerRef.current) window.clearTimeout(saveTimerRef.current);
    saveTimerRef.current = window.setTimeout(() => {
      saveTimerRef.current = null;
      void doSaveRef.current();
    }, delay);
  }, []);

  // 首次加载
  useEffect(() => {
    let cancelled = false;
    setInitialData(null);
    setLoadError("");
    (async () => {
      try {
        const scene = await apiClient.getScene(id);
        if (cancelled) return;
        versionRef.current = scene.version;
        uploadedRef.current = new Set(Object.keys(scene.files));
        savedSigRef.current = sceneSignature(
          scene.elements as any[],
          scene.appState as any,
        );
        latestRef.current = {
          elements: scene.elements as any[],
          appState: scene.appState,
          files: filesToBinaryFiles(scene.files),
        };
        setTitle(scene.name);
        setInitialData({
          elements: scene.elements,
          appState: { ...scene.appState, collaborators: new Map() },
          files: filesToBinaryFiles(scene.files),
          scrollToContent: true,
        });
        document.title = `${scene.name} · ExcalidrawKeep`;
      } catch (error) {
        if (cancelled) return;
        setLoadError(error instanceof Error ? error.message : "加载失败");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const handleChange = useCallback(
    (elements: readonly any[], appState: any, files: any) => {
      latestRef.current = { elements: elements as any[], appState, files };
      if (!interactedRef.current) return;
      if (Date.now() < suppressUntilRef.current) return;
      // 只有真正要落库的内容变了才算脏；滚动/缩放/选中/悬停等 appState
      // 变化不算，否则状态会一直停在「有未保存的改动」，并且每秒写一个新版本。
      if (sceneSignature(elements, appState) === savedSigRef.current) {
        dirtyRef.current = false;
        setStatus((prev) => (prev === "saving" ? prev : "saved"));
        return;
      }
      dirtyRef.current = true;
      scheduleSave();
    },
    [scheduleSave],
  );

  // Ctrl/Cmd + S 立即保存；有未保存内容时离开页面给个提示
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (saveTimerRef.current) {
          window.clearTimeout(saveTimerRef.current);
          saveTimerRef.current = null;
        }
        void doSaveRef.current();
      }
    };
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (dirtyRef.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => {
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("beforeunload", onBeforeUnload);
    };
  }, []);

  const handleManualSave = useCallback(() => {
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    void doSaveRef.current();
  }, []);

  const handleTitleCommit = async () => {
    const name = title.trim() || "未命名画布";
    setTitle(name);
    try {
      await apiClient.rename(id, name);
      document.title = `${name} · ExcalidrawKeep`;
    } catch (error) {
      setErrorText(error instanceof Error ? error.message : "改名失败");
    }
  };

  const statusText = useMemo(() => {
    switch (status) {
      case "saving":
        return "保存中…";
      case "dirty":
        return "有未保存的改动";
      case "error":
        return "保存失败";
      default:
        return lastSavedAt ? `已保存 ${formatTime(lastSavedAt.toISOString())}` : "已保存";
    }
  }, [status, lastSavedAt]);

  if (loadError) {
    return (
      <div className="editor-page">
        <div className="editor-error">
          <div>画布加载失败：{loadError}</div>
          <Link to="/">返回列表</Link>
        </div>
      </div>
    );
  }

  if (!initialData) {
    return (
      <div className="editor-page">
        <div className="editor-loading">加载中…</div>
      </div>
    );
  }

  return (
    <div className="editor-page">
      <div className="editor-topbar">
        <Link className="back" to="/">
          ← 我的画布
        </Link>
        <input
          className="title-input"
          value={title}
          maxLength={255}
          onChange={(event) => setTitle(event.target.value)}
          onBlur={() => void handleTitleCommit()}
          onKeyDown={(event) => {
            if (event.key === "Enter") (event.target as HTMLInputElement).blur();
          }}
        />
        <div className="spacer" />
        {errorText && <span className="status-pill error">{errorText}</span>}
        <span className={`status-pill ${status}`}>
          <span className="dot" />
          {statusText}
        </span>
        <button
          className="save-button"
          onClick={handleManualSave}
          disabled={status === "saving"}
          title="保存（Ctrl/Cmd + S）"
        >
          {status === "saving" ? "保存中…" : "保存"}
        </button>
      </div>

      <div
        className="editor-canvas"
        onPointerDownCapture={() => {
          interactedRef.current = true;
        }}
        onKeyDownCapture={() => {
          interactedRef.current = true;
        }}
      >
        <Excalidraw
          initialData={initialData as any}
          excalidrawAPI={(excalidrawApi) => {
            apiRef.current = excalidrawApi;
          }}
          onChange={handleChange}
          langCode="zh-CN"
        />
      </div>
    </div>
  );
}
