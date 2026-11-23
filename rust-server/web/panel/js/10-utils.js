        async function copyText(text, btn) {
            if (await copyToClipboard(text)) {
                if (btn) {
                    const orig = btn.textContent;
                    btn.textContent = "Скопировано!";
                    setTimeout(() => { btn.textContent = orig; }, 2000);
                }
                showToast("Адрес скопирован в буфер");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }
        function syncThemeColorMeta() {
            const meta = document.getElementById('themeColorMeta');
            if (!meta) return;
            meta.setAttribute('content', getComputedStyle(document.documentElement).getPropertyValue('--bg-color').trim());
        }
        function toggleTheme() {
            const isDark = document.body.parentElement.getAttribute('data-theme') === 'dark';
            document.body.parentElement.setAttribute('data-theme', isDark ? 'light' : 'dark');
            localStorage.setItem('csqtt-theme', isDark ? 'light' : 'dark');
            document.getElementById('themeIcon').innerHTML = isDark
                ? '<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>'
                : '<circle cx="12" cy="12" r="5"></circle><line x1="12" y1="1" x2="12" y2="3"></line><line x1="12" y1="21" x2="12" y2="23"></line><line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line><line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line><line x1="1" y1="12" x2="3" y2="12"></line><line x1="21" y1="12" x2="23" y2="12"></line><line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line><line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>';
            syncThemeColorMeta();
        }
        if (localStorage.getItem('csqtt-theme') === 'dark') { toggleTheme(); }
        syncThemeColorMeta();

        const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

        async function copyToClipboard(text) {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                try {
                    await navigator.clipboard.writeText(text);
                    return true;
                } catch (e) {}
            }
            try {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'absolute';
                ta.style.left = '-9999px';
                ta.style.top = '0';
                ta.setAttribute('readonly', '');
                document.body.appendChild(ta);
                ta.select();
                ta.setSelectionRange(0, 99999);
                const success = document.execCommand('copy');
                document.body.removeChild(ta);
                return success;
            } catch (e) {
                return false;
            }
        }

        let apiNetworkToastShown = false;
        async function api(u, o = {}) {
            try {
                let url = (basePath || '') + u;
                const opts = { credentials: "include", ...o, headers: { "content-type": "application/json", ...(o.headers || {}) } };
                if (!opts.signal && typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
                    opts.signal = AbortSignal.timeout(8000);
                }
                let res = await fetch(url, opts);
                if (res.status === 401) { location.reload(); }
                apiNetworkToastShown = false;
                return res;
            } catch (e) {
                if (e && (e.name === 'AbortError' || e.name === 'TimeoutError')) {
                    throw e;
                }
                if (!apiNetworkToastShown) {
                    apiNetworkToastShown = true;
                    showToast("Ошибка сети", "error");
                }
                throw e;
            }
        }

        const size = n => {
            if (n < 1024) return n + " B";
            if (n < 1048576) return (n / 1024).toFixed(1) + " KB";
            if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
            return (n / 1073741824).toFixed(2) + " GB";
        };

        const compactCounter = input => {
            const count = Number(input);
            if (!Number.isFinite(count) || count <= 0) return "0";
            const units = ["", "K", "M", "G", "T", "P", "E"];
            let value = count;
            let unit = 0;
            while (value >= 1000 && unit < units.length - 1) {
                value /= 1000;
                unit += 1;
            }
            if (value >= 999.5 && unit < units.length - 1) {
                value /= 1000;
                unit += 1;
            }
            const digits = value >= 100 ? 0 : value >= 10 ? 1 : 2;
            return value.toFixed(digits).replace(/\.0+$/, "") + units[unit];
        };

        const uptime = s => {
            const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
            return d > 0 ? d + "д " + h + "ч" : h > 0 ? h + "ч " + m + "м" : m + "м";
        };

