/* WeTalks developer portal: sign in with email + password (Firebase REST), manage apps and API keys. No third-party code, every string is inserted as text. */
(function () {
  "use strict";
  var KEY = "AIzaSyA9k0-Z4SuY3BJ_RWlYA6Ac4d2tdBOIVMk";   // public Firebase web key (identifies the project, grants nothing)
  var app = document.getElementById("app"), toastEl = document.getElementById("toast");
  var S = load();
  function load() { try { return JSON.parse(sessionStorage.getItem("wt-dev") || "null") || {}; } catch (e) { return {}; } }
  function save() { try { sessionStorage.setItem("wt-dev", JSON.stringify(S)); } catch (e) {} }
  function toast(t) { toastEl.textContent = t; toastEl.classList.add("show"); clearTimeout(toast.t); toast.t = setTimeout(function () { toastEl.classList.remove("show"); }, 2600); }
  function h(tag, attrs, kids) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) { if (k === "class") e.className = attrs[k]; else if (k.slice(0, 2) === "on") e.addEventListener(k.slice(2), attrs[k]); else if (attrs[k] !== false && attrs[k] != null) e.setAttribute(k, attrs[k] === true ? "" : attrs[k]); });
    (kids == null ? [] : [].concat(kids)).forEach(function (c) { if (c == null || c === false) return; e.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c); });
    return e;
  }
  function mount() { app.replaceChildren.apply(app, arguments); window.scrollTo(0, 0); }
  function spinner() { return h("div", { class: "center mt" }, [h("span", { class: "spin", role: "status", "aria-label": "Loading" })]); }

  // ---- Firebase REST
  function fbPost(url, body) {
    return fetch(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }).then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error(fbMsg(j)); return j; }); });
  }
  function fbMsg(j) {
    var m = j && j.error && j.error.message || "Something went wrong";
    var map = { EMAIL_EXISTS: "That email already has an account. Sign in instead.", INVALID_LOGIN_CREDENTIALS: "Wrong email or password.", INVALID_PASSWORD: "Wrong email or password.", EMAIL_NOT_FOUND: "Wrong email or password.",
      WEAK_PASSWORD: "Use a stronger password (at least 6 characters).", INVALID_EMAIL: "That email address looks wrong.", TOO_MANY_ATTEMPTS_TRY_LATER: "Too many attempts. Try again in a few minutes.", USER_DISABLED: "This account is disabled." };
    for (var k in map) if (m.indexOf(k) === 0) return map[k];
    return m;
  }
  var AUTH = "https://identitytoolkit.googleapis.com/v1/accounts:";
  function setSession(j) { S.idToken = j.idToken || j.id_token; S.refresh = j.refreshToken || j.refresh_token || S.refresh; S.exp = Date.now() + (parseInt(j.expiresIn || j.expires_in || "3600", 10) - 60) * 1000; var c = claims(S.idToken); S.email = c.email || S.email; S.verified = c.email_verified === true; save(); }
  function claims(t) { try { return JSON.parse(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))); } catch (e) { return {}; } }
  function refresh() {
    if (!S.refresh) return Promise.reject(new Error("signed out"));
    return fetch("https://securetoken.googleapis.com/v1/token?key=" + KEY, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body: "grant_type=refresh_token&refresh_token=" + encodeURIComponent(S.refresh) })
      .then(function (r) { return r.json().then(function (j) { if (!r.ok) throw new Error("session expired"); setSession(j); }); });
  }
  function signOut() { S = {}; save(); location.hash = "#/login"; }

  // ---- our API
  function api(path, opt, retry) {
    opt = opt || {};
    var go = function () {
      var o = { method: opt.method || "GET", headers: { "Authorization": "Bearer " + S.idToken } };
      if (opt.body) { o.headers["Content-Type"] = "application/json"; o.body = JSON.stringify(opt.body); }
      return fetch("/api/v1/" + path, o).then(function (r) {
        if (r.status === 401 && !retry) return refresh().then(function () { return api(path, opt, true); });
        if (r.status === 204) return null;
        return r.json().then(function (j) { if (!r.ok) { var e = new Error(typeof j.error === "string" ? j.error : (j.error && j.error.message) || "Request failed"); e.status = r.status; throw e; } return j; });
      });
    };
    return (S.exp && Date.now() > S.exp ? refresh() : Promise.resolve()).then(go);
  }
  function fmtDate(ts) { return new Date(ts * 1000).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }); }

  // ---- views
  function viewLogin(mode) {
    var up = mode === "signup", err = h("p", { class: "err", role: "alert" }), email = h("input", { type: "email", id: "em", autocomplete: "email", required: true }), pw = h("input", { type: "password", id: "pw", autocomplete: up ? "new-password" : "current-password", required: true, minlength: "6" });
    var btn = h("button", { class: "btn-primary", type: "submit", style: "width:100%" }, up ? "Create account" : "Sign in");
    var form = h("form", { onsubmit: function (e) {
      e.preventDefault(); err.textContent = ""; btn.disabled = true;
      fbPost(AUTH + (up ? "signUp" : "signInWithPassword") + "?key=" + KEY, { email: email.value.trim(), password: pw.value, returnSecureToken: true }).then(function (j) {
        setSession(j);
        if (up) return fbPost(AUTH + "sendOobCode?key=" + KEY, { requestType: "VERIFY_EMAIL", idToken: S.idToken }).then(function () { toast("Verification email sent"); });
      }).then(function () { location.hash = S.verified ? "#/apps" : "#/verify"; route(); }).catch(function (e2) { err.textContent = e2.message; btn.disabled = false; });
    } }, [
      h("div", { class: "field" }, [h("label", { for: "em" }, "Email"), email]),
      h("div", { class: "field" }, [h("label", { for: "pw" }, "Password"), pw, up ? h("p", { class: "hint" }, "At least 6 characters. Use something only you know.") : null]),
      btn, err]);
    var reset = h("a", { href: "#", onclick: function (e) { e.preventDefault(); if (!email.value) { err.textContent = "Type your email first, then tap this."; return; } fbPost(AUTH + "sendOobCode?key=" + KEY, { requestType: "PASSWORD_RESET", email: email.value.trim() }).then(function () { toast("If that account exists, a reset email is on its way"); }).catch(function (x) { err.textContent = x.message; }); } }, "Forgot password?");
    mount(h("div", { class: "auth-card card" }, [
      h("h2", null, up ? "Create your developer account" : "Developer sign in"),
      h("p", { class: "muted" }, up ? "Free. Takes a minute. You can use the same email in the WeTalks app." : "Sign in to manage your apps and API keys."),
      form, up ? null : h("p", { class: "center mt mb0" }, [reset]),
      h("p", { class: "center muted mb0 mt" }, [up ? "Already have an account? " : "New here? ", h("a", { href: up ? "#/login" : "#/signup" }, up ? "Sign in" : "Create an account")])]));
  }

  function viewVerify() {
    var err = h("p", { class: "err" });
    mount(h("div", { class: "auth-card card" }, [
      h("h2", null, "Verify your email"), h("p", { class: "muted" }, "We sent a link to " + (S.email || "your email") + ". Open it, then come back and tap the button. This keeps the platform free of spam."),
      h("div", { class: "row" }, [
        h("button", { class: "btn-primary", onclick: function () { refresh().then(function () { if (S.verified) { location.hash = "#/apps"; route(); } else err.textContent = "Not verified yet. Open the link in the email first."; }).catch(function () { err.textContent = "Please sign in again."; }); } }, "I've verified"),
        h("button", { class: "btn", onclick: function () { fbPost(AUTH + "sendOobCode?key=" + KEY, { requestType: "VERIFY_EMAIL", idToken: S.idToken }).then(function () { toast("Sent again"); }).catch(function (x) { err.textContent = x.message; }); } }, "Resend email"),
        h("button", { class: "btn", onclick: signOut }, "Sign out")]), err]));
  }

  function topbar(title, back) {
    return h("div", { class: "row", style: "margin-bottom:16px" }, [
      back ? h("a", { class: "btn sm", href: back }, "← Apps") : null, h("h2", { class: "grow mb0", style: "font-size:1.4rem" }, title),
      h("span", { class: "muted hide-sm" }, S.email || ""), h("button", { class: "btn sm", onclick: signOut }, "Sign out")]);
  }

  function viewApps() {
    mount(topbar("Your apps"), spinner());
    api("dev/apps").then(function (d) {
      var list = h("div", { class: "list" });
      d.apps.forEach(function (a) {
        list.appendChild(h("a", { class: "item", href: "#/apps/" + a.id, style: "text-decoration:none;color:inherit" }, [
          h("div", null, [h("div", { class: "t" }, [a.name + " ", h("span", { class: "badge " + (a.verified ? "ok" : "") }, a.verified ? "Verified" : "Unverified")]), h("div", { class: "s" }, a.id + " · " + a.active_keys + " active key" + (a.active_keys === 1 ? "" : "s"))]),
          h("div", { class: "s" }, a.today.messages + " msgs · " + a.today.otps + " codes today")]));
      });
      var dlg = h("dialog");
      function openNew() {
        var err = h("p", { class: "err" }), n = h("input", { type: "text", maxlength: "32", required: true, id: "an" }), de = h("input", { type: "text", maxlength: "200", id: "ad" });
        dlg.replaceChildren(h("form", { onsubmit: function (e) { e.preventDefault(); err.textContent = ""; api("dev/apps", { method: "POST", body: { name: n.value, description: de.value } }).then(function (a) { dlg.close(); location.hash = "#/apps/" + a.id; }).catch(function (x) { err.textContent = x.message; }); } }, [
          h("h3", null, "New app"), h("p", { class: "muted" }, "People see this name on every message you send, so use your real product name."),
          h("div", { class: "field" }, [h("label", { for: "an" }, "App name"), n]), h("div", { class: "field" }, [h("label", { for: "ad" }, "What does it do? (optional)"), de]),
          err, h("div", { class: "row" }, [h("button", { class: "btn-primary", type: "submit" }, "Create"), h("button", { class: "btn", type: "button", onclick: function () { dlg.close(); } }, "Cancel")])]));
        dlg.showModal();
      }
      mount(topbar("Your apps"), h("div", { class: "row", style: "margin-bottom:14px" }, [h("p", { class: "muted grow mb0" }, "Each app gets its own API keys, limits and message log."), h("button", { class: "btn-primary", onclick: openNew }, "+ New app")]),
        d.apps.length ? list : h("div", { class: "card center" }, [h("h3", null, "No apps yet"), h("p", null, "Create your first app to get an API key."), h("a", { class: "btn", href: "/developers/docs.html" }, "Read the docs")]), dlg);
    }).catch(fail);
  }

  function copyBtn(text, label) { return h("button", { class: "btn sm", type: "button", onclick: function () { (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject()).then(function () { toast("Copied"); }, function () { toast("Select and copy it manually"); }); } }, label || "Copy"); }

  function viewApp(id) {
    mount(topbar("App", "#/apps"), spinner());
    api("dev/apps/" + id).then(function (a) {
      var dlg = h("dialog");
      var max = Math.max(1, Math.max.apply(null, a.usage.map(function (u) { return u.messages + u.otps; }).concat([1])));
      var bars = h("div", { class: "bars", role: "img", "aria-label": "Messages and codes sent per day" }, a.usage.slice().reverse().map(function (u) { return h("i", { style: "height:" + Math.max(3, Math.round((u.messages + u.otps) / max * 100)) + "%", title: u.day + ": " + u.messages + " messages, " + u.otps + " codes" }); }));
      var keys = h("div", { class: "list" }, a.keys.filter(function (k) { return !k.revoked; }).map(function (k) {
        return h("div", { class: "item" }, [h("div", null, [h("div", { class: "t mono" }, k.prefix + "…" + (k.label ? "  " + k.label : "")), h("div", { class: "s" }, "Created " + fmtDate(k.created) + " · " + (k.last_used ? "last used " + fmtDate(k.last_used) : "never used"))]),
          h("button", { class: "btn sm danger", onclick: function () { if (confirm("Revoke this key? Anything using it stops working immediately.")) api("dev/apps/" + id + "/keys/" + k.id, { method: "DELETE" }).then(function () { toast("Key revoked"); viewApp(id); }).catch(fail); } }, "Revoke")]);
      }));
      function newKey() {
        var lab = h("input", { type: "text", maxlength: "40", id: "kl" }), err = h("p", { class: "err" });
        dlg.replaceChildren(h("form", { onsubmit: function (e) { e.preventDefault(); api("dev/apps/" + id + "/keys", { method: "POST", body: { label: lab.value } }).then(function (k) {
          dlg.replaceChildren(h("div", null, [h("h3", null, "Your new API key"), h("p", { class: "muted" }, "Copy it now and keep it secret. For your safety only a fingerprint is stored, so it can't be shown again."), h("div", { class: "keyshow" }, k.key),
            h("div", { class: "row mt" }, [copyBtn(k.key, "Copy key"), h("button", { class: "btn-primary", onclick: function () { dlg.close(); viewApp(id); } }, "I've saved it")])]));
        }).catch(function (x) { err.textContent = x.message; }); } }, [h("h3", null, "Create API key"), h("div", { class: "field" }, [h("label", { for: "kl" }, "Label (optional)"), lab, h("p", { class: "hint" }, "For example “production” or “staging”.")]), err,
          h("div", { class: "row" }, [h("button", { class: "btn-primary", type: "submit" }, "Create key"), h("button", { class: "btn", type: "button", onclick: function () { dlg.close(); } }, "Cancel")])]));
        dlg.showModal();
      }
      // test console (the key is typed in and never stored)
      var ck = h("input", { type: "password", id: "ck", placeholder: "wt_live_…", autocomplete: "off" }), ct = h("input", { type: "text", id: "ct", placeholder: "@username" }), cx = h("input", { type: "text", id: "cx", value: "Hello from my app!", maxlength: "500" });
      var out = h("pre", { style: "margin-top:12px;min-height:3em" }, "Response will appear here");
      function run(kind) {
        var body = kind === "otp" ? { to: ct.value } : { to: ct.value, text: cx.value, title: "Test", kind: "info" };
        out.textContent = "Sending…";
        fetch("/api/v1/" + (kind === "otp" ? "otp" : "messages"), { method: "POST", headers: { "Authorization": "Bearer " + ck.value.trim(), "Content-Type": "application/json" }, body: JSON.stringify(body) })
          .then(function (r) { return r.text().then(function (t) { var j; try { j = JSON.stringify(JSON.parse(t), null, 2); } catch (e) { j = t; } out.textContent = "HTTP " + r.status + "\n" + j; }); }).catch(function () { out.textContent = "Network error"; });
      }
      var nameIn = h("input", { type: "text", value: a.name, maxlength: "32", id: "nm" }), descIn = h("input", { type: "text", value: a.description, maxlength: "200", id: "ds" }), serr = h("p", { class: "err" });
      mount(topbar(a.name, "#/apps"),
        h("div", { class: "kpis" }, [kpi(a.today.messages, "messages today"), kpi(a.today.otps, "codes today"), kpi(a.today.errors, "errors today")]),
        h("section", { style: "padding:22px 0 0" }, [h("h3", null, "Last 14 days"), bars, h("p", { class: "hint" }, "Free plan: " + a.limits.messages_per_day + " messages and " + a.limits.otps_per_day + " codes per day.")]),
        h("section", { style: "padding:16px 0 0" }, [h("div", { class: "row" }, [h("h3", { class: "grow mb0" }, "API keys"), h("button", { class: "btn-primary sm", onclick: newKey }, "+ Create key")]), h("div", { class: "mt" }, [a.keys.some(function (k) { return !k.revoked; }) ? keys : h("p", { class: "muted" }, "No active keys. Create one to start sending.")])]),
        h("section", { style: "padding:22px 0 0" }, [h("h3", null, "Try it"), h("p", { class: "muted" }, "Send a real test message to a WeTalks user. They must not have blocked services. Your key stays in this page only."),
          h("div", { class: "field" }, [h("label", { for: "ck" }, "API key"), ck]), h("div", { class: "field" }, [h("label", { for: "ct" }, "Send to"), ct]), h("div", { class: "field" }, [h("label", { for: "cx" }, "Message"), cx]),
          h("div", { class: "row" }, [h("button", { class: "btn-primary", onclick: function () { run("msg"); } }, "Send message"), h("button", { class: "btn", onclick: function () { run("otp"); } }, "Send a code")]), out]),
        h("section", { style: "padding:22px 0 0" }, [h("h3", null, "Recent activity"), h("div", { id: "logs" }, [spinner()])]),
        h("section", { style: "padding:22px 0 0" }, [h("h3", null, "App details"), h("div", { class: "field" }, [h("label", { for: "nm" }, "Name"), nameIn]), h("div", { class: "field" }, [h("label", { for: "ds" }, "Description"), descIn]), serr,
          h("p", { class: "hint" }, "Changing the name resets the verified badge."),
          h("div", { class: "row" }, [h("button", { class: "btn", onclick: function () { api("dev/apps/" + id, { method: "PATCH", body: { name: nameIn.value, description: descIn.value } }).then(function () { toast("Saved"); viewApp(id); }).catch(function (x) { serr.textContent = x.message; }); } }, "Save"),
            h("button", { class: "btn danger right", onclick: function () { if (confirm("Delete this app and all its keys? This cannot be undone.")) api("dev/apps/" + id, { method: "DELETE" }).then(function () { location.hash = "#/apps"; }).catch(fail); } }, "Delete app")])]), dlg);
      api("dev/apps/" + id + "/logs").then(function (l) {
        var box = document.getElementById("logs"); if (!box) return;
        box.replaceChildren(l.logs.length ? h("div", null, l.logs.map(function (x) { var ok = /^(200|201)/.test(x.status); return h("div", { class: "logrow" }, [h("span", { class: "badge " + (ok ? "ok" : "bad") }, x.kind), h("span", { class: "mono muted" }, x.status + (x.detail ? " · " + x.detail : "")), h("span", { class: "muted" }, fmtDate(x.time))]); })) : h("p", { class: "muted" }, "Nothing yet. Calls to the API will show up here."));
      }).catch(function () {});
    }).catch(fail);
  }
  function kpi(n, l) { return h("div", { class: "kpi" }, [h("b", null, String(n)), h("span", null, l)]); }

  function fail(e) {
    if (e && (e.message === "signed out" || e.message === "session expired")) return signOut();
    if (e && e.status === 403 && /verify your email/i.test(e.message)) { location.hash = "#/verify"; return; }
    mount(topbar("Something went wrong"), h("div", { class: "card" }, [h("p", null, e && e.message || "Request failed"), h("a", { class: "btn", href: "#/apps" }, "Back to apps")]));
  }

  function route() {
    var r = (location.hash || "#/").replace(/^#/, "");
    if (!S.idToken) { return r === "/signup" ? viewLogin("signup") : viewLogin("signin"); }
    if (!S.verified) { return viewVerify(); }
    var m = r.match(/^\/apps\/(app_[a-f0-9]{12})$/);
    if (m) return viewApp(m[1]);
    if (r === "/login" || r === "/signup") { location.hash = "#/apps"; return; }
    viewApps();
  }
  window.addEventListener("hashchange", route);
  route();
})();
