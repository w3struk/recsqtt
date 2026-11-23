        const savedTab = localStorage.getItem('csqtt-active-tab') || 'monitoring';
        switchTab(savedTab);

        let updateIntervalId = null;
        let logsIntervalId = null;
        let nextClientsRefreshAt = 0;
        function startUpdateInterval(ms) {
            if (updateIntervalId) clearInterval(updateIntervalId);
            updateIntervalId = setInterval(() => {
                loadStats();
                if (document.getElementById('clients-section').style.display !== 'none' && Date.now() >= nextClientsRefreshAt) {
                    loadClients();
                    nextClientsRefreshAt = Date.now() + 30000;
                }
            }, ms);
            if (!logsIntervalId) {
                logsIntervalId = setInterval(() => {
                    if (document.getElementById('logs-section').style.display !== 'none') {
                        loadLogs();
                    }
                }, 5000);
            }
        }

        function changeUpdateInterval() {
            const ms = parseInt(document.getElementById('updateIntervalSelect').value, 10);
            localStorage.setItem('csqtt-update-interval', ms);
            startUpdateInterval(ms);
        }

        const dnsProviderSelect = document.getElementById('dns_provider');
        dnsProviderSelect?.addEventListener('change', () => {
            syncDnsControls();
            updateSettingsDirtyState();
        });
        document.getElementById('dns_manual')?.addEventListener('input', updateSettingsDirtyState);
        document.getElementById('auto_restart_interval')?.addEventListener('change', updateSettingsDirtyState);
        const dlBtn = document.getElementById('downloadLogBtn');
        if (dlBtn) dlBtn.href = (basePath || '') + '/api/logs/download';
        updateApiDocsPaths();
        loadStats(); loadSettings(); if (savedTab === 'clients') { loadClients(); } if (savedTab === 'logs') { loadLogs(); }

        const savedInterval = parseInt(localStorage.getItem('csqtt-update-interval'), 10) || 3000;
        const selectElem = document.getElementById('updateIntervalSelect');
        if (selectElem) {
            selectElem.value = savedInterval;
        }
        startUpdateInterval(savedInterval);

        document.querySelectorAll('dialog').forEach(dlg => {
            dlg.addEventListener('mousedown', (e) => {
                dlg._mouseDownOnBackdrop = (e.target === dlg);
            });
            dlg.addEventListener('click', (e) => {
                if (e.target === dlg && dlg._mouseDownOnBackdrop) {
                    const rect = dlg.getBoundingClientRect();
                    const isInDialog = (
                        rect.top <= e.clientY && e.clientY <= rect.top + rect.height &&
                        rect.left <= e.clientX && e.clientX <= rect.left + rect.width
                    );
                    if (!isInDialog) {
                        dlg.close();
                    }
                }
                dlg._mouseDownOnBackdrop = false;
            });
            dlg.addEventListener('close', () => {
                const toastBox = document.getElementById('toastContainer');
                if (toastBox && !document.querySelector('dialog[open]')) {
                    document.body.appendChild(toastBox);
                }
            });
        });

        document.getElementById('linksAndTokensDlg')?.addEventListener('close', () => {
            const cbs = document.querySelectorAll('.hub-client-cb');
            cbs.forEach(cb => cb.checked = false);
            const selectAll = document.getElementById('hubSelectAll');
            if (selectAll) selectAll.checked = false;
            updateHubSelectedCount();
        });

        document.getElementById('groupDlg')?.addEventListener('close', () => {
            if (typeof exitGroupInlineEdit === 'function') exitGroupInlineEdit();
        });

        window.qrSvgCache = {};
        function generateQrSvg(text) {
            if (window.qrSvgCache[text]) {
                return window.qrSvgCache[text];
            }
            try {
                const qr = qrcode(0, 'L');
                qr.addData(text);
                qr.make();
                const svg = qr.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
                window.qrSvgCache[text] = svg;
                return svg;
            } catch (e) {
                return '<div style="color: var(--danger); padding: 16px;">Ошибка генерации QR</div>';
            }
        }

        function openQrModalForClient(password) {
            const client = (window.clientsData || []).find(c => c.password === password) || (typeof activeClient !== 'undefined' && activeClient && activeClient.password === password ? activeClient : null);
            if (!client) return;
            const link = client.link || '';
            if (!link) { showToast('Ссылка клиента недоступна', true); return; }
            const titleEl = document.getElementById('qrDlgTitle');
            if (titleEl) titleEl.textContent = 'QR-код: ' + (client.name || 'Клиент');
            const linkInput = document.getElementById('qrDlgLink');
            if (linkInput) linkInput.value = link;
            const container = document.getElementById('qrCodeContainer');
            if (container) container.innerHTML = generateQrSvg(link);
            document.getElementById('qrDlg').showModal();
        }

        function openQrModalForActiveClient() {
            if (typeof activeClient !== 'undefined' && activeClient) {
                openQrModalForClient(activeClient.password);
            }
        }

        async function copyQrDlgLink(btn) {
            const link = document.getElementById('qrDlgLink')?.value || '';
            if (await copyToClipboard(link)) {
                if (btn) {
                    btn.style.color = 'var(--success)';
                    setTimeout(() => { btn.style.color = ''; }, 1500);
                }
                showToast("Ссылка скопирована");
            } else {
                showToast("Не удалось скопировать", "error");
            }
        }
