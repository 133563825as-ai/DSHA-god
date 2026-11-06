package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 「隐藏后台」生产接线合同：偏好必须在 Main 启动、预览宿主新建与回到前台、设置开关三处重放；
 * 最近任务可见性仍以 Main 单张主卡为准。只核对生产接线与清单语义，不冒充 ColorOS/Lenovo 真机测试。
 */
public class RecentsPreferenceContractTest {
  private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
  private static final String APPLY = "BrowserTasks.applyRecentsPreference(";

  private static Path root() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve("app/src/main/AndroidManifest.xml")))
      path = path.getParent();
    if (path == null) throw new AssertionError("Cannot locate DSHA source tree");
    return path;
  }

  private static String source(String name) throws Exception {
    return Files.readString(
            root().resolve("app/src/main/java/com/deepseekharness/app/ui/" + name + ".java"),
            StandardCharsets.UTF_8)
        .replaceAll("\\s+", " ");
  }

  private static String method(String source, String signature) {
    int start = source.indexOf(signature);
    assertTrue("Missing method " + signature, start >= 0);
    int open = source.indexOf('{', start), level = 1, end = open + 1;
    for (; end < source.length() && level != 0; end++) {
      if (source.charAt(end) == '{') level++;
      if (source.charAt(end) == '}') level--;
    }
    assertTrue("Unclosed method " + signature, level == 0);
    return source.substring(open + 1, end - 1);
  }

  @Test
  public void mainReplaysTheHidePreferenceOnEveryLaunch() throws Exception {
    String create =
        method(
            source("MainActivity"), "protected void onCreate(@Nullable Bundle savedInstanceState)");
    assertTrue("Main 启动必须重放「隐藏后台」", create.contains(APPLY + "this)"));
  }

  @Test
  public void previewHostReplaysTheHidePreferenceOnCreateAndResume() throws Exception {
    String preview = source("WebPreviewActivity");
    String create = method(preview, "protected void onCreate(@Nullable Bundle savedInstanceState)");
    assertTrue("预览宿主新建必须重放「隐藏后台」", create.contains(APPLY + "this)"));
    // 转发给存活宿主的实例直接结束，重放放在转发早退之后，避免为丢弃的实例改任务可见性。
    assertTrue(create.indexOf(APPLY) > create.indexOf("forwardToLiveBrowser"));

    String resume = method(preview, "protected void onResume()");
    assertTrue("预览宿主回到前台必须重放「隐藏后台」", resume.contains(APPLY + "this)"));
    // 必须在同步早退之前，否则回前台的重放会被跳过。
    assertTrue(resume.indexOf(APPLY) < resume.indexOf("synchronizeWebSession()"));
  }

  @Test
  public void settingsSwitchStillAppliesImmediately() throws Exception {
    String settings = source("SettingsFragment");
    assertTrue(settings.contains("config.setHideFromRecents(checked);"));
    assertTrue(settings.contains(APPLY + "requireContext())"));
  }

  @Test
  public void onlyTaskRootTransientsAreExcludedFromRecents() throws Exception {
    // 同任务内的权限设置、维护页等不是任务根，android:excludeFromRecents 对它们无效，
    // 不加无效清单项；只有会作为任务根的预览宿主与通知中转引用该属性，Main 始终作为主卡保留。
    Map<String, String> excluded = new LinkedHashMap<>();
    // 默认工厂不带命名空间：不开启时 android:excludeFromRecents 会退化成无前缀属性名。
    javax.xml.parsers.DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    NodeList activities =
        factory
            .newDocumentBuilder()
            .parse(root().resolve("app/src/main/AndroidManifest.xml").toFile())
            .getElementsByTagName("activity");
    for (int i = 0; i < activities.getLength(); i++) {
      Element activity = (Element) activities.item(i);
      if (activity.hasAttributeNS(ANDROID_NS, "excludeFromRecents"))
        excluded.put(
            activity.getAttributeNS(ANDROID_NS, "name"),
            activity.getAttributeNS(ANDROID_NS, "excludeFromRecents"));
    }
    assertEquals("true", excluded.remove(".ui.WebPreviewActivity"));
    assertEquals("true", excluded.remove(".ui.AppEntryRouterActivity"));
    assertTrue("常驻 Main 必须保留最近任务主卡：" + excluded, excluded.isEmpty());
  }
}
