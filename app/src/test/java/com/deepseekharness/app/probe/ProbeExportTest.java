package com.deepseekharness.app.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.Test;

public class ProbeExportTest {
  private static ProbeSample.ProcInfo proc(
      int pid, int ppid, String name, long cpuTicks, long rssKb, int threads, boolean self) {
    return new ProbeSample.ProcInfo(pid, ppid, name, cpuTicks, rssKb, threads, self);
  }

  private static ProbeSample sample(long tMs, boolean withPss) {
    return ProbeSample.builder(tMs, 1_700_000_000_000L + tMs)
        .shell(100 + tMs, 200 + tMs, 30)
        .heap(10_000_000, 20_000_000)
        .pssKb(withPss ? 40_000 : -1)
        .gc(5, 12)
        .samplerCpuTicks(2)
        .processes(
            List.of(
                proc(1234, 1, "com.deepseekharness.app", 100 + tMs, 200 + tMs, 30, true),
                proc(2001, 1234, "node", 500 + tMs, 300 + tMs, 8, false)))
        .build();
  }

  private static ProbeExport.Meta meta() {
    ProbeExport.Meta meta = new ProbeExport.Meta();
    meta.generatedAtEpochMs = 1_700_000_010_000L;
    meta.startedAtEpochMs = 1_700_000_000_000L;
    meta.uptimeMs = 10_000;
    meta.clkTck = 100;
    meta.cpuCount = 8;
    meta.appPid = 1234;
    meta.appUid = 10123;
    meta.samplerSelfCpuMs = 12.5;
    meta.versionName = "0.2.1-rc.5";
    meta.versionCode = 334;
    meta.testBuild = "t6";
    meta.debug = true;
    meta.manufacturer = "Google";
    meta.model = "Pixel";
    meta.device = "oriole";
    meta.sdk = 33;
    meta.release = "13";
    return meta;
  }

  @Test
  public void exportsSchemaMetaTwoLayersAndEvents() {
    ProbeExport export =
        new ProbeExport(
            meta(),
            List.of(sample(0, true), sample(1000, false)),
            List.of(
                new ProbeEvent(0, 1_700_000_000_000L, ProbeEvent.TYPE_PROBE_START, "pid=1234"),
                new ProbeEvent(
                    900, 1_700_000_000_900L, ProbeEvent.TYPE_MAIN_BLOCK, "delayMs=312")));
    JsonObject root = JsonParser.parseString(export.toJson()).getAsJsonObject();

    assertEquals("dsha-perf-probe/3", root.get("schema").getAsString());

    JsonObject meta = root.getAsJsonObject("meta");
    assertEquals(1234, meta.get("appPid").getAsInt());
    assertEquals(10123, meta.get("appUid").getAsInt());
    assertEquals(1000, meta.get("sampleIntervalMs").getAsInt());
    assertEquals(60, meta.get("sampleCapacity").getAsInt());
    assertEquals(5000, meta.get("pssIntervalMs").getAsLong());
    assertEquals(2, meta.get("samples").getAsInt());
    assertEquals(2, meta.get("events").getAsInt());
    assertEquals(12.5, meta.get("samplerSelfCpuMs").getAsDouble(), 0.001);
    assertEquals("t6", meta.getAsJsonObject("build").get("testBuild").getAsString());
    assertTrue(meta.getAsJsonObject("build").get("debug").getAsBoolean());
    assertEquals("oriole", meta.getAsJsonObject("device").get("device").getAsString());
    assertTrue(meta.get("generatedAt").getAsString().endsWith("Z"));
    assertTrue(meta.get("startedAt").getAsString().endsWith("Z"));
    assertFalse(meta.get("note").getAsString().isEmpty());

    JsonObject layers = root.getAsJsonObject("layers");
    JsonArray shell = layers.getAsJsonObject("shell").getAsJsonArray("samples");
    assertEquals(2, shell.size());
    assertEquals(0, shell.get(0).getAsJsonObject().get("tMs").getAsLong());
    assertEquals(1000, shell.get(1).getAsJsonObject().get("tMs").getAsLong());
    assertEquals(40000, shell.get(0).getAsJsonObject().get("pssKb").getAsLong());
    assertEquals(-1, shell.get(1).getAsJsonObject().get("pssKb").getAsLong());
    assertEquals(100, shell.get(0).getAsJsonObject().get("cpuTicks").getAsLong());
    assertEquals(2, shell.get(0).getAsJsonObject().get("samplerCpuTicks").getAsLong());

    assertEquals(0, layers.getAsJsonObject("page").entrySet().size());

    JsonArray events = root.getAsJsonArray("events");
    assertEquals(2, events.size());
    assertEquals("PROBE_START", events.get(0).getAsJsonObject().get("type").getAsString());
    assertEquals("delayMs=312", events.get(1).getAsJsonObject().get("detail").getAsString());
  }

  @Test
  public void containerRowsKeepTimeAxisAndExcludeTheHostProcess() {
    ProbeExport export =
        new ProbeExport(meta(), List.of(sample(0, true), sample(1000, false)), List.of());
    JsonArray container =
        JsonParser.parseString(export.toJson())
            .getAsJsonObject()
            .getAsJsonObject("layers")
            .getAsJsonArray("container");
    assertEquals(2, container.size());
    JsonObject first = container.get(0).getAsJsonObject();
    assertEquals(0, first.get("tMs").getAsLong());
    assertEquals(2001, first.get("pid").getAsInt());
    assertEquals(1234, first.get("ppid").getAsInt());
    assertEquals("node", first.get("name").getAsString());
    assertEquals(500, first.get("cpuTicks").getAsLong());
    assertEquals(300, first.get("rssKb").getAsLong());
    assertEquals(8, first.get("threads").getAsInt());
    assertEquals(1000, container.get(1).getAsJsonObject().get("tMs").getAsLong());
    for (int i = 0; i < container.size(); i++) {
      assertFalse(container.get(i).getAsJsonObject().get("pid").getAsInt() == 1234);
    }
  }

  @Test
  public void escapesDetailTextAndPreservesUnicode() {
    String detail = "a\"b\\c\nd\te\u0001 中文 😀";
    ProbeExport export =
        new ProbeExport(
            meta(), List.of(), List.of(new ProbeEvent(1, 2, ProbeEvent.TYPE_IME, detail)));
    JsonObject event =
        JsonParser.parseString(export.toJson())
            .getAsJsonObject()
            .getAsJsonArray("events")
            .get(0)
            .getAsJsonObject();
    assertEquals(detail, event.get("detail").getAsString());
    assertEquals("IME", event.get("type").getAsString());
  }

  @Test
  public void emptyProbeStillExportsValidDocument() {
    ProbeExport export = new ProbeExport(new ProbeExport.Meta(), List.of(), List.of());
    String first = export.toJson();
    assertEquals(first, export.toJson());
    JsonObject root = JsonParser.parseString(first).getAsJsonObject();
    assertEquals("dsha-perf-probe/3", root.get("schema").getAsString());
    assertEquals(
        0,
        root.getAsJsonObject("layers").getAsJsonObject("shell").getAsJsonArray("samples").size());
    assertEquals(0, root.getAsJsonObject("layers").getAsJsonArray("container").size());
    assertEquals(0, root.getAsJsonArray("events").size());
    assertEquals(-1, root.getAsJsonObject("meta").get("appPid").getAsInt());
    assertEquals("", root.getAsJsonObject("meta").get("startedAt").getAsString());
  }

  @Test
  public void unknownValuesStayMinusOne() {
    ProbeSample bare = ProbeSample.builder(500, 1_700_000_000_500L).build();
    ProbeExport export = new ProbeExport(meta(), List.of(bare), List.of());
    JsonObject shell =
        JsonParser.parseString(export.toJson())
            .getAsJsonObject()
            .getAsJsonObject("layers")
            .getAsJsonObject("shell")
            .getAsJsonArray("samples")
            .get(0)
            .getAsJsonObject();
    assertEquals(-1, shell.get("cpuTicks").getAsLong());
    assertEquals(-1, shell.get("rssKb").getAsLong());
    assertEquals(-1, shell.get("threads").getAsInt());
    assertEquals(-1, shell.get("pssKb").getAsLong());
    assertEquals(-1, shell.get("gcCount").getAsLong());
    assertEquals(-1, shell.get("samplerCpuTicks").getAsLong());
  }
}
