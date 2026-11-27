package dev.ubox.pocketenv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 额外按键的权重分配：保证窄屏下长标签不被裁剪。 */
public class KeyWeightTest {
  @Test public void 窄字符权重小于宽字符() {
    assertTrue(MainActivity.keyWeight("ESC") < MainActivity.keyWeight("SHIFT"));
    assertTrue(MainActivity.keyWeight("-") < MainActivity.keyWeight("HOME"));
  }

  @Test public void 中文按最宽计() {
    // 中文 12dp/字 + 基础 4dp
    assertEquals(4 + 12, MainActivity.keyWeight("退"), 0.001f);
  }

  @Test public void 大写字母比小写宽() {
    assertTrue(MainActivity.keyWeight("abc") < MainActivity.keyWeight("ABC"));
  }

  @Test public void 空标签只有基础宽度() {
    assertEquals(4f, MainActivity.keyWeight(""), 0.001f);
  }
}
