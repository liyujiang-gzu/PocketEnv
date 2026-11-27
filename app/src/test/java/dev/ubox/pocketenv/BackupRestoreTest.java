package dev.ubox.pocketenv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 还原入口的文件名处理：只接受 gzip 备份，并避免 SAF 返回的路径分隔符逃逸目标目录。 */
public class BackupRestoreTest {

  @Test public void 接受tar_gz和tgz备份() {
    assertTrue(SettingsActivity.isBackupFile("backup-20261127-171500.tar.gz"));
    assertTrue(SettingsActivity.isBackupFile("BACKUP.TGZ"));
  }

  @Test public void 拒绝其他扩展名() {
    assertFalse(SettingsActivity.isBackupFile("backup.zip"));
    assertFalse(SettingsActivity.isBackupFile("backup.tar.gz.exe"));
  }

  @Test public void 文件名去掉路径分隔符() {
    assertEquals("a_b.tar.gz", SettingsActivity.safeFileName("a/b.tar.gz"));
    assertEquals("a_b.tar.gz", SettingsActivity.safeFileName("a\\b.tar.gz"));
  }

  @Test public void 缺失或危险文件名回退到默认值() {
    assertEquals("restore.tar.gz", SettingsActivity.safeFileName(null));
    assertEquals("restore.tar.gz", SettingsActivity.safeFileName("  "));
    assertEquals("restore.tar.gz", SettingsActivity.safeFileName("."));
    assertEquals("restore.tar.gz", SettingsActivity.safeFileName(".."));
  }
}
