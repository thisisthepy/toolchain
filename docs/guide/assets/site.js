// toolchain guide — language and theme. Loaded synchronously in <head> so the first paint is
// already in the right language and theme.
(function () {
  var LANG_KEY = "thisisthepy.guide.lang";
  var THEME_KEY = "thisisthepy.guide.theme";
  var root = document.documentElement;

  function read(key) { try { return localStorage.getItem(key); } catch (e) { return null; } }
  function write(key, value) { try { localStorage.setItem(key, value); } catch (e) { /* private mode */ } }

  function initialLang() {
    var saved = read(LANG_KEY);
    if (saved === "en" || saved === "ko") return saved;
    var nav = (navigator.languages && navigator.languages[0]) || navigator.language || "en";
    return /^ko\b/i.test(nav) ? "ko" : "en";
  }

  function applyLang(lang) {
    root.lang = lang;
    var title = root.getAttribute("data-title-" + lang);
    if (title) document.title = title;
    var labelled = document.querySelectorAll("[data-label-" + lang + "]");
    for (var i = 0; i < labelled.length; i++) {
      labelled[i].setAttribute("aria-label", labelled[i].getAttribute("data-label-" + lang));
    }
  }

  var theme = read(THEME_KEY);
  if (theme === "light" || theme === "dark") root.setAttribute("data-theme", theme);
  applyLang(initialLang());

  document.addEventListener("DOMContentLoaded", function () {
    applyLang(root.lang);

    var langBtn = document.getElementById("lang-toggle");
    if (langBtn) langBtn.addEventListener("click", function () {
      var next = root.lang === "ko" ? "en" : "ko";
      write(LANG_KEY, next);
      applyLang(next);
    });

    var themeBtn = document.getElementById("theme-toggle");
    if (themeBtn) themeBtn.addEventListener("click", function () {
      var current = root.getAttribute("data-theme");
      if (!current) {
        current = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      }
      var next = current === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      write(THEME_KEY, next);
    });
  });
})();
