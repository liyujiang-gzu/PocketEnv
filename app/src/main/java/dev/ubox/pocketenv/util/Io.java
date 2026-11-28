package dev.ubox.pocketenv.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 流读写工具，命名与签名对齐 API 33 才加入的
 * {@link InputStream#transferTo(OutputStream)} 和 {@link InputStream#readAllBytes()}。
 *
 * <p>项目 minSdk 26，直接调用原生方法会在 Android 8–12（API 26–32）抛
 * {@link NoSuchMethodError}，故统一走这里的手动缓冲实现（全版本可用）。调用点写
 * {@code Io.transferTo(in, out)} / {@code Io.readAllBytes(in)}，与原生 API 读法一致，
 * 仅多一个 {@code Io.} 前缀。
 */
public final class Io {
  private static final int BUFFER_SIZE = 1 << 16; // 64 KiB

  private Io() {}

  /** 等价于 {@link InputStream#transferTo(OutputStream)}；不关闭任何一个流，返回复制的字节数。 */
  public static long transferTo(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int n;
    while ((n = in.read(buffer)) != -1) {
      out.write(buffer, 0, n);
      total += n;
    }
    return total;
  }

  /** 等价于 {@link InputStream#readAllBytes()}；不关闭流。 */
  public static byte[] readAllBytes(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transferTo(in, out);
    return out.toByteArray();
  }
}
