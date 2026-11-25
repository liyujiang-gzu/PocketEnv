package dev.ubox.app;

import android.provider.DocumentsProvider;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 通过 SAF（存储访问框架）把 rootfs 暴露给 Android 文件管理器。
 *
 * <p>rootfs 不能直接放到 /sdcard：共享存储的 FUSE 不支持符号链接和执行权限，proot 会起不来。所以文件仍保存在应用内部存储，由这个
 * DocumentsProvider 提供给系统文件管理器和其他支持 SAF 的应用浏览、复制。
 *
 * <p>除 /root 外都只读：宿主持有的文件句柄绕过了 proot 的权限映射，改动系统文件可能破坏 apt/dpkg 的状态。/root 是用户自己的目录，允许文件管理器读写，方便把文件拷进拷出。
 */
public final class RootfsProvider extends DocumentsProvider {
  static final String AUTHORITY = "dev.ubox.app.rootfs";
  static final String ROOT_ID = "rootfs";
  private static final String ROOT_NAME = "UbuntuBox";

  private static final String[] ROOT_COLUMNS = {
    Root.COLUMN_ROOT_ID,
    Root.COLUMN_DOCUMENT_ID,
    Root.COLUMN_TITLE,
    Root.COLUMN_SUMMARY,
    Root.COLUMN_FLAGS,
    Root.COLUMN_ICON,
    Root.COLUMN_MIME_TYPES,
    Root.COLUMN_AVAILABLE_BYTES,
  };

  private static final String[] DOCUMENT_COLUMNS = {
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
    Document.COLUMN_FLAGS,
    Document.COLUMN_SIZE,
    Document.COLUMN_LAST_MODIFIED,
  };

  private Rootfs rootfs;

  @Override
  public boolean onCreate() {
    rootfs = new Rootfs(getContext());
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    String[] columns = projection == null ? ROOT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String column : columns) {
      switch (column) {
        case Root.COLUMN_ROOT_ID:
          row.add(ROOT_ID);
          break;
        case Root.COLUMN_DOCUMENT_ID:
          row.add(ROOT_ID);
          break;
        case Root.COLUMN_TITLE:
          row.add(ROOT_NAME);
          break;
        case Root.COLUMN_SUMMARY:
          row.add("容器文件与个人数据");
          break;
        case Root.COLUMN_FLAGS:
          // 不能只给 FLAG_LOCAL_ONLY：它只向系统 DocumentsUI 区分「本地/云端」，
          // 不含可操作语义。MT 管理器等第三方文件管理器会按 root 是否声明可创建
          // 等能力来过滤，只读 root 会被从 SAF 列表里过滤掉（系统自带文件管理器
          // 更宽松，照列不误）。这里参照 Termux 补上 FLAG_SUPPORTS_CREATE 和
          // FLAG_SUPPORTS_IS_CHILD（Root 级别没有 WRITE/DELETE 常量，可写性由各
          // document 的 flags 与 createDocument/openDocument 的 writable() 把关，
          // 仅 /root 可写）。
          row.add(Root.FLAG_LOCAL_ONLY
              | Root.FLAG_SUPPORTS_CREATE
              | Root.FLAG_SUPPORTS_IS_CHILD);
          break;
        case Root.COLUMN_ICON:
          row.add(R.drawable.ic_launcher);
          break;
        case Root.COLUMN_MIME_TYPES:
          row.add("*/*");
          break;
        case Root.COLUMN_AVAILABLE_BYTES:
          row.add(rootfs.rootfs.getUsableSpace());
          break;
        default:
          row.add(null);
      }
    }
    return cursor;
  }

  @Override
  public Cursor queryDocument(String documentId, String[] projection)
      throws FileNotFoundException {
    File file = fileFor(documentId);
    if (file == null || !file.exists()) throw new FileNotFoundException(documentId);
    String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    addDocument(cursor, columns, documentId, file);
    return cursor;
  }

  @Override
  public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
      throws FileNotFoundException {
    File parent = fileFor(parentDocumentId);
    if (parent == null || !parent.isDirectory()) throw new FileNotFoundException(parentDocumentId);
    String[] columns = projection == null ? DOCUMENT_COLUMNS : projection;
    MatrixCursor cursor = new MatrixCursor(columns);
    File[] children = parent.listFiles();
    if (children != null) {
      Arrays.sort(children, (a, b) -> {
        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.getName().compareToIgnoreCase(b.getName());
      });
      for (File child : children) {
        String id = childId(parentDocumentId, child.getName());
        // 指向 rootfs 之外（例如 /proc）的符号链接不对外暴露
        if (fileFor(id) == null) continue;
        addDocument(cursor, columns, id, child);
      }
    }
    return cursor;
  }

  @Override
  public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    File file = fileFor(documentId);
    if (file == null || !file.isFile()) throw new FileNotFoundException(documentId);
    if (!"r".equals(mode) && !writable(file)) throw new FileNotFoundException("/root 以外为只读");
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
  }

  @Override
  public String createDocument(String parentDocumentId, String mimeType, String displayName)
      throws FileNotFoundException {
    File parent = fileFor(parentDocumentId);
    if (parent == null || !parent.isDirectory() || !writable(parent) || !writableId(parentDocumentId))
      throw new FileNotFoundException("只有 /root 可写");
    String name = sanitizeName(displayName);
    if (name == null) throw new FileNotFoundException("非法文件名：" + displayName);
    File target = uniqueFile(parent, name);
    try {
      if (Document.MIME_TYPE_DIR.equals(mimeType)) {
        if (!target.mkdir()) throw new IOException("无法创建目录");
      } else if (!target.createNewFile()) {
        throw new IOException("无法创建文件");
      }
    } catch (IOException error) {
      throw new FileNotFoundException(error.getMessage());
    }
    return childId(parentDocumentId, target.getName());
  }

  @Override
  public void deleteDocument(String documentId) throws FileNotFoundException {
    File file = fileFor(documentId);
    if (file == null || !writableId(documentId) || isRootDir(documentId))
      throw new FileNotFoundException("只有 /root 下的内容可删除");
    deleteTree(file);
  }

  @Override
  public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
    File file = fileFor(documentId);
    if (file == null || !writableId(documentId) || isRootDir(documentId))
      throw new FileNotFoundException("只有 /root 下的内容可重命名");
    String name = sanitizeName(displayName);
    if (name == null) throw new FileNotFoundException("非法文件名：" + displayName);
    if (name.equals(file.getName())) return documentId;
    File target = new File(file.getParentFile(), name);
    if (target.exists()) throw new FileNotFoundException("同名文件已存在：" + name);
    if (!file.renameTo(target)) throw new FileNotFoundException("无法重命名：" + documentId);
    int slash = documentId.lastIndexOf('/');
    String parentId = slash < 0 ? ROOT_ID : documentId.substring(0, slash);
    return childId(parentId, name);
  }

  @Override
  public boolean isChildDocument(String parentDocumentId, String documentId) {
    File parent = fileFor(parentDocumentId);
    File child = fileFor(documentId);
    if (parent == null || child == null) return false;
    try {
      return child.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator);
    } catch (IOException error) {
      return false;
    }
  }

  /** documentId 是相对 rootfs 的路径；ROOT_ID 表示根目录。 */
  private File fileFor(String documentId) {
    if (documentId == null) return null;
    if (ROOT_ID.equals(documentId)) return rootfs.rootfs;
    if (documentId.isEmpty() || documentId.startsWith("/")) return null;
    for (String part : documentId.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
    File file = new File(rootfs.rootfs, documentId);
    return insideRootfs(file) ? file : null;
  }

  private boolean insideRootfs(File file) {
    try {
      String root = rootfs.rootfs.getCanonicalPath();
      String path = file.getCanonicalPath();
      return path.equals(root) || path.startsWith(root + File.separator);
    } catch (IOException error) {
      return false;
    }
  }

  private static String childId(String parentId, String name) {
    return ROOT_ID.equals(parentId) ? name : parentId + "/" + name;
  }

  private void addDocument(MatrixCursor cursor, String[] columns, String id, File file) {
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String column : columns) {
      switch (column) {
        case Document.COLUMN_DOCUMENT_ID:
          row.add(id);
          break;
        case Document.COLUMN_DISPLAY_NAME:
          row.add(ROOT_ID.equals(id) ? ROOT_NAME : file.getName());
          break;
        case Document.COLUMN_MIME_TYPE:
          row.add(mimeType(file));
          break;
        case Document.COLUMN_FLAGS:
          row.add(flags(id, file));
          break;
        case Document.COLUMN_SIZE:
          row.add(file.isDirectory() ? 0 : file.length());
          break;
        case Document.COLUMN_LAST_MODIFIED:
          row.add(file.lastModified());
          break;
        case Document.COLUMN_ICON:
          row.add(R.drawable.ic_launcher);
          break;
        default:
          row.add(null);
      }
    }
  }

  private static String mimeType(File file) {
    if (file.isDirectory()) return Document.MIME_TYPE_DIR;
    String type = URLConnection.guessContentTypeFromName(file.getName());
    return type == null ? "application/octet-stream" : type;
  }

  /** 只有 /root 下的节点可写。用规范化路径判断，避免 /root 里的符号链接指到别处。 */
  private boolean writable(File file) {
    try {
      String root = new File(rootfs.rootfs, "root").getCanonicalPath();
      String path = file.getCanonicalPath();
      return path.equals(root) || path.startsWith(root + File.separator);
    } catch (IOException error) {
      return false;
    }
  }

  private static boolean isRootDir(String documentId) {
    return "root".equals(documentId);
  }

  private int flags(String documentId, File file) {
    if (!writableId(documentId)) return 0;
    int flags = 0;
    if (writable(file)) {
      flags |= file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE;
    }
    // /root 本身不允许删除或改名
    if (!isRootDir(documentId)) flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
    return flags;
  }

  /** 按路径判断是否在 /root 内；删除/改名针对链接本身，不跟随符号链接。 */
  private static boolean writableId(String documentId) {
    return "root".equals(documentId) || documentId.startsWith("root/");
  }

  private static void deleteTree(File file) throws FileNotFoundException {
    if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteTree(child);
    }
    if (!file.delete()) throw new FileNotFoundException("无法删除：" + file.getName());
  }

  private static String sanitizeName(String name) {
    if (name == null) return null;
    name = name.trim();
    if (name.isEmpty() || name.equals(".") || name.equals("..")
        || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0) return null;
    return name;
  }

  /** SAF 要求新建时不能覆盖已有文件，重名就加序号。 */
  private static File uniqueFile(File directory, String name) {
    File file = new File(directory, name);
    if (!file.exists()) return file;
    String base = name, extension = "";
    int dot = name.lastIndexOf('.');
    if (dot > 0) {
      base = name.substring(0, dot);
      extension = name.substring(dot);
    }
    for (int index = 1; index < 10000; index++) {
      file = new File(directory, base + " (" + index + ")" + extension);
      if (!file.exists()) return file;
    }
    return new File(directory, name + "." + System.nanoTime());
  }

  /** 供设置/菜单跳转时构造初始目录 URI。 */
  static Uri rootUri() {
    return android.provider.DocumentsContract.buildDocumentUri(AUTHORITY, ROOT_ID);
  }
}
