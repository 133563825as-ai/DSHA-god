package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.TransferBehaviorTestSupport.*;
import static org.junit.Assert.*;

import android.app.Application;
import com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest;
import com.deepseekharness.app.util.PreviewPageSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** 真实 loopback HTTP、HttpURLConnection、Engine/drain；仅私有文件写入与系统边界受控。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      TransferBehaviorTestSupport.Controller.class,
      TransferBehaviorTestSupport.HostFiles.class,
      TransferBehaviorTestSupport.Documents.class,
      DownloadEngineNetworkTest.JavaWrite.class,
      DownloadEngineNetworkTest.KotlinWrite.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class DownloadEngineNetworkTest {
  private static final byte[] BYTES = "实际 HTTP 下载与恢复字节".getBytes(StandardCharsets.UTF_8);
  private static final String COOKIE = "fixture-session=network-test";
  private TransferBehaviorTestSupport fixture;
  private DownloadEngine engine;
  private TransferArtifact artifact;
  private HttpServer server;
  private ExecutorService serverWorker;
  private String origin;
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<Throwable> serverError = new AtomicReference<>();
  private volatile ContentReply reply;

  interface ContentReply {
    void send(HttpExchange exchange, Request request) throws Exception;
  }

  static final class Request {
    final String path, query, method, cookie, origin, range, ifRange, encoding;
    final byte[] body;

    Request(HttpExchange exchange) throws IOException {
      path = exchange.getRequestURI().getPath();
      query = exchange.getRequestURI().getRawQuery();
      method = exchange.getRequestMethod();
      cookie = exchange.getRequestHeaders().getFirst("Cookie");
      origin = exchange.getRequestHeaders().getFirst("Origin");
      range = exchange.getRequestHeaders().getFirst("Range");
      ifRange = exchange.getRequestHeaders().getFirst("If-Range");
      encoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");
      body = exchange.getRequestBody().readAllBytes();
    }
  }

  /** Java 基线的静态入口；只替代平台描述符写入，记录真实 Engine 选择的偏移。 */
  @Implements(className = "com.deepseekharness.app.transfer.TransferFiles", isInAndroidSdk = false)
  public static final class JavaWrite {
    @Implementation
    protected static OutputStream write(File file, long offset) throws IOException {
      return hostWrite(file, offset);
    }
  }

  /** Kotlin 调用 Companion 的入口；Java 基线不存在该类时不参与执行。 */
  @Implements(
      className = "com.deepseekharness.app.transfer.TransferFiles$Companion",
      isInAndroidSdk = false)
  public static final class KotlinWrite {
    @Implementation
    protected OutputStream write(File file, long offset) throws IOException {
      return hostWrite(file, offset);
    }
  }

  private static final List<Long> openedOffsets = new CopyOnWriteArrayList<>();

  private static OutputStream hostWrite(File file, long offset) throws IOException {
    if (Files.isSymbolicLink(file.toPath())
        || Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !file.isFile())
      throw new IOException("FILE_PARTIAL_UNSAFE");
    if (offset > 0 && (!file.isFile() || file.length() != offset))
      throw new IOException("FILE_PARTIAL_CHANGED");
    openedOffsets.add(offset);
    return new FileOutputStream(file, offset > 0);
  }

  @Before
  public void prepare() throws Exception {
    fixture = new TransferBehaviorTestSupport();
    engine = fixture.engine();
    artifact = artifact("network-fixture", "network.bin", BYTES);
    openedOffsets.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverWorker =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "transfer-loopback-server");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverWorker);
    server.createContext("/api/dsha/files/", this::handle);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    Controller.identity =
        new PreviewPageSession.Identity(41, "network-instance", origin + "/?token=fixture");
    Controller.cookie = COOKIE;
    reply = (exchange, request) -> send(exchange, 200, BYTES);
  }

  @After
  public void release() throws Exception {
    try {
      fixture.close();
    } finally {
      if (server != null) server.stop(0);
      if (serverWorker != null) serverWorker.shutdownNow();
    }
    if (serverError.get() != null) throw new AssertionError("本机 HTTP 夹具失败", serverError.get());
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      Request request = new Request(exchange);
      requests.add(request);
      if (request.path.endsWith("/resolve")) {
        JSONObject manifest = artifact.toJson();
        // resolve 中的 URL 不是选主机的依据；真实 Engine 必须仍走本次已绑定 loopback。
        manifest.put("contentUrl", "http://127.0.0.1:1/must-be-ignored");
        send(exchange, 200, manifest.toString().getBytes(StandardCharsets.UTF_8));
      } else if (request.path.endsWith("/content")) {
        reply.send(exchange, request);
      } else if (request.path.endsWith("/release")) {
        send(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
      } else {
        send(exchange, 404, new byte[0]);
      }
    } catch (Throwable failure) {
      serverError.compareAndSet(null, failure);
    } finally {
      exchange.close();
    }
  }

  private void send(HttpExchange exchange, int status, byte[] bytes) throws IOException {
    exchange.getResponseHeaders().set("ETag", '"' + artifact.sha256 + '"');
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private void sendRange(HttpExchange exchange, int offset) throws IOException {
    exchange
        .getResponseHeaders()
        .set("Content-Range", "bytes " + offset + "-" + (BYTES.length - 1) + "/" + BYTES.length);
    send(exchange, 206, Arrays.copyOfRange(BYTES, offset, BYTES.length));
  }

  private String enqueue() throws Exception {
    return engine.enqueue(artifact, "downloads");
  }

  private void drain() throws Exception {
    engine.runQueued();
    queue(engine).runAll();
    idle();
  }

  private List<Request> contentRequests() {
    return requests.stream().filter(request -> request.path.endsWith("/content")).toList();
  }

  private void complete(String id) throws Exception {
    assertEquals("COMPLETE", engine.snapshot(id).getString("status"));
    assertArrayEquals(BYTES, fixture.media.require(fixture.media.last).bytes);
    assertFalse(fixture.partial(id).exists());
    assertFalse(engine.isBusy());
    assertNull(field(engine, "activeConnection"));
  }

  @Test
  public void actualHttpResolveDownloadAndReleaseUseBoundOriginCookieAndManifestIdentity()
      throws Exception {
    String id = enqueue();
    drain();
    complete(id);
    ((ManualExecutor) field(engine, "releaseWorker")).runAll();
    assertEquals(
        List.of("/api/dsha/files/resolve", "/api/dsha/files/content", "/api/dsha/files/release"),
        requests.stream().map(request -> request.path).toList());
    for (Request request : requests) {
      assertEquals(COOKIE, request.cookie);
      assertEquals(origin, request.origin);
    }
    Request resolve = requests.get(0), content = contentRequests().get(0);
    assertEquals("POST", resolve.method);
    JSONObject body = new JSONObject(new String(resolve.body, StandardCharsets.UTF_8));
    assertEquals(artifact.id, body.getString("id"));
    assertEquals(artifact.sha256, body.getString("sha256"));
    assertEquals(BYTES.length, body.getLong("bytes"));
    assertEquals("GET", content.method);
    assertEquals("id=" + artifact.id, content.query);
    assertEquals("bytes=0-", content.range);
    assertEquals('"' + artifact.sha256 + '"', content.ifRange);
    assertEquals("identity", content.encoding);
    assertEquals(List.of(0L), openedOffsets);
  }

  @Test
  public void matching206ResumesAtExistingBytesAndPublishesTheWholeVerifiedFile() throws Exception {
    int offset = 7;
    reply = (exchange, request) -> sendRange(exchange, offset);
    String id = enqueue();
    fixture.partial(id, Arrays.copyOf(BYTES, offset));
    drain();
    complete(id);
    assertEquals("bytes=" + offset + "-", contentRequests().get(0).range);
    assertEquals('"' + artifact.sha256 + '"', contentRequests().get(0).ifRange);
    assertEquals(List.of((long) offset), openedOffsets);
  }

  @Test
  public void serverIgnoringRangeWith200ReplacesStalePrefixFromOffsetZero() throws Exception {
    String id = enqueue();
    fixture.partial(id, "wrong".getBytes(StandardCharsets.UTF_8));
    drain();
    complete(id);
    assertEquals("bytes=5-", contentRequests().get(0).range);
    assertEquals(List.of(0L), openedOffsets);
    assertEquals(1, contentRequests().size());
  }

  @Test
  public void halfBodyDisconnectKeepsPrivatePrefixAndNeedsManualResumeBeforeNextHttpRequest()
      throws Exception {
    int half = BYTES.length / 2;
    AtomicInteger attempts = new AtomicInteger();
    reply =
        (exchange, request) -> {
          if (attempts.incrementAndGet() > 1) {
            sendRange(exchange, half);
            return;
          }
          exchange.getResponseHeaders().set("ETag", '"' + artifact.sha256 + '"');
          exchange.sendResponseHeaders(200, BYTES.length);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(BYTES, 0, half);
            output.flush();
          } catch (IOException expectedShortBody) {
            // 固定长度响应故意少写后断开；这是被测传输故障，不是夹具清理失败。
          }
        };
    String id = enqueue();
    drain();
    assertEquals("FAILED", engine.snapshot(id).getString("status"));
    assertArrayEquals(Arrays.copyOf(BYTES, half), Files.readAllBytes(fixture.partial(id).toPath()));
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertNull(field(engine, "activeConnection"));
    drain();
    assertEquals(1, contentRequests().size());
    engine.resume(id);
    drain();
    complete(id);
    assertEquals(2, contentRequests().size());
    assertEquals("bytes=" + half + "-", contentRequests().get(1).range);
    assertEquals(List.of(0L, (long) half), openedOffsets);
  }

  @Test
  public void oversizedChunkedBodyCannotWriteBeyondArtifactLimitOrReachPublication()
      throws Exception {
    reply =
        (exchange, request) -> {
          // 故意构造 TE+CL 响应：头中长度通过检查，流中多出的字节仍须由 Engine 拒绝。
          exchange.getResponseHeaders().set("ETag", '"' + artifact.sha256 + '"');
          exchange.getResponseHeaders().set("Content-Length", String.valueOf(BYTES.length));
          exchange.sendResponseHeaders(200, 0);
          try (OutputStream output = exchange.getResponseBody()) {
            output.write(Arrays.copyOf(BYTES, BYTES.length + 1));
          }
        };
    String id = enqueue();
    drain();
    assertEquals("FAILED", engine.snapshot(id).getString("status"));
    assertEquals("FILE_LENGTH_INVALID", engine.snapshot(id).getString("message"));
    assertEquals("应在通过响应头校验后进入真实下载流", List.of(0L), openedOffsets);
    assertTrue(fixture.partial(id).isFile());
    assertTrue(fixture.partial(id).length() <= BYTES.length);
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertNull(field(engine, "activeConnection"));
  }

  @Test
  public void sameUrlNewPageGenerationRejectsOldBodyBeforeWritingOrPublishing() throws Exception {
    PreviewPageSession.Identity old = Controller.identity;
    reply =
        (exchange, request) -> {
          Controller.identity =
              new PreviewPageSession.Identity(
                  old.generation() + 1, old.instanceId(), old.authUrl());
          send(exchange, 200, BYTES);
        };
    String id = enqueue();
    drain();
    assertEquals("FAILED", engine.snapshot(id).getString("status"));
    assertEquals("FILE_RUNTIME_CHANGED", engine.snapshot(id).getString("message"));
    assertEquals(old.authUrl(), Controller.identity.authUrl());
    assertTrue(Controller.identity.generation() > old.generation());
    assertTrue(fixture.partial(id).isFile());
    assertEquals(0, fixture.partial(id).length());
    assertTrue(fixture.media.insertedNames.isEmpty());
    assertNull(field(engine, "activeConnection"));
    assertEquals(1, contentRequests().size());
  }
}
