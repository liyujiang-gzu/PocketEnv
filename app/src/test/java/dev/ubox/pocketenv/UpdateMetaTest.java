package dev.ubox.pocketenv;

import dev.ubox.pocketenv.model.UpdateMeta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 版本元数据的 versionCode 比较：更新判断的关键形态回归。 */
public class UpdateMetaTest {
  private static UpdateMeta meta(int versionCode) {
    return new UpdateMeta(versionCode, "1.0.0", "说明",
        "https://gitee.com/example/releases", "https://github.com/example/releases");
  }

  @Test public void 远端更高时判定有新版本() {
    assertTrue(meta(2026200).isNewer(2026100));
  }

  @Test public void 版本相同时判定无新版本() {
    assertFalse(meta(2026100).isNewer(2026100));
  }

  @Test public void 远端更低时判定无新版本() {
    assertFalse(meta(2026000).isNewer(2026100));
  }

  @Test public void 字段能被原样读取() {
    UpdateMeta m = meta(2026200);
    assertEquals(2026200, m.versionCode());
    assertEquals("1.0.0", m.versionName());
    assertEquals("说明", m.releaseNotes());
    assertEquals("https://gitee.com/example/releases", m.releaseUrlGitee());
    assertEquals("https://github.com/example/releases", m.releaseUrlGithub());
  }
}
