package com.example.travianfarmassistant

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class FarmList(val id: String, val name: String)

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var farmContainer: LinearLayout
    private lateinit var farmStatus: TextView
    private lateinit var status: TextView
    private lateinit var lastRun: TextView
    private lateinit var nextRun: TextView
    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var nextAt = 0L
    private var intervalMs = 5 * 60_000L
    private var loginInProgress = false
    private var farmListRequested = false
    private var farmListUrlAttempt = 0
    private var pendingUsername = ""
    private var pendingPassword = ""
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scheduler = object : Runnable {
        override fun run() {
            if (!running) return
            executeSelectedFarmLists()
            nextAt = System.currentTimeMillis() + intervalMs
            updateNextRun()
            handler.postDelayed(this, intervalMs)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverInput = findViewById(R.id.server)
        usernameInput = findViewById(R.id.username)
        passwordInput = findViewById(R.id.password)
        farmContainer = findViewById(R.id.farmListContainer)
        farmStatus = findViewById(R.id.farmListStatus)
        status = findViewById(R.id.status)
        lastRun = findViewById(R.id.lastRun)
        nextRun = findViewById(R.id.nextRun)
        webView = findViewById(R.id.webView)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        serverInput.setText(prefs.getString("server", "https://ts20.x2.europe.travian.com"))
        usernameInput.setText(prefs.getString("username", ""))

        val interval = findViewById<Spinner>(R.id.interval)
        interval.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("5 menit", "10 menit", "15 menit", "30 menit", "60 menit")
        )

        val duration = findViewById<Spinner>(R.id.duration)
        duration.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("1 jam", "6 jam", "12 jam", "24 jam")
        )

        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "Chrome/120.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.addJavascriptInterface(FarmBridge(), "AndroidFarm")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == null) return

                val lower = url.lowercase(Locale.US)

                // ConsentManager yang dipakai situs dapat berada di Shadow DOM.
                // Jangan lanjut login / parsing Farm List sampai layer consent benar-benar hilang.
                handlePageAfterConsent(url, lower, 0)
            }
        }

        findViewById<Button>(R.id.loginTravian).setOnClickListener {
            startAutomaticLogin()
        }

        findViewById<Button>(R.id.openFarm).setOnClickListener {
            openFarmList()
        }

        findViewById<Button>(R.id.start).setOnClickListener {
            saveSelectedFarmLists()
            intervalMs = when (interval.selectedItemPosition) {
                0 -> 5 * 60_000L
                1 -> 10 * 60_000L
                2 -> 15 * 60_000L
                3 -> 30 * 60_000L
                else -> 60 * 60_000L
            }
            running = true
            status.text = "Status: RUNNING"
            executeSelectedFarmLists()
            nextAt = System.currentTimeMillis() + intervalMs
            updateNextRun()
            handler.removeCallbacks(scheduler)
            handler.postDelayed(scheduler, intervalMs)
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            stopScheduler()
        }

        createNotificationChannel()
    }

    /**
     * Login sekarang benar-benar otomatis:
     * 1. Ambil username/password dari form.
     * 2. Buka server.
     * 3. Jika session masih aktif -> langsung Farm List.
     * 4. Jika belum login -> cari form login dan submit dari WebView.
     * 5. Setelah redirect sukses -> otomatis membuka Farm List.
     *
     * Password hanya disimpan di RAM selama proses login dan tidak ditulis
     * ke SharedPreferences.
     */
    private fun startAutomaticLogin() {
        val server = normalizeServer(serverInput.text.toString())
        pendingUsername = usernameInput.text.toString().trim()
        pendingPassword = passwordInput.text.toString()

        if (pendingUsername.isBlank() || pendingPassword.isBlank()) {
            farmStatus.text = "Username dan password harus diisi."
            return
        }

        getSharedPreferences("config", MODE_PRIVATE)
            .edit()
            .putString("server", server)
            .putString("username", pendingUsername)
            .apply()

        loginInProgress = true
        farmListRequested = false
        farmStatus.text = "Menghubungkan ke Travian..."

        webView.loadUrl(server)
    }

    private fun autoLoginIfNeeded() {
        if (!loginInProgress) return

        val usernameJson = JSONObject.quote(pendingUsername)
        val passwordJson = JSONObject.quote(pendingPassword)

        val js = """
            (() => {
                const username = $usernameJson;
                const password = $passwordJson;

                const visible = el => {
                    if (!el) return false;
                    const s = getComputedStyle(el);
                    return s.display !== 'none' && s.visibility !== 'hidden' && el.offsetParent !== null;
                };

                const inputs = [...document.querySelectorAll('input')].filter(visible);
                const passwordInput = inputs.find(x =>
                    (x.type || '').toLowerCase() === 'password' ||
                    /pass|password/i.test(x.name || '') ||
                    /pass|password/i.test(x.id || '')
                );

                if (!passwordInput) {
                    AndroidFarm.onLoginResult('no_login_form');
                    return;
                }

                const userInput = inputs.find(x =>
                    /user|username|email|login|name/i.test(x.name || '') ||
                    /user|username|email|login|name/i.test(x.id || '') ||
                    (x.type || '').toLowerCase() === 'email'
                );

                if (!userInput) {
                    AndroidFarm.onLoginResult('no_username_field');
                    return;
                }

                const setValue = (el, value) => {
                    const setter = Object.getOwnPropertyDescriptor(
                        Object.getPrototypeOf(el), 'value'
                    )?.set;
                    if (setter) setter.call(el, value); else el.value = value;
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                };

                setValue(userInput, username);
                setValue(passwordInput, password);

                const form = passwordInput.closest('form') || userInput.closest('form');
                if (!form) {
                    AndroidFarm.onLoginResult('no_form');
                    return;
                }

                const buttons = [...form.querySelectorAll('button,input[type=submit],input[type=button],a')]
                    .filter(visible);

                const submitButton = buttons.find(x =>
                    /login|log in|sign in|anmelden|connexion|entrar|acceder/i.test(
                        (x.innerText || x.value || x.title || '').trim()
                    )
                );

                AndroidFarm.onLoginResult('submitting');

                if (submitButton) {
                    submitButton.click();
                } else if (typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                } else {
                    form.submit();
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    private fun openFarmList() {
        val server = normalizeServer(serverInput.text.toString())
        farmStatus.text = "Membuka halaman Farm List akun..."
        farmListRequested = true
        farmListUrlAttempt = 0
        // Pada versi Travian yang sekarang, Farm List berada di Rally Point
        // dan URL yang paling konsisten adalah id=39&gid=16&tt=99.
        // Versi lama hanya memakai gid=16&tt=99, jadi kita punya fallback.
        webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
    }

    private fun loadFarmListFallback() {
        val server = normalizeServer(serverInput.text.toString())
        farmListUrlAttempt++
        when (farmListUrlAttempt) {
            1 -> webView.loadUrl("$server/build.php?gid=16&tt=99")
            2 -> webView.loadUrl("$server/build.php?id=39&tt=99")
            else -> farmStatus.text = "Halaman Farm List sudah dibuka, tetapi struktur daftar tidak terbaca."
        }
    }

    /**
     * ConsentManager (consentmanager.net) sering memasang UI di #cmpwrapper.shadowRoot.
     * querySelector biasa dari document tidak akan melihat tombol di dalam Shadow DOM.
     * Karena itu kita cari di document + semua open shadowRoot dan baru lanjut setelah
     * banner tidak terlihat lagi.
     */
    private fun handlePageAfterConsent(url: String, lower: String, attempt: Int) {
        acceptCookiesIfPresent { result ->
            val consentStillVisible = result.contains("visible") || result.contains("clicked")

            if (consentStillVisible && attempt < 8) {
                farmStatus.text = if (result.contains("clicked")) {
                    "Cookie consent ditemukan. Menerima cookies..."
                } else {
                    "Menunggu cookie consent ditutup..."
                }
                CookieManager.getInstance().flush()
                handler.postDelayed({ handlePageAfterConsent(url, lower, attempt + 1) }, 700)
                return@acceptCookiesIfPresent
            }

            if (lower.contains("gid=16") && lower.contains("tt=99")) {
                loginInProgress = false
                farmListRequested = true
                farmStatus.text = "Login berhasil. Menunggu Farm List dimuat..."
                handler.postDelayed({ extractFarmLists(0) }, 1400)
                return@acceptCookiesIfPresent
            }

            if (loginInProgress) {
                handler.postDelayed({ autoLoginIfNeeded() }, 500)
            }
        }
    }

    /** Klik Accept All, termasuk jika tombol berada di Shadow DOM ConsentManager. */
    private fun acceptCookiesIfPresent(done: (String) -> Unit) {
        val js = """
            (() => {
              const visible = el => {
                if (!el) return false;
                const s = getComputedStyle(el);
                const r = el.getBoundingClientRect();
                return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();

              // Ambil semua root yang dapat diakses: document + open Shadow DOM bertingkat.
              const roots = [document];
              for (let i = 0; i < roots.length; i++) {
                const root = roots[i];
                let els = [];
                try { els = [...root.querySelectorAll('*')]; } catch (_) {}
                for (const el of els) {
                  if (el.shadowRoot && !roots.includes(el.shadowRoot)) roots.push(el.shadowRoot);
                }
              }

              // ConsentManager dikenal memakai #cmpwrapper dengan shadowRoot dan
              // #cmpwelcomebtnyes / .cmpboxbtnyes untuk tombol opt-in.
              const selectors = [
                '#cmpwelcomebtnyes a',
                '#cmpwelcomebtnyes',
                '.cmpboxbtnyes',
                '#cmpbntyestxt',
                '[class*="cmpboxbtnyes"]',
                '[id*="cmpwelcomebtnyes"]'
              ];

              let bannerVisible = false;
              for (const root of roots) {
                try {
                  const box = root.querySelector('#cmpbox, #cmpbox2, .cmpbox, .cmpmore');
                  if (box && visible(box)) bannerVisible = true;
                } catch (_) {}

                for (const sel of selectors) {
                  let el = null;
                  try { el = root.querySelector(sel); } catch (_) {}
                  if (el && visible(el)) {
                    try {
                      el.click();
                      return 'clicked';
                    } catch (_) {}
                  }
                }

                // Fallback berbasis teks untuk varian markup lain.
                let candidates = [];
                try {
                  candidates = [...root.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button]')];
                } catch (_) {}
                const accept = candidates.find(el => {
                  if (!visible(el)) return false;
                  const text = norm(el.innerText || el.textContent || el.value || el.title || el.getAttribute('aria-label'));
                  return /^(accept all|accept all cookies|allow all|agree all|alle akzeptieren|tout accepter|aceptar todo)$/.test(text);
                });
                if (accept) {
                  try {
                    accept.click();
                    return 'clicked';
                  } catch (_) {}
                }
              }

              // #cmpwrapper sendiri mungkin host Shadow DOM, jadi kehadirannya juga dicek.
              const host = document.querySelector('#cmpwrapper');
              if (host && visible(host)) bannerVisible = true;

              return bannerVisible ? 'visible' : 'absent';
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { raw ->
            val result = raw.orEmpty().trim('"').lowercase(Locale.US)
            done(result)
        }
    }

    private fun extractFarmLists(attempt: Int) {
        val js = """
            (() => {
              const clean = s => (s || '').replace(/\s+/g, ' ').trim();
              const text = el => clean(el && (el.innerText || el.textContent || ''));
              const visible = el => {
                if (!el) return false;
                const r = el.getBoundingClientRect();
                const st = getComputedStyle(el);
                return st.display !== 'none' && st.visibility !== 'hidden' && r.width > 0 && r.height > 0;
              };
              const out = [];
              const seen = new Set();

              const add = (id, name, readyCount) => {
                id = clean(id);
                name = clean(name);
                if (!name) return;
                name = name.split('\n')[0].trim();
                if (/^(farm list|create new farm list|start all|send all|edit|delete|settings)$/i.test(name)) return;
                const key = (id || '') + '|' + name;
                if (seen.has(key)) return;
                seen.add(key);
                out.push({ id: id || '', name, readyCount: readyCount || 0 });
              };

              const root = document.querySelector('#rallyPointFarmList');
              let wrappers = root ? [...root.querySelectorAll('.farmListWrapper')] : [];

              // TravianBot-Ultra-compatible fallback: some releases expose the wrapper
              // without the expected parent being present immediately after navigation.
              if (!wrappers.length) {
                wrappers = [...document.querySelectorAll('#rallyPointFarmList .farmListWrapper, .farmListWrapper')];
              }

              // Expand collapsed lists and scroll them into view so React/lazy-rendered
              // farm targets are materialized in the WebView DOM.
              let expanded = 0;
              wrappers.forEach(wrapper => {
                try {
                  if (wrapper.classList.contains('collapsed')) {
                    const toggle = wrapper.querySelector('.farmListHeader .expandCollapse, .expandCollapse');
                    if (toggle && visible(toggle)) {
                      toggle.click();
                      expanded++;
                    }
                  }
                  wrapper.scrollIntoView({ block: 'center' });
                } catch (_) {}
              });

              wrappers.forEach(wrapper => {
                let id =
                  wrapper.querySelector('.dragAndDrop[data-list]')?.getAttribute('data-list') ||
                  wrapper.querySelector('[data-farm-list-id]')?.getAttribute('data-farm-list-id') ||
                  wrapper.getAttribute('data-list') ||
                  wrapper.getAttribute('data-farm-list-id') || '';

                let name =
                  wrapper.querySelector('.farmListName .name')?.textContent ||
                  wrapper.querySelector('.farmListName')?.textContent ||
                  wrapper.querySelector('[data-farm-list-name]')?.getAttribute('data-farm-list-name') || '';

                if (!name) {
                  const header = wrapper.querySelector('.farmListHeader');
                  if (header) {
                    const candidate = [...header.querySelectorAll('.name, a, button, span, strong')]
                      .map(text)
                      .find(t => t && t.length < 120 && !/start|send|edit|delete|setting|collapse|expand/i.test(t));
                    name = candidate || '';
                  }
                }

                if (!id) {
                  const drag = wrapper.querySelector('.dragAndDrop');
                  id = drag?.getAttribute('data-list') || '';
                }

                let readyCount = 0;
                const start = wrapper.querySelector('button.startFarmList, .startFarmList');
                if (start) {
                  const m = text(start).match(/(?:start|send)\s*\(?\s*(\d+)\s*\)?/i);
                  if (m) readyCount = parseInt(m[1], 10) || 0;
                }

                // Fallback status such as "22 / 37".
                if (!readyCount) {
                  const status = wrapper.querySelector('.farmListStatus');
                  const m = text(status).match(/(\d+)\s*\/);
                  if (m) readyCount = parseInt(m[1], 10) || 0;
                }

                add(id, name, readyCount);
              });

              // Legacy fallback kept for older Travian worlds/themes.
              if (!out.length) {
                const roots = [
                  ...document.querySelectorAll('#raidList'),
                  ...document.querySelectorAll('#raidList .raidList'),
                  ...document.querySelectorAll('.raidList'),
                  ...document.querySelectorAll('[id^="list"]')
                ];
                roots.forEach(el => {
                  const id = el.getAttribute('data-list-id') ||
                             el.getAttribute('data-id') ||
                             el.getAttribute('data-raidlist-id') ||
                             (el.id && /^list\d+$/i.test(el.id) ? el.id : '');
                  const nameEl = el.querySelector('.raidListTitle, .raidListTitleText, .farmListTitle, .listTitle, .listName, h2, h3, h4');
                  const name = text(nameEl);
                  add(id, name, 0);
                });
              }

              const diagnostics = {
                url: location.href,
                rallyPointFarmList: document.querySelectorAll('#rallyPointFarmList').length,
                farmListWrappers: document.querySelectorAll('.farmListWrapper').length,
                collapsedWrappers: document.querySelectorAll('.farmListWrapper.collapsed').length,
                expandedNow: expanded,
                raidList: document.querySelectorAll('#raidList').length,
                listIds: document.querySelectorAll('[id^="list"]').length,
                startButtons: document.querySelectorAll('.startFarmList, .startAllFarmLists').length,
                listsFound: out.length
              };

              AndroidFarm.onFarmLists(JSON.stringify({ items: out, diagnostics }));
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) {
            if (attempt < 7) {
                // React Farm List can appear after navigation/page-finished. Keep polling
                // long enough for the wrapper and lazy-rendered rows to become available.
                handler.postDelayed({ extractFarmLists(attempt + 1) }, 1200)
            } else {
                handler.postDelayed({
                    if (farmContainer.childCount == 0) {
                        farmStatus.text = "Farm List belum terbaca. Memeriksa URL Farm List alternatif..."
                        loadFarmListFallback()
                    }
                }, 300)
            }
        }
    }

    private fun executeSelectedFarmLists() {
        val selected = mutableListOf<FarmList>()
        for (i in 0 until farmContainer.childCount) {
            val v = farmContainer.getChildAt(i)
            if (v is CheckBox && v.isChecked) {
                selected += FarmList(v.tag?.toString().orEmpty(), v.text.toString())
            }
        }

        if (selected.isEmpty()) {
            status.text = "Status: RUNNING — tidak ada Farm List dipilih"
            lastRun.text = "Last run: ${timeFormat.format(Date())}"
            return
        }

        lastRun.text = "Last run: ${timeFormat.format(Date())}"

        // Pastikan halaman Farm List aktif sebelum mencoba menekan tombol.
        val server = normalizeServer(serverInput.text.toString())
        if (!farmListRequested) {
            farmListRequested = true
            webView.loadUrl("$server/build.php?id=39&gid=16&tt=99")
        }

        selected.forEach { list ->
            val idJson = JSONObject.quote(list.id)
            val nameJson = JSONObject.quote(list.name)
            val js = """
                (() => {
                  const id = $idJson;
                  const name = $nameJson;
                  const root = document.querySelector('[data-list-id="' + CSS.escape(id) + '"]') ||
                               document.getElementById(id) ||
                               [...document.querySelectorAll('.raidList,.farmList,[class*="raidList"]')]
                                 .find(x => (x.innerText || '').includes(name));
                  if (!root) return 'not-found';
                  const all = [...root.querySelectorAll('button,input[type=submit],a')];
                  const btn = all.find(x => /send all|send|raid|senden|plunder|farm/i.test(
                      (x.innerText || x.value || x.title || '').trim()
                  ));
                  if (btn) { btn.click(); return 'clicked'; }
                  return 'button-not-found';
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
    }

    private fun saveSelectedFarmLists() {
        val selected = buildSet {
            for (i in 0 until farmContainer.childCount) {
                val v = farmContainer.getChildAt(i)
                if (v is CheckBox && v.isChecked) add(v.tag?.toString().orEmpty())
            }
        }
        getSharedPreferences("config", 0)
            .edit()
            .putStringSet("selectedFarmLists", selected)
            .apply()
    }

    private fun updateNextRun() {
        nextRun.text = "Next run: ${timeFormat.format(Date(nextAt))}"
    }

    private fun stopScheduler() {
        running = false
        handler.removeCallbacks(scheduler)
        status.text = "Status: STOPPED"
        nextRun.text = "Next run: --"
    }

    private fun normalizeServer(value: String): String {
        var s = value.trim()
        if (s.isBlank()) s = "https://ts20.x2.europe.travian.com"
        if (!s.startsWith("http")) s = "https://$s"
        return s.trimEnd('/')
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("farm", "Farm reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    inner class FarmBridge {
        @JavascriptInterface
        fun onLoginResult(result: String) {
            runOnUiThread {
                when (result) {
                    "submitting" -> {
                        farmStatus.text = "Mengirim login ke Travian..."
                    }
                    "no_login_form" -> {
                        // Tidak ada form password berarti kemungkinan session masih aktif.
                        farmStatus.text = "Session ditemukan. Membuka Farm List..."
                        handler.postDelayed({ openFarmList() }, 250)
                    }
                    "no_username_field", "no_form" -> {
                        farmStatus.text = "Form login Travian tidak dikenali. Silakan cek halaman login."
                    }
                }
            }
        }

        @JavascriptInterface
        fun onFarmLists(json: String) {
            runOnUiThread {
                try {
                    val root = JSONObject(json)
                    val arr = root.optJSONArray("items") ?: JSONArray()
                    val diagnostics = root.optJSONObject("diagnostics")
                    farmContainer.removeAllViews()
                    val saved = getSharedPreferences("config", 0)
                        .getStringSet("selectedFarmLists", emptySet()) ?: emptySet()

                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optString("id")
                        val name = o.optString("name")
                        if (name.isBlank()) continue

                        val cb = CheckBox(this@MainActivity)
                        cb.text = name
                        cb.tag = id
                        cb.isChecked = saved.isEmpty() || saved.contains(id)
                        farmContainer.addView(cb)
                    }

                    if (farmContainer.childCount == 0) {
                        val raid = diagnostics?.optInt("raidList", 0) ?: 0
                        val ids = diagnostics?.optInt("listIds", 0) ?: 0
                        val contents = diagnostics?.optInt("raidContents", 0) ?: 0
                        val buttons = diagnostics?.optInt("startButtons", 0) ?: 0
                        val page = diagnostics?.optString("url", "") ?: ""

                        farmStatus.text = if (raid > 0 || ids > 0 || contents > 0 || buttons > 0) {
                            "Halaman Farm List terbuka, tetapi nama list belum terbaca.\n" +
                            "Debug: raidList=$raid, list=$ids, content=$contents, start=$buttons"
                        } else {
                            "Halaman yang terbuka bukan Farm List.\n" +
                            "Debug URL: $page"
                        }
                    } else {
                        farmStatus.text = "${farmContainer.childCount} Farm List ditemukan dari akun."
                    }
                } catch (e: Exception) {
                    farmStatus.text = "Gagal membaca Farm List: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
