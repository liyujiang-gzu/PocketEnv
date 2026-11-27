package dev.ubox.pocketenv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Rootfs.normalize 的路径安全：拒绝绝对路径与 ..，防止解压逃逸。 */
public class RootfsNormalizeTest {
  @Test public void 去掉相对前缀() {
    assertEquals("etc/hosts", Rootfs.normalize("./etc/hosts"));
    assertEquals("etc/hosts", Rootfs.normalize("././etc/hosts"));
  }

  @Test public void 去掉绝对前缀() {
    assertEquals("etc/hosts", Rootfs.normalize("/etc/hosts"));
  }

  @Test public void 去掉尾部斜杠() {
    assertEquals("etc", Rootfs.normalize("etc/"));
    assertEquals("usr", Rootfs.normalize("/usr/"));
  }

  @Test public void 拒绝路径逃逸() {
    assertNull(Rootfs.normalize("../escape"));
    assertNull(Rootfs.normalize("a/../../escape"));
    assertNull(Rootfs.normalize("a/../b/../../../c"));
  }

  @Test public void 空与当前目录返回null() {
    assertNull(Rootfs.normalize(""));
    assertNull(Rootfs.normalize("."));
    assertNull(Rootfs.normalize("./"));
    assertNull(Rootfs.normalize("/"));
  }

  @Test public void 中间的点保留为普通路径() {
    // 只有「整段等于 ..」才拒绝，名字里含点的正常文件不受影响
    assertEquals("a.txt", Rootfs.normalize("a.txt"));
    assertEquals("dir/file.name", Rootfs.normalize("dir/file.name"));
  }
}
