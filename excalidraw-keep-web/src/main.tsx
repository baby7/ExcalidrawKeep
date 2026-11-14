import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import "@excalidraw/excalidraw/index.css";
import "./styles.css";

// 故意不套 StrictMode：开发模式下它会把组件挂载两次，
// Excalidraw 这类带大量内部状态和 imperative API 的组件容易被挂出问题。
const container = document.getElementById("root");
if (!container) {
  throw new Error("找不到 #root 容器");
}

ReactDOM.createRoot(container).render(
  <BrowserRouter>
    <App />
  </BrowserRouter>,
);
