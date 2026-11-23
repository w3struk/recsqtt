        async function animateSavedButton(button, normalText) {
            button.classList.add('save-success');
            button.textContent = 'Успешно!';
            await new Promise(resolve => setTimeout(resolve, 700));
            button.classList.remove('save-success', 'saving');
            button.textContent = normalText;
            updateSettingsDirtyState();
        }

        async function saveDnsSettings() {
            const button = document.getElementById('saveDnsBtn');
            const provider = document.getElementById('dns_provider')?.value || '';
            if (!button || button.disabled) return;
            let payload;
            if (provider === 'custom') {
                const value = document.getElementById('dns_manual')?.value || '';
                const addresses = value.split(',');
                const valid = value === value.trim()
                    && !value.includes(' ')
                    && (addresses.length === 1 || addresses.length === 2)
                    && addresses.every(address => {
                        const octets = address.split('.');
                        return octets.length === 4
                            && octets.every(octet => /^[0-9]{1,3}$/.test(octet) && Number(octet) <= 255);
                    });
                if (!valid) {
                    showToast('Введите один или два IPv4-адреса через запятую без пробелов', 'error');
                    return;
                }
                payload = {
                    dns_provider: 'custom',
                    dns_primary: addresses[0],
                    dns_secondary: addresses[1] || ''
                };
            } else {
                payload = { dns_provider: provider };
            }
            button.classList.add('saving'); button.disabled = true;
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify(payload)
            });
            if (!response.ok) {
                button.classList.remove('saving');
                showToast(await response.text(), 'error');
                updateSettingsDirtyState();
                return;
            }
            savedDnsProvider = provider;
            savedManualDns = provider === 'custom'
                ? [payload.dns_primary, payload.dns_secondary].filter(Boolean).join(',')
                : savedManualDns;
            await animateSavedButton(button, 'Применить DNS');
        }
        async function saveAutoRestartInterval() {
            const button = document.getElementById('saveAutoRestartIntervalBtn');
            if (!button || button.disabled) return;
            const hours = Number(document.getElementById('auto_restart_interval').value);
            if (![0, 12, 24, 48, 72].includes(hours)) {
                showToast('Выберите допустимый интервал перезагрузки', 'error');
                return;
            }
            button.classList.add('saving'); button.disabled = true;
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify({ auto_restart_interval_hours: hours })
            });
            if (!response.ok) {
                button.classList.remove('saving');
                showToast(await response.text(), 'error');
                updateSettingsDirtyState();
                return;
            }
            savedAutoRestartInterval = hours;
            await animateSavedButton(button, 'Сохранить интервал');
        }
        async function executeReboot() {
            document.getElementById('rebootDlg').close();
            const response = await api("/api/reboot", {method: "POST"});
            if (!response.ok) {
                showToast("Не удалось подготовить перезагрузку", "error");
                return;
            }
            showToast("Сервер перезапускается...");
            setTimeout(() => location.reload(), 3000);
        }
        async function logout() { await api("/api/logout", {method: "POST"}); sessionStorage.clear(); location.reload(); }

        function switchTab(tabId) {
            document.querySelectorAll('.tab-btn').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-tab') === tabId);
            });
            document.getElementById('monitoring-section').style.display = tabId === 'monitoring' ? 'block' : 'none';
            document.getElementById('clients-section').style.display = tabId === 'clients' ? 'block' : 'none';
            document.getElementById('logs-section').style.display = tabId === 'logs' ? 'block' : 'none';
            document.getElementById('settings-section').style.display = tabId === 'settings' ? 'block' : 'none';
            localStorage.setItem('csqtt-active-tab', tabId);
            if (tabId === 'clients') {
                loadClients();
            }
            if (tabId === 'logs') {
                loadLogs();
            }
        }

