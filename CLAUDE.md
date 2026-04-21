# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run API Server (port 8080) — default
./mvnw spring-boot:run

# Run PDF Server (port 8081) — must start BOTH servers for the demo to work
./mvnw spring-boot:run -Ppdf-server

# Build
./mvnw package

# Run tests
./mvnw test
```

Both servers must be running simultaneously. Start them in separate terminals.

## Architecture

This is a two-server Spring Boot demo of HTTP Range Request / resumable download.

```
Browser → API Server (:8080) → PDF Server (:8081) → file-storage/
```

**Two `@SpringBootApplication` main classes in the same Maven project:**

- `RangeStreamingApplication` — API Server (port 8080), `scanBasePackages = "...api"`, loads `application.yaml`
- `PdfServerApplication` — PDF Server (port 8081), `scanBasePackages = "...pdf"`, activates `"pdf"` profile which loads `application-pdf.yaml` (overrides port to 8081)

`scanBasePackages` isolation is critical: without it, both applications would load each other's beans and conflict.

**PDF Server** (`pdf/PdfServerConfig.java`): Registers `/files/**` → `file:./file-storage/` via Spring's `ResourceHttpRequestHandler`, which natively handles Range requests (206, Content-Range, Accept-Ranges) with no custom code.

**API Server** (`api/PdfProxyController.java`):
- `GET /api/pdf/{filename}` — proxies Range requests to PDF Server; forwards `Range` header upstream and `Content-Range` / `Content-Length` / `Accept-Ranges` back downstream; streams response with `InputStream.transferTo()` (fixed ~8KB buffer, no full-file memory load); URL-encodes filenames with `URLEncoder + .replace("+", "%20")` to handle Chinese characters
- `GET /api/files` — lists PDFs in `file-storage/` with name and size

**Frontend** (`src/main/resources/static/index.html`): Vanilla JS, no framework. Implements a resumable download state machine (`idle → downloading → interrupted → resuming → done`). Uses `fetch()` + `ReadableStream` for streaming, `AbortController` to simulate mid-download interruption, and `Range: bytes=X-` header for resumption. Both download segments share a single `S.chunks[]` array; `new Blob(S.chunks)` merges them into the complete file at the end.

**PDF files** go in `file-storage/` (relative to JVM working directory = project root). The directory has a `.gitkeep`; actual PDFs are gitignored.

## Key invariant

Phase must be set **before** calling `abort()`. Setting phase after abort causes the catch block to overwrite the intended state — this is a known subtle bug pattern in the JS state machine.
