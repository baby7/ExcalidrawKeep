import { useCallback, useEffect, useRef, useState } from "react";
import { apiClient, ApiError, type DrawingSummary } from "../api";
import { applyTheme, readCachedTheme, type Theme } from "../theme";

const PAGE_SIZE = 24;

function formatTime(iso: string): string {
  if (!iso) return "";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

export default function ListPage() {
  const [items, setItems] = useState<DrawingSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState<DrawingSummary | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [theme, setTheme] = useState<Theme>(readCachedTheme);
  const [themeSaving, setThemeSaving] = useState(false);

  // 请求序号：快速输入时只认最后一次请求的结果，避免旧响应覆盖新结果
  const requestSeq = useRef(0);

  const load = useCallback(async (q: string, currentPage: number) => {
    const seq = ++requestSeq.current;
    setLoading(true);
    try {
      const pageData = await apiClient.listDrawings(q, currentPage, PAGE_SIZE);
      if (seq !== requestSeq.current) return;
      setItems(pageData.items);
      setTotal(pageData.total);
      setError("");
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setItems([]);
      setTotal(0);
      setError(err instanceof Error ? err.message : "加载失败");
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      void load(query, page);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query, page, load]);

  // 主题是全局设置，存在数据库里；本地缓存只是让首屏不用等接口
  useEffect(() => {
    let cancelled = false;
    apiClient
      .getSettings()
      .then((settings) => {
        if (cancelled) return;
        setTheme(settings.theme);
        applyTheme(settings.theme);
      })
      .catch(() => {
        // 设置读不到不影响画布列表
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleToggleTheme = async () => {
    if (themeSaving) return;
    const next: Theme = theme === "dark" ? "light" : "dark";
    const previous = theme;
    // 先切界面（快），写库成功后以服务端返回为准，失败就退回去
    setTheme(next);
    applyTheme(next);
    setThemeSaving(true);
    try {
      const saved = await apiClient.updateSettings(next);
      setTheme(saved.theme);
      applyTheme(saved.theme);
    } catch (err) {
      setTheme(previous);
      applyTheme(previous);
      setError(err instanceof Error ? err.message : "主题保存失败");
    } finally {
      setThemeSaving(false);
    }
  };

  const handleCreate = async () => {
    if (creating) return;
    setCreating(true);
    try {
      const created = await apiClient.createDrawing("未命名画布");
      // 需求：画布在新标签页打开，方便同时对照两张图
      window.open(`/d/${created.id}`, "_blank", "noopener");
      setQuery("");
      setPage(0);
      await load("", 0);
    } catch (err) {
      setError(err instanceof Error ? err.message : "新建失败");
    } finally {
      setCreating(false);
    }
  };

  const handleRenameSubmit = async () => {
    if (!renaming) return;
    const name = renameValue.trim();
    if (!name) return;
    try {
      const updated = await apiClient.rename(renaming.id, name);
      setItems((prev) =>
        prev.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
      );
      setRenaming(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "改名失败");
    }
  };

  const handleDelete = async (drawing: DrawingSummary) => {
    if (!window.confirm(`删除「${drawing.name}」？删除后无法恢复。`)) {
      return;
    }
    try {
      await apiClient.deleteDrawing(drawing.id);
      await load(query, page);
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  };

  const maxPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  return (
    <div className="list-page">
      <div className="list-header">
        <div>
          <h1>我的画布</h1>
          <div className="subtitle">
            共 {total} 张 · 画布保存在你自己的 MySQL 里
          </div>
        </div>
        <div className="list-toolbar">
          <button
            className="theme-toggle"
            onClick={() => void handleToggleTheme()}
            disabled={themeSaving}
            title={theme === "dark" ? "切换到浅色主题" : "切换到深色主题"}
          >
            {theme === "dark" ? "☀️ 浅色" : "🌙 深色"}
          </button>
          <input
            type="search"
            placeholder="搜索画布名称…"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setPage(0);
            }}
            style={{ width: 220 }}
          />
          <button className="primary" onClick={handleCreate} disabled={creating}>
            {creating ? "创建中…" : "新建画布"}
          </button>
        </div>
      </div>

      {error && (
        <div className="error-banner">
          {error}
          <button
            style={{ marginLeft: 12 }}
            onClick={() => void load(query, page)}
          >
            重试
          </button>
        </div>
      )}

      {loading && items.length === 0 ? (
        <div className="empty-state">加载中…</div>
      ) : items.length === 0 ? (
        <div className="empty-state">
          {query ? `没有找到名字包含「${query}」的画布` : "还没有画布，点右上角「新建画布」开始"}
        </div>
      ) : (
        <div className="grid">
          {items.map((drawing) => (
            <div className="card" key={drawing.id}>
              <a
                className="thumb"
                href={`/d/${drawing.id}`}
                target="_blank"
                rel="noreferrer"
                title="在新标签页打开"
              >
                {drawing.preview ? (
                  <img src={drawing.preview} alt={drawing.name} />
                ) : (
                  <div className="empty">还没有缩略图</div>
                )}
              </a>
              <div className="body">
                <a
                  className="name"
                  href={`/d/${drawing.id}`}
                  target="_blank"
                  rel="noreferrer"
                  title={drawing.name}
                >
                  {drawing.name}
                </a>
                <div className="meta">
                  <span>{formatTime(drawing.updatedAt)}</span>
                  <span>· v{drawing.version}</span>
                </div>
                <div className="actions">
                  <button
                    onClick={() => {
                      setRenaming(drawing);
                      setRenameValue(drawing.name);
                    }}
                  >
                    改名
                  </button>
                  <button className="danger" onClick={() => void handleDelete(drawing)}>
                    删除
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {maxPage > 0 && (
        <div className="pager">
          <button disabled={page <= 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
            上一页
          </button>
          <span>
            {page + 1} / {maxPage + 1}
          </span>
          <button
            disabled={page >= maxPage}
            onClick={() => setPage((p) => Math.min(maxPage, p + 1))}
          >
            下一页
          </button>
        </div>
      )}

      {renaming && (
        <div className="modal-mask" onClick={() => setRenaming(null)}>
          <div className="modal" onClick={(event) => event.stopPropagation()}>
            <h3>重命名画布</h3>
            <input
              type="text"
              autoFocus
              value={renameValue}
              maxLength={255}
              onChange={(event) => setRenameValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") void handleRenameSubmit();
                if (event.key === "Escape") setRenaming(null);
              }}
            />
            <div className="footer">
              <button onClick={() => setRenaming(null)}>取消</button>
              <button className="primary" onClick={() => void handleRenameSubmit()}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
