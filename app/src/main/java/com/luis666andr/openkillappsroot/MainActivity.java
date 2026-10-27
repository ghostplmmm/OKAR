package com.luis666andr.openkillappsroot;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // Paquetes que nunca se cierran (romperían el sistema o esta app)
    private static final String[] PROTECTED = {
            "android", "com.android.systemui", "com.topjohnwu.magisk",
            "com.android.settings", "com.android.phone", "com.android.launcher",
            "com.android.launcher3", "com.google.android.tvlauncher",
            "com.android.inputmethod.latin", "com.android.providers.media",
            "com.android.bluetooth", "com.android.nfc"
    };

    /** ScrollView que no crece más de un tope: en vertical deja siempre espacio para la lista. */
    private static class CappedScrollView extends ScrollView {
        private final int max;
        CappedScrollView(Context c, int max) { super(c); this.max = max; }
        @Override
        protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(h);
            int cap = size > 0 ? Math.min(size, max) : max;
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    private final List<String> pkgs = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView status, result;
    private Button[] btns = new Button[0];
    private ScrollView sv;
    private boolean land = false, inList = false;
    private int cur = 0, listPos = 0;   // cursor propio (no depende del foco de Android)
    private String lastResult = "";
    private Set<String> whitelist = new HashSet<>();
    private boolean viewWhite = false;
    private Button btnWhite, btnViewWhite;
    private Button btnKill;
    private Button btnSystem, btnRunning, btnAggr, btnRestore;
    // El resaltado del mando solo se ve cuando se usa mando/teclado; al tocar la pantalla se oculta
    private boolean cursorOn = false;
    private boolean aggressive = true;

    private boolean showSystem = false;
    private boolean onlyRunning = true;
    private boolean rootOk = false;
    private SharedPreferences sp;

    // Joystick izquierdo -> flechas del dpad con repetición
    private final Handler handler = new Handler();
    private int dirX = 0, dirY = 0;
    private final Runnable repeater = new Runnable() {
        @Override
        public void run() {
            if (dirX == 0 && dirY == 0) return;
            stepFocus();
            handler.postDelayed(this, 160);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("killroot", MODE_PRIVATE);
        showSystem = sp.getBoolean("showSystem", false);
        onlyRunning = sp.getBoolean("onlyRunning", true);
        aggressive = sp.getBoolean("aggressive", true);
        whitelist = new HashSet<>(sp.getStringSet("whitelist", new HashSet<String>()));
        buildUi();
        checkRootThenLoad();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();
        refreshListViews();
    }

    // ---------------------------------------------------------------- Salir

    /** Cierra la actividad y quita la tarea de "recientes": no queda nada en segundo plano. */
    private void exitApp() {
        handler.removeCallbacks(repeater);
        dirX = 0;
        dirY = 0;
        finishAndRemoveTask();
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(repeater);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        land = dm.widthPixels > dm.heightPixels;
        int pad = land ? dp(12) : dp(8);

        // En vertical se oculta la barra superior (el título ya sale en la pantalla): más sitio para la lista
        ActionBar ab = getActionBar();
        if (ab != null) { if (land) ab.show(); else ab.hide(); }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(pad, pad, pad, pad);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, land ? 22 : 20);
        panel.addView(title);

        TextView credit = new TextView(this);
        credit.setText(getString(R.string.created_by, getString(R.string.author)));
        credit.setTextColor(Color.parseColor("#26C6DA"));
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        panel.addView(credit);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#B0B0B0"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setLines(2);   // altura fija: el texto cambia pero los botones no se mueven
        status.setText(rootOk ? getString(R.string.root_ok) : getString(R.string.checking_root));
        panel.addView(status);

        result = new TextView(this);
        result.setTextColor(Color.parseColor("#7CE0A0"));
        result.setTextSize(TypedValue.COMPLEX_UNIT_SP, land ? 14 : 12);
        result.setPadding(0, 0, 0, dp(4));
        result.setLines(3);   // altura fija (3 líneas) aunque no haya texto
        result.setEllipsize(android.text.TextUtils.TruncateAt.END);
        result.setText(lastResult);
        panel.addView(result);

        // División fija entre la zona de texto y los botones
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor("#3A3A3A"));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dl.setMargins(0, dp(2), 0, dp(8));
        panel.addView(div, dl);

        Button kill = btn(getString(R.string.btn_kill), new View.OnClickListener() {
            public void onClick(View v) { killSelected(); }
        });
        Button selAll = btn(getString(R.string.btn_select_all), new View.OnClickListener() {
            public void onClick(View v) {
                for (int i = 0; i < pkgs.size(); i++) list.setItemChecked(i, true);
            }
        });
        Button refresh = btn(getString(R.string.btn_refresh), new View.OnClickListener() {
            public void onClick(View v) {
                if (!rootOk) checkRootThenLoad(); else loadApps();
            }
        });
        btnSystem = btn("", new View.OnClickListener() {
            public void onClick(View v) { showSystem = !showSystem; sp.edit().putBoolean("showSystem", showSystem).apply(); updateToggleLabels(); loadApps(); }
        });
        btnRunning = btn("", new View.OnClickListener() {
            public void onClick(View v) { onlyRunning = !onlyRunning; sp.edit().putBoolean("onlyRunning", onlyRunning).apply(); updateToggleLabels(); loadApps(); }
        });
        btnWhite = btn("", new View.OnClickListener() {
            public void onClick(View v) { whiteAction(); }
        });
        btnViewWhite = btn("", new View.OnClickListener() {
            public void onClick(View v) { viewWhite = !viewWhite; updateToggleLabels(); loadApps(); }
        });
        btnAggr = btn("", new View.OnClickListener() {
            public void onClick(View v) { aggressive = !aggressive; sp.edit().putBoolean("aggressive", aggressive).apply(); updateToggleLabels(); }
        });
        btnRestore = btn(getString(R.string.btn_restore), new View.OnClickListener() {
            public void onClick(View v) { restoreSelected(); }
        });
        updateToggleLabels();
        btnKill = kill;

        Button[] all = {kill, selAll, btnWhite, btnViewWhite, btnSystem, btnRunning, btnAggr, btnRestore, refresh};
        btns = all;
        if (land) {
            for (Button x : all) panel.addView(x, rowParams());
        } else {
            for (int i = 0; i < all.length; i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(all[i], cellParams());
                if (i + 1 < all.length)
                    row.addView(all[i + 1], cellParams());
                panel.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setFocusable(false);
        list.setSelector(new ColorDrawable(Color.parseColor("#553D8BFF")));
        list.setDivider(new ColorDrawable(Color.parseColor("#222222")));
        list.setDividerHeight(1);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_multiple_choice, labels) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                CheckedTextView t = (CheckedTextView) v;
                t.setTextColor(Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                String raw = getItem(pos);
                int nl = raw == null ? -1 : raw.indexOf('\n');
                if (nl > 0) {   // nombre en blanco, paquete más pequeño y en gris
                    SpannableString ss = new SpannableString(raw);
                    ss.setSpan(new ForegroundColorSpan(Color.parseColor("#9E9E9E")), nl + 1, raw.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ss.setSpan(new RelativeSizeSpan(0.78f), nl + 1, raw.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    t.setText(ss);
                }
                t.setMinHeight(dp(48));
                t.setBackgroundColor((cursorOn && inList && pos == listPos) ? 0x883D8BFF : Color.TRANSPARENT);
                return v;
            }
        };
        list.setAdapter(adapter);

        sv = land ? new ScrollView(this) : new CappedScrollView(this, (int) (dm.heightPixels * 0.55f));
        sv.setFillViewport(land);
        sv.setFocusable(false);
        sv.addView(panel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (land) {
            root.addView(sv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2);
            lp.setMarginStart(pad);
            root.addView(list, lp);
        } else {
            // Vertical: el panel ocupa lo que necesita (con tope) y la lista se queda con todo el resto
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            lp.topMargin = pad;
            root.addView(list, lp);
        }

        setContentView(root);
        inList = false;
        cur = 0;
        applyCursor();
    }

    /** Celda de la rejilla vertical: mismo ancho, misma altura que su vecina y separación entre botones. */
    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return lp;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, land ? 15 : 13);
        b.setMinHeight(dp(land ? 44 : 42));
        b.setMinimumHeight(dp(land ? 44 : 42));
        if (!land) { b.setMaxLines(2); b.setPadding(dp(4), dp(4), dp(4), dp(4)); }
        b.setFocusable(false);
        b.setBackground(buttonBg());
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private StateListDrawable buttonBg() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_selected}, rect("#3D8BFF", "#FFFFFF"));
        s.addState(new int[]{android.R.attr.state_focused}, rect("#3D8BFF", "#FFFFFF"));
        s.addState(new int[]{android.R.attr.state_pressed}, rect("#2A6AD0", "#FFFFFF"));
        s.addState(new int[]{}, rect("#2B2B2B", "#444444"));
        return s;
    }

    private GradientDrawable rect(String fill, String stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(8));
        g.setStroke(dp(2), Color.parseColor(stroke));
        return g;
    }

    private String yn(boolean v) {
        return getString(v ? R.string.yes : R.string.no);
    }

    private void updateToggleLabels() {
        if (btnSystem != null) btnSystem.setText(getString(R.string.toggle_system, yn(showSystem)));
        if (btnRunning != null) btnRunning.setText(getString(R.string.toggle_running, yn(onlyRunning)));
        if (btnAggr != null) btnAggr.setText(getString(R.string.toggle_aggr, yn(aggressive)));
        if (btnWhite != null) btnWhite.setText(viewWhite ? R.string.btn_white_remove : R.string.btn_white_add);
        if (btnViewWhite != null) btnViewWhite.setText(getString(R.string.toggle_white, yn(viewWhite), whitelist.size()));
    }

    private void refreshListViews() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (status != null) {
            long[] m = mem();
            String ram = m[0] > 0 ? "\n" + getString(R.string.ram_free, (int) Math.round(m[1] * 100.0 / m[0])) : "";
            status.setText((rootOk ? getString(R.string.status_root_apps, pkgs.size()) : getString(R.string.no_root)) + ram);
        }
        if (result != null) result.setText(lastResult);
    }

    // ---------------------------------------------------------------- Mando

    /** Al tocar / usar ratón se apaga el resaltado del mando (antes se quedaba pegado en el primer botón). */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && cursorOn) {
            cursorOn = false;
            handler.removeCallbacks(repeater);
            dirX = 0;
            dirY = 0;
            applyCursor();
        }
        return super.dispatchTouchEvent(ev);
    }

    // El mando se maneja con un cursor propio: no depende del foco de Android
    // (que no funciona mientras la pantalla esté en "modo táctil").

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BUTTON_B) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) exitApp();
            return true;
        }
        boolean arrow = isArrow(k);
        boolean ok = k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_DPAD_CENTER
                || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (arrow || ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (arrow) {
                    moveCursor(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0),
                            k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 0));
                } else if (e.getRepeatCount() == 0) {
                    activate();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean isArrow(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    // Muchos mandos envían el dpad como eje HAT y el stick como AXIS_X/Y:
    // se tratan los dos como flechas.
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        int src = ev.getSource();
        boolean pad = (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        if (pad && ev.getAction() == MotionEvent.ACTION_MOVE) {
            float x = ev.getAxisValue(MotionEvent.AXIS_X);
            float y = ev.getAxisValue(MotionEvent.AXIS_Y);
            float hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int nx = (x > 0.6f || hx > 0.5f) ? 1 : ((x < -0.6f || hx < -0.5f) ? -1 : 0);
            int ny = (y > 0.6f || hy > 0.5f) ? 1 : ((y < -0.6f || hy < -0.5f) ? -1 : 0);
            if (nx != 0 && ny != 0) {          // en diagonal, gana el eje dominante
                if (Math.abs(x) > Math.abs(y)) ny = 0; else nx = 0;
            }
            if (nx != dirX || ny != dirY) {
                dirX = nx;
                dirY = ny;
                handler.removeCallbacks(repeater);
                if (dirX != 0 || dirY != 0) {
                    stepFocus();
                    handler.postDelayed(repeater, 350);
                }
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    private void stepFocus() {
        moveCursor(dirX, dirY);
    }

    /** Pulsar A: pulsa el botón resaltado o marca/desmarca la app resaltada. */
    private void activate() {
        if (!cursorOn) { cursorOn = true; applyCursor(); return; }   // 1ª pulsación: solo muestra el cursor
        if (!inList) {
            if (cur >= 0 && cur < btns.length) btns[cur].performClick();
        } else if (listPos >= 0 && listPos < pkgs.size()) {
            list.setItemChecked(listPos, !list.isItemChecked(listPos));
        }
    }

    private void enterList() {
        if (pkgs.isEmpty()) return;
        inList = true;
        listPos = Math.max(0, Math.min(listPos, pkgs.size() - 1));
        applyCursor();
    }

    private void moveCursor(int dx, int dy) {
        if (!cursorOn) { cursorOn = true; applyCursor(); return; }
        int n = btns.length;
        if (!inList) {
            if (land) {
                if (dx > 0) { enterList(); return; }
                if (dy != 0) cur = Math.max(0, Math.min(n - 1, cur + dy));
            } else {
                int row = cur / 2, col = cur % 2, rows = (n + 1) / 2;
                if (dx != 0) {
                    int c = cur + dx;
                    if (c >= 0 && c < n && c / 2 == row) cur = c;
                }
                if (dy != 0) {
                    int r = row + dy;
                    if (r >= rows) { enterList(); return; }
                    if (r >= 0) cur = Math.min(r * 2 + col, n - 1);
                }
            }
        } else {
            if (land && dx < 0) {
                inList = false;
            } else if (dy != 0) {
                int p = listPos + dy;
                if (p < 0) {
                    if (!land) { inList = false; cur = n - 1; }
                } else if (p < pkgs.size()) {
                    listPos = p;
                }
            }
        }
        applyCursor();
    }

    /** Dibuja el resaltado: botón seleccionado o fila de la lista. */
    private void applyCursor() {
        for (int i = 0; i < btns.length; i++) btns[i].setSelected(cursorOn && !inList && i == cur);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (!cursorOn) return;
        if (!inList && cur >= 0 && cur < btns.length) {
            Button b = btns[cur];
            boolean firstRow = land ? cur == 0 : cur < 2;
            if (firstRow && sv != null) {
                // Primera fila: subir al tope para que se vea el título y el texto de RAM completos
                sv.post(new Runnable() {
                    public void run() { sv.smoothScrollTo(0, 0); }
                });
            } else {
                b.requestRectangleOnScreen(new Rect(0, 0, b.getWidth(), b.getHeight()));
            }
        } else if (inList && list != null && listPos < pkgs.size()) {
            list.smoothScrollToPosition(listPos);
        }
    }

    // ---------------------------------------------------------------- Root

    private String su(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void checkRootThenLoad() {
        new Thread(new Runnable() {
            public void run() {
                String out = su("id");
                rootOk = out != null && out.contains("uid=0");
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (!rootOk) {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_root_denied), Toast.LENGTH_LONG).show();
                        }
                        loadApps();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- Apps

    private Set<String> protectedSet() {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, PROTECTED);
        s.add(getPackageName());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
            s.add(ri.activityInfo.packageName);
        }
        // Teclado activo: nunca se cierra
        try {
            String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName cn = ime == null ? null : ComponentName.unflattenFromString(ime);
            if (cn != null) s.add(cn.getPackageName());
        } catch (Exception ignored) { }
        return s;
    }

    private Set<String> runningPackages() {
        Set<String> run = new HashSet<>();
        String out = su("ps -A -o NAME 2>/dev/null || ps");
        if (out == null) return run;
        for (String line : out.split("\n")) {
            String n = line.trim();
            if (n.isEmpty()) continue;
            String[] parts = n.split("\\s+");
            n = parts[parts.length - 1];
            int c = n.indexOf(':');
            if (c > 0) n = n.substring(0, c);
            run.add(n);
        }
        return run;
    }

    /** Devuelve pares {paquete, etiqueta}. Debe llamarse fuera del hilo de UI. */
    private List<String[]> collect(boolean includeSystem, boolean onlyRun) {
        PackageManager pm = getPackageManager();
        Set<String> prot = protectedSet();
        Set<String> running = (onlyRun && rootOk && !viewWhite) ? runningPackages() : null;
        List<String[]> res = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (prot.contains(ai.packageName)) continue;
            boolean white = whitelist.contains(ai.packageName);
            if (viewWhite != white) continue;   // lista blanca: solo se ve en su propia vista
            if (!viewWhite) {
                boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (sys && !includeSystem) continue;
                if (running != null && !running.contains(ai.packageName)) continue;
            }
            String label = String.valueOf(pm.getApplicationLabel(ai));
            res.add(new String[]{ai.packageName, label});
        }
        Collections.sort(res, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                return a[1].compareToIgnoreCase(b[1]);
            }
        });
        return res;
    }

    private void loadApps() {
        if (status != null) status.setText(R.string.loading);
        new Thread(new Runnable() {
            public void run() {
                final List<String[]> res = collect(showSystem, onlyRunning);
                runOnUiThread(new Runnable() {
                    public void run() {
                        pkgs.clear();
                        labels.clear();
                        for (String[] r : res) {
                            pkgs.add(r[0]);
                            labels.add(r[1] + "\n" + r[0]);
                        }
                        adapter.notifyDataSetChanged();
                        list.clearChoices();
                        refreshListViews();
                        if (listPos >= pkgs.size()) listPos = Math.max(0, pkgs.size() - 1);
                        if (pkgs.isEmpty()) inList = false;
                        applyCursor();
                    }
                });
            }
        }).start();
    }

    private List<String> selected() {
        List<String> sel = new ArrayList<>();
        for (int i = 0; i < pkgs.size(); i++) {
            if (list.isItemChecked(i)) sel.add(pkgs.get(i));
        }
        return sel;
    }

    private void whiteAction() {
        List<String> sel = selected();
        if (sel.isEmpty()) {
            Toast.makeText(this, R.string.toast_none_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        if (viewWhite) whitelist.removeAll(sel); else { whitelist.addAll(sel); restoreBg(sel); }
        sp.edit().putStringSet("whitelist", new HashSet<String>(whitelist)).apply();
        Toast.makeText(this, getString(viewWhite ? R.string.toast_white_removed : R.string.toast_white_added, sel.size()),
                Toast.LENGTH_SHORT).show();
        updateToggleLabels();
        loadApps();
    }

    private void killSelected() {
        if (viewWhite) {
            Toast.makeText(this, R.string.toast_leave_white, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> sel = selected();
        if (sel.isEmpty()) {
            Toast.makeText(this, R.string.toast_none_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        killPackages(sel);
    }

    /** {total, disponible} en kB, leído de /proc/meminfo */
    private long[] mem() {
        long total = 0, avail = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = kb(line);
                else if (line.startsWith("MemAvailable:")) avail = kb(line);
            }
        } catch (Exception ignored) { }
        return new long[]{total, avail};
    }

    private long kb(String line) {
        return Long.parseLong(line.trim().split("\\s+")[1]);
    }

    // ---------------------------------------------------------------- Cierre agresivo

    private boolean isSystemPkg(String p) {
        try {
            return (getPackageManager().getApplicationInfo(p, 0).flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return true; }
    }

    /** PIDs de todos los procesos de cada paquete (incluye los ":servicio"). */
    private Map<String, List<String>> pidsOf(Collection<String> pk) {
        Map<String, List<String>> m = new HashMap<>();
        String out = su("ps -A -o PID,NAME 2>/dev/null || ps");
        if (out == null) return m;
        for (String line : out.split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 2) continue;
            String pid = t[0].matches("\\d+") ? t[0] : t[1];
            if (!pid.matches("\\d+")) continue;
            String name = t[t.length - 1];
            int c = name.indexOf(':');
            if (c > 0) name = name.substring(0, c);
            if (pk.contains(name)) {
                List<String> l = m.get(name);
                if (l == null) { l = new ArrayList<>(); m.put(name, l); }
                l.add(pid);
            }
        }
        return m;
    }

    /** Impide que el sistema u otras apps las vuelvan a despertar en segundo plano. */
    private void blockBg(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String p : l) {
            if (isSystemPkg(p)) continue;   // las del sistema solo se matan: bloquearlas puede romper el equipo
            for (String op : new String[]{"RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"}) {
                sb.append("(cmd appops set ").append(p).append(' ').append(op).append(" ignore || appops set ")
                  .append(p).append(' ').append(op).append(" ignore) >/dev/null 2>&1; ");
            }
            sb.append("am set-inactive ").append(p).append(" true >/dev/null 2>&1; ");
            sb.append("am set-standby-bucket ").append(p).append(" restricted >/dev/null 2>&1; ");
            sb.append("cmd deviceidle whitelist -").append(p).append(" >/dev/null 2>&1; ");
        }
        su(sb.toString());
    }

    /** Devuelve a la app sus permisos normales de segundo plano. */
    private void restoreBg(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String p : l) {
            for (String op : new String[]{"RUN_ANY_IN_BACKGROUND", "RUN_IN_BACKGROUND"}) {
                sb.append("(cmd appops set ").append(p).append(' ').append(op).append(" allow || appops set ")
                  .append(p).append(' ').append(op).append(" allow) >/dev/null 2>&1; ");
            }
            sb.append("am set-inactive ").append(p).append(" false >/dev/null 2>&1; ");
            sb.append("am set-standby-bucket ").append(p).append(" active >/dev/null 2>&1; ");
        }
        final String cmd = sb.toString();
        new Thread(new Runnable() { public void run() { if (rootOk) su(cmd); } }).start();
    }

    private void restoreSelected() {
        List<String> sel = selected();
        if (sel.isEmpty()) {
            Toast.makeText(this, R.string.toast_none_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        restoreBg(sel);
        Toast.makeText(this, getString(R.string.toast_bg_restored, sel.size()), Toast.LENGTH_SHORT).show();
    }

    /** Mata procesos: force-stop + am kill + kill -9 a cada PID. */
    private void killRound(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String p : l) sb.append("am force-stop ").append(p).append("; am kill ").append(p).append("; ");
        su(sb.toString());
        Map<String, List<String>> m = pidsOf(l);
        StringBuilder k = new StringBuilder();
        for (List<String> pl : m.values()) for (String pid : pl) k.append("kill -9 ").append(pid).append("; ");
        if (k.length() > 0) su(k.toString());
    }

    /** IDs de tareas de "Recientes" que pertenecen a los paquetes indicados. */
    private Map<String, String> recentTasks(Collection<String> pk) {
        Map<String, String> res = new HashMap<>();   // taskId -> paquete
        String out = su("dumpsys activity recents");
        if (out == null) return res;
        Pattern idp = Pattern.compile("Task(?:Record)?\\{\\S+ #(\\d+)");
        for (String block : out.split("Recent #")) {
            Matcher m = idp.matcher(block);
            if (!m.find()) continue;
            for (String p : pk) {
                if (block.contains(":" + p + " ") || block.contains("=" + p + "/")
                        || block.contains(" " + p + "/") || block.contains("{" + p + "/")) {
                    res.put(m.group(1), p);
                    break;
                }
            }
        }
        return res;
    }

    /** Quita las apps de "Recientes" (si no, al tocarlas desde ahí vuelven a abrirse). Devuelve {quitadas, restantes}. */
    private int[] removeRecents(List<String> l) {
        Map<String, String> tareas = recentTasks(l);
        if (tareas.isEmpty()) return new int[]{0, 0};
        StringBuilder sb = new StringBuilder();
        for (String id : tareas.keySet()) {
            sb.append("am task remove ").append(id).append(" >/dev/null 2>&1 || ")
              .append("cmd activity task remove ").append(id).append(" >/dev/null 2>&1 || ")
              .append("am stack remove ").append(id).append(" >/dev/null 2>&1; ");
        }
        su(sb.toString());
        int quedan = recentTasks(l).size();
        return new int[]{tareas.size() - quedan, quedan};
    }

    private void killPackages(final List<String> l) {
        new Thread(new Runnable() {
            public void run() {
                if (!rootOk) {
                    toast(getString(R.string.toast_no_root_kill));
                    return;
                }
                long[] antes = mem();
                // 1) bloquear que despierten solas (modo agresivo)
                if (aggressive) blockBg(l);
                // 2) matar en 3 rondas (force-stop + am kill + kill -9 a cada proceso)
                for (int i = 0; i < 3; i++) {
                    killRound(l);
                    try { Thread.sleep(i == 0 ? 500 : 350); } catch (InterruptedException ignored) { }
                }
                // 3) quitar de Recientes
                int[] rec = removeRecents(l);
                // 4) comprobar quién reapareció
                try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                long[] despues = mem();
                Set<String> run = runningPackages();
                List<String> back = new ArrayList<>();
                for (String p : l) if (run.contains(p)) back.add(p);
                int ok = l.size() - back.size();

                long libreKb = despues[1] - antes[1];
                String ram;
                if (libreKb > 0 && antes[0] > 0) {
                    ram = getString(R.string.res_ram, libreKb * 100.0 / antes[0], (int) (libreKb / 1024));
                } else {
                    ram = getString(R.string.res_ram_zero, 0);
                }
                String recTxt = rec[1] > 0 ? getString(R.string.res_recents_fail, rec[0], rec[1])
                        : getString(R.string.res_recents, rec[0]);
                String msg = (back.isEmpty() ? getString(R.string.res_closed, ok)
                        : getString(R.string.res_closed_restarted, ok, back.size()))
                        + "\n" + ram + "\n" + recTxt;
                lastResult = msg;
                StringBuilder t = new StringBuilder(msg);
                if (!back.isEmpty()) {
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < back.size() && i < 4; i++) names.append(i > 0 ? ", " : "").append(back.get(i));
                    t.append("\n").append(getString(R.string.res_alive, names.toString()));
                }
                toast(t.toString());
                runOnUiThread(new Runnable() {
                    public void run() { loadApps(); }
                });
            }
        }).start();
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show(); }
        });
    }
}
