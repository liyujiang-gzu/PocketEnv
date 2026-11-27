package dev.ubox.pocketenv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 工具目录的命令生成：安装/卸载/检测脚本的关键形态回归。 */
public class ToolboxTest {
  private static Toolbox.Tool tool(String id) {
    for (Toolbox.Section section : Toolbox.SECTIONS)
      for (Toolbox.Tool tool : section.tools())
        if (tool.id().equals(id)) return tool;
    throw new IllegalArgumentException("无此工具: " + id);
  }

  @Test public void apt安装命令包含全部包名() {
    Toolbox.Tool net = tool("net");
    assertEquals("ubox-install apt curl wget ca-certificates dnsutils net-tools",
        net.installCommand());
  }

  @Test public void apt卸载走apt_remove() {
    assertTrue(tool("net").removeCommand().startsWith("ubox-install apt-remove "));
  }

  @Test public void npm安装带软链兜底() {
    String command = tool("claude").installCommand();
    assertTrue(command.startsWith("ubox-install npm @anthropic-ai/claude-code"));
    // 兜底软链必须存在
    assertTrue(command.contains("ln -sf"));
  }

  @Test public void curl安装直接跑脚本() {
    assertEquals("curl -fsSL https://opencode.ai/v2/install | bash",
        tool("opencode").installCommand());
  }

  @Test public void 状态检测输出键值对() {
    String check = tool("git").statusCheck();
    // 输出必须以 id=1 / id=0 结尾，供 SettingsActivity 解析
    assertTrue(check.endsWith("echo git=1 || echo git=0"));
  }

  @Test public void 每个工具id唯一() {
    java.util.Set<String> ids = new java.util.HashSet<>();
    for (Toolbox.Section section : Toolbox.SECTIONS)
      for (Toolbox.Tool tool : section.tools())
        assertTrue("重复 id: " + tool.id(), ids.add(tool.id()));
  }

  @Test public void 状态脚本包含全部工具与SSH检测() {
    String script = Toolbox.statusScript();
    for (Toolbox.Section section : Toolbox.SECTIONS)
      for (Toolbox.Tool tool : section.tools())
        assertTrue("缺少 " + tool.id(), script.contains(tool.statusCheck()));
    assertTrue(script.contains("sshd=1"));
    assertTrue(script.contains("sshpw="));
    assertFalse(script.isEmpty());
  }

  @Test public void 解析状态输出() {
    java.util.Map<String, Boolean> installed = Toolbox.parseInstalled(
        "git=1\nnode=0\nsshkeys=3\nmirror=mirrors.tuna.tsinghua.edu.cn\n");
    assertEquals(true, installed.get("git"));
    assertEquals(false, installed.get("node"));
    // 非布尔键也会进 map，但值不是 1 时为 false
    assertEquals(false, installed.get("sshkeys"));
  }
}
