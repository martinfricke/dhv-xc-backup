package de.igcbackup;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

public class Main {
    private static final String BASE = "https://de.dhv-xc.de";
    private static final String LINKS = "a[href*='/flight/']";
    private static final String NEXT = "button.js-goto[data-dir='next']";
    private static final Pattern ID = Pattern.compile("^/flight/(\\d+)/?$");
    private static final Pattern GPS = Pattern.compile("B\\d{6}\\d{7}[NS]\\d{8}[EW].*");
    private static final Path APP = Paths.get(System.getProperty("user.home"), ".dhv-xc-backup");
    private static final Path SESSION = APP.resolve("session.json");
    private static final Path SETTINGS = APP.resolve("settings.properties");
    private static final Path LOG = APP.resolve("backup.log");
    private static final Path IMAGES = Paths.get("screenshots").toAbsolutePath();

    private static class Config {
        boolean igc = true, kml, screenshots = true;
        int delay = 500;
        Path output = downloadDirectory();
    }

    private static class Stats {
        int loaded, skipped, failed;

        int done() {
            return loaded + skipped + failed;
        }
    }

    private static class Installation {
        private JDialog dialog;
        private JLabel status;

        Installation() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                dialog = new JDialog((java.awt.Frame) null, "DHV-XC Backup – Installation", false);
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                JLabel title = new JLabel("DHV-XC Backup", SwingConstants.CENTER);
                title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
                status = new JLabel("Programm wird vorbereitet ...", SwingConstants.CENTER);
                JProgressBar progress = new JProgressBar();
                progress.setIndeterminate(true);
                progress.setPreferredSize(new Dimension(370, 18));
                JPanel panel = new JPanel(new GridLayout(3, 1, 0, 14));
                panel.setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 28));
                panel.add(title);
                panel.add(status);
                panel.add(progress);
                dialog.add(panel);
                dialog.pack();
                dialog.setLocationRelativeTo(null);
                dialog.setAlwaysOnTop(true);
                dialog.setVisible(true);
            });
        }

        void status(String value) {
            SwingUtilities.invokeLater(() -> {
                if (dialog != null && dialog.isDisplayable()) status.setText(value);
            });
        }

        void close() {
            SwingUtilities.invokeLater(() -> {
                if (dialog != null) dialog.dispose();
            });
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        try {
            Files.createDirectories(APP);
            permissions(APP, true);
            Config c = loadSettings();
            c = args.length == 0 ? startDialog(c) : parse(args, c);
            if (c == null) return;
            Files.createDirectories(c.output);
            saveSettings(c);
            log("DHV-XC Backup gestartet – Zielordner: " + c.output);
            run(c);
        } catch (Exception e) {
            log("FEHLER: " + message(e));
            error("Das Backup konnte nicht ausgeführt werden:\n\n" + message(e) + "\n\nProtokoll:\n" + LOG);
        }
    }

    // Grafischer Startdialog, beim ersten Anzeigen als Bild speichern.
    private static Config startDialog(Config c) {
        JComboBox<String> formats = new JComboBox<>(new String[]{"Nur IGC", "Nur KML", "IGC und KML"});
        formats.setSelectedIndex(c.igc && c.kml ? 2 : c.kml ? 1 : 0);
        JTextField folder = new JTextField(c.output.toString(), 30);
        JButton browse = new JButton("Durchsuchen ...");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(folder.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Zielordner auswählen");
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                folder.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(browse, BorderLayout.EAST);
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 6));
        panel.add(new JLabel("Downloadformat:"));
        panel.add(formats);
        panel.add(new JLabel("Zielordner:"));
        panel.add(folderRow);
        panel.add(new JLabel("Anmeldung und Backup erfolgen im Browser."));
        JOptionPane option = new JOptionPane(panel, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = option.createDialog(null, "DHV-XC Backup");
        if (c.screenshots) {
            javax.swing.Timer timer = new javax.swing.Timer(350, e -> screenshotDialog(dialog, folder));
            timer.setRepeats(false);
            timer.start();
        }
        dialog.setVisible(true);
        dialog.dispose();
        Object selection = option.getValue();
        if (!(selection instanceof Integer) || (Integer) selection != JOptionPane.OK_OPTION) return null;
        String path = folder.getText().trim();
        if (path.isEmpty()) throw new IllegalArgumentException("Bitte einen Zielordner auswählen.");
        c.igc = formats.getSelectedIndex() != 1;
        c.kml = formats.getSelectedIndex() != 0;
        c.output = Paths.get(path).toAbsolutePath().normalize();
        return c;
    }

    private static void screenshotDialog(JDialog dialog, JTextField folder) {
        if (!dialog.isShowing()) return;
        String original = folder.getText();
        try {
            Files.createDirectories(IMAGES);
            folder.setText("Downloads");
            int w = dialog.getContentPane().getWidth(), h = dialog.getContentPane().getHeight();
            if (w < 1 || h < 1) return;
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                dialog.getContentPane().printAll(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", IMAGES.resolve("startdialog.png").toFile());
            log("Screenshot gespeichert: startdialog.png");
        } catch (Exception e) {
            log("WARNUNG: Startbild konnte nicht erstellt werden: " + message(e));
        } finally {
            folder.setText(original);
        }
    }

    private static Config loadSettings() {
        Config c = new Config();
        if (!Files.isRegularFile(SETTINGS)) return c;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(SETTINGS)) {
            p.load(in);
            c.igc = Boolean.parseBoolean(p.getProperty("igc", "true"));
            c.kml = Boolean.parseBoolean(p.getProperty("kml", "false"));
            c.output = Paths.get(p.getProperty("output", c.output.toString())).toAbsolutePath().normalize();
            if (!c.igc && !c.kml) c.igc = true;
        } catch (Exception e) {
            log("WARNUNG: Einstellungen nicht lesbar: " + message(e));
        }
        return c;
    }

    private static void saveSettings(Config c) {
        Properties p = new Properties();
        p.setProperty("igc", Boolean.toString(c.igc));
        p.setProperty("kml", Boolean.toString(c.kml));
        p.setProperty("output", c.output.toString());
        try (OutputStream out = Files.newOutputStream(SETTINGS)) {
            p.store(out, "DHV-XC Backup");
            permissions(SETTINGS, false);
        } catch (IOException e) {
            log("WARNUNG: Einstellungen nicht gespeichert: " + message(e));
        }
    }

    // Browser vorbereiten und die Downloads geordnet abarbeiten.
    private static void run(Config c) throws Exception {
        Installation installation = new Installation();
        try (Playwright pw = Playwright.create()) {
            Browser browser;
            try {
                installation.status("Chromium wird überprüft ...");
                ensureChromium(pw, installation);
                installation.status("Chromium wird gestartet ...");
                browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                        .setHeadless(false).setArgs(Collections.singletonList("--start-maximized")));
            } finally {
                installation.close();
            }
            try {
                BrowserContext context = createContext(browser);
                context.setDefaultTimeout(12000);
                context.addInitScript(UI_SCRIPT);
                Page page = context.newPage();
                navigate(page, BASE + "/flights");
                log("Bitte anmelden, „Meine Flüge“ wählen und „Backup starten“ anklicken.");
                waitForStart(page);
                saveSession(context);
                Stats stats = new Stats();
                update(page, "Flugliste wird eingelesen ...", 0, 0, stats, "running");
                Map<String, String> flights = collectFlights(page, stats);
                List<String> formats = new ArrayList<>();
                if (c.igc) formats.add("igc");
                if (c.kml) formats.add("kml");
                int total = flights.size() * formats.size(), index = 0;
                boolean stopped = cancelled(page), progressCaptured = false;
                if (flights.isEmpty() && !stopped) throw new IllegalStateException("Keine Flüge gefunden.");
                downloadLoop:
                for (Map.Entry<String, String> flight : flights.entrySet()) {
                    index++;
                    for (String format : formats) {
                        if (cancelled(page)) {
                            stopped = true;
                            break downloadLoop;
                        }
                        String status = "Flug " + index + "/" + flights.size() + " – " + format.toUpperCase(Locale.ROOT);
                        update(page, status, stats.done(), total, stats, "running");
                        download(page, flight.getKey(), flight.getValue(), format, c.output, stats);
                        stopped = cancelled(page);
                        update(page, stopped ? "Abbruch angefordert ..." : status, stats.done(), total, stats, "running");
                        if (c.screenshots && !progressCaptured && total > 0 && stats.done() * 4 >= total)
                            progressCaptured = screenshotBrowser(page, "fortschritt.png");
                        if (stopped) break downloadLoop;
                        if (c.delay > 0) page.waitForTimeout(c.delay);
                    }
                }
                stopped |= cancelled(page);
                if (c.screenshots && !progressCaptured) screenshotBrowser(page, "fortschritt.png");
                update(page, stopped ? "Backup abgebrochen" : "Backup abgeschlossen", stats.done(), total,
                        stats, stopped ? "cancelled" : "finished");
                if (c.screenshots) screenshotBrowser(page, stopped ? "abbruch.png" : "abschluss.png");
                log(String.format("%s: %d Flüge, %d neu, %d vorhanden, %d Fehler",
                        stopped ? "Abgebrochen" : "Abgeschlossen",
                        flights.size(), stats.loaded, stats.skipped, stats.failed));
                while (!page.isClosed()) {
                    if (Boolean.TRUE.equals(page.evaluate("() => window.__backupClose === true"))) break;
                    page.waitForTimeout(300);
                }
            } catch (PlaywrightException e) {
                if (!browser.isConnected()) log("Browser geschlossen – Backup beendet.");
                else throw e;
            } finally {
                if (browser.isConnected()) browser.close();
            }
        } finally {
            installation.close();
        }
    }

    // Das vollständige sichtbare Chromium-Fenster aufnehmen, ohne Browser-Chrome.
    private static boolean screenshotBrowser(Page page, String filename) {
        try {
            Files.createDirectories(IMAGES);
            page.screenshot(new Page.ScreenshotOptions().setPath(IMAGES.resolve(filename))
                    .setFullPage(false));
            log("Screenshot gespeichert: " + filename);
            return true;
        } catch (Exception e) {
            log("WARNUNG: Screenshot " + filename + " fehlgeschlagen: " + message(e));
            return false;
        }
    }

    // Fehlendes Chromium mit der Playwright-CLI nachinstallieren.
    private static void ensureChromium(Playwright pw, Installation installation) throws Exception {
        Path executable = Paths.get(pw.chromium().executablePath());
        if (Files.isRegularFile(executable)) {
            log("Chromium bereits installiert.");
            return;
        }
        installation.status("Chromium wird installiert ...");
        String java = Paths.get(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "com.microsoft.playwright.CLI", "install", "chromium");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String lastLine = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    lastLine = line.trim();
                    log("[INSTALLATION] " + lastLine);
                }
            }
        }
        int code = process.waitFor();
        if (code != 0 || !Files.isRegularFile(executable))
            throw new IOException("Chromium-Installation fehlgeschlagen (Code " + code + "): " + lastLine);
        log("Chromium erfolgreich installiert.");
    }

    private static BrowserContext createContext(Browser browser) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setAcceptDownloads(true).setViewportSize(null);
        if (Files.isRegularFile(SESSION)) {
            try {
                BrowserContext context = browser.newContext(options.setStorageStatePath(SESSION));
                log("Gespeicherte Anmeldung geladen.");
                return context;
            } catch (PlaywrightException e) {
                log("Gespeicherte Anmeldung nicht lesbar.");
            }
        }
        return browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(true).setViewportSize(null));
    }

    private static void saveSession(BrowserContext context) {
        try {
            context.storageState(new BrowserContext.StorageStateOptions().setPath(SESSION));
            permissions(SESSION, false);
            log("Anmeldesitzung gespeichert.");
        } catch (Exception e) {
            log("WARNUNG: Anmeldung nicht gespeichert: " + message(e));
        }
    }

    private static void permissions(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
        }
    }

    // Bedienoberfläche bleibt beim Seitenwechsel im gleichen Browsertab erhalten.
    private static final String UI_SCRIPT = String.join("\n",
            "(() => {",
            " const STATE='dhv-backup-state',CANCEL='dhv-backup-cancel';",
            " function init(){",
            "  if(document.getElementById('backup-host'))return;",
            "  const host=document.createElement('div');host.id='backup-host';",
            "  host.style.cssText='position:fixed!important;inset:0!important;z-index:2147483647!important;pointer-events:none!important';",
            "  const root=host.attachShadow({mode:'open'});",
            "  root.innerHTML=`",
            "  <style>",
            "   *{box-sizing:border-box}",
            "   [hidden]{display:none!important}",
            "   button{font-family:Arial,sans-serif}",
            "   .start,.panel,.done{position:fixed;top:18px;left:50%;transform:translateX(-50%)}",
            "   .start{padding:20px 38px;max-width:92vw;background:#888;color:white;",
            "    border:3px solid white;border-radius:16px;font-size:22px;font-weight:bold;",
            "    box-shadow:0 8px 28px #0005;cursor:not-allowed;pointer-events:auto}",
            "   .start:enabled{background:#168347;cursor:pointer}",
            "   .start:enabled:hover{background:#106436}",
            "   .panel,.done{width:440px;max-width:92vw;padding:20px;background:white;",
            "    color:#263c30;border-radius:14px;box-shadow:0 6px 25px #0004;",
            "    font:14px Arial,sans-serif;pointer-events:auto}",
            "   h2{margin:0 0 14px;font-size:21px}",
            "   .line,.stats{display:flex;justify-content:space-between;gap:12px;margin:10px 0}",
            "   .track{height:16px;background:#e0e8e2;border-radius:10px;overflow:hidden}",
            "   .bar{height:100%;width:0;background:#168347;transition:width .2s}",
            "   .percent{text-align:center;font-size:27px;font-weight:bold;margin:12px}",
            "   .stats{font-size:13px}",
            "   .cancel,.close{display:block;margin:16px auto 0;padding:11px 24px;",
            "    color:white;border:0;border-radius:9px;font-size:15px;font-weight:bold;cursor:pointer}",
            "   .cancel{background:#b52e35}",
            "   .cancel:hover{background:#8f2026}",
            "   .cancel:disabled{background:#999;cursor:default}",
            "   .close{background:#168347}",
            "   .done{width:480px;padding:26px;text-align:center;font-size:16px}",
            "  </style>",
            "  <button id='start' class='start' disabled>Bitte anmelden und „Meine Flüge“ wählen</button>",
            "  <section id='progress' class='panel' hidden>",
            "   <h2>DHV-XC Backup</h2>",
            "   <div class='line'><span id='status'>Vorbereitung ...</span><span id='count'>0 / 0</span></div>",
            "   <div class='track'><div id='bar' class='bar'></div></div>",
            "   <div id='percent' class='percent'>0 %</div>",
            "   <div class='stats'><span id='loaded'>Neu: 0</span>",
            "    <span id='skipped'>Vorhanden: 0</span><span id='failed'>Fehler: 0</span></div>",
            "   <button id='cancel' class='cancel'>Backup abbrechen</button>",
            "  </section>",
            "  <section id='done' class='done' hidden>",
            "   <h2 id='result'>Backup abgeschlossen</h2>",
            "   <div class='track'><div id='donebar' class='bar'></div></div>",
            "   <p id='summary'></p><button id='close' class='close'>Schließen</button>",
            "  </section>`;",
            "  document.body.appendChild(host);",
            "  const el=id=>root.getElementById(id);",
            "  window.__backupStart=false;window.__backupClose=false;",
            "  el('start').onclick=()=>{",
            "   if(el('start').disabled)return;",
            "   sessionStorage.removeItem(STATE);sessionStorage.removeItem(CANCEL);",
            "   window.__backupStart=true;el('start').hidden=true;",
            "  };",
            "  el('cancel').onclick=()=>{",
            "   sessionStorage.setItem(CANCEL,'true');",
            "   el('cancel').disabled=true;el('cancel').textContent='Abbruch angefordert ...';",
            "  };",
            "  el('close').onclick=()=>window.__backupClose=true;",
            "  window.__backupReady=ready=>{",
            "   const b=el('start');b.disabled=!ready;",
            "   b.textContent=ready?'Backup starten':'Bitte anmelden und „Meine Flüge“ wählen';",
            "  };",
            "  window.__backupUpdate=d=>{",
            "   sessionStorage.setItem(STATE,JSON.stringify(d));render(d);",
            "  };",
            "  function render(d){",
            "   const running=d.state==='running';",
            "   const stopping=sessionStorage.getItem(CANCEL)==='true';",
            "   const percent=d.total?Math.min(100,Math.round(d.done*100/d.total)):0;",
            "   el('start').hidden=true;el('progress').hidden=!running;el('done').hidden=running;",
            "   el('status').textContent=stopping?'Abbruch angefordert ...':d.status;",
            "   el('count').textContent=d.done+' / '+d.total;",
            "   el('percent').textContent=percent+' %';el('bar').style.width=percent+'%';",
            "   el('loaded').textContent='Neu: '+d.loaded;",
            "   el('skipped').textContent='Vorhanden: '+d.skipped;",
            "   el('failed').textContent='Fehler: '+d.failed;",
            "   el('cancel').disabled=stopping;",
            "   el('cancel').textContent=stopping?'Abbruch angefordert ...':'Backup abbrechen';",
            "   el('result').textContent=d.state==='cancelled'?'Backup abgebrochen':",
            "    d.failed?'Backup mit Fehlern':'Backup erfolgreich abgeschlossen!';",
            "   el('summary').textContent='Neu: '+d.loaded+' | Vorhanden: '+d.skipped+",
            "    ' | Fehler: '+d.failed+' | Bearbeitet: '+d.done+' / '+d.total;",
            "   el('donebar').style.width=percent+'%';",
            "  }",
            "  try{const d=sessionStorage.getItem(STATE);if(d)render(JSON.parse(d));}catch(e){}",
            " }",
            " if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});",
            " else init();",
            "})();");

    private static void waitForStart(Page page) {
        while (!page.isClosed()) {
            try {
                URI uri = URI.create(page.url());
                if ("de.dhv-xc.de".equalsIgnoreCase(uri.getHost()) && "/flights".equals(uri.getPath())) {
                    boolean ready = Boolean.TRUE.equals(page.evaluate("() => {" +
                            "const login=[...document.querySelectorAll('button')].some(b=>" +
                            "b.textContent.trim()==='Anmelden'&&b.getClientRects().length>0);" +
                            "const filter=/Angewandte\\s+Filter\\s*:\\s*Meine\\s+Flüge\\b/i.test(document.body.innerText);" +
                            "return !login&&filter&&document.querySelectorAll('a[href*=\"/flight/\"]').length>0;}"));
                    page.evaluate("ready => window.__backupReady?.(ready)", ready);
                    if (ready && Boolean.TRUE.equals(page.evaluate("() => window.__backupStart === true"))) return;
                }
            } catch (PlaywrightException ignored) {
            }
            page.waitForTimeout(500);
        }
        throw new IllegalStateException("Browser geschlossen.");
    }

    private static void update(Page page, String status, int done, int total, Stats s, String state) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        data.put("done", done);
        data.put("total", total);
        data.put("loaded", s.loaded);
        data.put("skipped", s.skipped);
        data.put("failed", s.failed);
        data.put("state", state);
        page.evaluate("data => window.__backupUpdate?.(data)", data);
    }

    private static boolean cancelled(Page page) {
        return Boolean.TRUE.equals(page.evaluate(
                "() => sessionStorage.getItem('dhv-backup-cancel') === 'true'"));
    }

    // Alle Seiten der gefilterten Flugliste erfassen.
    private static Map<String, String> collectFlights(Page page, Stats s) {
        Map<String, String> flights = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        for (int number = 1; number <= 500; number++) {
            if (cancelled(page)) break;
            String oldUrl = page.url(), oldLinks = flightLinks(page);
            if (!visited.add(oldUrl + "|" + oldLinks)) break;
            Locator links = page.locator(LINKS);
            for (int i = 0, n = links.count(); i < n; i++) {
                if (cancelled(page)) break;
                String href = links.nth(i).getAttribute("href");
                if (href == null) continue;
                try {
                    URI uri = URI.create(page.url()).resolve(href);
                    Matcher m = ID.matcher(uri.getPath());
                    if ("de.dhv-xc.de".equalsIgnoreCase(uri.getHost()) && m.matches())
                        flights.putIfAbsent(m.group(1), BASE + "/flight/" + m.group(1));
                } catch (IllegalArgumentException ignored) {
                }
            }
            log("Seite " + number + ": " + flights.size() + " Flüge gefunden.");
            update(page, "Seite " + number + ": " + flights.size() + " Flüge gefunden", 0, 0, s, "running");
            if (cancelled(page)) break;
            Locator next = page.locator(NEXT);
            if (next.count() == 0 || !next.first().isVisible()
                    || !next.first().isEnabled() || disabled(next.first())) break;
            next.first().click();
            long deadline = System.currentTimeMillis() + 15000;
            boolean changed = false;
            while (System.currentTimeMillis() < deadline) {
                if (cancelled(page)) break;
                page.waitForTimeout(200);
                if (!oldUrl.equals(page.url()) || !oldLinks.equals(flightLinks(page))) {
                    changed = true;
                    break;
                }
            }
            if (!changed) break;
        }
        return flights;
    }

    private static String flightLinks(Page page) {
        return String.valueOf(page.locator(LINKS).evaluateAll(
                "els => els.map(e => e.getAttribute('href')).join('|')"));
    }

    private static boolean disabled(Locator button) {
        return button.getAttribute("disabled") != null
                || "true".equalsIgnoreCase(button.getAttribute("aria-disabled"))
                || Boolean.TRUE.equals(button.evaluate(
                "el => !!el.closest('.disabled,[aria-disabled=\"true\"]')" +
                        " || getComputedStyle(el).pointerEvents==='none'"));
    }

    // Vorhandene gültige Dateien behalten; neue Dateien zunächst temporär speichern.
    private static void download(Page page, String id, String url, String format, Path dir, Stats s) {
        Path target = dir.resolve(id + "." + format), temp = dir.resolve(id + "." + format + ".part");
        try {
            if (Files.isRegularFile(target) && valid(target, format)) {
                s.skipped++;
                log("[ÜBERSPRUNGEN] " + target.getFileName());
                return;
            }
            if (cancelled(page)) return;
            navigate(page, url);
            if (cancelled(page)) return;
            Download file = triggerDownload(page, format);
            if (file == null) {
                if (cancelled(page)) return;
                throw new IOException("Download-Schaltfläche nicht gefunden.");
            }
            Files.deleteIfExists(temp);
            file.saveAs(temp);
            if (!valid(temp, format)) {
                Path invalid = dir.resolve(id + "." + format + ".invalid");
                Files.move(temp, invalid, StandardCopyOption.REPLACE_EXISTING);
                throw new IOException("Ungültige Datei: " + invalid);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            s.loaded++;
            log("[OK] " + target.getFileName() + ("igc".equals(format) ?
                    " – " + gpsPoints(target) + " GPS-Punkte" : ""));
        } catch (PlaywrightException e) {
            if (page.isClosed()) throw e;
            s.failed++;
            log("[FEHLER] " + id + "." + format + ": " + message(e));
        } catch (Exception e) {
            s.failed++;
            log("[FEHLER] " + id + "." + format + ": " + message(e));
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException e) {
                log("WARNUNG: Temporäre Datei nicht gelöscht: " + temp);
            }
        }
    }

    private static Download triggerDownload(Page page, String format) {
        Pattern pattern = Pattern.compile("(^|[^a-z])" + Pattern.quote(format) + "([^a-z]|$)",
                Pattern.CASE_INSENSITIVE);
        for (int attempt = 0; attempt < 2; attempt++) {
            Locator controls = page.locator("a,button,[role='button']");
            for (int i = 0, n = Math.min(controls.count(), 350); i < n; i++) {
                if (cancelled(page)) return null;
                Locator button = controls.nth(i);
                try {
                    if (!button.isVisible()) continue;
                    String text = safe(button.textContent()) + " " + safe(button.getAttribute("title"))
                            + " " + safe(button.getAttribute("aria-label")) + " " + safe(button.getAttribute("href"));
                    if (pattern.matcher(text).find())
                        return page.waitForDownload(new Page.WaitForDownloadOptions().setTimeout(10000), button::click);
                } catch (PlaywrightException e) {
                    if (page.isClosed()) throw e;
                }
            }
            if (attempt == 0) {
                Locator menu = page.locator("button[aria-label*='download' i],button[title*='download' i],"
                        + "button[aria-label*='export' i],button[title*='export' i],"
                        + "button[aria-label*='mehr' i]");
                if (menu.count() == 0) break;
                try {
                    menu.first().click();
                } catch (PlaywrightException e) {
                    if (page.isClosed()) throw e;
                    break;
                }
            }
        }
        return null;
    }

    private static boolean valid(Path file, String format) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < 100) return false;
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if ("kml".equals(format)) {
            String lower = text.toLowerCase(Locale.ROOT);
            return lower.contains("<kml") && lower.contains("</kml>");
        }
        if (repeatedBlock(bytes)) return false;
        boolean header = false, date = false, points = false;
        for (String line : text.split("\\r?\\n|\\r")) {
            if (line.matches("A[A-Za-z0-9].*")) header = true;
            else if (line.matches("HFDTE\\d{6}.*")) date = true;
            else if (GPS.matcher(line).matches()) points = true;
        }
        return header && date && points;
    }

    private static boolean repeatedBlock(byte[] bytes) {
        final int size = 2048;
        if (bytes.length < size * 2) return false;
        for (int i = size; i < bytes.length; i++) if (bytes[i] != bytes[i % size]) return false;
        return true;
    }

    private static int gpsPoints(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        int count = 0;
        for (String line : text.split("\\r?\\n|\\r")) if (GPS.matcher(line).matches()) count++;
        return count;
    }

    private static Path downloadDirectory() {
        Path home = Paths.get(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String profile = System.getenv("USERPROFILE");
            return Paths.get(profile == null ? home.toString() : profile, "Downloads");
        }
        if (os.contains("mac")) return home.resolve("Downloads");
        try {
            Process p = new ProcessBuilder("xdg-user-dir", "DOWNLOAD").start();
            if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                String value = new String(readStream(p.getInputStream()), StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    Path path = Paths.get(value);
                    if (path.isAbsolute()) return path;
                }
            } else p.destroyForcibly();
        } catch (Exception ignored) {
        }
        Path config = home.resolve(".config/user-dirs.dirs");
        if (Files.isRegularFile(config)) {
            try {
                for (String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                    if (!line.trim().startsWith("XDG_DOWNLOAD_DIR=")) continue;
                    String value = line.substring(line.indexOf('=') + 1).trim()
                            .replace("\"", "").replace("$HOME", home.toString());
                    Path path = Paths.get(value);
                    if (path.isAbsolute()) return path;
                }
            } catch (Exception ignored) {
            }
        }
        return home.resolve("Downloads");
    }

    private static byte[] readStream(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static Config parse(String[] args, Config c) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--igc":
                    c.igc = true;
                    c.kml = false;
                    break;
                case "--kml":
                    c.igc = false;
                    c.kml = true;
                    break;
                case "--both":
                    c.igc = c.kml = true;
                    break;
                case "--out":
                    c.output = Paths.get(value(args, ++i));
                    break;
                case "--delay":
                    c.delay = Integer.parseInt(value(args, ++i));
                    if (c.delay < 0) throw new IllegalArgumentException("Die Pause darf nicht negativ sein.");
                    break;
                case "--no-screenshots":
                    c.screenshots = false;
                    break;
                case "--help":
                case "-h":
                    JOptionPane.showMessageDialog(null,
                            "--igc: Nur IGC\n--kml: Nur KML\n--both: Beide Formate\n"
                                    + "--out PFAD: Zielordner\n--delay MS: Pause zwischen Downloads\n"
                                    + "--no-screenshots: Ohne Bildschirmfotos",
                            "DHV-XC Backup – Hilfe", JOptionPane.INFORMATION_MESSAGE);
                    return null;
                default:
                    throw new IllegalArgumentException("Unbekannter Parameter: " + args[i]);
            }
        }
        c.output = c.output.toAbsolutePath().normalize();
        return c;
    }

    private static String value(String[] args, int index) {
        if (index >= args.length || args[index].startsWith("--"))
            throw new IllegalArgumentException("Parameterwert fehlt.");
        return args[index];
    }

    private static synchronized void log(String text) {
        String line = new Date() + " | " + text;
        System.out.println(line);
        try {
            Files.write(LOG, Collections.singletonList(line), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            permissions(LOG, false);
        } catch (Exception e) {
            System.err.println("Protokollfehler: " + message(e));
        }
    }

    private static void error(String text) {
        JOptionPane.showMessageDialog(null, text, "DHV-XC Backup – Fehler", JOptionPane.ERROR_MESSAGE);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static void navigate(Page page, String url) {
        page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
    }
}