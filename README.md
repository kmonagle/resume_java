# resume_java — the link backend, in Java (Spring Boot)

One of five implementations of the short-link API. The Next.js app in
[`resume_nextjs`](https://github.com/kmonagle/resume_nextjs) can serve links itself out of its own
code, and the [Go](https://github.com/kmonagle/resume_go),
[Python](https://github.com/kmonagle/resume_python) and
[C#](https://github.com/kmonagle/resume_csharp) services do the same job; this one does it in Java.
All of them are held to the **identical contract test suite**, which is the point: the contract, not
the language, defines the system.

**Implements contract `contract-v1`** (the tag pinned in `.github/workflows/ci.yml`).

Read this README for how the services fit together and why the design is the way it is. Read the code
for the Java: every file opens with a comment on why it exists, and comments tagged **`JS/TS vs Java:`**
call out where Java behaves differently from what a JavaScript/TypeScript developer would expect
(`grep -rn "JS/TS vs Java" src pom.xml`). A ten-point crib sheet of the biggest differences is in the
comment at the top of `src/main/java/com/resume/links/LinksApplication.java`.

> The integration story (browser → Next.js → backend → Postgres, the token, the redirect flow, cold
> starts, the shared database) is identical for every backend. The
> [Go README](https://github.com/kmonagle/resume_go#readme) has the long version; the essentials are
> repeated below so this one stands alone.

---

## The big picture

```
                    cookie: visitor_id                Authorization: Bearer <token>
                    (httpOnly, Next's domain)         X-Owner-Id: <visitor id>
┌─────────┐  HTTPS  ┌──────────────────────┐  HTTPS   ┌──────────────┐  SQL   ┌──────────┐
│ Browser │ ──────► │ Next.js (UI + BFF)   │ ───────► │ this Java    │ ─────► │  Neon    │
│         │ ◄────── │ resume_nextjs        │ ◄─────── │ service      │ ◄───── │ Postgres │
└─────────┘         └──────────────────────┘          └──────────────┘        └──────────┘
```

- The **browser never talks to this service**. It only sees the Next.js domain, so there is no CORS or
  cross-site-cookie problem, and the bearer token and this service's URL never reach client JavaScript.
  Next.js is a **BFF** (backend-for-frontend).
- Next.js picks its backend with an environment variable, `LINK_BACKEND`: **`local`** (its own Drizzle
  code) or **`remote`** (call one of these services, at `LINK_BACKEND_URL`). Point it at Go, Python, C#
  or Java and the UI can't tell.
- **All backends share one Postgres database**, so links carry across them.

### Who owns what

| Concern | Owner |
|---|---|
| UI, dashboard polling, forms, the visitor cookie | Next.js |
| Input validation | **Both**: Next validates first (fast form errors); this service validates again because it must not trust its caller. Rules and messages match. |
| Business rules (limits, retention, 404 vs 410), atomic click counting | **Every backend**, with the same behaviour |
| Click event logging | Whoever serves the redirect (here). Next.js must not log too or clicks double count. |
| **Database schema and migrations** | **The Next.js repo.** This service never migrates. |
| The contract (OpenAPI spec + tests) | The Next.js repo, pinned by tag |

### How each Next.js feature becomes calls to this service

| In the UI | Next.js calls | Auth |
|---|---|---|
| Create a link | `POST /links` | bearer + `X-Owner-Id` |
| Dashboard (polls every 5 s) | `GET /links` | bearer + `X-Owner-Id` |
| Activate / Deactivate | `PATCH /links/{id}` | bearer + `X-Owner-Id` |
| Follow a short link `/r/{code}` | `GET /r/{code}` (redirect **not** followed) | none (public) |
| Footer "Served by: …" | `GET /meta` | none (public) |

### Identity and trust

Next.js gives each browser a random `visitor_id` cookie and copies it into an `X-Owner-Id` header on
every call. This service trusts that header **only because the request also carries the shared bearer
token** (`LINK_BACKEND_TOKEN`). Render's free tier has no private networking, so the service is
reachable from the internet and the token is the only thing stopping anyone from claiming to be any
owner. Every query is also scoped by owner (`WHERE id = ? AND owner_id = ?`), so someone else's link id
returns `404` (not `403`, which would confirm it exists). The token is compared as SHA-256 hashes with
`MessageDigest.isEqual`, which takes the same time whatever the input, so timing can't be used to guess
it. (See `AuthInterceptor`.) The routes under `/links` are protected by registering that interceptor for
`/links/**`; a route not listed there (the redirect, `/meta`) is public by construction.

The cookie identifies a *browser*, not a person: it is scoping for a login-free demo, not
authentication.

### The redirect

```
browser ─ GET /r/abc ─► Next.js ─ GET /r/abc ─► this service
                          (redirect: "manual",   │ 1. one atomic UPDATE: check every rule
                           forwards Referer +    │    AND count the click (its own transaction, committed)
                           User-Agent)           │ 2. reply 307 + Location
                                                 │ 3. an @Async task logs the click_events row
browser ◄─ 307 Location ─ Next.js ◄─ 307 ───────┘
```

Two things specific to this implementation:

- **The click is logged off the request thread**, with Spring's `@Async` (`ClickLogger`), so analytics
  never slows the redirect. It runs on a virtual thread in its **own transaction and connection**, and a
  failure is logged and dropped (losing a click log is acceptable here). Note this is *concurrent with*
  the response, not strictly after it: `@Async` returns immediately and the row is written moments
  later. (Verified: it is written with the visitor's forwarded `Referer` and `User-Agent`.)
- **Click limits are enforced by one SQL statement** (`LinkJpaRepository.claim`): check and increment
  together. Loading the entity, checking in Java, then saving would let two simultaneous requests both
  read "9 of 10", both pass, and both redirect. In a single `UPDATE`, Postgres locks the row, so the
  second request waits and then fails the `WHERE` clause. The contract suite fires 12 requests in
  parallel at a limit-2 link and expects exactly 2 redirects. *JPA can't return columns from an
  `UPDATE`*, so the claim is decided by the statement's **rows-affected count** (1 = claimed, 0 = not
  redeemable), and the target URL is then read with a plain query; that is safe, because the decision
  was already made atomically and a link's target never changes.

### One request, end to end

What happens to a `POST /links` from the moment it arrives (the shape is the same for every route; the
redirect adds an `@Async` task at the end):

```
Next.js ── POST /links ──► Tomcat (the embedded web server) accepts it on a virtual thread
                            │
                            ▼  RequestLoggingFilter (outermost): starts a timer; logs ONE line at the end
                            ▼  DispatcherServlet finds the controller method for POST /links
                            ▼  AuthInterceptor.preHandle (runs BEFORE the body is read):
                            │    bearer token wrong  → 401     X-Owner-Id unusable → 400
                            │    (an ApiException, turned into JSON by ApiErrors)
                            ▼  Jackson parses the JSON body into CreateLinkRequest
                            │    (malformed JSON → 400, again via ApiErrors)
                            ▼  LinkController.createLink → CreateLinkValidator (field problems → 400)
                            ▼  LinkService.create: Spring's @Transactional PROXY opens a transaction,
                            │    then: cleanup DELETE, two COUNTs, INSERT ... ON CONFLICT DO NOTHING
                            ▼  the transaction COMMITS as create() returns   ← before the response
                            ▼  the controller builds a ResponseEntity (201); Jackson writes the JSON
                            ▼  RequestLoggingFilter logs: method, path, status, milliseconds
Next.js ◄── 201 + JSON ─────┘   (for a redirect, ClickLogger.record runs now, on its own thread)
```

Because the transaction belongs to the *service method*, not the request, it commits before the
controller returns, so nothing is committed after the response is sent (which is the trap the Python
service has to avoid with `scope="function"`). It also means `create` is one atomic unit, unlike the Go
and C# services, where each statement commits on its own. **Open Session In View is switched off**
(`spring.jpa.open-in-view=false`): Spring Boot enables it by default, which holds a database connection
for the *whole* request, including after the controller returns, and with a small pool that would
starve background work.

### How Next.js calls this service

The Next.js side of the conversation is `src/server/link-api/remote.ts`. What it does, and so what this
service has to uphold:

- **Every call to `/links`** carries `Authorization: Bearer <token>` and `X-Owner-Id`, uses
  `cache: "no-store"` (live data must never be cached by Next's fetch layer), and has a 90-second
  timeout (a free-tier cold start is a slow request, not an error).
- **Redirects** use `redirect: "manual"`, because the caller wants this service's `Location` header
  itself and not the destination site's HTML. It forwards the visitor's `Referer` and `User-Agent` so
  the click log holds the real browser. `GET /r/{code}` needs no token.
- **Responses are validated with zod against the contract**, not trusted. So the shape has to be exact:
  camelCase keys (Jackson derives them from the record components), timestamps as ISO strings with
  milliseconds and a `Z` (`LinkDto`), nullable fields sent as `null` (never omitted), and `status` one
  of `active | expired | max_clicks | disabled` (`LinkStatus.wire()`). A response that breaks the
  contract is treated by Next.js as "backend unavailable".
- **Errors are mapped by status code** (table below), so the *right* status matters more than the
  message text, with one exception: a `410` body is shown to the visitor as-is, and `429`'s message is
  shown on the form.
- **The footer's "Served by" line** comes from `GET /meta` (via Next.js's own `/api/meta`), which is why
  `/meta` is open and never touches the database.

### What Next.js does when this service misbehaves

| This service answers | Next.js treats it as |
|---|---|
| `201` on create | success |
| `409` on create | "that code is taken" (a field error on the form) |
| `429` on create | "limit reached" (this service's message is shown) |
| `404` on toggle | link not found (someone else's, or it doesn't exist) |
| `404` / `410` on a redirect | Next's 404 page / a `410` with this service's message |
| `401` | a misconfigured token: logged on the Next.js side, surfaced as "backend unavailable" |
| `5xx`, invalid JSON, or a timeout | "backend unavailable" |

"Backend unavailable" becomes: a `503` from the JSON API; a "waking up, try again" message on the form
(which keeps what you typed); a `503` with `Retry-After: 30` on a short link; and "live updates
paused" on the dashboard, which keeps polling and recovers by itself.

## Free-tier cold starts (Render)

On Render's free plan a service sleeps after 15 minutes without traffic and takes from about ten
seconds to a minute to wake (measured on Render: Go and C# about 12 s, Python about 22 s; the JVM is
the slowest).

**Only a public request wakes it.** A request from the Next.js service to a sleeping backend (both on
Render) gets Render's HTML `502` page straight away and does **not** wake the service, so the UI shows
a "waking up" state and stays there; a request from a browser or `curl` is held until the service is up.
To demo the Java backend on the free tier, wake it first by opening
`https://<this-service>.onrender.com/meta` (or `curl` it), then use the site. Next.js degrades politely
while the backend is asleep (a `503` from its JSON API, an amber "waking up" message on the form and
dashboard) but cannot wake it itself. (An earlier design pinged the backend from a Next.js startup hook
so the two would wake together; that was removed because server-to-server requests don't wake it.)

- **Don't try to keep everything awake.** A free workspace gets about 750 instance-hours a month. One
  always-on service uses about 730; two would run out mid-month.
- `LINK_BACKEND=local` needs no second service at all.

On this side, the JVM is the **slowest of the backends to start**: about 1.5 seconds on a laptop, and
several times that on a free instance's shared CPU. The app is built to keep that short. It **doesn't
touch the database at startup** (the pool connects lazily and Hibernate's dialect probe is skipped), so
it starts and answers `/meta` even while Neon's compute is still waking. The first real request is also
slower than the rest, since the JVM compiles code as it runs.

## Startup, shutdown and scaling

- **Start:** the image runs `java -jar app.jar`. Spring Boot builds the application context, and the
  settings are validated at startup (`@Validated` on `LinkProperties`): a missing `DATABASE_URL` or a
  token under 16 characters stops the process with a clear message.
- **Port:** Render injects `PORT`; `application.properties` maps it to `server.port` (default `8080`).
- **Stop:** Render sends `SIGTERM` on a redeploy. `server.shutdown=graceful` stops accepting new
  requests and lets in-flight ones finish, for up to 10 seconds, before the context closes and the
  connection pool shuts down.
- **Concurrency:** requests run on **virtual threads** (`spring.threads.virtual.enabled=true`, Java 21+):
  cheap JVM-managed threads that don't hold up an operating-system thread while they wait on the
  database. It is the closest Java gets to Node's "`await` frees the thread", with no `async`/`await`
  in the code at all. The database pool is 5 connections (HikariCP); a sixth concurrent query waits for a
  free one (up to Hikari's 30-second default) instead of failing, which is what happens to some of the
  12 simultaneous requests in the parallel-click contract test.
- **Memory:** a free instance has 512 MB, so the image sets `-XX:MaxRAMPercentage=70` (the default heap
  is only 25% of the container) and the single-threaded serial garbage collector, which has the
  smallest footprint on a small container.

## The shared database

- **Schema ownership.** Tables are defined by Drizzle in the Next.js repo (`src/server/db/schema.ts`;
  generated SQL in `drizzle/`). The Next.js service applies migrations on every deploy; **this service
  never migrates** (`spring.jpa.hibernate.ddl-auto=none`), and the entity classes are a hand-kept
  mirror of the tables. On a new database, deploy or migrate Next.js first, or you'll see `relation
  "links" does not exist`.
- **Small differences from the ORM.** Drizzle generates ids and sets `updated_at` in application code;
  here ids are `UUID`s made in Java, and `updated_at` is set from the *database* clock on every update
  by Hibernate's `@CurrentTimestamp`. `owner_id` is `NOT NULL` with no default, so every insert must
  supply one.
- **Neon and JDBC.** The URL Neon hands out (`postgres://…?sslmode=require&channel_binding=require`) is
  not a JDBC URL, and `config/DatabaseUrl.java` converts it: `jdbc:postgresql://host:port/db`, with the
  credentials passed separately, `sslmode` kept and `channel_binding` dropped. Because Neon's pooled URL
  goes through PgBouncer in transaction mode, `prepareThreshold=0` turns off the server-side prepared
  statements the driver would otherwise create (the Java twin of the Next.js app's `prepare: false`).
  The pool is capped at 5 connections because several services share one database.
- **Changing the schema safely.** Because several backends share the database, a migration must leave
  *older* backends working (add a nullable column, upgrade every backend, then tighten it).

## The contract

`docs/openapi.yaml` in the Next.js repo is the source of truth. `.github/workflows/ci.yml` here pins the
version this service implements (`CONTRACT_REF: contract-v1`, a git tag); CI checks it out, applies its
`drizzle/*.sql` to a throwaway Postgres, starts this service, and runs the shared suite against it. To
upgrade, bump the tag, make the new tests pass, and merge; other backends can stay on the old tag
meanwhile, so prefer *additive* contract changes.

**Where a framework default disagreed with the contract** (each one is a place the tests or the logs
would have caught it, and each is fixed and commented in the code):

| Default behaviour | What the contract needs | How it is handled |
|---|---|---|
| Jackson coerces for a typed field: `1.5` becomes `1`, `"5"` becomes `5`, `"true"` becomes `true` (**verified** in `JsonBindingTest`) | only a real whole number / a real boolean, else `400` | `maxClicks` and `isActive` are received as `Object` and their type is checked by hand |
| Spring answers a bad route, wrong method or unsupported media type with its own error JSON | the contract's `{"error": ...}` shape | `ApiErrors` re-shapes them, keeping Spring's status |
| An unexpected exception gives Spring Boot's default `/error` JSON (`timestamp`, `status`, `path`…) | `{"error": "Internal error"}`, nothing internal | the catch-all handler in `ApiErrors` |
| Open Session In View is **on** by default and holds a connection for the whole request | connections held only inside a transaction | `spring.jpa.open-in-view=false` |
| A JPA `save()` on an entity with an assigned id does a `SELECT` first, and a unique violation **aborts the surrounding Postgres transaction** (so "catch and retry" fails) | a race for the same code must resolve cleanly | a native `INSERT … ON CONFLICT DO NOTHING` that never raises |
| Hibernate probes the database at startup to pick a dialect, and HikariCP fails startup if it can't connect | start (and answer `/meta`) even while Neon is waking | `database-product-name` set, metadata access off, `initializationFailTimeout=-1` |
| Spring Boot logs startup but not requests | one log line per request | `RequestLoggingFilter` |
| The driver creates server-side prepared statements that PgBouncer can't keep | it must work on Neon's pooled URL | `prepareThreshold=0` in `DatabaseUrl` |
| Neon's URL isn't a JDBC URL | connect with the URL Neon hands out | `config/DatabaseUrl.java` |

### Five implementations, side by side

| Java (this repo) | Go (`resume_go`) | Python (`resume_python`) | C# (`resume_csharp`) | Next.js (`resume_nextjs`) | Job |
|---|---|---|---|---|---|
| `persistence/` | `internal/store` | `app/store.py`, `models.py` | `Data/` | `link-repository.ts`, `schema.ts` | the only code that runs queries; the table definitions |
| `service/LinkService` | `internal/service` | `app/service.py` | `Services/` | `link-api/local.ts` | limits, retention, codes, 404 vs 410 |
| `web/LinkController` | `internal/api` | `app/api.py` | `Endpoints/` | `src/app/api/**`, `src/app/r/**` | HTTP handlers |
| `web/AuthInterceptor` | `protected` middleware | `require_owner` | `RequireOwnerFilter` | (cookie identity) | bearer token + owner |
| `web/CreateLinkValidator`, `LinkDto` | `internal/link/validate.go` | `app/schemas.py` | `Contracts/` | `link-schema.ts`, `link-dto.ts` | input validation, wire format |
| `domain/` | `internal/link/link.go` | `app/domain.py` | `Domain/` | `link-status.ts` | "is this link usable?" |
| `config/` | `internal/config` | `app/config.py`, `db.py` | `Configuration/` | `env.ts`, `db/client.ts` | environment variables; connecting to Postgres |

The trade-offs show up in the numbers: this image is ~400 MB (it carries a Java runtime), against ~380 MB
for C#, ~270 MB for Python and ~20 MB for Go's single static binary.

## Environment variables

| Variable | Where | Meaning |
|---|---|---|
| `DATABASE_URL` | this service | Postgres URL. Use Neon's **pooled** URL in production. |
| `LINK_BACKEND_TOKEN` | this service **and** Next.js | Shared secret, 16+ characters. **Must be identical on both.** |
| `PORT` | this service | Render sets it; defaults to `8080`. |
| `LINK_BACKEND=remote`, `LINK_BACKEND_URL` | Next.js | Point Next.js at this service's public URL. |

## Run it locally

```bash
# 1. Postgres, with the schema applied by the Next.js repo
docker run -d --name links-pg -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=links -p 54329:5432 postgres:17
(cd ../resume_nextjs && DIRECT_URL=postgres://postgres:dev@localhost:54329/links npx drizzle-kit migrate)

# 2. This service, in Docker (no JDK install needed)
docker build -t resume-java .
docker run --rm -p 8080:8080 \
  -e DATABASE_URL="postgres://postgres:dev@host.docker.internal:54329/links" \
  -e LINK_BACKEND_TOKEN="local-dev-token-0123456789" resume-java
#    ...or with JDK 25 installed:
#    DATABASE_URL=... LINK_BACKEND_TOKEN=... ./mvnw spring-boot:run

# 3. Next.js in front of it (from ../resume_nextjs)
LINK_BACKEND=remote LINK_BACKEND_URL=http://localhost:8080 \
LINK_BACKEND_TOKEN=local-dev-token-0123456789 npm run dev
```

**Tests**

```bash
./mvnw test                                         # unit tests: no database needed
./mvnw spotless:check                               # formatting (also run in CI); `spotless:apply` fixes it
docker build --target test -t resume-java-test .    # the same tests, inside Docker

# the shared contract suite against this service directly (from ../resume_nextjs):
CONTRACT_BASE_URL=http://localhost:8080 CONTRACT_IDENTITY=header \
CONTRACT_API_PREFIX="" CONTRACT_TOKEN=local-dev-token-0123456789 npm run test:contract
```

## Deploy on Render

Create a **Web Service** from this repo, runtime **Docker**, and set `DATABASE_URL` (the Neon **pooled**
URL) and `LINK_BACKEND_TOKEN`. Render provides `PORT`. Set the health check path to `/meta`. To use it,
set `LINK_BACKEND=remote`, `LINK_BACKEND_URL` and the same `LINK_BACKEND_TOKEN` on the Next.js service.
Setting **Auto-Deploy** to "After CI Checks Pass" keeps a broken push out of production.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Next.js logs `Backend … failed (401)` | The two `LINK_BACKEND_TOKEN` values differ. |
| First page load takes a minute or two | Cold start: both services were asleep (the JVM is the slowest to start). |
| Startup fails with "Could not resolve placeholder 'DATABASE_URL'" | `DATABASE_URL` isn't set. |
| Startup fails with a validation error mentioning `link.backend.token` | `LINK_BACKEND_TOKEN` is missing or under 16 characters. |
| `relation "links" does not exist` | The schema hasn't been applied; migrate the Next.js repo first. |
| The service is killed for using too much memory | The heap flags in the `Dockerfile` (`JAVA_TOOL_OPTIONS`) assume a 512 MB instance; lower `MaxRAMPercentage` for less. |
| Clicks never appear in `click_events` | The `@Async` task is failing (see the log for "Recording click event failed"), or `@EnableAsync` was removed from `AppConfig`. |
| Clicks counted twice | Something else is also logging click events. |
| A test mentions `tools.jackson` | Spring Boot 4 uses Jackson 3, whose packages are `tools.jackson.*`, not the older `com.fasterxml.jackson.*`. |
| CI can't check out the contract | The tag isn't pushed, the Next.js repo is private, or `CONTRACT_REPO` is wrong. |

## Layout

```
src/main/java/com/resume/links/
  LinksApplication.java   entry point; the ten-point crib sheet
  web/                    controller, auth interceptor, validator, DTOs, error handling, request log
  service/                LinkService (rules, transactions), the LinkStore interface, result types
  persistence/            JPA entities, the Spring Data repository, JpaLinkStore (the LinkStore impl)
  domain/                 Link, LinkStatus, short-code generation (no I/O)
  config/                 typed settings, Neon URL conversion, the connection pool, small beans
src/main/resources/       application.properties (every non-obvious setting is commented)
src/test/java/…           JUnit 5: domain, validator, service (fake store), web layer (mock service), config
pom.xml                   dependencies (via Spring Boot's BOM), Java 25, Spotless
Dockerfile                four stages: deps, test, build, runtime
```

Suggested reading order: `domain/` → `service/` → `persistence/` → `web/` → `config/` →
`LinksApplication.java` (the crib sheet) last, or first if Java is new to you.

## Design decisions worth talking about

Each of these is a choice with a reason, and the trade-off is stated so it can be challenged.

- **A conventional stack, on purpose.** Spring Boot, Spring MVC, Spring Data JPA over Hibernate, HikariCP,
  JUnit 5 with AssertJ and Mockito, Maven and Spotless: the mainstream Java service stack, so a Java
  developer can navigate it without learning anything bespoke.
- **Native SQL for the two statements that matter, JPA for the rest.** Reads and the toggle use JPA
  (derived queries, dirty checking). The click claim and the create-if-absent insert are explicit native
  statements (`UPDATE … WHERE` and `INSERT … ON CONFLICT DO NOTHING`), because the load-modify-save
  sequence would let concurrent requests overshoot a click limit, and a `save()` that hits the unique
  constraint would abort the whole Postgres transaction. The ORM is a convenience; correctness lives in
  the database, and the code says so where it counts.
- **`@Transactional` at the service, so one operation is one transaction.** The transaction commits
  when the service method returns, which is *before* the controller writes its response, so a client can
  never see a response for work that isn't committed yet.
- **A `LinkStore` interface between the service and JPA.** Spring apps often inject the repository into
  the service directly. The extra interface costs a small class, and buys tests that run against a
  hand-written fake with no Spring and no mocking library, and a structure that matches the other four
  implementations, which makes the five easy to compare.
- **Sealed result types.** `CreateResult` and `FollowResult` are sealed interfaces of records, so a
  `switch` over them must cover every case or it doesn't compile. Expected outcomes are values; only
  genuine failures are exceptions.
- **Auth as an interceptor on `/links/**`,** which runs before the body is parsed, so an unauthenticated
  caller never learns whether its body was valid.
- **An explicit validator, not Bean Validation, for requests.** Bean Validation (`@NotBlank`, `@Size`) is
  the usual Spring answer and *is* used for the startup settings. For the request the messages, the
  blank-means-absent behaviour and the code-point title length must match the other implementations
  word for word, and plain code is unit-testable with no framework.
- **`Object`-typed JSON fields where the contract is strict.** A test documents why (see the table).
- **Virtual threads instead of an async stack.** Blocking code stays simple, and the JVM makes waiting
  cheap. Spring MVC on virtual threads gets most of the benefit of a reactive stack with none of its
  complexity.
- **Configuration is validated at startup, and the pool is lazy.** A bad setting stops the process with
  a clear message; a slow database doesn't.
- **Formatting is checked, not argued about.** Spotless with google-java-format in CI.
- **The contract is pinned and tested,** so "same behaviour in five languages" is checked on every push,
  not just claimed.
