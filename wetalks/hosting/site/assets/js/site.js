(function () {
  "use strict";
  var root = document.documentElement;
  function theme() { var t = root.getAttribute("data-theme"); return t || (matchMedia && matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"); }
  var tb = document.getElementById("themeBtn");
  if (tb) tb.addEventListener("click", function () { var n = theme() === "dark" ? "light" : "dark"; root.setAttribute("data-theme", n); try { localStorage.setItem("wt-theme", n); } catch (e) {} });
  var mb = document.getElementById("menuBtn"), links = document.getElementById("navLinks");
  if (mb && links) {
    mb.addEventListener("click", function () { var o = links.classList.toggle("open"); mb.setAttribute("aria-expanded", o ? "true" : "false"); });
    links.addEventListener("click", function (e) { if (e.target.closest("a")) { links.classList.remove("open"); mb.setAttribute("aria-expanded", "false"); } });
  }
  // copy buttons on code blocks
  document.querySelectorAll(".codebox").forEach(function (b) {
    var pre = b.querySelector("pre"); if (!pre) return;
    var btn = document.createElement("button"); btn.className = "copy"; btn.type = "button"; btn.textContent = "Copy";
    btn.addEventListener("click", function () {
      var txt = pre.innerText;
      (navigator.clipboard ? navigator.clipboard.writeText(txt) : Promise.reject()).then(function () { btn.textContent = "Copied"; setTimeout(function () { btn.textContent = "Copy"; }, 1400); }, function () {
        var r = document.createRange(); r.selectNodeContents(pre); var s = getSelection(); s.removeAllRanges(); s.addRange(r); btn.textContent = "Select + copy";
      });
    });
    b.appendChild(btn);
  });
  // language tabs for code samples
  document.querySelectorAll("[data-tabs]").forEach(function (g) {
    var tabs = g.querySelectorAll(".tab"), panels = g.querySelectorAll(".tabpanel");
    tabs.forEach(function (t, i) {
      t.addEventListener("click", function () {
        tabs.forEach(function (x, j) { x.setAttribute("aria-selected", j === i ? "true" : "false"); });
        panels.forEach(function (p, j) { p.hidden = j !== i; });
        try { localStorage.setItem("wt-lang", t.dataset.lang || ""); } catch (e) {}
      });
    });
    try { var l = localStorage.getItem("wt-lang"); if (l) { var m = g.querySelector('.tab[data-lang="' + l + '"]'); if (m) m.click(); } } catch (e) {}
  });
  // live download info
  var dv = document.querySelectorAll("[data-latest]");
  if (dv.length) fetch("/download/latest.json", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (j) {
    dv.forEach(function (el) { var k = el.getAttribute("data-latest"); if (j[k] !== undefined) el.textContent = j[k]; });
  }).catch(function () {});
})();
