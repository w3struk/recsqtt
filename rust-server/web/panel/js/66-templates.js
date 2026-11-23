        let templateDomainsCache = null;
        async function openTemplateDomainsDlg() {
            const listEl = document.getElementById('templateDomainsList');
            const countEl = document.getElementById('templateDomainsCount');
            const filterEl = document.getElementById('templateDomainsFilter');
            if (filterEl) filterEl.value = '';
            document.getElementById('templateDomainsDlg').showModal();
            if (!templateDomainsCache) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 20px;">Загрузка...</div>';
                try {
                    const r = await api('/api/template-domains');
                    if (r.ok) {
                        const txt = await r.text();
                        templateDomainsCache = txt.split('\n')
                            .map(s => s.trim())
                            .filter(s => s && !s.startsWith('#'));
                    } else {
                        templateDomainsCache = null;
                    }
                } catch(e) {
                    templateDomainsCache = null;
                }
            }
            if (!templateDomainsCache) {
                if (countEl) countEl.textContent = '—';
                listEl.innerHTML = '<div style="color: var(--danger); text-align: center; padding: 10px;">Не удалось загрузить список. Откройте диалог ещё раз.</div>';
                return;
            }
            if (countEl) countEl.textContent = templateDomainsCache.length;
            renderTemplateDomainsList(templateDomainsCache);
        }

        function renderTemplateDomainsList(list) {
            const listEl = document.getElementById('templateDomainsList');
            if (!listEl) return;
            if (!list || list.length === 0) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 10px;">Ничего не найдено</div>';
                return;
            }
            listEl.innerHTML = list.map(d => `<div style="padding: 2px 0;">${esc(d)}</div>`).join('');
        }

        let presetAppsCache = null;
        async function openPresetAppsDlg() {
            document.getElementById('presetAppsDlg').showModal();
            if (!presetAppsCache) {
                const loading = '<div style="color: var(--text-muted); text-align: center; padding: 10px;">Загрузка...</div>';
                document.getElementById('presetRedList').innerHTML = loading;
                document.getElementById('presetOrangeList').innerHTML = loading;
                try {
                    const r = await api('/api/preset-apps');
                    if (r.ok) {
                        presetAppsCache = await r.json();
                    }
                } catch(e) {
                    presetAppsCache = null;
                }
            }
            if (!presetAppsCache) {
                // Сетевая ошибка — это не пустой и не битый файл пресета: не
                // отправляем оператора искать ru_apps_preset.json на сервере.
                renderPresetAppsLoadError(
                    'Список пресета не загрузился: проверьте соединение с сервером.');
                return;
            }
            renderPresetAppsGroup('presetRedList', 'presetRedCount', presetAppsCache.red,
                'Уровень пуст: эти приложения поедут через туннель. Проверьте ru_apps_preset.json на сервере.');
            renderPresetAppsGroup('presetOrangeList', 'presetOrangeCount', presetAppsCache.orange,
                'Уровень пуст: нечего возвращать в туннель. Проверьте ru_apps_preset.json на сервере.');
        }

        function renderPresetAppsLoadError(message) {
            for (const [listId, countId] of [['presetRedList', 'presetRedCount'],
                                             ['presetOrangeList', 'presetOrangeCount']]) {
                const countEl = document.getElementById(countId);
                if (countEl) countEl.textContent = '—';
                const listEl = document.getElementById(listId);
                if (listEl) {
                    listEl.innerHTML = '<div style="color: var(--danger); text-align: center; padding: 10px;">' + esc(message) + '</div>';
                }
            }
        }

        function renderPresetAppsGroup(listId, countId, entries, emptyWarning) {
            // A 200 response with an unexpected body (a string or an object)
            // used to print `undefined` and throw on `.map`, leaving "Загрузка..."
            // on screen forever.
            const list = Array.isArray(entries) ? entries : [];
            const countEl = document.getElementById(countId);
            if (countEl) countEl.textContent = list.length;
            const listEl = document.getElementById(listId);
            if (!listEl) return;
            if (list.length === 0) {
                listEl.innerHTML = '<div style="color: var(--danger); text-align: center; padding: 10px;">' + esc(emptyWarning) + '</div>';
                return;
            }
            // `name` is empty for a bare package name in the preset file.
            listEl.innerHTML = list.map(e => {
                const name = e && e.name ? e.name : '';
                const pkg = e && e.package ? e.package : String(e);
                return `<div style="padding: 2px 0;">${esc(name ? name + ' — ' : '')}${esc(pkg)}</div>`;
            }).join('');
        }

        function filterTemplateDomains(query) {
            if (!templateDomainsCache) return;
            const q = query.trim().toLowerCase();
            if (!q) {
                renderTemplateDomainsList(templateDomainsCache);
                return;
            }
            const filtered = templateDomainsCache.filter(d => d.toLowerCase().includes(q));
            renderTemplateDomainsList(filtered);
        }

        let templateIpsCache = null;
        async function openTemplateIpsDlg() {
            const listEl = document.getElementById('templateIpsList');
            const countEl = document.getElementById('templateIpsCount');
            const filterEl = document.getElementById('templateIpsFilter');
            if (filterEl) filterEl.value = '';
            document.getElementById('templateIpsDlg').showModal();
            if (!templateIpsCache) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 20px;">Загрузка...</div>';
                try {
                    const r = await api('/api/template-ips');
                    if (r.ok) {
                        const txt = await r.text();
                        templateIpsCache = txt.split('\n')
                            .map(s => s.trim())
                            .filter(s => s && !s.startsWith('#'));
                    } else {
                        templateIpsCache = null;
                    }
                } catch(e) {
                    templateIpsCache = null;
                }
            }
            if (!templateIpsCache) {
                if (countEl) countEl.textContent = '—';
                listEl.innerHTML = '<div style="color: var(--danger); text-align: center; padding: 10px;">Не удалось загрузить список. Откройте диалог ещё раз.</div>';
                return;
            }
            if (countEl) countEl.textContent = templateIpsCache.length;
            renderTemplateIpsList(templateIpsCache);
        }

        function renderTemplateIpsList(list) {
            const listEl = document.getElementById('templateIpsList');
            if (!listEl) return;
            if (!list || list.length === 0) {
                listEl.innerHTML = '<div style="color: var(--text-muted); text-align: center; padding: 10px;">Ничего не найдено</div>';
                return;
            }
            listEl.innerHTML = list.map(ip => `<div style="padding: 2px 0;">${esc(ip)}</div>`).join('');
        }

        function filterTemplateIps(value) {
            if (!templateIpsCache) return;
            const q = value.trim().toLowerCase();
            if (!q) {
                renderTemplateIpsList(templateIpsCache);
                return;
            }
            const filtered = templateIpsCache.filter(ip => ip.toLowerCase().includes(q));
            renderTemplateIpsList(filtered);
        }

