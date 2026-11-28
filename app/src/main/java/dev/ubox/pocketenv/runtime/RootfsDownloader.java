package dev.ubox.pocketenv.runtime;

import android.content.Context;
import dev.ubox.pocketenv.model.Distro;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 从远程下载发行版 rootfs tarball：镜像优先、官方兜底，逐个地址回退；
 * 下载完成后做 SHA256 校验，不匹配则删除并报错，绝不把损坏的 rootfs 解压进环境。
 *
 * <p>rootfs 外置（不打进 APK），首次安装某发行版时按需下载一次，缓存到应用私有目录，
 * 解压成功后即删除 tarball 释放空间。
 */
public final class RootfsDownloader {
  /** 下载进度回调：done/total 为当前已下载字节数与 tarball 总大小。 */
  public interface Progress {
    void update(long done, long total);
  }

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;

  private final Context context;
  private final Distro distro;

  public RootfsDownloader(Context context, Distro distro) {
    this.context = context.getApplicationContext();
    this.distro = distro;
  }

  /**
   * 下载并校验 rootfs tarball，返回本地文件。
   *
   * <p>下载到 .part 临时文件，全部完成且校验通过后才重命名为最终文件；中途失败不留下
   * 半个可用 tarball。校验失败或所有地址均不可达时抛 {@link IOException}。
   */
  public File download(Progress progress) throws IOException {
    File cache = new File(context.getCacheDir(), distro.id + "-rootfs.tarball");
    File partial = new File(cache.getPath() + ".part");
    IOException lastError = null;
    for (String url : distro.urls()) {
      try {
        downloadOne(url, partial, progress);
        verify(partial);
        // 校验通过才落定为最终 tarball；下次解压失败重试时不必重新下载
        if (cache.exists()) cache.delete();
        if (!partial.renameTo(cache)) throw new IOException("无法保存下载的 rootfs");
        return cache;
      } catch (IOException error) {
        lastError = error;
        partial.delete(); // 换下一个地址重新下，不残留半截数据
      }
    }
    throw new IOException("下载 " + distro.title + " rootfs 失败："
        + (lastError != null ? lastError.getMessage() : "所有下载源均不可达"));
  }

  /** 下载单个地址到 partial 文件，回调已下载字节数。 */
  private void downloadOne(String url, File partial, Progress progress) throws IOException {
    HttpURLConnection connection = open(url);
    try {
      int code = connection.getResponseCode();
      if (code != HttpURLConnection.HTTP_OK)
        throw new IOException("HTTP " + code + " 来自 " + host(url));
      long total = connection.getContentLengthLong();
      try (InputStream in = new java.io.BufferedInputStream(connection.getInputStream(), 1 << 16);
          OutputStream out = new FileOutputStream(partial)) {
        byte[] buffer = new byte[1 << 16];
        long done = 0;
        long lastReport = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
          out.write(buffer, 0, n);
          done += n;
          if (progress != null && done - lastReport > 256 * 1024) {
            lastReport = done;
            progress.update(done, total);
          }
        }
        if (total > 0 && done != total)
          throw new IOException("下载不完整：" + done + "/" + total + " 字节");
      }
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestProperty("User-Agent", "PocketEnv-rootfs");
    return connection;
  }

  /** 校验已下载文件的整体 SHA256 与发行版登记值一致。 */
  private void verify(File file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IOException("缺少 SHA-256");
    }
    try (InputStream in = new java.io.FileInputStream(file)) {
      byte[] buffer = new byte[1 << 16];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format("%02x", b));
    if (!hex.toString().equalsIgnoreCase(distro.sha256))
      throw new IOException("SHA256 校验失败（下载源可能被篡改或下载不完整）");
  }

  private static String host(String url) {
    try {
      return new URL(url).getHost();
    } catch (IOException error) {
      return url;
    }
  }
}
