package dev.ubox.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 会话显示名格式：无标题文字「会话N」，有标题文字「N. 名称 · 标题」。 */
public class SessionLabelTest {

  @Test public void 默认会话无标题显示会话编号() {
    assertEquals("会话1", MainActivity.sessionLabel(1, null, null));
  }

  @Test public void 默认会话有标题只显示编号和标题() {
    assertEquals("2. bash", MainActivity.sessionLabel(2, null, "bash"));
  }

  @Test public void 自定义名称无标题显示编号和名称() {
    assertEquals("3. 安装 curl", MainActivity.sessionLabel(3, "安装 curl", null));
  }

  @Test public void 自定义名称有标题显示编号名称和标题() {
    assertEquals("3. 安装 curl · bash", MainActivity.sessionLabel(3, "安装 curl", "bash"));
  }

  @Test public void 空标题视为无标题() {
    assertEquals("会话4", MainActivity.sessionLabel(4, null, ""));
  }

  @Test public void 自定义名称配空标题只显示名称() {
    assertEquals("5. 卸载 vim", MainActivity.sessionLabel(5, "卸载 vim", ""));
  }
}
