package dev.ubox.pocketenv.ui;

import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

/**
 * targetSdk 35+ 强制全面屏：内容会画到状态栏、导航栏下面，adjustResize 也不再缩小窗口。
 * 这里把系统栏、刘海和输入法的占位统一加成根布局的 padding。
 */
public final class EdgeToEdge {
  private EdgeToEdge() {}

  public static void padForSystemBars(View root) {
    // API 30 以下不会被强制全面屏，系统照常处理 adjustResize
    if (android.os.Build.VERSION.SDK_INT < 30) return;
    root.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          Insets bars =
              insets.getInsets(
                  WindowInsets.Type.systemBars()
                      | WindowInsets.Type.displayCutout()
                      | WindowInsets.Type.ime());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return WindowInsets.CONSUMED;
        });
    root.requestApplyInsets();
  }
}
