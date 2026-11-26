package dev.ubox.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toolbar;

/** 第三方开源许可列表，点击条目弹窗查看许可全文。 */
public class LicensesActivity extends Activity {
  /** 第三方组件：显示名、许可、主页、assets 里的许可全文（null = 无全文，仅展示来源）。 */
  static final String[][] THIRD_PARTY = {
    {"proot", "GPL-2.0", "https://github.com/proot-me/proot", "licenses/proot-COPYING.txt"},
    {"Termux terminal-view / terminal-emulator", "Apache-2.0",
        "https://github.com/termux/termux-app", "licenses/Termux-terminal-Apache-2.0.txt"},
    {"DSHA (DSH-APP)", "MIT", "https://github.com/DSH-APP/DSHA", "licenses/DSHA-LICENSE.txt"},
    {"Apache Commons Compress", "Apache-2.0",
        "https://commons.apache.org/proper/commons-compress/", null},
    {"ubuntu-box (biyan113)", "MIT", "https://github.com/biyan113/ubuntu-box", null},
  };

  private float density;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    density = getResources().getDisplayMetrics().density;

    LinearLayout list = new LinearLayout(this);
    list.setOrientation(LinearLayout.VERTICAL);
    list.setPadding(dp(16), dp(8), dp(16), dp(24));

    for (int i = 0; i < THIRD_PARTY.length; i++) {
      LinearLayout componentRow = row(THIRD_PARTY[i][0]);
      subtitle(componentRow).setText(THIRD_PARTY[i][1] + " · " + THIRD_PARTY[i][2]);
      componentRow.setOnClickListener(v -> showLicenseDetail(THIRD_PARTY[i]));
      list.addView(componentRow);
    }

    ScrollView scroll = new ScrollView(this);
    scroll.addView(list);

    Toolbar toolbar = new Toolbar(this);
    toolbar.setBackgroundColor(0xFF1E1E1E);
    toolbar.setTitleTextColor(Color.WHITE);
    toolbar.setMinimumHeight(dp(48));

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF121212);
    root.addView(
        toolbar,
        new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    setContentView(root);
    setActionBar(toolbar);
    setTitle("第三方开源许可");
    getActionBar().setDisplayHomeAsUpEnabled(true);
    EdgeToEdge.padForSystemBars(root);
  }

  @Override
  public boolean onNavigateUp() {
    finish();
    return true;
  }

  /** 弹出单个组件的许可详情：许可 + 主页 + 完整协议全文（若有）。 */
  private void showLicenseDetail(String[] component) {
    StringBuilder body = new StringBuilder();
    body.append("许可：").append(component[1]).append("\n主页：").append(component[2]).append("\n\n");
    String assetPath = component[3];
    if (assetPath != null) {
      body.append("──────── 许可协议全文 ────────\n\n");
      try (java.io.BufferedReader reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(getAssets().open(assetPath)))) {
        String line;
        while ((line = reader.readLine()) != null) body.append(line).append('\n');
      } catch (Exception error) {
        body.append("（许可文件读取失败：").append(error.getMessage()).append("）\n");
      }
    } else {
      body.append("该组件的完整许可协议可在其主页查看。\n");
    }

    ScrollView scroll = new ScrollView(this);
    TextView view = text(body.toString(), 13, 0xFFDDDDDD);
    view.setTextIsSelectable(true);
    view.setPadding(dp(16), dp(12), dp(16), dp(12));
    scroll.addView(view);

    new AlertDialog.Builder(this)
        .setTitle(component[0])
        .setView(scroll)
        .setPositiveButton("关闭", null)
        .show();
  }

  // ---------------------------------------------------------------- 视图

  private LinearLayout row(String title) {
    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(10), 0, dp(10));
    TypedValue ripple = new TypedValue();
    getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    row.setBackgroundResource(ripple.resourceId);

    LinearLayout texts = new LinearLayout(this);
    texts.setOrientation(LinearLayout.VERTICAL);
    texts.addView(text(title, 16, Color.WHITE));
    texts.addView(text("", 13, 0xFF9E9E9E));
    row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    return row;
  }

  private TextView subtitle(LinearLayout row) {
    return (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
  }

  private TextView text(String value, int sp, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    view.setTypeface(Typeface.DEFAULT);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * density);
  }
}
