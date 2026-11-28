package dev.ubox.pocketenv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.ubox.pocketenv.model.Distro;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** 发行版模型：id 唯一、反查、shell 与包管理器映射、下载源与校验值齐备。 */
public class DistroTest {

  @Test
  public void idsAreUniqueAndNonEmpty() {
    Set<String> ids = new HashSet<>();
    for (Distro d : Distro.values()) {
      assertNotNull(d.id);
      assertTrue("id 不能为空", !d.id.isEmpty());
      assertTrue("id 重复: " + d.id, ids.add(d.id));
    }
  }

  @Test
  public void byIdResolvesEachDistro() {
    for (Distro d : Distro.values()) assertEquals(d, Distro.byId(d.id));
  }

  @Test
  public void byIdReturnsNullForUnknown() {
    assertNull(Distro.byId("nonexistent"));
    assertNull(Distro.byId(null));
  }

  @Test
  public void alpineUsesAshOthersUseBash() {
    assertEquals("/bin/ash", Distro.ALPINE_3.shell());
    assertEquals("/bin/bash", Distro.UBUNTU_24.shell());
    assertEquals("/bin/bash", Distro.DEBIAN_13.shell());
  }

  @Test
  public void alpineUsesApkOthersUseApt() {
    assertEquals(Distro.Pkg.APK, Distro.ALPINE_3.pkg);
    assertEquals(Distro.Pkg.APT, Distro.UBUNTU_24.pkg);
    assertEquals(Distro.Pkg.APT, Distro.DEBIAN_13.pkg);
  }

  @Test
  public void debianUsesXzOthersUseGzip() {
    assertEquals(Distro.Compress.XZ, Distro.DEBIAN_13.compress);
    assertEquals(Distro.Compress.GZIP, Distro.UBUNTU_24.compress);
    assertEquals(Distro.Compress.GZIP, Distro.ALPINE_3.compress);
  }

  @Test
  public void downloadSourcesArePresent() {
    for (Distro d : Distro.values()) {
      assertTrue("至少一个下载源: " + d.id, d.sources.length > 0);
      assertNotNull(d.title);
      assertTrue(!d.title.isEmpty());
      for (Distro.Source source : d.sources) {
        assertNotNull(source.url);
        assertTrue("下载源应为 https: " + source.url, source.url.startsWith("https://"));
      }
    }
  }

  @Test
  public void fixedSourcesCarryValidSha256() {
    for (Distro d : Distro.values()) {
      for (Distro.Source source : d.sources) {
        if (!(source instanceof Distro.ResolvedSource)) {
          // 固定源必须带 64 位十六进制 SHA256
          assertNotNull("固定源应有 SHA256: " + source.url, source.sha256);
          assertEquals(64, source.sha256.length());
          assertTrue(source.sha256.matches("[0-9a-fA-F]{64}"));
        }
      }
    }
  }

  @Test
  public void fixedSourceResolvesToItself() throws Exception {
    Distro.Source source = Distro.UBUNTU_24.sources[0];
    Distro.Resolved resolved = source.resolve();
    assertEquals(source.url, resolved.url);
    assertEquals(source.sha256, resolved.sha256);
  }
}
