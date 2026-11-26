        let savedDnsProvider = 'cloudflare';
        let savedManualDns = '';
        let savedAutoRestartInterval = 0;
        let sharedVkToken = '';
        let sharedVkUserId = '';
        let sharedVkHashes = '';

        function syncDnsControls() {
            const provider = document.getElementById('dns_provider')?.value;
            document.getElementById('dns_selection')?.classList.toggle('is-custom', provider === 'custom');
        }

        function updateSettingsDirtyState() {
            const dnsProvider = document.getElementById('dns_provider')?.value ?? '';
            const manualDns = document.getElementById('dns_manual')?.value ?? '';
            const autoRestartInterval = Number(document.getElementById('auto_restart_interval')?.value ?? 0);
            const dnsButton = document.getElementById('saveDnsBtn');
            const autoRestartButton = document.getElementById('saveAutoRestartIntervalBtn');
            if (dnsButton && !dnsButton.classList.contains('saving')) {
                dnsButton.disabled = dnsProvider === savedDnsProvider
                    && (dnsProvider !== 'custom' || manualDns === savedManualDns);
            }
            if (autoRestartButton && !autoRestartButton.classList.contains('saving')) {
                autoRestartButton.disabled = autoRestartInterval === savedAutoRestartInterval;
            }
        }

        async function loadSettings() {
            let r = await api("/api/settings"); if(!r.ok) return; let x = await r.json();
            const dnsSelect = document.getElementById('dns_provider');
            const providerOptions = new Set(Array.from(dnsSelect?.options ?? []).map(option => option.value));
            savedDnsProvider = providerOptions.has(x.dns_provider) ? x.dns_provider : 'custom';
            savedManualDns = [x.dns_primary, x.dns_secondary].filter(Boolean).join(',');
            savedAutoRestartInterval = [0, 12, 24, 48, 72].includes(Number(x.auto_restart_interval_hours))
                ? Number(x.auto_restart_interval_hours)
                : 0;
            sharedVkToken = x.vk_token || '';
            sharedVkUserId = x.vk_token_user_id || '';
            sharedVkHashes = x.vk_hashes || '';
            dnsSelect.value = savedDnsProvider;
            document.getElementById('dns_manual').value = savedManualDns;
            document.getElementById('auto_restart_interval').value = String(savedAutoRestartInterval);
            syncDnsControls();
            updateSettingsDirtyState();
            loadLocalProxy();
        }

        function openVkTokenSettingsDlg() {
            document.getElementById('vkTokenSettingsInput').value = sharedVkToken;
            document.getElementById('vkTokenUserIdInput').value = sharedVkUserId;
            document.getElementById('vkTokenSettingsDlg').showModal();
        }

        async function saveVkTokenSettings(clear = false) {
            const input = document.getElementById('vkTokenSettingsInput');
            const userIdInput = document.getElementById('vkTokenUserIdInput');
            const token = clear ? '' : input.value.trim();
            const userId = clear ? '' : userIdInput.value.trim();
            if (token && (/\s/.test(token) || token.length > 4096)) {
                showToast('VK токен не должен содержать пробелы и должен быть короче 4097 символов', 'error');
                return;
            }
            if (userId && !/^[0-9]{1,20}$/.test(userId)) {
                showToast('VK user id: только цифры, до 20 символов', 'error');
                return;
            }
            if (userId && !token) {
                showToast('VK user id имеет смысл только вместе с токеном', 'error');
                return;
            }
            const response = await api('/api/settings', {
                method: 'POST', body: JSON.stringify({ vk_token: token, vk_token_user_id: userId })
            });
            if (!response.ok) {
                showToast(await response.text(), 'error');
                return;
            }
            sharedVkToken = token;
            sharedVkUserId = userId;
            input.value = token;
            userIdInput.value = userId;
            document.getElementById('vkTokenSettingsDlg').close();
            if (token && !userId) {
                showToast('Токен сохранён без user id — звонки потеряют владельца при перевыпуске', 'warning');
            } else {
                showToast(token ? 'Общий VK токен сохранён' : 'Общий VK токен очищен');
            }
        }

        function clearVkTokenSettings() {
            saveVkTokenSettings(true);
        }

