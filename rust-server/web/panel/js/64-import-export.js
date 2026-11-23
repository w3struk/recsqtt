        function openImportExportDlg() {
            switchImportExportTab('export');
            const st = document.getElementById('ieImportStatus');
            if (st) { st.style.display = 'none'; st.textContent = ''; }
            document.getElementById('importExportDlg').showModal();
        }

        function switchImportExportTab(tab) {
            const btnExp = document.getElementById('btnTabExport');
            const btnImp = document.getElementById('btnTabImport');
            const paneExp = document.getElementById('iePaneExport');
            const paneImp = document.getElementById('iePaneImport');
            if (tab === 'export') {
                btnExp.className = 'btn btn-primary';
                btnImp.className = 'btn btn-ghost';
                btnImp.style.border = '1px solid var(--border)';
                paneExp.style.display = 'flex';
                paneImp.style.display = 'none';
            } else {
                btnImp.className = 'btn btn-primary';
                btnExp.className = 'btn btn-ghost';
                btnExp.style.border = '1px solid var(--border)';
                paneExp.style.display = 'none';
                paneImp.style.display = 'flex';
            }
        }

        async function fetchExportData() {
            const r = await api('/api/export');
            if (!r.ok) throw new Error('Ошибка экспорта: ' + r.statusText);
            const data = await r.json();
            const incClients = document.getElementById('ieOptClients').checked;
            const incSettings = document.getElementById('ieOptSettings').checked;
            const filtered = {
                version: data.version,
                exported_at: data.exported_at
            };
            if (incClients) {
                filtered.clients = data.clients;
                filtered.groups = data.groups;
                filtered.routing_rules = data.routing_rules;
            }
            if (incSettings) {
                filtered.dns = data.dns;
                filtered.dns_provider = data.dns_provider;
                filtered.auto_restart_interval_hours = data.auto_restart_interval_hours;
                filtered.vk_access_token = data.vk_access_token;
                filtered.vk_access_token_user_id = data.vk_access_token_user_id;
                filtered.vk_hashes = data.vk_hashes;
            }
            return filtered;
        }

        async function executeExportDownload() {
            try {
                const data = await fetchExportData();
                const jsonStr = JSON.stringify(data, null, 2);
                const hostIp = window.location.hostname || 'server';
                const safeIp = hostIp.replace(/[^a-zA-Z0-9]/g, '-').replace(/-+/g, '-').replace(/^-|-$/g, '');
                const filename = (safeIp || 'server') + '-config.json';
                const blob = new Blob([jsonStr], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = filename;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                URL.revokeObjectURL(url);
            } catch (e) {
                alert(e.message || 'Ошибка скачивания конфигурации');
            }
        }

        async function executeExportCopy() {
            try {
                const data = await fetchExportData();
                const jsonStr = JSON.stringify(data, null, 2);
                await navigator.clipboard.writeText(jsonStr);
                alert('Конфигурация скопирована в буфер обмена ✓');
            } catch (e) {
                alert(e.message || 'Ошибка копирования');
            }
        }

        function handleImportFile(event) {
            const file = event.target.files && event.target.files[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = async function(e) {
                await processImportJson(e.target.result);
            };
            reader.readAsText(file);
            event.target.value = '';
        }

        async function executeImportFromClipboard() {
            const statusEl = document.getElementById('ieImportStatus');
            statusEl.style.display = 'block';
            statusEl.style.color = 'var(--text-muted)';
            statusEl.textContent = 'Чтение буфера...';
            try {
                const text = await navigator.clipboard.readText();
                if (!text || !text.trim()) {
                    statusEl.style.color = 'var(--danger)';
                    statusEl.textContent = 'Буфер обмена пуст';
                    return;
                }
                await processImportJson(text.trim());
            } catch (e) {
                statusEl.style.color = 'var(--danger)';
                statusEl.textContent = 'Ошибка чтения буфера: ' + (e.message || e);
            }
        }

        async function processImportJson(text) {
            const statusEl = document.getElementById('ieImportStatus');
            statusEl.style.display = 'block';
            let parsed;
            try {
                parsed = JSON.parse(text);
            } catch (e) {
                statusEl.style.color = 'var(--danger)';
                statusEl.textContent = 'Ошибка: невалидный JSON';
                return;
            }
            const incClients = document.getElementById('ieOptClients').checked;
            const incSettings = document.getElementById('ieOptSettings').checked;
            statusEl.style.color = 'var(--text-muted)';
            statusEl.textContent = 'Импортирование...';
            try {
                const r = await api('/api/import', {
                    method: 'POST',
                    body: JSON.stringify({
                        import_clients: incClients,
                        import_settings: incSettings,
                        config: parsed
                    })
                });
                if (!r.ok) {
                    const err = await r.text();
                    throw new Error(err || r.statusText);
                }
                const res = await r.json();
                statusEl.style.color = 'var(--success)';
                statusEl.textContent = 'Успешно! Добавлено новых клиентов: ' + (res.added_clients || 0) + ' ✓';
                if (typeof loadClients === 'function') loadClients();
                if (typeof loadSettings === 'function') loadSettings();
                setTimeout(() => {
                    document.getElementById('importExportDlg').close();
                }, 1500);
            } catch (e) {
                statusEl.style.color = 'var(--danger)';
                statusEl.textContent = 'Ошибка: ' + (e.message || 'Не удалось импортировать');
            }
        }

