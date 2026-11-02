![MirraNET Logo](https://raw.githubusercontent.com/LinzN/Stem/master/stem-core/src/main/resources/header-logo.png)

# web-dashboard

> The MirraNET smart home dashboard, hosted by [STEM](https://github.com/LinzN/Stem) itself.

web-dashboard serves the MirraNET web page on the web server of [web-api](https://github.com/LinzN/Web-api).
Page and API share one origin and **one login**: whoever logs in on the page can use the API, nothing reaches the
page or the API without a login. No web server or login of its own (Apache) is needed for the page any more.

---

## Requirements

| Requirement | Version |
|---|---|
| STEM | 1.2.0 or newer |
| Plugins | [web-api](https://github.com/LinzN/Web-api) with login (`auth.enabled: true`, the default) |

---

## Installation

1. Build with `mvn clean package` and copy `target/web-dashboard.jar` into the `plugins` folder of STEM.
2. Restart STEM once; the plugin creates `plugins/web-dashboard/public/`.
3. Copy the web page (export of Bootstrap Studio, with `index.html` at the top) into `plugins/web-dashboard/public/`.
4. The dashboard is at `http://<stem>:8082/` (port of web-api). First login: see "Login" in the web-api README
   (setup code in `plugins/web-api/codes/admin.txt`).

The page calls the API below `/apiv2/` as before; web-api answers there (`server.basePath`).

---

## Configuration (`config.yml`)

| Key | Default | Description |
|---|---|---|
| `web.path` | `/` | Path of the dashboard; `/` also answers every path no API module takes |
| `web.folder` | `public` | Folder with the web page, relative to `plugins/web-dashboard/` or absolute |

The page is not part of the plugin: it is read from this folder, changed files are served right away (no restart).
Hidden files, paths outside the folder and symbolic links that lead out of it are never served.

---

## Behaviour

- Without login the browser is sent to `/login` and comes back to the same page (also to `#system` etc.).
- When the login expires while the page is open, the next API answer `401` opens the login page.
- **Abmelden** in the sidebar ends the session on the server.
- Caching: HTML, `manifest.json` and `serviceWorker.js` are checked on every load (ETag, `304`); files with a
  version hash in the URL (`?h=...`) are cached for a year, others for an hour. Change the `?h=` value in
  `index.html` when you change such a file. Text files are sent gzip compressed.
- Unknown paths below `/apiv2/` are answered as JSON `404`, like the API.

---

## Changes the page needs for the login

- `index.html`: last entry of the sidebar
  `<a class="mn-nav-item mn-nav-logout" href="/login" data-logout><i class="fas fa-sign-out-alt"></i>Abmelden</a>`
  (no `data-page`)
- `assets/js/mirranet-devices.js`: `checkLogin()` (API answer `401` opens `/login?next=...`),
  `credentials: "same-origin"` in both `fetch` calls, logout handler for `[data-logout]` at the end
- `assets/css/mirranet.css` (optional): `.mn-nav-logout { opacity: .75; }`

---

## License

Copyright (c) MirraNET, Niklas Linz. Licensed under the
[GNU Lesser General Public License v3.0](https://www.gnu.org/licenses/lgpl-3.0.html).
