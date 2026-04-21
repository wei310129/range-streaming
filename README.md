# Range Streaming Demo

Spring Boot 練習專案：示範 HTTP Range Request 與斷點續傳。

- 兩個伺服器同一個 Maven 專案
- 前端用 `fetch + ReadableStream + AbortController` 模擬中斷與續傳
- API Server 代理 PDF Server，前端只打 API

## 架構

```text
Browser -> API Server (:8080) -> PDF Server (:8081) -> file-storage/
```

## 專案重點

- `RangeStreamingApplication`（API Server）
  - `scanBasePackages = "...api"`
  - 代理端點：`/api/pdf/{filename}`、檔案列表：`/api/files`
- `PdfServerApplication`（PDF Server）
  - `scanBasePackages = "...pdf"`
  - 啟用 `pdf` profile，讀取 `application-pdf.yaml`
- `PdfFileController`
  - `pdf.range-enabled=true`：支援 Range，回 `206 + Content-Range`
  - `pdf.range-enabled=false`：忽略 Range，固定回 `200` 全檔
- `PdfProxyController`
  - 會轉發前端 `Range`
  - 若上游忽略 Range 回 `200`，API 仍會本地切片回 `206`（保持前端續傳語意）

## 需求

- JDK 21+
- Maven Wrapper（已內建，使用 `mvnw` / `mvnw.cmd`）

## 放置測試 PDF

把 PDF 放到專案根目錄下的 `file-storage/`：

```text
range-streaming/
  file-storage/
    your-file.pdf
```

## 啟動方式（兩個伺服器都要啟動）

### Windows PowerShell

```powershell
Set-Location "D:\my-project\range-streaming"
.\mvnw.cmd spring-boot:run
```

```powershell
Set-Location "D:\my-project\range-streaming"
.\mvnw.cmd spring-boot:run -Ppdf-server
```

### macOS / Linux

```bash
cd /path/to/range-streaming
./mvnw spring-boot:run
```

```bash
cd /path/to/range-streaming
./mvnw spring-boot:run -Ppdf-server
```

## 使用方式

1. 開啟 `http://localhost:8080` 前端頁面
2. 在左側 PDF 列表中選一個 PDF
3. 點「開始下載」（預設會隨機中斷，模擬網路斷連，並在 1 秒後用 Range 方式重連）
4. 下載中可點「中止」再點「從斷點續傳」

## 常用 API

- `GET /api/files`
  - 列出 `file-storage/` 裡的 PDF 與大小
- `GET /api/pdf/{filename}`
  - 代理下載端點，支援 Range/續傳語意
- `GET /files/{filename}`（PDF Server）
  - 直接由 PDF Server 取檔（示範用）

## 測試與打包

```powershell
Set-Location "D:\my-project\range-streaming"
.\mvnw.cmd test
```

```powershell
Set-Location "D:\my-project\range-streaming"
.\mvnw.cmd package
```

